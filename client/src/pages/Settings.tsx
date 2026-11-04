import { useState } from 'react';
import { useMe, useSetRole, useUsers } from '../api/queries';
import { Empty, ErrorState, Loading } from '../components/states';

export default function Settings() {
  const me = useMe();
  const users = useUsers();
  const setRole = useSetRole();
  const [error, setError] = useState<string | null>(null);

  if (me?.role !== 'ADMIN') {
    return (
      <main>
        <h1>Settings</h1>
        <p role="alert">Settings are ADMIN only.</p>
      </main>
    );
  }

  return (
    <main>
      <h1>Settings</h1>
      {error && <p role="alert">{error}</p>}
      {users.isLoading && <Loading label="users" />}
      {users.isError && (
        <ErrorState
          message={(users.error as Error)?.message ?? 'Users failed to load.'}
          onRetry={() => void users.refetch()}
        />
      )}
      {users.data && users.data.length === 0 && <Empty message="No users." />}
      {users.data && users.data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Email</th>
              <th>Role</th>
              <th>Change role</th>
            </tr>
          </thead>
          <tbody>
            {users.data.map((user) => (
              <tr key={user.id}>
                <td>{user.email}</td>
                <td>{user.role}</td>
                <td>
                  <select
                    aria-label={`Role for ${user.email}`}
                    value={user.role}
                    disabled={setRole.isPending || user.id === me?.id}
                    title={user.id === me?.id ? 'You cannot change your own role' : undefined}
                    onChange={(event) => {
                      setError(null);
                      setRole.mutate(
                        { id: user.id, role: event.target.value },
                        { onError: (mutationError) => setError((mutationError as Error).message) },
                      );
                    }}
                  >
                    <option value="ADMIN">ADMIN</option>
                    <option value="BID_MANAGER">BID_MANAGER</option>
                    <option value="VIEWER">VIEWER</option>
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}
