package com.tenderops.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.demo.TenderPdfGenerator;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Tender upload + reads: multipart ingest starts the FULL pipeline; list/detail/version/
 * pages/image/text/regions/crop/requirements all serve the processed tender, company-scoped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class TenderApiTest {

  @TempDir
  Path temp;

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private TenderPdfGenerator generator;

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
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return headers;
  }

  private byte[] v1Bytes() throws Exception {
    generator.generateSamples(temp);
    return Files.readAllBytes(temp.resolve(TenderPdfGenerator.V1_FILE));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> upload(String token, byte[] pdf, String fileName) {
    ByteArrayResource resource = new ByteArrayResource(pdf) {
      @Override
      public String getFilename() {
        return fileName;
      }
    };
    MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
    multipart.add("file", resource);
    HttpHeaders headers = auth(token);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<Map> response = rest.exchange("/api/v1/tenders", HttpMethod.POST,
        new HttpEntity<>(multipart, headers), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  @SuppressWarnings("unchecked")
  private void awaitJob(String token, long jobId) {
    long deadline = System.currentTimeMillis() + 180_000;
    while (true) {
      ResponseEntity<Map> response = rest.exchange("/api/v1/jobs/" + jobId, HttpMethod.GET,
          new HttpEntity<>(auth(token)), Map.class);
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
  private Map<String, Object> get(String url, String token) {
    ResponseEntity<Map> response =
        rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  @Test
  @SuppressWarnings("unchecked")
  void uploadRunsPipelineAndServesReads() throws Exception {
    AuthResponse owner = register("tenfull-");
    Map<String, Object> done = upload(owner.accessToken(), v1Bytes(), "Smart_City_RFP_v1.pdf");
    assertThat(done.get("duplicate")).isEqualTo(false);
    long versionId = ((Number) done.get("versionId")).longValue();
    long tenderId = ((Number) done.get("tenderId")).longValue();
    awaitJob(owner.accessToken(), ((Number) done.get("jobId")).longValue());

    Map<String, Object> list = get("/api/v1/tenders", owner.accessToken());
    assertThat(((Number) list.get("total")).longValue()).isEqualTo(1L);
    Map<String, Object> item = (Map<String, Object>) ((List<Object>) list.get("items")).get(0);
    assertThat(item.get("latestVersion")).isEqualTo(1);
    assertThat(item.get("latestDecision")).isEqualTo("INSUFFICIENT_DATA");

    Map<String, Object> detail = get("/api/v1/tenders/" + tenderId, owner.accessToken());
    List<Object> versionDtos = (List<Object>) detail.get("versions");
    assertThat(versionDtos).hasSize(1);
    Map<String, Object> version = (Map<String, Object>) versionDtos.get(0);
    assertThat(version.get("reference")).isEqualTo("SCCC/2026/RFP/001");
    assertThat(version.get("estimatedValue")).isEqualTo("600000000.0000");
    assertThat(version.get("emdAmount")).isEqualTo("12000000.0000");
    assertThat(version.get("tenderFee")).isEqualTo("50000.0000");
    assertThat((String) version.get("submissionDeadline")).contains("2026-12-15");
    Map<String, Object> latestRun = (Map<String, Object>) detail.get("latestRun");
    assertThat(latestRun.get("decision")).isEqualTo("INSUFFICIENT_DATA");
    assertThat(latestRun.get("readinessScore")).isEqualTo("0.00");
    assertThat(latestRun.get("museMode")).isEqualTo("FALLBACK");
    assertThat(latestRun.get("samMode")).isEqualTo("FALLBACK");

    ResponseEntity<List> pages = rest.exchange(
        "/api/v1/tender-versions/" + versionId + "/pages", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), List.class);
    assertThat(pages.getBody()).hasSize(6);
    assertThat(((Map<String, Object>) pages.getBody().get(0)).get("classification"))
        .isEqualTo("DIGITAL_TEXT");

    Map<String, Object> text =
        get("/api/v1/tender-versions/" + versionId + "/pages/3/text", owner.accessToken());
    assertThat((String) text.get("text")).contains("Clause 4.1");

    ResponseEntity<byte[]> image = rest.exchange(
        "/api/v1/tender-versions/" + versionId + "/pages/1/image", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), byte[].class);
    assertThat(image.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(image.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(image.getBody()).isNotNull();
    assertThat(image.getBody()[1]).isEqualTo((byte) 'P');

    ResponseEntity<List> regions = rest.exchange(
        "/api/v1/tender-versions/" + versionId + "/regions", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), List.class);
    assertThat(regions.getBody()).isNotEmpty();
    Map<String, Object> table = null;
    for (Object region : regions.getBody()) {
      Map<String, Object> row = (Map<String, Object>) region;
      if ("TABLE_REGION".equals(row.get("regionType"))) {
        table = row;
      }
      assertThat(row.get("source")).isEqualTo("FALLBACK_HEURISTIC");
    }
    assertThat(table).isNotNull();
    assertThat(table.get("contentSource")).isEqualTo("PDFBOX_GEOMETRIC");
    Map<String, Object> structured = (Map<String, Object>) table.get("structuredTable");
    List<Object> rows = (List<Object>) structured.get("rows");
    assertThat(rows).hasSize(4);
    assertThat((List<Object>) rows.get(0)).containsExactly("Financial Year", "Period");
    assertThat(((Map<String, Object>) table.get("bbox")).get("x0")).isNotNull();

    ResponseEntity<byte[]> crop = rest.exchange((String) table.get("cropUrl"),
        HttpMethod.GET, new HttpEntity<>(auth(owner.accessToken())), byte[].class);
    assertThat(crop.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(crop.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);

    ResponseEntity<List> requirements = rest.exchange(
        "/api/v1/tender-versions/" + versionId + "/requirements", HttpMethod.GET,
        new HttpEntity<>(auth(owner.accessToken())), List.class);
    assertThat(requirements.getBody()).hasSize(6);
    assertThat(((Map<String, Object>) requirements.getBody().get(0)).get("clauseRef"))
        .isEqualTo("4.1");
  }

  @Test
  void duplicateUploadReturnsExisting() throws Exception {
    AuthResponse owner = register("tendup-");
    byte[] pdf = v1Bytes();
    Map<String, Object> first = upload(owner.accessToken(), pdf, "Smart_City_RFP_v1.pdf");
    awaitJob(owner.accessToken(), ((Number) first.get("jobId")).longValue());

    ByteArrayResource resource = new ByteArrayResource(pdf) {
      @Override
      public String getFilename() {
        return "Smart_City_RFP_v1.pdf";
      }
    };
    MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
    multipart.add("file", resource);
    HttpHeaders headers = auth(owner.accessToken());
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<Map> second = rest.exchange("/api/v1/tenders", HttpMethod.POST,
        new HttpEntity<>(multipart, headers), Map.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody().get("duplicate")).isEqualTo(true);
    assertThat(second.getBody().get("versionId")).isEqualTo(first.get("versionId"));
    assertThat(second.getBody().get("jobId")).isNull();
  }

  @Test
  void nonPdfUploadRejected() {
    AuthResponse owner = register("tenbad-");
    ByteArrayResource resource = new ByteArrayResource("not a pdf".getBytes()) {
      @Override
      public String getFilename() {
        return "evil.txt";
      }
    };
    MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
    multipart.add("file", resource);
    HttpHeaders headers = auth(owner.accessToken());
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<Map> response = rest.exchange("/api/v1/tenders", HttpMethod.POST,
        new HttpEntity<>(multipart, headers), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void readsAreScopedAndAuthenticated() throws Exception {
    AuthResponse owner = register("tenown-");
    AuthResponse stranger = register("tenstr-");
    Map<String, Object> done = upload(owner.accessToken(), v1Bytes(), "t.pdf");
    long versionId = ((Number) done.get("versionId")).longValue();
    long tenderId = ((Number) done.get("tenderId")).longValue();

    ResponseEntity<Map> cross = rest.exchange("/api/v1/tenders/" + tenderId,
        HttpMethod.GET, new HttpEntity<>(auth(stranger.accessToken())), Map.class);
    assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<Map> crossVersion = rest.exchange("/api/v1/tender-versions/" + versionId,
        HttpMethod.GET, new HttpEntity<>(auth(stranger.accessToken())), Map.class);
    assertThat(crossVersion.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<Map> anon = rest.exchange("/api/v1/tenders/" + tenderId, HttpMethod.GET,
        new HttpEntity<>(auth(null)), Map.class);
    assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    awaitJob(owner.accessToken(), ((Number) done.get("jobId")).longValue());
  }
}
