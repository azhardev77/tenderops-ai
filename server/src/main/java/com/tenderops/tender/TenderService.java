package com.tenderops.tender;

import com.tenderops.document.PdfIngestionService;
import com.tenderops.workflow.WorkflowService;
import org.springframework.stereotype.Service;

/**
 * Tender upload orchestration. Deliberately NOT transactional: ingest and job-start
 * each commit in their own transaction, and the async job must only launch after
 * both are visible.
 */
@Service
public class TenderService {

  public record TenderUpload(long tenderId, long versionId, Long jobId, boolean duplicate) {
  }

  private final PdfIngestionService ingestion;
  private final WorkflowService workflows;

  public TenderService(PdfIngestionService ingestion, WorkflowService workflows) {
    this.ingestion = ingestion;
    this.workflows = workflows;
  }

  /** Ingests a BASE PDF and starts the FULL pipeline, unless it is a duplicate. */
  public TenderUpload upload(long companyId, byte[] pdf, String fileName) {
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, fileName);
    if (ingest.duplicate()) {
      return new TenderUpload(ingest.tenderId(), ingest.versionId(), null, true);
    }
    long jobId = workflows.startJob(ingest.versionId(), companyId,
        WorkflowService.KIND_FULL);
    workflows.launchAsync(jobId);
    return new TenderUpload(ingest.tenderId(), ingest.versionId(), jobId, false);
  }
}
