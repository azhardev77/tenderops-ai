package com.tenderops.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.amendment.Amendment;
import com.tenderops.amendment.AmendmentDiff;
import com.tenderops.amendment.AmendmentRepository;
import com.tenderops.amendment.AmendmentTx;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.security.CurrentUser;
import com.tenderops.security.RateLimiter;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.workflow.WorkflowJobRepository;
import com.tenderops.workflow.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Amendment upload (versioned ingest + merge job) and clause diff reads. */
@RestController
@RequestMapping("/api/v1")
public class AmendmentController {

  public record AmendmentUploadResponse(long versionId, Long jobId, Long amendmentId,
      boolean duplicate) {
  }

  public record ModifiedDto(String clauseRef, boolean thresholdChanged,
      boolean operatorChanged, boolean conditionTreeChanged) {
  }

  public record DiffResponse(List<String> inserted, List<String> deleted,
      List<ModifiedDto> modified, boolean deadlineChanged, String prevDecision,
      String newDecision, String scoreDelta) {
  }

  public record AmendmentListItem(long id, long baseVersionId, long newVersionId,
      String prevDecision, String newDecision, String scoreDelta) {
  }

  @GetMapping("/tenders/{id}/amendments")
  public List<AmendmentListItem> list(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    Tender tender = tenders.findById(id)
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    current.requireCompany(tender.getCompanyId());
    List<AmendmentListItem> out = new ArrayList<>();
    for (Amendment amendment : amendments.findByTenderIdOrderByIdDesc(id)) {
      out.add(new AmendmentListItem(amendment.getId(), amendment.getBaseVersionId(),
          amendment.getNewVersionId(), amendment.getPrevDecision(),
          amendment.getNewDecision(),
          amendment.getScoreDelta() == null ? null
              : amendment.getScoreDelta().toPlainString()));
    }
    return out;
  }

  private final TenderRepository tenders;
  private final AmendmentRepository amendments;
  private final AmendmentTx amendmentTx;
  private final PdfIngestionService ingestion;
  private final WorkflowService workflows;
  private final WorkflowJobRepository jobs;
  private final RateLimiter rateLimiter;
  private final TenderopsProperties properties;
  private final ObjectMapper mapper;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public AmendmentController(TenderRepository tenders, AmendmentRepository amendments,
      AmendmentTx amendmentTx, PdfIngestionService ingestion, WorkflowService workflows,
      WorkflowJobRepository jobs, RateLimiter rateLimiter, TenderopsProperties properties,
      ObjectMapper mapper) {
    this.tenders = tenders;
    this.amendments = amendments;
    this.amendmentTx = amendmentTx;
    this.ingestion = ingestion;
    this.workflows = workflows;
    this.jobs = jobs;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.mapper = mapper;
  }

  @PostMapping(value = "/tenders/{id}/amendments",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<AmendmentUploadResponse> upload(@PathVariable long id,
      @RequestParam("file") MultipartFile file, @RequestParam("kind") String kind,
      HttpServletRequest request) {
    CurrentUser current = CurrentUser.require();
    if (!"CORRIGENDUM".equals(kind) && !"FULL_REVISION".equals(kind)) {
      throw new BadRequestException("kind must be CORRIGENDUM or FULL_REVISION");
    }
    if (!rateLimiter.tryConsume("upload:" + request.getRemoteAddr(),
        properties.rateLimit().uploadPerMinute())) {
      throw new RateLimitExceededException();
    }
    Tender tender = tenders.findById(id)
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    current.requireCompany(tender.getCompanyId());
    byte[] pdf;
    try {
      pdf = file.getBytes();
    } catch (IOException e) {
      throw new BadRequestException("Cannot read upload");
    }
    String name = file.getOriginalFilename() == null ? "amendment.pdf"
        : file.getOriginalFilename();
    PdfIngestionService.IngestResult ingest = ingestion.ingestNewVersion(id,
        current.companyId(), pdf, name, kind);
    if (ingest.duplicate()) {
      return ResponseEntity.ok(
          new AmendmentUploadResponse(ingest.versionId(), null, null, true));
    }
    Amendment amendment = amendmentTx.createForUpload(id, ingest.versionId());
    long jobId = workflows.startJob(ingest.versionId(), current.companyId(),
        WorkflowService.KIND_AMENDMENT);
    workflows.launchAsync(jobId);
    return ResponseEntity.status(HttpStatus.CREATED).body(
        new AmendmentUploadResponse(ingest.versionId(), jobId, amendment.getId(),
            false));
  }

  @GetMapping("/amendments/{id}/diff")
  public DiffResponse diff(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    Amendment amendment = amendments.findById(id)
        .orElseThrow(() -> new NotFoundException("Amendment not found"));
    Tender tender = tenders.findById(amendment.getTenderId())
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    current.requireCompany(tender.getCompanyId());
    if (amendment.getNewDecision() == null || amendment.getDiffJson() == null) {
      boolean active = !jobs.findByTenderVersionIdAndStatusIn(
          amendment.getNewVersionId(), List.of("QUEUED", "RUNNING")).isEmpty();
      if (active) {
        throw new JobAlreadyActiveException(amendment.getNewVersionId());
      }
      throw new AmendmentNotReadyException("Amendment has not been processed");
    }
    AmendmentDiff parsed;
    try {
      parsed = mapper.readValue(amendment.getDiffJson(), AmendmentDiff.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalStateException("Stored amendment diff is corrupt");
    }
    List<ModifiedDto> modified = new ArrayList<>();
    for (AmendmentDiff.ModifiedClause clause : parsed.modified()) {
      modified.add(new ModifiedDto(clause.clauseRef(), clause.thresholdChanged(),
          clause.operatorChanged(), clause.conditionTreeChanged()));
    }
    return new DiffResponse(parsed.inserted(), parsed.deleted(), modified,
        parsed.deadlineChanged(), parsed.prevDecision(), parsed.newDecision(),
        parsed.scoreDelta() == null ? null : parsed.scoreDelta().toPlainString());
  }
}
