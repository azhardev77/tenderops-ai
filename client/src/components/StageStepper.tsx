import type { StreamEvent } from '../api/types';

export const FULL_STAGES = [
  'VALIDATE',
  'PARSE',
  'RENDER',
  'VISUAL',
  'EXTRACT',
  'VERIFY',
  'RISK',
  'DECIDE',
] as const;

export function StageStepper({ events }: { events: StreamEvent[] }) {
  const completed = new Set<string>();
  let current: string | null = null;
  let failed: string | null = null;
  for (const event of events) {
    const stage = event.stage ?? (event.payload['stage'] as string | undefined) ?? null;
    if (!stage) {
      continue;
    }
    if (event.type === 'STAGE_STARTED') {
      current = stage;
    } else if (event.type === 'STAGE_COMPLETED') {
      completed.add(stage);
      if (current === stage) {
        current = null;
      }
    } else if (event.type === 'STAGE_FAILED') {
      failed = stage;
    }
  }
  return (
    <ol data-testid="stage-stepper">
      {FULL_STAGES.map((stage) => (
        <li
          key={stage}
          data-testid={`stage-${stage}`}
          data-state={failed === stage ? 'failed' : completed.has(stage) ? 'done' : current === stage ? 'active' : 'pending'}
        >
          {failed === stage ? '✗' : completed.has(stage) ? '✓' : current === stage ? '▶' : '○'} {stage}
        </li>
      ))}
    </ol>
  );
}

function describe(event: StreamEvent): string {
  const payload = event.payload;
  switch (event.type) {
    case 'STAGE_STARTED':
    case 'STAGE_COMPLETED':
    case 'STAGE_FAILED':
      return `${event.type} ${(payload['stage'] as string) ?? ''}`.trim();
    case 'STAGE_PROGRESS':
      return `STAGE_PROGRESS ${String(payload['stage'] ?? '')} ${String(payload['done'] ?? '?')}/${String(payload['total'] ?? '?')}`;
    case 'PAGE_PARSED':
      return `PAGE_PARSED p${String(payload['pageNo'] ?? '?')} (${String(payload['classification'] ?? '?')})`;
    case 'REGION_DETECTED':
      return `REGION_DETECTED ${String(payload['regionType'] ?? '?')} p${String(payload['pageNo'] ?? '?')}`;
    case 'REQUIREMENT_EXTRACTED':
      return `REQUIREMENT_EXTRACTED clause ${String(payload['clauseRef'] ?? '?')}`;
    case 'VERDICT_COMPUTED':
      return `VERDICT_COMPUTED clause ${String(payload['clauseRef'] ?? '?')} = ${String(payload['verdict'] ?? '?')}`;
    case 'RISK_RAISED':
      return `RISK_RAISED ${String(payload['severity'] ?? '?')} ${String(payload['riskCode'] ?? '')}`.trim();
    case 'DECISION_UPDATED':
      return `DECISION_UPDATED ${String(payload['decision'] ?? '?')} score ${String(payload['score'] ?? '?')}`;
    case 'AI_CALL':
      return `AI_CALL ${String(payload['component'] ?? '?')} ${String(payload['aiMode'] ?? '?')}`;
    case 'AGENT_STEP':
      return `AGENT_STEP ${String(payload['toolName'] ?? '?')}`;
    case 'AMENDMENT_STAGE':
      return `AMENDMENT_STAGE modified=${JSON.stringify(payload['modified'] ?? [])}`;
    case 'JOB_COMPLETED':
    case 'JOB_FAILED':
      return event.type;
    default:
      return event.type;
  }
}

export function ActivityFeed({ events }: { events: StreamEvent[] }) {
  if (events.length === 0) {
    return <p>No activity yet.</p>;
  }
  return (
    <ol data-testid="activity-feed">
      {events.map((event) => (
        <li key={event.seq}>
          #{event.seq} {describe(event)}
        </li>
      ))}
    </ol>
  );
}
