"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { useAuth } from "./auth-provider";
import { CopyButton, ExternalLink, StatusBadge } from "./link-ui";
import { ErrorState, Loading } from "./shell";
import { errorMessage, isAbort } from "@/lib/api";
import { formatDate } from "@/lib/helpers";
import { useResource } from "@/lib/use-resource";
import type { ShortUrl } from "@/lib/types";

export function UrlDetail({ id }: { id: string }) {
  const { data, loading, error, reload } = useResource<ShortUrl>(`/api/urls/${id}`);
  return <div className="container workspace detail-page"><Link className="back-link" href="/dashboard">← All links</Link><div className="page-heading"><div><p className="eyebrow">WORKSPACE / LINK DETAILS</p><h1>A link <span>under your control.</span></h1><p>One destination. All the details.</p></div></div>{loading ? <Loading message="Loading link details…" /> : error ? <ErrorState message={error} retry={reload} /> : data && <DetailContent key={`${data.id}:${data.active}`} initial={data} />}</div>;
}

function DetailContent({ initial }: { initial: ShortUrl }) {
  const { request } = useAuth();
  const [url, setUrl] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const inFlight = useRef(false);
  async function toggle() {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError("");
    setMessage("");
    try {
      const updated = await request<ShortUrl>(`/api/urls/${url.id}`, { method: "PATCH", body: JSON.stringify({ active: !url.active }) });
      setUrl(updated);
      setMessage(updated.active ? "Link reactivated. Expiration still applies." : "Link deactivated. Your details and click history are preserved.");
    } catch (failure) { if (!isAbort(failure)) setError(errorMessage(failure)); }
    finally { setBusy(false); inFlight.current = false; }
  }
  return <><section className="panel link-detail-panel" aria-labelledby="link-title"><div className="panel-heading"><div><p className="eyebrow">YOUR SHORT LINK</p><h2 id="link-title">{url.shortCode}</h2></div><StatusBadge url={url} /></div><div className="share-strip"><ExternalLink href={url.shortUrl}>{url.shortUrl}</ExternalLink><CopyButton value={url.shortUrl} /></div><dl className="detail-list"><div className="wide-detail"><dt>Destination URL</dt><dd><ExternalLink href={url.originalUrl}>{url.originalUrl}</ExternalLink></dd></div><div><dt>Created (UTC)</dt><dd>{formatDate(url.createdAt)}</dd></div><div><dt>Expires (UTC)</dt><dd>{formatDate(url.expiresAt)}</dd></div><div><dt>Short code</dt><dd>{url.shortCode}</dd></div><div><dt>Activation setting</dt><dd>{url.active ? "Active" : "Inactive"}</dd></div></dl></section><div className="detail-grid"><section className="panel control-panel"><p className="eyebrow">LINK AVAILABILITY</p><h2>{url.active ? "Need a pause?" : "Ready to share again?"}</h2><p>Deactivating stops redirects without deleting the link or its history. You can reactivate it later.</p><p className="field-hint">Expiration always applies, even after reactivation. Cached redirects may take up to 30 seconds to reflect changes with the default backend settings.</p><button className={`button ${url.active ? "danger-secondary" : ""}`} onClick={toggle} disabled={busy}>{busy ? "Updating link…" : url.active ? "Deactivate link" : "Reactivate link"}</button>{error && <p className="form-error" role="alert">{error}</p>}{message && <p className="success-message" role="status">{message}</p>}</section><section className="panel analytics-teaser"><p className="eyebrow">FOLLOW THE ACTIVITY</p><h2>See where it goes.</h2><p>Explore this link’s recorded clicks, daily activity, and audience breakdowns.</p><Link className="button" href={`/urls/${url.id}/analytics`}>View analytics <span aria-hidden="true">↗</span></Link><p className="field-hint">Analytics remain available for inactive and expired links.</p></section></div></>;
}
