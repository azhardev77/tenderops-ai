package com.tenderops.document;

import com.tenderops.common.FileSafetyValidator;
import com.tenderops.common.PdfRejectedException;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.NotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validated PDF intake: magic/size guards, SHA-256 dedup, encrypted/active-content rejection,
 * page-count cap, position-sorted text extraction, page classification.
 */
@Service
public class PdfIngestionService {

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final DocumentPageRepository pages;
  private final StorageService storage;
  private final TenderopsProperties properties;

  public PdfIngestionService(TenderRepository tenders, TenderVersionRepository versions,
      DocumentPageRepository pages, StorageService storage, TenderopsProperties properties) {
    this.tenders = tenders;
    this.versions = versions;
    this.pages = pages;
    this.storage = storage;
    this.properties = properties;
  }

  public record IngestResult(
      long tenderId, long versionId, int pageCount, boolean duplicate) {}

  /**
   * Ingests a new tender PDF as BASE version 1. An identical SHA-256 for the same company
   * returns the existing version without reprocessing (TC-ING-02).
   */
  @Transactional
  public IngestResult ingestNewTender(Long companyId, byte[] pdf, String fileName) {
    FileSafetyValidator.requirePdfMagic(pdf);
    FileSafetyValidator.requireSizeWithin(pdf, properties.limits().maxPdfBytes());
    String sha256 = FileSafetyValidator.sha256Hex(pdf);

    List<TenderVersion> existing =
        tenders.findVersionsByCompanyAndSha256(companyId, sha256);
    if (!existing.isEmpty()) {
      TenderVersion dup = existing.get(0);
      return new IngestResult(dup.getTenderId(), dup.getId(), dup.getPageCount(), true);
    }

    try (PDDocument doc = load(pdf)) {
      PdfActionInspector.rejectIfDangerous(doc);
      int pageCount = doc.getNumberOfPages();
      if (pageCount > properties.limits().maxPdfPages()) {
        throw new PdfRejectedException("PAGE_LIMIT",
            "PDF has " + pageCount + " pages, limit is " + properties.limits().maxPdfPages());
      }
      String storagePath = storage.store(pdf, ".pdf");
      Tender tender = tenders.save(new Tender(companyId, fileName, null));
      TenderVersion version = versions.save(new TenderVersion(tender.getId(), 1,
          TenderVersion.KIND_BASE, sha256, storagePath, pageCount));
      parsePages(version, doc);
      return new IngestResult(tender.getId(), version.getId(), pageCount, false);
    } catch (IOException e) {
      throw new PdfRejectedException("CORRUPT", "Cannot parse PDF: " + safeMessage(e));
    }
  }

  /**
   * Ingests an amendment PDF as the next version of an existing tender.
   * An identical SHA-256 for the same company returns the existing version.
   */
  @Transactional
  public IngestResult ingestNewVersion(Long tenderId, Long companyId, byte[] pdf,
      String fileName, String kind) {
    FileSafetyValidator.requirePdfMagic(pdf);
    FileSafetyValidator.requireSizeWithin(pdf, properties.limits().maxPdfBytes());
    String sha256 = FileSafetyValidator.sha256Hex(pdf);

    List<TenderVersion> existing =
        tenders.findVersionsByCompanyAndSha256(companyId, sha256);
    if (!existing.isEmpty()) {
      TenderVersion dup = existing.get(0);
      return new IngestResult(dup.getTenderId(), dup.getId(), dup.getPageCount(), true);
    }

    Tender tender = tenders.findByIdAndCompanyId(tenderId, companyId)
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    tender.setUpdatedAt(java.time.OffsetDateTime.now());
    tenders.save(tender);
    int nextNumber = versions.findByTenderIdOrderByVersionNumber(tender.getId()).stream()
        .mapToInt(TenderVersion::getVersionNumber).max().orElse(0) + 1;
    try (PDDocument doc = load(pdf)) {
      PdfActionInspector.rejectIfDangerous(doc);
      int pageCount = doc.getNumberOfPages();
      if (pageCount > properties.limits().maxPdfPages()) {
        throw new PdfRejectedException("PAGE_LIMIT",
            "PDF has " + pageCount + " pages, limit is " + properties.limits().maxPdfPages());
      }
      String storagePath = storage.store(pdf, ".pdf");
      TenderVersion version = versions.save(new TenderVersion(tender.getId(), nextNumber,
          kind, sha256, storagePath, pageCount));
      parsePages(version, doc);
      return new IngestResult(tender.getId(), version.getId(), pageCount, false);
    } catch (IOException e) {
      throw new PdfRejectedException("CORRUPT", "Cannot parse PDF: " + safeMessage(e));
    }
  }

  private void parsePages(TenderVersion version, PDDocument doc) throws IOException {
    boolean visionAvailable = properties.muse().imageInput()
        && !properties.metaApi().key().isBlank();
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    int pageNo = 1;
    for (PDPage page : doc.getPages()) {
      stripper.setStartPage(pageNo);
      stripper.setEndPage(pageNo);
      String text = stripper.getText(doc);
      PageImageScan.Result images = PageImageScan.scan(page);
      String kind = PageClassifier.classify(text, images.maxFraction());
      DocumentPage row = new DocumentPage(version.getId(), pageNo, text, kind);
      row.setExtractionConfidence(confidenceFor(kind, text));
      if (!kind.equals(DocumentPage.DIGITAL_TEXT) && !visionAvailable) {
        row.setNeedsHumanReview(true);
        row.setReviewReason(DocumentPage.OCR_UNAVAILABLE);
      }
      pages.save(row);
      pageNo++;
    }
  }

  private PDDocument load(byte[] pdf) throws IOException {
    try {
      return Loader.loadPDF(new RandomAccessReadBuffer(pdf));
    } catch (InvalidPasswordException e) {
      throw new PdfRejectedException("ENCRYPTED", "Encrypted PDFs are rejected");
    }
  }

  private BigDecimal confidenceFor(String kind, String text) {
    return switch (kind) {
      case DocumentPage.DIGITAL_TEXT -> BigDecimal.ONE
          .subtract(BigDecimal.valueOf(PageClassifier.replacementRatio(text)))
          .setScale(4, RoundingMode.HALF_UP);
      case DocumentPage.MIXED -> new BigDecimal("0.5000");
      default -> BigDecimal.ZERO.setScale(4);
    };
  }

  private String safeMessage(IOException e) {
    String message = e.getMessage();
    return message == null ? "unreadable bytes" : message.lines().findFirst().orElse("?");
  }
}
