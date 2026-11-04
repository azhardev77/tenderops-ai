package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.tenderops.company.Company;
import com.tenderops.company.CompanyRepository;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import com.tenderops.web.dto.AuthDtos.AuthResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** GET /jobs/{id}: auth required, company-scoped, progress visible. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class JobApiTest {

  @Autowired
  private TestRestTemplate rest;

  @Autowired
  private CompanyRepository companies;

  @Autowired
  private TenderRepository tenders;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private WorkflowJobRepository jobs;

  private AuthResponse register(String prefix) {
    Map<String, String> body = Map.of("email", prefix + UUID.randomUUID() + "@x.local",
        "password", "password-123", "companyName", prefix + " Co");
    ResponseEntity<AuthResponse> response =
        rest.postForEntity("/api/v1/auth/register", body, AuthResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    return response.getBody();
  }

  private long jobFor(long companyId) {
    Tender tender = tenders.save(new Tender(companyId, "Job tender", null));
    String sha = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
    TenderVersion version = versions.save(new TenderVersion(tender.getId(), 1,
        TenderVersion.KIND_BASE, sha, "jobapi/x.pdf", 1));
    WorkflowJob job = new WorkflowJob(version.getId(), companyId,
        WorkflowService.KIND_FULL, "RUNNING");
    job.setCurrentStage("VISUAL");
    return jobs.save(job).getId();
  }

  private ResponseEntity<Map> get(String url, String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
  }

  @Test
  void requiresAuth() {
    AuthResponse owner = register("jobauth-");
    long jobId = jobFor(owner.user().companyId());

    assertThat(get("/api/v1/jobs/" + jobId, null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void crossCompanyReturns404() {
    AuthResponse owner = register("jobown-");
    AuthResponse stranger = register("jobstr-");
    long jobId = jobFor(owner.user().companyId());

    assertThat(get("/api/v1/jobs/" + jobId, stranger.accessToken()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void ownerReadsJob() {
    AuthResponse owner = register("jobread-");
    long jobId = jobFor(owner.user().companyId());

    ResponseEntity<Map> response = get("/api/v1/jobs/" + jobId, owner.accessToken());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("status")).isEqualTo("RUNNING");
    assertThat(response.getBody().get("currentStage")).isEqualTo("VISUAL");
  }
}
