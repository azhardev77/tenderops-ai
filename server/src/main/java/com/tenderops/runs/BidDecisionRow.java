package com.tenderops.runs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "bid_decisions")
public class BidDecisionRow {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(nullable = false, length = 24)
  private String recommendation;

  @Column(name = "approval_status", nullable = false, length = 32)
  private String approvalStatus;

  @Column(name = "approved_by")
  private Long approvedBy;

  @Column(name = "approved_at")
  private OffsetDateTime approvedAt;

  @Column(columnDefinition = "TEXT")
  private String warnings;

  protected BidDecisionRow() {}

  public BidDecisionRow(Long runId, String recommendation, String approvalStatus,
      String warnings) {
    this.runId = runId;
    this.recommendation = recommendation;
    this.approvalStatus = approvalStatus;
    this.warnings = warnings;
  }

  public Long getId() { return id; }
  public Long getRunId() { return runId; }
  public String getRecommendation() { return recommendation; }
  public String getApprovalStatus() { return approvalStatus; }
  public void setApprovalStatus(String v) { this.approvalStatus = v; }
  public Long getApprovedBy() { return approvedBy; }
  public void setApprovedBy(Long v) { this.approvedBy = v; }
  public OffsetDateTime getApprovedAt() { return approvedAt; }
  public void setApprovedAt(OffsetDateTime v) { this.approvedAt = v; }
  public String getWarnings() { return warnings; }
}
