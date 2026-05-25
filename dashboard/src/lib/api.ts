import { toast } from "sonner";

const API_URL_KEY = "nimbus_api_url";
const TOKEN_KEY = "nimbus_api_token";
const SESSION_TOKEN_KEY = "nimbus_session_token";
export const AUTH_KIND_KEY = "nimbus_auth_kind";

/**
 * Session tokens live in `sessionStorage`: they survive a page refresh (so
 * the user isn't bounced to /login after Cmd-R) but **not** a closed tab and
 * are not readable from other tabs. API tokens are long-lived, operator-chosen
 * credentials and stay in-memory for the session's lifetime — we deliberately
 * never persist them.
 */
let inMemoryToken = "";
function readPersistedSessionToken(): string {
  if (typeof window === "undefined") return "";
  try {
    return sessionStorage.getItem(SESSION_TOKEN_KEY) || "";
  } catch {
    return "";
  }
}

export type AuthKind = "api-token" | "user-session";

export interface UserInfo {
  uuid: string;
  name: string;
  permissions: string[];
  isAdmin: boolean;
  totpEnabled: boolean;
}

/**
 * Extra options accepted by `apiFetch` on top of the standard `RequestInit`.
 *
 * `silent` suppresses the global error toast — use for polling requests and
 * anywhere the caller shows its own error UI (e.g. inside a form, or the
 * `ErrorState` rendered by `PageShell`).
 */
export interface ApiFetchOptions extends RequestInit {
  silent?: boolean;
}

function toastApiError(status: number, message: string) {
  // 401 is already handled via redirect; don't also toast.
  if (status === 401) return;
  if (status === 403) {
    toast.error("Forbidden", { description: message });
    return;
  }
  if (status >= 500) {
    toast.error("Server error", {
      description: message || `The controller returned ${status}.`,
    });
    return;
  }
  if (status >= 400) {
    toast.error("Request failed", { description: message });
    return;
  }
  // Network / CORS failure — no status.
  toast.error("Network error", { description: message });
}

export function getApiUrl(): string {
  if (typeof window === "undefined") return "";
  return localStorage.getItem(API_URL_KEY) || "";
}

export function getToken(): string {
  if (typeof window === "undefined") return "";
  // Best-effort cleanup for legacy persisted token value.
  localStorage.removeItem(TOKEN_KEY);
  if (inMemoryToken) return inMemoryToken;
  // First call after a page refresh — rehydrate the session token from
  // sessionStorage. API-token credentials don't persist and will stay empty
  // here, forcing the auth bootstrap to redirect to /login as before.
  const persisted = readPersistedSessionToken();
  if (persisted) inMemoryToken = persisted;
  return inMemoryToken;
}

export function getAuthKind(): AuthKind | null {
  if (typeof window === "undefined") return null;
  const raw = localStorage.getItem(AUTH_KIND_KEY);
  if (raw === "api-token" || raw === "user-session") return raw;
  return null;
}

function persistSessionToken(token: string) {
  try {
    sessionStorage.setItem(SESSION_TOKEN_KEY, token);
  } catch {
    // sessionStorage unavailable (private mode, etc.) — fall back to in-memory only.
  }
}

function clearPersistedSessionToken() {
  try {
    sessionStorage.removeItem(SESSION_TOKEN_KEY);
  } catch {
    // ignore
  }
}

export function setCredentials(apiUrl: string, token: string) {
  localStorage.setItem(API_URL_KEY, apiUrl);
  inMemoryToken = token;
  localStorage.removeItem(TOKEN_KEY);
  clearPersistedSessionToken();
}

/** Stores an API-token style credential (long-lived controller token). */
export function setApiTokenCredentials(apiUrl: string, token: string) {
  localStorage.setItem(API_URL_KEY, apiUrl);
  inMemoryToken = token;
  localStorage.removeItem(TOKEN_KEY);
  // API tokens are never persisted — user re-enters after a tab close.
  clearPersistedSessionToken();
  localStorage.setItem(AUTH_KIND_KEY, "api-token");
}

