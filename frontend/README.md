# Shortify frontend

Next.js App Router + React + TypeScript client for the existing Spring Boot API. Authentication, ownership, URL validation, redirects, and analytics remain backend responsibilities. There is no NextAuth layer, token issuer, mock account, or fabricated analytics data.

## Run locally

Use Node.js 22.18+ (or a current supported Node release) and npm. Run commands from `frontend/`:

```sh
npm ci
npm run dev -- --port 3000
```

Open `http://localhost:3000` for the default command. In this workspace, port 3000 is used by another project, so run `npm run dev -- --port 3002` and open `http://localhost:3002` instead. The configured workspace `.env.local` contains:

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
```

This is the public API origin, not a secret. Configure it before the production build; Next.js embeds `NEXT_PUBLIC_*` settings in browser assets. The fallback when unset is `http://localhost:8080`. Do not put `JWT_SECRET`, database credentials, or any other private backend settings here.

Start the backend and its PostgreSQL/Redis dependencies using the root README. The backend must allow the exact browser origin through `CORS_ALLOWED_ORIGIN`: `http://localhost:3000` by default or `http://localhost:3002` for this workspace. Use `localhost` consistently rather than switching to `127.0.0.1`. Requests go directly from the browser to the configured API origin, without credentialed cookies. Public links use the backend-returned `shortUrl` unchanged; configure the backend public base URL correctly.

## Routes

| Route | Behavior |
| --- | --- |
| `/` | Create form with destination, optional case-sensitive alias, and optional expiration. Guests save a draft and continue to sign-in. |
| `/login` | Backend email/password login with safe internal return routes. |
| `/register` | Backend registration, then the same JWT session flow. |
| `/dashboard` | Protected, newest-first owner link pagination, page-size controls, refresh, copy, details/analytics navigation, and creation. |
| `/urls/[id]` | Protected owner details, copy, original destination, creation/expiry timestamps, and active/inactive toggle via PATCH. |
| `/urls/[id]/analytics` | Protected lifetime click total, days with activity, latest activity date, daily chart/table, and backend category breakdowns. |

Malformed route IDs produce a not-found page. API ownership errors are displayed without inferring whether another account owns a record. Empty, loading, network, API error, and retry states are provided. Creation does not silently retry mutations.

## Sessions and data isolation

- The backend is the only authority issuing JWTs. Login/register use `accessToken`, `tokenType`, `expiresIn`, and normalized `email` from its response.
- The provider keeps the session in memory and in `sessionStorage` under `shortify.session`. This supports same-tab reloads without introducing a separate frontend authentication protocol. It is not stored in localStorage or cookies.
- The client uses the earlier of the backend token's `exp` and response lifetime for automatic expiration. Reading `exp` is scheduling only, not signature validation; the backend validates every management request.
- Expiry, a management 401, or explicit sign-out clears the session and stored draft, cancels in-flight management requests, and resets the entire page subtree. Responses from an old session are rejected before returning to page code. Protected children never render without a current session. There is no persistent account-data cache.
- Login 401 errors remain on the login form as credential errors. There is no refresh-token or backend logout endpoint. Discarding a token does not revoke copies of it on the server.
- Guest drafts use `shortify.draft` in sessionStorage and survive navigation between sign-in and registration. Sign-in returns to the dashboard for an explicit review/create action, not an automatic mutation. Successful creation and logout clear the stored draft.
- When storage is blocked, sessions work in memory. Guest draft preservation displays an error instead of navigating and losing the draft.
- SessionStorage is accessible to same-origin JavaScript; protect against XSS, use HTTPS outside local development, and sign out on shared devices. SessionStorage is normally tab-scoped; browsers can copy its initial contents when duplicating a tab. Separate tabs do not implement synchronized server-side logout.

## Forms and statistics

Client validation checks common URI errors, aliases/reserved names, expiration bounds, email structure, password length, and BCrypt's 72-byte UTF-8 limit. The backend remains authoritative. Passwords and aliases are never trimmed or case-normalized by the frontend; surrounding email whitespace is removed and the backend performs canonical normalization.

Expiration input uses the browser's local timezone and is sent as an ISO UTC instant. Detail timestamps are labeled UTC. Past expiration is intentionally accepted with a warning; reactivation never overrides expiration. The toggle explains the backend's default bounded cache delay.

Summary statistics come from `/api/urls/{id}/analytics`. Daily rows use UTC dates and only recorded days, without fabricated zero-filled periods. Categories show counts and rounded percentages of the returned lifetime total. The Geography panel offers Country and City views through `/api/urls/{id}/analytics/geography?by=country|city`, showing up to ten values with percentages of all recorded clicks, not just the displayed buckets. City labels include country context. IP-derived location is approximate; unavailable data, local/private addresses, and historical clicks display `Unknown`. Provider/database setup is documented in the root README and requires no frontend secret. Counts are successful persisted GET redirect events, not unique visitors, and may lag or drop because processing is asynchronous and best effort. Inactive/expired links retain history.

