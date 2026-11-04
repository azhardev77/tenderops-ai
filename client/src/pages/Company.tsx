import { useState } from 'react';
import { Link } from 'react-router-dom';
import {
  useCertificates,
  useDocuments,
  useFinancials,
  useMe,
  useMutateCertificates,
  useMutateFinancials,
  useMutateProjects,
  useProfile,
  useProjects,
  useReverify,
  useTender,
  useTenders,
  useUpdateProfile,
  useUploadDocument,
} from '../api/queries';
import { Empty, ErrorState, Loading } from '../components/states';

function Field({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <label>
      {label}
      <input
        value={value}
        placeholder={placeholder ?? ''}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

function blankToUndefined(value: string): string | undefined {
  return value.trim() === '' ? undefined : value.trim();
}

export default function Company() {
  const me = useMe();
  const canEdit = me?.role !== 'VIEWER';
  const profile = useProfile();
  const updateProfile = useUpdateProfile();
  const financials = useFinancials();
  const mutateFinancials = useMutateFinancials();
  const projects = useProjects();
  const mutateProjects = useMutateProjects();
  const certificates = useCertificates();
  const mutateCertificates = useMutateCertificates();
  const documents = useDocuments();
  const uploadDocument = useUploadDocument();
  const tenders = useTenders(0);

  const [name, setName] = useState('');
  const [gstNumber, setGstNumber] = useState('');
  const [fyLabel, setFyLabel] = useState('');
  const [fyEndDate, setFyEndDate] = useState('');
  const [turnover, setTurnover] = useState('');
  const [netWorth, setNetWorth] = useState('');
  const [projectName, setProjectName] = useState('');
  const [contractValue, setContractValue] = useState('');
  const [completionDate, setCompletionDate] = useState('');
  const [domainCategory, setDomainCategory] = useState('');
  const [certName, setCertName] = useState('');
  const [expiryDate, setExpiryDate] = useState('');
  const [certAmount, setCertAmount] = useState('');
  const [docId, setDocId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [reverifyJob, setReverifyJob] = useState<Record<number, number>>({});

  if (profile.isLoading) {
    return <Loading label="company" />;
  }
  if (profile.isError || !profile.data) {
    return <ErrorState message="Company failed to load." />;
  }

  const fail = (message: string) => setError(message);

  return (
    <main>
      <h1>Company database</h1>
      {!canEdit && <p>You have read-only access (VIEWER).</p>}
      {error && <p role="alert">{error}</p>}
      <section>
        <h2>Profile (data version {profile.data.dataVersion})</h2>
        <p>
          {profile.data.name} — GST {profile.data.gstNumber ?? '—'}
          {profile.data.demo ? ' (demo company)' : ''}
        </p>
        {canEdit && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setError(null);
              updateProfile.mutate(
                { name: blankToUndefined(name), gstNumber: blankToUndefined(gstNumber) },
                { onError: (mutationError) => fail((mutationError as Error).message) },
              );
            }}
          >
            <Field label="Name" value={name} onChange={setName} placeholder={profile.data.name} />
            <Field
              label="GST number"
              value={gstNumber}
              onChange={setGstNumber}
              placeholder={profile.data.gstNumber ?? ''}
            />
            <button type="submit" disabled={updateProfile.isPending}>
              Save profile
            </button>
          </form>
        )}
      </section>

      <section>
        <h2>Financials</h2>
        {financials.isLoading && <Loading label="financials" />}
        {financials.data && financials.data.length === 0 && (
          <Empty message="No financial records." />
        )}
        {financials.data && financials.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>FY</th>
                <th>End date</th>
                <th>Turnover</th>
                <th>Net worth</th>
                <th>Audited</th>
                {canEdit && <th>Delete</th>}
              </tr>
            </thead>
            <tbody>
              {financials.data.map((row) => (
                <tr key={row.id}>
                  <td>{row.fyLabel}</td>
                  <td>{row.fyEndDate}</td>
                  <td>{row.turnover ?? '—'}</td>
                  <td>{row.netWorth ?? '—'}</td>
                  <td>{row.audited ? 'yes' : 'no'}</td>
                  {canEdit && (
                    <td>
                      <button
                        type="button"
                        onClick={() =>
                          mutateFinancials.remove.mutate(row.id, {
                            onError: (mutationError) =>
                              fail((mutationError as Error).message),
                          })
                        }
                      >
                        Delete
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {canEdit && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setError(null);
              mutateFinancials.create.mutate(
                {
                  fyLabel: fyLabel.trim(),
                  fyEndDate: fyEndDate.trim(),
                  turnover: blankToUndefined(turnover),
                  netWorth: blankToUndefined(netWorth),
                },
                {
                  onError: (mutationError) => fail((mutationError as Error).message),
                  onSuccess: () => {
                    setFyLabel('');
                    setFyEndDate('');
                    setTurnover('');
                    setNetWorth('');
                  },
                },
              );
            }}
          >
            <Field label="FY label" value={fyLabel} onChange={setFyLabel} placeholder="FY2025-26" />
            <Field label="FY end date" value={fyEndDate} onChange={setFyEndDate} placeholder="2026-03-31" />
            <Field label="Turnover (string)" value={turnover} onChange={setTurnover} placeholder="1120000000" />
            <Field label="Net worth (string)" value={netWorth} onChange={setNetWorth} />
            <button type="submit">Add financial record</button>
          </form>
        )}
      </section>

      <section>
        <h2>Projects</h2>
        {projects.isLoading && <Loading label="projects" />}
        {projects.data && projects.data.length === 0 && <Empty message="No projects." />}
        {projects.data && projects.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Value</th>
                <th>Completed</th>
                <th>Domain</th>
                {canEdit && <th>Delete</th>}
              </tr>
            </thead>
            <tbody>
              {projects.data.map((row) => (
                <tr key={row.id}>
                  <td>{row.name}</td>
                  <td>{row.contractValue ?? '—'}</td>
                  <td>{row.completionDate ?? '—'}</td>
                  <td>{row.domainCategory}</td>
                  {canEdit && (
                    <td>
                      <button
                        type="button"
                        onClick={() =>
                          mutateProjects.remove.mutate(row.id, {
                            onError: (mutationError) =>
                              fail((mutationError as Error).message),
                          })
                        }
                      >
                        Delete
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {canEdit && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setError(null);
              mutateProjects.create.mutate(
                {
                  name: projectName.trim(),
                  contractValue: blankToUndefined(contractValue),
                  completionDate: blankToUndefined(completionDate),
                  domainCategory: domainCategory.trim(),
                },
                {
                  onError: (mutationError) => fail((mutationError as Error).message),
                  onSuccess: () => {
                    setProjectName('');
                    setContractValue('');
                    setCompletionDate('');
                    setDomainCategory('');
                  },
                },
              );
            }}
          >
            <Field label="Name" value={projectName} onChange={setProjectName} />
            <Field label="Contract value (string)" value={contractValue} onChange={setContractValue} />
            <Field label="Completion date" value={completionDate} onChange={setCompletionDate} placeholder="2023-03-31" />
            <Field label="Domain" value={domainCategory} onChange={setDomainCategory} placeholder="HIGHWAY" />
            <button type="submit">Add project</button>
          </form>
        )}
      </section>

      <section>
        <h2>Certificates</h2>
        {certificates.isLoading && <Loading label="certificates" />}
        {certificates.data && certificates.data.length === 0 && (
          <Empty message="No certificates." />
        )}
        {certificates.data && certificates.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Expiry</th>
                <th>Amount</th>
                <th>Document</th>
                {canEdit && <th>Delete</th>}
              </tr>
            </thead>
            <tbody>
              {certificates.data.map((row) => (
                <tr key={row.id}>
                  <td>{row.name}</td>
                  <td>{row.expiryDate ?? '—'}</td>
                  <td>{row.amount ?? '—'}</td>
                  <td>{row.documentId ?? '—'}</td>
                  {canEdit && (
                    <td>
                      <button
                        type="button"
                        onClick={() =>
                          mutateCertificates.remove.mutate(row.id, {
                            onError: (mutationError) =>
                              fail((mutationError as Error).message),
                          })
                        }
                      >
                        Delete
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {canEdit && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              setError(null);
              mutateCertificates.create.mutate(
                {
                  name: certName.trim(),
                  expiryDate: blankToUndefined(expiryDate),
                  amount: blankToUndefined(certAmount),
                  documentId: docId.trim() === '' ? undefined : Number(docId),
                },
                {
                  onError: (mutationError) => fail((mutationError as Error).message),
                  onSuccess: () => {
                    setCertName('');
                    setExpiryDate('');
                    setCertAmount('');
                    setDocId('');
                  },
                },
              );
            }}
          >
            <Field label="Name" value={certName} onChange={setCertName} />
            <Field label="Expiry date" value={expiryDate} onChange={setExpiryDate} placeholder="2027-03-31" />
            <Field label="Amount (string)" value={certAmount} onChange={setCertAmount} />
            <Field label="Document id" value={docId} onChange={setDocId} />
            <button type="submit">Add certificate</button>
          </form>
        )}
      </section>

      <section>
        <h2>Supporting documents</h2>
        {documents.isLoading && <Loading label="documents" />}
        {documents.data && documents.data.length === 0 && (
          <Empty message="No documents." />
        )}
        {documents.data && documents.data.length > 0 && (
          <ul>
            {documents.data.map((doc) => (
              <li key={doc.id}>
                #{doc.id} {doc.fileName} ({doc.mimeType}, {doc.sizeBytes} bytes)
              </li>
            ))}
          </ul>
        )}
        {canEdit && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              const input = event.currentTarget.querySelector('input[type="file"]');
              const file = input && (input as HTMLInputElement).files?.[0];
              if (!file) {
                fail('Choose a file first.');
                return;
              }
              setError(null);
              uploadDocument.mutate(file, {
                onError: (mutationError) => fail((mutationError as Error).message),
              });
            }}
          >
            <label>
              Document (PDF, PNG, JPEG)
              <input type="file" accept=".pdf,.png,.jpg,.jpeg" />
            </label>
            <button type="submit" disabled={uploadDocument.isPending}>
              Upload document
            </button>
          </form>
        )}
      </section>

      <section>
        <h2>Re-verify tenders</h2>
        <p>Company edits apply to the next evaluation. Re-verify a tender to re-run the Java engine on current data.</p>
        {tenders.isLoading && <Loading label="tenders" />}
        {tenders.data && tenders.data.items.length === 0 && (
          <Empty message="No tenders to re-verify." />
        )}
        {tenders.data && tenders.data.items.length > 0 && (
          <ul>
            {tenders.data.items.map((tender) => (
              <li key={tender.id}>
                <Link to={`/tenders/${tender.id}/workspace`}>{tender.title}</Link>{' '}
                <ReverifyButton
                  tenderId={tender.id}
                  jobId={reverifyJob[tender.id] ?? null}
                  onStarted={(jobId) =>
                    setReverifyJob((current) => ({ ...current, [tender.id]: jobId }))
                  }
                />
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

function ReverifyButton({
  tenderId,
  jobId,
  onStarted,
}: {
  tenderId: number;
  jobId: number | null;
  onStarted: (jobId: number) => void;
}) {
  const tender = useTender(tenderId);
  const latest = tender.data?.versions?.length
    ? tender.data.versions[tender.data.versions.length - 1].id
    : null;
  const reverify = useReverify(latest ?? 0);
  return (
    <span>
      <button
        type="button"
        disabled={latest === null || reverify.isPending}
        onClick={() =>
          reverify.mutate(undefined, {
            onSuccess: (data) => onStarted(data.jobId),
          })
        }
      >
        Re-Verify latest version
      </button>{' '}
      {reverify.isError && <span role="alert">{(reverify.error as Error).message}</span>}
      {jobId !== null && (
        <Link to={`/tenders/${tenderId}/workspace`}>job {jobId} running — open workspace</Link>
      )}
    </span>
  );
}
