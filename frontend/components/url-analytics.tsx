"use client";

import Link from "next/link";
import { useId, useState } from "react";
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from "recharts";
import { ExternalLink, StatusBadge } from "./link-ui";
import { ErrorState, Loading } from "./shell";
import { useResource } from "@/lib/use-resource";
import { compareClicks, fillBuckets } from "@/lib/analytics";
import type { Analytics, Bucket, ShortUrl } from "@/lib/types";

const periods = [
  { days: 7, label: "7D", name: "Last 7 days" },
  { days: 30, label: "30D", name: "Last 30 days" },
  { days: 90, label: "90D", name: "Last 90 days" },
  { days: 365, label: "365D", name: "Last 365 days" },
] as const;
type Period = (typeof periods)[number]["days"] | "all";

export function UrlAnalytics({ id }: { id: string }) {
  const [period, setPeriod] = useState<Period>(30);
  const link = useResource<ShortUrl>(`/api/urls/${id}`);
  const analytics = useResource<Analytics>(`/api/urls/${id}/analytics`);
  const data = analytics.data;
  const activity = data ? activityForPeriod(data.clicksOverTime, period) : null;
  const comparison = data ? compareClicks(data.clicksOverTime, period) : null;
  return (
    <div className="container workspace analytics-page">
      <Link className="back-link" href={`/urls/${id}`}>Link details</Link>
      <div className="page-heading">
        <div>
          <h1>Link analytics</h1>
          <p>Recorded clicks for this link, across all time.</p>
        </div>
        <button className="button secondary" onClick={() => { link.reload(); analytics.reload(); }} disabled={link.loading || analytics.loading}>
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
              <p>Unique clicks</p>
              <strong>{data.uniqueClicks.toLocaleString()}</strong>
              <span>Distinct IPs · Approximate · New clicks only</span>
            </article>
            <article className="stat-card">
              <p>Repeat click rate</p>
              <strong>{data.trackedClicks ? `${Math.round(Math.max(0, data.trackedClicks - data.uniqueClicks) / data.trackedClicks * 100)}%` : "—"}</strong>
              <span>{data.trackedClicks ? `${Math.max(0, data.trackedClicks - data.uniqueClicks).toLocaleString()} repeat clicks of ${data.trackedClicks.toLocaleString()} IP-tracked · Approximate` : "Available after client IPs can be resolved"}</span>
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
              <label className="chart-range-control">
                <span className="sr-only">Chart time period</span>
                <select aria-label="Chart time period" value={period}
                  onChange={event => setPeriod(event.target.value === "all" ? "all" : Number(event.target.value) as Period)}>
                  {periods.map(option => <option key={option.days} value={option.days}>{option.name}</option>)}
                  <option value="all">All time</option>
                </select>
              </label>
            </div>
            {data.clicksOverTime.length ? (
              <div className="daily-chart">
                <div className="chart-summary">
                  <strong>{activity!.total.toLocaleString()} clicks</strong>
                  <span>{formatChartDate(activity!.start)} – {formatChartDate(activity!.end)} UTC · {comparison!.previous === 0
                    ? comparison!.current > 0 ? "New activity vs previous period" : "No previous-period activity"
                    : `${comparison!.current >= comparison!.previous ? "+" : ""}${Math.round((comparison!.current - comparison!.previous) / comparison!.previous * 100)}% vs previous period (${comparison!.previous.toLocaleString()})`}</span>
                </div>
                <ClickChart points={activity!.points} period={period} />
                {activity!.total === 0 && <p className="chart-period-empty">No clicks in this period.</p>}
                <details className="chart-table-details">
                  <summary>View daily totals</summary>
                  <p className="field-hint">Only days with recorded clicks are listed.</p>
                  <div className="chart-scroll" tabIndex={0} role="region" aria-label="Daily click totals">
                    <table className="activity-table">
                      <caption className="sr-only">Recorded clicks by UTC date</caption>
                      <thead>
                        <tr><th scope="col">Date (UTC)</th><th scope="col">Daily activity</th><th scope="col">Clicks</th></tr>
                      </thead>
                      <tbody>
                        {activity!.activeDays.map(day => (
                          <tr key={day.date}>
                            <th scope="row">{day.date}</th>
                            <td><div className="bar-track" aria-hidden="true"><span style={{ width: `${day.clicks / activity!.maxClicks * 100}%` }} /></div></td>
                            <td>{day.clicks.toLocaleString()}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </details>
              </div>
            ) : (
              <p className="chart-empty">Daily activity will appear after the first recorded click.</p>
            )}
          </section>
          <section className="panel time-patterns-panel" aria-labelledby="time-patterns-title">
            <div className="panel-heading"><h2 id="time-patterns-title">When clicks happen</h2><span className="quiet-label">All recorded clicks · UTC</span></div>
            <div className="time-patterns-grid">
              <PatternChart title="By hour" rows={fillBuckets(data.hourlyClicks, Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, "0")))} />
              <PatternChart title="By weekday" rows={fillBuckets(data.weekdayClicks, ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"])} />
            </div>
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
          </div>
          <aside className="analytics-footnote">
            <strong>About these numbers</strong>
            <p>Analytics are asynchronous and best effort. Totals can lag and some events may be dropped. Only persisted successful GET redirects count—not HEAD requests, failed requests, or inactive or expired links. Unique clicks count distinct IP addresses, so shared networks may combine visitors and changing IPs may count one visitor more than once. Historical clicks recorded before unique tracking are not included. History remains available after deactivation or expiration.</p>
          </aside>
        </>
      )}
    </div>
  );
}

function activityForPeriod(rows: Analytics["clicksOverTime"], period: Period) {
  const today = new Date();
  const end = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()));
  const latest = end.toISOString().slice(0, 10);
  const firstRecorded = rows[0]?.date ?? latest;
  const start = period === "all" ? new Date(`${firstRecorded}T00:00:00Z`) : new Date(end);
  if (period !== "all") start.setUTCDate(start.getUTCDate() - period + 1);

  const counts = new Map(rows.map(row => [row.date, row.clicks]));
  const points: Analytics["clicksOverTime"] = [];
  const activeDays: Analytics["clicksOverTime"] = [];
  for (const day = new Date(start); day <= end; day.setUTCDate(day.getUTCDate() + 1)) {
    const date = day.toISOString().slice(0, 10);
    const clicks = counts.get(date) ?? 0;
    points.push({ date, clicks });
    if (clicks) activeDays.push({ date, clicks });
  }
  return {
    points,
    activeDays,
    total: activeDays.reduce((sum, day) => sum + day.clicks, 0),
    maxClicks: activeDays.reduce((maximum, day) => Math.max(maximum, day.clicks), 1),
    start: points[0]?.date ?? latest,
    end: latest,
  };
}

