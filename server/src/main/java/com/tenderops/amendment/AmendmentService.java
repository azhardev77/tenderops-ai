package com.tenderops.amendment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.ai.RequirementFingerprint;
import com.tenderops.compliance.BidDecisionEngine;
import com.tenderops.compliance.CompanySnapshot;
import com.tenderops.compliance.Decision;
import com.tenderops.compliance.EvaluatedRequirement;
import com.tenderops.compliance.EvaluationContext;
import com.tenderops.compliance.ReadinessScore;
import com.tenderops.compliance.RequirementEvaluator;
import com.tenderops.compliance.RequirementView;
import com.tenderops.compliance.RiskEngine;
import com.tenderops.compliance.RiskFinding;
import com.tenderops.compliance.Verdict;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.RunService;
import com.tenderops.runs.SummaryService;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.workflow.EventService;
import com.tenderops.workflow.EventTypes;
import com.tenderops.workflow.WorkflowJob;
import com.tenderops.workflow.WorkflowJobRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Amendments (section 6.11): diff by clauseRef, virtual merge (selection over base +
 * new rows, never duplicated), and selective re-verification (copy iff fingerprint
 * and evaluation-context hash both match, else evaluate).
 */
@Service
public class AmendmentService {

  private static final Logger LOG = LoggerFactory.getLogger(AmendmentService.class);

