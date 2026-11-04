package com.tenderops.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.runs.BidDecisionRow;
import com.tenderops.runs.BidDecisionRowRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.DecisionService;
import com.tenderops.security.CurrentUser;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bid decision reads and the human approval gate (section 6.8). Approval needs
 * ADMIN/BID_MANAGER (TC-SEC-02: VIEWER cannot approve).
 */
@RestController
@RequestMapping("/api/v1")
public class DecisionController {

  public record DecisionResponse(String recommendation, String approvalStatus,
      String readinessScore, List<String> warnings, String decidedAt) {
  }

  public record ApproveRequest(String decision) {
  }

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final DecisionService decisionService;
  private final BidDecisionRowRepository decisions;
  private final ObjectMapper mapper;

  public DecisionController(TenderRepository tenders, TenderVersionRepository versions,
      DecisionService decisionService, BidDecisionRowRepository decisions,
      ObjectMapper mapper) {
    this.tenders = tenders;
    this.versions = versions;
    this.decisionService = decisionService;
    this.decisions = decisions;
    this.mapper = mapper;
  }

  @GetMapping("/tender-versions/{id}/decision")
  public DecisionResponse decision(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    TenderVersion version = visibleVersion(id);
    ComplianceRun run = decisionService.resolveRun(version.getId(), runId);
    BidDecisionRow row = decisions.findByRunId(run.getId())
        .orElseThrow(() -> new NotFoundException("Decision not found"));
    return new DecisionResponse(row.getRecommendation(), row.getApprovalStatus(),
        run.getReadinessScore() == null ? null : run.getReadinessScore().toPlainString(),
        warningsOf(row), String.valueOf(run.getFinishedAt()));
  }

  @PostMapping("/tender-versions/{id}/decision/approve")
  public DecisionResponse approve(@PathVariable long id,
      @RequestBody ApproveRequest body) {
    CurrentUser current = CurrentUser.require();
    if (!current.canApprove()) {
      throw new AccessDeniedException("Approval needs ADMIN or BID_MANAGER");
    }
    TenderVersion version = visibleVersion(id);
    String decision = body == null ? null : body.decision();
    BidDecisionRow row = decisionService.approve(current.companyId(), current.userId(),
        version.getId(), null, decision);
    ComplianceRun run = decisionService.resolveRun(version.getId(), null);
    return new DecisionResponse(row.getRecommendation(), row.getApprovalStatus(),
        run.getReadinessScore() == null ? null : run.getReadinessScore().toPlainString(),
        warningsOf(row), String.valueOf(run.getFinishedAt()));
  }

  private TenderVersion visibleVersion(long versionId) {
    CurrentUser current = CurrentUser.require();
    TenderVersion version = versions.findById(versionId)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    current.requireCompany(tender.getCompanyId());
    return version;
  }

  private List<String> warningsOf(BidDecisionRow row) {
    if (row.getWarnings() == null || row.getWarnings().isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(row.getWarnings(), new TypeReference<List<String>>() {
      });
    } catch (Exception e) {
      return List.of(row.getWarnings());
    }
  }
}
