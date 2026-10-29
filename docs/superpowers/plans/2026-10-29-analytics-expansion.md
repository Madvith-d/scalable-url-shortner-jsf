# Analytics Expansion Implementation Plan

> **For agentic workers:** Inline execution is authorized by the user's request. Use test-first implementation and keep changes in the existing backend and frontend structure.

**Goal:** Add time-of-week click charts, prior-period comparison, an estimated repeat click rate, and an account-wide top-five links summary.

**Architecture:** Extend the owner-scoped analytics API with UTC hour/weekday buckets and the number of clicks eligible for unique counting. Derive selected and previous range totals in the analytics UI; expose the top five owner links through a small API endpoint backed by the existing click counter.

**Tech Stack:** Spring Boot, PostgreSQL, Next.js, TypeScript, Recharts.

**Spec:** User-approved analytics additions in the conversation on 2026-10-29.

## Global Constraints

- Keep geography collection removed.
- Only aggregate click metadata already stored by the application.
- Label repeat click rate as approximate and calculate it only from events with visitor hashes.
- Preserve account ownership checks for link analytics and top-link queries.

## Review Focus

- UTC boundary handling for hourly and weekday aggregation.
- Empty or zero-activity analytics responses.
- Historical events without visitor hashes must not inflate estimated repeats.
- Top links must be selected across all account links, not only the current page.
- A zero previous-period total must not produce an invalid percentage.

### Task 1: Backend analytics aggregates and top links

**Files:** `ClickEventRepository`, `AnalyticsResponse`, `AnalyticsService`, `ShortUrlRepository`, `ShortUrlService`, `ShortUrlController`, `Phase4ApiIT`.

- [ ] Extend integration assertions for UTC hour/weekday buckets, hash-eligible clicks, and owner-scoped top-five links.
- [ ] Run the focused integration test and confirm the new assertions fail against the current API.
- [ ] Add the minimal repository queries, response fields, service method, and endpoint.
- [ ] Run the integration test and backend compile.

### Task 2: Analytics and dashboard UI

**Files:** `frontend/lib/types.ts`, `frontend/components/url-analytics.tsx`, `frontend/components/dashboard.tsx`, `frontend/app/globals.css`, `frontend/app/responsive.css`.

- [ ] Add assertions for period comparison and time-bucket mapping if an existing unit-test seam supports them.
- [ ] Implement time-of-week charts, previous-period comparison, estimated repeat rate, and top-links summary.
- [ ] Run frontend unit tests and production build.
