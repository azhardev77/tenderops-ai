package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.audit.AuditService;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyDocumentRepository;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoDataSeeder;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.document.StorageService;
import com.tenderops.runs.BidDecisionRowRepository;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.runs.RiskRowRepository;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.JobAlreadyActiveException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * TC-JOB-01/02 (service level): full pipeline run, one active job per version,
 * persisted progress, stale-job recovery, and honest failure recording.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
@Transactional
class WorkflowServiceTest {

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
  private TenderRepository tenders;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private WorkflowJobRepository jobs;

  @Autowired
  private WorkflowEventRepository events;

  @Autowired
  private ComplianceRunRepository runs;

  @Autowired
  private ComplianceResultRepository results;

  @Autowired
  private RiskRowRepository risks;

  @Autowired
  private BidDecisionRowRepository decisions;

  @Autowired
  private TenderRequirementRepository requirements;

  private DemoDataSeeder seeder() {
    return new DemoDataSeeder(users, companies, financials, projects, certificates,
        documents, storage, generator, passwordEncoder, auditService, properties,
        environment, "seed-test-password-1");
  }

  private TenderVersion ingestV1(long companyId) throws Exception {
    generator.generateSamples(temp);
    byte[] pdf = Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE));
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, "Smart_City_RFP_v1.pdf");
    return versions.findById(ingest.versionId()).orElseThrow();
  }

  private TenderVersion bareVersion(String tag) {
    Company company = companies.save(new Company("Job Co " + tag));
    Tender tender = tenders.save(new Tender(company.getId(), "Job tender " + tag, null));
    return versions.save(new TenderVersion(tender.getId(), 1, TenderVersion.KIND_BASE,
        "sha-for-jobs-" + tag + "00000000000000000000000000000000000000000000",
        "jobs/x.pdf", 1));
  }

  @Test
  void fullPipelineCompletesGoldenRun() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion version = ingestV1(companyId);

    long jobId = workflows.startJob(version.getId(), companyId, WorkflowService.KIND_FULL);
    workflows.executeJob(jobId);

    WorkflowJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo("COMPLETED");
    JsonNode progress = new ObjectMapper().readTree(job.getProgressJson());
    assertThat(progress.get("stages").size()).isEqualTo(8);

    ComplianceRun run = runs.findByTenderVersionIdOrderByIdDesc(version.getId()).get(0);
    assertThat(run.getDecision()).isEqualTo("NO_BID");
    assertThat(run.getReadinessScore()).isEqualByComparingTo(new BigDecimal("40.00"));
    assertThat(run.getMuseMode()).isEqualTo("FALLBACK");
    assertThat(run.getSamMode()).isEqualTo("FALLBACK");
    assertThat(run.getSummaryText()).isNotBlank();
    assertThat(run.getTimeSavedPercent()).isNotNull();
    assertThat(results.findByRunIdOrderById(run.getId())).hasSize(6);
    assertThat(risks.findByRunIdOrderById(run.getId())).hasSize(3);
    assertThat(decisions.findByRunId(run.getId())).isPresent();
    assertThat(decisions.findByRunId(run.getId()).orElseThrow().getApprovalStatus())
        .isEqualTo("PENDING_HUMAN_APPROVAL");

    List<WorkflowEvent> stream =
        events.findByTenderVersionIdOrderBySeq(version.getId());
    assertThat(stream).isNotEmpty();
    assertThat(stream.get(0).getType()).isEqualTo("STAGE_STARTED");
    assertThat(stream.get(stream.size() - 1).getType()).isEqualTo("JOB_COMPLETED");
    assertThat(stream.stream().map(WorkflowEvent::getSeq).toList()).isSorted();
    List<String> startedStages = new ArrayList<>();
    for (WorkflowEvent event : stream) {
      if ("STAGE_STARTED".equals(event.getType())) {
        startedStages.add(event.getStage());
      }
    }
    assertThat(startedStages).containsExactly("VALIDATE", "PARSE", "RENDER", "VISUAL",
        "EXTRACT", "VERIFY", "RISK", "DECIDE");
  }

  @Test
  void secondFullRunReusesRequirements() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion version = ingestV1(companyId);
    int runsBefore =
        runs.findByTenderVersionIdOrderByIdDesc(version.getId()).size();

    long first =
        workflows.startJob(version.getId(), companyId, WorkflowService.KIND_FULL);
    workflows.executeJob(first);
    List<Long> before = requirements.findByTenderVersionIdOrderById(version.getId())
        .stream().map(TenderRequirement::getId).toList();

    long second =
        workflows.startJob(version.getId(), companyId, WorkflowService.KIND_FULL);
    workflows.executeJob(second);

    WorkflowJob done = jobs.findById(second).orElseThrow();
    assertThat(done.getStatus()).isEqualTo("COMPLETED");
    List<Long> after = requirements.findByTenderVersionIdOrderById(version.getId())
        .stream().map(TenderRequirement::getId).toList();
    assertThat(after).isEqualTo(before);
    List<ComplianceRun> reruns =
        runs.findByTenderVersionIdOrderByIdDesc(version.getId());
    assertThat(reruns).hasSize(runsBefore + 2);
    assertThat(reruns.get(0).getDecision()).isEqualTo("NO_BID");
  }

  @Test
  void secondActiveJobIsRejected() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion version = ingestV1(companyId);

    long first = workflows.startJob(version.getId(), companyId, WorkflowService.KIND_FULL);
    assertThatThrownBy(() -> workflows.startJob(version.getId(), companyId,
        WorkflowService.KIND_FULL)).isInstanceOf(JobAlreadyActiveException.class);

    workflows.executeJob(first);
    long second = workflows.startJob(version.getId(), companyId, WorkflowService.KIND_FULL);

    assertThat(second).isNotEqualTo(first);
  }

  @Test
  void reverifySkipsAiStages() throws Exception {
    long companyId = seeder().seed(temp).companyId();
    TenderVersion version = ingestV1(companyId);
    long first = workflows.startJob(version.getId(), companyId, WorkflowService.KIND_FULL);
    workflows.executeJob(first);
    List<WorkflowEvent> before =
        events.findByTenderVersionIdOrderBySeq(version.getId());
    long maxSeq = before.get(before.size() - 1).getSeq();

    long jobId =
        workflows.startJob(version.getId(), companyId, WorkflowService.KIND_REVERIFY);
    workflows.executeJob(jobId);

    WorkflowJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo("COMPLETED");
    List<WorkflowEvent> fresh = events.findByTenderVersionIdOrderBySeq(version.getId())
        .stream().filter(event -> event.getSeq() > maxSeq).toList();
    List<String> startedStages = new ArrayList<>();
    for (WorkflowEvent event : fresh) {
      if ("STAGE_STARTED".equals(event.getType())) {
        startedStages.add(event.getStage());
      }
      assertThat(event.getType()).isNotEqualTo("REQUIREMENT_EXTRACTED");
    }
    assertThat(startedStages).containsExactly("VERIFY", "RISK", "DECIDE");
    assertThat(runs.findByTenderVersionIdOrderByIdDesc(version.getId())).hasSize(2);
  }

  @Test
  void recoveryRequeuesStaleJob() {
    TenderVersion version = bareVersion("stale");
    Tender tender = tenders.findById(version.getTenderId()).orElseThrow();
    WorkflowJob stale = jobs.save(new WorkflowJob(version.getId(), tender.getCompanyId(),
        WorkflowService.KIND_FULL, "RUNNING"));
    // Precondition: foreign RUNNING rows committed by earlier suites are settled,
    // so the recovery count pins exactly this test's stale job.
    for (WorkflowJob other : jobs.findByStatus("RUNNING")) {
      if (!other.getId().equals(stale.getId())) {
        other.setStatus("COMPLETED");
        other.setFinishedAt(OffsetDateTime.now());
        jobs.save(other);
      }
    }

    int recovered = workflows.recoverStaleJobs();

    assertThat(recovered).isEqualTo(1);
    WorkflowJob requeued = jobs.findById(stale.getId()).orElseThrow();
    assertThat(requeued.getStatus()).isEqualTo("QUEUED");
    assertThat(requeued.getAttempt()).isEqualTo(1);

    requeued.setStatus("RUNNING");
    requeued.setAttempt(3);
    jobs.save(requeued);
    workflows.recoverStaleJobs();

    assertThat(jobs.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
  }

  @Test
  void corruptStorageFailsJobHonestly() throws Exception {
    TenderVersion version = bareVersion("broken");
    Tender tender = tenders.findById(version.getTenderId()).orElseThrow();

    long jobId = workflows.startJob(version.getId(), tender.getCompanyId(),
        WorkflowService.KIND_FULL);

    assertThatThrownBy(() -> workflows.executeJob(jobId))
        .isInstanceOf(RuntimeException.class);
    WorkflowJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo("FAILED");
    assertThat(job.getErrorText()).isNotBlank();
    List<String> types = events.findByTenderVersionIdOrderBySeq(version.getId()).stream()
        .map(WorkflowEvent::getType).toList();
    assertThat(types).contains("STAGE_FAILED", "JOB_FAILED");
    assertThat(types).doesNotContain("JOB_COMPLETED");
  }
}
