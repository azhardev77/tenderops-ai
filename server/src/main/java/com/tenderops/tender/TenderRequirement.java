package com.tenderops.tender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Types;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "tender_requirements")
public class TenderRequirement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tender_version_id", nullable = false)
  private Long tenderVersionId;

  @Column(name = "clause_ref", nullable = false, length = 32)
  private String clauseRef;

  @Column(nullable = false, length = 64)
  private String category;

  @Column(nullable = false)
  private boolean mandatory = true;

  @Column(name = "mandatory_ambiguous", nullable = false)
  private boolean mandatoryAmbiguous;

  @Column(nullable = false, length = 48)
  private String metric;

  @Column(name = "operator", nullable = false, length = 8)
  private String operator;

  @Column(name = "amount_text", length = 128)
  private String amountText;

  @Column(name = "amount_value", precision = 19, scale = 4)
  private BigDecimal amountValue;

  @Column(nullable = false, length = 8)
  private String currency = "INR";

  @Column(name = "count_value")
  private Integer countValue;

  @Column(name = "lookback_years")
  private Integer lookbackYears;

  @Column(name = "domain_tags", length = 256)
  private String domainTags;

  @Column(name = "certificate_name", length = 64)
  private String certificateName;

  @Column(name = "certificate_level")
  private Integer certificateLevel;

  @Column(name = "condition_tree", columnDefinition = "TEXT")
  private String conditionTree;

  @Column(name = "source_document", nullable = false, length = 512)
  private String sourceDocument;

  @Column(name = "source_page", nullable = false)
  private int sourcePage;

  @Column(name = "source_region_id")
  private Long sourceRegionId;

  @Column(name = "original_quote", nullable = false, columnDefinition = "TEXT")
  private String originalQuote;

  @Column(name = "quote_verified", nullable = false)
  private boolean quoteVerified;

  @Column(precision = 5, scale = 4)
  private BigDecimal confidence;

  @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
  @JdbcTypeCode(Types.CHAR)
  private String fingerprint;

  @Column(name = "amendment_action", length = 16)
  private String amendmentAction;

  @Column(name = "target_clause_ref", length = 32)
  private String targetClauseRef;

  public TenderRequirement() {}

  public Long getId() { return id; }
  public Long getTenderVersionId() { return tenderVersionId; }
  public void setTenderVersionId(Long v) { this.tenderVersionId = v; }
  public String getClauseRef() { return clauseRef; }
  public void setClauseRef(String v) { this.clauseRef = v; }
  public String getCategory() { return category; }
  public void setCategory(String v) { this.category = v; }
  public boolean isMandatory() { return mandatory; }
  public void setMandatory(boolean v) { this.mandatory = v; }
  public boolean isMandatoryAmbiguous() { return mandatoryAmbiguous; }
  public void setMandatoryAmbiguous(boolean v) { this.mandatoryAmbiguous = v; }
  public String getMetric() { return metric; }
  public void setMetric(String v) { this.metric = v; }
  public String getOperator() { return operator; }
  public void setOperator(String v) { this.operator = v; }
  public String getAmountText() { return amountText; }
  public void setAmountText(String v) { this.amountText = v; }
  public BigDecimal getAmountValue() { return amountValue; }
  public void setAmountValue(BigDecimal v) { this.amountValue = v; }
  public String getCurrency() { return currency; }
  public void setCurrency(String v) { this.currency = v; }
  public Integer getCountValue() { return countValue; }
  public void setCountValue(Integer v) { this.countValue = v; }
  public Integer getLookbackYears() { return lookbackYears; }
  public void setLookbackYears(Integer v) { this.lookbackYears = v; }
  public String getDomainTags() { return domainTags; }
  public void setDomainTags(String v) { this.domainTags = v; }
  public String getCertificateName() { return certificateName; }
  public void setCertificateName(String v) { this.certificateName = v; }
  public Integer getCertificateLevel() { return certificateLevel; }
  public void setCertificateLevel(Integer v) { this.certificateLevel = v; }
  public String getConditionTree() { return conditionTree; }
  public void setConditionTree(String v) { this.conditionTree = v; }
  public String getSourceDocument() { return sourceDocument; }
  public void setSourceDocument(String v) { this.sourceDocument = v; }
  public int getSourcePage() { return sourcePage; }
  public void setSourcePage(int v) { this.sourcePage = v; }
  public Long getSourceRegionId() { return sourceRegionId; }
  public void setSourceRegionId(Long v) { this.sourceRegionId = v; }
  public String getOriginalQuote() { return originalQuote; }
  public void setOriginalQuote(String v) { this.originalQuote = v; }
  public boolean isQuoteVerified() { return quoteVerified; }
  public void setQuoteVerified(boolean v) { this.quoteVerified = v; }
  public BigDecimal getConfidence() { return confidence; }
  public void setConfidence(BigDecimal v) { this.confidence = v; }
  public String getFingerprint() { return fingerprint; }
  public void setFingerprint(String v) { this.fingerprint = v; }
  public String getAmendmentAction() { return amendmentAction; }
  public void setAmendmentAction(String v) { this.amendmentAction = v; }
  public String getTargetClauseRef() { return targetClauseRef; }
  public void setTargetClauseRef(String v) { this.targetClauseRef = v; }
}
