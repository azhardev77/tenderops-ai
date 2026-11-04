package com.tenderops.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.document.DocumentPage;
import com.tenderops.document.DocumentPageRepository;
import com.tenderops.document.PageRenderer;
import com.tenderops.document.StorageService;
import com.tenderops.runs.ComplianceRun;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.security.CurrentUser;
import com.tenderops.security.RateLimiter;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderService;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.VisualRegion;
import com.tenderops.visual.VisualRegionRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

/**
 * Tenders, versions, pages, regions and requirements (section 9). All reads are
 * company-scoped (TC-SEC-02); money travels as strings (R1).
 */
@RestController
@RequestMapping("/api/v1")
public class TenderController {

  public record UploadResponse(long tenderId, long versionId, Long jobId, boolean duplicate) {
  }

  public record TenderListItem(long id, String title, String reference, int latestVersion,
      String latestDecision, OffsetDateTime updatedAt) {
  }

  public record TenderListResponse(List<TenderListItem> items, int page, int size, long total) {
  }

  public record VersionDto(long id, int versionNumber, String kind, String reference,
      String estimatedValue, String emdAmount, String tenderFee,
      OffsetDateTime submissionDeadline, int pageCount) {
  }

  public record RunSummaryDto(Long runId, long versionId, String decision, String readinessScore,
      String museMode, String samMode) {
  }

  public record TenderDetailResponse(long id, String title, String reference,
      OffsetDateTime createdAt, OffsetDateTime updatedAt, List<VersionDto> versions,
      RunSummaryDto latestRun) {
  }

  public record PageDto(int pageNo, String classification, boolean needsHumanReview,
      String reviewReason) {
  }

  public record PageTextResponse(int pageNo, String text) {
  }

  public record BboxDto(String x0, String y0, String x1, String y1) {
  }

  public record RegionDto(long id, int pageNo, String regionType, BboxDto bbox,
      String confidence, String source, String contentSource, String regionText,
      Object structuredTable, String cropUrl) {
  }

  public record RequirementDto(long id, String clauseRef, String category, boolean mandatory,
      String metric, String operator, String amountText, String amountValue, Integer countValue,
      Integer lookbackYears, List<String> domainTags, String certificateName,
      Integer certificateLevel, String conditionTree, String sourceDocument, int sourcePage,
      Long sourceRegionId, String originalQuote, boolean quoteVerified, String confidence,
      String amendmentAction, String targetClauseRef) {
  }

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final TenderRequirementRepository requirements;
  private final DocumentPageRepository pages;
  private final VisualRegionRepository regions;
  private final ComplianceRunRepository runs;
  private final TenderService tenderService;
  private final PageRenderer renderer;
  private final StorageService storage;
  private final RateLimiter rateLimiter;
  private final TenderopsProperties properties;
  private final ObjectMapper mapper;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TenderController(TenderRepository tenders, TenderVersionRepository versions,
      TenderRequirementRepository requirements, DocumentPageRepository pages,
      VisualRegionRepository regions, ComplianceRunRepository runs,
      TenderService tenderService, PageRenderer renderer, StorageService storage,
      RateLimiter rateLimiter, TenderopsProperties properties, ObjectMapper mapper) {
    this.tenders = tenders;
    this.versions = versions;
    this.requirements = requirements;
    this.pages = pages;
    this.regions = regions;
    this.runs = runs;
    this.tenderService = tenderService;
    this.renderer = renderer;
    this.storage = storage;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.mapper = mapper;
  }

