package com.tenderops.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live SSE subscriber registry (per version + per company). Replay rows are delivered
 * before buffered live events, in order; dead emitters are removed on send failure,
 * completion, timeout or error.
 */
@Component
public class SseHub {

  public interface EmitterHandle {
    void send(long seq, String type, String payloadJson) throws Exception;

    void complete();

    void onTerminal(Runnable cleanup);

    default void ping() throws Exception {
    }
  }

  /** One subscriber: buffers live events until its replay finishes. */
  public static class Slot {
    private final EmitterHandle handle;
    private final Runnable remove;
    private final List<WorkflowEvent> buffer = new ArrayList<>();
    private boolean replaying = true;
    private long maxReplaySeq = -1;

    Slot(EmitterHandle handle, Runnable remove) {
      this.handle = handle;
      this.remove = remove;
      handle.onTerminal(remove);
    }

    public synchronized void deliver(WorkflowEvent event) {
      sendNow(event);
      if (event.getSeq() != null && event.getSeq() > maxReplaySeq) {
        maxReplaySeq = event.getSeq();
      }
    }

    synchronized void emit(WorkflowEvent event) {
      if (replaying) {
        buffer.add(event);
      } else {
        sendNow(event);
      }
    }

    public synchronized void finishReplay() {
      replaying = false;
      for (WorkflowEvent event : buffer) {
        if (event.getSeq() == null || event.getSeq() > maxReplaySeq) {
          sendNow(event);
        }
      }
      buffer.clear();
    }

    synchronized void heartbeat() {
      try {
        handle.ping();
      } catch (Exception e) {
        remove.run();
      }
    }

    private void sendNow(WorkflowEvent event) {
      try {
        handle.send(event.getSeq() == null ? -1 : event.getSeq(), event.getType(),
            event.getPayload());
      } catch (Exception e) {
        remove.run();
        try {
          handle.complete();
        } catch (Exception ignored) {
          // Removal is what matters; the emitter is already dead.
        }
      }
    }
  }

  static class SseEmitterHandle implements EmitterHandle {
    private final SseEmitter emitter;

    SseEmitterHandle(SseEmitter emitter) {
      this.emitter = emitter;
    }

    @Override
    public void send(long seq, String type, String payloadJson) throws Exception {
      emitter.send(SseEmitter.event().id(String.valueOf(seq)).name(type).data(payloadJson,
          MediaType.APPLICATION_JSON));
    }

    @Override
    public void complete() {
      emitter.complete();
    }

    @Override
    public void onTerminal(Runnable cleanup) {
      emitter.onCompletion(cleanup);
      emitter.onTimeout(cleanup);
      emitter.onError(error -> cleanup.run());
    }

    @Override
    public void ping() throws Exception {
      emitter.send(SseEmitter.event().comment("ping"));
    }
  }

  private final WorkflowEventRepository events;
  private final Map<Long, Set<Slot>> versionSlots = new ConcurrentHashMap<>();
  private final Map<Long, Set<Slot>> companySlots = new ConcurrentHashMap<>();

  public SseHub(WorkflowEventRepository events) {
    this.events = events;
  }

  public Slot registerVersion(long versionId, EmitterHandle handle) {
    return register(versionSlots, versionId, handle);
  }

  public Slot registerCompany(long companyId, EmitterHandle handle) {
    return register(companySlots, companyId, handle);
  }

  private Slot register(Map<Long, Set<Slot>> registry, long key, EmitterHandle handle) {
    Set<Slot> slots = registry.computeIfAbsent(key, id -> ConcurrentHashMap.newKeySet());
    Slot[] ref = new Slot[1];
    Slot slot = new Slot(handle, () -> removeSlot(registry, key, ref[0]));
    ref[0] = slot;
    slots.add(slot);
    return slot;
  }

  private void removeSlot(Map<Long, Set<Slot>> registry, long key, Slot slot) {
    Set<Slot> slots = registry.get(key);
    if (slots != null) {
      slots.remove(slot);
      if (slots.isEmpty()) {
        registry.remove(key);
      }
    }
  }

  public void sendToVersion(long versionId, WorkflowEvent event) {
    sendAll(versionSlots.get(versionId), event);
  }

  public void sendToCompany(long companyId, WorkflowEvent event) {
    sendAll(companySlots.get(companyId), event);
  }

  private void sendAll(Set<Slot> slots, WorkflowEvent event) {
    if (slots == null || slots.isEmpty()) {
      return;
    }
    for (Slot slot : new ArrayList<>(slots)) {
      slot.emit(event);
    }
  }

  public int liveEmitters() {
    int count = 0;
    for (Set<Slot> slots : versionSlots.values()) {
      count += slots.size();
    }
    for (Set<Slot> slots : companySlots.values()) {
      count += slots.size();
    }
    return count;
  }

  /** Production subscribe: registers, replays seq > afterSeq, then goes live. */
  public SseEmitter subscribeVersionSse(long versionId, long afterSeq) {
    SseEmitter emitter = new SseEmitter(120_000L);
    Slot slot = registerVersion(versionId, new SseEmitterHandle(emitter));
    List<WorkflowEvent> replay =
        events.findByTenderVersionIdAndSeqGreaterThanOrderBySeq(versionId, afterSeq);
    for (WorkflowEvent row : replay) {
      slot.deliver(row);
    }
    slot.finishReplay();
    return emitter;
  }

  /** Company channel: JOB_* and DECISION_UPDATED only (filtered at broadcast). */
  public SseEmitter subscribeCompanySse(long companyId, long afterSeq) {
    SseEmitter emitter = new SseEmitter(120_000L);
    Slot slot = registerCompany(companyId, new SseEmitterHandle(emitter));
    List<WorkflowEvent> replay =
        events.findByCompanyIdAndSeqGreaterThanOrderBySeq(companyId, afterSeq);
    for (WorkflowEvent row : replay) {
      if (row.getType().startsWith("JOB_")
          || EventTypes.DECISION_UPDATED.equals(row.getType())) {
        slot.deliver(row);
      }
    }
    slot.finishReplay();
    return emitter;
  }

  @Scheduled(fixedRate = 15000)
  public void heartbeat() {
    for (Set<Slot> slots : versionSlots.values()) {
      for (Slot slot : new ArrayList<>(slots)) {
        slot.heartbeat();
      }
    }
    for (Set<Slot> slots : companySlots.values()) {
      for (Slot slot : new ArrayList<>(slots)) {
        slot.heartbeat();
      }
    }
  }
}
