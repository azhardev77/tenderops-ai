package com.tenderops.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.ai.MetaApiTransport;
import com.tenderops.ai.TransportFailedException;
import com.tenderops.ai.TransportFatalException;
import com.tenderops.ai.TransportFormatException;
import com.tenderops.config.TenderopsProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Live SAM 3.1 detector via the Meta Responses API (one request per candidate page × noun
 * phrase). Retries 429/5xx/timeouts with backoff honoring Retry-After, then throws
 * {@link SamUnavailableException} so the caller falls back. Results cached by
 * (image SHA-256, page, prompt). Response grammar live-verified (see SamOutputParser).
 */
@Component
public class Sam31Detector implements VisualRegionDetector {

  public static final List<Prompt> PROMPTS = List.of(
      new Prompt("circular official stamp", VisualRegion.OFFICIAL_STAMP),
      new Prompt("handwritten signature", VisualRegion.HANDWRITTEN_SIGNATURE),
      new Prompt("table", VisualRegion.TABLE_REGION));

  public record Prompt(String phrase, String regionType) {}

  private final TenderopsProperties properties;
  private final ObjectMapper objectMapper;
  private final AiCallLogger callLogger;
  private final MetaApiTransport transport;
  private final Map<String, List<Detection>> cache = new ConcurrentHashMap<>();

  @Autowired
  public Sam31Detector(TenderopsProperties properties, ObjectMapper objectMapper,
      AiCallLogger callLogger, MetaApiTransport transport) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.callLogger = callLogger;
    this.transport = transport;
  }

  /** Test-only constructor (transport sharing the test properties). */
  Sam31Detector(TenderopsProperties properties, ObjectMapper objectMapper,
      AiCallLogger callLogger) {
    this(properties, objectMapper, callLogger, new MetaApiTransport(properties));
  }

  @Override
  public List<Detection> detect(PDDocument doc, int pageIndex0, byte[] renderedPng,
      int imageWidth, int imageHeight, String pageSha256) throws IOException {
    String key = properties.metaApi().key();
    if (key == null || key.isBlank()) {
      throw new SamUnavailableException("MODEL_API_KEY is empty; SAM unavailable");
    }
    List<Detection> out = new ArrayList<>();
    for (Prompt prompt : PROMPTS) {
      String cacheKey = pageSha256 + "|" + pageIndex0 + "|" + prompt.phrase();
      List<Detection> cached = cache.get(cacheKey);
      if (cached != null) {
        out.addAll(cached);
        continue;
      }
      List<Detection> found =
          detectPrompt(prompt, renderedPng, imageWidth, imageHeight, key);
      cache.put(cacheKey, found);
      out.addAll(found);
    }
    return out;
  }

  private List<Detection> detectPrompt(Prompt prompt, byte[] renderedPng, int imageWidth,
      int imageHeight, String key) {
    String purpose = "sam-detect:" + prompt.phrase();
    String url = properties.metaApi().baseUrl() + properties.metaApi().responsesPath();
    String bodyJson = requestJson(prompt.phrase(), renderedPng);
    MetaApiTransport.AttemptListener listener = outcome -> callLogger.log(null, "SAM",
        properties.metaApi().samModel(), purpose, outcome.status(), outcome.attempt(),
        outcome.latencyMs(), "OK".equals(outcome.status()) ? "LIVE" : "FALLBACK",
        outcome.error());
    String responseBody;
    try {
      responseBody = transport.postJson(url, key, bodyJson,
          properties.metaApi().requestTimeoutMs(), properties.metaApi().maxAttempts(),
          properties.metaApi().backoffInitialMs(), listener);
    } catch (TransportFailedException e) {
      throw new SamUnavailableException(
          "SAM retries exhausted for '" + prompt.phrase() + "': " + e.getMessage(), e);
    } catch (TransportFatalException e) {
      throw new SamException("SAM call rejected: " + e.getMessage());
    }
    String outputText;
    try {
      outputText = MetaApiTransport.extractOutputText(objectMapper, responseBody);
    } catch (TransportFormatException e) {
      callLogger.log(null, "SAM", properties.metaApi().samModel(), purpose, "FORMAT_ERROR",
          1, null, "FALLBACK", e.getMessage());
      throw new SamFormatException(e.getMessage());
    }
    List<SamOutputParser.SamBox> boxes;
    try {
      boxes = SamOutputParser.parse(outputText, imageWidth, imageHeight);
    } catch (SamFormatException e) {
      callLogger.log(null, "SAM", properties.metaApi().samModel(), purpose, "FORMAT_ERROR",
          1, null, "FALLBACK", e.getMessage());
      throw e;
    }
    boolean keepMasks = !"none".equalsIgnoreCase(properties.sam().maskFormat());
    List<Detection> detections = new ArrayList<>();
    for (SamOutputParser.SamBox box : boxes) {
      detections.add(new Detection(prompt.regionType(), box.x0(), box.y0(), box.x1(),
          box.y1(), box.confidence(), VisualRegion.SOURCE_SAM,
          keepMasks ? box.maskRef() : null));
    }
    return detections;
  }

  private String requestJson(String phrase, byte[] renderedPng) {
    String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(renderedPng);
    Map<String, Object> imagePart = new LinkedHashMap<>();
    imagePart.put("type", "input_image");
    imagePart.put("image_url", dataUrl);
    Map<String, Object> textPart = new LinkedHashMap<>();
    textPart.put("type", "input_text");
    textPart.put("text", phrase);
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("type", "message");
    message.put("role", "user");
    message.put("content", List.of(textPart, imagePart));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", properties.metaApi().samModel());
    body.put("input", List.of(message));
    try {
      return objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new SamException("Cannot serialize SAM request", e);
    }
  }
}
