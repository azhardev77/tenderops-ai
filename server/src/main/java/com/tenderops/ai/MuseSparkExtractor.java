package com.tenderops.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.common.PromptSanitizer;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.visual.AiCallLogger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Live Muse Spark requirement extractor (Responses shape + strict schema live-verified).
 * Sends the system prompt + R4-sandboxed excerpts, requests strict JSON-schema output when
 * enabled, validates (confidence range, clause keys, tree shape), retries validation
 * failures twice with error feedback, then throws {@link MuseFailedException} so the
 * caller falls back. Amounts stay raw text (R6); quotes verified by Java (R5).
 */
@Component
public class MuseSparkExtractor implements RequirementExtractor {

  static final int MAX_VALIDATION_ATTEMPTS = 3;

  private final TenderopsProperties properties;
  private final ObjectMapper objectMapper;
  private final AiCallLogger callLogger;
  private final MetaApiTransport transport;

  public MuseSparkExtractor(TenderopsProperties properties, ObjectMapper objectMapper,
      AiCallLogger callLogger, MetaApiTransport transport) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.callLogger = callLogger;
    this.transport = transport;
  }

  @Override
  public Extraction.Result extract(String sourceDocument, int sourcePage, String pageText) {
    String key = properties.metaApi().key();
    if (key == null || key.isBlank()) {
      throw new MuseFailedException("MODEL_API_KEY is empty; Muse unavailable");
    }
    String excerpt = PromptSanitizer.wrapExcerpt(sourcePage, pageText == null ? "" : pageText);
    List<String> feedback = new ArrayList<>();
    for (int attempt = 1; attempt <= MAX_VALIDATION_ATTEMPTS; attempt++) {
      String outputText = post(sourceDocument, sourcePage, excerpt, feedback, key, attempt);
      try {
        return parseAndValidate(outputText);
      } catch (ValidationFailure e) {
        feedback.addAll(e.errors());
        if (attempt >= MAX_VALIDATION_ATTEMPTS) {
          throw new MuseFailedException(
              "Muse output invalid after " + MAX_VALIDATION_ATTEMPTS + " attempts: "
                  + String.join("; ", e.errors()));
        }
      }
    }
    throw new MuseFailedException("Muse extraction failed");
  }

  private String post(String sourceDocument, int sourcePage, String excerpt,
      List<String> feedback, String key, int validationAttempt) {
    String purpose = "muse-extract:p" + sourcePage + "/v" + validationAttempt;
    StringBuilder userText = new StringBuilder(systemPrompt());
    userText.append("\n\n").append(excerpt);
    if (!feedback.isEmpty()) {
      userText.append(
          "\n\nYour previous response was invalid. Fix every issue below and return "
              + "ONLY the corrected JSON object, no prose:\n");
      for (String error : feedback) {
        userText.append("- ").append(error).append("\n");
      }
    }
    String bodyJson = requestJson(userText.toString());
    String url = properties.metaApi().baseUrl() + properties.metaApi().responsesPath();
    MetaApiTransport.AttemptListener listener = outcome -> callLogger.log(null, "MUSE",
        properties.metaApi().museModel(), purpose, outcome.status(), outcome.attempt(),
        outcome.latencyMs(), "OK".equals(outcome.status()) ? "LIVE" : "FALLBACK",
        outcome.error());
    try {
      String responseBody = transport.postJson(url, key, bodyJson,
          properties.metaApi().requestTimeoutMs(), properties.metaApi().maxAttempts(),
          properties.metaApi().backoffInitialMs(), listener);
      return MetaApiTransport.extractOutputText(objectMapper, responseBody);
    } catch (TransportFailedException | TransportFatalException e) {
      throw new MuseFailedException("Muse call failed: " + e.getMessage(), e);
    } catch (TransportFormatException e) {
      callLogger.log(null, "MUSE", properties.metaApi().museModel(), purpose, "FORMAT_ERROR",
          1, null, "FALLBACK", e.getMessage());
      throw new MuseFailedException("Muse response has no output text: " + e.getMessage(), e);
    }
  }

  private String systemPrompt() {
    return "You extract tender requirements as JSON. " + PromptSanitizer.systemPromptStatement()
        + " Return amounts as raw text exactly as written (never compute). Quote clauses "
        + "verbatim. Confidence is your uncalibrated self-assessment in [0.0, 1.0]. "
        + "JSON shape: {\"tender\": {\"reference\", \"estimatedValueText\", \"emdText\", "
        + "\"tenderFeeText\", \"deadlineText\"}, \"requirements\": [{\"clauseRef\", "
        + "\"category\", \"mandatory\", \"mandatoryAmbiguous\", \"metric\", \"operator\", "
        + "\"amountText\", \"countValue\", \"lookbackYears\", \"domainTags\", "
        + "\"certificateName\", \"certificateLevel\", \"conditionTree\", \"sourceRegionId\", "
        + "\"originalQuote\", \"confidence\", \"amendmentAction\", \"targetClauseRef\"}]}. "
        + "Metrics: AVG_ANNUAL_TURNOVER, NET_WORTH, SIMILAR_PROJECTS_COUNT_VALUE, "
        + "SIMILAR_PROJECTS_80_50_40, CERTIFICATE_VALID, SOLVENCY_CERTIFICATE, OTHER. "
        + "Operators: GTE, GT, LTE, LT, EQ. conditionTree is a JSON-encoded string of "
        + "the AND/OR tree ({\"op\", \"children\"} or null when the clause has no "
        + "AND/OR structure); recursive object schemas are unsupported, so never "
        + "emit it as an object.";
  }

  private String requestJson(String userText) {
    Map<String, Object> textPart = new LinkedHashMap<>();
    textPart.put("type", "input_text");
    textPart.put("text", userText);
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("type", "message");
    message.put("role", "user");
    message.put("content", List.of(textPart));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.metaApi().museModel());
    body.put("input", List.of(message));
    if (properties.muse().structuredOutput()) {
      Map<String, Object> format = new LinkedHashMap<>();
      format.put("type", "json_schema");
      format.put("name", "tender_extraction");
      format.put("strict", true);
      format.put("schema", extractionSchema());
      Map<String, Object> text = new LinkedHashMap<>();
      text.put("format", format);
      body.put("text", text);
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new MuseFailedException("Cannot serialize Muse request", e);
    }
  }

  /**
   * Strict-mode schema (live-verified 2026-11-05): every object lists ALL its
   * property keys in {@code required} and sets {@code additionalProperties} false;
   * optionals are nullable. Recursive schemas are unsupported, so the condition
   * tree travels as a JSON-encoded string.
   */
  private Map<String, Object> extractionSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("clauseRef", Map.of("type", "string"));
    props.put("category", nullable("string"));
    props.put("mandatory", nullable("boolean"));
    props.put("mandatoryAmbiguous", nullable("boolean"));
    props.put("metric", nullable("string"));
    props.put("operator", nullable("string"));
    props.put("amountText", nullable("string"));
    props.put("countValue", nullable("integer"));
    props.put("lookbackYears", nullable("integer"));
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put("type", List.of("array", "null"));
    tags.put("items", Map.of("type", "string"));
    props.put("domainTags", tags);
    props.put("certificateName", nullable("string"));
    props.put("certificateLevel", nullable("integer"));
    props.put("conditionTree", nullable("string"));
    props.put("sourceRegionId", nullable("integer"));
    props.put("originalQuote", nullable("string"));
    props.put("confidence", Map.of("type", "number"));
    props.put("amendmentAction", nullable("string"));
    props.put("targetClauseRef", nullable("string"));
    Map<String, Object> requirement = new LinkedHashMap<>();
    requirement.put("type", "object");
    requirement.put("properties", props);
    requirement.put("required", new ArrayList<>(props.keySet()));
    requirement.put("additionalProperties", false);
    Map<String, Object> tenderProps = new LinkedHashMap<>();
    tenderProps.put("reference", nullable("string"));
    tenderProps.put("estimatedValueText", nullable("string"));
    tenderProps.put("emdText", nullable("string"));
    tenderProps.put("tenderFeeText", nullable("string"));
    tenderProps.put("deadlineText", nullable("string"));
    Map<String, Object> tender = new LinkedHashMap<>();
    tender.put("type", "object");
    tender.put("properties", tenderProps);
    tender.put("required", new ArrayList<>(tenderProps.keySet()));
    tender.put("additionalProperties", false);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", Map.of(
        "tender", tender,
        "requirements", Map.of("type", "array", "items", requirement)));
    schema.put("required", List.of("tender", "requirements"));
    schema.put("additionalProperties", false);
    return schema;
  }

  private static Map<String, Object> nullable(String type) {
    return Map.of("type", List.of(type, "null"));
  }

  private Extraction.Result parseAndValidate(String outputText) {
    MuseExtractionJson parsed;
    try {
      parsed = objectMapper.readValue(outputText, MuseExtractionJson.class);
    } catch (IOException e) {
      throw new ValidationFailure(List.of("response is not valid JSON: " + oneLine(e)));
    }
    if (parsed.requirements() == null) {
      throw new ValidationFailure(List.of("missing 'requirements' array"));
    }
    List<String> errors = new ArrayList<>();
    List<Extraction.RequirementItem> items = new ArrayList<>();
    for (int i = 0; i < parsed.requirements().size(); i++) {
      MuseRequirementJson raw = parsed.requirements().get(i);
      String where = "requirement[" + i + "]"
          + (raw.clauseRef() == null ? "" : " clauseRef " + raw.clauseRef());
      if (raw.clauseRef() == null || raw.clauseRef().isBlank()) {
        errors.add(where + ": missing clauseRef");
        continue;
      }
      if (raw.confidence() == null || raw.confidence() < 0.0 || raw.confidence() > 1.0) {
        errors.add(where + ": confidence " + raw.confidence() + " out of [0.0, 1.0]");
        continue;
      }
      int failures = errors.size();
      ConditionNode tree = toTree(raw.conditionTree(), where, errors);
      if (errors.size() != failures) {
        continue;
      }
      if (tree != null && !validTree(tree)) {
        errors.add(where + ": malformed conditionTree");
        continue;
      }
      items.add(new Extraction.RequirementItem(raw.clauseRef().trim(),
          raw.category() == null ? "OTHER" : raw.category().trim(), raw.mandatory(),
          raw.mandatoryAmbiguous(), raw.metric(), raw.operator(), raw.amountText(),
          raw.countValue(), raw.lookbackYears(), raw.domainTags(), raw.certificateName(),
          raw.certificateLevel(), tree, raw.sourceRegionId(), raw.originalQuote(),
          raw.confidence(), raw.amendmentAction(), raw.targetClauseRef()));
    }
    if (!errors.isEmpty()) {
      throw new ValidationFailure(errors);
    }
    Extraction.TenderBlock tender = new Extraction.TenderBlock(
        parsed.tender() == null ? null : parsed.tender().reference(),
        parsed.tender() == null ? null : parsed.tender().estimatedValueText(),
        parsed.tender() == null ? null : parsed.tender().emdText(),
        parsed.tender() == null ? null : parsed.tender().tenderFeeText(),
        parsed.tender() == null ? null : parsed.tender().deadlineText());
    return new Extraction.Result(tender, items);
  }

  /**
   * Reads the condition tree in its strict-schema string form, tolerating the
   * legacy object form (non-strict callers, golden fixtures).
   */
  private ConditionNode toTree(JsonNode node, String where, List<String> errors) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      try {
        return objectMapper.readValue(node.asText(), ConditionNode.class);
      } catch (IOException e) {
        errors.add(where + ": conditionTree string is not valid JSON: " + oneLine(e));
        return null;
      }
    }
    if (node.isObject()) {
      try {
        return objectMapper.treeToValue(node, ConditionNode.class);
      } catch (IOException e) {
        errors.add(where + ": malformed conditionTree: " + oneLine(e));
        return null;
      }
    }
    errors.add(where + ": conditionTree must be a JSON-encoded string or object");
    return null;
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
    return node.metric() != null && !node.metric().isBlank()
        && node.operator() != null && !node.operator().isBlank();
  }

  private String oneLine(IOException e) {
    String message = e.getMessage();
    if (message == null) {
      return "unreadable";
    }
    String first = message.lines().findFirst().orElse("unreadable");
    return first.length() <= 200 ? first : first.substring(0, 200);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MuseTenderJson(String reference, String estimatedValueText, String emdText,
      String tenderFeeText, String deadlineText) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MuseRequirementJson(
      String clauseRef, String category, Boolean mandatory, Boolean mandatoryAmbiguous,
      String metric, String operator, String amountText, Integer countValue,
      Integer lookbackYears, List<String> domainTags, String certificateName,
      Integer certificateLevel, JsonNode conditionTree, Long sourceRegionId,
      String originalQuote, Double confidence, String amendmentAction,
      String targetClauseRef) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record MuseExtractionJson(MuseTenderJson tender,
      List<MuseRequirementJson> requirements) {}

  private static final class ValidationFailure extends RuntimeException {
    private final List<String> errors;

    ValidationFailure(List<String> errors) {
      super(String.join("; ", errors));
      this.errors = errors;
    }

    List<String> errors() {
      return errors;
    }
  }
}
