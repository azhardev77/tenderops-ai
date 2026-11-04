package com.tenderops.report;

import com.tenderops.company.Company;
import com.tenderops.company.CompanyRepository;
import com.tenderops.runs.BidDecisionRow;
import com.tenderops.runs.BidDecisionRowRepository;
import com.tenderops.runs.ChecklistService;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.DecisionService;
import com.tenderops.runs.RiskRow;
import com.tenderops.runs.RiskRowRepository;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles one immutable report data bundle for the PDF and XLSX writers. */
@Service
public class ReportDataService {

  public record MatrixLine(TenderRequirement requirement, ComplianceResult result) {
  }

  public record ReportData(Tender tender, TenderVersion version, Company company,
      ComplianceRun run, BidDecisionRow decision, List<MatrixLine> matrix, List<RiskRow> risks,
      List<ChecklistService.ChecklistItem> checklist) {
  }

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final TenderRequirementRepository requirements;
  private final CompanyRepository companies;
  private final ComplianceResultRepository results;
  private final RiskRowRepository risks;
  private final BidDecisionRowRepository decisions;
  private final DecisionService decisionService;
  private final ChecklistService checklists;

  public ReportDataService(TenderRepository tenders, TenderVersionRepository versions,
      TenderRequirementRepository requirements, CompanyRepository companies,
      ComplianceResultRepository results, RiskRowRepository risks,
      BidDecisionRowRepository decisions, DecisionService decisionService,
      ChecklistService checklists) {
    this.tenders = tenders;
    this.versions = versions;
    this.requirements = requirements;
    this.companies = companies;
    this.results = results;
    this.risks = risks;
    this.decisions = decisions;
    this.decisionService = decisionService;
    this.checklists = checklists;
  }

  @Transactional(readOnly = true)
  public ReportData assemble(long versionId, Long runId, long companyId) {
    TenderVersion version = versions.findById(versionId)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    if (tender.getCompanyId() != companyId) {
      throw new NotFoundException("Version not found");
    }
    Company company = companies.findById(companyId)
        .orElseThrow(() -> new NotFoundException("Company not found"));
    ComplianceRun run = decisionService.resolveRun(version.getId(), runId);
    if (run.getCompanyId() != companyId) {
      throw new NotFoundException("Run not found");
    }
    BidDecisionRow decision = decisions.findByRunId(run.getId())
        .orElseThrow(() -> new NotFoundException("Decision not found"));
    List<ComplianceResult> rows = results.findByRunIdOrderById(run.getId());
    Map<Long, TenderRequirement> byId = new LinkedHashMap<>();
    for (TenderRequirement requirement : requirements.findByTenderVersionIdOrderById(
        version.getId())) {
      byId.put(requirement.getId(), requirement);
    }
    for (ComplianceResult row : rows) {
      byId.computeIfAbsent(row.getRequirementId(),
          missing -> requirements.findById(missing).orElse(null));
    }
    byId.values().removeIf(value -> value == null);
    List<MatrixLine> matrix = new ArrayList<>();
    for (ComplianceResult row : rows) {
      TenderRequirement requirement = byId.get(row.getRequirementId());
      if (requirement != null) {
        matrix.add(new MatrixLine(requirement, row));
      }
    }
    return new ReportData(tender, version, company, run, decision, matrix,
        risks.findByRunIdOrderById(run.getId()), checklists.checklist(rows, byId));
  }
}
