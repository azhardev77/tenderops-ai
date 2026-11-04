package com.tenderops.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event store writer: rows persist in the SAME transaction as the state change they
 * describe, and broadcast to live subscribers ONLY after commit (R17). A rolled-back
 * transaction therefore emits nothing.
 */
@Service
public class EventService {

  public record EventCommitted(WorkflowEvent event) {
  }

  private final WorkflowEventRepository repository;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher publisher;
  private final SseHub hub;

  public EventService(WorkflowEventRepository repository, ObjectMapper mapper,
      ApplicationEventPublisher publisher, SseHub hub) {
    this.repository = repository;
    this.mapper = mapper;
    this.publisher = publisher;
    this.hub = hub;
  }

  @Transactional
  public WorkflowEvent publish(long versionId, long companyId, String type, String stage,
      Map<String, Object> payload) {
    String json;
    try {
      json = mapper.writeValueAsString(payload == null ? Map.of() : payload);
    } catch (JsonProcessingException e) {
      json = "{}";
    }
    WorkflowEvent row =
        repository.save(new WorkflowEvent(versionId, companyId, type, stage, json));
    publisher.publishEvent(new EventCommitted(row));
    return row;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onCommit(EventCommitted committed) {
    WorkflowEvent event = committed.event();
    hub.sendToVersion(event.getTenderVersionId(), event);
    if (event.getType().startsWith("JOB_")
        || EventTypes.DECISION_UPDATED.equals(event.getType())) {
      hub.sendToCompany(event.getCompanyId(), event);
    }
  }
}
