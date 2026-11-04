package com.tenderops.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_steps")
public class AgentStep {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "agent_run_id", nullable = false)
  private Long agentRunId;

  @Column(name = "step_no", nullable = false)
  private int stepNo;

  @Column(name = "tool_name", nullable = false, length = 64)
  private String toolName;

  @Column(name = "input_json", columnDefinition = "TEXT")
  private String inputJson;

  @Column(name = "output_json", columnDefinition = "TEXT")
  private String outputJson;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(name = "error_text", columnDefinition = "TEXT")
  private String errorText;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected AgentStep() {}

  public AgentStep(Long agentRunId, int stepNo, String toolName, String inputJson,
      String outputJson, Long latencyMs, String errorText) {
    this.agentRunId = agentRunId;
    this.stepNo = stepNo;
    this.toolName = toolName;
    this.inputJson = inputJson;
    this.outputJson = outputJson;
    this.latencyMs = latencyMs;
    this.errorText = errorText;
    this.createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public Long getAgentRunId() { return agentRunId; }
  public int getStepNo() { return stepNo; }
  public String getToolName() { return toolName; }
  public String getInputJson() { return inputJson; }
  public String getOutputJson() { return outputJson; }
  public Long getLatencyMs() { return latencyMs; }
  public String getErrorText() { return errorText; }
}
