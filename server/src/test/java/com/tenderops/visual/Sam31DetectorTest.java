package com.tenderops.visual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tenderops.visual.VisualRegionDetector.Detection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * TC-VIS-03/04: the SAM adapter speaks the docs-verified Responses shape (via a JDK HttpServer
 * test double), parses boxes + masks, retries 429/5xx/timeouts, then fails over. The API key
 * never appears in logs (TC-AI-08 analog).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=TESTKEY-SAM-DOUBLE-1",
    "tenderops.meta-api.max-attempts=3",
    "tenderops.meta-api.backoff-initial-ms=10",
    "tenderops.meta-api.request-timeout-ms=1000"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class Sam31DetectorTest {

  private static final String OUTPUT_TEXT = "<0f>0"
      + "<|box;x1=10;y1=20;x2=110;y2=220;c=0.95;w=200;h=400|>"
      + "<|mask;x=0;y=0;data=QUJD|>";

  private static HttpServer server;
  private static final Deque<Behavior> script = new ArrayDeque<>();
  private static final List<CapturedRequest> captured = new ArrayList<>();
  private static int hits;

  private record Behavior(int status, String retryAfter, long sleepMs, String outputText) {
    static Behavior success(String outputText) {
      return new Behavior(200, null, 0, outputText);
    }

    static Behavior failure(int status, String retryAfter) {
      return new Behavior(status, retryAfter, 0, null);
    }

    static Behavior slow(long sleepMs, String outputText) {
      return new Behavior(200, null, sleepMs, outputText);
    }
  }

  private record CapturedRequest(String authHeader, String body) {}

  static {
    try {
      startDouble();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot start SAM test double", e);
    }
  }

  static void startDouble() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/responses", exchange -> {
      String body;
      try (var in = exchange.getRequestBody()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      Behavior behavior;
      synchronized (script) {
        behavior = script.isEmpty() ? Behavior.failure(500, "0") : script.removeFirst();
        hits++;
        captured.add(new CapturedRequest(
            exchange.getRequestHeaders().getFirst("Authorization"), body));
      }
      if (behavior.sleepMs() > 0) {
        try {
          Thread.sleep(behavior.sleepMs());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] payload;
      if (behavior.status() == 200) {
        String json = "{\"output\":[{\"content\":[{\"text\":\"" + behavior.outputText()
            + "\"}]}]}";
        payload = json.getBytes(StandardCharsets.UTF_8);
      } else {
        payload = "{\"error\":\"double failure\"}".getBytes(StandardCharsets.UTF_8);
      }
      if (behavior.retryAfter() != null) {
        exchange.getResponseHeaders().add("Retry-After", behavior.retryAfter());
      }
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(behavior.status(), payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
  }

  @AfterAll
  static void stopDouble() {
    if (server != null) {
      server.stop(0);
    }
  }

  @DynamicPropertySource
  static void doubleUrl(DynamicPropertyRegistry registry) {
    registry.add("tenderops.meta-api.base-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Autowired
  private Sam31Detector detector;

  @Autowired
  private AiCallLogRepository callLogs;

  @Autowired
  private ObjectMapper objectMapper;

  @BeforeEach
  void resetDouble() {
    synchronized (script) {
      script.clear();
      captured.clear();
      hits = 0;
    }
  }

  private static void enqueue(Behavior behavior) {
    synchronized (script) {
      script.addLast(behavior);
    }
  }

  private static int hitCount() {
    synchronized (script) {
      return hits;
    }
  }

  @Test
  void tcVis03ParsesDoubleResponsesAndUsesDocsShape() throws Exception {
    enqueue(Behavior.success(OUTPUT_TEXT));
    enqueue(Behavior.success(OUTPUT_TEXT));
    enqueue(Behavior.success(OUTPUT_TEXT));
    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};

    List<Detection> detections = detector.detect(null, 0, png, 200, 400, "tc-vis-03");

    assertThat(detections).hasSize(3);
    assertThat(detections.stream().map(Detection::regionType).toList()).containsExactly(
        VisualRegion.OFFICIAL_STAMP, VisualRegion.HANDWRITTEN_SIGNATURE,
        VisualRegion.TABLE_REGION);
    Detection first = detections.get(0);
    assertThat(first.x0()).isEqualTo(0.05);
    assertThat(first.y0()).isEqualTo(0.05);
    assertThat(first.x1()).isEqualTo(0.55);
    assertThat(first.y1()).isEqualTo(0.55);
    assertThat(first.confidence()).isEqualTo(0.95);
    assertThat(first.source()).isEqualTo(VisualRegion.SOURCE_SAM);
    assertThat(first.maskRef()).isEqualTo("<|mask;x=0;y=0;data=QUJD|>");

    assertThat(hitCount()).isEqualTo(3);
    CapturedRequest request;
    synchronized (script) {
      request = captured.get(0);
    }
    assertThat(request.authHeader()).isEqualTo("Bearer TESTKEY-SAM-DOUBLE-1");
    JsonNode root = objectMapper.readTree(request.body());
    assertThat(root.get("model").asText()).isEqualTo("sam-3.1");
    JsonNode content = root.get("input").get(0).get("content");
    assertThat(content.get(0).get("type").asText()).isEqualTo("input_text");
    assertThat(content.get(0).get("text").asText()).isEqualTo("circular official stamp");
    assertThat(content.get(1).get("type").asText()).isEqualTo("input_image");
    assertThat(content.get(1).get("image_url").asText()).startsWith("data:image/png;base64,");

    List<AiCallLog> logs = callLogs.findByComponentOrderById("SAM");
    assertThat(logs).hasSize(3);
    assertThat(logs).allSatisfy(
        log -> assertThat(log.getStatus()).isEqualTo("OK"));
    assertThat(logs).allSatisfy(
        log -> assertThat(log.getAiMode()).isEqualTo("LIVE"));
    assertThat(logs).allSatisfy(log -> {
      assertThat(String.valueOf(log.getPurpose())).doesNotContain("TESTKEY");
      assertThat(String.valueOf(log.getErrorText())).doesNotContain("TESTKEY");
      assertThat(String.valueOf(log.getStatus())).doesNotContain("TESTKEY");
    });

    List<Detection> cached = detector.detect(null, 0, png, 200, 400, "tc-vis-03");
    assertThat(cached).hasSize(3);
    assertThat(hitCount()).isEqualTo(3);
  }

  @Test
  void tcVis04RetriesThenFailsOverOn429And500() {
    enqueue(Behavior.failure(429, "0"));
    enqueue(Behavior.failure(500, "0"));
    enqueue(Behavior.failure(500, "0"));

    assertThatThrownBy(() -> detector.detect(null, 0, new byte[4], 10, 10, "tc-vis-04a"))
        .isInstanceOf(SamUnavailableException.class);
    assertThat(hitCount()).isEqualTo(3);

    List<AiCallLog> logs = callLogs.findByComponentOrderById("SAM");
    assertThat(logs).hasSize(3);
    assertThat(logs.stream().map(AiCallLog::getStatus).toList())
        .containsExactly("HTTP_429", "HTTP_500", "HTTP_500");
    assertThat(logs.stream().map(AiCallLog::getAttempt).toList())
        .containsExactly(1, 2, 3);
    assertThat(logs).allSatisfy(
        log -> assertThat(log.getAiMode()).isEqualTo("FALLBACK"));
  }

  @Test
  void tcVis04TimeoutRetriesThenFailsOver() {
    enqueue(Behavior.slow(3000, OUTPUT_TEXT));
    enqueue(Behavior.slow(3000, OUTPUT_TEXT));
    enqueue(Behavior.slow(3000, OUTPUT_TEXT));

    assertThatThrownBy(() -> detector.detect(null, 0, new byte[4], 10, 10, "tc-vis-04b"))
        .isInstanceOf(SamUnavailableException.class);
    assertThat(hitCount()).isEqualTo(3);

    List<AiCallLog> logs = callLogs.findByComponentOrderById("SAM");
    assertThat(logs).hasSize(3);
    assertThat(logs.stream().map(AiCallLog::getStatus).toList())
        .containsExactly("TIMEOUT", "TIMEOUT", "TIMEOUT");
  }

  @Test
  void missingKeyFailsOverImmediately() {
    Sam31Detector noKey = new Sam31Detector(
        new com.tenderops.config.TenderopsProperties(null, null, null, null,
            new com.tenderops.config.TenderopsProperties.MetaApi("http://127.0.0.1:1",
                "/responses", "", "muse-spark-1.3", "sam-3.1", 100, 100, 1, 10),
            null, new com.tenderops.config.TenderopsProperties.Sam("none"), null, "1.0",
            "data/storage", null, null),
        objectMapper,
        new AiCallLogger(callLogs));
    assertThatThrownBy(() -> noKey.detect(null, 0, new byte[4], 10, 10, "no-key"))
        .isInstanceOf(SamUnavailableException.class)
        .hasMessageContaining("MODEL_API_KEY is empty");
    assertThat(hitCount()).isZero();
  }
}
