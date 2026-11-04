import type { ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { clearToken } from '../api/client';
import { useMe } from '../api/queries';

export default function Layout({ children }: { children: ReactNode }) {
  const me = useMe();
  const navigate = useNavigate();
  return (
    <div>
      <header>
        <nav aria-label="Primary">
          <Link to="/">Dashboard</Link> | <Link to="/tenders">Tenders</Link> |{' '}
          <Link to="/company">Company</Link> | <Link to="/audit">Audit</Link>
          {me?.role === 'ADMIN' && (
            <>
              {' | '}
              <Link to="/settings">Settings</Link>
            </>
          )}
        </nav>
        <p>
          {me ? `${me.email} (${me.role})` : 'Not signed in'}{' '}
          {me && (
            <button
              type="button"
              onClick={() => {
                clearToken();
                navigate('/login');
              }}
            >
              Logout
            </button>
          )}
        </p>
      </header>
      <hr />
      {children}
    </div>
  );
}
