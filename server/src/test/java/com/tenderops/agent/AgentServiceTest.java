package com.tenderops.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tenderops.ai.ExtractionService;
import com.tenderops.audit.AuditService;
import com.tenderops.company.CertificateRepository;
import com.tenderops.company.CompanyDocumentRepository;
import com.tenderops.company.CompanyRepository;
import com.tenderops.company.FinancialRecordRepository;
import com.tenderops.company.ProjectRepository;
import com.tenderops.compliance.ComplianceOutcome;
import com.tenderops.compliance.ComplianceService;
import com.tenderops.compliance.RequirementEvaluator;
import com.tenderops.config.TenderopsProperties;
import com.tenderops.demo.DemoDataSeeder;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.document.StorageService;
import com.tenderops.runs.ComplianceResultRepository;
import com.tenderops.runs.ComplianceRunRepository;
import com.tenderops.runs.RunService;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderRequirementRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.AiCallLog;
import com.tenderops.visual.AiCallLogRepository;
import com.tenderops.visual.AiCallLogger;
import com.tenderops.ai.MetaApiTransport;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * TC-AGT-01/02/03 (model-driven halves): a scripted Muse double drives the bounded
 * tool loop — multi-step execution with invalid-argument recovery, step/time limits,
 * and engine-over-model verdict supremacy. Extraction requests get 500 so the setup
 * stays on the rule-based fallback.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=TESTKEY-AGENT-1",
    "tenderops.meta-api.max-attempts=1",
    "tenderops.meta-api.backoff-initial-ms=10",
    "tenderops.meta-api.request-timeout-ms=5000",
    "spring.datasource.url=jdbc:h2:mem:tenderopsagt;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentServiceTest {

  private static HttpServer server;
  private static final AtomicInteger agentHits = new AtomicInteger();
  private static volatile Function<String, String> agentResponder =
      body -> {
        throw new IllegalStateException("No agent script installed");
      };

  static {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/responses", exchange -> {
        String body;
        try (var in = exchange.getRequestBody()) {
          body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        byte[] payload;
        int status;
        if (body.contains("verification assistant")) {
          agentHits.incrementAndGet();
          String modelJson;
          try {
            modelJson = agentResponder.apply(body);
          } catch (Exception e) {
            modelJson = "{\"tool_calls\":[],\"done\":true,\"explanation\":\"script error\"}";
          }
          String json = "{\"output\":[{\"content\":[{\"text\":"
              + quoteJson(modelJson) + "}]}]}";
          payload = json.getBytes(StandardCharsets.UTF_8);
          status = 200;
        } else {
          payload = "{\"error\":\"extraction not served\"}".getBytes(StandardCharsets.UTF_8);
          status = 500;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(payload);
        }
      });
      server.setExecutor(Executors.newCachedThreadPool());
      server.start();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot start agent test double", e);
    }
  }

  private static String quoteJson(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\"";
  }

  @DynamicPropertySource
  static void doubleUrl(DynamicPropertyRegistry registry) {
    registry.add("tenderops.meta-api.base-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterAll
  void stopDouble() {
    server.stop(0);
  }

  @TempDir
  Path temp;

  @Autowired
  private AgentService agents;

  @Autowired
  private AgentRunRepository agentRuns;

  @Autowired
  private AgentStepRepository agentSteps;

  @Autowired
  private AgentTools agentTools;

  @Autowired
  private AgentTx agentTx;

  @Autowired
  private TenderRepository tenders;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private TenderRequirementRepository requirements;

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
  private UserRepository users;

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

  @Autowired
  private PdfIngestionService ingestion;

  @Autowired
  private ExtractionService extraction;

  @Autowired
  private ComplianceService compliance;

  @Autowired
  private RunService runService;

  @Autowired
  private ComplianceRunRepository runs;

  @Autowired
  private ComplianceResultRepository storedResults;

  @Autowired
  private RequirementEvaluator evaluator;

  @Autowired
  private MetaApiTransport transport;

  @Autowired
  private AiCallLogger callLogger;

  @Autowired
  private AiCallLogRepository callLogs;

  @Autowired
  private ObjectMapper mapper;

  private record Seeded(long companyId, long versionId) {
  }

  private static final Clock GOLDEN_CLOCK = Clock.fixed(
      ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant(),
      ZoneId.of("Asia/Kolkata"));

  @BeforeEach
  void resetDouble() {
    agentHits.set(0);
    agentResponder = body -> {
      throw new IllegalStateException("No agent script installed");
    };
  }

  private Seeded seed() throws Exception {
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, "agent-test-password-1");
    long companyId = seeder.seed(temp).companyId();
    generator.generateSamples(temp);
    // Non-transactional: every test commits, so each fixture needs a distinct SHA.
    byte[] pdf = com.tenderops.IsolatedPdfs.tag(
        Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE)),
        "agt-" + java.util.UUID.randomUUID());
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, TenderPdfGenerator.V1_FILE);
    assertThat(ingest.duplicate()).isFalse();
    TenderVersion version = versions.findById(ingest.versionId()).orElseThrow();
    ExtractionService.ExtractResult extracted = extraction.extractVersion(version);
    assertThat(extracted.museMode()).isEqualTo("FALLBACK");
    ComplianceOutcome outcome = compliance.evaluate(version.getId(), companyId, GOLDEN_CLOCK);
    runService.persistRun(version.getId(), companyId, outcome, extracted.museMode(),
        "FALLBACK");
    return new Seeded(companyId, version.getId());
  }

  private long idOfClause(long versionId, String clauseRef) {
    for (TenderRequirement row : requirements.findByTenderVersionIdOrderById(versionId)) {
      if (clauseRef.equals(row.getClauseRef())) {
        return row.getId();
      }
    }
    throw new IllegalStateException("No requirement " + clauseRef);
  }

  private static long targetIdFrom(String body) {
    Matcher matcher = Pattern.compile("Target requirement (\\d+)").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException("Double cannot find target requirement in request");
    }
    return Long.parseLong(matcher.group(1));
  }

  private static String agentJson(List<Map<String, Object>> calls) {
    StringBuilder json = new StringBuilder("{\"tool_calls\":[");
    for (int i = 0; i < calls.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"name\":\"").append(calls.get(i).get("name")).append("\",\"arguments\":{");
      @SuppressWarnings("unchecked")
      Map<String, Object> args = (Map<String, Object>) calls.get(i).get("arguments");
      boolean first = true;
      for (Map.Entry<String, Object> entry : args.entrySet()) {
        if (!first) {
          json.append(',');
        }
        first = false;
        json.append("\"").append(entry.getKey()).append("\":");
        Object value = entry.getValue();
        if (value instanceof String s) {
          json.append("\"").append(s).append("\"");
        } else {
          json.append(value);
        }
      }
      json.append("}}");
    }
    return json.append("],\"done\":false}").toString();
  }

  private static String agentDone(String assertion, String explanation) {
    return "{\"tool_calls\":[],\"done\":true,\"verdictAssertion\":\"" + assertion
        + "\",\"explanation\":\"" + explanation + "\"}";
  }

  private static Map<String, Object> call(String name, Map<String, Object> args) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", name);
    out.put("arguments", args);
    return out;
  }

  @Test
  void multiStepLoopRecoversFromInvalidArgs() throws Exception {
    Seeded seeded = seed();
    agentResponder = body -> {
      long target = targetIdFrom(body);
      return switch (agentHits.get()) {
        case 1 -> agentJson(
            List.of(call("get_requirement", Map.of("requirementId", target))));
        case 2 -> agentJson(List.of(call("run_deterministic_compliance",
            Map.of("requirementId", "not-a-number"))));
        case 3 -> agentJson(List.of(call("run_deterministic_compliance",
            Map.of("requirementId", target))));
        default -> agentDone("FAIL", "trace reviewed, engine agrees");
      };
    };

    long targetId = idOfClause(seeded.versionId(), "5.1");
    long agentRunId = agents.run(seeded.versionId(), seeded.companyId(), targetId);

    AgentRun run = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(run.getMode()).isEqualTo(AgentRun.MODEL_DRIVEN);
    assertThat(run.getFinalVerdict()).isEqualTo("FAIL");
    assertThat(run.getRequirementId()).isEqualTo(targetId);
    List<AgentStep> steps = agentSteps.findByAgentRunIdOrderByStepNo(agentRunId);
    assertThat(steps).hasSize(4);
    assertThat(steps.get(0).getToolName()).isEqualTo("get_requirement");
    assertThat(steps.get(0).getErrorText()).isNull();
    assertThat(steps.get(0).getOutputJson()).contains("5.1");
    assertThat(steps.get(1).getToolName()).isEqualTo("run_deterministic_compliance");
    assertThat(steps.get(1).getErrorText()).contains("must be an integer");
    assertThat(steps.get(2).getToolName()).isEqualTo("run_deterministic_compliance");
    assertThat(steps.get(2).getErrorText()).isNull();
    assertThat(steps.get(2).getOutputJson()).contains("FAIL");
    assertThat(steps.get(3).getToolName()).isEqualTo("MODEL_SUMMARY");
    assertThat(steps).allMatch(step -> step.getLatencyMs() != null);

    List<AiCallLog> museCalls = callLogs.findByComponentOrderById("MUSE");
    assertThat(museCalls.stream().filter(
        log -> "agent-loop".equals(log.getPurpose()) && "LIVE".equals(log.getAiMode()))
        .count()).isGreaterThanOrEqualTo(4L);
  }

  @Test
  void stepLimitStopsEndlessModel() throws Exception {
    Seeded seeded = seed();
    agentResponder = body -> agentJson(List.of(call("get_company_financials", Map.of())));

    long agentRunId = agents.run(seeded.versionId(), seeded.companyId(), null);

    AgentRun run = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(run.getMode()).isEqualTo(AgentRun.MODEL_DRIVEN);
    List<AgentStep> steps = agentSteps.findByAgentRunIdOrderByStepNo(agentRunId);
    assertThat(steps).hasSize(10);
    assertThat(agentHits.get()).isEqualTo(10);
    // Default target: first non-PASS mandatory requirement (golden 5.1 FAIL).
    assertThat(run.getFinalVerdict()).isEqualTo("FAIL");
    assertThat(run.getRequirementId()).isEqualTo(idOfClause(seeded.versionId(), "5.1"));
  }

  @Test
  void injectedLimitsAreHonored() throws Exception {
    Seeded seeded = seed();
    agentResponder = body -> agentJson(List.of(call("get_company_financials", Map.of())));
    AgentService tight = new AgentService(tenders, versions, requirements, companies,
        financials, projects, certificates, runs, storedResults, evaluator, agentTools,
        agentTx, transport, callLogger, properties, mapper,
        new AgentLimits(3, 10_000L, 120_000L));

    long agentRunId = tight.run(seeded.versionId(), seeded.companyId(), null);

    assertThat(agentSteps.findByAgentRunIdOrderByStepNo(agentRunId)).hasSize(3);

    AgentService noBudget = new AgentService(tenders, versions, requirements, companies,
        financials, projects, certificates, runs, storedResults, evaluator, agentTools,
        agentTx, transport, callLogger, properties, mapper,
        new AgentLimits(10, 10_000L, -1L));
    long starvedId = noBudget.run(seeded.versionId(), seeded.companyId(), null);
    assertThat(agentSteps.findByAgentRunIdOrderByStepNo(starvedId)).isEmpty();
    assertThat(agentRuns.findById(starvedId).orElseThrow().getFinalVerdict())
        .isEqualTo("FAIL");
  }

  @Test
  void persistedVerdictEqualsEngineDespiteModelAssertion() throws Exception {
    Seeded seeded = seed();
    agentResponder =
        body -> agentDone("PASS", "the model wrongly claims this requirement passes");

    long targetId = idOfClause(seeded.versionId(), "5.1");
    long agentRunId = agents.run(seeded.versionId(), seeded.companyId(), targetId);

    AgentRun run = agentRuns.findById(agentRunId).orElseThrow();
    assertThat(run.getMode()).isEqualTo(AgentRun.MODEL_DRIVEN);
    assertThat(run.getFinalVerdict()).isEqualTo("FAIL");
    List<AgentStep> steps = agentSteps.findByAgentRunIdOrderByStepNo(agentRunId);
    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).getToolName()).isEqualTo("MODEL_SUMMARY");
    assertThat(steps.get(0).getOutputJson()).contains("PASS");
  }
}
