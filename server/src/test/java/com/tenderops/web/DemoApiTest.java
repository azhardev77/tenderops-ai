package com.tenderops.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.security.User;
import com.tenderops.security.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * One-click demo endpoints: bundled RFP/corrigendum loads run the real pipeline, sample
 * listing, duplicate handling, cross-company guards, ADMIN-only live check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class DemoApiTest {

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordEncoder passwordEncoder;

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
    headers.setBearerAuth(token);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private void awaitJob(String token, long jobId) {
    long deadline = System.currentTimeMillis() + 180_000;
    while (true) {
      ResponseEntity<Map> response = rest.exchange("/api/v1/jobs/" + jobId, HttpMethod.GET,
          new HttpEntity<>(auth(token)), Map.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
  void loadRfpRunsPipelineForEmptyCompany() {
    AuthResponse owner = register("demorfp-");
    ResponseEntity<Map> loaded = rest.exchange("/api/v1/demo/load-rfp", HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(loaded.getBody().get("duplicate")).isEqualTo(false);
    long tenderId = ((Number) loaded.getBody().get("tenderId")).longValue();
    awaitJob(owner.accessToken(), ((Number) loaded.getBody().get("jobId")).longValue());

    ResponseEntity<Map> detail = rest.exchange("/api/v1/tenders/" + tenderId,
        HttpMethod.GET, new HttpEntity<>(auth(owner.accessToken())), Map.class);
    Map<String, Object> latestRun = (Map<String, Object>) detail.getBody().get("latestRun");
    assertThat(latestRun.get("decision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(latestRun.get("readinessScore")).isEqualTo("0.00");

    ResponseEntity<Map> again = rest.exchange("/api/v1/demo/load-rfp", HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody().get("duplicate")).isEqualTo(true);
    assertThat(again.getBody().get("versionId")).isEqualTo(loaded.getBody().get("versionId"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void loadCorrigendumMergesAndDiffs() {
    AuthResponse owner = register("democor-");
    ResponseEntity<Map> loaded = rest.exchange("/api/v1/demo/load-rfp", HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    long tenderId = ((Number) loaded.getBody().get("tenderId")).longValue();
    awaitJob(owner.accessToken(), ((Number) loaded.getBody().get("jobId")).longValue());

    ResponseEntity<Map> amended = rest.exchange(
        "/api/v1/demo/load-corrigendum?tenderId=" + tenderId, HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(amended.getBody().get("duplicate")).isEqualTo(false);
    long amendmentId = ((Number) amended.getBody().get("amendmentId")).longValue();
    awaitJob(owner.accessToken(), ((Number) amended.getBody().get("jobId")).longValue());

    ResponseEntity<Map> diff = rest.exchange("/api/v1/amendments/" + amendmentId + "/diff",
        HttpMethod.GET, new HttpEntity<>(auth(owner.accessToken())), Map.class);
    assertThat(diff.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Object> modified = (List<Object>) diff.getBody().get("modified");
    assertThat(modified).hasSize(2);
    assertThat(diff.getBody().get("prevDecision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(diff.getBody().get("newDecision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(diff.getBody().get("scoreDelta")).isEqualTo("0.00");
  }

  @Test
  @SuppressWarnings("unchecked")
  void amendmentListShowsLoads() {
    AuthResponse owner = register("demolist-");
    ResponseEntity<Map> loaded = rest.exchange("/api/v1/demo/load-rfp", HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    long tenderId = ((Number) loaded.getBody().get("tenderId")).longValue();
    awaitJob(owner.accessToken(), ((Number) loaded.getBody().get("jobId")).longValue());

    ResponseEntity<List> before = rest.exchange(
        "/api/v1/tenders/" + tenderId + "/amendments", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), List.class);
    assertThat(before.getBody()).isEmpty();

    ResponseEntity<Map> amended = rest.exchange(
        "/api/v1/demo/load-corrigendum?tenderId=" + tenderId, HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    long amendmentId = ((Number) amended.getBody().get("amendmentId")).longValue();
    awaitJob(owner.accessToken(), ((Number) amended.getBody().get("jobId")).longValue());

    ResponseEntity<List> after = rest.exchange(
        "/api/v1/tenders/" + tenderId + "/amendments", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), List.class);
    assertThat(after.getBody()).hasSize(1);
    Map<String, Object> entry = (Map<String, Object>) after.getBody().get(0);
    assertThat(((Number) entry.get("id")).longValue()).isEqualTo(amendmentId);
    assertThat(entry.get("prevDecision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(entry.get("newDecision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(entry.get("scoreDelta")).isEqualTo("0.00");
  }

  @Test
  void corrigendumOfForeignTenderIsNotFound() {
    AuthResponse owner = register("demoown-");
    AuthResponse stranger = register("demostr-");
    ResponseEntity<Map> loaded = rest.exchange("/api/v1/demo/load-rfp", HttpMethod.POST,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    long tenderId = ((Number) loaded.getBody().get("tenderId")).longValue();
    awaitJob(owner.accessToken(), ((Number) loaded.getBody().get("jobId")).longValue());

    ResponseEntity<Map> amended = rest.exchange(
        "/api/v1/demo/load-corrigendum?tenderId=" + tenderId, HttpMethod.POST,
        new HttpEntity<>(auth(stranger.accessToken())), Map.class);
    assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @SuppressWarnings("unchecked")
  void samplePdfsListed() {
    AuthResponse owner = register("demosmp-");
    ResponseEntity<Map> response = rest.exchange("/api/v1/demo/sample-pdfs", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<String> files = (List<String>) response.getBody().get("files");
    assertThat(files).containsExactly("Smart_City_RFP_v1.pdf", "Corrigendum_1.pdf",
        "Variant_B_Tender.pdf");
  }

  @Test
  @SuppressWarnings("unchecked")
  void liveCheckRunsGoldenFlowForAdminOnly() {
    AuthResponse owner = register("demolive-");
    ResponseEntity<Map> forbidden = rest.exchange("/api/v1/demo/live-check",
        HttpMethod.POST, new HttpEntity<>(auth(owner.accessToken())), Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    long companyId = owner.user().companyId();
    String email = "liveadmin-" + UUID.randomUUID() + "@x.local";
    users.save(new User(companyId, email, passwordEncoder.encode("admin-123"), "ADMIN"));
    ResponseEntity<AuthResponse> login = rest.postForEntity("/api/v1/auth/login",
        Map.of("email", email, "password", "admin-123"), AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> checked = rest.exchange("/api/v1/demo/live-check", HttpMethod.POST,
        new HttpEntity<>(auth(login.getBody().accessToken())), Map.class);
    assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(checked.getBody().get("status")).isEqualTo("COMPLETED");
    assertThat(checked.getBody().get("museMode")).isEqualTo("FALLBACK");
    assertThat(checked.getBody().get("samMode")).isEqualTo("FALLBACK");
    assertThat(checked.getBody().get("decision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(checked.getBody().get("readinessScore")).isEqualTo("0.00");
    Map<String, String> verdicts = (Map<String, String>) checked.getBody().get("verdicts");
    assertThat(verdicts).hasSize(6);
    assertThat(verdicts.values()).containsOnly("UNKNOWN");
  }
}