  private final TenderVersionRepository versions;
  private final TenderRepository tenders;
  private final TenderRequirementRepository requirements;
  private final CompanyRepository companies;
  private final FinancialRecordRepository financials;
  private final ProjectRepository projects;
  private final CertificateRepository certificates;
  private final RequirementEvaluator evaluator;
  private final RunService runs;
  private final ComplianceResultRepository storedResults;
  private final AmendmentTx amendmentTx;
  private final SummaryService summaries;
  private final EventService events;
  private final WorkflowJobRepository jobs;
  private final TenderopsProperties properties;
  private final ObjectMapper mapper;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public AmendmentService(TenderVersionRepository versions, TenderRepository tenders,
      TenderRequirementRepository requirements, CompanyRepository companies,
      FinancialRecordRepository financials, ProjectRepository projects,
      CertificateRepository certificates, RequirementEvaluator evaluator, RunService runs,
      ComplianceResultRepository storedResults, AmendmentTx amendmentTx,
      SummaryService summaries, EventService events, WorkflowJobRepository jobs,
      TenderopsProperties properties, ObjectMapper mapper, Clock clock) {
    this.versions = versions;
    this.tenders = tenders;
    this.requirements = requirements;
    this.companies = companies;
    this.financials = financials;
    this.projects = projects;
    this.certificates = certificates;
    this.evaluator = evaluator;
    this.runs = runs;
    this.storedResults = storedResults;
    this.amendmentTx = amendmentTx;
    this.summaries = summaries;
    this.events = events;
    this.jobs = jobs;
    this.properties = properties;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Latest version below the given number; an amendment always needs a base. */
  public static TenderVersion baseVersionOf(List<TenderVersion> ordered, int versionNumber) {
    TenderVersion base = null;
    for (TenderVersion version : ordered) {
      if (version.getVersionNumber() < versionNumber) {
        base = version;
      }
    }
    if (base == null) {
      throw new IllegalStateException("Amendment needs a base version");
    }
    return base;
  }

  /** Fingerprint over engine-visible requirement fields (matches persisted rows). */
  public static String fingerprintOf(RequirementView view) {
    String tags = view.domainTags() == null || view.domainTags().isEmpty() ? null
        : String.join(",", view.domainTags());
    return RequirementFingerprint.of(view.clauseRef(), view.metric(), view.operator(),
        view.amountText(), view.countValue(), view.lookbackYears(), tags,
        view.certificateName(), view.certificateLevel(), view.conditionTree(),
        view.mandatory());
  }

  /** Copy iff the same fingerprint was evaluated under the same context hash. */
  public static boolean canCopy(String fingerprint,
      Map<String, EvaluatedRequirement> baseByFingerprint, EvaluationContext newCtx,
      String baseCtxHash) {
    return baseByFingerprint.containsKey(fingerprint)
        && newCtx.contextHash().equals(baseCtxHash);
  }

  /**
   * Selective re-verification: copies carry verdict+reason+trace+evidence, changed or
   * context-shifted requirements go through the Java engine. Pure given the inputs.
   */
  public static List<EvaluatedRequirement> selectiveReverify(
      RequirementEvaluator evaluator, List<MergedRequirement> merged,
      Map<String, EvaluatedRequirement> baseByFingerprint, EvaluationContext newCtx,
      String baseCtxHash, CompanySnapshot snapshot) {
    List<EvaluatedRequirement> out = new ArrayList<>();
    for (MergedRequirement item : merged) {
      String fingerprint = fingerprintOf(item.view());
      EvaluatedRequirement base = baseByFingerprint.get(fingerprint);
      if (base != null && canCopy(fingerprint, baseByFingerprint, newCtx, baseCtxHash)) {
        out.add(new EvaluatedRequirement(item.requirementId(), item.view().clauseRef(),
            item.view().mandatory(), base.verdict(), base.reasonCode(), base.traceJson(),
            base.evidenceIds()));
      } else {
        out.add(evaluator.evaluateView(item.requirementId(), item.view(), snapshot,
            newCtx));
      }
    }
    return out;
  }

  /** Virtual merge: base-clause order, substitutes in place, inserts appended. */
  public MergedSelection merge(TenderVersion next, List<TenderRequirement> baseRows,
      List<TenderRequirement> newRows) {
    if ("FULL_REVISION".equals(next.getKind())) {
      return mergeFullRevision(baseRows, newRows);
    }
    Map<String, TenderRequirement> baseByClause = new LinkedHashMap<>();
    for (TenderRequirement row : baseRows) {
      baseByClause.put(row.getClauseRef(), row);
    }
    Map<String, TenderRequirement> newByTarget = new LinkedHashMap<>();
    List<TenderRequirement> inserts = new ArrayList<>();
    Set<String> deleted = new HashSet<>();
    for (TenderRequirement row : newRows) {
      String action = row.getAmendmentAction() == null ? "NONE" : row.getAmendmentAction();
      String target = row.getTargetClauseRef() == null
          || row.getTargetClauseRef().isBlank() ? row.getClauseRef()
              : row.getTargetClauseRef();
      switch (action) {
        case "DELETE" -> {
          if (baseByClause.containsKey(target)) {
            deleted.add(target);
          } else {
            LOG.warn("DELETE targets unknown clause {}", target);
          }
        }
        case "SUBSTITUTE" -> {
          if (baseByClause.containsKey(target)) {
            newByTarget.put(target, row);
          } else {
            inserts.add(row);
          }
        }
        case "INSERT" -> inserts.add(row);
        case "DEADLINE_CHANGE" -> {
          // Metadata only; the new version deadline applies to the merged context.
        }
        default -> {
          if (baseByClause.containsKey(row.getClauseRef())) {
            newByTarget.put(row.getClauseRef(), row);
          } else {
            inserts.add(row);
          }
        }
      }
    }
    List<TenderRequirement> merged = new ArrayList<>();
    List<String> deletedRefs = new ArrayList<>();
    for (TenderRequirement row : baseRows) {
      String ref = row.getClauseRef();
      if (deleted.contains(ref)) {
        deletedRefs.add(ref);
      } else if (newByTarget.containsKey(ref)) {
        merged.add(newByTarget.get(ref));
      } else {
        merged.add(row);
      }
    }
    merged.addAll(inserts);
    return new MergedSelection(merged, deletedRefs);
  }

  private MergedSelection mergeFullRevision(List<TenderRequirement> baseRows,
      List<TenderRequirement> newRows) {
    Set<String> next = new HashSet<>();
    for (TenderRequirement row : newRows) {
      next.add(row.getClauseRef());
    }
    List<String> deleted = new ArrayList<>();
    for (TenderRequirement row : baseRows) {
      if (!next.contains(row.getClauseRef())) {
        deleted.add(row.getClauseRef());
      }
    }
    return new MergedSelection(new ArrayList<>(newRows), deleted);
  }

  /** Clause diff between the base set and the merged selection. */
  public AmendmentDiff diff(List<TenderRequirement> baseRows,
      List<TenderRequirement> newRows, TenderVersion base, TenderVersion next,
      MergedSelection merged) {
    Map<String, TenderRequirement> baseByClause = new LinkedHashMap<>();
    for (TenderRequirement row : baseRows) {
      baseByClause.put(row.getClauseRef(), row);
    }
    Set<Long> newIds = new HashSet<>();
    for (TenderRequirement row : newRows) {
      newIds.add(row.getId());
    }
    List<String> inserted = new ArrayList<>();
    List<AmendmentDiff.ModifiedClause> modified = new ArrayList<>();
    for (TenderRequirement row : merged.rows()) {
      if (!newIds.contains(row.getId())) {
        continue;
      }
      TenderRequirement before = baseByClause.get(row.getClauseRef());
      if (before == null) {
        inserted.add(row.getClauseRef());
      } else if (!row.getFingerprint().equals(before.getFingerprint())) {
        modified.add(new AmendmentDiff.ModifiedClause(row.getClauseRef(),
            !equalAmounts(before.getAmountValue(), row.getAmountValue()),
            !equalStrings(before.getOperator(), row.getOperator()),
            !equalStrings(before.getConditionTree(), row.getConditionTree())));
      }
    }
    return new AmendmentDiff(inserted, merged.deleted(), modified,
        deadlineChanged(base.getSubmissionDeadline(), next.getSubmissionDeadline()),
        null, null, null);
  }

  /** Full amendment run: merge, selective re-verify, persist run, decide, report. */
  public long processAmendment(long newVersionId, long jobId, String museMode,
      String samMode) {
    WorkflowJob job = jobs.findById(jobId)
        .orElseThrow(() -> new IllegalStateException("Job " + jobId + " not found"));
    TenderVersion next = versions.findById(newVersionId)
        .orElseThrow(() -> new IllegalStateException("Version not found"));
    Tender tender = tenders.findById(next.getTenderId())
        .orElseThrow(() -> new IllegalStateException("Tender not found"));
    long companyId = tender.getCompanyId();
    TenderVersion base = baseVersionOf(
        versions.findByTenderIdOrderByVersionNumber(tender.getId()),
        next.getVersionNumber());
    ComplianceRun baseRun = runs.latestRun(base.getId()).orElseThrow(
        () -> new IllegalStateException("Base version has no completed run"));
    List<ComplianceResult> baseResultRows =
        storedResults.findByRunIdOrderById(baseRun.getId());
    Map<Long, TenderRequirement> reqById = new HashMap<>();
    for (TenderRequirement row :
        requirements.findByTenderVersionIdOrderById(base.getId())) {
      reqById.put(row.getId(), row);
    }
    for (TenderRequirement row :
        requirements.findByTenderVersionIdOrderById(next.getId())) {
      reqById.put(row.getId(), row);
    }
    List<TenderRequirement> baseRows = new ArrayList<>();
    Map<String, EvaluatedRequirement> baseByFingerprint = new LinkedHashMap<>();
    for (ComplianceResult row : baseResultRows) {
      TenderRequirement req = reqById.get(row.getRequirementId());
      if (req == null) {
        continue;
      }
      baseRows.add(req);
      baseByFingerprint.put(req.getFingerprint(),
          new EvaluatedRequirement(req.getId(), req.getClauseRef(), req.isMandatory(),
              Verdict.valueOf(row.getVerdict()), row.getReasonCode(),
              row.getCalculationTrace(), evidenceFromTrace(row.getCalculationTrace())));
    }
    List<TenderRequirement> newRows =
        requirements.findByTenderVersionIdOrderById(next.getId());
    MergedSelection merged = merge(next, baseRows, newRows);
    AmendmentDiff diff = diff(baseRows, newRows, base, next, merged);
    List<String> modifiedRefs = new ArrayList<>();
    for (AmendmentDiff.ModifiedClause clause : diff.modified()) {
      modifiedRefs.add(clause.clauseRef());
    }
    events.publish(next.getId(), companyId, EventTypes.AMENDMENT_STAGE, "AMENDMENT",
        Map.of("jobId", jobId, "inserted", diff.inserted(), "deleted", diff.deleted(),
            "modified", modifiedRefs, "deadlineChanged", diff.deadlineChanged()));

    OffsetDateTime deadline = next.getSubmissionDeadline() != null
        ? next.getSubmissionDeadline() : base.getSubmissionDeadline();
    BigDecimal estimated = next.getEstimatedValue() != null ? next.getEstimatedValue()
        : base.getEstimatedValue();
    Company company = companies.findById(companyId)
        .orElseThrow(() -> new IllegalStateException("Company not found"));
    EvaluationContext newCtx = EvaluationContext.of(deadline, estimated,
        properties.ruleVersion(), company.getDataVersion());
    CompanySnapshot snapshot = new CompanySnapshot(
        financials.findByCompanyIdOrderByFyEndDateDesc(companyId),
        projects.findByCompanyIdOrderById(companyId),
        certificates.findByCompanyIdOrderById(companyId));
    List<MergedRequirement> items = new ArrayList<>();
    for (TenderRequirement row : merged.rows()) {
      items.add(new MergedRequirement(row.getId(), RequirementView.from(row)));
    }
    List<EvaluatedRequirement> out = selectiveReverify(evaluator, items,
        baseByFingerprint, newCtx, baseRun.getEvaluationContextHash(), snapshot);
    Map<Long, Long> copiedFrom = new HashMap<>();
    int copied = 0;
    for (MergedRequirement item : items) {
      if (canCopy(fingerprintOf(item.view()), baseByFingerprint, newCtx,
          baseRun.getEvaluationContextHash())) {
        copiedFrom.put(item.requirementId(), baseRun.getId());
        copied++;
      }
    }
    Decision decision = BidDecisionEngine.decide(out);
    BigDecimal score = ReadinessScore.of(out);
    List<RiskFinding> riskList = RiskEngine.assess(out, deadline, clock);
    long runId = runs.persistRunWithCopies(next.getId(), companyId, out, decision,
        score, riskList, newCtx.contextHash(), newCtx.ruleVersion(),
        newCtx.companyDataVersion(), museMode, samMode, copiedFrom);
    summaries.generateAndAttach(runId);
    Map<Long, TenderRequirement> mergedById = new HashMap<>();
    for (TenderRequirement row : merged.rows()) {
      mergedById.put(row.getId(), row);
    }
    int reviewItems = RunService.countReviewItems(
        storedResults.findByRunIdOrderById(runId), mergedById);
    long processingMs =
        Duration.between(job.getCreatedAt(), OffsetDateTime.now()).toMillis();
    runs.finishRun(runId, processingMs, reviewItems,
        properties.timeSaved().baselineMinutesPerPage(),
        properties.timeSaved().reviewMinutesPerItem());
    for (EvaluatedRequirement result : out) {
      events.publish(next.getId(), companyId, EventTypes.VERDICT_COMPUTED, "AMENDMENT",
          Map.of("jobId", jobId, "runId", runId, "requirementId",
              result.requirementId(), "clauseRef", result.clauseRef(), "verdict",
              result.verdict().name(), "reasonCode", result.reasonCode()));
    }
    for (RiskFinding finding : riskList) {
      Map<String, Object> payload = new HashMap<>();
      payload.put("jobId", jobId);
      payload.put("runId", runId);
      payload.put("severity", finding.severity());
      payload.put("riskCode", finding.riskCode());
      payload.put("sourceClause", finding.sourceClause());
      events.publish(next.getId(), companyId, EventTypes.RISK_RAISED, "AMENDMENT",
          payload);
    }
    events.publish(next.getId(), companyId, EventTypes.DECISION_UPDATED, "AMENDMENT",
        Map.of("jobId", jobId, "runId", runId, "decision", decision.recommendation(),
            "score", score.toPlainString()));
    events.publish(next.getId(), companyId, EventTypes.AMENDMENT_STAGE, "AMENDMENT",
        Map.of("jobId", jobId, "copied", copied, "evaluated", out.size() - copied));

    BigDecimal delta = score.subtract(baseRun.getReadinessScore());
    String diffJson;
    try {
      diffJson = mapper.writeValueAsString(new AmendmentDiff(diff.inserted(),
          diff.deleted(), diff.modified(), diff.deadlineChanged(),
          baseRun.getDecision(), decision.recommendation(), delta));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize amendment diff", e);
    }
    amendmentTx.saveOutcome(tender.getId(), base.getId(), next.getId(), diffJson,
        baseRun.getDecision(), decision.recommendation(), delta);
    LOG.debug("Amendment run {} decided {} at {}", runId, decision.recommendation(),
        score);
    return runId;
  }

  /** Virtual merged selection: ordered rows plus base clauses dropped by DELETE. */
  public record MergedSelection(List<TenderRequirement> rows, List<String> deleted) {
  }

  private List<Long> evidenceFromTrace(String traceJson) {
    try {
      JsonNode node = mapper.readTree(traceJson).get("evidenceIds");
      List<Long> ids = new ArrayList<>();
      if (node != null && node.isArray()) {
        for (JsonNode id : node) {
          ids.add(id.asLong());
        }
      }
      return ids;
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return List.of();
    }
  }

  private static boolean deadlineChanged(OffsetDateTime before, OffsetDateTime after) {
    if (before == null && after == null) {
      return false;
    }
    if (after == null) {
      // Silent amendment: the base deadline applies (same rule as merged context).
      return false;
    }
    if (before == null) {
      return true;
    }
    return !before.isEqual(after);
  }

  private static boolean equalAmounts(BigDecimal left, BigDecimal right) {
    if (left == null && right == null) {
      return true;
    }
    if (left == null || right == null) {
      return false;
    }
    return left.compareTo(right) == 0;
  }

  private static boolean equalStrings(String left, String right) {
    if (left == null && right == null) {
      return true;
    }
    if (left == null || right == null) {
      return false;
    }
    return left.equals(right);
  }
}
