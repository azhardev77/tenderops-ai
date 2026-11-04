package com.tenderops.workflow;

import com.tenderops.ai.ExtractionService;
import com.tenderops.amendment.AmendmentService;
import com.tenderops.compliance.ComplianceOutcome;
import com.tenderops.compliance.ComplianceService;
import com.tenderops.compliance.EvaluatedRequirement;
import com.tenderops.compliance.RiskFinding;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.document.DocumentPage;
import com.tenderops.document.DocumentPageRepository;
import com.tenderops.document.StorageService;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.RunService;
import com.tenderops.runs.SummaryService;
import com.tenderops.security.ResourceNotVisibleException;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.RegionContentService;
import com.tenderops.visual.VisualDetectionService;
import com.tenderops.visual.VisualRegion;
import com.tenderops.web.JobAlreadyActiveException;
import com.tenderops.web.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pipeline job runner (FULL: VALIDATE..DECIDE; REVERIFY: VERIFY..DECIDE).
 * At most one active job per tender version (R17); transient stage failures retry.
 */
@Service
public class WorkflowService {

  public static final String KIND_FULL = "FULL";
  public static final String KIND_REVERIFY = "REVERIFY";
  public static final String KIND_AMENDMENT = "AMENDMENT";

  private static final List<String> FULL_STAGES =
      List.of("VALIDATE", "PARSE", "RENDER", "VISUAL", "EXTRACT", "VERIFY", "RISK",
          "DECIDE");
  private static final List<String> REVERIFY_STAGES = List.of("VERIFY", "RISK", "DECIDE");
  private static final List<String> AMENDMENT_STAGES =
      List.of("VALIDATE", "PARSE", "RENDER", "VISUAL", "EXTRACT", "AMENDMENT");

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowService.class);

  private final WorkflowJobRepository jobs;
  private final TenderVersionRepository versions;
  private final TenderRepository tenders;
  private final DocumentPageRepository pages;
  private final VisualDetectionService visual;
  private final RegionContentService content;
  private final ExtractionService extraction;
  private final ComplianceService compliance;
  private final RunService runs;
  private final SummaryService summaries;
  private final ComplianceResultRepository storedResults;
  private final TenderRequirementRepository requirements;
  private final StorageService storage;
  private final EventService events;
  private final WorkflowTx tx;
  private final AmendmentService amendments;
  private final TenderopsProperties properties;
  private final Clock clock;
  private final Map<Long, Object> versionLocks = new ConcurrentHashMap<>();

  @SuppressWarnings("checkstyle:ParameterNumber")
  public WorkflowService(WorkflowJobRepository jobs, TenderVersionRepository versions,
      TenderRepository tenders, DocumentPageRepository pages, VisualDetectionService visual,
      RegionContentService content, ExtractionService extraction,
      ComplianceService compliance, RunService runs, SummaryService summaries,
      ComplianceResultRepository storedResults, TenderRequirementRepository requirements,
      StorageService storage, EventService events, WorkflowTx tx,
      AmendmentService amendments, TenderopsProperties properties, Clock clock) {
    this.jobs = jobs;
    this.versions = versions;
    this.tenders = tenders;
    this.pages = pages;
    this.visual = visual;
    this.content = content;
    this.extraction = extraction;
    this.compliance = compliance;
    this.runs = runs;
    this.summaries = summaries;
    this.storedResults = storedResults;
    this.requirements = requirements;
    this.storage = storage;
    this.events = events;
    this.tx = tx;
    this.amendments = amendments;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public long startJob(long versionId, long companyId, String kind) {
    if (!KIND_FULL.equals(kind) && !KIND_REVERIFY.equals(kind)
        && !KIND_AMENDMENT.equals(kind)) {
      throw new IllegalArgumentException("Unknown job kind: " + kind);
    }
    TenderVersion version = versions.findById(versionId)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    if (tender.getCompanyId() != companyId) {
      throw new ResourceNotVisibleException();
    }
    synchronized (lockFor(versionId)) {
      boolean active = !jobs.findByTenderVersionIdAndStatusIn(versionId,
          List.of("QUEUED", "RUNNING")).isEmpty();
      if (active) {
        throw new JobAlreadyActiveException(versionId);
      }
      return jobs.save(new WorkflowJob(versionId, companyId, kind, "QUEUED")).getId();
    }
  }

  @Async("workflowExecutor")
  public void launchAsync(long jobId) {
    try {
      executeJob(jobId);
    } catch (Exception e) {
      LOG.warn("Job {} failed: {}", jobId, e.getMessage());
    }
  }

  public void executeJob(long jobId) {
    WorkflowJob job = jobs.findById(jobId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    List<String> stages = KIND_REVERIFY.equals(job.getKind()) ? REVERIFY_STAGES
        : KIND_AMENDMENT.equals(job.getKind()) ? AMENDMENT_STAGES : FULL_STAGES;
    RunState state = new RunState();
    int index = 0;
    for (String stage : stages) {
      index++;
      tx.beginStage(jobId, stage);
      try {
        RetryPolicy.execute(() -> {
          runStage(jobId, stage, state);
          return null;
        }, RetryPolicy.MAX_ATTEMPTS, RetryPolicy::isTransient);
      } catch (Exception e) {
        tx.failJob(jobId, stage, message(e));
        if (e instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new RuntimeException(e);
      }
      tx.completeStage(jobId, stage, index * 100 / stages.size());
    }
    tx.finishJob(jobId);
  }

  /** Crash recovery: stale RUNNING jobs re-queue (bounded) or fail honestly. */
  @Transactional
  public int recoverStaleJobs() {
    List<WorkflowJob> running = jobs.findByStatus("RUNNING");
    for (WorkflowJob job : running) {
      job.setAttempt(job.getAttempt() + 1);
      if (job.getAttempt() >= 3) {
        job.setStatus("FAILED");
        job.setFinishedAt(OffsetDateTime.now());
        job.setErrorText("Job stalled and exceeded recovery attempts");
        jobs.save(job);
        events.publish(job.getTenderVersionId(), job.getCompanyId(),
            EventTypes.JOB_FAILED, job.getCurrentStage(),
            Map.of("jobId", job.getId(), "reason", "stalled"));
      } else {
        job.setStatus("QUEUED");
        jobs.save(job);
      }
    }
    return running.size();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverOnStartup() {
    int recovered = recoverStaleJobs();
    if (recovered > 0) {
      LOG.info("Recovered {} stale workflow job(s)", recovered);
    }
  }

  private void runStage(long jobId, String stage, RunState state) throws Exception {
    WorkflowJob job = jobs.findById(jobId)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    TenderVersion version = versions.findById(job.getTenderVersionId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    switch (stage) {
      case "VALIDATE" -> {
        // Existence and company scoping were verified at startJob; nothing to redo.
      }
      case "PARSE" -> tx.emitParsed(jobId);
      case "RENDER" -> tx.renderPages(jobId);
      case "VISUAL" -> visualStage(job, version, state);
      case "EXTRACT" -> extractStage(job, version, state);
      case "VERIFY" -> verifyStage(job, version, state);
      case "RISK" -> riskStage(job, version, state);
      case "DECIDE" -> decideStage(job, version, state);
      case "AMENDMENT" -> amendments.processAmendment(job.getTenderVersionId(),
          jobId, state.museMode, state.samMode);
      default -> throw new IllegalStateException("Unknown stage: " + stage);
    }
  }

  private void visualStage(WorkflowJob job, TenderVersion version, RunState state)
      throws Exception {
    long start = System.currentTimeMillis();
    VisualDetectionService.DetectResult detect = visual.detectVersion(version);
    long latency = System.currentTimeMillis() - start;
    state.samMode = detect.samMode();
    Map<Long, DocumentPage> byId = new HashMap<>();
    for (DocumentPage page :
        pages.findByTenderVersionIdOrderByPageNo(version.getId())) {
      byId.put(page.getId(), page);
    }
    byte[] pdf = storage.load(version.getStoragePath());
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      for (VisualRegion region : detect.regions()) {
        DocumentPage page = byId.get(region.getPageId());
        if (page == null) {
          continue;
        }
        try {
          if (VisualRegion.OFFICIAL_STAMP.equals(region.getRegionType())) {
            content.ensureStampContent(version, page, region, doc);
          } else if (VisualRegion.TABLE_REGION.equals(region.getRegionType())) {
            content.ensureTableContent(version, page, region, doc);
          }
        } catch (Exception e) {
          LOG.warn("Region content failed for region {}: {}", region.getId(),
              e.toString());
        }
      }
    }
    for (VisualRegion region : detect.regions()) {
      DocumentPage page = byId.get(region.getPageId());
      events.publish(version.getId(), job.getCompanyId(), EventTypes.REGION_DETECTED,
          "VISUAL", Map.of("jobId", job.getId(), "regionId", region.getId(), "pageNo",
              page == null ? -1 : page.getPageNo(), "regionType",
              region.getRegionType(), "source", region.getSource()));
    }
    events.publish(version.getId(), job.getCompanyId(), EventTypes.AI_CALL, "VISUAL",
        Map.of("jobId", job.getId(), "component", "SAM", "model",
            properties.metaApi().samModel(), "status", "OK", "latencyMs", latency,
            "attempt", 1, "aiMode", state.samMode));
  }

  private void extractStage(WorkflowJob job, TenderVersion version, RunState state) {
    List<TenderRequirement> existing =
        requirements.findByTenderVersionIdOrderById(version.getId());
    if (!existing.isEmpty()) {
      // Idempotent re-run: stored results reference these rows (FK), and the same
      // PDF bytes always yield the same set, so never re-extract over them.
      // No AI call happens here; museMode keeps its FALLBACK default.
      for (TenderRequirement requirement : existing) {
        events.publish(version.getId(), job.getCompanyId(),
            EventTypes.REQUIREMENT_EXTRACTED, "EXTRACT", Map.of("jobId", job.getId(),
                "requirementId", requirement.getId(), "clauseRef",
                requirement.getClauseRef(), "metric", requirement.getMetric()));
      }
      return;
    }
    long start = System.currentTimeMillis();
    ExtractionService.ExtractResult ext = extraction.extractVersion(version);
    long latency = System.currentTimeMillis() - start;
    state.museMode = ext.museMode();
    for (TenderRequirement requirement : ext.requirements()) {
      events.publish(version.getId(), job.getCompanyId(),
          EventTypes.REQUIREMENT_EXTRACTED, "EXTRACT", Map.of("jobId", job.getId(),
              "requirementId", requirement.getId(), "clauseRef",
              requirement.getClauseRef(), "metric", requirement.getMetric()));
    }
    events.publish(version.getId(), job.getCompanyId(), EventTypes.AI_CALL, "EXTRACT",
        Map.of("jobId", job.getId(), "component", "MUSE", "model",
            properties.metaApi().museModel(), "status", "OK", "latencyMs", latency,
            "attempt", 1, "aiMode", state.museMode));
  }

  private void verifyStage(WorkflowJob job, TenderVersion version, RunState state) {
    if (!TenderVersion.KIND_BASE.equals(version.getKind())) {
      // Amended versions re-verify the merged selection (base + amendment rows):
      // the delta rows alone are not a coherent tender. The merge persists the
      // run, risks, decision and summary itself, so RISK/DECIDE stand down.
      state.runId = amendments.processAmendment(version.getId(), job.getId(),
          state.museMode, state.samMode);
      state.merged = true;
      return;
    }
    ComplianceOutcome outcome =
        compliance.evaluate(version.getId(), job.getCompanyId(), clock);
    long runId = runs.persistRun(version.getId(), job.getCompanyId(), outcome,
        state.museMode, state.samMode);
    state.outcome = outcome;
    state.runId = runId;
    for (EvaluatedRequirement result : outcome.results()) {
      events.publish(version.getId(), job.getCompanyId(), EventTypes.VERDICT_COMPUTED,
          "VERIFY", Map.of("jobId", job.getId(), "runId", runId, "requirementId",
              result.requirementId(), "clauseRef", result.clauseRef(), "verdict",
              result.verdict().name(), "reasonCode", result.reasonCode()));
    }
  }

  private void riskStage(WorkflowJob job, TenderVersion version, RunState state) {
    if (state.merged) {
      return;
    }
    for (RiskFinding finding : state.outcome.risks()) {
      Map<String, Object> payload = new HashMap<>();
      payload.put("jobId", job.getId());
      payload.put("runId", state.runId);
      payload.put("severity", finding.severity());
      payload.put("riskCode", finding.riskCode());
      payload.put("sourceClause", finding.sourceClause());
      events.publish(version.getId(), job.getCompanyId(), EventTypes.RISK_RAISED,
          "RISK", payload);
    }
  }

  private void decideStage(WorkflowJob job, TenderVersion version, RunState state) {
    if (state.merged) {
      return;
    }
    summaries.generateAndAttach(state.runId);
    List<ComplianceResult> rows = storedResults.findByRunIdOrderById(state.runId);
    Map<Long, TenderRequirement> byId = new HashMap<>();
    for (TenderRequirement requirement :
        requirements.findByTenderVersionIdOrderById(version.getId())) {
      byId.put(requirement.getId(), requirement);
    }
    int reviewItems = RunService.countReviewItems(rows, byId);
    long processingMs =
        Duration.between(job.getCreatedAt(), OffsetDateTime.now()).toMillis();
    runs.finishRun(state.runId, processingMs, reviewItems,
        properties.timeSaved().baselineMinutesPerPage(),
        properties.timeSaved().reviewMinutesPerItem());
    events.publish(version.getId(), job.getCompanyId(), EventTypes.DECISION_UPDATED,
        "DECIDE", Map.of("jobId", job.getId(), "runId", state.runId, "decision",
            state.outcome.decision().recommendation(), "score",
            state.outcome.readinessScore().toPlainString()));
  }

  private Object lockFor(long versionId) {
    return versionLocks.computeIfAbsent(versionId, id -> new Object());
  }

  private static String message(Exception e) {
    String text = e.getMessage() == null ? e.toString() : e.getMessage();
    return text.length() <= 2000 ? text : text.substring(0, 2000);
  }

  private static class RunState {
    String museMode = "FALLBACK";
    String samMode = "FALLBACK";
    long runId;
    ComplianceOutcome outcome;
    boolean merged;
  }
}
