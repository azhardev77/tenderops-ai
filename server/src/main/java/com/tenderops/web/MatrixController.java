package com.tenderops.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.runs.ChecklistService;
import com.tenderops.runs.ClarificationService;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.DecisionService;
import com.tenderops.runs.RiskRow;
import com.tenderops.runs.RiskRowRepository;
import com.tenderops.security.CurrentUser;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.workflow.WorkflowService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance reads (section 9): matrix with traces, re-verify, risks, checklist,
 * clarification questions and the executive summary. Company-scoped; money as strings.
 */
@RestController
@RequestMapping("/api/v1")
public class MatrixController {

  public record RequirementInline(long id, String clauseRef, boolean mandatory, String metric,
      String operator, String amountText, int sourcePage, String originalQuote,
      String confidence) {
  }

  public record MatrixRow(RequirementInline requirement, String verdict, String reasonCode,
      Object calculationTrace, boolean copied) {
  }

  public record TimeSavedDto(String percent, String manualMinutes, String assistedMinutes) {
  }

  public record MatrixResponse(long runId, String museMode, String samMode,
      String readinessScore, String decision, TimeSavedDto timeSaved, List<MatrixRow> rows) {
  }

  public record ReverifyResponse(Long runId, long jobId) {
  }

  public record RiskDto(String severity, String riskCode, String sourceClause,
      String evidenceSummary, String detectionReason, String businessImpact,
      String correctiveAction) {
  }

  public record SummaryResponse(String text, boolean groundingWarning, String source,
      String museMode) {
  }

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final TenderRequirementRepository requirements;
  private final ComplianceResultRepository results;
  private final RiskRowRepository risks;
  private final DecisionService decisionService;
  private final ChecklistService checklists;
  private final ClarificationService clarifications;
  private final WorkflowService workflows;
  private final ObjectMapper mapper;

  public MatrixController(TenderRepository tenders, TenderVersionRepository versions,
      TenderRequirementRepository requirements, ComplianceResultRepository results,
      RiskRowRepository risks, DecisionService decisionService, ChecklistService checklists,
      ClarificationService clarifications, WorkflowService workflows, ObjectMapper mapper) {
    this.tenders = tenders;
    this.versions = versions;
    this.requirements = requirements;
    this.results = results;
    this.risks = risks;
    this.decisionService = decisionService;
    this.checklists = checklists;
    this.clarifications = clarifications;
    this.workflows = workflows;
    this.mapper = mapper;
  }

  @GetMapping("/tender-versions/{id}/matrix")
  public MatrixResponse matrix(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    TenderVersion version = visibleVersion(id);
    ComplianceRun run = decisionService.resolveRun(version.getId(), runId);
    List<ComplianceResult> rows = results.findByRunIdOrderById(run.getId());
    Map<Long, TenderRequirement> byId = requirementsById(version.getId(), rows);
    List<MatrixRow> out = new ArrayList<>();
    for (ComplianceResult row : rows) {
      TenderRequirement requirement = byId.get(row.getRequirementId());
      if (requirement == null) {
        continue;
      }
      out.add(new MatrixRow(inline(requirement), row.getVerdict(), row.getReasonCode(),
          traceOf(row.getCalculationTrace()), row.getCopiedFromRunId() != null));
    }
    return new MatrixResponse(run.getId(), run.getMuseMode(), run.getSamMode(),
        plain(run.getReadinessScore()), run.getDecision(),
        run.getTimeSavedPercent() == null ? null
            : new TimeSavedDto(run.getTimeSavedPercent().toPlainString(),
                plain(run.getManualMinutes()), plain(run.getAssistedMinutes())),
        out);
  }

  @PostMapping("/tender-versions/{id}/re-verify")
  public ResponseEntity<ReverifyResponse> reverify(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    TenderVersion version = visibleVersion(id);
    long jobId = workflows.startJob(version.getId(), current.companyId(),
        WorkflowService.KIND_REVERIFY);
    workflows.launchAsync(jobId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ReverifyResponse(null, jobId));
  }

  @GetMapping("/tender-versions/{id}/risks")
  public List<RiskDto> risks(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    TenderVersion version = visibleVersion(id);
    ComplianceRun run = decisionService.resolveRun(version.getId(), runId);
    List<RiskDto> out = new ArrayList<>();
    for (RiskRow risk : risks.findByRunIdOrderById(run.getId())) {
      out.add(new RiskDto(risk.getSeverity(), risk.getRiskCode(), risk.getSourceClause(),
          risk.getEvidenceSummary(), risk.getDetectionReason(), risk.getBusinessImpact(),
          risk.getCorrectiveAction()));
    }
    return out;
  }

  @GetMapping("/tender-versions/{id}/checklist")
  public List<ChecklistService.ChecklistItem> checklist(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    List<ComplianceResult> rows = rowsOf(id, runId);
    return checklists.checklist(rows, requirementsById(visibleVersion(id).getId(), rows));
  }

  @GetMapping("/tender-versions/{id}/clarifications")
  public List<ClarificationService.ClarificationItem> clarifications(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    List<ComplianceResult> rows = rowsOf(id, runId);
    return clarifications.clarifications(rows, requirementsById(visibleVersion(id).getId(), rows));
  }

  @GetMapping("/tender-versions/{id}/summary")
  public SummaryResponse summary(@PathVariable long id,
      @RequestParam(required = false) Long runId) {
    TenderVersion version = visibleVersion(id);
    ComplianceRun run = decisionService.resolveRun(version.getId(), runId);
    return new SummaryResponse(run.getSummaryText(), run.isGroundingWarning(),
        run.getSummarySource(), run.getMuseMode());
  }

  private List<ComplianceResult> rowsOf(long versionId, Long runId) {
    TenderVersion version = visibleVersion(versionId);
    ComplianceRun run = decisionService.resolveRun(version.getId(), runId);
    return results.findByRunIdOrderById(run.getId());
  }

  private Map<Long, TenderRequirement> requirementsById(long versionId,
      List<ComplianceResult> rows) {
    Map<Long, TenderRequirement> byId = new LinkedHashMap<>();
    for (TenderRequirement requirement : requirements.findByTenderVersionIdOrderById(
        versionId)) {
      byId.put(requirement.getId(), requirement);
    }
    // Amendment runs evaluate merged rows that may live on earlier versions.
    for (ComplianceResult row : rows) {
      byId.computeIfAbsent(row.getRequirementId(),
          missing -> requirements.findById(missing).orElse(null));
    }
    byId.values().removeIf(value -> value == null);
    return byId;
  }

  private Object traceOf(String traceJson) {
    try {
      return mapper.readValue(traceJson, JsonNode.class);
    } catch (Exception e) {
      return Map.of("raw", traceJson);
    }
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

  private static RequirementInline inline(TenderRequirement requirement) {
    return new RequirementInline(requirement.getId(), requirement.getClauseRef(),
        requirement.isMandatory(), requirement.getMetric(), requirement.getOperator(),
        requirement.getAmountText(), requirement.getSourcePage(),
        requirement.getOriginalQuote(),
        requirement.getConfidence() == null ? null
            : requirement.getConfidence().toPlainString());
  }

  private static String plain(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
