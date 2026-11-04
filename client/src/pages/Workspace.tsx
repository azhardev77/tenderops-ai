import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/client';
import {
  useChecklist,
  useDecision,
  useMatrix,
  usePages,
  usePageText,
  useRegions,
  useRequirements,
  useRisks,
  useTender,
} from '../api/queries';
import type {
  MatrixResponse,
  MatrixRow,
  RequirementDto,
  StreamEvent,
} from '../api/types';
import { AiModeBadge, ConnectionBadge, DecisionBanner } from '../components/badges';
import MatrixTable from '../components/MatrixTable';
import PageViewer from '../components/PageViewer';
import { ActivityFeed, StageStepper } from '../components/StageStepper';
import { Empty, ErrorState, Loading } from '../components/states';
import { useEventStream } from '../hooks/useEventStream';

function mergeVerdict(
  previous: MatrixResponse | undefined,
  requirement: RequirementDto,
  verdict: string,
  reasonCode: string | null,
): MatrixResponse | undefined {
  if (!previous) {
    return previous;
  }
  const row: MatrixRow = {
    requirement: {
      id: requirement.id,
      clauseRef: requirement.clauseRef,
      mandatory: requirement.mandatory,
      metric: requirement.metric,
      operator: requirement.operator,
      amountText: requirement.amountText,
      sourcePage: requirement.sourcePage,
      originalQuote: requirement.originalQuote,
      confidence: requirement.confidence,
    },
    verdict,
    reasonCode,
    calculationTrace: { pending: true },
    copied: false,
  };
  const rows = previous.rows.filter((existing) => existing.requirement.id !== row.requirement.id);
  rows.push(row);
  rows.sort((a, b) => a.requirement.id - b.requirement.id);
  return { ...previous, rows };
}

