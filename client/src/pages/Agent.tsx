import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useAgentRun, useAgentTrace, useRequirements, useTender } from '../api/queries';
import { Empty, ErrorState, Loading } from '../components/states';

export default function Agent() {
  const { id } = useParams();
  const tenderId = Number(id);
  const valid = Number.isFinite(tenderId);
  const tender = useTender(valid ? tenderId : null);
  const versions = tender.data?.versions ?? [];
  const [versionId, setVersionId] = useState<number | null>(null);
  const activeVersion =
    versions.find((version) => version.id === versionId) ??
    (versions.length > 0 ? versions[versions.length - 1] : null);
  const effectiveVersionId = activeVersion?.id ?? null;
  const requirements = useRequirements(effectiveVersionId);
  const [requirementId, setRequirementId] = useState<string>('');
  const [agentRunId, setAgentRunId] = useState<number | null>(null);
  const run = useAgentRun(effectiveVersionId ?? 0);
  const trace = useAgentTrace(agentRunId);

  if (tender.isLoading) {
    return <Loading label="tender" />;
  }
  if (tender.isError || !tender.data) {
    return <ErrorState message="Tender not found or failed to load." />;
  }

  return (
    <main>
      <h1>Agent — {tender.data.title}</h1>
      <p>
        <Link to={`/tenders/${tenderId}/workspace`}>Back to workspace</Link>
      </p>
      <p>
        The bounded verification agent explains one requirement. The persisted verdict
        always equals the Java engine output.
      </p>
      <label>
        Version{' '}
        <select
          value={effectiveVersionId ?? ''}
          onChange={(event) => {
            setVersionId(Number(event.target.value));
            setAgentRunId(null);
          }}
        >
          {versions.map((version) => (
            <option key={version.id} value={version.id}>
              v{version.versionNumber} ({version.kind})
            </option>
          ))}
        </select>
      </label>{' '}
      <label>
        Requirement{' '}
        <select
          value={requirementId}
          onChange={(event) => setRequirementId(event.target.value)}
        >
          <option value="">Default (first non-pass mandatory)</option>
          {(requirements.data ?? []).map((requirement) => (
            <option key={requirement.id} value={requirement.id}>
              {requirement.clauseRef} ({requirement.metric})
            </option>
          ))}
        </select>
      </label>{' '}
      <button
        type="button"
        disabled={run.isPending || effectiveVersionId === null}
        onClick={() =>
          run.mutate(requirementId === '' ? null : Number(requirementId), {
            onSuccess: (data) => setAgentRunId(data.agentRunId),
          })
        }
      >
        {run.isPending ? 'Running…' : 'Run agent'}
      </button>
      {run.isError && <p role="alert">{(run.error as Error).message}</p>}

      {agentRunId !== null && (
        <section>
          <h2>Trace for run {agentRunId}</h2>
          {trace.isLoading && <Loading label="agent trace" />}
          {trace.isError && <ErrorState message="Agent trace failed to load." />}
          {trace.data && (
            <>
              <ul>
                <li>Mode: {trace.data.mode}</li>
                <li>Muse mode: {trace.data.museMode}</li>
                <li>Final verdict: {trace.data.finalVerdict ?? '—'}</li>
              </ul>
              {trace.data.steps.length === 0 ? (
                <Empty message="No steps recorded." />
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Tool</th>
                      <th>Latency (ms)</th>
                      <th>Error</th>
                      <th>Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trace.data.steps.map((step) => (
                      <tr key={step.stepNo}>
                        <td>{step.stepNo}</td>
                        <td>{step.toolName}</td>
                        <td>{step.latencyMs ?? '—'}</td>
                        <td>{step.error ?? '—'}</td>
                        <td>
                          <details>
                            <summary>input/output</summary>
                            <pre style={{ whiteSpace: 'pre-wrap' }}>
                              {JSON.stringify({ input: step.input, output: step.output }, null, 2)}
                            </pre>
                          </details>
                        </td>
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
