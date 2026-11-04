package com.tenderops.amendment;

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
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import com.tenderops.workflow.WorkflowService;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Amendment upload + diff over HTTP: 201 flow, idempotent re-upload, 400/404 guards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=",
    "spring.datasource.url=jdbc:h2:mem:tenderopsamd;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmendmentApiTest {

  static class NamedBytes extends ByteArrayResource {
    private final String name;

    NamedBytes(byte[] bytes, String name) {
      super(bytes);
      this.name = name;
    }

    @Override
    public String getFilename() {
      return name;
    }
  }

  @LocalServerPort
  int port;

  private Path temp;

  @Autowired
  private WorkflowService workflows;

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
  private TenderPdfGenerator generator;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private AuditService auditService;

  @Autowired
  private TenderopsProperties properties;

  @Autowired
  private Environment environment;

  private String demoToken;
  private long demoCompanyId;

  @BeforeAll
  void seedOnce() throws Exception {
    temp = Files.createTempDirectory("amd-seed");
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, TestPasswords.DEMO);
    demoCompanyId = seeder.seed(temp).companyId();
    TestRestTemplate rest = new TestRestTemplate();
    Map<String, String> body = Map.of("email", "demo@tenderops.local", "password",
        TestPasswords.DEMO);
    ResponseEntity<AuthResponse> login = rest.postForEntity(
        base() + "/api/v1/auth/login", body, AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    demoToken = login.getBody().accessToken();
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private TenderVersion ingestV1(String tag) throws Exception {
    generator.generateSamples(temp);
    byte[] pdf = IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE)), tag);
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(demoCompanyId, pdf, "Smart_City_RFP_v1.pdf");
    return versions.findById(ingest.versionId()).orElseThrow();
  }

  private byte[] corrBytes(String tag) throws Exception {
    generator.generateSamples(temp);
    return IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.CORR_FILE)), tag);
  }

  private void awaitJob(long jobId) throws Exception {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(demoToken);
    long deadline = System.currentTimeMillis() + 180_000;
    while (true) {
      ResponseEntity<Map> response = rest.exchange(base() + "/api/v1/jobs/" + jobId,
          HttpMethod.GET, new HttpEntity<>(headers), Map.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      String status = (String) response.getBody().get("status");
      if ("COMPLETED".equals(status)) {
        return;
      }
      if ("FAILED".equals(status)) {
        throw new AssertionError("job failed: " + response.getBody().get("error"));
      }
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("job timed out");
      }
      Thread.sleep(300);
    }
  }

  private ResponseEntity<Map> uploadAmendment(long tenderId, byte[] pdf, String kind,
      String token) {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new NamedBytes(pdf, "Corrigendum_1.pdf"));
    body.add("kind", kind);
    return rest.exchange(base() + "/api/v1/tenders/" + tenderId + "/amendments",
        HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }

  @Test
  void uploadAmendmentRunsMergeJob() throws Exception {
    TenderVersion v1 = ingestV1("api-merge");
    long baseJob =
        workflows.startJob(v1.getId(), demoCompanyId, WorkflowService.KIND_FULL);
    workflows.launchAsync(baseJob);
    awaitJob(baseJob);
    byte[] corr = corrBytes("api-merge");

    ResponseEntity<Map> uploaded =
        uploadAmendment(v1.getTenderId(), corr, "CORRIGENDUM", demoToken);

    assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(uploaded.getBody()).isNotNull();
    Number jobId = (Number) uploaded.getBody().get("jobId");
    Number amendmentId = (Number) uploaded.getBody().get("amendmentId");
    assertThat(jobId).isNotNull();
    assertThat(amendmentId).isNotNull();
    awaitJob(jobId.longValue());

    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(demoToken);
    ResponseEntity<Map> diff = rest.exchange(
        base() + "/api/v1/amendments/" + amendmentId.longValue() + "/diff",
        HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(diff.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(diff.getBody().get("newDecision")).isEqualTo("CONDITIONAL_BID");
    assertThat(diff.getBody().get("prevDecision")).isEqualTo("NO_BID");
    assertThat(String.valueOf(diff.getBody().get("scoreDelta"))).isEqualTo("40.00");
    List<Map> modified = (List<Map>) diff.getBody().get("modified");
    List<String> clauses = new java.util.ArrayList<>();
    for (Map row : modified) {
      clauses.add((String) row.get("clauseRef"));
    }
    assertThat(clauses).containsExactly("5.1", "6.1");
  }

  @Test
  void duplicateAmendmentReturnsExisting() throws Exception {
    TenderVersion v1 = ingestV1("api-duplicate");
    long baseJob =
        workflows.startJob(v1.getId(), demoCompanyId, WorkflowService.KIND_FULL);
    workflows.launchAsync(baseJob);
    awaitJob(baseJob);
    byte[] corr = corrBytes("api-duplicate");
    ResponseEntity<Map> first =
        uploadAmendment(v1.getTenderId(), corr, "CORRIGENDUM", demoToken);
    assertThat(first.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
    if (first.getStatusCode() == HttpStatus.CREATED) {
      awaitJob(((Number) first.getBody().get("jobId")).longValue());
    }

    ResponseEntity<Map> second =
        uploadAmendment(v1.getTenderId(), corr, "CORRIGENDUM", demoToken);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().get("duplicate")).isEqualTo(Boolean.TRUE);
    assertThat(second.getBody().get("jobId")).isNull();
  }

  @Test
  void badKindIsRejected() throws Exception {
    TenderVersion v1 = ingestV1("api-badkind");
    byte[] corr = corrBytes("api-badkind");

    ResponseEntity<Map> response =
        uploadAmendment(v1.getTenderId(), corr, "BOGUS", demoToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void crossCompanyAmendmentIsNotFound() throws Exception {
    TenderVersion v1 = ingestV1("api-cross");
    TestRestTemplate rest = new TestRestTemplate();
    Map<String, String> body = Map.of("email", "amd-stranger-" + UUID.randomUUID()
        + "@x.local", "password", "password-123", "companyName", "Stranger Co");
    ResponseEntity<AuthResponse> registered = rest.postForEntity(
        base() + "/api/v1/auth/register", body, AuthResponse.class);
    assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    byte[] corr = corrBytes("api-cross");

    ResponseEntity<Map> response = uploadAmendment(v1.getTenderId(), corr, "CORRIGENDUM",
        registered.getBody().accessToken());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
