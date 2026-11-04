import { useState } from 'react';
import type { MatrixRow } from '../api/types';
import { VerdictBadge } from './badges';
import { Empty } from './states';

function TraceDrawer({ trace }: { trace: unknown }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOpen((value) => !value)}>
        {open ? 'Hide trace' : 'Show trace'}
      </button>
      {open && (
        <pre data-testid="trace-json" style={{ whiteSpace: 'pre-wrap' }}>
          {JSON.stringify(trace, null, 2)}
        </pre>
      )}
    </div>
  );
}

export default function MatrixTable({
  rows,
  onPageChip,
  onSelectRow,
  selectedId,
}: {
  rows: MatrixRow[];
  onPageChip?: (pageNo: number) => void;
  onSelectRow?: (row: MatrixRow) => void;
  selectedId?: number | null;
}) {
  if (rows.length === 0) {
    return <Empty message="No requirements evaluated yet." />;
  }
  return (
    <table>
      <thead>
        <tr>
          <th>Clause</th>
          <th>Mandatory</th>
          <th>Metric</th>
          <th>Verdict</th>
          <th>Reason</th>
          <th>Source page</th>
          <th>Calculation trace</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.requirement.id}
            data-testid={`matrix-row-${row.requirement.clauseRef}`}
            style={
              selectedId === row.requirement.id ? { background: '#eef4ff' } : undefined
            }
          >
            <td>
              {onSelectRow ? (
                <button type="button" onClick={() => onSelectRow(row)}>
                  {row.requirement.clauseRef}
                </button>
              ) : (
                row.requirement.clauseRef
              )}
            </td>
            <td>{row.requirement.mandatory ? 'yes' : 'no'}</td>
            <td>{row.requirement.metric}</td>
            <td>
              <VerdictBadge verdict={row.verdict} />
            </td>
            <td>{row.reasonCode ?? '—'}</td>
            <td>
              {onPageChip ? (
                <button type="button" onClick={() => onPageChip(row.requirement.sourcePage)}>
                  p{row.requirement.sourcePage}
                </button>
              ) : (
                `p${row.requirement.sourcePage}`
              )}
              {row.copied ? ' (carried)' : ''}
            </td>
            <td>
              <TraceDrawer trace={row.calculationTrace} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
