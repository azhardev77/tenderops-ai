package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.TestPasswords;
import com.tenderops.audit.AuditService;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.CompanyDocumentRepository;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoDataSeeder;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.StorageService;
import com.tenderops.security.UserRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
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
 * TC-E2E-00/01 (API level): empty-company INSUFFICIENT_DATA, and the full golden
 * journey on the demo company — live event streams, v1 NO_BID 40.00, corrigendum
 * CONDITIONAL_BID 80.00, solvency + Re-Verify BID 100.00, approval, downloads,
 * audit trail. Own database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=",
    "spring.datasource.url=jdbc:h2:mem:tenderopse2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2eFlowTest {

  @LocalServerPort
  int port;

  private String demoToken;

  @Autowired
  private TenderPdfGenerator generator;

  @Autowired
  private UserRepository users;

  @Autowired
  private CompanyRepository companies;

  @Autowired
  private FinancialRecordRepository financials;

  @Autowired
  private ProjectRepository projects;

  @Autowired
  private CertificateRepository certificates;

  @Autowired
  private CompanyDocumentRepository documents;

  @Autowired
  private StorageService storage;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditService auditService;

  @Autowired
  private TenderopsProperties properties;

  @Autowired
  private Environment environment;

  private String base() {
    return "http://localhost:" + port;
  }

  private HttpHeaders auth(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  @BeforeAll
  void seedDemo() throws Exception {
    Path temp = Files.createTempDirectory("e2e-seed");
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, TestPasswords.DEMO);
    seeder.seed(temp).companyId();
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<AuthResponse> login = rest.postForEntity(base() + "/api/v1/auth/login",
        Map.of("email", DemoDataSeeder.DEMO_EMAIL, "password", TestPasswords.DEMO),
        AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    demoToken = login.getBody().accessToken();
  }

  private AuthResponse register(String prefix) {
    TestRestTemplate rest = new TestRestTemplate();
    Map<String, String> body = Map.of("email", prefix + UUID.randomUUID() + "@x.local",
        "password", "password-123", "companyName", prefix + " Co");
    ResponseEntity<AuthResponse> response =
        rest.postForEntity(base() + "/api/v1/auth/register", body, AuthResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  private void awaitJob(String token, long jobId) {
    TestRestTemplate rest = new TestRestTemplate();
    long deadline = System.currentTimeMillis() + 180_000;
    while (true) {
      ResponseEntity<Map> response = rest.exchange(base() + "/api/v1/jobs/" + jobId,
          HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> matrix(String token, long versionId) {
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> response = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/matrix", HttpMethod.GET,
        new HttpEntity<>(auth(token)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyCompanyIsInsufficientData() {
    AuthResponse newcomer = register("e2empty-");
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> loaded = rest.exchange(base() + "/api/v1/demo/load-rfp",
        HttpMethod.POST, new HttpEntity<>(auth(newcomer.accessToken())), Map.class);
    assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    long versionId = ((Number) loaded.getBody().get("versionId")).longValue();
    awaitJob(newcomer.accessToken(), ((Number) loaded.getBody().get("jobId")).longValue());

    Map<String, Object> body = matrix(newcomer.accessToken(), versionId);
    assertThat(body.get("decision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(body.get("readinessScore")).isEqualTo("0.00");
    List<Object> rows = (List<Object>) body.get("rows");
    assertThat(rows).hasSize(6);
    for (Object row : rows) {
      Map<String, Object> map = (Map<String, Object>) row;
      Map<String, Object> requirement = (Map<String, Object>) map.get("requirement");
      if (Boolean.TRUE.equals(requirement.get("mandatory"))) {
        assertThat(map.get("verdict")).isEqualTo("UNKNOWN");
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void goldenJourneyToApprovedBid() throws Exception {
    TestRestTemplate rest = new TestRestTemplate();

    // Dashboard stream first so no live event is missed.
    List<Map<String, String>> dashEvents = new ArrayList<>();
    RealtimeTest.SseReader dashStream =
        new RealtimeTest.SseReader(base() + "/api/v1/dashboard/events", demoToken, null);
    assertThat(dashStream.status()).isEqualTo(200);
    try {
      ResponseEntity<Map> loaded = rest.exchange(base() + "/api/v1/demo/load-rfp",
          HttpMethod.POST, new HttpEntity<>(auth(demoToken)), Map.class);
      assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      long tenderId = ((Number) loaded.getBody().get("tenderId")).longValue();
      long v1 = ((Number) loaded.getBody().get("versionId")).longValue();
      long jobId = ((Number) loaded.getBody().get("jobId")).longValue();

      // Version stream while the pipeline runs.
      List<Map<String, String>> liveEvents = new ArrayList<>();
      try (RealtimeTest.SseReader stream = new RealtimeTest.SseReader(
          base() + "/api/v1/tender-versions/" + v1 + "/events", demoToken, null)) {
        assertThat(stream.status()).isEqualTo(200);
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
          Map<String, String> event = stream.next();
          if (event == null) {
            break;
          }
          liveEvents.add(event);
          if ("JOB_COMPLETED".equals(event.get("name"))) {
            break;
          }
        }
      }
      assertThat(liveEvents.stream().map(event -> event.get("name")))
          .contains("JOB_COMPLETED");
      assertThat(liveEvents.stream().map(event -> event.get("name")))
          .contains("VERDICT_COMPUTED", "DECISION_UPDATED");
      List<Long> seqs =
          liveEvents.stream().map(event -> Long.parseLong(event.get("id"))).toList();
      assertThat(seqs).isSorted();
      assertThat(seqs.stream().distinct().count()).isEqualTo((long) seqs.size());

      awaitJob(demoToken, jobId);
      Map<String, Object> v1Matrix = matrix(demoToken, v1);
      assertThat(v1Matrix.get("decision")).isEqualTo("NO_BID");
      assertThat(v1Matrix.get("readinessScore")).isEqualTo("40.00");
      Map<String, String> v1Verdicts = verdictsOf(v1Matrix);
      assertThat(v1Verdicts).containsExactly(Map.entry("4.1", "PASS"),
          Map.entry("4.2", "PASS"), Map.entry("5.1", "FAIL"), Map.entry("6.1", "FAIL"),
          Map.entry("6.2", "UNKNOWN"), Map.entry("8.1", "PASS"));

      // Dashboard stream saw the same completion live.
      long dashDeadline = System.currentTimeMillis() + 60_000;
      while (System.currentTimeMillis() < dashDeadline) {
        Map<String, String> event = dashStream.next();
        if (event == null) {
          break;
        }
        dashEvents.add(event);
        if ("JOB_COMPLETED".equals(event.get("name"))) {
          break;
        }
      }
      assertThat(dashEvents.stream().map(event -> event.get("name")))
          .contains("JOB_COMPLETED", "DECISION_UPDATED");

      // Corrigendum: v2 CONDITIONAL_BID 80.00, delta +40.00.
      ResponseEntity<Map> amended = rest.exchange(
          base() + "/api/v1/demo/load-corrigendum?tenderId=" + tenderId, HttpMethod.POST,
          new HttpEntity<>(auth(demoToken)), Map.class);
      assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      long v2 = ((Number) amended.getBody().get("versionId")).longValue();
      long amendmentId = ((Number) amended.getBody().get("amendmentId")).longValue();
      awaitJob(demoToken, ((Number) amended.getBody().get("jobId")).longValue());

      ResponseEntity<Map> diff = rest.exchange(
          base() + "/api/v1/amendments/" + amendmentId + "/diff", HttpMethod.GET,
          new HttpEntity<>(auth(demoToken)), Map.class);
      assertThat(((List<Object>) diff.getBody().get("modified"))).hasSize(2);
      assertThat(diff.getBody().get("prevDecision")).isEqualTo("NO_BID");
      assertThat(diff.getBody().get("newDecision")).isEqualTo("CONDITIONAL_BID");
      assertThat(diff.getBody().get("scoreDelta")).isEqualTo("40.00");

      Map<String, Object> v2Matrix = matrix(demoToken, v2);
      assertThat(v2Matrix.get("decision")).isEqualTo("CONDITIONAL_BID");
      assertThat(v2Matrix.get("readinessScore")).isEqualTo("80.00");

      // Solvency certificate with supporting document, then Re-Verify to BID.
      byte[] docBytes = "%PDF-1.4 bank solvency certificate".getBytes(StandardCharsets.UTF_8);
      ByteArrayResource resource = new ByteArrayResource(docBytes) {
        @Override
        public String getFilename() {
          return "solvency.pdf";
        }
      };
      MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
      multipart.add("file", resource);
      HttpHeaders uploadHeaders = auth(demoToken);
      uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
      ResponseEntity<Map> uploaded = rest.exchange(base() + "/api/v1/company/documents",
          HttpMethod.POST, new HttpEntity<>(multipart, uploadHeaders), Map.class);
      assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      long docId = ((Number) uploaded.getBody().get("id")).longValue();

      Map<String, Object> cert = new HashMap<>();
      cert.put("name", "Bank Solvency Certificate");
      cert.put("issueDate", "2026-10-01");
      cert.put("expiryDate", "2027-03-31");
      cert.put("amount", "180000000");
      cert.put("documentId", docId);
      HttpHeaders jsonHeaders = auth(demoToken);
      jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
      ResponseEntity<Map> createdCert = rest.exchange(base() + "/api/v1/company/certificates",
          HttpMethod.POST, new HttpEntity<>(cert, jsonHeaders), Map.class);
      assertThat(createdCert.getStatusCode()).isEqualTo(HttpStatus.CREATED);

      ResponseEntity<Map> reverify = rest.exchange(
          base() + "/api/v1/tender-versions/" + v2 + "/re-verify", HttpMethod.POST,
          new HttpEntity<>(auth(demoToken)), Map.class);
      assertThat(reverify.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
      awaitJob(demoToken, ((Number) reverify.getBody().get("jobId")).longValue());

      Map<String, Object> v3Matrix = matrix(demoToken, v2);
      assertThat(v3Matrix.get("decision")).isEqualTo("BID");
      assertThat(v3Matrix.get("readinessScore")).isEqualTo("100.00");
      assertThat(verdictsOf(v3Matrix).values()).containsOnly("PASS");

      ResponseEntity<Map> decision = rest.exchange(
          base() + "/api/v1/tender-versions/" + v2 + "/decision", HttpMethod.GET,
          new HttpEntity<>(auth(demoToken)), Map.class);
      assertThat(decision.getBody().get("approvalStatus"))
          .isEqualTo("PENDING_HUMAN_APPROVAL");

      ResponseEntity<Map> approved = rest.exchange(
          base() + "/api/v1/tender-versions/" + v2 + "/decision/approve", HttpMethod.POST,
          new HttpEntity<>(Map.of("decision", "APPROVED_FOR_BID"), jsonHeaders), Map.class);
      assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(approved.getBody().get("approvalStatus")).isEqualTo("APPROVED_FOR_BID");

      ResponseEntity<byte[]> pdfDownload = rest.exchange(base() + "/api/v1/tender-versions/"
          + v2 + "/reports/bid-package.pdf", HttpMethod.GET,
          new HttpEntity<>(auth(demoToken)), byte[].class);
      assertThat(pdfDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(new String(pdfDownload.getBody(), 0, 5, StandardCharsets.UTF_8))
          .isEqualTo("%PDF-");
      ResponseEntity<byte[]> xlsxDownload = rest.exchange(base() + "/api/v1/tender-versions/"
          + v2 + "/reports/checklist.xlsx", HttpMethod.GET,
          new HttpEntity<>(auth(demoToken)), byte[].class);
      assertThat(xlsxDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(xlsxDownload.getBody()).isNotEmpty();

      ResponseEntity<Map> audits = rest.exchange(base() + "/api/v1/audit-logs?size=100",
          HttpMethod.GET, new HttpEntity<>(auth(demoToken)), Map.class);
      List<Object> entries = (List<Object>) audits.getBody().get("items");
      assertThat(entries.stream().map(entry -> (String) ((Map<String, Object>) entry)
          .get("action"))).contains("APPROVE", "CREATE");
    } finally {
      dashStream.close();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> verdictsOf(Map<String, Object> body) {
    Map<String, String> verdicts = new LinkedHashMap<>();
    for (Object row : (List<Object>) body.get("rows")) {
      Map<String, Object> map = (Map<String, Object>) row;
      Map<String, Object> requirement = (Map<String, Object>) map.get("requirement");
      verdicts.put((String) requirement.get("clauseRef"), (String) map.get("verdict"));
    }
    return verdicts;
  }
}
