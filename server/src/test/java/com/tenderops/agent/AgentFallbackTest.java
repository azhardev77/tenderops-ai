package com.tenderops.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tenderops.ai.ExtractionService;
import com.tenderops.audit.AuditService;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.CompanyDocumentRepository;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.compliance.ComplianceOutcome;
import com.tenderops.compliance.ComplianceService;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoDataSeeder;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.document.StorageService;
import com.tenderops.runs.RunService;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.BadRequestException;
import com.tenderops.web.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * TC-AGT halves without a key: the deterministic orchestrator runs the fixed
 * explanation plan labelled FALLBACK_DETERMINISTIC; scoping and empty-run guards.
 * Own committed database (tool threads read committed rows, as in production).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=",
    "spring.datasource.url=jdbc:h2:mem:tenderopsagtfb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class AgentFallbackTest {

  @TempDir
  Path temp;

  @Autowired
  private AgentService agents;

  @Autowired
  private AgentRunRepository agentRuns;

  @Autowired
  private AgentStepRepository agentSteps;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private TenderRequirementRepository requirements;

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
  private UserRepository users;

  @Autowired
  private StorageService storage;

  @Autowired
  private TenderPdfGenerator generator;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditService auditService;

  @Autowired
  private TenderopsProperties properties;

  @Autowired
  private Environment environment;

  @Autowired
  private PdfIngestionService ingestion;

  @Autowired
  private ExtractionService extraction;

  @Autowired
  private ComplianceService compliance;

  @Autowired
  private RunService runService;

  private record Seeded(long companyId, long versionId) {
  }

  private static final Clock GOLDEN_CLOCK = Clock.fixed(
      ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant(),
      ZoneId.of("Asia/Kolkata"));

  private Seeded seed(String tag) throws Exception {
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, "agent-fb-password-1");
    long companyId = seeder.seed(temp).companyId();
    generator.generateSamples(temp);
    byte[] pdf = com.tenderops.IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE)),
        tag + "-" + UUID.randomUUID());
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, TenderPdfGenerator.V1_FILE);
    assertThat(ingest.duplicate()).isFalse();
    TenderVersion version = versions.findById(ingest.versionId()).orElseThrow();
    ExtractionService.ExtractResult extracted = extraction.extractVersion(version);
    assertThat(extracted.museMode()).isEqualTo("FALLBACK");
    ComplianceOutcome outcome = compliance.evaluate(version.getId(), companyId, GOLDEN_CLOCK);
    runService.persistRun(version.getId(), companyId, outcome, extracted.museMode(),
        "FALLBACK");
    return new Seeded(companyId, version.getId());
  }

  private long idOfClause(long versionId, String clauseRef) {
    for (TenderRequirement row : requirements.findByTenderVersionIdOrderById(versionId)) {
      if (clauseRef.equals(row.getClauseRef())) {
        return row.getId();
      }
    }
    throw new IllegalStateException("No requirement " + clauseRef);
  }

  @Test
  void deterministicPlanExplainsTurnover() throws Exception {
    Seeded seeded = seed("fb-turnover");
    long targetId = idOfClause(seeded.versionId(), "4.1");

    long agentRunId = agents.run(seeded.versionId(), seeded.companyId(), targetId);

    AgentRun run = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(run.getMode()).isEqualTo(AgentRun.FALLBACK_DETERMINISTIC);
    assertThat(run.getFinalVerdict()).isEqualTo("PASS");
    List<AgentStep> steps = agentSteps.findByAgentRunIdOrderByStepNo(agentRunId);
    assertThat(steps.stream().map(AgentStep::getToolName)).containsExactly(
        "get_requirement", "get_company_financials", "find_missing_documents",
        "run_deterministic_compliance");
    assertThat(steps).allMatch(step -> step.getErrorText() == null);
    assertThat(steps).allMatch(step -> step.getLatencyMs() != null);
    assertThat(steps.get(3).getOutputJson()).contains("THRESHOLD_MET");
  }

  @Test
  void defaultTargetPicksFirstNonPassMandatory() throws Exception {
    Seeded seeded = seed("fb-default");

    long agentRunId = agents.run(seeded.versionId(), seeded.companyId(), null);

    AgentRun run = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(run.getRequirementId()).isEqualTo(idOfClause(seeded.versionId(), "5.1"));
    assertThat(run.getFinalVerdict()).isEqualTo("FAIL");
    List<AgentStep> steps = agentSteps.findByAgentRunIdOrderByStepNo(agentRunId);
    assertThat(steps.stream().map(AgentStep::getToolName)).containsExactly(
        "get_requirement", "search_company_projects", "find_missing_documents",
        "run_deterministic_compliance");
  }

  @Test
  void solvencyTargetUsesCertificateTool() throws Exception {
    Seeded seeded = seed("fb-solvency");
    long targetId = idOfClause(seeded.versionId(), "6.2");

    long agentRunId = agents.run(seeded.versionId(), seeded.companyId(), targetId);

    AgentRun run = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(run.getFinalVerdict()).isEqualTo("UNKNOWN");
    List<AgentStep> steps = agentSteps.findByAgentRunIdOrderByStepNo(agentRunId);
    assertThat(steps.stream().map(AgentStep::getToolName)).containsExactly(
        "get_requirement", "verify_certificates", "find_missing_documents",
        "run_deterministic_compliance");
    assertThat(steps.get(3).getOutputJson()).contains("NO_SOLVENCY_CERTIFICATE");
  }

  @Test
  void unknownRequirementIdRejected() throws Exception {
    Seeded seeded = seed("fb-unknown");

    assertThatThrownBy(
        () -> agents.run(seeded.versionId(), seeded.companyId(), 999999999L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void foreignTenderRequirementRejected() throws Exception {
    Seeded first = seed("fb-first");
    Seeded second = seed("fb-second");
    long foreignId = idOfClause(second.versionId(), "4.1");

    assertThatThrownBy(() -> agents.run(first.versionId(), first.companyId(), foreignId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void versionWithoutRunRejected() throws Exception {
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, "agent-fb-password-1");
    long companyId = seeder.seed(temp).companyId();
    generator.generateSamples(temp);
    byte[] pdf = com.tenderops.IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE)), "fb-norun");
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, TenderPdfGenerator.V1_FILE);

    assertThatThrownBy(() -> agents.run(ingest.versionId(), companyId, null))
        .isInstanceOf(BadRequestException.class);
  }
}
