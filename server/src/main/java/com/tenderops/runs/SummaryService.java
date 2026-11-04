package com.tenderops.runs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.ai.GroundingChecker;
import com.tenderops.ai.MetaApiTransport;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.AiCallLogger;
import com.tenderops.web.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executive bid summary (section 6.12): Muse strictly from structured verified facts
 * with a Java grounding check, else a labelled template fallback. The live path is
 * UNVERIFIED until a keyed probe exercises it.
 */
@Service
public class SummaryService {

  private static final Logger LOG = LoggerFactory.getLogger(SummaryService.class);

  private final ComplianceRunRepository runs;
  private final ComplianceResultRepository results;
  private final TenderRequirementRepository requirements;
  private final TenderVersionRepository versions;
  private final TenderopsProperties properties;
  private final ObjectMapper mapper;
  private final MetaApiTransport transport;
  private final AiCallLogger callLogger;

  public SummaryService(ComplianceRunRepository runs, ComplianceResultRepository results,
      TenderRequirementRepository requirements, TenderVersionRepository versions,
      TenderopsProperties properties, ObjectMapper mapper, MetaApiTransport transport,
      AiCallLogger callLogger) {
    this.runs = runs;
    this.results = results;
    this.requirements = requirements;
    this.versions = versions;
    this.properties = properties;
    this.mapper = mapper;
    this.transport = transport;
    this.callLogger = callLogger;
  }

  @Transactional
  public void generateAndAttach(long runId) {
    ComplianceRun run =
        runs.findById(runId).orElseThrow(() -> new NotFoundException("Run not found"));
    TenderVersion version = versions.findById(run.getTenderVersionId())
        .orElseThrow(() -> new NotFoundException("Version not found"));
    List<ComplianceResult> rows = results.findByRunIdOrderById(runId);
    Map<Long, TenderRequirement> byId = new LinkedHashMap<>();
    for (TenderRequirement requirement :
        requirements.findByTenderVersionIdOrderById(version.getId())) {
      byId.put(requirement.getId(), requirement);
    }
    List<String> facts = buildFacts(version, run, rows, byId);
    String key = properties.metaApi().key();
    String text;
    boolean warning = false;
    String source = "FALLBACK_TEMPLATE";
    if (key == null || key.isBlank()) {
      text = template(version, run, rows);
    } else {
      try {
        long start = System.currentTimeMillis();
        text = museSummary(facts, key);
        long latency = System.currentTimeMillis() - start;
        callLogger.log(runId, "MUSE", properties.metaApi().museModel(), "exec-summary",
            "OK", 1, latency, "LIVE", null);
        warning = !GroundingChecker.ungroundedNumbers(text, facts).isEmpty();
        source = "MUSE";
      } catch (Exception e) {
        LOG.warn("Muse summary failed, template fallback: {}", e.getMessage());
        callLogger.log(runId, "MUSE", properties.metaApi().museModel(), "exec-summary",
            "FALLBACK_USED", 1, null, "FALLBACK", e.getMessage());
        text = template(version, run, rows);
      }
    }
    run.setSummaryText(text);
    run.setGroundingWarning(warning);
    run.setSummarySource(source);
    runs.save(run);
  }

  private List<String> buildFacts(TenderVersion version, ComplianceRun run,
      List<ComplianceResult> rows, Map<Long, TenderRequirement> byId) {
    List<String> facts = new ArrayList<>();
    facts.add("Tender reference "
        + (version.getReference() == null ? "unknown" : version.getReference()));
    facts.add("Submission deadline " + (version.getSubmissionDeadline() == null
        ? "unknown" : version.getSubmissionDeadline().toString()));
    facts.add("Estimated value " + (version.getEstimatedValue() == null ? "unknown"
        : version.getEstimatedValue().toPlainString()));
    for (ComplianceResult row : rows) {
      TenderRequirement requirement = byId.get(row.getRequirementId());
      String clause = requirement == null ? "?" : requirement.getClauseRef();
      facts.add("Clause " + clause + ": " + row.getVerdict());
    }
    facts.add("Readiness score "
        + (run.getReadinessScore() == null ? "unknown" : run.getReadinessScore()));
    facts.add("Decision " + run.getDecision());
    return facts;
  }

  private String template(TenderVersion version, ComplianceRun run,
      List<ComplianceResult> rows) {
    int pass = 0;
    int fail = 0;
    int unknown = 0;
    int review = 0;
    for (ComplianceResult row : rows) {
      if ("PASS".equals(row.getVerdict())) {
        pass++;
      } else if ("FAIL".equals(row.getVerdict())) {
        fail++;
      } else if ("UNKNOWN".equals(row.getVerdict())) {
        unknown++;
      } else {
        review++;
      }
    }
    return "Bid summary (rule-based fallback template). Tender "
        + (version.getReference() == null ? "unknown" : version.getReference()) + ": "
        + rows.size() + " requirements evaluated: " + pass + " PASS, " + fail + " FAIL, "
        + unknown + " UNKNOWN, " + review + " NEEDS_REVIEW. Recommendation: "
        + run.getDecision() + ". Readiness score (rule-based): " + run.getReadinessScore()
        + ".";
  }

  private String museSummary(List<String> facts, String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.metaApi().museModel());
    body.put("input", "Write a concise executive bid summary strictly from these "
        + "verified facts. Use no other numbers, amounts or dates. Facts: "
        + String.join(" | ", facts));
    body.put("max_output_tokens", 600);
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize summary request", e);
    }
    String url = properties.metaApi().baseUrl() + properties.metaApi().responsesPath();
    String response = transport.postJson(url, key, json,
        properties.metaApi().requestTimeoutMs(), properties.metaApi().maxAttempts(),
        properties.metaApi().backoffInitialMs(), outcome -> {
        });
    return MetaApiTransport.extractOutputText(mapper, response);
  }
}
