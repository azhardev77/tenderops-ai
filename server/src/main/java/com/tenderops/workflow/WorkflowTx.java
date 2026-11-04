package com.tenderops.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenderops.document.DocumentPage;
import com.tenderops.document.DocumentPageRepository;
import com.tenderops.document.PageRenderer;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional job-state steps. Each step commits job state together with the events
 * it describes, so broadcasts after commit always match persisted state.
 */
@Service
public class WorkflowTx {

  private final WorkflowJobRepository jobs;
  private final EventService publisher;
  private final TenderVersionRepository versions;
  private final DocumentPageRepository pages;
  private final PageRenderer renderer;
  private final ObjectMapper mapper;

  public WorkflowTx(WorkflowJobRepository jobs, EventService publisher,
      TenderVersionRepository versions, DocumentPageRepository pages, PageRenderer renderer,
      ObjectMapper mapper) {
    this.jobs = jobs;
    this.publisher = publisher;
    this.versions = versions;
    this.pages = pages;
    this.renderer = renderer;
    this.mapper = mapper;
  }

  @Transactional
  public void beginStage(long jobId, String stage) {
    WorkflowJob job = load(jobId);
    job.setStatus("RUNNING");
    job.setCurrentStage(stage);
    job.setProgressJson(withStage(job.getProgressJson(), stage, "RUNNING", 0));
    jobs.save(job);
    publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
        EventTypes.STAGE_STARTED, stage, Map.of("jobId", jobId, "stage", stage));
  }

  @Transactional
  public void completeStage(long jobId, String stage, int percent) {
    WorkflowJob job = load(jobId);
    job.setProgressJson(withStage(job.getProgressJson(), stage, "COMPLETED", percent));
    jobs.save(job);
    publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
        EventTypes.STAGE_COMPLETED, stage,
        Map.of("jobId", jobId, "stage", stage, "percent", percent));
  }

  @Transactional
  public void progress(long jobId, String stage, int done, int total) {
    WorkflowJob job = load(jobId);
    int percent = total <= 0 ? 100 : done * 100 / total;
    job.setProgressJson(withStage(job.getProgressJson(), stage, "RUNNING", percent));
    jobs.save(job);
    publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
        EventTypes.STAGE_PROGRESS, stage,
        Map.of("jobId", jobId, "stage", stage, "done", done, "total", total));
  }

  @Transactional
  public void failJob(long jobId, String stage, String error) {
    WorkflowJob job = load(jobId);
    job.setStatus("FAILED");
    job.setErrorText(error);
    job.setFinishedAt(OffsetDateTime.now());
    job.setProgressJson(withStage(job.getProgressJson(), stage, "FAILED", 0));
    jobs.save(job);
    publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
        EventTypes.STAGE_FAILED, stage, Map.of("jobId", jobId, "stage", stage,
            "error", error == null ? "unknown" : error));
    publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
        EventTypes.JOB_FAILED, stage,
        Map.of("jobId", jobId, "error", error == null ? "unknown" : error));
  }

  @Transactional
  public void finishJob(long jobId) {
    WorkflowJob job = load(jobId);
    job.setStatus("COMPLETED");
    job.setFinishedAt(OffsetDateTime.now());
    jobs.save(job);
    publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
        EventTypes.JOB_COMPLETED, job.getCurrentStage(), Map.of("jobId", jobId));
  }

  /** PARSE fan-out: one PAGE_PARSED per stored page, in page order. */
  @Transactional
  public void emitParsed(long jobId) {
    WorkflowJob job = load(jobId);
    List<DocumentPage> rows =
        pages.findByTenderVersionIdOrderByPageNo(job.getTenderVersionId());
    int index = 0;
    for (DocumentPage page : rows) {
      index++;
      publisher.publish(job.getTenderVersionId(), job.getCompanyId(),
          EventTypes.PAGE_PARSED, "PARSE", Map.of("jobId", jobId, "pageNo",
              page.getPageNo(), "classification", page.getClassification(),
              "needsHumanReview", page.isNeedsHumanReview()));
      progress(jobId, "PARSE", index, rows.size());
    }
  }

  /** RENDER: ensures every page PNG exists (render failures flag review, never throw). */
  @Transactional
  public void renderPages(long jobId) {
    WorkflowJob job = load(jobId);
    TenderVersion version = versions.findById(job.getTenderVersionId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    List<DocumentPage> rows =
        pages.findByTenderVersionIdOrderByPageNo(job.getTenderVersionId());
    int index = 0;
    for (DocumentPage page : rows) {
      index++;
      renderer.ensureRendered(version, page);
      progress(jobId, "RENDER", index, rows.size());
    }
  }

  private WorkflowJob load(long jobId) {
    return jobs.findById(jobId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
  }

  private String withStage(String current, String stage, String status, int percent) {
    ObjectNode root;
    try {
      JsonNode parsed =
          current == null || current.isBlank() ? null : mapper.readTree(current);
      root = parsed instanceof ObjectNode node ? node : mapper.createObjectNode();
    } catch (JsonProcessingException e) {
      root = mapper.createObjectNode();
    }
    ObjectNode stages;
    if (root.get("stages") instanceof ObjectNode node) {
      stages = node;
    } else {
      stages = mapper.createObjectNode();
      root.set("stages", stages);
    }
    ObjectNode entry = mapper.createObjectNode();
    entry.put("status", status);
    entry.put("percent", percent);
    stages.set(stage, entry);
    root.put("currentStage", stage);
    try {
      return mapper.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      String quote = String.valueOf((char) 34);
      return "{" + quote + "currentStage" + quote + ":" + quote + stage + quote + "}";
    }
  }
}