/** Stores a user-session style credential (dashboard login token). */
export function setUserSessionCredentials(apiUrl: string, token: string) {
  localStorage.setItem(API_URL_KEY, apiUrl);
  inMemoryToken = token;
  localStorage.removeItem(TOKEN_KEY);
  // Session tokens survive a page refresh inside the same tab via
  // sessionStorage — cleared on tab close, not readable from other tabs.
  persistSessionToken(token);
  localStorage.setItem(AUTH_KIND_KEY, "user-session");
}

export function clearCredentials() {
  inMemoryToken = "";
  localStorage.removeItem(API_URL_KEY);
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(AUTH_KIND_KEY);
  clearPersistedSessionToken();
}

export function isAuthenticated(): boolean {
  return !!getApiUrl() && !!getToken();
}

/**
 * Whether requests need to go through the server-side proxy.
 * This is true when the dashboard is served over HTTPS but the controller
 * is plain HTTP — browsers block such mixed-content requests.
 */
function needsProxy(): boolean {
  if (typeof window === "undefined") return false;
  const dashboardHttps = window.location.protocol === "https:";
  const controllerHttp = getApiUrl().startsWith("http://");
  return dashboardHttps && controllerHttp;
}

/**
 * Build the fetch URL and headers, routing through the server-side proxy
 * when mixed-content would block the request.
 */
function buildRequest(
  path: string,
  options: RequestInit = {}
): { url: string; init: RequestInit } {
  const apiUrl = getApiUrl();
  const token = getToken();

  if (needsProxy()) {
    // Route through /api/proxy/... on the same origin
    const url = `/api/proxy${path}`;
    return {
      url,
      init: {
        ...options,
        headers: {
          "Content-Type": "application/json",
          "X-Nimbus-Controller": apiUrl,
          "X-Nimbus-Token": token,
          ...options.headers,
        },
      },
    };
  }

  // Direct connection (same network / development)
  return {
    url: `${apiUrl}${path}`,
    init: {
      ...options,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        ...options.headers,
      },
    },
  };
}

