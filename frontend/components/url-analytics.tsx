"use client";

import Link from "next/link";
import { useState } from "react";
import { ExternalLink, StatusBadge } from "./link-ui";
import { ErrorState, Loading } from "./shell";
import { useResource } from "@/lib/use-resource";
import type { Analytics, Bucket, GeographyAnalytics, GeographyBy, ShortUrl } from "@/lib/types";

export function UrlAnalytics({ id }: { id: string }) {
  const [geographyBy, setGeographyBy] = useState<GeographyBy>("country");
  const link = useResource<ShortUrl>(`/api/urls/${id}`);
  const analytics = useResource<Analytics>(`/api/urls/${id}/analytics`);
  const geography = useResource<GeographyAnalytics>(`/api/urls/${id}/analytics/geography?by=${geographyBy}`);
  const data = analytics.data;
  const maxDailyClicks = data?.clicksOverTime.reduce((maximum, day) => Math.max(maximum, day.clicks), 1) || 1;
  return (
    <div className="container workspace analytics-page">
      <Link className="back-link" href={`/urls/${id}`}>Link details</Link>
      <div className="page-heading">
        <div>
          <h1>Link analytics</h1>
          <p>Recorded clicks for this link, across all time.</p>
        </div>
        <button className="button secondary" onClick={() => { link.reload(); analytics.reload(); geography.reload(); }} disabled={link.loading || analytics.loading || geography.loading}>
          Refresh analytics
        </button>
      </div>
      {link.loading ? (
        <Loading message="Loading link…" />
      ) : link.error ? (
        <ErrorState message={link.error} retry={link.reload} />
      ) : link.data && (
        <div className="analytics-link">
          <ExternalLink className="short-link" href={link.data.shortUrl}>{link.data.shortUrl}</ExternalLink>
          <StatusBadge url={link.data} />
        </div>
      )}
      {analytics.loading ? (
        <Loading message="Loading recorded activity…" />
      ) : analytics.error ? (
        <ErrorState message={analytics.error} retry={analytics.reload} />
      ) : data && (
        <>
          <section className="stats-grid" aria-label="Lifetime click summary">
            <article className="stat-card primary-stat">
              <p>Total recorded clicks</p>
              <strong>{data.totalClicks.toLocaleString()}</strong>
              <span>Successful GET redirects · All time</span>
            </article>
            <article className="stat-card">
              <p>Days with recorded activity</p>
              <strong>{data.clicksOverTime.length.toLocaleString()}</strong>
              <span>UTC days with at least one click</span>
            </article>
            <article className="stat-card">
              <p>Last recorded activity</p>
              <strong className="date-stat">{data.clicksOverTime.at(-1)?.date || "Not yet"}</strong>
              <span>UTC date · Not a live visitor count</span>
            </article>
          </section>
          {data.totalClicks === 0 && (
            <div className="empty-state analytics-empty">
              <h3>No clicks recorded yet</h3>
              <p>Share your active, unexpired link and refresh after someone visits. Processing can take a moment.</p>
            </div>
          )}
          <section className="panel activity-panel" aria-labelledby="activity-title">
            <div className="panel-heading">
              <h2 id="activity-title">Clicks over time</h2>
              <span className="quiet-label">All time · UTC</span>
            </div>
            {data.clicksOverTime.length ? (
              <div className="daily-chart">
                <p className="field-hint">Only days with recorded clicks are shown; missing days are not filled in.</p>
                <div className="chart-scroll" tabIndex={0} role="region" aria-label="Daily click activity">
                  <table className="activity-table">
                    <caption className="sr-only">Recorded clicks by UTC date</caption>
                    <thead>
                      <tr><th scope="col">Date (UTC)</th><th scope="col">Daily activity</th><th scope="col">Clicks</th></tr>
                    </thead>
                    <tbody>
                      {data.clicksOverTime.map(day => (
                        <tr key={day.date}>
                          <th scope="row">{day.date}</th>
                          <td><div className="bar-track" aria-hidden="true"><span style={{ width: `${day.clicks / maxDailyClicks * 100}%` }} /></div></td>
                          <td>{day.clicks.toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <p className="chart-empty">Daily activity will appear after the first recorded click.</p>
            )}
          </section>
          <div className="breakdown-grid">
            <Breakdown
              title="Referrers"
              rows={data.referrers}
              total={data.totalClicks}
              description="Referrer hosts only. Direct means no referrer; Unknown means it could not be classified."
            />
            <Breakdown
              title="Devices"
              rows={data.devices}
              total={data.totalClicks}
              description="Device categories inferred by the backend. No raw user agents are stored."
            />
            <section className="panel breakdown-panel geography-panel" aria-labelledby="geography-title">
              <h2 id="geography-title">Geography</h2>
              <label className="geography-control">
                Geography view
                <select value={geographyBy} onChange={event => setGeographyBy(event.target.value as GeographyBy)}>
                  <option value="country">Country</option>
                  <option value="city">City</option>
                </select>
              </label>
              {geography.loading ? (
                <Loading message={`Loading ${geographyBy} geography…`} />
              ) : geography.error ? (
                <ErrorState message={geography.error} retry={geography.reload} />
              ) : geography.data && (
                <BucketList title={geographyBy === "country" ? "Country" : "City"} rows={geography.data.buckets} total={geography.data.totalClicks} />
              )}
              <p className="field-hint">Approximate IP-based location, not a precise location. Unknown means the {geographyBy} could not be determined. Top 10 · Percentages of all recorded clicks.</p>
            </section>
          </div>
          <aside className="analytics-footnote">
            <strong>About these numbers</strong>
            <p>Analytics are asynchronous and best effort. Totals can lag and some events may be dropped. Only persisted successful GET redirects count—not HEAD requests, failed requests, or inactive or expired links. These are clicks, not unique visitors. History remains available after deactivation or expiration.</p>
          </aside>
        </>
      )}
    </div>
  );
}

function Breakdown({ title, rows, total, description }: { title: string; rows: Bucket[]; total: number; description: string }) {
  return (
    <section className="panel breakdown-panel" aria-label={title}>
      <h2>{title}</h2>
      <BucketList title={title} rows={rows} total={total} />
      <p className="field-hint">{description}</p>
    </section>
  );
}

function BucketList({ title, rows, total }: { title: string; rows: Bucket[]; total: number }) {
  return rows.length ? (
    <ul className="bucket-list" tabIndex={0} aria-label={`${title} breakdown`}>
      {rows.map(row => (
        <li key={row.label}>
          <div>
            <span className="bucket-label">{row.label}</span>
            <span>
              <strong>{row.clicks.toLocaleString()}</strong>{" "}
              <span className="bucket-percent">{total ? Math.round(row.clicks / total * 100) : 0}%</span>
            </span>
          </div>
          <div className="bar-track" aria-hidden="true"><span style={{ width: `${total ? row.clicks / total * 100 : 0}%` }} /></div>
        </li>
      ))}
    </ul>
  ) : (
    <p className="chart-empty">No {title.toLowerCase()} data yet.</p>
  );
}
