package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.company.Company;
import com.tenderops.company.CompanyRepository;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event persistence + AFTER_COMMIT broadcast. A rolled-back publish emits nothing (TC-RT-04).
 * Non-transactional: each publish commits on its own so broadcasts fire inline.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "tenderops.jwt.secret=ApiTestDummySecret0123456789abcdefghij0123",
    "tenderops.meta-api.key="})
class EventServiceTest {

  @Autowired
  private EventService events;

  @Autowired
  private WorkflowEventRepository repository;

  @Autowired
  private CompanyRepository companies;

  @Autowired
  private TenderRepository tenders;

  @Autowired
  private TenderVersionRepository versions;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @SpyBean
  private SseHub hub;

  @Autowired
  private ObjectMapper objectMapper;

  private TenderVersion version() {
    Company company = companies.save(new Company("Event Co"));
    Tender tender = tenders.save(new Tender(company.getId(), "Event tender", null));
    return versions.save(new TenderVersion(tender.getId(), 1, TenderVersion.KIND_BASE,
        "evt" + "0".repeat(61), "events/x.pdf", 2));
  }

  @Test
  void publishPersistsAndBroadcastsAfterCommit() throws Exception {
    clearInvocations(hub);
    TenderVersion version = version();
    Tender tender = tenders.findById(version.getTenderId()).orElseThrow();

    WorkflowEvent row = events.publish(version.getId(), tender.getCompanyId(),
        "STAGE_STARTED", "VALIDATE", Map.of("jobId", 5));

    assertThat(row.getSeq()).isNotNull();
    assertThat(repository.findById(row.getSeq())).isPresent();
    assertThat(objectMapper.readTree(row.getPayload()).get("jobId").asInt()).isEqualTo(5);
    verify(hub, timeout(2000)).sendToVersion(anyLong(), any(WorkflowEvent.class));
  }

  @Test
  void rolledBackPublishEmitsNothing() {
    clearInvocations(hub);
    TenderVersion version = version();
    Tender tender = tenders.findById(version.getTenderId()).orElseThrow();
    TransactionTemplate template = new TransactionTemplate(transactionManager);

    template.execute(status -> {
      events.publish(version.getId(), tender.getCompanyId(), "STAGE_STARTED", "VALIDATE",
          Map.of("jobId", 6));
      status.setRollbackOnly();
      return null;
    });

    assertThat(repository.findByTenderVersionIdOrderBySeq(version.getId())).isEmpty();
    verify(hub, never()).sendToVersion(anyLong(), any(WorkflowEvent.class));
    verify(hub, never()).sendToCompany(anyLong(), any(WorkflowEvent.class));
  }
}
