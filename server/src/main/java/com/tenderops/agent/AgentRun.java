package com.tenderops.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "agent_runs")
public class AgentRun {

  public static final String MODEL_DRIVEN = "MODEL_DRIVEN";
  public static final String FALLBACK_DETERMINISTIC = "FALLBACK_DETERMINISTIC";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(name = "requirement_id")
  private Long requirementId;

  @Column(nullable = false, length = 32)
  private String mode;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Column(name = "final_verdict", length = 16)
  private String finalVerdict;

  protected AgentRun() {}

  public AgentRun(Long runId, Long requirementId, String mode) {
    this.runId = runId;
    this.requirementId = requirementId;
    this.mode = mode;
    this.startedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public Long getRunId() { return runId; }
  public Long getRequirementId() { return requirementId; }
  public String getMode() { return mode; }
  public void setMode(String mode) { this.mode = mode; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime v) { this.finishedAt = v; }
  public String getFinalVerdict() { return finalVerdict; }
  public void setFinalVerdict(String v) { this.finalVerdict = v; }
}
