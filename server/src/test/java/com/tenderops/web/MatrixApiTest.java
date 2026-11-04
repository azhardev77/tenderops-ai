package com.tenderops.web;

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
import com.tenderops.document.PdfIngestionService;
import com.tenderops.document.StorageService;
import com.tenderops.security.User;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Matrix, re-verify, risks, checklist, clarifications, summary and approval over HTTP.
 * Own database: asserts the golden v1 verdicts on a pristine demo company.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=",
    "spring.datasource.url=jdbc:h2:mem:tenderopsmtx;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatrixApiTest {

  @LocalServerPort
  int port;

  private Path temp;
  private String demoToken;
  private long demoCompanyId;
  private long versionId;

  @Autowired
  private TenderPdfGenerator generator;

  @Autowired
  private PdfIngestionService ingestion;

  @Autowired
  private TenderVersionRepository versions;

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
  void seedAndProcess() throws Exception {
    temp = Files.createTempDirectory("mtx-seed");
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, TestPasswords.DEMO);
    demoCompanyId = seeder.seed(temp).companyId();
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<AuthResponse> login = rest.postForEntity(base() + "/api/v1/auth/login",
        Map.of("email", DemoDataSeeder.DEMO_EMAIL, "password", TestPasswords.DEMO),
        AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    demoToken = login.getBody().accessToken();

    generator.generateSamples(temp);
    byte[] pdf = Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE));
    ByteArrayResource resource = new ByteArrayResource(pdf) {
      @Override
      public String getFilename() {
        return TenderPdfGenerator.V1_FILE;
      }
    };
    MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
    multipart.add("file", resource);
    HttpHeaders headers = auth(demoToken);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<Map> uploaded = rest.exchange(base() + "/api/v1/tenders", HttpMethod.POST,
        new HttpEntity<>(multipart, headers), Map.class);
    assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    versionId = ((Number) uploaded.getBody().get("versionId")).longValue();
    awaitJob(((Number) uploaded.getBody().get("jobId")).longValue());
  }

  @SuppressWarnings("unchecked")
  private void awaitJob(long jobId) {
    TestRestTemplate rest = new TestRestTemplate();
    long deadline = System.currentTimeMillis() + 180_000;
    while (true) {
      ResponseEntity<Map> response = rest.exchange(base() + "/api/v1/jobs/" + jobId,
          HttpMethod.GET, new HttpEntity<>(auth(demoToken)), Map.class);
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
  private Map<String, Object> get(String path) {
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> response = rest.exchange(base() + path, HttpMethod.GET,
        new HttpEntity<>(auth(demoToken)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  @Test
  @SuppressWarnings("unchecked")
  void matrixShowsGoldenV1Verdicts() {
    Map<String, Object> matrix = get("/api/v1/tender-versions/" + versionId + "/matrix");
    assertThat(matrix.get("decision")).isEqualTo("NO_BID");
    assertThat(matrix.get("readinessScore")).isEqualTo("40.00");
    assertThat(matrix.get("museMode")).isEqualTo("FALLBACK");
    assertThat(matrix.get("samMode")).isEqualTo("FALLBACK");
    Map<String, Object> timeSaved = (Map<String, Object>) matrix.get("timeSaved");
    assertThat(timeSaved).isNotNull();
    assertThat(timeSaved.get("manualMinutes")).isEqualTo("24.00");

    List<Object> rows = (List<Object>) matrix.get("rows");
    assertThat(rows).hasSize(6);
    Map<String, String> verdicts = new LinkedHashMap<>();
    Map<String, Map<String, Object>> traces = new LinkedHashMap<>();
    for (Object row : rows) {
      Map<String, Object> map = (Map<String, Object>) row;
      Map<String, Object> requirement = (Map<String, Object>) map.get("requirement");
      verdicts.put((String) requirement.get("clauseRef"), (String) map.get("verdict"));
      traces.put((String) requirement.get("clauseRef"),
          (Map<String, Object>) map.get("calculationTrace"));
      assertThat(map.get("copied")).isEqualTo(false);
    }
    assertThat(verdicts).containsExactly(Map.entry("4.1", "PASS"), Map.entry("4.2", "PASS"),
        Map.entry("5.1", "FAIL"), Map.entry("6.1", "FAIL"), Map.entry("6.2", "UNKNOWN"),
        Map.entry("8.1", "PASS"));
    Map<String, Object> trace41 = traces.get("4.1");
    assertThat(trace41.get("verdict")).isEqualTo("PASS");
    assertThat(trace41.get("formula")).isNotNull();
    assertThat((List<Object>) trace41.get("evidenceIds")).isNotEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void risksChecklistClarificationsSummary() {
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<List> risks = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/risks", HttpMethod.GET,
        new HttpEntity<>(auth(demoToken)), List.class);
    assertThat(risks.getBody()).hasSize(3);
    long critical =
        risks.getBody().stream().filter(row -> "CRITICAL".equals(((Map<String, Object>) row)
            .get("severity"))).count();
    assertThat(critical).isEqualTo(2L);

    ResponseEntity<List> checklist = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/checklist", HttpMethod.GET,
        new HttpEntity<>(auth(demoToken)), List.class);
    assertThat(checklist.getBody()).hasSize(1);
    Map<String, Object> item = (Map<String, Object>) checklist.getBody().get(0);
    assertThat(item.get("clauseRef")).isEqualTo("6.2");
    assertThat((String) item.get("missingItem")).contains("Solvency");

    ResponseEntity<List> clarifications = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/clarifications", HttpMethod.GET,
        new HttpEntity<>(auth(demoToken)), List.class);
    assertThat(clarifications.getBody()).isEmpty();

    Map<String, Object> summary =
        get("/api/v1/tender-versions/" + versionId + "/summary");
    assertThat((String) summary.get("text")).contains("NO_BID");
    assertThat(summary.get("source")).isEqualTo("FALLBACK_TEMPLATE");
    assertThat(summary.get("groundingWarning")).isEqualTo(false);
  }

  @Test
  void decisionAndApprovalGate() {
    Map<String, Object> before = get("/api/v1/tender-versions/" + versionId + "/decision");
    assertThat(before.get("recommendation")).isEqualTo("NO_BID");
    assertThat(before.get("approvalStatus")).isEqualTo("PENDING_HUMAN_APPROVAL");
    assertThat(before.get("readinessScore")).isEqualTo("40.00");

    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> approved = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/decision/approve",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("decision", "REJECTED_NO_BID"), auth(demoToken)), Map.class);
    assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(approved.getBody().get("approvalStatus")).isEqualTo("REJECTED_NO_BID");

    Map<String, Object> after = get("/api/v1/tender-versions/" + versionId + "/decision");
    assertThat(after.get("approvalStatus")).isEqualTo("REJECTED_NO_BID");

    ResponseEntity<Map> again = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/decision/approve",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("decision", "APPROVED_FOR_BID"), auth(demoToken)), Map.class);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void approveRejectsBadValueAndViewer() {
    TestRestTemplate rest = new TestRestTemplate();
    String email = "mtxviewer-" + UUID.randomUUID() + "@x.local";
    users.save(new User(demoCompanyId, email, passwordEncoder.encode("viewer-123"), "VIEWER"));
    ResponseEntity<AuthResponse> login = rest.postForEntity(base() + "/api/v1/auth/login",
        Map.of("email", email, "password", "viewer-123"), AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders viewerHeaders = auth(login.getBody().accessToken());
    viewerHeaders.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> forbidden = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/decision/approve",
        HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVED_FOR_BID"),
            viewerHeaders),
        Map.class);
    assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    HttpHeaders adminHeaders = auth(demoToken);
    adminHeaders.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> bad = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/decision/approve",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("decision", "MAYBE"), adminHeaders), Map.class);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @SuppressWarnings("unchecked")
  void reverifyProducesIdenticalVerdicts() {
    Map<String, Object> before = get("/api/v1/tender-versions/" + versionId + "/matrix");
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> started = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/re-verify", HttpMethod.POST,
        new HttpEntity<>(auth(demoToken)), Map.class);
    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    awaitJob(((Number) started.getBody().get("jobId")).longValue());

    Map<String, Object> after = get("/api/v1/tender-versions/" + versionId + "/matrix");
    assertThat(after.get("runId")).isNotEqualTo(before.get("runId"));
    assertThat(after.get("decision")).isEqualTo("NO_BID");
    assertThat(after.get("readinessScore")).isEqualTo("40.00");
    List<Object> rows = (List<Object>) after.get("rows");
    assertThat(rows).hasSize(6);

    Map<String, Object> pinned =
        get("/api/v1/tender-versions/" + versionId + "/matrix?runId=" + before.get("runId"));
    assertThat(pinned.get("runId")).isEqualTo(before.get("runId"));

    ResponseEntity<Map> missing = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/matrix?runId=999999",
        HttpMethod.GET, new HttpEntity<>(auth(demoToken)), Map.class);
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void matrixWithoutRunIsNotFound() throws Exception {
    generator.generateSamples(temp);
    byte[] pdf = com.tenderops.IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE)), "norun");
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(demoCompanyId, pdf, "unprocessed.pdf");
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> response = rest.exchange(base() + "/api/v1/tender-versions/"
        + ingest.versionId() + "/matrix", HttpMethod.GET,
        new HttpEntity<>(auth(demoToken)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void crossCompanyMatrixIsNotFound() {
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<AuthResponse> registered = rest.postForEntity(
        base() + "/api/v1/auth/register",
        Map.of("email", "mtxstr-" + UUID.randomUUID() + "@x.local", "password",
            "password-123", "companyName", "Stranger Co"),
        AuthResponse.class);
    assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ResponseEntity<Map> response = rest.exchange(base() + "/api/v1/tender-versions/"
        + versionId + "/matrix", HttpMethod.GET,
        new HttpEntity<>(auth(registered.getBody().accessToken())), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
