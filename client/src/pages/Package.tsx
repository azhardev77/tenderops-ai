import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiBlob } from '../api/client';
import {
  useApprove,
  useChecklist,
  useClarifications,
  useDecision,
  useMatrix,
  useMe,
  useSummary,
  useTender,
} from '../api/queries';
import { DecisionBanner } from '../components/badges';
import MatrixTable from '../components/MatrixTable';
import { Empty, ErrorState, Loading } from '../components/states';

export default function Package() {
  const { id } = useParams();
  const tenderId = Number(id);
  const valid = Number.isFinite(tenderId);
  const me = useMe();
  const canApprove = me?.role === 'ADMIN' || me?.role === 'BID_MANAGER';
  const tender = useTender(valid ? tenderId : null);
  const versions = tender.data?.versions ?? [];
  const [versionId, setVersionId] = useState<number | null>(null);
  const effectiveVersionId =
    versions.find((version) => version.id === versionId)?.id ??
    (versions.length > 0 ? versions[versions.length - 1].id : null);
  const matrix = useMatrix(effectiveVersionId);
  const decision = useDecision(effectiveVersionId);
  const summary = useSummary(effectiveVersionId);
  const checklist = useChecklist(effectiveVersionId);
  const clarifications = useClarifications(effectiveVersionId);
  const approve = useApprove(effectiveVersionId ?? 0);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  useEffect(() => {
    if (versions.length > 0 && versionId === null) {
      setVersionId(versions[versions.length - 1].id);
    }
  }, [versions, versionId]);

  if (tender.isLoading) {
    return <Loading label="tender" />;
  }
  if (tender.isError || !tender.data) {
    return <ErrorState message="Tender not found or failed to load." />;
  }

  const download = (kind: 'pdf' | 'xlsx') => {
    if (effectiveVersionId === null) {
      return;
    }
    setDownloadError(null);
    const path =
      kind === 'pdf'
        ? `/api/v1/tender-versions/${effectiveVersionId}/reports/bid-package.pdf`
        : `/api/v1/tender-versions/${effectiveVersionId}/reports/checklist.xlsx`;
    void apiBlob(path)
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = kind === 'pdf' ? 'bid-package.pdf' : 'checklist.xlsx';
        anchor.click();
        URL.revokeObjectURL(url);
      })
      .catch((error: Error) => setDownloadError(error.message));
  };

  return (
    <main>
      <h1>Bid package — {tender.data.title}</h1>
      <p>
        <Link to={`/tenders/${tenderId}/workspace`}>Back to workspace</Link>
      </p>
      <label>
        Version{' '}
        <select
          value={effectiveVersionId ?? ''}
          onChange={(event) => setVersionId(Number(event.target.value))}
        >
          {versions.map((version) => (
            <option key={version.id} value={version.id}>
              v{version.versionNumber} ({version.kind})
            </option>
          ))}
        </select>
      </label>

      {decision.isLoading && <Loading label="decision" />}
      {decision.data && (
        <>
          <DecisionBanner decision={decision.data.recommendation} />
          <p>
            Approval: <strong>{decision.data.approvalStatus}</strong> — readiness score
            (rule-based): <strong>{decision.data.readinessScore ?? '—'}</strong>
          </p>
          {decision.data.warnings.length > 0 && (
            <ul>
              {decision.data.warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          )}
          {decision.data.approvalStatus === 'PENDING_HUMAN_APPROVAL' && (
            <p>
              {canApprove ? (
                <>
                  <button
                    type="button"
                    disabled={approve.isPending}
                    onClick={() => approve.mutate('APPROVED_FOR_BID')}
                  >
                    Approve for bid
                  </button>{' '}
                  <button
                    type="button"
                    disabled={approve.isPending}
                    onClick={() => approve.mutate('REJECTED_NO_BID')}
                  >
                    Reject (no bid)
                  </button>
                </>
              ) : (
                'Approval needs an ADMIN or BID_MANAGER.'
              )}
            </p>
          )}
          {approve.isError && <p role="alert">{(approve.error as Error).message}</p>}
        </>
      )}

      <h2>Executive summary</h2>
      {summary.isLoading && <Loading label="summary" />}
      {summary.data && (
        <>
          <p>{summary.data.text ?? '—'}</p>
          <p>
            Source: {summary.data.source ?? '—'}
            {summary.data.groundingWarning ? ' (grounding warning)' : ''}
          </p>
        </>
      )}

      <h2>Compliance matrix</h2>
      {matrix.isLoading && <Loading label="matrix" />}
      {matrix.isError && <p>No compliance run yet for this version.</p>}
      {matrix.data && <MatrixTable rows={matrix.data.rows} />}

      <h2>Missing-document checklist</h2>
      {(checklist.data?.length ?? 0) === 0 && <Empty message="No missing documents." />}
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

      <h2>Clarification questions</h2>
      {(clarifications.data?.length ?? 0) === 0 && (
        <Empty message="No clarification questions." />
      )}
      {(clarifications.data?.length ?? 0) > 0 && (
        <ul>
          {clarifications.data!.map((item) => (
            <li key={item.clauseRef}>
              <strong>Clause {item.clauseRef}</strong>: {item.question}
            </li>
          ))}
        </ul>
      )}

      <h2>Downloads</h2>
      <p>
        <button type="button" onClick={() => download('pdf')}>
          Download bid package (PDF)
        </button>{' '}
        <button type="button" onClick={() => download('xlsx')}>
          Download checklist (XLSX)
        </button>
      </p>
      {downloadError && <p role="alert">{downloadError}</p>}
    </main>
  );
}
