export interface UserDto {
  id: number;
  email: string;
  role: string;
  companyId: number;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserDto;
}

export class ApiError extends Error {
  status: number;
  title: string;

  constructor(status: number, title: string, detail: string) {
    super(detail);
    this.status = status;
    this.title = title;
  }
}

const TOKEN_KEY = 'tenderops.token';

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY);
}

/**
 * fetch wrapper: Bearer injection, ProblemDetail parsing, 401 redirect to /login.
 * Money arrives as strings and is never computed with here (CL-06).
 */
export function redirectToLogin(): void {
  clearToken();
  if (window.location.pathname !== '/login') {
    window.location.assign('/login');
  }
}

export function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: 'Bearer ' + token } : {};
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((options.headers as Record<string, string>) ?? {}),
  };
  const token = getToken();
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401) {
    redirectToLogin();
    throw new ApiError(401, 'Unauthorized', 'Authentication required');
  }
  if (!response.ok) {
    let title = 'Request failed';
    let detail = 'Request failed with status ' + response.status;
    try {
      const problem = (await response.json()) as { title?: string; detail?: string };
      if (problem.title) title = problem.title;
      if (problem.detail) detail = problem.detail;
    } catch {
      // keep defaults
    }
    throw new ApiError(response.status, title, detail);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Multipart POST (file uploads): no JSON content type; the browser sets the boundary. */
export async function apiMultipart<T>(path: string, form: FormData): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  if (response.status === 401) {
    redirectToLogin();
    throw new ApiError(401, 'Unauthorized', 'Authentication required');
  }
  if (!response.ok) {
    let detail = 'Request failed with status ' + response.status;
    try {
      const problem = (await response.json()) as { detail?: string };
      if (problem.detail) detail = problem.detail;
    } catch {
      // keep default
    }
    throw new ApiError(response.status, 'Request failed', detail);
  }
  return (await response.json()) as T;
}

/** Binary GET (report downloads). */
export async function apiBlob(path: string): Promise<Blob> {
  const response = await fetch(path, { headers: authHeaders() });
  if (response.status === 401) {
    redirectToLogin();
    throw new ApiError(401, 'Unauthorized', 'Authentication required');
  }
  if (!response.ok) {
    throw new ApiError(response.status, 'Request failed', 'Download failed');
  }
  return await response.blob();
}
