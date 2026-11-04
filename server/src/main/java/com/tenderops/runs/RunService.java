package com.tenderops.runs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.compliance.ComplianceOutcome;
import com.tenderops.compliance.Decision;
import com.tenderops.compliance.EvaluatedRequirement;
import com.tenderops.compliance.RiskFinding;
import com.tenderops.compliance.TimeSavedCalculator;
import com.tenderops.compliance.TimeSavedResult;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.NotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists in-memory compliance outcomes to runs/results/risks/decision rows. */
@Service
public class RunService {

  private final ComplianceRunRepository runs;
  private final ComplianceResultRepository results;
  private final RiskRowRepository risks;
  private final BidDecisionRowRepository decisions;
  private final TenderVersionRepository versions;
  private final ObjectMapper mapper;

  public RunService(ComplianceRunRepository runs, ComplianceResultRepository results,
      RiskRowRepository risks, BidDecisionRowRepository decisions,
      TenderVersionRepository versions, ObjectMapper mapper) {
    this.runs = runs;
    this.results = results;
    this.risks = risks;
    this.decisions = decisions;
    this.versions = versions;
    this.mapper = mapper;
  }

  @Transactional
  public long persistRun(long versionId, long companyId, ComplianceOutcome outcome,
      String museMode, String samMode) {
    return persistRunWithCopies(versionId, companyId, outcome.results(),
        outcome.decision(), outcome.readinessScore(), outcome.risks(),
        outcome.contextHash(), outcome.ruleVersion(), outcome.companyDataVersion(),
        museMode, samMode, Map.of());
  }

  @Transactional
  public long persistRunWithCopies(long versionId, long companyId,
      List<EvaluatedRequirement> outcome, Decision decision, BigDecimal score,
      List<RiskFinding> riskList, String contextHash, String ruleVersion,
      long companyDataVersion, String museMode, String samMode,
      Map<Long, Long> copiedFrom) {
    ComplianceRun run = new ComplianceRun(versionId, companyId, companyDataVersion,
        contextHash, ruleVersion, museMode, samMode);
    run.setDecision(decision.recommendation());
    run.setReadinessScore(score);
    run = runs.save(run);
    for (EvaluatedRequirement result : outcome) {
      results.save(new ComplianceResult(run.getId(), result.requirementId(),
          result.verdict().name(), result.reasonCode(), result.traceJson(),
          copiedFrom.get(result.requirementId())));
    }
    for (RiskFinding finding : riskList) {
      risks.save(new RiskRow(run.getId(), finding.severity(), finding.riskCode(),
          finding.sourceClause(), finding.evidenceSummary(), finding.detectionReason(),
          finding.businessImpact(), finding.correctiveAction()));
    }
    decisions.save(new BidDecisionRow(run.getId(), decision.recommendation(),
        decision.approvalStatus(), warningsJson(decision.warnings())));
    return run.getId();
  }

  /** Distinct requirements needing human review: UNKNOWN, NEEDS_REVIEW, low confidence. */
  public static int countReviewItems(List<ComplianceResult> rows,
      Map<Long, TenderRequirement> byId) {
    Set<Long> review = new HashSet<>();
    for (ComplianceResult row : rows) {
      TenderRequirement requirement = byId.get(row.getRequirementId());
      boolean low = requirement != null && requirement.getConfidence() != null
          && requirement.getConfidence().compareTo(new BigDecimal("0.80")) < 0;
      if ("UNKNOWN".equals(row.getVerdict()) || "NEEDS_REVIEW".equals(row.getVerdict())
          || low) {
        review.add(row.getRequirementId());
      }
    }
    return review.size();
  }

  @Transactional
  public void finishRun(long runId, long processingMs, int reviewItems,
      double baselinePerPage, double reviewMinutesPerItem) {
    ComplianceRun run = runs.findById(runId)
        .orElseThrow(() -> new NotFoundException("Run not found"));
    TenderVersion version = versions.findById(run.getTenderVersionId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    TimeSavedResult saved = TimeSavedCalculator.calculate(version.getPageCount(),
        processingMs, reviewItems, baselinePerPage, reviewMinutesPerItem);
    run.setManualMinutes(saved.manualMinutes());
    run.setAssistedMinutes(saved.assistedMinutes());
    run.setTimeSavedPercent(saved.percent());
    run.setFinishedAt(OffsetDateTime.now());
    runs.save(run);
  }

  @Transactional(readOnly = true)
  public Optional<ComplianceRun> latestRun(long versionId) {
    List<ComplianceRun> all = runs.findByTenderVersionIdOrderByIdDesc(versionId);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
  }

  private String warningsJson(List<String> warnings) {
    try {
      return mapper.writeValueAsString(warnings == null ? List.of() : warnings);
    } catch (JsonProcessingException e) {
      return "[]";
    }
  }
}