## UI and accessibility

The interface uses plain responsive CSS, compact typography, neutral surfaces, and restrained blue accents. Space Grotesk and IBM Plex Sans are bundled from the installed Fontsource packages; building/running does not fetch Google Fonts. Layouts adapt for phones, tablets, and desktop. The dashboard table becomes stacked link cards on smaller screens, keeping status and actions visible. Long analytics lists provide keyboard-accessible scrolling.

The header's **Appearance** selector offers **System**, **Light**, and **Dark**. System follows the operating system, including changes while the page is open. Explicit choices persist in localStorage under `shortify.theme`, synchronize across tabs, and remain independent of login/logout. A small script applies the saved appearance before React hydrates to avoid an initial wrong-theme render. Invalid or missing preferences fall back to System. When preference storage is blocked, selection still works for the current page session but does not persist through reloads.

Semantic landmarks, a skip link, visible focus outlines, labeled fields, password visibility controls, status/error announcements, table headers, textual chart values, reduced-motion styles, and copy-failure guidance are included. New-tab destination links have screen-reader notices. The browser suite verifies the main interaction and responsive flows; manual screen-reader testing has not been performed.

## Checks

```sh
npm test
npm run typecheck
npm run build
```

The 51 unit tests use Node's built-in runner and TypeScript stripping, with no backend/database writes. They cover session parsing/deadlines, safe return paths, status boundaries, UTC formatting, draft persistence/payloads, URI/alias/expiry/credential validation, bearer headers, no-cache/no-cookie transport, backend errors/retry hints, cancellation, 204 responses, appearance preference parsing, and pre-hydration theme initialization.

`npm run build` produces standalone output for the frontend Dockerfile. The root Compose `app` profile builds and starts both applications. `npm run start -- --port 3000` also serves a local production build through Next.js.

### Browser tests

With the frontend, backend, PostgreSQL, and Redis already running:

```sh
npx playwright install chromium
# Clean-checkout defaults: frontend 3000, backend 8080.
npm run test:e2e
# This workspace uses alternate ports:
E2E_BASE_URL=http://localhost:3002 E2E_API_URL=http://localhost:8081 npm run test:e2e
```

Thirteen Chromium scenarios run in both Light and Dark projects, for **26 browser tests**. Four functional scenarios exercise real registration/login/logout, guest draft preservation, creation with aliases and expiration, actual public redirects and resulting analytics, activation changes across pages, clipboard, two-account isolation, pagination, authenticated landing creation, country/city switching, geography network recovery, missing resources, and expired/rejected sessions. Four appearance scenarios check persistence, System changes, cross-tab synchronization, blocked storage, semantic text contrast, and saved appearance before hydration. Five explicitly named geography fixture scenarios intercept API responses to cover populated and country-only data, long city names, lifetime-total percentages, selected-view refresh, empty states, slow-response cancellation, logout, and geography-triggered session rejection. These fixtures are test-only and do not claim a live provider lookup. All seven route types are visited. Responsive checks cover widths 320, 375, 414, 768, and 1440 pixels. Screenshots are saved under `test-results/`; the HTML report is under `playwright-report/`. Both are ignored by Git.

Use a development database: browser tests create uniquely named `shortify-e2e-…@example.test` accounts and links. There is deliberately no public account-delete API, so browser tests do not erase accounts or reset the database. The primary functional flows use the real backend; network failures are injected for recovery checks. The separately named geography fixture scenarios use intercepted API responses and a test-only browser session, without writing accounts or locations to the database. Tests execute serially to respect shared rate limits; rapid repeated runs can legitimately exhaust the default per-IP budget for a minute.

Executed results and deployment verification are recorded in the root `docs/verification.md`. Browser layout and behavior checks are not a substitute for a manual assistive-technology accessibility audit.

## Source map

- `app/`: route entries, global/responsive styles, not-found/error handling.
- `components/theme-provider.tsx`, `lib/theme.ts`: appearance control, persistence, System resolution, and pre-hydration initialization.
- `components/auth-provider.tsx`: session lifecycle, bearer requests, expiry/401 handling and cancellation.
- `components/shell.tsx`: navigation, auth-keyed page reset, protected-page boundary, shared request states.
- `components/`: creation/auth forms, dashboard, detail, analytics, and copy/status components.
- `lib/api.ts`, `lib/helpers.ts`, `lib/types.ts`: shared transport, pure helpers, and backend DTO types.
- `lib/use-resource.ts`: cancellable per-page data fetching without a global cache.
- `tests/unit/`: helper and transport tests.
- `tests/e2e/`: actual browser-to-backend integration scenarios.
