package com.tenderops.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Dashboard aggregates: empty-company zeros, then real figures after a demo run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class DashboardApiTest {

  @Autowired
  private TestRestTemplate rest;

  private AuthResponse register(String prefix) {
    Map<String, String> body = Map.of("email", prefix + UUID.randomUUID() + "@x.local",
        "password", "password-123", "companyName", prefix + " Co");
    ResponseEntity<AuthResponse> response =
        rest.postForEntity("/api/v1/auth/register", body, AuthResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private HttpHeaders auth(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> stats(String token) {
    ResponseEntity<Map> response = rest.exchange("/api/v1/dashboard/stats", HttpMethod.GET,
        new HttpEntity<>(auth(token)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  private void awaitJob(String token, long jobId) {
    long deadline = System.currentTimeMillis() + 180_000;
    while (true) {
      ResponseEntity<Map> response = rest.exchange("/api/v1/jobs/" + jobId, HttpMethod.GET,
          new HttpEntity<>(auth(token)), Map.class);
      String status = (String) response.getBody().get("status");
      if ("COMPLETED".equals(status)) {
        return;
      }
      assertThat(status).isNotEqualTo("FAILED");
      if (System.currentTimeMillis() > deadline) {
        throw new IllegalStateException("Timed out awaiting job " + jobId);
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyCompanyHasZeros() {
    AuthResponse owner = register("dashempty-");
    Map<String, Object> body = stats(owner.accessToken());
    assertThat(body.get("totalTenders")).isEqualTo(0);
    assertThat(body.get("processingTenders")).isEqualTo(0);
    assertThat(body.get("completedTenders")).isEqualTo(0);
    assertThat(body.get("pendingReviews")).isEqualTo(0);
    assertThat(body.get("criticalIssues")).isEqualTo(0);
    assertThat((List<Object>) body.get("upcomingDeadlines")).isEmpty();
    assertThat(body.get("timeSaved")).isNull();
    Map<String, Object> breakdown = (Map<String, Object>) body.get("decisionBreakdown");
    assertThat(breakdown.values()).containsOnly(0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void figuresAfterDemoRun() {
    AuthResponse owner = register("dashrun-");
    ResponseEntity<Map> loaded = rest.exchange("/api/v1/demo/load-rfp", HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    awaitJob(owner.accessToken(), ((Number) loaded.getBody().get("jobId")).longValue());

    Map<String, Object> body = stats(owner.accessToken());
    assertThat(body.get("totalTenders")).isEqualTo(1);
    assertThat(body.get("processingTenders")).isEqualTo(0);
    assertThat(body.get("completedTenders")).isEqualTo(1);
    assertThat(body.get("pendingReviews")).isEqualTo(6);
    assertThat(body.get("criticalIssues")).isEqualTo(0);
    Map<String, Object> breakdown = (Map<String, Object>) body.get("decisionBreakdown");
    assertThat(breakdown.get("INSUFFICIENT_DATA")).isEqualTo(1);
    List<Object> upcoming = (List<Object>) body.get("upcomingDeadlines");
    assertThat(upcoming).hasSize(1);
    assertThat(((Map<String, Object>) upcoming.get(0)).get("reference"))
        .isEqualTo("SCCC/2026/RFP/001");
    Map<String, Object> performance = (Map<String, Object>) body.get("performance");
    assertThat(performance.get("avgMsPerPage")).isNotNull();
    assertThat(performance.get("lastRunMs")).isNotNull();
    Map<String, Object> timeSaved = (Map<String, Object>) body.get("timeSaved");
    assertThat(timeSaved).isNotNull();
    assertThat(timeSaved.get("aiMode")).isEqualTo("FALLBACK");
    assertThat(timeSaved.get("representative")).isEqualTo(false);
    assertThat(timeSaved.get("manualMinutes")).isEqualTo("24.00");
  }

  @Test
  void requiresAuth() {
    ResponseEntity<Map> response = rest.exchange("/api/v1/dashboard/stats", HttpMethod.GET,
        new HttpEntity<>(auth(null)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