export async function apiFetch<T = unknown>(
  path: string,
  options: ApiFetchOptions = {}
): Promise<T> {
  const { silent, ...requestOptions } = options;
  const { url, init } = buildRequest(path, requestOptions);

  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    // Aborts are expected (unmount, refetch); let them propagate without toasts.
    const err = e instanceof Error ? e : new Error(String(e));
    if (err.name !== "AbortError" && !silent) {
      toastApiError(0, err.message || "Failed to reach controller");
    }
    throw err;
  }

  if (res.status === 401) {
    clearCredentials();
    window.location.href = "/login";
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    const message = body.error || body.message || `API error: ${res.status}`;
    if (!silent) toastApiError(res.status, message);
    throw new Error(message);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

/**
 * Silent GET /api/auth/me — resolves to the current user or null on any
 * failure (missing creds, 401, network, etc.). Never throws, never toasts.
 * Used by the auth bootstrap + silent refresh loop.
 */
export async function fetchMe(): Promise<UserInfo | null> {
  if (!getApiUrl() || !getToken()) return null;
  try {
    const { url, init } = buildRequest("/api/auth/me", { method: "GET" });
    const res = await fetch(url, init);
    if (!res.ok) return null;
    return (await res.json()) as UserInfo;
  } catch {
    return null;
  }
}

/**
 * Upload a file as raw request body (streamed, no multipart buffering).
 * In proxy mode, automatically uses chunked upload to bypass Vercel's body size limit.
 * Parameters should be passed as query params in the path.
 */
export async function apiUpload<T = unknown>(
  path: string,
  file: File | Blob,
  onProgress?: (uploaded: number, total: number) => void
): Promise<T> {
  // In proxy mode, use chunked upload to bypass hosting body size limits
  if (needsProxy()) {
    return apiChunkedUpload<T>(path, file, onProgress);
  }

  const apiUrl = getApiUrl();
  const token = getToken();

  const res = await fetch(`${apiUrl}${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/octet-stream",
    },
    body: file,
  });

  if (res.status === 401) {
    clearCredentials();
    window.location.href = "/login";
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || body.error || `Upload failed: ${res.status}`);
  }

  return res.json();
}

const CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB — safe for Vercel's body limit

/**
 * Chunked upload through the server-side proxy.
 * 1. POST /api/modpacks/upload/init?target=group|dedicated → get uploadId
 * 2. POST /api/modpacks/upload/chunk (sequential, 4MB each) → append on server
 * 3. POST {finalize endpoint matching target} → trigger import
 *
 * The init/chunk endpoints live under /api/modpacks/ regardless of target;
 * the finalize endpoint differs (group → /api/modpacks/upload/finalize,
 * dedicated → /api/dedicated/modpack/upload/finalize). The original path tells
 * us which finalize to call.
 *
 * File.slice() is zero-copy — no RAM usage on the client.
 */
async function apiChunkedUpload<T = unknown>(
  path: string,
  file: File | Blob,
  onProgress?: (uploaded: number, total: number) => void
): Promise<T> {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
  const fileName = file instanceof File ? file.name : "upload.zip";

  // Detect upload target from the original path. Dedicated single-shot uploads
  // hit /api/dedicated/modpack/upload, group uploads hit /api/modpacks/upload.
  const isDedicated = path.startsWith("/api/dedicated/modpack/upload");
  const target = isDedicated ? "dedicated" : "group";
  const finalizePath = isDedicated
    ? "/api/dedicated/modpack/upload/finalize"
    : "/api/modpacks/upload/finalize";

  // Extract query params from the original path (e.g. groupName/type/memory
  // for groups, name/port/memory/proxyEnabled for dedicated).
  const [, queryString] = path.split("?");
  const originalParams = new URLSearchParams(queryString || "");

  // Step 1: Initialize chunked upload
  const initParams = new URLSearchParams({
    fileName,
    totalChunks: String(totalChunks),
    target,
  });
  const init = await apiFetch<{ uploadId: string; totalChunks: number }>(
    `/api/modpacks/upload/init?${initParams.toString()}`,
    { method: "POST" }
  );

  // Step 2: Send chunks sequentially
  for (let i = 0; i < totalChunks; i++) {
    const start = i * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, file.size);
    const chunk = file.slice(start, end);

    const chunkParams = new URLSearchParams({
      uploadId: init.uploadId,
      index: String(i),
    });

    const { url, init: fetchInit } = buildRequest(
      `/api/modpacks/upload/chunk?${chunkParams.toString()}`,
      { method: "POST" }
    );

    // Override Content-Type for binary chunk
    const headers = { ...fetchInit.headers } as Record<string, string>;
    headers["Content-Type"] = "application/octet-stream";

    const res = await fetch(url, {
      ...fetchInit,
      headers,
      body: chunk,
    });

    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.error || `Chunk ${i} upload failed: ${res.status}`);
    }

    onProgress?.(end, file.size);
  }

  // Step 3: Finalize — pass original params (groupName/type for group,
  // name/port/memory/proxyEnabled for dedicated). Server validates target match.
  originalParams.set("uploadId", init.uploadId);
  return apiFetch<T>(
    `${finalizePath}?${originalParams.toString()}`,
    { method: "POST" }
  );
}

/**
 * Exchange the current session bearer for a short-lived (30s), single-use
 * WebSocket ticket. API-token principals skip this — they use their master
 * token directly. Returns the token that should be placed in the `?token=`
 * query param: a `wt_…` ticket for session users, the bearer itself for
 * API-token users, or an empty string if no credentials are present.
 */
export async function fetchWsToken(): Promise<string> {
  const token = getToken();
  if (!token) return "";
  const kind = getAuthKind();
  if (kind !== "user-session") return token;
  try {
    const res = await apiFetch<{ ticket: string; expiresAt: number }>(
      "/api/auth/ws-ticket",
      { method: "GET", silent: true }
    );
    return res.ticket;
  } catch {
    // Fall back to the bearer if the controller doesn't expose the ticket
    // endpoint yet (mixed-version rollouts). Session WS connections still
    // work, just without the URL-exposure hardening.
    return token;
  }
}

export async function apiWebSocket(path: string): Promise<WebSocket> {
  const apiUrl = getApiUrl();
  const token = await fetchWsToken();
  const wsUrl = apiUrl.replace(/^http/, "ws");
  return new WebSocket(`${wsUrl}${path}?token=${encodeURIComponent(token)}`);
}

/**
 * EventSource-based WebSocket bridge for proxy mode.
 * Uses SSE (GET /api/proxy-ws/...) to receive and POST to send.
 * Returns an object with the same shape as apiWebSocketReconnect.
 */
export function apiProxyWebSocket(
  path: string,
  handlers: {
    onOpen?: () => void;
    onMessage?: (event: MessageEvent) => void;
    onClose?: () => void;
    onError?: (event: Event) => void;
  },
  options?: { maxRetries?: number; baseDelay?: number }
): { send: (message: string) => Promise<void>; cleanup: () => void } {
  const apiUrl = getApiUrl();
  const maxRetries = options?.maxRetries ?? 10;
  const baseDelay = options?.baseDelay ?? 1000;
  let eventSource: EventSource | null = null;
  let retries = 0;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  async function connect() {
    if (stopped) return;

    // Fetch a fresh single-use ticket per (re)connect. Sessions exchange
    // their bearer for a `wt_…` ticket so the long-lived token never lands
    // in a URL query param; API tokens pass straight through.
    const token = await fetchWsToken();
    if (stopped) return;

    // EventSource doesn't support custom headers, so we pass credentials as query params
    const params = new URLSearchParams({
      controller: apiUrl,
      token: token,
    });
    eventSource = new EventSource(`/api/proxy-ws${path}?${params.toString()}`);

    eventSource.addEventListener("open", () => {
      retries = 0;
      handlers.onOpen?.();
    });

    eventSource.onmessage = (event) => {
      handlers.onMessage?.(event);
    };

    eventSource.addEventListener("close", () => {
      handlers.onClose?.();
      eventSource?.close();
      reconnect();
    });

    eventSource.addEventListener("error", (event) => {
      handlers.onError?.(event);
      eventSource?.close();
      reconnect();
    });
  }

  function reconnect() {
    if (stopped || retries >= maxRetries) return;
    const delay = Math.min(baseDelay * Math.pow(2, retries), 30000);
    retries++;
    timer = setTimeout(() => { void connect(); }, delay);
  }

  void connect();

  return {
    send: async (message: string) => {
      // POST side uses the bearer in a header (not the URL), so it's safe
      // to send the long-lived token directly.
      await fetch(`/api/proxy-ws${path}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Nimbus-Controller": apiUrl,
          "X-Nimbus-Token": getToken(),
        },
        body: JSON.stringify({ message }),
      });
    },
    cleanup: () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      eventSource?.close();
    },
  };
}

