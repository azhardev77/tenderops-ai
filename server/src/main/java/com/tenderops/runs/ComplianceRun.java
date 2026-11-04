package com.tenderops.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "compliance_runs")
public class ComplianceRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tender_version_id", nullable = false)
  private Long tenderVersionId;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "company_data_version", nullable = false)
  private long companyDataVersion;

  @Column(name = "evaluation_context_hash", nullable = false, length = 64,
      columnDefinition = "CHAR(64)")
  private String evaluationContextHash;

  @Column(name = "rule_version", nullable = false, length = 16)
  private String ruleVersion;

  @Column(name = "muse_mode", nullable = false, length = 16)
  private String museMode;

  @Column(name = "sam_mode", nullable = false, length = 16)
  private String samMode;

  @Column(name = "readiness_score", precision = 6, scale = 2)
  private BigDecimal readinessScore;

  @Column(length = 24)
  private String decision;

  @Column(name = "started_at", nullable = false)
  private OffsetDateTime startedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Column(name = "time_saved_percent", precision = 6, scale = 2)
  private BigDecimal timeSavedPercent;

  @Column(name = "manual_minutes", precision = 10, scale = 2)
  private BigDecimal manualMinutes;

  @Column(name = "assisted_minutes", precision = 10, scale = 2)
  private BigDecimal assistedMinutes;

  @Column(name = "summary_text", columnDefinition = "TEXT")
  private String summaryText;

  @Column(name = "grounding_warning", nullable = false)
  private boolean groundingWarning;

  @Column(name = "summary_source", length = 24)
  private String summarySource;

  protected ComplianceRun() {}

  public ComplianceRun(Long tenderVersionId, Long companyId, long companyDataVersion,
      String evaluationContextHash, String ruleVersion, String museMode, String samMode) {
    this.tenderVersionId = tenderVersionId;
    this.companyId = companyId;
    this.companyDataVersion = companyDataVersion;
    this.evaluationContextHash = evaluationContextHash;
    this.ruleVersion = ruleVersion;
    this.museMode = museMode;
    this.samMode = samMode;
    this.startedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public Long getTenderVersionId() { return tenderVersionId; }
  public Long getCompanyId() { return companyId; }
  public long getCompanyDataVersion() { return companyDataVersion; }
  public String getEvaluationContextHash() { return evaluationContextHash; }
  public String getRuleVersion() { return ruleVersion; }
  public String getMuseMode() { return museMode; }
  public String getSamMode() { return samMode; }
  public BigDecimal getReadinessScore() { return readinessScore; }
  public void setReadinessScore(BigDecimal v) { this.readinessScore = v; }
  public String getDecision() { return decision; }
  public void setDecision(String v) { this.decision = v; }
  public OffsetDateTime getStartedAt() { return startedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime v) { this.finishedAt = v; }
  public BigDecimal getTimeSavedPercent() { return timeSavedPercent; }
  public void setTimeSavedPercent(BigDecimal v) { this.timeSavedPercent = v; }
  public BigDecimal getManualMinutes() { return manualMinutes; }
  public void setManualMinutes(BigDecimal v) { this.manualMinutes = v; }
  public BigDecimal getAssistedMinutes() { return assistedMinutes; }
  public void setAssistedMinutes(BigDecimal v) { this.assistedMinutes = v; }
  public String getSummaryText() { return summaryText; }
  public void setSummaryText(String v) { this.summaryText = v; }
  public boolean isGroundingWarning() { return groundingWarning; }
  public void setGroundingWarning(boolean v) { this.groundingWarning = v; }
  public String getSummarySource() { return summarySource; }
  public void setSummarySource(String v) { this.summarySource = v; }
}
