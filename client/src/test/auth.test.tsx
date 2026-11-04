import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, clearToken, setToken } from '../api/client';
import ProtectedRoute from '../components/ProtectedRoute';
import Login from '../pages/Login';

describe('401 handling', () => {
  const assign = vi.fn();

  beforeEach(() => {
    assign.mockClear();
    // jsdom navigation is swapped for a mock (file-level env isolation)
    // @ts-expect-error test-only location shim
    delete window.location;
    window.location = { pathname: '/', assign } as unknown as Location;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearToken();
  });

  it('api() on 401 clears the token and redirects to /login', async () => {
    setToken('stale-token');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }));
    await expect(api('/api/v1/company')).rejects.toBeInstanceOf(ApiError);
    expect(localStorage.getItem('tenderops.token')).toBeNull();
    expect(assign).toHaveBeenCalledWith('/login');
  });

  it('ProtectedRoute redirects to /login without a token', () => {
    clearToken();
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/login" element={<p>login page</p>} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <p>secret</p>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText('login page')).toBeInTheDocument();
    expect(screen.queryByText('secret')).not.toBeInTheDocument();
  });

  it('failed login shows the error state', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 401,
        ok: false,
        json: () => Promise.resolve({ title: 'Unauthorized', detail: 'Bad credentials' }),
      }),
    );
    const queryClient = new QueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <Login />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText(/email/i), { target: { value: 'a@b.c' } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: /^login$/i }));
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
  });
});
