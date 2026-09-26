"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { useAuth } from "./auth-provider";
import { CopyButton, ExternalLink, StatusBadge } from "./link-ui";
import { errorMessage, isAbort } from "@/lib/api";
import { DRAFT_KEY, draftPayload, emptyDraft, readDraft, validateDraft } from "@/lib/helpers";
import type { Draft, ShortUrl } from "@/lib/types";

export function CreateForm({ onCreated }: { onCreated?: (url: ShortUrl) => void }) {
  const { session, request } = useAuth();
  const router = useRouter();
  const id = useId();
  const [draft, setDraft] = useState<Draft>({ ...emptyDraft });
  const [error, setError] = useState("");
  const [storageWarning, setStorageWarning] = useState("");
  const [busy, setBusy] = useState(false);
  const [created, setCreated] = useState<ShortUrl | null>(null);
  const result = useRef<HTMLDivElement>(null);
  const inFlight = useRef(false);
  useEffect(() => {
    try { setDraft(readDraft(sessionStorage.getItem(DRAFT_KEY))); } catch { /* The form can be used without storage. */ }
  }, []);
  useEffect(() => { if (created) result.current?.focus(); }, [created]);

  function update(field: keyof Draft, value: string) {
    const next = { ...draft, [field]: value };
    setDraft(next);
    setError("");
    if (!session) {
      try { sessionStorage.setItem(DRAFT_KEY, JSON.stringify(next)); }
      catch { setStorageWarning("Browser storage is unavailable. Sign in first so your draft is not lost."); }
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;
    setError("");
    const validation = validateDraft(draft);
    if (validation) { setError(validation); return; }
    if (!session) {
      try { sessionStorage.setItem(DRAFT_KEY, JSON.stringify(draft)); }
      catch { setError("Your browser is blocking draft storage. Sign in first, then enter your link."); return; }
      router.push("/login?next=%2Fdashboard&draft=1");
      return;
    }
    inFlight.current = true;
    setBusy(true);
    try {
      const url = await request<ShortUrl>("/api/urls", { method: "POST", body: JSON.stringify(draftPayload(draft)) });
      setCreated(url);
      setDraft({ ...emptyDraft });
      try { sessionStorage.removeItem(DRAFT_KEY); } catch { /* No persisted draft to clear. */ }
      onCreated?.(url);
    } catch (failure) { if (!isAbort(failure)) setError(errorMessage(failure)); }
    finally { setBusy(false); inFlight.current = false; }
  }

  return (
    <>
      <form className="create-form" onSubmit={submit} aria-busy={busy}>
        <div className="field">
          <label htmlFor={`${id}-url`}>Destination URL <span className="required-label">Required</span></label>
          <input
            id={`${id}-url`}
            type="url"
            inputMode="url"
            placeholder="https://example.com/your-page"
            value={draft.originalUrl}
            onChange={event => update("originalUrl", event.target.value)}
            required
            aria-describedby={`${id}-url-help`}
            disabled={busy}
          />
          <p className="field-hint" id={`${id}-url-help`}>The full http:// or https:// address your visitors will open.</p>
        </div>
        <div className="form-grid">
          <div className="field">
            <label htmlFor={`${id}-alias`}>Custom alias <span>Optional</span></label>
            <input
              id={`${id}-alias`}
              placeholder="my-link"
              value={draft.customAlias}
              onChange={event => update("customAlias", event.target.value)}
              maxLength={32}
              aria-describedby={`${id}-alias-help`}
              disabled={busy}
            />
            <p className="field-hint" id={`${id}-alias-help`}>3–32 letters, numbers, _ or -. Case-sensitive.</p>
          </div>
          <div className="field">
            <label htmlFor={`${id}-expiry`}>Expiration <span>Optional</span></label>
            <input
              id={`${id}-expiry`}
              type="datetime-local"
              value={draft.expiresAt}
              onChange={event => update("expiresAt", event.target.value)}
              min="0001-01-01T00:00"
              max="9999-12-31T23:59"
              aria-describedby={`${id}-expiry-help`}
              disabled={busy}
            />
            <p className="field-hint" id={`${id}-expiry-help`}>Your local time. Leave blank for no expiration.</p>
          </div>
        </div>
        {draft.expiresAt && Date.parse(draft.expiresAt) <= Date.now() && (
          <p className="warning" role="status">This time is in the past. Your link will be created already expired and will not redirect.</p>
        )}
        {storageWarning && <p className="warning" role="status">{storageWarning}</p>}
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="form-bottom">
          <p>{session ? "Saved to your dashboard after creation." : "Sign in to save your link. We’ll keep your draft."}</p>
          <button className="button" type="submit" disabled={busy}>
            {busy ? "Creating link…" : session ? "Create short link" : "Continue to sign in"}
          </button>
        </div>
      </form>
      {created && (
        <div className="created-result" ref={result} tabIndex={-1} role="status">
          <div className="section-heading">
            <h3>Short link created</h3>
            <StatusBadge url={created} />
          </div>
          <div className="result-url">
            <ExternalLink href={created.shortUrl}>{created.shortUrl}</ExternalLink>
            <CopyButton value={created.shortUrl} />
          </div>
          <Link className="text-link" href={`/urls/${created.id}`}>Manage this link</Link>
        </div>
      )}
    </>
  );
}
