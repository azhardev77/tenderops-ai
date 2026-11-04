package com.tenderops.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.common.FileSafetyValidator;
import com.tenderops.common.MoneyParseException;
import com.tenderops.common.MoneyUtils;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.document.DocumentPage;
import com.tenderops.document.DocumentPageRepository;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.AiCallLogger;
import com.tenderops.visual.VisualRegion;
import com.tenderops.visual.VisualRegionRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extraction orchestration: per-page live Muse (key present) with rule-based fallback,
 * item validation, R5 quote verification, Java amount conversion (R6), metadata merge,
 * fingerprinting, persistence. Unreadable pages are skipped (no text to extract).
 */
@Service
public class ExtractionService {

  private static final Logger LOG = LoggerFactory.getLogger(ExtractionService.class);

  private static final Set<String> METRICS = Set.of("AVG_ANNUAL_TURNOVER", "NET_WORTH",
      "SIMILAR_PROJECTS_COUNT_VALUE", "SIMILAR_PROJECTS_80_50_40", "CERTIFICATE_VALID",
      "SOLVENCY_CERTIFICATE", "OTHER");
  private static final Set<String> OPERATORS = Set.of("GTE", "GT", "LTE", "LT", "EQ");
  private static final Set<String> ACTIONS =
      Set.of("NONE", "SUBSTITUTE", "INSERT", "DELETE", "DEADLINE_CHANGE");

  private final TenderopsProperties properties;
  private final ObjectMapper objectMapper;
  private final MuseSparkExtractor muse;
  private final RuleBasedFallbackExtractor fallback;
  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final TenderRequirementRepository requirements;
  private final DocumentPageRepository pages;
  private final VisualRegionRepository regions;
  private final AiCallLogger callLogger;

