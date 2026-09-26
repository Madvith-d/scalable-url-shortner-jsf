import assert from "node:assert/strict";
import { afterEach, describe, it, mock } from "node:test";
import { API_BASE, ApiError, errorMessage, fetchApi, isAbort } from "../../lib/api.ts";

afterEach(() => mock.restoreAll());

describe("shared backend transport", () => {
  it("sends bearer authorization, JSON headers, no cookies, and no cache", async () => {
    const spy = mock.method(globalThis, "fetch", async () => Response.json({ id: 7 }));
    const result = await fetchApi<{ id: number }>("/api/urls", {
      method: "POST", body: JSON.stringify({ originalUrl: "https://example.com" }), headers: new Headers({ "X-Test": "present" }),
    }, "backend-issued-token");
    assert.deepEqual(result, { id: 7 });
    const [url, init] = spy.mock.calls[0].arguments as unknown as [string, RequestInit];
    assert.equal(url, `${API_BASE}/api/urls`);
    assert.equal(init.credentials, "omit");
    assert.equal(init.cache, "no-store");
    const headers = new Headers(init.headers);
    assert.equal(headers.get("Authorization"), "Bearer backend-issued-token");
    assert.equal(headers.get("Content-Type"), "application/json");
    assert.equal(headers.get("Accept"), "application/json");
    assert.equal(headers.get("X-Test"), "present");
  });
  it("does not attach a token to login requests", async () => {
    const spy = mock.method(globalThis, "fetch", async () => Response.json({ email: "member@example.com" }));
    await fetchApi("/api/auth/login", { method: "POST", body: "{}" });
    const [, init] = spy.mock.calls[0].arguments as unknown as [string, RequestInit];
    assert.equal(new Headers(init.headers).has("Authorization"), false);
  });
  it("preserves backend error codes and messages for 401 handling", async () => {
    mock.method(globalThis, "fetch", async () => Response.json({ code: "UNAUTHORIZED", message: "Authentication required" }, { status: 401 }));
    await assert.rejects(fetchApi("/api/urls", {}, "expired"), (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 401);
      assert.equal(error.code, "UNAUTHORIZED");
      assert.equal(error.message, "Authentication required");
      return true;
    });
  });
  it("includes retry information for rate limits", async () => {
    mock.method(globalThis, "fetch", async () => Response.json({ code: "RATE_LIMITED", message: "Too many requests." }, { status: 429, headers: { "Retry-After": "30" } }));
    await assert.rejects(fetchApi("/api/auth/login"), /Try again in 30 seconds/);
  });
  it("handles non-JSON errors without exposing response text", async () => {
    mock.method(globalThis, "fetch", async () => new Response("<html>proxy internals</html>", { status: 502 }));
    await assert.rejects(fetchApi("/api/urls"), /Request failed \(502\)/);
  });
  it("handles empty 204 responses", async () => {
    mock.method(globalThis, "fetch", async () => new Response(null, { status: 204 }));
    assert.equal(await fetchApi("/api/urls/7", { method: "DELETE" }, "token"), undefined);
  });
  it("turns connection failures into a useful message", async () => {
    mock.method(globalThis, "fetch", async () => { throw new TypeError("Failed to fetch"); });
    await assert.rejects(fetchApi("/api/urls"), /Cannot reach the server/);
  });
  it("preserves cancellation rather than reporting a network error", async () => {
    const abort = new DOMException("Aborted", "AbortError");
    mock.method(globalThis, "fetch", async () => { throw abort; });
    await assert.rejects(fetchApi("/api/urls"), error => error === abort);
    assert.equal(isAbort(abort), true);
    assert.equal(isAbort(new Error("oops")), false);
  });
  it("uses safe fallbacks for unknown errors", () => {
    assert.equal(errorMessage(new Error("Helpful message")), "Helpful message");
    assert.equal(errorMessage(null), "Something went wrong. Please try again.");
  });
});
