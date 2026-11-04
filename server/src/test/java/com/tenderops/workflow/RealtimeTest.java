package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * TC-RT-01/02/03/05/06/07/08 over real HTTP: ordered live streams, replay without
 * gaps or duplicates, auth, secret-free payloads, emitter cleanup, polling, latency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealtimeTest {

  /** Minimal SSE client over java.net.http. Heartbeat comments are skipped. */
  static class SseReader implements AutoCloseable {
    private final HttpResponse<InputStream> response;
    private final BufferedReader reader;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    SseReader(String url, String token, String lastEventId) throws Exception {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
          .header("Accept", "text/event-stream");
      if (token != null) {
        builder.header("Authorization", "Bearer " + token);
      }
      if (lastEventId != null) {
        builder.header("Last-Event-ID", lastEventId);
      }
      response = client.send(builder.GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
      reader = new BufferedReader(
          new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    }

    int status() {
      return response.statusCode();
    }

    Map<String, String> next() throws Exception {
      Future<Map<String, String>> future = exec.submit(() -> {
        String id = null;
        String name = null;
        String data = "";
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty()) {
            if (name != null || !data.isEmpty() || id != null) {
              break;
            }
            continue;
          }
          if (line.startsWith(":")) {
            continue;
          }
          if (line.startsWith("id:")) {
            id = line.substring(3).trim();
          } else if (line.startsWith("event:")) {
            name = line.substring(6).trim();
          } else if (line.startsWith("data:")) {
            data = line.substring(5).trim();
          }
        }
        if (line == null && name == null && data.isEmpty()) {
          return null;
        }
        Map<String, String> event = new HashMap<>();
        event.put("id", id == null ? "" : id);
        event.put("name", name == null ? "" : name);
        event.put("data", data);
        return event;
      });
      return future.get(120, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      exec.shutdownNow();
      try {
        response.body().close();
      } catch (Exception ignored) {
        // Best effort; the server cleans up on failed send.
      }
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
  private EventService eventService;

  @Autowired
  private SseHub hub;

  @Autowired
  private WorkflowEventRepository eventRows;

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
    temp = java.nio.file.Files.createTempDirectory("rt-seed");
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
    assertThat(login.getBody()).isNotNull();
    demoToken = login.getBody().accessToken();
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private String streamUrl(long versionId) {
    return base() + "/api/v1/tender-versions/" + versionId + "/events";
  }

  private TenderVersion ingestProbe(String marker) throws Exception {
    List<String> lines = List.of("Realtime probe " + marker + " " + System.nanoTime(),
        "Clause 4.1 The average annual turnover of the bidder over the last "
            + "three audited financial years shall be not less than Rs 100 Cr.",
        "Bid submission deadline 15 December 2026, 17:00 IST.");
    byte[] pdf = onePagePdf(lines);
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(demoCompanyId, pdf, "probe.pdf");
    assertThat(ingest.duplicate()).isFalse();
    return versions.findById(ingest.versionId()).orElseThrow();
  }

  private static byte[] onePagePdf(List<String> lines) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        content.beginText();
        content.newLineAtOffset(72, 720);
        for (String line : lines) {
          content.showText(line);
          content.newLineAtOffset(0, -16);
        }
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private List<Map<String, String>> collectUntil(SseReader reader, String terminal)
      throws Exception {
    List<Map<String, String>> out = new ArrayList<>();
    while (true) {
      Map<String, String> event = reader.next();
      if (event == null) {
        break;
      }
      out.add(event);
      if (terminal.equals(event.get("name"))) {
        break;
      }
    }
    return out;
  }

  private void awaitJob(long jobId) throws Exception {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(demoToken);
    long deadline = System.currentTimeMillis() + 120_000;
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
      Thread.sleep(200);
    }
  }

  private List<Map> poll(long versionId, long afterSeq, String token) {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    ResponseEntity<List> response = rest.exchange(
        streamUrl(versionId) + "?afterSeq=" + afterSeq, HttpMethod.GET,
        new HttpEntity<>(headers), List.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    List<Map> out = new ArrayList<>();
    for (Object row : response.getBody()) {
      out.add((Map) row);
    }
    return out;
  }

  @Test
  void streamDeliversFullJobInStageOrder() throws Exception {
    TenderVersion version = ingestProbe("order");
    long jobId =
        workflows.startJob(version.getId(), demoCompanyId, WorkflowService.KIND_FULL);

    List<Map<String, String>> received;
    try (SseReader reader = new SseReader(streamUrl(version.getId()), demoToken, null)) {
      assertThat(reader.status()).isEqualTo(200);
      workflows.launchAsync(jobId);
      received = collectUntil(reader, "JOB_COMPLETED");
    }

    assertThat(received).isNotEmpty();
    List<Long> seqs = new ArrayList<>();
    for (Map<String, String> event : received) {
      seqs.add(Long.parseLong(event.get("id")));
    }
    for (int i = 1; i < seqs.size(); i++) {
      assertThat(seqs.get(i)).isGreaterThan(seqs.get(i - 1));
    }
    ObjectMapper stages = new ObjectMapper();
    List<String> startedStages = new ArrayList<>();
    for (Map<String, String> event : received) {
      if ("STAGE_STARTED".equals(event.get("name"))) {
        startedStages.add(stages.readTree(event.get("data")).get("stage").asText());
      }
    }
    assertThat(startedStages).containsExactly("VALIDATE", "PARSE", "RENDER", "VISUAL",
        "EXTRACT", "VERIFY", "RISK", "DECIDE");
    List<String> names = new ArrayList<>();
    for (Map<String, String> event : received) {
      names.add(event.get("name"));
    }
    assertThat(names).contains("PAGE_PARSED", "REQUIREMENT_EXTRACTED",
        "VERDICT_COMPUTED", "AI_CALL", "DECISION_UPDATED");
  }

  @Test
  void reconnectReplaysWithoutGapsOrDupes() throws Exception {
    TenderVersion version = ingestProbe("replay");
    long jobId =
        workflows.startJob(version.getId(), demoCompanyId, WorkflowService.KIND_FULL);
    List<Map<String, String>> first = new ArrayList<>();
    String lastId;
    try (SseReader reader = new SseReader(streamUrl(version.getId()), demoToken, null)) {
      assertThat(reader.status()).isEqualTo(200);
      workflows.launchAsync(jobId);
      for (int i = 0; i < 5; i++) {
        first.add(reader.next());
      }
      lastId = first.get(first.size() - 1).get("id");
    }
    awaitJob(jobId);

    List<Map<String, String>> rest;
    try (SseReader reader =
        new SseReader(streamUrl(version.getId()), demoToken, lastId)) {
      assertThat(reader.status()).isEqualTo(200);
      rest = collectUntil(reader, "JOB_COMPLETED");
    }

    List<Long> combined = new ArrayList<>();
    for (Map<String, String> event : first) {
      combined.add(Long.parseLong(event.get("id")));
    }
    for (Map<String, String> event : rest) {
      long seq = Long.parseLong(event.get("id"));
      assertThat(seq).isGreaterThan(Long.parseLong(lastId));
      combined.add(seq);
    }
    List<Long> stored = new ArrayList<>();
    for (WorkflowEvent row :
        eventRows.findByTenderVersionIdOrderBySeq(version.getId())) {
      stored.add(row.getSeq());
    }
    assertThat(combined).isEqualTo(stored);
  }

  @Test
  void streamRequiresAuthAndCompany() throws Exception {
    TenderVersion version = ingestProbe("auth");
    try (SseReader anonymous =
        new SseReader(streamUrl(version.getId()), null, null)) {
      assertThat(anonymous.status()).isEqualTo(401);
    }
    TestRestTemplate rest = new TestRestTemplate();
    Map<String, String> body = Map.of("email", "rt-stranger-" + UUID.randomUUID()
        + "@x.local", "password", "password-123", "companyName", "Stranger Co");
    ResponseEntity<AuthResponse> registered = rest.postForEntity(
        base() + "/api/v1/auth/register", body, AuthResponse.class);
    assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    try (SseReader stranger = new SseReader(streamUrl(version.getId()),
        registered.getBody().accessToken(), null)) {
      assertThat(stranger.status()).isEqualTo(404);
    }
  }

  @Test
  void payloadsCarryNoSecrets() throws Exception {
    TenderVersion version = ingestProbe("secrets");
    long jobId =
        workflows.startJob(version.getId(), demoCompanyId, WorkflowService.KIND_FULL);
    workflows.launchAsync(jobId);
    awaitJob(jobId);

    List<Map> rows = poll(version.getId(), 0, demoToken);
    assertThat(rows).isNotEmpty();
    ObjectMapper mapper = new ObjectMapper();
    for (Map row : rows) {
      String text = mapper.writeValueAsString(row);
      assertThat(text).doesNotContain("Bearer", "ApiTestDummySecret", "api-key",
          "password", "rawPrompt", "systemPrompt");
      if ("AI_CALL".equals(row.get("type"))) {
        Map payload = (Map) row.get("payload");
        assertThat(payload.keySet()).containsExactlyInAnyOrder("jobId", "component",
            "model", "status", "latencyMs", "attempt", "aiMode");
      }
    }
  }

  @Test
  void disconnectCleansUpEmitters() throws Exception {
    TenderVersion version = ingestProbe("cleanup");
    int baseline = hub.liveEmitters();
    SseReader reader = new SseReader(streamUrl(version.getId()), demoToken, null);
    assertThat(reader.status()).isEqualTo(200);
    assertThat(hub.liveEmitters()).isEqualTo(baseline + 1);
    reader.close();

    for (int i = 0; i < 3; i++) {
      eventService.publish(version.getId(), demoCompanyId, "STAGE_PROGRESS", "VALIDATE",
          Map.of("probe", i));
    }
    long deadline = System.currentTimeMillis() + 10_000;
    while (hub.liveEmitters() != baseline && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    assertThat(hub.liveEmitters()).isEqualTo(baseline);
  }

  @Test
  void pollingMatchesStoredStream() throws Exception {
    TenderVersion version = ingestProbe("poll");
    long jobId =
        workflows.startJob(version.getId(), demoCompanyId, WorkflowService.KIND_FULL);
    workflows.launchAsync(jobId);
    awaitJob(jobId);

    List<Map> full = poll(version.getId(), 0, demoToken);
    List<Long> stored = new ArrayList<>();
    for (WorkflowEvent row :
        eventRows.findByTenderVersionIdOrderBySeq(version.getId())) {
      stored.add(row.getSeq());
    }
    List<Long> polled = new ArrayList<>();
    for (Map row : full) {
      polled.add(((Number) row.get("seq")).longValue());
    }
    assertThat(polled).isEqualTo(stored);
    long mid = stored.get(2);
    List<Map> suffix = poll(version.getId(), mid, demoToken);
    List<Long> suffixSeqs = new ArrayList<>();
    for (Map row : suffix) {
      suffixSeqs.add(((Number) row.get("seq")).longValue());
    }
    assertThat(suffixSeqs).isEqualTo(stored.subList(3, stored.size()));
  }

  @Test
  void timeToFirstEventIsFast() throws Exception {
    TenderVersion version = ingestProbe("latency");
    long jobId =
        workflows.startJob(version.getId(), demoCompanyId, WorkflowService.KIND_FULL);

    long firstMs;
    try (SseReader reader = new SseReader(streamUrl(version.getId()), demoToken, null)) {
      assertThat(reader.status()).isEqualTo(200);
      long start = System.nanoTime();
      workflows.launchAsync(jobId);
      Map<String, String> first = reader.next();
      firstMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(first).isNotNull();
      assertThat(first.get("name")).isEqualTo("STAGE_STARTED");
      collectUntil(reader, "JOB_COMPLETED");
    }
    System.out.println("RT-08 time-to-first-event ms: " + firstMs);
    assertThat(firstMs).isLessThan(10_000);
  }
}
