package com.tenderops.amendment;

import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional amendment-row writes (separate bean: no self-invocation). */
@Service
public class AmendmentTx {

  private final AmendmentRepository amendments;
  private final TenderVersionRepository versions;

  public AmendmentTx(AmendmentRepository amendments, TenderVersionRepository versions) {
    this.amendments = amendments;
    this.versions = versions;
  }

  @Transactional
  public Amendment createForUpload(long tenderId, long newVersionId) {
    TenderVersion next = versions.findById(newVersionId).orElseThrow(
        () -> new IllegalStateException("Version " + newVersionId + " not found"));
    TenderVersion base = AmendmentService.baseVersionOf(
        versions.findByTenderIdOrderByVersionNumber(tenderId),
        next.getVersionNumber());
    return amendments.save(new Amendment(tenderId, base.getId(), newVersionId));
  }

  @Transactional
  public Amendment saveOutcome(long tenderId, long baseVersionId, long newVersionId,
      String diffJson, String prevDecision, String newDecision, BigDecimal scoreDelta) {
    Amendment amendment = amendments.findByNewVersionId(newVersionId)
        .orElse(new Amendment(tenderId, baseVersionId, newVersionId));
    amendment.setDiffJson(diffJson);
    amendment.setPrevDecision(prevDecision);
    amendment.setNewDecision(newDecision);
    amendment.setScoreDelta(scoreDelta);
    return amendments.save(amendment);
  }

  @Transactional(readOnly = true)
  public List<Amendment> history(long tenderId) {
    return new ArrayList<>(amendments.findByTenderIdOrderByIdDesc(tenderId));
  }
}
