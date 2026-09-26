import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { draftPayload, emptyDraft, formatDate, linkStatus, readDraft, readSession, safeNext, tokenDeadline, validateCredentials, validateDraft } from "../../lib/helpers.ts";

const now = Date.parse("2026-09-26T12:00:00Z");
const draft = { originalUrl: "https://example.com/a?b=c#section", customAlias: "", expiresAt: "" };
const jwt = (exp: number) => `header.${Buffer.from(JSON.stringify({ exp })).toString("base64url")}.signature`;

describe("link status and dates", () => {
  it("identifies active and inactive links", () => {
    assert.equal(linkStatus({ active: true, expiresAt: null }, now), "Active");
    assert.equal(linkStatus({ active: false, expiresAt: null }, now), "Inactive");
  });
  it("marks the exact expiration boundary expired, including reactivated links", () => {
    assert.equal(linkStatus({ active: true, expiresAt: new Date(now).toISOString() }, now), "Expired");
    assert.equal(linkStatus({ active: false, expiresAt: new Date(now - 1).toISOString() }, now), "Expired");
    assert.equal(linkStatus({ active: true, expiresAt: new Date(now + 1).toISOString() }, now), "Active");
  });
  it("formats timestamps in UTC with a no-expiration fallback", () => {
    assert.equal(formatDate(null), "No expiration");
    assert.match(formatDate("2026-09-26T14:00:00+02:00"), /26 Sept 2026.*12:00:00 UTC/);
  });
});

describe("safe authentication return routes", () => {
  for (const path of ["/", "/dashboard", "/dashboard?page=2", "/urls/23", "/urls/23/analytics"]) {
    it(`keeps ${path}`, () => assert.equal(safeNext(path), path));
  }
  for (const path of [null, "https://evil.example", "//evil.example", "/\\evil.example", "/login", "/dashboard/elsewhere", "/urls/nope", "/urls/2/analytics/evil", "/dashboardevil"]) {
    it(`rejects ${String(path)}`, () => assert.equal(safeNext(path), "/dashboard"));
  }
});

describe("backend-token session helpers", () => {
  it("restores only live, well-formed sessions", () => {
    const session = { accessToken: "backend-token", email: "member@example.com", expiresAt: now + 1000 };
    assert.deepEqual(readSession(JSON.stringify(session), now), session);
    assert.equal(readSession(JSON.stringify({ ...session, expiresAt: now }), now), null);
    assert.equal(readSession(JSON.stringify({ ...session, accessToken: "" }), now), null);
    assert.equal(readSession(JSON.stringify({ ...session, email: null }), now), null);
    assert.equal(readSession(JSON.stringify({ ...session, expiresAt: "later" }), now), null);
  });
  it("rejects corrupted storage without throwing", () => {
    for (const raw of [null, "{broken", "[]", "null", "42"]) assert.equal(readSession(raw, now), null);
  });
  it("uses the earlier of JWT expiry and expiresIn", () => {
    assert.equal(tokenDeadline(jwt(now / 1000 + 30), 3600, now), now + 30_000);
    assert.equal(tokenDeadline(jwt(now / 1000 + 3600), 30, now), now + 30_000);
    assert.equal(tokenDeadline(jwt(now / 1000 - 1), 3600, now), now - 1000);
  });
  it("falls back to expiresIn when a token cannot be decoded", () => {
    assert.equal(tokenDeadline("opaque-backend-token", 60, now), now + 60_000);
  });
});

describe("guest drafts and strict creation payloads", () => {
  it("restores only draft fields and ignores unexpected stored fields", () => {
    assert.deepEqual(readDraft(JSON.stringify({ ...draft, owner: 3 })), draft);
    assert.deepEqual(readDraft("{broken"), emptyDraft);
    assert.deepEqual(readDraft(JSON.stringify({ ...draft, customAlias: 3 })), emptyDraft);
    assert.deepEqual(readDraft(null), emptyDraft);
  });
  it("accepts HTTP(S), localhost, IPs, and case-sensitive aliases", () => {
    for (const originalUrl of [draft.originalUrl, "http://localhost:8081/path", "http://127.0.0.1/a", "https://[::1]/", "https://xn--bcher-kva.example/a"]) {
      assert.equal(validateDraft({ ...draft, originalUrl, customAlias: "My_link-1" }), null);
    }
  });
  it("rejects invalid destinations and unsafe URI characters", () => {
    for (const originalUrl of ["", "example.com", "https:example.com", "javascript:alert(1)", "ftp://example.com", "https://user:pass@example.com", "https://example.com/a b", "https://example.com/%0a", "https://example.com/%7F", "https://example.com/%oops", "https://example.com:99999", "https://example.com\\path"]) {
      assert.ok(validateDraft({ ...draft, originalUrl }), originalUrl);
    }
  });
  it("enforces alias length, ASCII alphabet, and reserved names without trimming", () => {
    for (const customAlias of ["ab", "a".repeat(33), "has space", "my.alias", "mýalias", "API", "Dashboard", "_next", " abc"]) {
      assert.ok(validateDraft({ ...draft, customAlias }), customAlias);
    }
    assert.equal(validateDraft({ ...draft, customAlias: "a".repeat(32) }), null);
  });
  it("accepts past expiration but rejects invalid dates and unsupported years", () => {
    assert.equal(validateDraft({ ...draft, expiresAt: "2000-01-01T12:00" }), null);
    assert.ok(validateDraft({ ...draft, expiresAt: "not a date" }));
    assert.ok(validateDraft({ ...draft, expiresAt: "0000-01-01T12:00:00Z" }));
  });
  it("omits blank optional fields and never sends owner or active properties", () => {
    assert.deepEqual(draftPayload(draft), { originalUrl: draft.originalUrl });
    assert.deepEqual(draftPayload({ ...draft, customAlias: "MyLink", expiresAt: "2027-01-01T12:00:00+02:00" }), {
      originalUrl: draft.originalUrl, customAlias: "MyLink", expiresAt: "2027-01-01T10:00:00.000Z",
    });
  });
});

describe("credentials validation", () => {
  it("accepts normalized emails and leaves password spaces intact", () => {
    assert.equal(validateCredentials(" Member@example.com ", "  password  "), null);
    assert.equal(validateCredentials("member@example.com", "a".repeat(72)), null);
    assert.equal(validateCredentials("member@example.com", "é".repeat(36)), null);
  });
  it("rejects blank, short, oversized, and UTF-8-byte-overflow passwords", () => {
    for (const password of ["        ", "short", "a".repeat(73), "é".repeat(37), "😀".repeat(19)]) {
      assert.ok(validateCredentials("member@example.com", password));
    }
  });
  it("rejects malformed and oversized emails", () => {
    for (const email of ["not-an-email", "a@@example.com", "a b@example.com", `${"a".repeat(250)}@example.com`]) {
      assert.ok(validateCredentials(email, "password"));
    }
  });
});
