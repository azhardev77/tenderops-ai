package com.tenderops.agent;

import com.tenderops.workflow.EventService;
import com.tenderops.workflow.EventTypes;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional agent writes. Each step persists in the SAME transaction as its
 * AGENT_STEP event row; broadcast happens only after commit (R17).
 */
@Service
public class AgentTx {

  private final AgentRunRepository runs;
  private final AgentStepRepository steps;
  private final EventService events;

  public AgentTx(AgentRunRepository runs, AgentStepRepository steps, EventService events) {
    this.runs = runs;
    this.steps = steps;
    this.events = events;
  }

  @Transactional
  public AgentRun createRun(long runId, Long requirementId, String mode) {
    return runs.save(new AgentRun(runId, requirementId, mode));
  }

  @Transactional
  public AgentStep saveStep(long versionId, long companyId, long agentRunId, int stepNo,
      String toolName, String inputJson, String outputJson, Long latencyMs,
      String errorText) {
    AgentStep step = steps.save(new AgentStep(agentRunId, stepNo, toolName, inputJson,
        outputJson, latencyMs, errorText));
    events.publish(versionId, companyId, EventTypes.AGENT_STEP, "VERIFY", Map.of("agentRunId",
        agentRunId, "stepNo", stepNo, "toolName", toolName, "latencyMs",
        latencyMs == null ? -1L : latencyMs, "error", errorText != null));
    return step;
  }

  @Transactional
  public void finishRun(long agentRunId, String mode, String finalVerdict) {
    AgentRun run = runs.findById(agentRunId)
        .orElseThrow(() -> new IllegalStateException("Agent run not found"));
    run.setMode(mode);
    run.setFinalVerdict(finalVerdict);
    run.setFinishedAt(OffsetDateTime.now());
    runs.save(run);
  }
}
