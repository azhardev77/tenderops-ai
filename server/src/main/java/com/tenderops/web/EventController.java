package com.tenderops.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderops.security.CurrentUser;
import com.tenderops.tender.Tender;
import com.tenderops.tender.TenderRepository;
import com.tenderops.tender.TenderVersion;
import com.tenderops.tender.TenderVersionRepository;
import java.time.OffsetDateTime;
import com.tenderops.workflow.EventTypes;
import com.tenderops.workflow.SseHub;
import com.tenderops.workflow.WorkflowEvent;
import com.tenderops.workflow.WorkflowEventRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live SSE streams plus polling equivalents. Same path serves both: presence of the
 * afterSeq query parameter selects JSON polling, otherwise a live stream with
 * Last-Event-ID replay. All endpoints are auth + company scoped.
 */
@RestController
@RequestMapping("/api/v1")
public class EventController {

  private final TenderRepository tenders;
  private final TenderVersionRepository versions;
  private final WorkflowEventRepository events;
  private final SseHub hub;
  private final ObjectMapper mapper;

  public EventController(TenderRepository tenders, TenderVersionRepository versions,
      WorkflowEventRepository events, SseHub hub, ObjectMapper mapper) {
    this.tenders = tenders;
    this.versions = versions;
    this.events = events;
    this.hub = hub;
    this.mapper = mapper;
  }

  public record EventDto(long seq, String type, String stage, Object payload,
      OffsetDateTime createdAt) {
  }

  @GetMapping(value = "/tender-versions/{id}/events", params = "afterSeq")
  public List<EventDto> pollVersion(@PathVariable long id,
      @RequestParam long afterSeq) {
    requireVersionAccess(id);
    List<EventDto> out = new ArrayList<>();
    for (WorkflowEvent row :
        events.findByTenderVersionIdAndSeqGreaterThanOrderBySeq(id, afterSeq)) {
      out.add(render(row));
      if (out.size() >= 1000) {
        break;
      }
    }
    return out;
  }

  @GetMapping(value = "/tender-versions/{id}/events", params = "!afterSeq")
  public SseEmitter streamVersion(@PathVariable long id,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    requireVersionAccess(id);
    return hub.subscribeVersionSse(id, parseSeq(lastEventId));
  }

  @GetMapping(value = "/dashboard/events", params = "afterSeq")
  public List<EventDto> pollDashboard(@RequestParam long afterSeq) {
    CurrentUser current = CurrentUser.require();
    List<EventDto> out = new ArrayList<>();
    for (WorkflowEvent row : events.findByCompanyIdAndSeqGreaterThanOrderBySeq(
        current.companyId(), afterSeq)) {
      if (row.getType().startsWith("JOB_")
          || EventTypes.DECISION_UPDATED.equals(row.getType())) {
        out.add(render(row));
        if (out.size() >= 1000) {
          break;
        }
      }
    }
    return out;
  }

  @GetMapping(value = "/dashboard/events", params = "!afterSeq")
  public SseEmitter streamDashboard(
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    CurrentUser current = CurrentUser.require();
    return hub.subscribeCompanySse(current.companyId(), parseSeq(lastEventId));
  }

  private TenderVersion requireVersionAccess(long versionId) {
    CurrentUser current = CurrentUser.require();
    TenderVersion version = versions.findById(versionId)
        .orElseThrow(() -> new NotFoundException("Version not found"));
    Tender tender = tenders.findById(version.getTenderId())
        .orElseThrow(() -> new NotFoundException("Tender not found"));
    current.requireCompany(tender.getCompanyId());
    return version;
  }

  private EventDto render(WorkflowEvent row) {
    return new EventDto(row.getSeq(), row.getType(), row.getStage(),
        parsePayload(row.getPayload()), row.getCreatedAt());
  }

  private Object parsePayload(String json) {
    try {
      return mapper.readValue(json, Object.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return json;
    }
  }

  private static long parseSeq(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
