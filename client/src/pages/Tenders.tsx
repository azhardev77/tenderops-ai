import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useLoadRfp, useTenders, useUploadTender } from '../api/queries';
import type { UploadResponse } from '../api/types';
import { Empty, ErrorState, Loading } from '../components/states';
import UploadForm from '../components/UploadForm';

export default function Tenders() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const tenders = useTenders(page);
  const upload = useUploadTender();
  const loadRfp = useLoadRfp();
  const [notice, setNotice] = useState<string | null>(null);

  const done = (result: UploadResponse) => {
    if (result.duplicate) {
      setNotice(
        `Duplicate upload: tender ${result.tenderId} already holds these exact bytes; no reprocessing.`,
      );
      return;
    }
    navigate(`/tenders/${result.tenderId}/workspace`);
  };

  return (
    <main>
      <h1>Tenders</h1>
      <section>
        <h2>Upload tender PDF</h2>
        <UploadForm
          label="Tender PDF"
          pending={upload.isPending}
          error={upload.isError ? (upload.error as Error).message : null}
          onUpload={(file) => {
            setNotice(null);
            upload.mutate(file, { onSuccess: done });
          }}
        />
      </section>
      <section>
        <h2>Demo</h2>
        <button
          type="button"
          disabled={loadRfp.isPending}
          onClick={() => {
            setNotice(null);
            loadRfp.mutate(undefined, { onSuccess: done });
          }}
        >
          {loadRfp.isPending ? 'Loading…' : 'Load demo RFP'}
        </button>
        {loadRfp.isError && <p role="alert">{(loadRfp.error as Error).message}</p>}
      </section>
      {notice && <p role="status">{notice}</p>}
      <section>
        <h2>All tenders</h2>
        {tenders.isLoading && <Loading label="tenders" />}
        {tenders.isError && (
          <ErrorState
            message={(tenders.error as Error)?.message ?? 'Tenders failed to load.'}
            onRetry={() => void tenders.refetch()}
          />
        )}
        {tenders.data && tenders.data.items.length === 0 && (
          <Empty message="No tenders yet. Upload one or load the demo RFP." />
        )}
        {tenders.data && tenders.data.items.length > 0 && (
          <>
            <table>
              <thead>
                <tr>
                  <th>Title</th>
                  <th>Reference</th>
                  <th>Version</th>
                  <th>Decision</th>
                  <th>Updated</th>
                </tr>
              </thead>
              <tbody>
                {tenders.data.items.map((tender) => (
                  <tr key={tender.id}>
                    <td>
                      <Link to={`/tenders/${tender.id}/workspace`}>{tender.title}</Link>
                    </td>
                    <td>{tender.reference ?? '—'}</td>
                    <td>{tender.latestVersion}</td>
                    <td>{tender.latestDecision ?? '—'}</td>
                    <td>{tender.updatedAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              Page {tenders.data.page + 1} of{' '}
              {Math.max(1, Math.ceil(tenders.data.total / tenders.data.size))} (
              {tenders.data.total} total){' '}
              <button
                type="button"
                disabled={page <= 0}
                onClick={() => setPage((value) => value - 1)}
              >
                Prev
              </button>{' '}
              <button
                type="button"
                disabled={(page + 1) * tenders.data.size >= tenders.data.total}
                onClick={() => setPage((value) => value + 1)}
              >
                Next
              </button>
            </p>
          </>
        )}
      </section>
    </main>
  );
}