/**
 * WebSocket with automatic reconnection on disconnect.
 * In proxy mode, transparently uses SSE bridge instead.
 * Returns a cleanup function to stop reconnecting.
 */
export function apiWebSocketReconnect(
  path: string,
  handlers: {
    onOpen?: (ws: WebSocket) => void;
    onMessage?: (event: MessageEvent) => void;
    onClose?: () => void;
    onError?: (event: Event) => void;
  },
  options?: { maxRetries?: number; baseDelay?: number }
): { getSocket: () => WebSocket | null; send: (message: string) => void; cleanup: () => void } {
  if (needsProxy()) {
    const bridge = apiProxyWebSocket(
      path,
      {
        onOpen: () => handlers.onOpen?.(null as unknown as WebSocket),
        onMessage: handlers.onMessage,
        onClose: handlers.onClose,
        onError: handlers.onError,
      },
      options
    );
    return {
      getSocket: () => null,
      send: (msg: string) => { bridge.send(msg); },
      cleanup: bridge.cleanup,
    };
  }

  const maxRetries = options?.maxRetries ?? 10;
  const baseDelay = options?.baseDelay ?? 1000;
  let ws: WebSocket | null = null;
  let retries = 0;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  async function connect() {
    if (stopped) return;
    ws = await apiWebSocket(path);
    if (stopped) {
      ws.close();
      return;
    }

    ws.onopen = () => {
      retries = 0;
      handlers.onOpen?.(ws!);
    };

    ws.onmessage = (event) => handlers.onMessage?.(event);

    ws.onerror = (event) => handlers.onError?.(event);

    ws.onclose = () => {
      handlers.onClose?.();
      if (stopped || retries >= maxRetries) return;
      const delay = Math.min(baseDelay * Math.pow(2, retries), 30000);
      retries++;
      timer = setTimeout(() => { void connect(); }, delay);
    };
  }

  void connect();

  return {
    getSocket: () => ws,
    send: (msg: string) => { ws?.send(msg); },
    cleanup: () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      ws?.close();
    },
  };
}