  public ExtractionService(TenderopsProperties properties, ObjectMapper objectMapper,
      MuseSparkExtractor muse, RuleBasedFallbackExtractor fallback, TenderRepository tenders,
      TenderVersionRepository versions, TenderRequirementRepository requirements,
      DocumentPageRepository pages, VisualRegionRepository regions, AiCallLogger callLogger) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.muse = muse;
    this.fallback = fallback;
    this.tenders = tenders;
    this.versions = versions;
    this.requirements = requirements;
    this.pages = pages;
    this.regions = regions;
    this.callLogger = callLogger;
  }

  public record ExtractResult(long versionId, String museMode,
      List<TenderRequirement> requirements) {}

  @Transactional
  public ExtractResult extractVersion(TenderVersion version) {
    requirements.deleteByTenderVersionId(version.getId());
    Tender tender =
        tenders.findById(version.getTenderId()).orElseThrow();
    boolean museLive = false;
    List<TenderRequirement> all = new ArrayList<>();
    String reference = null;
    String estimated = null;
    String emd = null;
    String fee = null;
    String deadline = null;
    String key = properties.metaApi().key();
    List<DocumentPage> docPages =
        pages.findByTenderVersionIdOrderByPageNo(version.getId());
    Map<Long, VisualRegion> regionsById = new HashMap<>();
    for (DocumentPage page : docPages) {
      for (VisualRegion region : regions.findByPageIdOrderById(page.getId())) {
        regionsById.put(region.getId(), region);
      }
    }
    for (DocumentPage page : docPages) {
      if (page.isNeedsHumanReview() || page.getTextContent() == null
          || page.getTextContent().isBlank()) {
        continue;
      }
      Extraction.Result result;
      if (key != null && !key.isBlank()) {
        try {
          result = muse.extract(tender.getTitle(), page.getPageNo(), page.getTextContent());
          museLive = true;
        } catch (MuseFailedException e) {
          LOG.warn("Muse failed on version {} page {}; rule-based fallback: {}",
              version.getId(), page.getPageNo(), e.getMessage());
          callLogger.log(null, "MUSE", properties.metaApi().museModel(),
              "muse-extract:fallback", "FALLBACK_USED", 1, null, "FALLBACK", e.getMessage());
          result = fallback.extract(tender.getTitle(), page.getPageNo(),
              page.getTextContent());
        }
      } else {
        result = fallback.extract(tender.getTitle(), page.getPageNo(),
            page.getTextContent());
      }
      if (result.tender() != null) {
        reference = firstNonNull(reference, result.tender().reference());
        estimated = firstNonNull(estimated, result.tender().estimatedValueText());
        emd = firstNonNull(emd, result.tender().emdText());
        fee = firstNonNull(fee, result.tender().tenderFeeText());
        deadline = firstNonNull(deadline, result.tender().deadlineText());
      }
      for (Extraction.RequirementItem item : result.requirements()) {
        all.add(requirements.save(toEntity(item, version.getId(), tender.getTitle(),
            page.getPageNo(), page.getTextContent(), regionsById)));
      }
    }
    applyMetadata(version, tender, reference, estimated, emd, fee, deadline);
    versions.save(version);
    tenders.save(tender);
    return new ExtractResult(version.getId(), museLive ? "LIVE" : "FALLBACK", all);
  }

  private String firstNonNull(String current, String candidate) {
    if (current != null) {
      return current;
    }
    return candidate == null || candidate.isBlank() ? null : candidate.trim();
  }

  private void applyMetadata(TenderVersion version, Tender tender, String reference,
      String estimated, String emd, String fee, String deadline) {
    if (reference != null) {
      version.setReference(reference);
      tender.setReference(reference);
    }
    if (estimated != null) {
      try {
        version.setEstimatedValue(MoneyUtils.parse(estimated));
      } catch (MoneyParseException e) {
        LOG.warn("Unparseable estimated value '{}': {}", estimated, e.getMessage());
      }
    }
    if (emd != null) {
      try {
        version.setEmdAmount(MoneyUtils.parse(emd));
      } catch (MoneyParseException e) {
        LOG.warn("Unparseable EMD '{}': {}", emd, e.getMessage());
      }
    }
    if (fee != null) {
      try {
        version.setTenderFee(MoneyUtils.parse(fee));
      } catch (MoneyParseException e) {
        LOG.warn("Unparseable tender fee '{}': {}", fee, e.getMessage());
      }
    }
    if (deadline != null) {
      try {
        version.setSubmissionDeadline(DeadlineParser.parse(deadline));
      } catch (DeadlineParseException e) {
        LOG.warn("Unparseable deadline '{}': {}", deadline, e.getMessage());
      }
    }
  }

  private TenderRequirement toEntity(Extraction.RequirementItem item, long versionId,
      String sourceDocument, int sourcePage, String pageText,
      Map<Long, VisualRegion> regionsById) {
    TenderRequirement row = new TenderRequirement();
    row.setTenderVersionId(versionId);
    row.setClauseRef(item.clauseRef().trim());
    row.setCategory(item.category() == null || item.category().isBlank() ? "OTHER"
        : item.category().trim().toUpperCase(Locale.ROOT));
    boolean mandatory = item.mandatory() == null || item.mandatory();
    row.setMandatory(mandatory);
    row.setMandatoryAmbiguous(
        Boolean.TRUE.equals(item.mandatoryAmbiguous()) || item.mandatory() == null);

    String metric = item.metric() == null ? "OTHER"
        : item.metric().trim().toUpperCase(Locale.ROOT);
    if (!METRICS.contains(metric)) {
      metric = "OTHER";
    }
    String operator = item.operator() == null || item.operator().isBlank() ? "GTE"
        : item.operator().trim().toUpperCase(Locale.ROOT);
    if (!OPERATORS.contains(operator)) {
      metric = "OTHER";
      operator = "GTE";
    }

    String treeJson = null;
    if (item.conditionTree() != null) {
      ConditionNode canonical = item.conditionTree().canonical();
      if (validTree(canonical)) {
        try {
          treeJson = objectMapper.writeValueAsString(canonical);
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot serialize condition tree", e);
        }
      } else {
        metric = "OTHER";
      }
    }

    String domains = null;
    if (item.domainTags() != null && !item.domainTags().isEmpty()) {
      List<String> tags = new ArrayList<>(item.domainTags());
      Collections.sort(tags);
      domains = String.join(",", tags);
    }

    BigDecimal amountValue = null;
    if (!"OTHER".equals(metric) && item.amountText() != null
        && !item.amountText().isBlank()) {
      if (!"INR".equals(MoneyUtils.currencyOf(item.amountText()))) {
        metric = "OTHER";
      } else {
        try {
          amountValue = MoneyUtils.parse(item.amountText());
        } catch (MoneyParseException e) {
          metric = "OTHER";
        }
      }
    }

    row.setMetric(metric);
    row.setOperator(operator);
    row.setAmountText(item.amountText());
    row.setAmountValue(amountValue);
    row.setCurrency("INR");
    row.setCountValue(item.countValue());
    row.setLookbackYears(item.lookbackYears());
    row.setDomainTags(domains);
    row.setCertificateName(item.certificateName());
    row.setCertificateLevel(item.certificateLevel());
    row.setConditionTree(treeJson);
    row.setSourceDocument(sourceDocument);
    row.setSourcePage(sourcePage);
    VisualRegion linked = item.sourceRegionId() == null ? null
        : regionsById.get(item.sourceRegionId());
    if (item.sourceRegionId() != null && linked == null) {
      LOG.warn("Ignoring bogus sourceRegionId {} for clause {} (no such region"
          + " on version {})", item.sourceRegionId(), item.clauseRef(), versionId);
    }
    row.setSourceRegionId(linked == null ? null : item.sourceRegionId());

    boolean visionOnly = linked != null
        && VisualRegion.CONTENT_MUSE.equals(linked.getContentSource());
    Optional<QuoteVerifier.VerifiedQuote> verified = visionOnly ? Optional.empty()
        : QuoteVerifier.verify(pageText, item.originalQuote());
    if (verified.isPresent()) {
      row.setOriginalQuote(verified.get().rawSpan());
      row.setQuoteVerified(true);
    } else {
      row.setOriginalQuote("");
      row.setQuoteVerified(false);
    }

    if (item.confidence() != null) {
      row.setConfidence(BigDecimal.valueOf(item.confidence()).setScale(4,
          RoundingMode.HALF_UP));
    }
    String action = item.amendmentAction() == null || item.amendmentAction().isBlank()
        ? "NONE"
        : item.amendmentAction().trim().toUpperCase(Locale.ROOT);
    row.setAmendmentAction(ACTIONS.contains(action) ? action : "NONE");
    row.setTargetClauseRef(item.targetClauseRef());

    row.setFingerprint(RequirementFingerprint.of(row.getClauseRef(), metric, operator,
        row.getAmountText(), row.getCountValue(), row.getLookbackYears(), domains,
        row.getCertificateName(), row.getCertificateLevel(), treeJson, mandatory));
    return row;
  }

  private boolean validTree(ConditionNode node) {
    if (node.isBranch()) {
      if (!"AND".equals(node.op()) && !"OR".equals(node.op())) {
        return false;
      }
      if (node.children() == null || node.children().isEmpty()) {
        return false;
      }
      for (ConditionNode child : node.children()) {
        if (child == null || !validTree(child)) {
          return false;
        }
      }
      return true;
    }
    return node.metric() != null && METRICS.contains(node.metric())
        && node.operator() != null && OPERATORS.contains(node.operator());
  }
}
