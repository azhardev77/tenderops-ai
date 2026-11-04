package com.tenderops.tender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tenders")
public class Tender {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(nullable = false, length = 512)
  private String title;

  @Column(length = 128)
  private String reference;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected Tender() {}

  public Tender(Long companyId, String title, String reference) {
    this.companyId = companyId;
    this.title = title;
    this.reference = reference;
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = this.createdAt;
  }

  public Long getId() { return id; }
  public Long getCompanyId() { return companyId; }
  public String getTitle() { return title; }
  public String getReference() { return reference; }
  public void setReference(String reference) { this.reference = reference; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
