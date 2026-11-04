package com.tenderops.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "workflow_events")
public class WorkflowEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long seq;

  @Column(name = "tender_version_id", nullable = false)
  private Long tenderVersionId;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(nullable = false, length = 32)
  private String type;

  @Column(length = 16)
  private String stage;

  @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
  private String payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected WorkflowEvent() {}

  public WorkflowEvent(Long tenderVersionId, Long companyId, String type, String stage,
      String payload) {
    this.tenderVersionId = tenderVersionId;
    this.companyId = companyId;
    this.type = type;
    this.stage = stage;
    this.payload = payload;
    this.createdAt = OffsetDateTime.now();
  }

  public Long getSeq() { return seq; }
  public Long getTenderVersionId() { return tenderVersionId; }
  public Long getCompanyId() { return companyId; }
  public String getType() { return type; }
  public String getStage() { return stage; }
  public String getPayload() { return payload; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
