package com.tenderops.tender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "tender_versions")
public class TenderVersion {

  public static final String KIND_BASE = "BASE";
  public static final String KIND_CORRIGENDUM = "CORRIGENDUM";
  public static final String KIND_FULL_REVISION = "FULL_REVISION";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tender_id", nullable = false)
  private Long tenderId;

  @Column(name = "version_number", nullable = false)
  private int versionNumber;

  @Column(nullable = false, length = 16)
  private String kind;

  @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
  @JdbcTypeCode(Types.CHAR)
  private String sha256;

  @Column(name = "storage_path", nullable = false, length = 512)
  private String storagePath;

  @Column(name = "page_count", nullable = false)
  private int pageCount;

  @Column(length = 128)
  private String reference;

  @Column(name = "estimated_value", precision = 19, scale = 4)
  private BigDecimal estimatedValue;

  @Column(name = "emd_amount", precision = 19, scale = 4)
  private BigDecimal emdAmount;

  @Column(name = "tender_fee", precision = 19, scale = 4)
  private BigDecimal tenderFee;

  @Column(name = "submission_deadline")
  private OffsetDateTime submissionDeadline;

  protected TenderVersion() {}

  public TenderVersion(Long tenderId, int versionNumber, String kind, String sha256,
      String storagePath, int pageCount) {
    this.tenderId = tenderId;
    this.versionNumber = versionNumber;
    this.kind = kind;
    this.sha256 = sha256;
    this.storagePath = storagePath;
    this.pageCount = pageCount;
  }

  public Long getId() { return id; }
  public Long getTenderId() { return tenderId; }
  public int getVersionNumber() { return versionNumber; }
  public String getKind() { return kind; }
  public String getSha256() { return sha256; }
  public String getStoragePath() { return storagePath; }
  public int getPageCount() { return pageCount; }
  public String getReference() { return reference; }
  public void setReference(String reference) { this.reference = reference; }
  public BigDecimal getEstimatedValue() { return estimatedValue; }
  public void setEstimatedValue(BigDecimal v) { this.estimatedValue = v; }
  public BigDecimal getEmdAmount() { return emdAmount; }
  public void setEmdAmount(BigDecimal v) { this.emdAmount = v; }
  public BigDecimal getTenderFee() { return tenderFee; }
  public void setTenderFee(BigDecimal v) { this.tenderFee = v; }
  public OffsetDateTime getSubmissionDeadline() { return submissionDeadline; }
  public void setSubmissionDeadline(OffsetDateTime v) { this.submissionDeadline = v; }
}
