package com.tenderops.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "risk_findings")
public class RiskRow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(nullable = false, length = 16)
  private String severity;

  @Column(name = "risk_code", nullable = false, length = 64)
  private String riskCode;

  @Column(name = "source_clause", length = 32)
  private String sourceClause;

  @Column(name = "evidence_summary", columnDefinition = "TEXT")
  private String evidenceSummary;

  @Column(name = "detection_reason", columnDefinition = "TEXT")
  private String detectionReason;

  @Column(name = "business_impact", columnDefinition = "TEXT")
  private String businessImpact;

  @Column(name = "corrective_action", columnDefinition = "TEXT")
  private String correctiveAction;

  protected RiskRow() {}

  public RiskRow(Long runId, String severity, String riskCode, String sourceClause,
      String evidenceSummary, String detectionReason, String businessImpact,
      String correctiveAction) {
    this.runId = runId;
    this.severity = severity;
    this.riskCode = riskCode;
    this.sourceClause = sourceClause;
    this.evidenceSummary = evidenceSummary;
    this.detectionReason = detectionReason;
    this.businessImpact = businessImpact;
    this.correctiveAction = correctiveAction;
  }

  public Long getId() { return id; }
  public Long getRunId() { return runId; }
  public String getSeverity() { return severity; }
  public String getRiskCode() { return riskCode; }
  public String getSourceClause() { return sourceClause; }
  public String getEvidenceSummary() { return evidenceSummary; }
  public String getDetectionReason() { return detectionReason; }
  public String getBusinessImpact() { return businessImpact; }
  public String getCorrectiveAction() { return correctiveAction; }
}