  @PostMapping(value = "/tenders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file,
      HttpServletRequest request) {
    CurrentUser current = CurrentUser.require();
    if (!rateLimiter.tryConsume("upload:" + request.getRemoteAddr(),
        properties.rateLimit().uploadPerMinute())) {
      throw new RateLimitExceededException();
    }
    byte[] pdf;
    try {
      pdf = file.getBytes();
    } catch (IOException e) {
      throw new BadRequestException("Cannot read upload");
    }
    String name = file.getOriginalFilename() == null ? "tender.pdf"
        : file.getOriginalFilename();
    TenderService.TenderUpload done =
        tenderService.upload(current.companyId(), pdf, name);
    UploadResponse body = new UploadResponse(done.tenderId(), done.versionId(), done.jobId(),
        done.duplicate());
    if (done.duplicate()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }

  @GetMapping("/tenders")
  public TenderListResponse list(@RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    CurrentUser current = CurrentUser.require();
    int safeSize = Math.min(Math.max(size, 1), 100);
    int safePage = Math.max(page, 0);
    List<Tender> all = tenders.findByCompanyIdOrderByIdDesc(current.companyId());
    int from = Math.min(safePage * safeSize, all.size());
    int to = Math.min(from + safeSize, all.size());
    List<TenderListItem> items = new ArrayList<>();
    for (Tender tender : all.subList(from, to)) {
      List<TenderVersion> tenderVersions =
          versions.findByTenderIdOrderByVersionNumber(tender.getId());
      int latest = tenderVersions.isEmpty() ? 0
          : tenderVersions.get(tenderVersions.size() - 1).getVersionNumber();
      String decision = null;
      if (!tenderVersions.isEmpty()) {
        List<ComplianceRun> versionRuns = runs.findByTenderVersionIdOrderByIdDesc(
            tenderVersions.get(tenderVersions.size() - 1).getId());
        if (!versionRuns.isEmpty()) {
          decision = versionRuns.get(0).getDecision();
        }
      }
      items.add(new TenderListItem(tender.getId(), tender.getTitle(), tender.getReference(),
          latest, decision, tender.getUpdatedAt()));
    }
    return new TenderListResponse(items, safePage, safeSize, all.size());
  }

  @GetMapping("/tenders/{id}")
  public TenderDetailResponse getTender(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    Tender tender = tenders.findById(id)
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    current.requireCompany(tender.getCompanyId());
    List<TenderVersion> tenderVersions =
        versions.findByTenderIdOrderByVersionNumber(tender.getId());
    List<VersionDto> versionDtos = new ArrayList<>();
    RunSummaryDto latestRun = null;
    for (TenderVersion version : tenderVersions) {
      versionDtos.add(toVersion(version));
      List<ComplianceRun> versionRuns =
          runs.findByTenderVersionIdOrderByIdDesc(version.getId());
      if (!versionRuns.isEmpty()) {
        ComplianceRun run = versionRuns.get(0);
        latestRun = toRunSummary(run);
      }
    }
    return new TenderDetailResponse(tender.getId(), tender.getTitle(), tender.getReference(),
        tender.getCreatedAt(), tender.getUpdatedAt(), versionDtos, latestRun);
  }

  @GetMapping("/tender-versions/{id}")
  public VersionDto getVersion(@PathVariable long id) {
    TenderVersion version = visibleVersion(id);
    return toVersion(version);
  }

  @GetMapping("/tender-versions/{id}/pages")
  public List<PageDto> listPages(@PathVariable long id) {
    TenderVersion version = visibleVersion(id);
    List<PageDto> out = new ArrayList<>();
    for (DocumentPage page : pages.findByTenderVersionIdOrderByPageNo(version.getId())) {
      out.add(new PageDto(page.getPageNo(), page.getClassification(),
          page.isNeedsHumanReview(), page.getReviewReason()));
    }
    return out;
  }

  @GetMapping(value = "/tender-versions/{id}/pages/{n}/image", produces = "image/png")
  public ResponseEntity<byte[]> pageImage(@PathVariable long id, @PathVariable int n) {
    TenderVersion version = visibleVersion(id);
    DocumentPage page = pageByNo(version.getId(), n);
    DocumentPage rendered = renderer.ensureRendered(version, page);
    if (rendered.getRenderedPath() == null) {
      throw new NotFoundException("Page image unavailable");
    }
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
        .body(storage.load(rendered.getRenderedPath()));
  }

  @GetMapping("/tender-versions/{id}/pages/{n}/text")
  public PageTextResponse pageText(@PathVariable long id, @PathVariable int n) {
    TenderVersion version = visibleVersion(id);
    DocumentPage page = pageByNo(version.getId(), n);
    return new PageTextResponse(page.getPageNo(), page.getTextContent());
  }

  @GetMapping("/tender-versions/{id}/regions")
  public List<RegionDto> listRegions(@PathVariable long id,
      @RequestParam(required = false) Integer page) {
    TenderVersion version = visibleVersion(id);
    List<RegionDto> out = new ArrayList<>();
    for (DocumentPage docPage : pages.findByTenderVersionIdOrderByPageNo(version.getId())) {
      if (page != null && docPage.getPageNo() != page) {
        continue;
      }
      for (VisualRegion region : regions.findByPageIdOrderById(docPage.getId())) {
        out.add(toRegion(region, docPage.getPageNo()));
      }
    }
    return out;
  }

  @GetMapping(value = "/regions/{id}/crop", produces = "image/png")
  public ResponseEntity<byte[]> regionCrop(@PathVariable long id) {
    CurrentUser current = CurrentUser.require();
    VisualRegion region = regions.findById(id)
        .orElseThrow(() -> new NotFoundException("Region not found"));
    DocumentPage page = pages.findById(region.getPageId())
        .orElseThrow(() -> new NotFoundException("Region not found"));
    TenderVersion version = versions.findById(page.getTenderVersionId())
        .orElseThrow(() -> new NotFoundException("Region not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Region not found"));
    current.requireCompany(tender.getCompanyId());
    if (region.getCropPath() == null) {
      throw new NotFoundException("Region crop unavailable");
    }
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
        .body(storage.load(region.getCropPath()));
  }

  @GetMapping("/tender-versions/{id}/requirements")
  public List<RequirementDto> listRequirements(@PathVariable long id) {
    TenderVersion version = visibleVersion(id);
    List<RequirementDto> out = new ArrayList<>();
    for (TenderRequirement requirement :
        requirements.findByTenderVersionIdOrderById(version.getId())) {
      out.add(toRequirement(requirement));
    }
    return out;
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

  private DocumentPage pageByNo(long versionId, int pageNo) {
    for (DocumentPage page : pages.findByTenderVersionIdOrderByPageNo(versionId)) {
      if (page.getPageNo() == pageNo) {
        return page;
      }
    }
    throw new NotFoundException("Page not found");
  }

  private static VersionDto toVersion(TenderVersion version) {
    return new VersionDto(version.getId(), version.getVersionNumber(), version.getKind(),
        version.getReference(), plain(version.getEstimatedValue()),
        plain(version.getEmdAmount()), plain(version.getTenderFee()),
        version.getSubmissionDeadline(), version.getPageCount());
  }

  private static RunSummaryDto toRunSummary(ComplianceRun run) {
    return new RunSummaryDto(run.getId(), run.getTenderVersionId(), run.getDecision(),
        run.getReadinessScore() == null ? null : run.getReadinessScore().toPlainString(),
        run.getMuseMode(), run.getSamMode());
  }

  private RegionDto toRegion(VisualRegion region, int pageNo) {
    Object table = null;
    if (region.getStructuredTable() != null && !region.getStructuredTable().isBlank()) {
      try {
        table = mapper.readValue(region.getStructuredTable(), JsonNode.class);
      } catch (Exception e) {
        table = region.getStructuredTable();
      }
    }
    return new RegionDto(region.getId(), pageNo, region.getRegionType(),
        new BboxDto(region.getX0().toPlainString(), region.getY0().toPlainString(),
            region.getX1().toPlainString(), region.getY1().toPlainString()),
        region.getConfidence().toPlainString(), region.getSource(),
        region.getContentSource(), region.getRegionText(), table,
        region.getCropPath() == null ? null : "/api/v1/regions/" + region.getId() + "/crop");
  }

  private static RequirementDto toRequirement(TenderRequirement requirement) {
    List<String> tags = requirement.getDomainTags() == null
        || requirement.getDomainTags().isBlank() ? List.of()
            : List.of(requirement.getDomainTags().split(","));
    return new RequirementDto(requirement.getId(), requirement.getClauseRef(),
        requirement.getCategory(), requirement.isMandatory(), requirement.getMetric(),
        requirement.getOperator(), requirement.getAmountText(),
        plain(requirement.getAmountValue()), requirement.getCountValue(),
        requirement.getLookbackYears(), tags, requirement.getCertificateName(),
        requirement.getCertificateLevel(), requirement.getConditionTree(),
        requirement.getSourceDocument(), requirement.getSourcePage(),
        requirement.getSourceRegionId(), requirement.getOriginalQuote(),
        requirement.isQuoteVerified(),
        requirement.getConfidence() == null ? null
            : requirement.getConfidence().toPlainString(),
        requirement.getAmendmentAction(), requirement.getTargetClauseRef());
  }

  private static String plain(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
