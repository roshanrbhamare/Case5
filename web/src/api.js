// Same-origin '/api' behind nginx or the Vite proxy. A hosted build (e.g. Vercel) sets
// VITE_API_URL to the backend's own URL, so large uploads skip the host's proxy limits.
const BASE = (import.meta.env.VITE_API_URL ?? '/api').replace(/\/$/, '');
let token = null;
let onUnauthorized = () => {};

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.error?.message ?? `Request failed (${status})`);
    this.status = status;
    this.code = body?.error?.code;
    this.requestId = body?.error?.requestId;
    this.details = body?.error?.details;
  }
}

export function setToken(value) {
  token = value;
}

export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

/**
 * One readiness probe: 'ready' (backend and database up), 'starting' (backend answered but
 * isn't ready yet) or 'unreachable' (no answer within the timeout, e.g. a host waking from sleep).
 */
export async function serverStatus(timeoutMs = 8000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(BASE + '/health/ready', { signal: controller.signal, cache: 'no-store' });
    if (res.ok) return 'ready';
    // A hosting proxy's own error page while the app boots isn't the backend answering.
    const body = await res.json().catch(() => null);
    return body?.status === 'not_ready' ? 'starting' : 'unreachable';
  } catch {
    return 'unreachable';
  } finally {
    clearTimeout(timer);
  }
}

export async function api(path, { method = 'GET', body, headers = {}, raw } = {}) {
  let res;
  try {
    res = await fetch(BASE + path, {
      method,
      headers: {
        ...(body !== undefined ? { 'content-type': 'application/json' } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined),
    });
  } catch {
    throw new ApiError(0, { error: { message: "We couldn't reach the server. Check your connection and try again." } });
  }
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    if (res.status === 401 && token) onUnauthorized();
    throw new ApiError(res.status, data);
  }
  return data;
}
