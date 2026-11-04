package com.tenderops.company;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.audit.AuditLog;
import com.tenderops.audit.AuditLogRepository;
import com.tenderops.security.User;
import com.tenderops.security.UserRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Company database API: profile/financials/projects/certificates CRUD, document upload,
 * money-as-strings, dataVersion bumps, audit rows, VIEWER 403 on writes, cross-company 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class CompanyApiTest {

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditLogRepository audits;

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
  private Map<String, Object> get(String url, String token) {
    ResponseEntity<Map> response =
        rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map> exchange(String url, HttpMethod method, String token,
      Object body) {
    HttpHeaders headers = auth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
  }

  @Test
  void profileReadUpdateBumpsDataVersion() {
    AuthResponse owner = register("coprof-");
    Map<String, Object> before = get("/api/v1/company", owner.accessToken());
    assertThat(before.get("name")).isEqualTo("coprof- Co");
    assertThat(((Number) before.get("dataVersion")).longValue()).isEqualTo(0L);

    Map<String, Object> update = Map.of("gstNumber", "27AAPCA1234F1Z5", "gstRegistered",
        true, "msmeRegistered", true);
    ResponseEntity<Map> updated =
        exchange("/api/v1/company", HttpMethod.PUT, owner.accessToken(), update);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody().get("gstNumber")).isEqualTo("27AAPCA1234F1Z5");
    assertThat(updated.getBody().get("dataVersion")).isEqualTo(1);

    Map<String, Object> reread = get("/api/v1/company", owner.accessToken());
    assertThat(reread.get("dataVersion")).isEqualTo(1);
  }

  @Test
  void financialsCrudWithMoneyStrings() {
    AuthResponse owner = register("cofin-");
    Map<String, Object> body = new HashMap<>();
    body.put("fyLabel", "FY2025-26");
    body.put("fyEndDate", "2026-03-31");
    body.put("turnover", "1120000000");
    body.put("netWorth", "350000000.0000");
    body.put("audited", true);
    ResponseEntity<Map> created =
        exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(), body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().get("turnover")).isEqualTo("1120000000.0000");
    assertThat(created.getBody().get("currency")).isEqualTo("INR");
    Number id = (Number) created.getBody().get("id");

    ResponseEntity<Map> duplicate =
        exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(), body);
    assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    Map<String, Object> patch = Map.of("turnover", "1130000000.5");
    ResponseEntity<Map> updated = exchange("/api/v1/company/financials/" + id.longValue(),
        HttpMethod.PUT, owner.accessToken(), patch);
    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody().get("turnover")).isEqualTo("1130000000.5000");

    ResponseEntity<Map> deleted = exchange("/api/v1/company/financials/" + id.longValue(),
        HttpMethod.DELETE, owner.accessToken(), null);
    assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<List> listed = rest.exchange("/api/v1/company/financials", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), List.class);
    assertThat(listed.getBody()).isEmpty();
  }

  @Test
  void invalidMoneyRejected() {
    AuthResponse owner = register("comon-");
    Map<String, Object> negative = new HashMap<>();
    negative.put("fyLabel", "FY2025-26");
    negative.put("fyEndDate", "2026-03-31");
    negative.put("turnover", "-5");
    assertThat(exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(),
        negative).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    Map<String, Object> garbage = new HashMap<>(negative);
    garbage.put("turnover", "twelve crore");
    assertThat(exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(),
        garbage).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    Map<String, Object> missing = Map.of("turnover", "100");
    assertThat(exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(),
        missing).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void projectsAndCertificatesCrud() {
    AuthResponse owner = register("coproj-");
    Map<String, Object> project = new HashMap<>();
    project.put("name", "Harbor Link");
    project.put("contractValue", "290000000");
    project.put("completionDate", "2023-03-31");
    project.put("domainCategory", "highway");
    ResponseEntity<Map> created =
        exchange("/api/v1/company/projects", HttpMethod.POST, owner.accessToken(), project);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().get("contractValue")).isEqualTo("290000000.0000");
    assertThat(created.getBody().get("domainCategory")).isEqualTo("HIGHWAY");
    assertThat(created.getBody().get("status")).isEqualTo("COMPLETED");

    Map<String, Object> cert = new HashMap<>();
    cert.put("name", "Bank Solvency Certificate");
    cert.put("issueDate", "2026-10-01");
    cert.put("expiryDate", "2027-03-31");
    cert.put("amount", "180000000");
    ResponseEntity<Map> createdCert = exchange("/api/v1/company/certificates",
        HttpMethod.POST, owner.accessToken(), cert);
    assertThat(createdCert.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(createdCert.getBody().get("amount")).isEqualTo("180000000.0000");

    Map<String, Object> profile = get("/api/v1/company", owner.accessToken());
    assertThat(((Number) profile.get("dataVersion")).longValue()).isEqualTo(2L);
  }

  @Test
  void documentUploadAndLink() {
    AuthResponse owner = register("codoc-");
    byte[] pdf = "%PDF-1.4 demo supporting doc".getBytes(StandardCharsets.UTF_8);
    ByteArrayResource resource = new ByteArrayResource(pdf) {
      @Override
      public String getFilename() {
        return "solvency.pdf";
      }
    };
    MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
    multipart.add("file", resource);
    HttpHeaders headers = auth(owner.accessToken());
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<Map> uploaded = rest.exchange("/api/v1/company/documents",
        HttpMethod.POST, new HttpEntity<>(multipart, headers), Map.class);
    assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(uploaded.getBody().get("sha256")).isNotNull();
    Number docId = (Number) uploaded.getBody().get("id");

    Map<String, Object> cert = new HashMap<>();
    cert.put("name", "ISO 9001");
    cert.put("expiryDate", "2027-08-31");
    cert.put("documentId", docId.longValue());
    ResponseEntity<Map> created = exchange("/api/v1/company/certificates", HttpMethod.POST,
        owner.accessToken(), cert);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(((Number) created.getBody().get("documentId")).longValue())
        .isEqualTo(docId.longValue());

    Map<String, Object> badLink = new HashMap<>(cert);
    badLink.put("name", "ISO 27001");
    badLink.put("documentId", 999999999L);
    assertThat(exchange("/api/v1/company/certificates", HttpMethod.POST, owner.accessToken(),
        badLink).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void nonDocumentBytesRejected() {
    AuthResponse owner = register("cobad-");
    byte[] exe = "MZ not a document".getBytes(StandardCharsets.UTF_8);
    ByteArrayResource resource = new ByteArrayResource(exe) {
      @Override
      public String getFilename() {
        return "evil.exe";
      }
    };
    MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
    multipart.add("file", resource);
    HttpHeaders headers = auth(owner.accessToken());
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<Map> uploaded = rest.exchange("/api/v1/company/documents",
        HttpMethod.POST, new HttpEntity<>(multipart, headers), Map.class);
    assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void viewerCannotWriteButCanRead() {
    AuthResponse owner = register("coview-");
    long companyId = owner.user().companyId();
    String email = "viewer-" + UUID.randomUUID() + "@x.local";
    users.save(new User(companyId, email, passwordEncoder.encode("viewer-123"), "VIEWER"));
    ResponseEntity<AuthResponse> login = rest.postForEntity("/api/v1/auth/login",
        Map.of("email", email, "password", "viewer-123"), AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String viewerToken = login.getBody().accessToken();

    assertThat(get("/api/v1/company", viewerToken).get("id")).isNotNull();
    Map<String, Object> update = Map.of("msmeRegistered", true);
    assertThat(exchange("/api/v1/company", HttpMethod.PUT, viewerToken, update)
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    Map<String, Object> fin = Map.of("fyLabel", "FY2025-26", "fyEndDate", "2026-03-31");
    assertThat(exchange("/api/v1/company/financials", HttpMethod.POST, viewerToken, fin)
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void crossCompanySubResourceIsNotFound() {
    AuthResponse owner = register("coown-");
    AuthResponse stranger = register("costr-");
    Map<String, Object> body = new HashMap<>();
    body.put("fyLabel", "FY2025-26");
    body.put("fyEndDate", "2026-03-31");
    body.put("turnover", "100");
    ResponseEntity<Map> created =
        exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(), body);
    Number id = (Number) created.getBody().get("id");

    assertThat(exchange("/api/v1/company/financials/" + id.longValue(), HttpMethod.PUT,
        stranger.accessToken(), Map.of("turnover", "200")).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(exchange("/api/v1/company/financials/" + id.longValue(), HttpMethod.DELETE,
        stranger.accessToken(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void writesAreAuditLoggedWithBeforeAfter() {
    AuthResponse owner = register("coaud-");
    long companyId = owner.user().companyId();
    Map<String, Object> body = new HashMap<>();
    body.put("fyLabel", "FY2025-26");
    body.put("fyEndDate", "2026-03-31");
    body.put("turnover", "100");
    ResponseEntity<Map> created =
        exchange("/api/v1/company/financials", HttpMethod.POST, owner.accessToken(), body);
    Number id = (Number) created.getBody().get("id");
    exchange("/api/v1/company/financials/" + id.longValue(), HttpMethod.PUT,
        owner.accessToken(), Map.of("turnover", "200"));

    List<AuditLog> rows = audits.findByCompanyIdAndEntityTypeOrderByIdDesc(companyId,
        "financial_record", org.springframework.data.domain.Pageable.ofSize(10));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getAction()).isEqualTo("UPDATE");
    assertThat(rows.get(0).getBeforeJson()).contains("100.0000");
    assertThat(rows.get(0).getAfterJson()).contains("200.0000");
    assertThat(rows.get(1).getAction()).isEqualTo("CREATE");
  }
}
