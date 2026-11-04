import type { ConnectionState } from '../hooks/useEventStream';

const VERDICT_STYLES: Record<string, { background: string; color: string }> = {
  PASS: { background: '#d6f0d6', color: '#0a4d0a' },
  FAIL: { background: '#f6d5d5', color: '#7a1010' },
  UNKNOWN: { background: '#e8e8e8', color: '#333' },
  NEEDS_REVIEW: { background: '#fff0c2', color: '#6b4e00' },
};

export function VerdictBadge({ verdict }: { verdict: string }) {
  const style = VERDICT_STYLES[verdict] ?? { background: '#eee', color: '#111' };
  return (
    <span
      data-testid={`verdict-${verdict}`}
      style={{ ...style, padding: '0.1rem 0.5rem', borderRadius: 4, fontWeight: 600 }}
    >
      {verdict}
    </span>
  );
}

const DECISION_STYLES: Record<string, { background: string; color: string }> = {
  BID: { background: '#d6f0d6', color: '#0a4d0a' },
  CONDITIONAL_BID: { background: '#fff0c2', color: '#6b4e00' },
  NO_BID: { background: '#f6d5d5', color: '#7a1010' },
  INSUFFICIENT_DATA: { background: '#e8e8e8', color: '#333' },
};

export function DecisionBanner({ decision }: { decision: string }) {
  const style = DECISION_STYLES[decision] ?? { background: '#eee', color: '#111' };
  return (
    <p
      role="status"
      data-testid={`decision-${decision}`}
      style={{ ...style, padding: '0.5rem 1rem', fontWeight: 700 }}
    >
      Recommendation: {decision}
    </p>
  );
}

export function AiModeBadge({ museMode, samMode }: { museMode: string; samMode: string }) {
  return (
    <p>
      Extraction: <strong>MUSE/{museMode}</strong> Visual: <strong>SAM/{samMode}</strong>
    </p>
  );
}

export function SourceBadge({ source }: { source: string | null }) {
  if (!source) {
    return null;
  }
  return <span data-testid={`source-${source}`}>[{source}]</span>;
}

export function ConnectionBadge({ state }: { state: ConnectionState }) {
  return (
    <p data-testid="connection-state">
      Connection: <strong>{state}</strong>
    </p>
  );
}
