package com.tenderops.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "company_financial_records")
public class FinancialRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "fy_label", nullable = false)
  private String fyLabel;

  @Column(name = "fy_end_date", nullable = false)
  private LocalDate fyEndDate;

  @Column(precision = 19, scale = 4)
  private BigDecimal turnover;

  @Column(name = "net_worth", precision = 19, scale = 4)
  private BigDecimal netWorth;

  @Column(nullable = false)
  private String currency = "INR";

  @Column(name = "is_audited", nullable = false)
  private boolean audited;

  @Column(name = "document_id")
  private Long documentId;

  protected FinancialRecord() {}

  public FinancialRecord(Long companyId, String fyLabel, LocalDate fyEndDate) {
    this.companyId = companyId;
    this.fyLabel = fyLabel;
    this.fyEndDate = fyEndDate;
  }

  public Long getId() { return id; }
  public Long getCompanyId() { return companyId; }
  public String getFyLabel() { return fyLabel; }
  public void setFyLabel(String fyLabel) { this.fyLabel = fyLabel; }
  public LocalDate getFyEndDate() { return fyEndDate; }
  public void setFyEndDate(LocalDate fyEndDate) { this.fyEndDate = fyEndDate; }
  public BigDecimal getTurnover() { return turnover; }
  public void setTurnover(BigDecimal turnover) { this.turnover = turnover; }
  public BigDecimal getNetWorth() { return netWorth; }
  public void setNetWorth(BigDecimal netWorth) { this.netWorth = netWorth; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public boolean isAudited() { return audited; }
  public void setAudited(boolean audited) { this.audited = audited; }
  public Long getDocumentId() { return documentId; }
  public void setDocumentId(Long documentId) { this.documentId = documentId; }
}
