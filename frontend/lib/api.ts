export const API_BASE = (process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080").replace(/\/$/, "");

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(message: string, status = 0, code = "NETWORK_ERROR") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export function isAbort(error: unknown) {
  return error instanceof Error && error.name === "AbortError";
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Something went wrong. Please try again.";
}

export async function fetchApi<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  let response: Response;
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init, cache: "no-store", credentials: "omit", headers,
    });
  } catch (error) {
    if (isAbort(error)) throw error;
    throw new ApiError("Cannot reach the server. Check your connection and try again.");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    const retry = response.headers.get("Retry-After");
    const message = body?.message || `Request failed (${response.status}). Please try again.`;
    throw new ApiError(`${message}${retry ? ` Try again in ${retry} seconds.` : ""}`, response.status, body?.code || "REQUEST_FAILED");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
