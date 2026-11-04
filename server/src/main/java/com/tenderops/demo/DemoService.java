package com.tenderops.demo;

import com.tenderops.amendment.Amendment;
import com.tenderops.amendment.AmendmentRepository;
import com.tenderops.amendment.AmendmentTx;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.runs.ComplianceResult;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderService;
import com.tenderops.tender.TenderVersion;
import com.tenderops.workflow.WorkflowJob;
import com.tenderops.workflow.WorkflowJobRepository;
import com.tenderops.workflow.WorkflowService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * One-click demo flows (dev/demo/test only): bundled RFP, bundled corrigendum and the
 * live-check probe used by scripts/run-live-check. Sample bytes are generated on demand
 * so the endpoints work regardless of the seeder's output directory.
 */
@Service
public class DemoService {

  public record CorrigendumLoad(long versionId, Long jobId, Long amendmentId,
      boolean duplicate) {
  }

  public record LiveCheck(String status, String museMode, String samMode, String decision,
      String readinessScore, Map<String, String> verdicts) {
  }

  private final TenderPdfGenerator generator;
  private final TenderService tenderService;
  private final PdfIngestionService ingestion;
  private final AmendmentTx amendmentTx;
  private final AmendmentRepository amendments;
  private final WorkflowService workflows;
  private final WorkflowJobRepository jobs;
  private final ComplianceRunRepository runs;
  private final ComplianceResultRepository results;
  private final TenderRequirementRepository requirements;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public DemoService(TenderPdfGenerator generator, TenderService tenderService,
      PdfIngestionService ingestion, AmendmentTx amendmentTx,
      AmendmentRepository amendments, WorkflowService workflows, WorkflowJobRepository jobs,
      ComplianceRunRepository runs, ComplianceResultRepository results,
      TenderRequirementRepository requirements) {
    this.generator = generator;
    this.tenderService = tenderService;
    this.ingestion = ingestion;
    this.amendmentTx = amendmentTx;
    this.amendments = amendments;
    this.workflows = workflows;
    this.jobs = jobs;
    this.runs = runs;
    this.results = results;
    this.requirements = requirements;
  }

  public List<String> samplePdfs() {
    return List.of(TenderPdfGenerator.V1_FILE, TenderPdfGenerator.CORR_FILE,
        TenderPdfGenerator.VARIANT_B_FILE);
  }

  public TenderService.TenderUpload loadRfp(long companyId) {
    byte[] pdf = sampleBytes(TenderPdfGenerator.V1_FILE);
    return tenderService.upload(companyId, pdf, TenderPdfGenerator.V1_FILE);
  }

  /** Not transactional: ingest, amendment row and job-start commit separately. */
  public CorrigendumLoad loadCorrigendum(long companyId, long tenderId) {
    byte[] pdf = sampleBytes(TenderPdfGenerator.CORR_FILE);
    PdfIngestionService.IngestResult ingest = ingestion.ingestNewVersion(tenderId, companyId,
        pdf, TenderPdfGenerator.CORR_FILE, TenderVersion.KIND_CORRIGENDUM);
    if (ingest.duplicate()) {
      Long amendmentId = amendments.findByNewVersionId(ingest.versionId())
          .map(Amendment::getId).orElse(null);
      return new CorrigendumLoad(ingest.versionId(), null, amendmentId, true);
    }
    Amendment amendment = amendmentTx.createForUpload(tenderId, ingest.versionId());
    long jobId = workflows.startJob(ingest.versionId(), companyId,
        WorkflowService.KIND_AMENDMENT);
    workflows.launchAsync(jobId);
    return new CorrigendumLoad(ingest.versionId(), jobId, amendment.getId(), false);
  }

  /**
   * Runs the golden flow for the caller's company and reports the outcome with per
   * component aiMode. Reuses the existing RFP version on duplicate loads.
   */
  public LiveCheck liveCheck(long companyId) {
    TenderService.TenderUpload upload = loadRfp(companyId);
    awaitCompletion(upload.versionId(), 180_000);
    List<ComplianceRun> versionRuns =
        runs.findByTenderVersionIdOrderByIdDesc(upload.versionId());
    if (versionRuns.isEmpty()) {
      String status = latestStatus(upload.versionId());
      return new LiveCheck(status, "FALLBACK", "FALLBACK", null, null, Map.of());
    }
    ComplianceRun run = versionRuns.get(0);
    Map<Long, String> clauses = new LinkedHashMap<>();
    for (TenderRequirement requirement :
        requirements.findByTenderVersionIdOrderById(upload.versionId())) {
      clauses.put(requirement.getId(), requirement.getClauseRef());
    }
    Map<String, String> verdicts = new LinkedHashMap<>();
    for (ComplianceResult row : results.findByRunIdOrderById(run.getId())) {
      verdicts.put(clauses.getOrDefault(row.getRequirementId(), "?"), row.getVerdict());
    }
    return new LiveCheck(latestStatus(upload.versionId()), run.getMuseMode(),
        run.getSamMode(), run.getDecision(),
        run.getReadinessScore() == null ? null : run.getReadinessScore().toPlainString(),
        verdicts);
  }

  private void awaitCompletion(long versionId, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      String status = latestStatus(versionId);
      if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
        return;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting job completion");
      }
    }
    throw new IllegalStateException("Timed out awaiting job completion");
  }

  private String latestStatus(long versionId) {
    return jobs.findByTenderVersionIdOrderByIdDesc(versionId).stream()
        .max(Comparator.comparing(WorkflowJob::getId)).map(WorkflowJob::getStatus)
        .orElse("NONE");
  }

  private byte[] sampleBytes(String fileName) {
    Path dir = null;
    try {
      dir = Files.createTempDirectory("demo-samples");
      generator.generateSamples(dir);
      return Files.readAllBytes(dir.resolve(fileName));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot generate bundled sample " + fileName, e);
    } finally {
      if (dir != null) {
        try {
          Files.deleteIfExists(dir.resolve(TenderPdfGenerator.V1_FILE));
          Files.deleteIfExists(dir.resolve(TenderPdfGenerator.CORR_FILE));
          Files.deleteIfExists(dir.resolve(TenderPdfGenerator.VARIANT_B_FILE));
          Files.deleteIfExists(dir.resolve(TenderPdfGenerator.TRUTH_FILE));
          Files.deleteIfExists(dir);
        } catch (IOException ignored) {
          // Best-effort temp cleanup; the OS reclaims the remainder.
        }
      }
    }
  }

}
