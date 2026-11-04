import { useState } from 'react';
import { useAuditLogs } from '../api/queries';
import { Empty, ErrorState, Loading } from '../components/states';

const ENTITY_TYPES = ['', 'company', 'financial_record', 'project', 'certificate', 'company_document', 'bid_decision', 'USER'];

export default function Audit() {
  const [entityType, setEntityType] = useState('');
  const [page, setPage] = useState(0);
  const logs = useAuditLogs(entityType === '' ? null : entityType, page);

  return (
    <main>
      <h1>Audit log</h1>
      <label>
        Entity type{' '}
        <select
          value={entityType}
          onChange={(event) => {
            setEntityType(event.target.value);
            setPage(0);
          }}
        >
          {ENTITY_TYPES.map((type) => (
            <option key={type} value={type}>
              {type === '' ? 'All' : type}
            </option>
          ))}
        </select>
      </label>
      {logs.isLoading && <Loading label="audit log" />}
      {logs.isError && (
        <ErrorState
          message={(logs.error as Error)?.message ?? 'Audit log failed to load.'}
          onRetry={() => void logs.refetch()}
        />
      )}
      {logs.data && logs.data.items.length === 0 && (
        <Empty message="No audit entries." />
      )}
      {logs.data && logs.data.items.length > 0 && (
        <>
          <table>
            <thead>
              <tr>
                <th>Action</th>
                <th>Entity</th>
                <th>ID</th>
                <th>Before</th>
                <th>After</th>
                <th>User</th>
                <th>At</th>
              </tr>
            </thead>
            <tbody>
              {logs.data.items.map((entry, index) => (
                // eslint-disable-next-line react/no-array-index-key
                <tr key={index}>
                  <td>{entry.action}</td>
                  <td>{entry.entityType}</td>
                  <td>{entry.entityId ?? '—'}</td>
                  <td>
                    <details>
                      <summary>before</summary>
                      <pre style={{ whiteSpace: 'pre-wrap' }}>{entry.before ?? '—'}</pre>
                    </details>
                  </td>
                  <td>
                    <details>
                      <summary>after</summary>
                      <pre style={{ whiteSpace: 'pre-wrap' }}>{entry.after ?? '—'}</pre>
                    </details>
                  </td>
                  <td>{entry.userId ?? '—'}</td>
                  <td>{entry.createdAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>
            Page {logs.data.page + 1} ({logs.data.total} total){' '}
            <button
              type="button"
              disabled={page <= 0}
              onClick={() => setPage((value) => value - 1)}
            >
              Prev
            </button>{' '}
            <button
              type="button"
              disabled={(page + 1) * logs.data.size >= logs.data.total}
              onClick={() => setPage((value) => value + 1)}
            >
              Next
            </button>
          </p>
        </>
      )}
    </main>
  );
}
