package com.tenderops.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "company_documents")
public class CompanyDocument {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "file_name", nullable = false)
  private String fileName;

  @Column(name = "storage_path", nullable = false)
  private String storagePath;

  @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
  @JdbcTypeCode(Types.CHAR)
  private String sha256;

  @Column(name = "mime_type", nullable = false)
  private String mimeType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "is_demo", nullable = false)
  private boolean demo;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected CompanyDocument() {}

  public CompanyDocument(Long companyId, String fileName, String storagePath, String sha256,
      String mimeType, long sizeBytes, boolean demo) {
    this.companyId = companyId;
    this.fileName = fileName;
    this.storagePath = storagePath;
    this.sha256 = sha256;
    this.mimeType = mimeType;
    this.sizeBytes = sizeBytes;
    this.demo = demo;
    this.createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public Long getCompanyId() { return companyId; }
  public String getFileName() { return fileName; }
  public String getStoragePath() { return storagePath; }
  public String getSha256() { return sha256; }
  public String getMimeType() { return mimeType; }
  public long getSizeBytes() { return sizeBytes; }
  public boolean isDemo() { return demo; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
