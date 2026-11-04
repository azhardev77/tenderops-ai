package com.tenderops.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SseHub routing, ordered replay-before-live delivery, and dead-emitter cleanup. */
class SseHubTest {

  static class FakeEmitter implements SseHub.EmitterHandle {
    final List<String> received = new ArrayList<>();
    boolean dead;
    Runnable cleanup;

    @Override
    public void send(long seq, String type, String payloadJson) {
      if (dead) {
        throw new RuntimeException("broken pipe");
      }
      received.add(payloadJson);
    }

    @Override
    public void complete() {
    }

    @Override
    public void onTerminal(Runnable cleanup) {
      this.cleanup = cleanup;
    }
  }

  private static WorkflowEvent event(String payload) {
    WorkflowEvent row = new WorkflowEvent(7L, 9L, "STAGE_STARTED", "VALIDATE", payload);
    return row;
  }

  @Test
  void versionSubscribeReceivesBroadcasts() {
    SseHub hub = new SseHub(null);
    FakeEmitter emitter = new FakeEmitter();

    SseHub.Slot slot = hub.registerVersion(7L, emitter);
    slot.finishReplay();
    hub.sendToVersion(7L, event("p1"));
    hub.sendToVersion(8L, event("other"));

    assertThat(emitter.received).containsExactly("p1");
    assertThat(hub.liveEmitters()).isEqualTo(1);
  }

  @Test
  void replayDeliversBeforeLiveInOrder() {
    SseHub hub = new SseHub(null);
    FakeEmitter emitter = new FakeEmitter();

    SseHub.Slot slot = hub.registerVersion(7L, emitter);
    hub.sendToVersion(7L, event("live3"));
    slot.deliver(event("replay1"));
    slot.deliver(event("replay2"));
    slot.finishReplay();
    hub.sendToVersion(7L, event("live4"));

    assertThat(emitter.received)
        .containsExactly("replay1", "replay2", "live3", "live4");
  }

  @Test
  void deadEmitterIsRemoved() {
    SseHub hub = new SseHub(null);
    FakeEmitter emitter = new FakeEmitter();

    SseHub.Slot slot = hub.registerVersion(7L, emitter);
    slot.finishReplay();
    emitter.dead = true;
    hub.sendToVersion(7L, event("p1"));

    assertThat(hub.liveEmitters()).isEqualTo(0);
  }

  @Test
  void terminalCallbackRemovesEmitter() {
    SseHub hub = new SseHub(null);
    FakeEmitter emitter = new FakeEmitter();

    SseHub.Slot slot = hub.registerVersion(7L, emitter);
    slot.finishReplay();
    assertThat(hub.liveEmitters()).isEqualTo(1);
    emitter.cleanup.run();

    assertThat(hub.liveEmitters()).isEqualTo(0);
  }

  @Test
  void companyChannelIsSeparate() {
    SseHub hub = new SseHub(null);
    FakeEmitter emitter = new FakeEmitter();

    SseHub.Slot slot = hub.registerCompany(9L, emitter);
    slot.finishReplay();
    hub.sendToCompany(9L, event("c1"));
    hub.sendToVersion(7L, event("v1"));

    assertThat(emitter.received).containsExactly("c1");
  }
}
