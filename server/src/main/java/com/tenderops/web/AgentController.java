package com.tenderops.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.agent.AgentRun;
import com.tenderops.agent.AgentRunRepository;
import com.tenderops.agent.AgentService;
import com.tenderops.agent.AgentStep;
import com.tenderops.agent.AgentStepRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.security.CurrentUser;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded verification agent (section 8). The run executes inline within the POST
 * (local tools are millisecond-fast; the model loop is time-boxed) and the id of
 * the completed run is returned.
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {

  public record AgentRunRequest(Long requirementId) {
  }

  public record AgentRunResponse(long agentRunId) {
  }

  public record StepDto(int stepNo, String toolName, Object input, Object output,
      Long latencyMs, String error) {
  }

  public record AgentTraceResponse(long id, long runId, Long requirementId, String mode,
      String museMode, String finalVerdict, List<StepDto> steps) {
  }

  private final AgentService agents;
  private final AgentRunRepository agentRuns;
  private final AgentStepRepository agentSteps;
  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final ComplianceRunRepository runs;
  private final ObjectMapper mapper;

  public AgentController(AgentService agents, AgentRunRepository agentRuns,
      AgentStepRepository agentSteps, TenderRepository tenders,
      TenderVersionRepository versions, ComplianceRunRepository runs, ObjectMapper mapper) {
    this.agents = agents;
    this.agentRuns = agentRuns;
    this.agentSteps = agentSteps;
    this.tenders = tenders;
    this.versions = versions;
    this.runs = runs;
    this.mapper = mapper;
  }

  @PostMapping("/tender-versions/{id}/agent/run")
  public ResponseEntity<AgentRunResponse> run(@PathVariable long id,
      @RequestBody(required = false) AgentRunRequest body) {
    CurrentUser current = CurrentUser.require();
    TenderVersion version = visibleVersion(id);
    long agentRunId = agents.run(version.getId(), current.companyId(),
        body == null ? null : body.requirementId());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AgentRunResponse(agentRunId));
  }

  @GetMapping("/agent-runs/{id}")
  public AgentTraceResponse trace(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    AgentRun agentRun = agentRuns.findById(id)
        .orElseThrow(() -> new NotFoundException("Agent run not found"));
    ComplianceRun run = runs.findById(agentRun.getRunId())
        .orElseThrow(() -> new NotFoundException("Agent run not found"));
    TenderVersion version = versions.findById(run.getTenderVersionId())
        .orElseThrow(() -> new NotFoundException("Agent run not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Agent run not found"));
    current.requireCompany(tender.getCompanyId());
    List<StepDto> steps = new ArrayList<>();
    for (AgentStep step : agentSteps.findByAgentRunIdOrderByStepNo(agentRun.getId())) {
      steps.add(new StepDto(step.getStepNo(), step.getToolName(), jsonOf(step.getInputJson()),
          jsonOf(step.getOutputJson()), step.getLatencyMs(), step.getErrorText()));
    }
    return new AgentTraceResponse(agentRun.getId(), agentRun.getRunId(),
        agentRun.getRequirementId(), agentRun.getMode(),
        AgentRun.MODEL_DRIVEN.equals(agentRun.getMode()) ? "LIVE" : "FALLBACK",
        agentRun.getFinalVerdict(), steps);
  }

  private TenderVersion visibleVersion(long versionId) {
    CurrentUser current = CurrentUser.require();
    TenderVersion version = versions.findById(versionId)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    current.requireCompany(tender.getCompanyId());
    return version;
  }

  private Object jsonOf(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(json, JsonNode.class);
    } catch (Exception e) {
      return json;
    }
  }
}
