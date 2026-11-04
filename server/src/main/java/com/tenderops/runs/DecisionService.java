package com.tenderops.runs;

import com.tenderops.audit.AuditService;
import com.tenderops.web.BadRequestException;
import com.tenderops.web.ConflictException;
import com.tenderops.web.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human approval gate (section 6.8): the system recommendation stays
 * PENDING_HUMAN_APPROVAL until an ADMIN/BID_MANAGER records APPROVED_FOR_BID or
 * REJECTED_NO_BID. Every approval is audit-logged (R11).
 */
@Service
public class DecisionService {

  public static final String PENDING = "PENDING_HUMAN_APPROVAL";
  public static final String APPROVED = "APPROVED_FOR_BID";
  public static final String REJECTED = "REJECTED_NO_BID";

  private final ComplianceRunRepository runs;
  private final BidDecisionRowRepository decisions;
  private final AuditService audit;

  public DecisionService(ComplianceRunRepository runs, BidDecisionRowRepository decisions,
      AuditService audit) {
    this.runs = runs;
    this.decisions = decisions;
    this.audit = audit;
  }

  @Transactional
  public BidDecisionRow approve(long companyId, long userId, long versionId, Long runId,
      String decision) {
    if (!APPROVED.equals(decision) && !REJECTED.equals(decision)) {
      throw new BadRequestException("decision must be APPROVED_FOR_BID or REJECTED_NO_BID");
    }
    ComplianceRun run = resolveRun(versionId, runId);
    if (run.getCompanyId() != companyId) {
      throw new NotFoundException("Run not found");
    }
    BidDecisionRow row = decisions.findByRunId(run.getId())
        .orElseThrow(() -> new NotFoundException("Decision not found"));
    if (!PENDING.equals(row.getApprovalStatus())) {
      throw new ConflictException("Decision was already " + row.getApprovalStatus());
    }
    Map<String, Object> before = Map.of("approvalStatus", row.getApprovalStatus());
    row.setApprovalStatus(decision);
    row.setApprovedBy(userId);
    row.setApprovedAt(OffsetDateTime.now());
    decisions.save(row);
    audit.record(companyId, userId, "APPROVE", "bid_decision", row.getId(), before,
        Map.of("approvalStatus", decision, "recommendation", row.getRecommendation()));
    return row;
  }

  @Transactional(readOnly = true)
  public ComplianceRun resolveRun(long versionId, Long runId) {
    List<ComplianceRun> versionRuns = runs.findByTenderVersionIdOrderByIdDesc(versionId);
    if (versionRuns.isEmpty()) {
      throw new NotFoundException("No compliance run for this version");
    }
    if (runId == null) {
      return versionRuns.get(0);
    }
    return versionRuns.stream().filter(run -> run.getId().equals(runId)).findFirst()
        .orElseThrow(() -> new NotFoundException("Run not found for this version"));
  }
}
