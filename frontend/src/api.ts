/**
 * API client — centralised fetch wrapper with auth token management.
 */

const BASE = import.meta.env.VITE_API_URL || "http://localhost:8000";

let token: string | null = localStorage.getItem("ontoboard_token");

export function setToken(t: string | null) {
  token = t;
  if (t) localStorage.setItem("ontoboard_token", t);
  else localStorage.removeItem("ontoboard_token");
}

export function getToken() {
  return token;
}

export async function api(path: string, opts: RequestInit = {}): Promise<Response> {
  const headers: Record<string, string> = {
    ...(opts.headers as Record<string, string> || {}),
  };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (!(opts.body instanceof FormData)) {
    headers["Content-Type"] = headers["Content-Type"] || "application/json";
  }
  return fetch(`${BASE}${path}`, { ...opts, headers });
}

export async function apiJson<T = unknown>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await api(path, opts);
  if (!res.ok) {
    const body = await res.json().catch(() => ({ detail: res.statusText }));
    throw new ApiError(res.status, body.detail || "Request failed");
  }
  return res.json();
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}
