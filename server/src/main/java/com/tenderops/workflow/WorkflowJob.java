package com.tenderops.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "workflow_jobs")
public class WorkflowJob {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tender_version_id", nullable = false)
  private Long tenderVersionId;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(nullable = false, length = 16)
  private String kind = "FULL";

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "current_stage", length = 16)
  private String currentStage;

  @Column(name = "progress_json", columnDefinition = "TEXT")
  private String progressJson;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "error_text", columnDefinition = "TEXT")
  private String errorText;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  protected WorkflowJob() {}

  public WorkflowJob(Long tenderVersionId, Long companyId, String kind, String status) {
    this.tenderVersionId = tenderVersionId;
    this.companyId = companyId;
    this.kind = kind;
    this.status = status;
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public Long getTenderVersionId() { return tenderVersionId; }
  public Long getCompanyId() { return companyId; }
  public String getKind() { return kind; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; touch(); }
  public String getCurrentStage() { return currentStage; }
  public void setCurrentStage(String v) { this.currentStage = v; touch(); }
  public String getProgressJson() { return progressJson; }
  public void setProgressJson(String v) { this.progressJson = v; touch(); }
  public int getAttempt() { return attempt; }
  public void setAttempt(int v) { this.attempt = v; touch(); }
  public String getErrorText() { return errorText; }
  public void setErrorText(String v) { this.errorText = v; touch(); }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public OffsetDateTime getFinishedAt() { return finishedAt; }
  public void setFinishedAt(OffsetDateTime v) { this.finishedAt = v; touch(); }

  private void touch() {
    this.updatedAt = OffsetDateTime.now();
  }
}
