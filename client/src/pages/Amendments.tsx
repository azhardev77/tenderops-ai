import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  useAmendmentDiff,
  useAmendments,
  useLoadCorrigendum,
  useTender,
  useUploadAmendment,
} from '../api/queries';
import type { AmendmentUploadResponse } from '../api/types';
import { DecisionBanner } from '../components/badges';
import { Empty, ErrorState, Loading } from '../components/states';
import UploadForm from '../components/UploadForm';
import type { VersionDto } from '../api/types';

function versionLabel(versions: VersionDto[], versionId: number): string {
  const found = versions.find((version) => version.id === versionId);
  return found ? `v${found.versionNumber} (${found.kind})` : `#${versionId}`;
}

export default function Amendments() {
  const { id } = useParams();
  const tenderId = Number(id);
  const valid = Number.isFinite(tenderId);
  const tender = useTender(valid ? tenderId : null);
  const amendments = useAmendments(valid ? tenderId : null);
  const upload = useUploadAmendment(tenderId);
  const loadDemo = useLoadCorrigendum(tenderId);
  const [kind, setKind] = useState('CORRIGENDUM');
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const diff = useAmendmentDiff(selectedId);

  const done = (result: AmendmentUploadResponse) => {
    if (result.duplicate) {
      setNotice('Duplicate corrigendum: these exact bytes were already processed.');
      return;
    }
    setNotice('Amendment accepted; the merge pipeline is running.');
    setSelectedId(result.amendmentId);
  };

  if (tender.isLoading) {
    return <Loading label="tender" />;
  }
  if (tender.isError || !tender.data) {
    return <ErrorState message="Tender not found or failed to load." />;
  }

  return (
    <main>
      <h1>Amendments — {tender.data.title}</h1>
      <p>
        <Link to={`/tenders/${tenderId}/workspace`}>Back to workspace</Link>
      </p>
      <section>
        <h2>Upload amendment PDF</h2>
        <label>
          Kind{' '}
          <select value={kind} onChange={(event) => setKind(event.target.value)}>
            <option value="CORRIGENDUM">CORRIGENDUM</option>
            <option value="ADDENDUM">ADDENDUM</option>
            <option value="CLARIFICATION">CLARIFICATION</option>
          </select>
        </label>
        <UploadForm
          label="Amendment PDF"
          pending={upload.isPending}
          error={upload.isError ? (upload.error as Error).message : null}
          onUpload={(file) => {
            setNotice(null);
            upload.mutate(
              { file, kind },
              { onSuccess: done },
            );
          }}
        />
      </section>
      <section>
        <h2>Demo</h2>
        <button
          type="button"
          disabled={loadDemo.isPending}
          onClick={() => {
            setNotice(null);
            loadDemo.mutate(undefined, { onSuccess: done });
          }}
        >
          {loadDemo.isPending ? 'Loading…' : 'Apply demo corrigendum'}
        </button>
        {loadDemo.isError && <p role="alert">{(loadDemo.error as Error).message}</p>}
      </section>
      {notice && <p role="status">{notice}</p>}

      <section>
        <h2>History</h2>
        {amendments.isLoading && <Loading label="amendments" />}
        {amendments.isError && <ErrorState message="Amendments failed to load." />}
        {amendments.data && amendments.data.length === 0 && (
          <Empty message="No amendments yet." />
        )}
        {amendments.data && amendments.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Base → new version</th>
                <th>Decision transition</th>
                <th>Score delta</th>
                <th>Diff</th>
              </tr>
            </thead>
            <tbody>
              {amendments.data.map((amendment) => (
                <tr key={amendment.id}>
                  <td>{amendment.id}</td>
                  <td>
                    {versionLabel(tender.data.versions, amendment.baseVersionId)} →{' '}
                    {versionLabel(tender.data.versions, amendment.newVersionId)}
                  </td>
                  <td>
                    {amendment.prevDecision ?? '—'} → {amendment.newDecision ?? '—'}
                  </td>
                  <td>{amendment.scoreDelta ?? '—'}</td>
                  <td>
                    <button type="button" onClick={() => setSelectedId(amendment.id)}>
                      Show diff
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selectedId !== null && (
        <section>
          <h2>Diff for amendment {selectedId}</h2>
          {diff.isLoading && <Loading label="diff" />}
          {diff.isError && <ErrorState message="Diff is not ready yet — the merge pipeline may still be running." />}
          {diff.data && (
            <>
              {diff.data.newDecision && <DecisionBanner decision={diff.data.newDecision} />}
              <p>
                {diff.data.prevDecision ?? '—'} → {diff.data.newDecision ?? '—'} (delta{' '}
                {diff.data.scoreDelta ?? '—'})
              </p>
              <p>Deadline changed: {diff.data.deadlineChanged ? 'yes' : 'no'}</p>
              <h3>Inserted clauses</h3>
              {diff.data.inserted.length === 0 ? (
                <p>None.</p>
              ) : (
                <ul>
                  {diff.data.inserted.map((clause) => (
                    <li key={clause}>{clause}</li>
                  ))}
                </ul>
              )}
              <h3>Deleted clauses</h3>
              {diff.data.deleted.length === 0 ? (
                <p>None.</p>
              ) : (
                <ul>
                  {diff.data.deleted.map((clause) => (
                    <li key={clause}>{clause}</li>
                  ))}
                </ul>
              )}
              <h3>Modified clauses</h3>
              {diff.data.modified.length === 0 ? (
                <p>None.</p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>Clause</th>
                      <th>Threshold</th>
                      <th>Operator</th>
                      <th>Condition tree</th>
                    </tr>
                  </thead>
                  <tbody>
                    {diff.data.modified.map((row) => (
                      <tr key={row.clauseRef}>
                        <td>{row.clauseRef}</td>
                        <td>{row.thresholdChanged ? 'changed' : 'same'}</td>
                        <td>{row.operatorChanged ? 'changed' : 'same'}</td>
                        <td>{row.conditionTreeChanged ? 'changed' : 'same'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </section>
      )}
    </main>
  );
}
