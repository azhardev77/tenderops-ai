package com.tenderops.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyRepository;
import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.document.PdfIngestionService;
import com.tenderops.tender.TenderRequirement;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.visual.AiCallLog;
import com.tenderops.visual.AiCallLogRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * TC-AI-03/05/10 (live halves): the test-double Muse drives full extraction of the golden
 * tender (6 verified requirements, parsed metadata, model numerics ignored, bad quotes
 * flagged) with aiMode=LIVE; total live failure degrades to fallback extraction.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=TESTKEY-MUSE-LIVE-1",
    "tenderops.meta-api.max-attempts=2",
    "tenderops.meta-api.backoff-initial-ms=10",
    "tenderops.meta-api.request-timeout-ms=2000"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional
class ExtractionServiceLiveTest {

  private static HttpServer server;
  private static final Map<Integer, String> pageOutputs = new HashMap<>();
  private static final AtomicInteger hits = new AtomicInteger();
  private static volatile String overrideOutput;
  private static volatile Integer overrideStatus;

  static {
    try {
      for (int page = 1; page <= 6; page++) {
        pageOutputs.put(page, new String(
            new ClassPathResource("muse/v1-p" + page + ".json").getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8));
      }
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/responses", exchange -> {
        String body;
        try (var in = exchange.getRequestBody()) {
          body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        hits.incrementAndGet();
        int status = overrideStatus == null ? 200 : overrideStatus;
        String output = overrideOutput;
        if (output == null) {
          Matcher page = Pattern.compile("page=\\\\?\"(\\d+)\\\\?\"").matcher(body);
          output = page.find()
              ? pageOutputs.getOrDefault(Integer.parseInt(page.group(1)), "{\"tender\":{},\"requirements\":[]}")
              : "{\"tender\":{},\"requirements\":[]}";
        }
        byte[] payload;
        if (status == 200) {
          String json = "{\"output\":[{\"content\":[{\"text\":" + quoteJson(output)
              + "}]}]}";
          payload = json.getBytes(StandardCharsets.UTF_8);
        } else {
          payload = "{\"error\":\"double failure\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(payload);
        }
      });
      server.setExecutor(Executors.newCachedThreadPool());
      server.start();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot start Muse live double", e);
    }
  }

  private static String quoteJson(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\"";
  }

  @AfterAll
  static void stopDouble() {
    if (server != null) {
      server.stop(0);
    }
    overrideOutput = null;
    overrideStatus = null;
  }

  @DynamicPropertySource
  static void doubleUrl(DynamicPropertyRegistry registry) {
    registry.add("tenderops.meta-api.base-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @TempDir
  Path temp;

  @Autowired
  private ExtractionService extraction;

  @Autowired
  private PdfIngestionService ingestion;

  @Autowired
  private TenderPdfGenerator generator;

  @Autowired
  private CompanyRepository companies;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private AiCallLogRepository callLogs;

  @Autowired
  private com.tenderops.document.DocumentPageRepository pageRepository;

  @Autowired
  private com.tenderops.visual.VisualRegionRepository regionRepository;

  @Autowired
  private ObjectMapper objectMapper;

  private TenderVersion ingestV1() throws Exception {
    Long company = companies.save(new Company("Live Extract Co")).getId();
    generator.generateSamples(temp);
    byte[] pdf = Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE));
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(company, pdf, "Smart_City_RFP_v1.pdf");
    return versions.findById(ingest.versionId()).orElseThrow();
  }

  private TenderRequirement byRef(List<TenderRequirement> rows, String ref) {
    return rows.stream().filter(row -> row.getClauseRef().equals(ref)).findFirst()
        .orElseThrow(() -> new AssertionError("Missing clause " + ref));
  }

  @Test
  void liveDoubleExtractsGoldenTender() throws Exception {
    overrideOutput = null;
    overrideStatus = null;
    hits.set(0);
    TenderVersion version = ingestV1();

    ExtractionService.ExtractResult result = extraction.extractVersion(version);

    assertThat(result.museMode()).isEqualTo("LIVE");
    assertThat(result.requirements()).hasSize(6);
    assertThat(result.requirements()).allSatisfy(row ->
        assertThat(row.isQuoteVerified()).isTrue());
    assertThat(hits.get()).isEqualTo(6);

    TenderRequirement turnover = byRef(result.requirements(), "4.1");
    assertThat(turnover.getAmountValue()).isEqualByComparingTo(new BigDecimal("1000000000"));
    assertThat(turnover.getConfidence()).isEqualByComparingTo(new BigDecimal("0.92"));

    TenderRequirement projects = byRef(result.requirements(), "5.1");
    JsonNode tree = objectMapper.readTree(projects.getConditionTree());
    assertThat(tree.get("op").asText()).isEqualTo("OR");
    assertThat(tree.get("children").get(1).get("domainTags").toString())
        .isEqualTo("[\"HIGHWAY\",\"SMART_CITY\"]");

    TenderVersion reloaded = versions.findById(version.getId()).orElseThrow();
    assertThat(reloaded.getReference()).isEqualTo("SCCC/2026/RFP/001");
    assertThat(reloaded.getEstimatedValue())
        .isEqualByComparingTo(new BigDecimal("600000000"));
    assertThat(reloaded.getEmdAmount()).isEqualByComparingTo(new BigDecimal("12000000"));
    assertThat(reloaded.getTenderFee()).isEqualByComparingTo(new BigDecimal("50000"));
    assertThat(reloaded.getSubmissionDeadline())
        .isEqualTo(OffsetDateTime.parse("2026-12-15T17:00+05:30"));

    List<AiCallLog> logs = callLogs.findByComponentOrderById("MUSE");
    assertThat(logs).hasSize(6);
    assertThat(logs).allSatisfy(log -> {
      assertThat(log.getStatus()).isEqualTo("OK");
      assertThat(log.getAiMode()).isEqualTo("LIVE");
      assertThat(String.valueOf(log.getPurpose())).doesNotContain("TESTKEY");
    });
  }

  @Test
  void badQuotePersistsUnverified() throws Exception {
    overrideOutput = "{\"tender\": {}, \"requirements\": [{\"clauseRef\": \"4.1\","
        + " \"category\": \"FINANCIAL\", \"mandatory\": true, \"metric\":"
        + " \"AVG_ANNUAL_TURNOVER\", \"operator\": \"GTE\", \"amountText\": \"Rs 100 Cr\","
        + " \"lookbackYears\": 3, \"originalQuote\": \"gibberish xyz not on page\","
        + " \"confidence\": 0.9, \"amendmentAction\": \"NONE\"}]}";
    overrideStatus = null;
    TenderVersion version = ingestV1();
    try {
      ExtractionService.ExtractResult result = extraction.extractVersion(version);
      assertThat(result.museMode()).isEqualTo("LIVE");
      assertThat(result.requirements()).hasSize(6);
      assertThat(result.requirements()).allSatisfy(row -> {
        assertThat(row.isQuoteVerified()).isFalse();
        assertThat(row.getOriginalQuote()).isEmpty();
      });
    } finally {
      overrideOutput = null;
    }
  }

  @Test
  void visionSourcedQuoteStaysUnverified() throws Exception {
    TenderVersion version = ingestV1();
    com.tenderops.document.DocumentPage page =
        pageRepository.findByTenderVersionIdOrderByPageNo(version.getId()).get(2);
    com.tenderops.visual.VisualRegion region = regionRepository.save(
        new com.tenderops.visual.VisualRegion(page.getId(),
            com.tenderops.visual.VisualRegion.TABLE_REGION, new BigDecimal("0.100000"),
            new BigDecimal("0.100000"), new BigDecimal("0.900000"), new BigDecimal("0.200000"),
            new BigDecimal("0.6500"), com.tenderops.visual.VisualRegion.SOURCE_FALLBACK));
    region.setContentSource(com.tenderops.visual.VisualRegion.CONTENT_MUSE);
    regionRepository.save(region);
    overrideOutput = "{\"tender\": {}, \"requirements\": [{\"clauseRef\": \"4.1\","
        + " \"category\": \"FINANCIAL\", \"mandatory\": true, \"metric\":"
        + " \"AVG_ANNUAL_TURNOVER\", \"operator\": \"GTE\", \"amountText\": \"Rs 100 Cr\","
        + " \"lookbackYears\": 3, \"sourceRegionId\": " + region.getId() + ","
        + " \"originalQuote\": \"Clause 4.1 The average annual turnover of the bidder over"
        + " the last three audited financial years shall be not less than Rs 100 Cr.\","
        + " \"confidence\": 0.9, \"amendmentAction\": \"NONE\"}]}";
    overrideStatus = null;
    try {
      ExtractionService.ExtractResult result = extraction.extractVersion(version);
      assertThat(result.requirements()).hasSize(6);
      assertThat(result.requirements()).allSatisfy(row -> {
        assertThat(row.isQuoteVerified()).isFalse();
        assertThat(row.getOriginalQuote()).isEmpty();
        assertThat(row.getSourceRegionId()).isEqualTo(region.getId());
      });
    } finally {
      overrideOutput = null;
    }
  }

  @Test
  void bogusSourceRegionIdIsNulledPipelineContinues() throws Exception {
    TenderVersion version = ingestV1();
    overrideOutput = "{\"tender\": {}, \"requirements\": [{\"clauseRef\": \"4.1\","
        + " \"category\": \"FINANCIAL\", \"mandatory\": true, \"metric\":"
        + " \"AVG_ANNUAL_TURNOVER\", \"operator\": \"GTE\", \"amountText\": \"Rs 100 Cr\","
        + " \"lookbackYears\": 3, \"sourceRegionId\": 999999,"
        + " \"originalQuote\": \"Clause 4.1 The average annual turnover of the bidder over"
        + " the last three audited financial years shall be not less than Rs 100 Cr.\","
        + " \"confidence\": 0.9, \"amendmentAction\": \"NONE\"}]}";
    overrideStatus = null;
    try {
      ExtractionService.ExtractResult result = extraction.extractVersion(version);
      assertThat(result.museMode()).isEqualTo("LIVE");
      assertThat(result.requirements()).hasSize(6);
      assertThat(result.requirements()).allSatisfy(row ->
          assertThat(row.getSourceRegionId()).isNull());
      List<TenderRequirement> page3 = result.requirements().stream()
          .filter(row -> row.getSourcePage() == 3).toList();
      assertThat(page3).hasSize(1);
      assertThat(page3.get(0).isQuoteVerified()).isTrue();
    } finally {
      overrideOutput = null;
    }
  }

  @Test
  void totalLiveFailureDegradesToFallback() throws Exception {
    overrideOutput = null;
    overrideStatus = 500;
    TenderVersion version = ingestV1();
    try {
      ExtractionService.ExtractResult result = extraction.extractVersion(version);
      assertThat(result.museMode()).isEqualTo("FALLBACK");
      assertThat(result.requirements()).hasSize(6);
      assertThat(result.requirements().stream().map(TenderRequirement::getClauseRef).toList())
          .containsExactly("4.1", "4.2", "5.1", "6.1", "6.2", "8.1");
      List<AiCallLog> logs = callLogs.findByComponentOrderById("MUSE");
      assertThat(logs.stream().map(AiCallLog::getStatus).toList())
          .contains("HTTP_500", "FALLBACK_USED");
    } finally {
      overrideStatus = null;
    }
  }
}
