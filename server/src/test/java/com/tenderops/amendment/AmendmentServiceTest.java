package com.tenderops.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.audit.AuditService;
import com.tenderops.company.Certificate;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyDocument;
import com.tenderops.company.CompanyDocumentRepository;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.compliance.CompanySnapshot;
import com.tenderops.compliance.EvaluationContext;
import com.tenderops.compliance.RequirementEvaluator;
import com.tenderops.compliance.RequirementView;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoDataSeeder;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.document.StorageService;
import com.tenderops.runs.BidDecisionRowRepository;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.runs.RiskRow;
import com.tenderops.runs.RiskRowRepository;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.workflow.WorkflowEvent;
import com.tenderops.workflow.WorkflowEventRepository;
import com.tenderops.workflow.WorkflowService;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * TC-AMD-01..06: corrigendum merge by clause, selective re-verification with an
 * engine-call spy, deadline-only and data-bump corners, missed deadlines, re-verify.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
@Transactional
class AmendmentServiceTest {

  @TempDir
  Path temp;

  @Autowired
  private WorkflowService workflows;

  @Autowired
  private PdfIngestionService ingestion;

  @Autowired
  private TenderPdfGenerator generator;

  @Autowired
  private UserRepository users;

  @Autowired
  private CompanyRepository companies;

  @Autowired
  private FinancialRecordRepository financials;

  @Autowired
  private ProjectRepository projects;

  @Autowired
  private CertificateRepository certificates;

  @Autowired
  private CompanyDocumentRepository documents;

  @Autowired
  private StorageService storage;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditService auditService;

  @Autowired
  private TenderopsProperties properties;

  @Autowired
  private Environment environment;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private TenderRequirementRepository requirements;

  @Autowired
  private AmendmentRepository amendments;

  @Autowired
  private ComplianceRunRepository runs;

  @Autowired
  private ComplianceResultRepository results;

  @Autowired
  private RiskRowRepository risks;

  @Autowired
  private BidDecisionRowRepository decisions;

  @Autowired
  private WorkflowEventRepository events;

  @SpyBean
  private RequirementEvaluator evaluator;

  private DemoDataSeeder seeder() {
    return new DemoDataSeeder(users, companies, financials, projects, certificates,
        documents, storage, generator, passwordEncoder, auditService, properties,
        environment, "seed-test-password-1");
  }

  private static byte[] onePagePdf(List<String> lines) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        content.beginText();
        content.newLineAtOffset(72, 720);
        for (String line : lines) {
          content.showText(line);
          content.newLineAtOffset(0, -16);
        }
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private long baseRunV1(long companyId, long versionId) {
    long jobId =
        workflows.startJob(versionId, companyId, WorkflowService.KIND_FULL);
    workflows.executeJob(jobId);
    return runs.findByTenderVersionIdOrderByIdDesc(versionId).get(0).getId();
  }

