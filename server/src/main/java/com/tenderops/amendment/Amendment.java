package com.tenderops.amendment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "amendments")
public class Amendment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tender_id", nullable = false)
  private Long tenderId;

  @Column(name = "base_version_id", nullable = false)
  private Long baseVersionId;

  @Column(name = "new_version_id", nullable = false)
  private Long newVersionId;

  @Column(name = "diff_json", nullable = false, columnDefinition = "TEXT")
  private String diffJson;

  @Column(name = "prev_decision", length = 24)
  private String prevDecision;

  @Column(name = "new_decision", length = 24)
  private String newDecision;

  @Column(name = "score_delta", precision = 6, scale = 2)
  private BigDecimal scoreDelta;

  protected Amendment() {}

  public Amendment(Long tenderId, Long baseVersionId, Long newVersionId) {
    this.tenderId = tenderId;
    this.baseVersionId = baseVersionId;
    this.newVersionId = newVersionId;
    this.diffJson = "{}";
  }

  public Long getId() { return id; }
  public Long getTenderId() { return tenderId; }
  public Long getBaseVersionId() { return baseVersionId; }
  public Long getNewVersionId() { return newVersionId; }
  public String getDiffJson() { return diffJson; }
  public void setDiffJson(String v) { this.diffJson = v; }
  public String getPrevDecision() { return prevDecision; }
  public void setPrevDecision(String v) { this.prevDecision = v; }
  public String getNewDecision() { return newDecision; }
  public void setNewDecision(String v) { this.newDecision = v; }
  public BigDecimal getScoreDelta() { return scoreDelta; }
  public void setScoreDelta(BigDecimal v) { this.scoreDelta = v; }
}
