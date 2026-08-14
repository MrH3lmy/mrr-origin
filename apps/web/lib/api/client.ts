import { ApiError } from "./errors";

export interface ApiClient {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body?: unknown): Promise<T>;
  put<T>(path: string, body?: unknown): Promise<T>;
  del<T = void>(path: string): Promise<T>;
}

async function parseResponse<T>(response: Response): Promise<T> {
  const text = await response.text();
  const parsed: unknown = text ? JSON.parse(text) : undefined;

  if (!response.ok) {
    const body =
      (parsed as { code?: string; message?: string } | undefined) ?? {};
    throw new ApiError(
      response.status,
      body.code ?? "unknown_error",
      body.message ?? `Request failed with status ${response.status}`,
    );
  }

  return parsed as T;
}

/**
 * Shared fetch-based client used by both the browser client (which calls the same-origin
 * `/api/proxy/*` route so the session token stays server-only) and the server client (which calls
 * the backend API directly, attaching the bearer token itself).
 */
export function buildClient(
  basePath: string,
  extraHeaders: () => HeadersInit,
): ApiClient {
  async function request<T>(
    method: string,
    path: string,
    body?: unknown,
  ): Promise<T> {
    const response = await fetch(`${basePath}${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { "content-type": "application/json" } : {}),
        ...extraHeaders(),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      cache: "no-store",
    });
    return parseResponse<T>(response);
  }

  return {
    get: <T>(path: string) => request<T>("GET", path),
    post: <T>(path: string, body?: unknown) =>
      request<T>("POST", path, body ?? {}),
    put: <T>(path: string, body?: unknown) =>
      request<T>("PUT", path, body ?? {}),
    del: <T = void>(path: string) => request<T>("DELETE", path),
  };
}

/** Client for use inside client components: same-origin, cookie-authenticated proxy. */
export function createBrowserClient(): ApiClient {
  return buildClient("/api/proxy", () => ({}));
}