  private TenderVersion ingestV1(long companyId) throws Exception {
    generator.generateSamples(temp);
    byte[] pdf = IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE)), "svc-v1");
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, "Smart_City_RFP_v1.pdf");
    return versions.findById(ingest.versionId()).orElseThrow();
  }

  private byte[] corrBytes() throws Exception {
    generator.generateSamples(temp);
    return IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.CORR_FILE)), "svc-corr");
  }

  private Map<String, String> verdictsByClause(long runId, long baseVersionId,
      long newVersionId) {
    Map<Long, String> clauses = new LinkedHashMap<>();
    for (long versionId : List.of(baseVersionId, newVersionId)) {
      for (TenderRequirement requirement :
          requirements.findByTenderVersionIdOrderById(versionId)) {
        clauses.putIfAbsent(requirement.getId(), requirement.getClauseRef());
      }
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (ComplianceResult row : results.findByRunIdOrderById(runId)) {
      out.put(clauses.get(row.getRequirementId()), row.getVerdict());
    }
    return out;
  }

  @Test
  void corrigendumMergesByClause() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion v1 = ingestV1(companyId);
    baseRunV1(companyId, v1.getId());
    byte[] corr = corrBytes();
    PdfIngestionService.IngestResult amended = ingestion.ingestNewVersion(v1.getTenderId(),
        companyId, corr, "Corrigendum_1.pdf", "CORRIGENDUM");

    long jobId = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);
    workflows.executeJob(jobId);

    TenderVersion v2 = versions.findById(amended.versionId()).orElseThrow();
    assertThat(v2.getVersionNumber()).isEqualTo(2);
    assertThat(requirements.findByTenderVersionIdOrderById(v2.getId())).hasSize(2);
    ComplianceRun run = runs.findByTenderVersionIdOrderByIdDesc(v2.getId()).get(0);
    assertThat(verdictsByClause(run.getId(), v1.getId(), v2.getId())).isEqualTo(Map.of(
        "4.1", "PASS", "4.2", "PASS", "5.1", "PASS", "6.1", "PASS", "6.2", "UNKNOWN",
        "8.1", "PASS"));
    assertThat(new ArrayList<>(
        verdictsByClause(run.getId(), v1.getId(), v2.getId()).keySet()))
        .containsExactly("4.1", "4.2", "5.1", "6.1", "6.2", "8.1");
    assertThat(run.getDecision()).isEqualTo("CONDITIONAL_BID");
    assertThat(run.getReadinessScore()).isEqualByComparingTo(new BigDecimal("80.00"));

    Amendment amendment =
        amendments.findByNewVersionId(v2.getId()).orElseThrow();
    assertThat(amendment.getPrevDecision()).isEqualTo("NO_BID");
    assertThat(amendment.getNewDecision()).isEqualTo("CONDITIONAL_BID");
    assertThat(amendment.getScoreDelta()).isEqualByComparingTo(new BigDecimal("40.00"));
    JsonNode diff = new ObjectMapper().readTree(amendment.getDiffJson());
    assertThat(diff.get("deadlineChanged").asBoolean()).isFalse();
    assertThat(diff.get("inserted").size()).isEqualTo(0);
    assertThat(diff.get("deleted").size()).isEqualTo(0);
    List<String> modified = new ArrayList<>();
    diff.get("modified").forEach(node -> modified.add(node.get("clauseRef").asText()));
    assertThat(modified).containsExactly("5.1", "6.1");
  }

  @Test
  void selectiveReverifyEvaluatesOnlyChanged() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion v1 = ingestV1(companyId);
    long baseRunId = baseRunV1(companyId, v1.getId());
    byte[] corr = corrBytes();
    PdfIngestionService.IngestResult amended = ingestion.ingestNewVersion(v1.getTenderId(),
        companyId, corr, "Corrigendum_1.pdf", "CORRIGENDUM");
    long jobId = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);

    clearInvocations(evaluator);
    workflows.executeJob(jobId);

    verify(evaluator, times(2)).evaluateView(anyLong(), any(RequirementView.class),
        any(CompanySnapshot.class), any(EvaluationContext.class));
    ComplianceRun run =
        runs.findByTenderVersionIdOrderByIdDesc(amended.versionId()).get(0);
    List<ComplianceResult> rows = results.findByRunIdOrderById(run.getId());
    assertThat(rows.stream().filter(row -> row.getCopiedFromRunId() != null).toList())
        .hasSize(4);
    assertThat(rows.stream().map(ComplianceResult::getCopiedFromRunId)
        .filter(id -> id != null).toList()).containsOnly(baseRunId);
    List<String> types =
        events.findByTenderVersionIdOrderBySeq(amended.versionId()).stream()
            .map(WorkflowEvent::getType).toList();
    assertThat(types).contains("AMENDMENT_STAGE");
  }

  @Test
  void deadlineOnlyCorrigendumKeepsVerdicts() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion v1 = ingestV1(companyId);
    baseRunV1(companyId, v1.getId());
    byte[] corr = IsolatedPdfs.tag(onePagePdf(List.of("Corrigendum 2 for Smart City RFP.",
        "Bid submission deadline: 20 December 2026, 17:00 IST.")), "svc-dl2");
    PdfIngestionService.IngestResult amended = ingestion.ingestNewVersion(v1.getTenderId(),
        companyId, corr, "Corrigendum_2.pdf", "CORRIGENDUM");
    long jobId = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);

    clearInvocations(evaluator);
    workflows.executeJob(jobId);

    TenderVersion v2 = versions.findById(amended.versionId()).orElseThrow();
    ComplianceRun run = runs.findByTenderVersionIdOrderByIdDesc(v2.getId()).get(0);
    assertThat(verdictsByClause(run.getId(), v1.getId(), v2.getId())).isEqualTo(Map.of(
        "4.1", "PASS", "4.2", "PASS", "5.1", "FAIL", "6.1", "FAIL", "6.2", "UNKNOWN",
        "8.1", "PASS"));
    verify(evaluator, times(6)).evaluateView(anyLong(), any(RequirementView.class),
        any(CompanySnapshot.class), any(EvaluationContext.class));
    JsonNode diff = new ObjectMapper()
        .readTree(amendments.findByNewVersionId(v2.getId()).orElseThrow().getDiffJson());
    assertThat(diff.get("deadlineChanged").asBoolean()).isTrue();
    assertThat(diff.get("modified").size()).isEqualTo(0);
  }

  @Test
  void dataBumpReevaluatesEverything() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion v1 = ingestV1(companyId);
    baseRunV1(companyId, v1.getId());
    Company company = companies.findById(companyId).orElseThrow();
    company.bumpDataVersion();
    companies.save(company);
    byte[] corr = corrBytes();
    PdfIngestionService.IngestResult amended = ingestion.ingestNewVersion(v1.getTenderId(),
        companyId, corr, "Corrigendum_1.pdf", "CORRIGENDUM");
    long jobId = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);

    clearInvocations(evaluator);
    workflows.executeJob(jobId);

    verify(evaluator, times(6)).evaluateView(anyLong(), any(RequirementView.class),
        any(CompanySnapshot.class), any(EvaluationContext.class));
  }

  @Test
  void missedDeadlineRaisesCriticalRisk() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion v1 = ingestV1(companyId);
    baseRunV1(companyId, v1.getId());
    byte[] corr = IsolatedPdfs.tag(onePagePdf(List.of("Corrigendum 3 for Smart City RFP.",
        "Bid submission deadline: 10 October 2026, 17:00 IST.")), "svc-dl3");
    PdfIngestionService.IngestResult amended = ingestion.ingestNewVersion(v1.getTenderId(),
        companyId, corr, "Corrigendum_3.pdf", "CORRIGENDUM");
    long jobId = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);
    workflows.executeJob(jobId);

    ComplianceRun run =
        runs.findByTenderVersionIdOrderByIdDesc(amended.versionId()).get(0);
    List<RiskRow> findings = risks.findByRunIdOrderById(run.getId());
    assertThat(findings.stream().map(RiskRow::getRiskCode).toList())
        .contains("SUBMISSION_DEADLINE_PASSED");
    assertThat(findings.stream()
        .filter(row -> "SUBMISSION_DEADLINE_PASSED".equals(row.getRiskCode()))
        .map(RiskRow::getSeverity).toList()).containsExactly("CRITICAL");
  }

  @Test
  void reverifyAfterSolvencyReachesBidWithoutAi() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion v1 = ingestV1(companyId);
    baseRunV1(companyId, v1.getId());
    byte[] corr = corrBytes();
    PdfIngestionService.IngestResult amended = ingestion.ingestNewVersion(v1.getTenderId(),
        companyId, corr, "Corrigendum_1.pdf", "CORRIGENDUM");
    long amdJob = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);
    workflows.executeJob(amdJob);
    long reqCount =
        requirements.findByTenderVersionIdOrderById(amended.versionId()).size();

    CompanyDocument doc = documents.findByCompanyIdOrderById(companyId).get(0);
    Certificate solvency = new Certificate(companyId, "BANK_SOLVENCY_CERTIFICATE");
    solvency.setAmount(new BigDecimal("180000000.0000"));
    solvency.setIssueDate(LocalDate.of(2026, 10, 1));
    solvency.setExpiryDate(LocalDate.of(2027, 3, 31));
    solvency.setDocumentId(doc.getId());
    certificates.save(solvency);
    Company company = companies.findById(companyId).orElseThrow();
    company.bumpDataVersion();
    companies.save(company);
    List<WorkflowEvent> before =
        events.findByTenderVersionIdOrderBySeq(amended.versionId());
    long maxSeq = before.get(before.size() - 1).getSeq();

    long jobId = workflows.startJob(amended.versionId(), companyId,
        WorkflowService.KIND_REVERIFY);
    workflows.executeJob(jobId);

    ComplianceRun run =
        runs.findByTenderVersionIdOrderByIdDesc(amended.versionId()).get(0);
    assertThat(run.getDecision()).isEqualTo("BID");
    assertThat(run.getReadinessScore()).isEqualByComparingTo(new BigDecimal("100.00"));
    assertThat(requirements.findByTenderVersionIdOrderById(amended.versionId()))
        .hasSize((int) reqCount);
    List<WorkflowEvent> fresh =
        events.findByTenderVersionIdOrderBySeq(amended.versionId()).stream()
            .filter(event -> event.getSeq() > maxSeq).toList();
    assertThat(fresh.stream().map(WorkflowEvent::getType).toList())
        .doesNotContain("REQUIREMENT_EXTRACTED", "REGION_DETECTED");
  }
}