export default function Workspace() {
  const { id } = useParams();
  const tenderId = Number(id);
  const tender = useTender(Number.isFinite(tenderId) ? tenderId : null);
  const [versionId, setVersionId] = useState<number | null>(null);
  const [pageNo, setPageNo] = useState(1);
  const [selected, setSelected] = useState<MatrixRow | null>(null);
  const [events, setEvents] = useState<StreamEvent[]>([]);
  const seenRef = useRef<Set<number>>(new Set());
  const queryClient = useQueryClient();

  const versions = tender.data?.versions ?? [];
  const activeVersion = versions.find((version) => version.id === versionId) ?? null;
  useEffect(() => {
    if (versions.length > 0 && versionId === null) {
      setVersionId(versions[versions.length - 1].id);
    }
  }, [versions, versionId]);
  useEffect(() => {
    setEvents([]);
    seenRef.current = new Set();
    setSelected(null);
    setPageNo(1);
  }, [versionId]);

  const pages = usePages(versionId);
  const regions = useRegions(versionId);
  useRequirements(versionId); // warms the cache the live verdict merge reads from
  const matrix = useMatrix(versionId);
  const risks = useRisks(versionId);
  const checklist = useChecklist(versionId);
  const decision = useDecision(versionId);
  const pageText = usePageText(versionId, pageNo);

  const appendEvents = useCallback((incoming: StreamEvent[]) => {
    setEvents((current) => {
      const merged = [...current];
      for (const event of incoming) {
        if (!seenRef.current.has(event.seq)) {
          seenRef.current.add(event.seq);
          merged.push(event);
        }
      }
      merged.sort((a, b) => a.seq - b.seq);
      return merged.slice(-200);
    });
  }, []);

  // Seed the stepper/feed from the persisted history, then go live.
  useEffect(() => {
    if (versionId === null) {
      return;
    }
    let cancelled = false;
    void api<StreamEvent[]>(`/api/v1/tender-versions/${versionId}/events?afterSeq=0`)
      .then((history) => {
        if (!cancelled) {
          appendEvents(history);
        }
      })
      .catch(() => {
        // history is best-effort; the live stream still works
      });
    return () => {
      cancelled = true;
    };
  }, [versionId, appendEvents]);

  const streamUrl = versionId === null ? null : `/api/v1/tender-versions/${versionId}/events`;
  const { state } = useEventStream({
    url: streamUrl,
    pollUrl: streamUrl,
    enabled: versionId !== null,
    onEvent: (event) => {
      appendEvents([event]);
      if (event.type === 'VERDICT_COMPUTED') {
        const cached = queryClient.getQueryData<RequirementDto[]>([
          'requirements',
          versionId,
        ]);
        const requirementId = event.payload['requirementId'];
        const found = cached?.find((row) => row.id === requirementId);
        if (found) {
          queryClient.setQueryData<MatrixResponse | undefined>(
            ['matrix', versionId],
            (previous) =>
              mergeVerdict(
                previous,
                found,
                String(event.payload['verdict'] ?? 'UNKNOWN'),
                (event.payload['reasonCode'] as string | null) ?? null,
              ),
          );
        }
      } else if (event.type === 'REQUIREMENT_EXTRACTED') {
        void queryClient.invalidateQueries({ queryKey: ['requirements', versionId] });
      } else if (event.type === 'REGION_DETECTED') {
        void queryClient.invalidateQueries({ queryKey: ['regions', versionId] });
      } else if (event.type === 'PAGE_PARSED') {
        void queryClient.invalidateQueries({ queryKey: ['pages', versionId] });
      } else if (
        event.type === 'DECISION_UPDATED' ||
        event.type === 'JOB_COMPLETED' ||
        event.type === 'JOB_FAILED'
      ) {
        for (const key of [
          'matrix',
          'risks',
          'decision',
          'checklist',
          'clarifications',
          'summary',
          'requirements',
          'regions',
          'pages',
        ]) {
          void queryClient.invalidateQueries({ queryKey: [key, versionId] });
        }
        void queryClient.invalidateQueries({ queryKey: ['tender', tenderId] });
        void queryClient.invalidateQueries({ queryKey: ['tenders'] });
      }
    },
  });

  if (tender.isLoading) {
    return <Loading label="tender" />;
  }
  if (tender.isError || !tender.data) {
    return <ErrorState message="Tender not found or failed to load." />;
  }

  const pageCount = activeVersion?.pageCount ?? 0;
  const pageRegions = (regions.data ?? []).filter((region) => region.pageNo === pageNo);

  return (
    <main>
      <h1>{tender.data.title}</h1>
      <p>
        <Link to={`/tenders/${tenderId}/amendments`}>Amendments</Link> |{' '}
        <Link to={`/tenders/${tenderId}/package`}>Bid package</Link> |{' '}
        <Link to={`/tenders/${tenderId}/agent`}>Agent</Link>
      </p>
      <ConnectionBadge state={state} />
      <label>
        Version{' '}
        <select
          value={versionId ?? ''}
          onChange={(event) => setVersionId(Number(event.target.value))}
        >
          {versions.map((version) => (
            <option key={version.id} value={version.id}>
              v{version.versionNumber} ({version.kind})
            </option>
          ))}
        </select>
      </label>
      {decision.data && <DecisionBanner decision={decision.data.recommendation} />}
      {matrix.data && (
        <>
          <p>
            Readiness score (rule-based): <strong>{matrix.data.readinessScore ?? '—'}</strong>
          </p>
          <AiModeBadge museMode={matrix.data.museMode} samMode={matrix.data.samMode} />
          {matrix.data.timeSaved && (
            <p>
              Time saved: {matrix.data.timeSaved.percent}% (manual{' '}
              {matrix.data.timeSaved.manualMinutes ?? '—'} min, assisted{' '}
              {matrix.data.timeSaved.assistedMinutes ?? '—'} min)
            </p>
          )}
        </>
      )}

      <h2>Stages</h2>
      <StageStepper events={events} />
      <h2>Activity</h2>
      <ActivityFeed events={events} />

      <h2>Compliance matrix</h2>
      {matrix.isLoading && <Loading label="matrix" />}
      {matrix.isError && (
        <p>No compliance run yet for this version — the matrix appears when the pipeline finishes.</p>
      )}
      {matrix.data && (
        <MatrixTable
          rows={matrix.data.rows}
          selectedId={selected?.requirement.id ?? null}
          onPageChip={(page) => setPageNo(page)}
          onSelectRow={(row) => {
            setSelected(row);
            setPageNo(row.requirement.sourcePage);
          }}
        />
      )}

      <h2>Pages</h2>
      {pages.isLoading && <Loading label="pages" />}
      {pages.isError && <ErrorState message="Pages failed to load." />}
      {pages.data && pageCount > 0 && versionId !== null && (
        <PageViewer
          versionId={versionId}
          pageNo={Math.min(pageNo, pageCount)}
          pageCount={pageCount}
          regions={pageRegions}
          onPageChange={setPageNo}
          highlightQuote={selected?.requirement.originalQuote ?? null}
        />
      )}
      {pageText.data?.text && (
        <details>
          <summary>Page {pageNo} text</summary>
          <pre style={{ whiteSpace: 'pre-wrap' }}>{pageText.data.text}</pre>
        </details>
      )}

      <h2>Risk register</h2>
      {risks.isLoading && <Loading label="risks" />}
      {(risks.data?.length ?? 0) === 0 && !risks.isLoading && (
        <Empty message="No risks recorded." />
      )}
      {(risks.data?.length ?? 0) > 0 && (
        <table>
          <thead>
            <tr>
              <th>Severity</th>
              <th>Code</th>
              <th>Clause</th>
              <th>Impact</th>
              <th>Corrective action</th>
            </tr>
          </thead>
          <tbody>
            {risks.data!.map((risk, index) => (
              // eslint-disable-next-line react/no-array-index-key
              <tr key={index}>
                <td>{risk.severity}</td>
                <td>{risk.riskCode}</td>
                <td>{risk.sourceClause ?? '—'}</td>
                <td>{risk.businessImpact ?? '—'}</td>
                <td>{risk.correctiveAction ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Missing-document checklist</h2>
      {checklist.isLoading && <Loading label="checklist" />}
      {(checklist.data?.length ?? 0) === 0 && !checklist.isLoading && (
        <Empty message="No missing documents." />
      )}
      {(checklist.data?.length ?? 0) > 0 && (
        <ul>
          {checklist.data!.map((item) => (
            <li key={item.clauseRef}>
              <strong>Clause {item.clauseRef}</strong>: {item.missingItem} —{' '}
              {item.correctiveAction}
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
