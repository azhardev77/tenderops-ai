import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { StreamEvent } from '../api/types';
import { parseSseBlock, useEventStream } from '../hooks/useEventStream';

function frame(seq: number, type: string, payload: Record<string, unknown>): string {
  return `id: ${seq}\nevent: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;
}

/** Mock SSE response: emits frames, then either hangs (open stream) or fails. */
function sseResponse(frames: string[], mode: 'hang' | 'end' = 'hang') {
  let index = 0;
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: async (): Promise<{ done: boolean; value?: Uint8Array }> => {
          if (index < frames.length) {
            return { done: false, value: new TextEncoder().encode(frames[index++]) };
          }
          if (mode === 'end') {
            return { done: true, value: undefined };
          }
          return new Promise(() => {
            // open stream: never resolves; unmount abandons it
          });
        },
        releaseLock: () => {},
      }),
    },
  };
}

function Harness({
  onEvent,
  pollIntervalMs = 20,
}: {
  onEvent: (event: StreamEvent) => void;
  pollIntervalMs?: number;
}) {
  const { state, lastSeq } = useEventStream({
    url: '/stream',
    pollUrl: '/poll',
    onEvent,
    reconnectBaseMs: 5,
    pollIntervalMs,
  });
  return (
    <p data-testid="conn">
      {state}:{lastSeq === null ? 'none' : lastSeq}
    </p>
  );
}

describe('parseSseBlock', () => {
  it('parses id/event/data and skips heartbeats', () => {
    expect(parseSseBlock(': heartbeat')).toBeNull();
    expect(parseSseBlock('')).toBeNull();
    const parsed = parseSseBlock('id: 7\nevent: JOB_COMPLETED\ndata: {"ok":true}');
    expect(parsed).toEqual({ id: '7', name: 'JOB_COMPLETED', data: '{"ok":true}' });
  });

  it('joins multiline data frames', () => {
    const parsed = parseSseBlock('id: 1\nevent: X\ndata: {"a":\ndata: 1}');
    expect(parsed?.data).toBe('{"a":\n1}');
  });
});

describe('useEventStream', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('TC-RT-09: streams rows incrementally in order', async () => {
    const seen: StreamEvent[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        sseResponse([
          frame(1, 'VERDICT_COMPUTED', { clauseRef: '4.1', verdict: 'PASS' }),
          frame(2, 'VERDICT_COMPUTED', { clauseRef: '4.2', verdict: 'PASS' }),
          frame(3, 'DECISION_UPDATED', { decision: 'BID', score: '100.00' }),
        ]),
      ),
    );
    const { unmount } = render(
      <Harness
        onEvent={(event) => {
          seen.push(event);
        }}
      />,
    );
    await waitFor(() => expect(seen).toHaveLength(3));
    expect(seen.map((event) => event.seq)).toEqual([1, 2, 3]);
    expect(seen[0].type).toBe('VERDICT_COMPUTED');
    expect(seen[0].payload['clauseRef']).toBe('4.1');
    expect(screen.getByTestId('conn')).toHaveTextContent('STREAMING:3');
    unmount();
  });

  it('TC-RT-10: reconnects with Last-Event-ID and dedupes by seq', async () => {
    const seen: StreamEvent[] = [];
    const fetchMock = vi.fn();
    fetchMock
      .mockResolvedValueOnce(
        sseResponse(
          [
            frame(1, 'VERDICT_COMPUTED', { clauseRef: '4.1' }),
            frame(2, 'VERDICT_COMPUTED', { clauseRef: '4.2' }),
          ],
          'end',
        ),
      )
      .mockResolvedValue(
        sseResponse([
          frame(2, 'VERDICT_COMPUTED', { clauseRef: '4.2' }),
          frame(3, 'JOB_COMPLETED', {}),
        ]),
      );
    vi.stubGlobal('fetch', fetchMock);
    const { unmount } = render(
      <Harness
        onEvent={(event) => {
          seen.push(event);
        }}
      />,
    );
    await waitFor(() => expect(seen).toHaveLength(3));
    expect(seen.map((event) => event.seq)).toEqual([1, 2, 3]);
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2);
    const secondHeaders = fetchMock.mock.calls[1][1].headers as Record<string, string>;
    expect(secondHeaders['Last-Event-ID']).toBe('2');
    unmount();
  });

  it('TC-RT-11: falls back to polling after repeated failures', async () => {
    const seen: StreamEvent[] = [];
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('afterSeq')) {
        return Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve([
              { seq: 5, type: 'JOB_COMPLETED', stage: null, payload: {}, createdAt: '' },
            ]),
        });
      }
      return Promise.reject(new Error('stream down'));
    });
    vi.stubGlobal('fetch', fetchMock);
    const { unmount } = render(
      <Harness
        pollIntervalMs={10}
        onEvent={(event) => {
          seen.push(event);
        }}
      />,
    );
    await waitFor(() => expect(screen.getByTestId('conn')).toHaveTextContent('POLLING'));
    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0]).toMatchObject({ seq: 5, type: 'JOB_COMPLETED' });
    // initial attempt + 3 reconnects before the fallback kicks in
    const streamCalls = fetchMock.mock.calls.filter(
      (call) => !(call[0] as string).includes('afterSeq'),
    );
    expect(streamCalls.length).toBeGreaterThanOrEqual(4);
    unmount();
  });

  it('does not emit events at or below the last seen seq', async () => {
    const seen: StreamEvent[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        sseResponse([
          frame(4, 'RISK_RAISED', {}),
          frame(4, 'RISK_RAISED', {}),
          frame(3, 'RISK_RAISED', {}),
        ]),
      ),
    );
    const { unmount } = render(
      <Harness
        onEvent={(event) => {
          seen.push(event);
        }}
      />,
    );
    await waitFor(() => expect(seen).toHaveLength(1));
    expect(seen[0].seq).toBe(4);
    unmount();
  });
});
