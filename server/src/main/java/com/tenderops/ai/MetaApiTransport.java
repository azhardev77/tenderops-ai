package com.tenderops.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.config.TenderopsProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared Meta Responses-API transport (SAM + Muse): POST with Bearer auth, retries on
 * 429/5xx/timeouts honoring Retry-After, exponential backoff with jitter, per-attempt
 * listener (callers log to ai_call_logs). 4xx is fatal (logged, no retry).
 */
@Component
public class MetaApiTransport {

  private static final Logger LOG = LoggerFactory.getLogger(MetaApiTransport.class);

  public record AttemptOutcome(int attempt, String status, Long latencyMs, String error) {}

  public interface AttemptListener {
    void onAttempt(AttemptOutcome outcome);
  }

  private final TenderopsProperties properties;
  private final HttpClient httpClient;

  public MetaApiTransport(TenderopsProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.metaApi().connectTimeoutMs()))
        .build();
  }

  /**
   * POSTs a JSON body, returning the 2xx response body. Every attempt (success, retryable
   * failure, fatal failure) is reported to {@code listener}.
   */
  public String postJson(String url, String key, String bodyJson, int requestTimeoutMs,
      int maxAttempts, int backoffInitialMs, AttemptListener listener) {
    int attempts = Math.max(1, maxAttempts);
    RetryableFailure last = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      long start = System.currentTimeMillis();
      try {
        String body = postOnce(url, key, bodyJson, requestTimeoutMs, attempt);
        long latency = System.currentTimeMillis() - start;
        listener.onAttempt(new AttemptOutcome(attempt, "OK", latency, null));
        return body;
      } catch (RetryableFailure e) {
        long latency = System.currentTimeMillis() - start;
        listener.onAttempt(
            new AttemptOutcome(attempt, e.status(), latency, e.getMessage()));
        last = e;
        if (attempt < attempts) {
          sleepBeforeRetry(e.retryAfterMs(), backoffInitialMs, attempt);
        }
      } catch (FatalFailure e) {
        long latency = System.currentTimeMillis() - start;
        listener.onAttempt(
            new AttemptOutcome(attempt, e.status(), latency, e.getMessage()));
        throw new TransportFatalException(e.getMessage());
      }
    }
    throw new TransportFailedException(
        "Meta API retries exhausted: " + (last == null ? "?" : last.getMessage()),
        last == null ? null : last.getCause());
  }

  private String postOnce(String url, String key, String bodyJson, int requestTimeoutMs,
      int attempt) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(requestTimeoutMs))
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
        .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new RetryableFailure("TIMEOUT", 0, "Meta API request timed out", e);
    } catch (IOException e) {
      throw new RetryableFailure("CONNECTION_ERROR", 0, messageOf(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportFailedException("Meta API call interrupted", e);
    }
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return response.body();
    }
    if (status == 429 || status >= 500) {
      throw new RetryableFailure("HTTP_" + status, retryAfterMs(response),
          "Meta API HTTP " + status + " (attempt " + attempt + ")");
    }
    throw new FatalFailure("HTTP_" + status,
        "Meta API HTTP " + status + ": " + abbreviate(response.body()));
  }

  private long retryAfterMs(HttpResponse<String> response) {
    try {
      return response.headers().firstValue("Retry-After").map(value -> {
        long seconds = Long.parseLong(value.trim());
        return Math.min(30_000, Math.max(0, seconds * 1000));
      }).orElse(0L);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private void sleepBeforeRetry(long retryAfterMs, int backoffInitialMs, int attempt) {
    long backoff = (long) backoffInitialMs * (1L << (attempt - 1));
    long jitter = ThreadLocalRandom.current().nextLong(0, 101);
    long waitMs = Math.max(retryAfterMs, backoff + jitter);
    try {
      Thread.sleep(waitMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportFailedException("Meta API backoff interrupted", e);
    }
  }

  /** Tolerantly extracts model output text from a Responses-API JSON payload. */
  public static String extractOutputText(ObjectMapper objectMapper, String body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (IOException e) {
      throw new TransportFormatException("Meta response is not JSON");
    }
    List<String> chunks = new ArrayList<>();
    JsonNode output = root.get("output");
    if (output != null && output.isArray()) {
      for (JsonNode item : output) {
        JsonNode content = item.get("content");
        if (content != null && content.isArray()) {
          for (JsonNode part : content) {
            JsonNode text = part.get("text");
            if (text != null && text.isTextual()) {
              chunks.add(text.asText());
            }
          }
        }
      }
    }
    if (chunks.isEmpty() && root.has("output_text") && root.get("output_text").isTextual()) {
      chunks.add(root.get("output_text").asText());
    }
    if (chunks.isEmpty()) {
      LOG.warn("Meta response carried no output text");
      throw new TransportFormatException(
          "Meta response carries no output text" + incompleteSuffix(root));
    }
    return String.join("\n", chunks);
  }

  private static String incompleteSuffix(JsonNode root) {
    String status = root.path("status").asText("");
    JsonNode reason = root.path("incomplete_details").path("reason");
    if (reason.isTextual()) {
      return " [status=" + status + " reason=" + reason.asText() + "]";
    }
    if (!status.isBlank()) {
      return " [status=" + status + "]";
    }
    return "";
  }

  private String messageOf(IOException e) {
    return e.getMessage() == null ? "connection failed" : e.getMessage();
  }

  private String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 200 ? body : body.substring(0, 200) + "...";
  }

  private static final class RetryableFailure extends RuntimeException {
    private final String status;
    private final long retryAfterMs;

    RetryableFailure(String status, long retryAfterMs, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
      this.retryAfterMs = retryAfterMs;
    }

    RetryableFailure(String status, long retryAfterMs, String message) {
      super(message);
      this.status = status;
      this.retryAfterMs = retryAfterMs;
    }

    String status() {
      return status;
    }

    long retryAfterMs() {
      return retryAfterMs;
    }
  }

  private static final class FatalFailure extends RuntimeException {
    private final String status;

    FatalFailure(String status, String message) {
      super(message);
      this.status = status;
    }

    String status() {
      return status;
    }
  }
}
