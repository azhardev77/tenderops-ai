package com.tenderops.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "compliance_results")
public class ComplianceResult {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(name = "requirement_id", nullable = false)
  private Long requirementId;

  @Column(nullable = false, length = 16)
  private String verdict;

  @Column(name = "reason_code", length = 64)
  private String reasonCode;

  @Column(name = "calculation_trace", nullable = false, columnDefinition = "TEXT")
  private String calculationTrace;

  @Column(name = "copied_from_run_id")
  private Long copiedFromRunId;

  protected ComplianceResult() {}

  public ComplianceResult(Long runId, Long requirementId, String verdict, String reasonCode,
      String calculationTrace, Long copiedFromRunId) {
    this.runId = runId;
    this.requirementId = requirementId;
    this.verdict = verdict;
    this.reasonCode = reasonCode;
    this.calculationTrace = calculationTrace;
    this.copiedFromRunId = copiedFromRunId;
  }

  public Long getId() { return id; }
  public Long getRunId() { return runId; }
  public Long getRequirementId() { return requirementId; }
  public String getVerdict() { return verdict; }
  public String getReasonCode() { return reasonCode; }
  public String getCalculationTrace() { return calculationTrace; }
  public Long getCopiedFromRunId() { return copiedFromRunId; }
}