function formatChartDate(value: string) {
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric", timeZone: "UTC" })
    .format(new Date(`${value}T00:00:00Z`));
}

function ClickChart({ points, period }: { points: Analytics["clicksOverTime"]; period: Period }) {
  const gradientId = `click-area-${useId().replace(/:/g, "")}`;
  const rangeName = period === "all" ? "all time" : `the last ${period} days`;
  return (
    <div className="click-chart-wrap" role="img" aria-label={`Daily clicks for ${rangeName}`}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={points} margin={{ top: 8, right: 12, left: 4, bottom: 0 }}>
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="var(--accent)" stopOpacity={0.34} />
              <stop offset="95%" stopColor="var(--accent)" stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke="var(--line)" />
          <XAxis dataKey="date" tickLine={false} axisLine={false} minTickGap={32}
            tickFormatter={formatChartDate} tick={{ fill: "var(--muted)", fontSize: 12 }} />
          <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={48}
            tick={{ fill: "var(--muted)", fontSize: 12 }} />
          <Tooltip content={<ChartTooltip />} />
          <Area dataKey="clicks" type="natural" stroke="var(--accent)" strokeWidth={2.5}
            fill={`url(#${gradientId})`} activeDot={{ r: 4 }} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

function ChartTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ value?: number }>; label?: string }) {
  if (!active || !payload?.length || !label) return null;
  return <div className="chart-tooltip"><span>{formatChartDate(label)} · UTC</span><strong>{Number(payload[0].value ?? 0).toLocaleString()} clicks</strong></div>;
}

function Breakdown({ title, rows, total, description }: { title: string; rows: Bucket[]; total: number; description: string }) {
  return (
    <section className="panel breakdown-panel" aria-label={title}>
      <h2>{title}</h2>
      {rows.length ? <div className="breakdown-chart" role="img" aria-label={`${title} clicks breakdown`} style={{ height: Math.max(180, rows.length * 34) }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 8, left: 0, bottom: 4 }}>
            <CartesianGrid horizontal={false} stroke="var(--line)" />
            <XAxis type="number" allowDecimals={false} hide />
            <YAxis type="category" dataKey="label" width={112} tickLine={false} axisLine={false}
              tickFormatter={value => String(value).length > 17 ? `${String(value).slice(0, 16)}…` : value}
              tick={{ fill: "var(--muted)", fontSize: 11 }} />
            <Tooltip content={<BreakdownTooltip total={total} />} cursor={{ fill: "var(--accent-soft)" }} />
            <Bar dataKey="clicks" fill="var(--accent)" radius={[0, 4, 4, 0]} maxBarSize={18} />
          </BarChart>
        </ResponsiveContainer>
      </div> : <p className="chart-empty">No {title.toLowerCase()} data yet.</p>}
      {rows.length > 0 && <details className="bucket-details">
        <summary>View exact totals</summary>
        <BucketList title={title} rows={rows} total={total} />
      </details>}
      <p className="field-hint">{description}</p>
    </section>
  );
}

function BreakdownTooltip({ active, payload, total }: { active?: boolean; payload?: Array<{ payload?: Bucket }>; total: number }) {
  const row = payload?.[0]?.payload;
  if (!active || !row) return null;
  return <div className="chart-tooltip"><span>{row.label}</span><strong>{row.clicks.toLocaleString()} clicks · {total ? Math.round(row.clicks / total * 100) : 0}%</strong></div>;
}

function PatternChart({ title, rows }: { title: string; rows: Bucket[] }) {
  return <div className="pattern-chart-panel" role="img" aria-label={`Clicks ${title.toLowerCase()}`}>
    <h3>{title}</h3>
    <div className="pattern-chart">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--line)" />
          <XAxis dataKey="label" tickLine={false} axisLine={false} minTickGap={8} tick={{ fill: "var(--muted)", fontSize: 10 }} />
          <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={38} tick={{ fill: "var(--muted)", fontSize: 10 }} />
          <Tooltip content={<PatternTooltip />} cursor={{ fill: "var(--accent-soft)" }} />
          <Bar dataKey="clicks" fill="var(--accent)" radius={[3, 3, 0, 0]} maxBarSize={24} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  </div>;
}

function PatternTooltip({ active, payload, label }: { active?: boolean; payload?: Array<{ value?: number }>; label?: string }) {
  if (!active || !payload?.length || !label) return null;
  return <div className="chart-tooltip"><span>{label}{/^\d{2}$/.test(label) ? ":00 UTC" : ""}</span><strong>{Number(payload[0].value ?? 0).toLocaleString()} clicks</strong></div>;
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
