import type { Draft, Session, ShortUrl } from "./types.ts";

export const SESSION_KEY = "shortify.session";
export const DRAFT_KEY = "shortify.draft";
export const emptyDraft: Draft = { originalUrl: "", customAlias: "", expiresAt: "" };

export function linkStatus(url: Pick<ShortUrl, "active" | "expiresAt">, now = Date.now()) {
  if (url.expiresAt && Date.parse(url.expiresAt) <= now) return "Expired";
  return url.active ? "Active" : "Inactive";
}

export function formatDate(value: string | null) {
  if (!value) return "No expiration";
  return new Intl.DateTimeFormat("en-GB", {
    day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
    second: "2-digit", timeZone: "UTC", timeZoneName: "short",
  }).format(new Date(value));
}

export function safeNext(value: string | null) {
  return value && /^(\/(?:dashboard|urls\/\d+(?:\/analytics)?)|\/)($|\?)/.test(value)
    && !value.includes("\\") ? value : "/dashboard";
}

export function readSession(raw: string | null, now = Date.now()): Session | null {
  try {
    const value = JSON.parse(raw || "null");
    if (typeof value?.accessToken !== "string" || !value.accessToken ||
        typeof value.email !== "string" || !Number.isFinite(value.expiresAt) || value.expiresAt <= now) return null;
    return { accessToken: value.accessToken, email: value.email, expiresAt: value.expiresAt };
  } catch { return null; }
}

export function tokenDeadline(token: string, expiresIn: number, now = Date.now()) {
  const declared = now + expiresIn * 1000;
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return typeof payload.exp === "number" ? Math.min(declared, payload.exp * 1000) : declared;
  } catch { return declared; }
}

export function readDraft(raw: string | null): Draft {
  try {
    const value = JSON.parse(raw || "null");
    return value && ["originalUrl", "customAlias", "expiresAt"].every(key => typeof value[key] === "string")
      ? { originalUrl: value.originalUrl, customAlias: value.customAlias, expiresAt: value.expiresAt }
      : { ...emptyDraft };
  } catch { return { ...emptyDraft }; }
}

const reservedAliases = new Set(["api", "auth", "login", "register", "logout", "dashboard", "analytics", "actuator", "error", "_next", "admin", "health", "metrics", "static", "assets", "favicon", "robots", "sitemap", "swagger-ui", "v3"]);

export function validateDraft(draft: Draft): string | null {
  try {
    const url = new URL(draft.originalUrl);
    if (!/^https?:\/\//i.test(draft.originalUrl) || !/^https?:$/.test(url.protocol) || !url.hostname || url.username || url.password ||
        /\s|[\x00-\x1f\x7f\\]|%(?:0[0-9a-f]|1[0-9a-f]|7f)|%(?![0-9a-f]{2})/i.test(draft.originalUrl)) throw new Error();
  } catch { return "Enter an absolute http:// or https:// destination without spaces or credentials."; }
  if (draft.customAlias && !/^[A-Za-z0-9_-]{3,32}$/.test(draft.customAlias)) {
    return "Use 3–32 letters, numbers, hyphens or underscores for the alias.";
  }
  if (reservedAliases.has(draft.customAlias.toLowerCase())) return "That alias is reserved. Choose another alias.";
  if (draft.expiresAt) {
    const date = new Date(draft.expiresAt);
    if (!Number.isFinite(date.getTime()) || date.getUTCFullYear() < 1 || date.getUTCFullYear() > 9999) return "Enter a valid expiration date and time (years 0001–9999).";
  }
  return null;
}

export function draftPayload(draft: Draft) {
  return {
    originalUrl: draft.originalUrl,
    ...(draft.customAlias ? { customAlias: draft.customAlias } : {}),
    ...(draft.expiresAt ? { expiresAt: new Date(draft.expiresAt).toISOString() } : {}),
  };
}

export function validateCredentials(email: string, password: string): string | null {
  if (!/^[^\s@]+@[^\s@]+$/.test(email.trim()) || email.trim().length > 254) return "Enter a valid email address (up to 254 characters).";
  if (!password.trim() || password.length < 8 || password.length > 72 || new TextEncoder().encode(password).length > 72) return "Use a password of 8–72 characters and no more than 72 UTF-8 bytes.";
  return null;
}
