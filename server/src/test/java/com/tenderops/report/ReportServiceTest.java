package com.tenderops.report;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.tenderops.runs.SummaryService;
import com.tenderops.security.UserRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * TC-RPT-01: the bid-package PDF starts with %PDF-, has >= 2 pages and carries every
 * clause ref, verdict and risk; the XLSX opens with POI and holds all matrix and
 * checklist rows. Downloads are company-scoped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key=",
    "spring.datasource.url=jdbc:h2:mem:tenderopsrpt;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportServiceTest {

  @LocalServerPort
  int port;

  private long companyId;
  private long versionId;
  private String demoToken;

  @Autowired
  private ReportDataService assembler;

  @Autowired
  private PdfReportService pdf;

  @Autowired
  private XlsxReportService xlsx;

  @Autowired
  private TenderVersionRepository versions;

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
  private SummaryService summaries;

  private static final Clock GOLDEN_CLOCK = Clock.fixed(
      ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant(),
      ZoneId.of("Asia/Kolkata"));

  private String base() {
    return "http://localhost:" + port;
  }

  @BeforeAll
  void seedAndProcess() throws Exception {
    Path temp = Files.createTempDirectory("rpt-seed");
    DemoDataSeeder seeder = new DemoDataSeeder(users, companies, financials, projects,
        certificates, documents, storage, generator, passwordEncoder, auditService,
        properties, environment, "report-test-password-1");
    companyId = seeder.seed(temp).companyId();
    TestRestTemplate rest = new TestRestTemplate();
    ResponseEntity<AuthResponse> login = rest.postForEntity(base() + "/api/v1/auth/login",
        Map.of("email", DemoDataSeeder.DEMO_EMAIL, "password", "report-test-password-1"),
        AuthResponse.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    demoToken = login.getBody().accessToken();

    generator.generateSamples(temp);
    byte[] pdf = Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE));
    PdfIngestionService.IngestResult ingest =
        ingestion.ingestNewTender(companyId, pdf, TenderPdfGenerator.V1_FILE);
    versionId = ingest.versionId();
    TenderVersion version = versions.findById(versionId).orElseThrow();
    ExtractionService.ExtractResult extracted = extraction.extractVersion(version);
    ComplianceOutcome outcome = compliance.evaluate(versionId, companyId, GOLDEN_CLOCK);
    long runId = runService.persistRun(versionId, companyId, outcome, extracted.museMode(),
        "FALLBACK");
    summaries.generateAndAttach(runId);
  }

  @Test
  void pdfCarriesEveryClauseVerdictAndRisk() throws Exception {
    ReportDataService.ReportData data = assembler.assemble(versionId, null, companyId);
    assertThat(data.matrix()).hasSize(6);
    byte[] bytes = pdf.bidPackage(data);

    assertThat(new String(bytes, 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
    String text;
    int pages;
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      pages = doc.getNumberOfPages();
      text = new PDFTextStripper().getText(doc);
    }
    assertThat(pages).isGreaterThanOrEqualTo(2);
    assertThat(text).contains("SCCC/2026/RFP/001", "NO_BID", "40.00");
    for (String clause : new String[] {"4.1", "4.2", "5.1", "6.1", "6.2", "8.1"}) {
      assertThat(text).contains(clause);
    }
    assertThat(text).contains("PASS", "FAIL", "UNKNOWN");
    assertThat(text).contains("MANDATORY_REQUIREMENT_FAILED", "MISSING_DOCUMENTATION");
    assertThat(text).contains("Solvency");
    assertThat(text.toLowerCase()).doesNotContain("probability", "chance of winning");
  }

  @Test
  void xlsxHoldsMatrixAndChecklistRows() throws Exception {
    ReportDataService.ReportData data = assembler.assemble(versionId, null, companyId);
    byte[] bytes = xlsx.checklistWorkbook(data);

    try (Workbook workbook = new XSSFWorkbook(
        new java.io.ByteArrayInputStream(bytes))) {
      Sheet matrix = workbook.getSheet("Matrix");
      assertThat(matrix).isNotNull();
      assertThat(matrix.getLastRowNum()).isEqualTo(6);
      assertThat(matrix.getRow(1).getCell(0).getStringCellValue()).isEqualTo("4.1");
      assertThat(matrix.getRow(3).getCell(3).getStringCellValue()).isEqualTo("FAIL");
      Sheet checklist = workbook.getSheet("Checklist");
      assertThat(checklist).isNotNull();
      assertThat(checklist.getLastRowNum()).isEqualTo(1);
      assertThat(checklist.getRow(1).getCell(0).getStringCellValue()).isEqualTo("6.2");
      Sheet run = workbook.getSheet("Run");
      assertThat(run.getRow(3).getCell(1).getStringCellValue()).isEqualTo("NO_BID");
    }
  }

  @Test
  void downloadsAreScoped() {
    TestRestTemplate rest = new TestRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(demoToken);
    ResponseEntity<byte[]> pdfResponse = rest.exchange(base() + "/api/v1/tender-versions/"
        + versionId + "/reports/bid-package.pdf", HttpMethod.GET,
        new HttpEntity<>(headers), byte[].class);
    assertThat(pdfResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pdfResponse.getHeaders().getContentType().toString()).contains("pdf");
    assertThat(pdfResponse.getBody()).isNotEmpty();

    ResponseEntity<byte[]> xlsxResponse = rest.exchange(base() + "/api/v1/tender-versions/"
        + versionId + "/reports/checklist.xlsx", HttpMethod.GET,
        new HttpEntity<>(headers), byte[].class);
    assertThat(xlsxResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(xlsxResponse.getHeaders().getContentType().toString()).contains(
        "spreadsheetml");
    assertThat(xlsxResponse.getBody()).isNotEmpty();

    ResponseEntity<AuthResponse> registered = rest.postForEntity(
        base() + "/api/v1/auth/register",
        Map.of("email", "rptstr-" + UUID.randomUUID() + "@x.local", "password",
            "password-123", "companyName", "Stranger Co"),
        AuthResponse.class);
    HttpHeaders stranger = new HttpHeaders();
    stranger.setBearerAuth(registered.getBody().accessToken());
    ResponseEntity<Map> cross = rest.exchange(base() + "/api/v1/tender-versions/"
        + versionId + "/reports/bid-package.pdf", HttpMethod.GET,
        new HttpEntity<>(stranger), Map.class);
    assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
