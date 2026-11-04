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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Audit log reads: company writes appear with before/after, filters and paging work. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class AuditApiTest {

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

  @SuppressWarnings("unchecked")
  private Map<String, Object> list(String token, String query) {
    ResponseEntity<Map> response = rest.exchange("/api/v1/audit-logs" + query,
        HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

  @Test
  @SuppressWarnings("unchecked")
  void companyWritesAppearWithBeforeAfter() {
    AuthResponse owner = register("audw-");
    HttpHeaders headers = auth(owner.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> updated = rest.exchange("/api/v1/company", HttpMethod.PUT,
        new HttpEntity<>(Map.of("msmeRegistered", true), headers), Map.class);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> body = list(owner.accessToken(), "");
    // USER_REGISTERED plus the profile UPDATE, newest first.
    assertThat(body.get("total")).isEqualTo(2);
    List<Object> items = (List<Object>) body.get("items");
    assertThat(items).hasSize(2);
    Map<String, Object> entry = (Map<String, Object>) items.get(0);
    assertThat(entry.get("action")).isEqualTo("UPDATE");
    assertThat(entry.get("entityType")).isEqualTo("company");
    assertThat((String) entry.get("before")).contains("msmeRegistered");
    assertThat((String) entry.get("after")).contains("msmeRegistered");

    Map<String, Object> filtered = list(owner.accessToken(), "?entityType=company");
    assertThat(filtered.get("total")).isEqualTo(1);
    Map<String, Object> empty = list(owner.accessToken(), "?entityType=certificate");
    assertThat(empty.get("total")).isEqualTo(0);
    assertThat((List<Object>) empty.get("items")).isEmpty();

    Map<String, Object> paged = list(owner.accessToken(), "?page=0&size=1");
    assertThat(paged.get("page")).isEqualTo(0);
    assertThat(paged.get("size")).isEqualTo(1);
    assertThat(((List<Object>) paged.get("items"))).hasSize(1);
    Map<String, Object> beyond = list(owner.accessToken(), "?page=5&size=10");
    assertThat(((List<Object>) beyond.get("items"))).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void strangerSeesOnlyOwnCompany() {
    AuthResponse owner = register("audown-");
    AuthResponse stranger = register("audstr-");
    HttpHeaders headers = auth(owner.accessToken());
    headers.setContentType(MediaType.APPLICATION_JSON);
    rest.exchange("/api/v1/company", HttpMethod.PUT,
        new HttpEntity<>(Map.of("msmeRegistered", true), headers), Map.class);

    assertThat(list(owner.accessToken(), "").get("total")).isEqualTo(2);
    assertThat(list(stranger.accessToken(), "").get("total")).isEqualTo(1);
  }

  @Test
  void requiresAuth() {
    ResponseEntity<Map> response = rest.exchange("/api/v1/audit-logs", HttpMethod.GET,
        new HttpEntity<>(auth(null)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
