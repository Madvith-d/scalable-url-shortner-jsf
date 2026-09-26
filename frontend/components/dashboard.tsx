"use client";

import Link from "next/link";
import { useState } from "react";
import { CreateForm } from "./create-form";
import { CopyButton, ExternalLink, StatusBadge } from "./link-ui";
import { ErrorState, Loading } from "./shell";
import { formatDate } from "@/lib/helpers";
import { useResource } from "@/lib/use-resource";
import type { UrlPage } from "@/lib/types";

export function Dashboard() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const { data, loading, error, reload } = useResource<UrlPage>(`/api/urls?page=${page}&size=${size}`);
  return (
    <div className="container workspace">
      <div className="page-heading">
        <div>
          <h1>Your links</h1>
          <p>Manage destinations, availability, and click activity.</p>
        </div>
        <a href="#new-link" className="button">New short link</a>
      </div>
      <section className="panel" aria-labelledby="links-title">
        <div className="panel-heading">
          <h2 id="links-title">All links {data && <span className="count-pill">{data.totalElements.toLocaleString()}</span>}</h2>
          <button className="button secondary small" onClick={reload} disabled={loading}>Refresh links</button>
        </div>
        <div className="table-toolbar">
          <p>Newest first · Includes inactive and expired links</p>
          <label htmlFor="page-size">
            Per page
            <select id="page-size" value={size} onChange={event => { setSize(Number(event.target.value)); setPage(0); }}>
              <option value={10}>10</option>
              <option value={20}>20</option>
              <option value={50}>50</option>
            </select>
          </label>
        </div>
        {loading ? (
          <Loading message="Loading your links…" />
        ) : error ? (
          <ErrorState message={error} retry={reload} />
        ) : data && data.content.length === 0 ? (
          <div className="empty-state">
            <h3>{data.totalElements === 0 ? "No links yet" : "No links on this page."}</h3>
            <p>{data.totalElements === 0 ? "Create a short link below to start your list." : "Return to the first page to see your saved links."}</p>
            {data.totalElements === 0 ? (
              <a className="text-link" href="#new-link">Create your first link</a>
            ) : (
              <button className="button secondary" onClick={() => setPage(0)}>Go to first page</button>
            )}
          </div>
        ) : data && (
          <div className="table-scroll" role="region" aria-label="Your links table">
            <table className="links-table" role="table">
              <caption className="sr-only">Your short links, newest first</caption>
              <thead role="rowgroup">
                <tr role="row">
                  <th scope="col" role="columnheader">Link / destination</th>
                  <th scope="col" role="columnheader">Status</th>
                  <th scope="col" role="columnheader">Expiration (UTC)</th>
                  <th scope="col" role="columnheader">Actions</th>
                </tr>
              </thead>
              <tbody role="rowgroup">
                {data.content.map(url => (
                  <tr key={url.id} role="row">
                    <td data-label="Link / destination" role="cell">
                      <ExternalLink className="short-link" href={url.shortUrl}>{url.shortUrl}</ExternalLink>
                      <span className="destination" title={url.originalUrl}>{url.originalUrl}</span>
                      <span className="created-date">Created {formatDate(url.createdAt)}</span>
                    </td>
                    <td data-label="Status" role="cell"><StatusBadge url={url} /></td>
                    <td className="date-cell" data-label="Expiration (UTC)" role="cell">{formatDate(url.expiresAt)}</td>
                    <td data-label="Actions" role="cell">
                      <div className="row-actions">
                        <CopyButton value={url.shortUrl} />
                        <Link className="text-link" href={`/urls/${url.id}`}>Manage<span className="sr-only"> {url.shortCode}</span></Link>
                        <Link className="text-link" href={`/urls/${url.id}/analytics`}>Analytics<span className="sr-only"> for {url.shortCode}</span></Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.totalPages > 0 && (
          <nav className="pagination" aria-label="Link pagination">
            <p aria-live="polite">Page {data.page + 1} of {data.totalPages} · {data.totalElements.toLocaleString()} {data.totalElements === 1 ? "link" : "links"}</p>
            <div>
              <button className="button secondary small" disabled={page === 0 || loading} onClick={() => setPage(value => Math.max(0, value - 1))}>Previous</button>
              <button className="button secondary small" disabled={page + 1 >= data.totalPages || loading} onClick={() => setPage(value => value + 1)}>Next</button>
            </div>
          </nav>
        )}
      </section>
      <section className="panel dashboard-create" id="new-link" aria-labelledby="new-link-title">
        <div className="panel-heading"><h2 id="new-link-title">Create a short link</h2></div>
        <CreateForm onCreated={() => { setPage(0); reload(); }} />
      </section>
    </div>
  );
}
