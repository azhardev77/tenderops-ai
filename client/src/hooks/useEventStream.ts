import { useEffect, useRef, useState } from 'react';
import { authHeaders, redirectToLogin } from '../api/client';
import type { StreamEvent } from '../api/types';

export type ConnectionState = 'STREAMING' | 'POLLING' | 'DISCONNECTED';

interface StreamOptions {
  /** SSE endpoint, e.g. /api/v1/tender-versions/7/events */
  url: string | null;
  /** Polling equivalent base, e.g. /api/v1/tender-versions/7/events (we add ?afterSeq=N) */
  pollUrl: string | null;
  enabled?: boolean;
  onEvent?: (event: StreamEvent) => void;
  /** Test hook: poll cadence (default 2000 ms). */
  pollIntervalMs?: number;
  /** Test hook: base reconnect backoff (default 500 ms). */
  reconnectBaseMs?: number;
}

interface ParsedEvent {
  id: string;
  name: string;
  data: string;
}

/** Minimal SSE block parser: id:/event:/data:, heartbeat comments skipped. */
export function parseSseBlock(block: string): ParsedEvent | null {
  let id = '';
  let name = '';
  let data = '';
  let seen = false;
  for (const raw of block.split('\n')) {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
    if (line === '' || line.startsWith(':')) {
      continue;
    }
    seen = true;
    if (line.startsWith('id:')) {
      id = line.slice(3).trim();
    } else if (line.startsWith('event:')) {
      name = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data = data === '' ? line.slice(5).trim() : data + '\n' + line.slice(5).trim();
    }
  }
  if (!seen) {
    return null;
  }
  return { id, name, data };
}

/**
 * fetch()-based SSE reader (ReadableStream parser): sends the auth header (which
 * EventSource cannot), reconnects with Last-Event-ID, dedupes by seq, and falls back
 * to polling ?afterSeq=N after 3 failed reconnects (TC-RT-09/10/11).
 */
export function useEventStream({
  url,
  pollUrl,
  enabled = true,
  onEvent,
  pollIntervalMs = 2000,
  reconnectBaseMs = 500,
}: StreamOptions): { state: ConnectionState; lastSeq: number | null } {
  const [state, setState] = useState<ConnectionState>('DISCONNECTED');
  const [lastSeq, setLastSeq] = useState<number | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;
  const maxSeqRef = useRef<number | null>(null);

  useEffect(() => {
    if (!enabled || !url) {
      setState('DISCONNECTED');
      return;
    }
    let cancelled = false;
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let pollTimer: ReturnType<typeof setInterval> | null = null;
    const aborters = new Set<AbortController>();

    const emit = (event: StreamEvent) => {
      if (maxSeqRef.current !== null && event.seq <= maxSeqRef.current) {
        return;
      }
      maxSeqRef.current = event.seq;
      setLastSeq(event.seq);
      onEventRef.current?.(event);
    };

    const pollOnce = async () => {
      if (cancelled || !pollUrl) {
        return;
      }
      const after = maxSeqRef.current ?? 0;
      try {
        const response = await fetch(`${pollUrl}?afterSeq=${after}`, {
          headers: authHeaders(),
        });
        if (cancelled) {
          return;
        }
        if (response.status === 401) {
          redirectToLogin();
          return;
        }
        if (!response.ok) {
          return;
        }
        const body = (await response.json()) as StreamEvent[];
        for (const event of body ?? []) {
          emit(event);
        }
      } catch {
        // polling retries on its own cadence
      }
    };

    const startPolling = () => {
      if (cancelled || pollTimer) {
        return;
      }
      setState('POLLING');
      void pollOnce();
      pollTimer = setInterval(() => void pollOnce(), pollIntervalMs);
    };

    const connect = async () => {
      if (cancelled) {
        return;
      }
      const controller = new AbortController();
      aborters.add(controller);
      try {
        const headers: Record<string, string> = {
          Accept: 'text/event-stream',
          ...authHeaders(),
        };
        if (maxSeqRef.current !== null) {
          headers['Last-Event-ID'] = String(maxSeqRef.current);
        }
        const response = await fetch(url, { headers, signal: controller.signal });
        if (cancelled) {
          return;
        }
        if (response.status === 401) {
          redirectToLogin();
          return;
        }
        if (!response.ok || !response.body) {
          throw new Error('stream failed with status ' + response.status);
        }
        failures = 0;
        setState('STREAMING');
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (cancelled) {
            try {
              reader.releaseLock();
            } catch {
              // ignore
            }
            return;
          }
          if (done) {
            break;
          }
          buffer += decoder.decode(value, { stream: true });
          let boundary = buffer.indexOf('\n\n');
          while (boundary >= 0) {
            const block = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const parsed = parseSseBlock(block);
            if (parsed && parsed.name !== '') {
              const seq = Number.parseInt(parsed.id, 10);
              if (Number.isFinite(seq)) {
                let payload: Record<string, unknown> = {};
                try {
                  payload = JSON.parse(parsed.data) as Record<string, unknown>;
                } catch {
                  // unparseable payload rides along empty
                }
                const stage =
                  typeof payload['stage'] === 'string'
                    ? (payload['stage'] as string)
                    : null;
                emit({ seq, type: parsed.name, stage, payload, createdAt: '' });
              }
            }
            boundary = buffer.indexOf('\n\n');
          }
        }
        throw new Error('stream ended');
      } catch (error) {
        if (cancelled || (error instanceof DOMException && error.name === 'AbortError')) {
          return;
        }
        failures += 1;
        if (failures > 3) {
          startPolling();
          return;
        }
        setState('DISCONNECTED');
        timer = setTimeout(
          () => void connect(),
          Math.min(reconnectBaseMs * failures, 5000),
        );
      } finally {
        aborters.delete(controller);
      }
    };

    void connect();
    return () => {
      cancelled = true;
      if (timer) {
        clearTimeout(timer);
      }
      if (pollTimer) {
        clearInterval(pollTimer);
      }
      for (const aborter of aborters) {
        aborter.abort();
      }
    };
    // pollUrl/options intentionally fixed per mount; url/enabled drive reconnects.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, enabled]);

  return { state, lastSeq };
}
