package com.tenderops.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.security.CurrentUser;
import com.tenderops.workflow.WorkflowJob;
import com.tenderops.workflow.WorkflowJobRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

  private final WorkflowJobRepository jobs;
  private final ObjectMapper mapper;

  public JobController(WorkflowJobRepository jobs, ObjectMapper mapper) {
    this.jobs = jobs;
    this.mapper = mapper;
  }

  public record JobResponse(long id, long versionId, String status, String currentStage,
      Map<String, Object> progress, int attempt, String error) {
  }

  @GetMapping("/{id}")
  public JobResponse get(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    WorkflowJob job = jobs.findById(id)
        .orElseThrow(() -> new NotFoundException("Job not found"));
    current.requireCompany(job.getCompanyId());
    return new JobResponse(job.getId(), job.getTenderVersionId(), job.getStatus(),
        job.getCurrentStage(), progressOf(job), job.getAttempt(), job.getErrorText());
  }

  private Map<String, Object> progressOf(WorkflowJob job) {
    if (job.getProgressJson() == null || job.getProgressJson().isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(job.getProgressJson(),
          new TypeReference<Map<String, Object>>() {
          });
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return Map.of();
    }
  }
}
