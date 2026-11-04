package com.tenderops.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.TestPasswords;
import com.tenderops.agent.AgentRun;
import com.tenderops.agent.AgentRunRepository;
import com.tenderops.agent.AgentStepRepository;
import com.tenderops.ai.ExtractionService;
import com.tenderops.audit.AuditService;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.CompanyDocumentRepository;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.compliance.ComplianceOutcome;
import com.tenderops.compliance.ComplianceService;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoDataSeeder;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.document.StorageService;
import com.tenderops.runs.RunService;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Agent endpoints: inline run returns the completed trace id; the trace shows steps,
 * latencies and the FALLBACK mode label without a key. Company-scoped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=",
    "spring.datasource.url=jdbc:h2:mem:tenderopsagtapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentApiTest {

  @LocalServerPort
  int port;

  private String demoToken;
  private long demoCompanyId;
  private long versionId;
  private long clause51;

  @Autowired
  private TenderPdfGenerator generator;

  @Autowired
  private PdfIngestionService ingestion;

  @Autowired
  private ExtractionService extraction;

  @Autowired
  private ComplianceService compliance;

  @Autowired
  private RunService runService;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private TenderRequirementRepository requirements;

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

  @Autowired
  private AgentRunRepository agentRuns;

  @Autowired
  private AgentStepRepository agentSteps;

  @Autowired
  private Clock clock;

  private String base() {
    return "http://localhost:" + port;
  }

  private HttpHeaders auth(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return headers;
  }

  @BeforeAll
  void seedAndProcess() throws Exception {
    Path temp = Files.createTempDirectory("agt-api-seed");
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
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(demoCompanyId, pdf, TenderPdfGenerator.V1_FILE);
    versionId = ingest.versionId();
    TenderVersion version = versions.findById(versionId).orElseThrow();
    ExtractionService.ExtractResult extracted = extraction.extractVersion(version);
    assertThat(extracted.museMode()).isEqualTo("FALLBACK");
    ComplianceOutcome outcome = compliance.evaluate(versionId, demoCompanyId, clock);
    runService.persistRun(versionId, demoCompanyId, outcome, extracted.museMode(),
        "FALLBACK");
    for (TenderRequirement row : requirements.findByTenderVersionIdOrderById(versionId)) {
      if ("5.1".equals(row.getClauseRef())) {
        clause51 = row.getId();
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void runReturnsTraceWithFallbackLabel() {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = auth(demoToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> started = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/agent/run", HttpMethod.POST,
        new HttpEntity<>(Map.of("requirementId", clause51), headers), Map.class);
    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    long agentRunId = ((Number) started.getBody().get("agentRunId")).longValue();

    ResponseEntity<Map> trace = rest.exchange(base() + "/api/v1/agent-runs/" + agentRunId,
        HttpMethod.GET, new HttpEntity<>(auth(demoToken)), Map.class);
    assertThat(trace.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(trace.getBody().get("mode")).isEqualTo(AgentRun.FALLBACK_DETERMINISTIC);
    assertThat(trace.getBody().get("museMode")).isEqualTo("FALLBACK");
    assertThat(trace.getBody().get("finalVerdict")).isEqualTo("FAIL");
    List<Object> steps = (List<Object>) trace.getBody().get("steps");
    assertThat(steps).hasSize(4);
    assertThat(((Map<String, Object>) steps.get(0)).get("toolName"))
        .isEqualTo("get_requirement");
    assertThat(((Map<String, Object>) steps.get(0)).get("latencyMs")).isNotNull();

    AgentRun stored = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(stored.getFinalVerdict()).isEqualTo("FAIL");
    assertThat(agentSteps.findByAgentRunIdOrderByStepNo(agentRunId)).hasSize(4);
  }

  @Test
  void runWithoutBodyUsesDefaultTarget() {
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> started = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/agent/run", HttpMethod.POST,
        new HttpEntity<>(auth(demoToken)), Map.class);
    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    long agentRunId = ((Number) started.getBody().get("agentRunId")).longValue();
    AgentRun stored = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(stored.getRequirementId()).isEqualTo(clause51);
    assertThat(stored.getFinalVerdict()).isEqualTo("FAIL");
  }

  @Test
  void unknownRequirementIsNotFound() {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = auth(demoToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> started = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/agent/run", HttpMethod.POST,
        new HttpEntity<>(Map.of("requirementId", 999999999L), headers), Map.class);
    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void traceIsScopedAndAuthenticated() {
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<Map> started = rest.exchange(
        base() + "/api/v1/tender-versions/" + versionId + "/agent/run", HttpMethod.POST,
        new HttpEntity<>(auth(demoToken)), Map.class);
    long agentRunId = ((Number) started.getBody().get("agentRunId")).longValue();

    ResponseEntity<AuthResponse> registered = rest.postForEntity(
        base() + "/api/v1/auth/register",
        Map.of("email", "agtstr-" + UUID.randomUUID() + "@x.local", "password",
            "password-123", "companyName", "Stranger Co"),
        AuthResponse.class);
    assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ResponseEntity<Map> cross = rest.exchange(base() + "/api/v1/agent-runs/" + agentRunId,
        HttpMethod.GET,
        new HttpEntity<>(auth(registered.getBody().accessToken())), Map.class);
    assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<Map> anon = rest.exchange(base() + "/api/v1/agent-runs/" + agentRunId,
        HttpMethod.GET, new HttpEntity<>(auth(null)), Map.class);
    assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
