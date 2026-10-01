# Shortify

A URL shortening and click analytics platform built with **Java 21, Spring Boot, Next.js, TypeScript, PostgreSQL, and Redis**. The backend is a layered modular monolith: controllers → services → Spring Data JPA repositories. Spring Security issues and validates JWTs; Next.js is a client of the backend API, not a separate authentication system.

Create short links with optional aliases, scheduled activation, expiration, and click caps; generate/download/share QR codes, manage your own links, and view click totals, daily activity, referrers, devices, and available geography. Public redirects use Redis with PostgreSQL fallback. Rate limiting, asynchronous click processing, and scheduled expiration cleanup use Redis and Spring mechanisms—no microservices, Kafka, or cloud infrastructure.

Use TLS before exposing bearer tokens outside local development. Read the cache consistency, outage, and best-effort analytics limitations below before deployment. The approved requirements remain in `phases.md` and `scope.md`; executed checks are recorded in [`docs/verification.md`](docs/verification.md).

## Prerequisites

- Docker Engine with Docker Compose is sufficient for the fully containerized application.
- For development outside containers: JDK 21 and Node.js 24 LTS with npm. Set `JAVA_HOME` to your installed JDK; the untracked `.local/jdk21` in this implementation workspace is not included in a clean checkout.
- Internet access for the first build, plus `curl` or `wget` and `unzip` on Linux/macOS.
- No installed Maven is needed. The official Maven Wrapper 3.3.4 in `backend/` bootstraps Maven 3.9.16.

Commands below use **Bash**, starting at the repository root. If using Fish, enter `bash` first.

## Quick start — all services in Docker

From a fresh checkout:

```bash
cp .env.example .env
# Set POSTGRES_PASSWORD and JWT_SECRET in .env to separate strong random values.
# Generate each with: openssl rand -hex 32
chmod 600 .env
docker compose --profile app up -d --build --wait
```

Open **http://localhost:3000** and register an account. The backend defaults to **http://localhost:8080**. Both application images build from source; Java, Maven, and Node installations are unnecessary on the host for this path. PostgreSQL and Redis have health checks; application startup waits for them. Application containers run as non-root users. The backend health check expects an unauthenticated management request to return 401.

To use different ports, change `SERVER_PORT`, `FRONTEND_PORT`, and optionally `DB_PORT`/`REDIS_PORT` before starting. Update `CORS_ALLOWED_ORIGIN` to the frontend's exact origin. For access through a domain or reverse proxy, also configure `SHORTIFY_BASE_URL` and `NEXT_PUBLIC_API_BASE_URL` as browser-reachable backend origins, and use TLS. The browser cannot resolve Compose service names such as `backend`.

`NEXT_PUBLIC_API_BASE_URL` is a **frontend build-time** setting: rebuild the frontend image when it changes. Container-to-container database/Redis connections use their internal ports independently of host port overrides. Compose binds all published services to loopback by default.

```bash
docker compose --profile app ps
docker compose --profile app logs -f backend frontend
# Stop the application and infrastructure, preserving database data:
docker compose --profile app down
```

The `app` profile includes both application services; `docker compose up -d --wait postgres redis` starts infrastructure alone for independent backend/frontend development. Image builds compile/package; run the verification commands below to execute tests rather than treating an image build as a test run.

## Configure and start PostgreSQL and Redis

For a fresh checkout only, copy `.env.example` to the root `.env`. Do not overwrite an existing configured file. Set `POSTGRES_PASSWORD` and `JWT_SECRET` before starting. Both are required; no JWT signing secret is supplied by the application or committed to Git.

Generate at least 32 random bytes for the signing secret, for example with `openssl rand -hex 32`, and store the result privately as `JWT_SECRET` in `.env`. The application uses the **literal UTF-8 bytes** of the value, not Base64/hex decoding, and refuses blank or fewer than 32-byte values. Keep `.env` ignored and restrict its permissions (`chmod 600 .env`). Do not paste credentials or tokens into logs or commits.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_DB` | `shortify` | Database name, shared by Compose and backend |
| `POSTGRES_USER` | `shortify` | Database user, shared by Compose and backend |
| `POSTGRES_PASSWORD` | Required | Database password |
| `DB_HOST` | `localhost` | Backend database hostname |
| `DB_PORT` | `5432` | Published PostgreSQL port and backend database port |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `SHORTIFY_BASE_URL` | `http://localhost:${SERVER_PORT}` | Public origin used in response `shortUrl` values |
| `JWT_SECRET` | Required, at least 32 UTF-8 bytes | HS256 signing/verification secret; use a cryptographically random value |
| `JWT_ISSUER` | `shortify` | Exact accepted JWT issuer; must not be blank |
| `JWT_EXPIRES_IN` | `3600` | Positive access-token lifetime in seconds |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:3000` | One exact HTTP(S) frontend origin, no path/trailing slash/wildcard |

Set alternative ports in `.env` **before** starting; do not stop unrelated containers. This workspace uses frontend **3002**, backend **8081**, and PostgreSQL **5434**; its ignored `.env` contains those overrides. The examples' clean-checkout defaults remain 3000, 8080, and 5432. Changing credentials in `.env` does not change credentials already initialized in a database volume.

Compose reads `.env`; Spring Boot and Maven do not. Export the root `.env` in each new shell before running the backend or tests:

```bash
set -a
source .env
set +a
# Set JAVA_HOME to your JDK 21 installation if it is not already set.
# This workspace's optional local JDK is at "$PWD/.local/jdk21".
export PATH="$JAVA_HOME/bin:$PATH"
java -version
docker compose up -d --wait postgres redis
docker compose ps
```

Only source your own trusted `.env`; keep values valid Bash assignments. PostgreSQL binds to loopback and persists data in the named `postgres_data` volume. Redis 7 binds to `127.0.0.1:${REDIS_PORT:-6379}` with persistence disabled and no local authentication; do not expose it publicly. Redis keys are temporary, not authoritative data. `REDIS_PASSWORD` is supported by the backend for an externally managed authenticated Redis; setting it does not enable auth in this local Compose service.

## Build, test, and run

With PostgreSQL and Redis healthy and the settings exported:

```bash
cd backend
./mvnw --version
./mvnw clean verify
./mvnw spring-boot:run
# Alternatively, after verify:
# java -jar target/shortify-0.0.1-SNAPSHOT.jar
```

Flyway owns the schema; Hibernate uses `ddl-auto: validate`. Stop a manually started backend with Ctrl+C.

- Surefire runs unit tests for URL validation, code generation, service behavior, and security configuration.
- Failsafe starts real HTTP servers on random ports and exercises the full Spring Security filter chain against **real PostgreSQL and Redis**, not H2 or mock authentication. Each test context gets a unique Redis prefix. Existing suites use high configured limits, not disabled protection; dedicated tests use low limits.
- Phase 2 HTTP tests now register an account and attach its bearer token to management requests; public redirect requests remain unauthenticated. Existing validation/collision/expiry coverage is retained.
- Phase 1 persistence assertions are retained using the repository directly, rather than preserving unauthenticated service accessors.
- Tests cover account normalization/BCrypt/login, owner isolation, pagination, activation, invalid JWTs, aliases/concurrency, legacy links, CORS, Redis hits/misses/races/expiry, async analytics/privacy, rate limits, outage behavior, and automatic bounded cleanup. A migration test creates a uniquely named schema, applies V1, inserts a legacy URL, applies current migrations, verifies preservation, and drops only that schema.
- Persistence tests roll back. HTTP tests delete only their own tracked URLs/accounts; tables are never truncated. Identity sequences can advance. Flyway changes to the main schema persist.
- No database tests are silently skipped. Use a local/development database whose user can create a temporary schema for the migration test.

`./mvnw test` runs unit tests only; **use `clean verify` for acceptance**. Actual counts and results are in `docs/verification.md`. Reports are under `backend/target/{surefire-reports,failsafe-reports}/`.

## API contract

All request bodies are strict JSON: unknown fields, incorrect types, trailing JSON, and malformed bodies return **400**. Entities, password hashes, and database exceptions are never response DTOs.

### Register and log in

| Endpoint | Body | Success |
| --- | --- | --- |
| `POST /api/auth/register` | `email`, `password` | 201, JWT response |
| `POST /api/auth/login` | `email`, `password` | 200, JWT response |

Email is stripped of surrounding whitespace and lowercased with `Locale.ROOT` before validation and lookup; maximum length is 254. Normalized email uniqueness is enforced in PostgreSQL, including concurrent registrations. Passwords must be nonblank, 8–72 characters, and at most **72 UTF-8 bytes** (BCrypt's input limit); passwords are not trimmed or normalized. Only salted BCrypt hashes are persisted.

Both endpoints return exactly `accessToken` (JWT string), `tokenType` (`Bearer`), `expiresIn` (seconds), and `email` (normalized). Responses have `Cache-Control: no-store`. Duplicate email returns **409 `EMAIL_IN_USE`**; wrong passwords and unknown accounts both return **401 `INVALID_CREDENTIALS`**.

The backend uses Spring Security's `NimbusJwtEncoder` and OAuth2 resource-server `NimbusJwtDecoder`, restricted to **HS256**. Standard validators enforce the issuer, required expiration, expiration/not-before times with zero clock skew, and a numeric account subject. Management additionally requires that the subject still identify a persisted account. Tokens include `iss`, `sub`, `iat`, `nbf`, and `exp`; they contain no password/hash. There is no custom token filter or hand-written signature verification, no HTTP session, form login, or second authentication protocol.

Send `Authorization: Bearer <accessToken>` for **every** `/api/urls` management request. Tokens in query strings are not accepted. CSRF is disabled because authentication is explicit bearer headers, not ambient cookie credentials. Invalid/missing tokens return JSON **401**, not an HTML login page. There are no refresh, password-reset, or server-side logout endpoints in this phase. Clients discard tokens at logout; a token otherwise remains valid until expiration (or signing-secret rotation).

### URL creation and management

| Endpoint | Authentication | Success and behavior |
| --- | --- | --- |
| `POST /api/urls` | Bearer required | 201 + URL DTO; `Location: /api/urls/{id}` |
| `GET /api/urls?page=0&size=20` | Bearer required | 200 + owner-only page, newest ID first |
| `GET /api/urls/{id}` | Owner only | 200 + URL DTO, including inactive/expired records |
| `PATCH /api/urls/{id}` | Owner only | Body contains only Boolean `active`; 200 + updated URL DTO |
| `DELETE /api/urls/{id}` | Owner only | 204, deactivates without deleting; idempotent for an owned record |
| `GET /{shortCode}` | Public | 302 + original `Location`, empty body, `Cache-Control: no-store`, only when active, within its schedule, unexpired, and below its click cap |

Creation accepts required `originalUrl`, optional `customAlias`, optional `expiresAt`, optional `activatesAt`, and optional `maxClicks`. It creates an active URL owned by the JWT subject; passing `user`, `owner`, `userId`, `id`, or `active` in the creation JSON is not allowed.

URL DTO fields are `id`, `shortCode`, `shortUrl`, `originalUrl`, `createdAt`, `expiresAt`, `active`, `activatesAt`, `maxClicks`, and `clickCount`. A page contains `content` (URL DTOs), `page`, `size`, `totalElements`, and `totalPages`. Defaults: page 0, size 20. Bounds: page 0–1,000,000, size 1–100; invalid values return **400 `INVALID_PARAMETER`**. Totals and page contents include only the requesting owner's URLs, including inactive/expired ones. Pages beyond available results have empty content.

Missing, other-owner, and legacy-unowned IDs all return the same **404 `URL_NOT_FOUND`** for get/delete/patch/analytics. Ownership is queried in the service/repository, never inferred from a caller-supplied JSON owner. Analytics uses the same `ShortUrlService.requireOwned` guard; anonymous callers get 401.

Example after saving a returned access token privately in the shell variable `TOKEN`:

```bash
BASE="http://localhost:${SERVER_PORT:-8080}"
curl -i "$BASE/api/urls" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/some/long/path","customAlias":"My_link-1","expiresAt":"2099-01-01T00:00:00Z"}'
curl -i "$BASE/api/urls?page=0&size=20" -H "Authorization: Bearer $TOKEN"
# Replace 1 with the returned id.
curl -i -X PATCH "$BASE/api/urls/1" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"active":false}'
curl -i "$BASE/My_link-1"
```

### Alias, URL, and expiration rules

- Custom aliases are **3–32 ASCII characters**, restricted to `[A-Za-z0-9_-]`. No trimming or case conversion is performed. Lookup and uniqueness are **case-sensitive**: `MyLink` and `mylink` are distinct. Reserved names are checked **case-insensitively**.
- Reserved names: `api`, `auth`, `login`, `register`, `logout`, `dashboard`, `analytics`, `actuator`, `error`, `_next`, `admin`, `health`, `metrics`, `static`, `assets`, `favicon`, `robots`, `sitemap`, `swagger-ui`, and `v3` (also too short under the length rule). Invalid/reserved aliases return **400 `INVALID_ALIAS`**.
- Omitted/null `customAlias` uses an eight-character SecureRandom Base62 code. Empty aliases are invalid. Generated codes also avoid reserved names.
- PostgreSQL's unchanged `uk_short_urls_short_code` uniqueness constraint covers both random codes and aliases globally. Each insert uses a separate `REQUIRES_NEW` transaction with `saveAndFlush`, so a unique-constraint failure rolls back before retrying. Random collisions retry at most ten times; an alias conflict immediately returns **409 `ALIAS_IN_USE`**. There is no exists-then-insert race and no overwrite/upsert. Inactive, expired, and legacy codes remain reserved.
- `originalUrl` must be a nonblank syntactically valid absolute HTTP(S) URI with a valid host, no credentials, whitespace, or literal/encoded control characters. Malformed escapes and invalid ports are rejected. Localhost and IP hosts are accepted; use punycode for internationalized hosts. Validation does not resolve DNS or fetch destinations and does not guarantee reachability or trustworthiness.
- `expiresAt` is absent/null or an ISO-8601 timestamp string with an offset, within UTC calendar years 0001–9999. As in Phase 2, **past timestamps are intentionally accepted**; the resulting public link immediately returns **410 `URL_EXPIRED`**. Expiration is `expiresAt <= current time`, normalized to PostgreSQL microsecond precision. Reactivation does not bypass expiration. Inactive links return **410 `URL_INACTIVE`**.

### Scheduled activation, click caps, and QR sharing

Example creation body:

```json
{
  "originalUrl": "https://example.com/campaign",
  "activatesAt": "2027-01-01T09:00:00Z",
  "expiresAt": "2027-02-01T00:00:00Z",
  "maxClicks": 100
}
```

- `activatesAt` is absent/null for immediate activation, otherwise an offset-bearing ISO-8601 timestamp (UTC years 0001–9999, microsecond precision). Past activation times are accepted. It must precede expiration if both are present. Before this instant, redirects return **404 `URL_NOT_ACTIVE_YET`**; at the exact instant they become eligible without a scheduler tick. The UI accepts local time and sends UTC.
- `maxClicks` is absent/null for unlimited redirects, otherwise a JSON integer from 1 through 9007199254740991. Strings, fractions, zero, and negatives return **400 `INVALID_BODY`**. Once exhausted, GET and HEAD return **410 `URL_CLICK_CAP_REACHED`**.
- Every accepted GET atomically increments `short_urls.click_count` in PostgreSQL before sending the redirect, including repeat visits and bots. Concurrent requests across instances cannot exceed the cap. HEAD, rejected redirects, management reads, and QR generation do not consume it. A connection lost after admission may still count; this is an admitted-redirect count, not a unique visitor or verified destination-load count.
- This counter is independent of best-effort analytics. It survives Redis failures, restarts, and dropped analytics events; analytics totals can be lower. PostgreSQL failure fails closed rather than allowing an uncounted redirect. This adds one synchronous database UPDATE per accepted GET and a database read for HEAD, even on cache hits.
- Manual deactivation always wins. Reactivation does not bypass the schedule/expiration/cap or reset counts. Schedule and cap are set at creation; PATCH continues to accept only `active`.
- **QR & share** is available in creation results, dashboard rows, and link details, including previously created links. QR codes encode the exact short URL (not its destination), are generated locally with no third-party QR requests, and download as PNG. Web Share supports links and, where available, PNG files. Unsupported browsers offer clipboard/manual copying and PNG download. Native sharing generally requires HTTPS or localhost. QR scans follow the same redirect rules.
- Flyway **V5** adds nullable activation/cap fields and a durable nonnegative counter, preserving existing links. Existing recorded click events seed the counter; historical dropped events cannot be recovered. Restart/rebuild the backend to apply V5 automatically, and rebuild the frontend for the new controls. Old binaries must not serve redirects alongside the new version because they do not enforce caps.

### Existing URLs and schema upgrades

V1 is unchanged. V2 adds the `users` table, a nullable foreign key `short_urls.user_id`, and an owner/list index. Existing rows retain their codes, destinations, timestamps, expiration, and active state. Their owner is **NULL**: active/unexpired links keep redirecting publicly, but no account can list, retrieve, modify, delete, or claim them through management APIs. New API-created URLs always have an authenticated owner. No data reset or arbitrary backfill account is required.

### CORS and errors

`CORS_ALLOWED_ORIGIN` permits exactly one frontend origin (default `http://localhost:3000`) on `/api/**`. Bearer `Authorization` and JSON `Content-Type` headers and the management methods are allowed; `Location` is exposed. Preflight needs no JWT. Credentials are **not** enabled, and origins cannot be wildcard patterns. Different ports, schemes, and lookalike hosts are rejected with JSON **403 `FORBIDDEN`**. CORS is a browser policy, not a substitute for authentication.

All application, authentication/authorization, and CORS errors use the same four fields: `timestamp` (ISO instant), `status` (HTTP status number), `code`, and a sanitized `message`. No SQL, constraint details, stack traces, tokens, or hashes are included.

| Status | Codes |
| --- | --- |
| 400 | `INVALID_BODY`, `INVALID_URL`, `INVALID_ALIAS`, `INVALID_PARAMETER`, `INVALID_GEOGRAPHY_SELECTOR` |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN` |
| 404 | `URL_NOT_FOUND`, `URL_NOT_ACTIVE_YET`, `NOT_FOUND` for unimplemented/unknown routes |
| 409 | `EMAIL_IN_USE`, `ALIAS_IN_USE`, `URL_STATE_CHANGED` (retry after a concurrent availability change) |
| 410 | `URL_INACTIVE`, `URL_EXPIRED`, `URL_CLICK_CAP_REACHED` |
| 405 / 415 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` |
| 429 | `RATE_LIMITED` with `Retry-After` |
| 503 | `CODE_GENERATION_UNAVAILABLE` or `RATE_LIMIT_UNAVAILABLE` (with `Retry-After`) |
| 500 | `INTERNAL_ERROR` for unexpected failures |

## Phase 4 analytics contract

`GET /api/urls/{id}/analytics` requires the owning account's bearer token and returns **200**, `Cache-Control: no-store`, and exactly:

```json
{
  "totalClicks": 2,
  "clicksOverTime": [{"date": "2026-09-26", "clicks": 2}],
  "referrers": [{"label": "example.com", "clicks": 2}],
  "devices": [{"label": "Mobile", "clicks": 2}],
  "geography": [{"label": "Unknown", "clicks": 2}]
}
```

An unclicked URL returns `totalClicks: 0` and four empty arrays. This is a lifetime aggregate, without query parameters: UTC calendar days ascending, no zero-filled days; category buckets sorted by clicks descending, then label ascending. PostgreSQL performs `COUNT`/`GROUP BY`; raw events are never fetched into application memory for aggregation. The ownership check and five aggregate queries use one read-only repeatable-read snapshot. Counts include persisted successful GET redirects only, not HEAD, missing/inactive/expired URLs, rejected requests, or failed/dropped analytics tasks. Async arrival means counts may lag the redirect. Inactive/expired URLs retain analytics and remain owner-readable.

V3 adds `click_events` (URL foreign key, access timestamp, referrer host, device, geography), an URL/time index, and an active-expiry cleanup index. V1/V2 are unchanged. Management and cleanup only deactivate: URL IDs, ownership, and click history remain intact. Physical deletion, used by test cleanup, cascades click rows.

### Privacy and bounded background work

- Referrers retain only a lowercase HTTP(S) URI host: credentials, port, path, query, and fragment are removed. Missing referrers are `Direct`; malformed/non-HTTP(S)/oversized values are `Unknown`.
- User agents are reduced immediately to `Bot`, `Tablet`, `Mobile`, `Desktop`, or `Unknown`. Raw user agents and HTTP request objects are never queued. A validated public IP may be held transiently in the bounded in-memory analytics queue/worker for country/city lookup; it is never stored in PostgreSQL, Redis, application logs, or analytics responses. No visitor hash or unique-visitor tracking is added.
- Geography uses a local city database first, then a paid HTTPS provider if keyed or the configurable keyless HTTPS fallback otherwise. Only country code and city are persisted, never coordinates or full provider responses. Unknown, private/local addresses, historical clicks, and unsuccessful lookups display `Unknown`. Arbitrary geography headers are always ignored; forwarded IPs are used only through explicitly trusted proxy CIDRs. See the geolocation setup below.
- Spring's `analyticsExecutor` uses a fixed worker maximum and bounded queue, with `AbortPolicy`, never caller-runs or blocking queue insertion. Rejection and persistence failure drop the event, increment `AnalyticsService.droppedCount()`, and emit a sanitized warning on the first and every 100th drop. There is no public metrics API.
- Analytics is best effort, not a durable queue: saturation, DB failure, shutdown timeout, or process crashes can lose events. Shutdown allows up to ten seconds to drain. Aggregate bucket cardinality can grow with history; no BI/range/export/retention feature is added.
- Cleanup runs automatically after each configured fixed delay, one transaction and at most one batch per tick. PostgreSQL `FOR UPDATE SKIP LOCKED` allows concurrent workers without selecting locked rows. It marks expired active URLs inactive and invalidates after commit. A backlog takes multiple ticks; redirect expiry checks do not wait for cleanup.

### Cache consistency and outage policy

Spring Data Redis caches URL ID, destination, active flag, activation time, expiration, and an absolute validity deadline. Cache hits avoid a destination lookup, but every eligible redirect now performs authoritative PostgreSQL admission (an atomic GET counter UPDATE or a HEAD availability read), in addition to any asynchronous analytics INSERT. Misses load PostgreSQL. Every hit still checks active/schedule/expiry; expiry is exclusive (`expiresAt <= now` is gone). Redis entry TTL and the serialized deadline are bounded by both `CACHE_TTL` and URL expiration and are never extended on hits.

PATCH, DELETE/deactivation, and scheduled cleanup publish transaction events; only **AFTER_COMMIT** invalidates. Rollbacks leave the cache unchanged. Invalidation atomically rotates a per-code random generation and deletes data. A miss samples generation before loading PostgreSQL; a Lua compare-and-set permits population only if that generation is unchanged. Generation markers expire after twice the maximum cache lifetime; a slow loader cannot write after its original absolute deadline. This prevents an in-flight pre-mutation miss from repopulating after a successful invalidation. Redis is configured without eviction/persistence locally; generation loss through eviction/restart is treated as degraded consistency, still bounded by the absolute deadline.

**Cache invalidation is bounded-staleness, not linearizable.** A redirect already admitted may finish after a concurrent deactivation. Authoritative database admission prevents stale cache entries from bypassing deactivation, schedules, expiration, or caps. If invalidation fails after reactivation, a stale inactive cache entry can still reject requests until its original deadline, **at most 30 seconds by default (configurable up to five minutes)**, never indefinitely. Hits cannot refresh this deadline. The same bound covers a process crash between DB commit and invalidation, or lost generation metadata. There is no transactional Redis/PostgreSQL outbox, delivery retry, or instant multi-instance guarantee. Keep instance clocks synchronized. Direct SQL mutations bypass events and have the same TTL bound.

Redis read/write errors or corrupt payloads fall back to PostgreSQL; cache failures are counted internally and logged without sensitive payloads on the first/every 100th failure. Redis connect and command timeouts are 500 ms each, so outages can increase request latency. Cache fallback does not disable request protection:

- Auth and URL creation **fail closed with JSON 503 `RATE_LIMIT_UNAVAILABLE` and `Retry-After`** if Redis cannot enforce their budget.
- Redirects use a fixed-memory, per-process **global** fallback budget (default 100 per 60 seconds, shared across all clients); after exhaustion they also return 503. This bounds fallback DB load without an unbounded per-client map. The fallback budget resets on process restart and multiplies across application instances; it is not a distributed substitute for Redis.
- Healthy Redis enforces an atomic Lua `INCR` + first-hit `PEXPIRE` fixed window per category/client. Auth register/login share one category; creation and redirect each have their own. Exceeded limits return the normal four-field JSON error with **429 `RATE_LIMITED`**, `Retry-After` (whole seconds rounded up), and `Cache-Control: no-store`.
- Keys contain HMAC-SHA256 of the socket peer IP using the configured secret, never the raw IP, and expire with the window. NAT clients share a budget. `server.forward-headers-strategy=none`: the rate limiter ignores `X-Forwarded-For` and `Forwarded`. Behind a proxy the proxy's socket address remains the rate-limit key. The separate analytics-only trusted-proxy configuration below does not change rate-limit identity or globally enable forwarding headers.
- Limiting is selected by the actual Spring handler, not a raw URI heuristic. Auth POST, authenticated URL creation, and all GET/HEAD requests routed to the redirect controller consume their category budget. Encoded aliases, legacy codes, and misses cannot bypass it. Framework resource handlers, error handlers, OPTIONS/preflight, and management/analytics reads are not limited. A one-segment name such as `/favicon.ico` currently resolves through the redirect controller, so it is treated as a redirect miss rather than a static resource.

### Phase 4 settings

| Variable | Default | Meaning |
| --- | --- | --- |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Backend Redis connection; port also controls loopback Compose publishing |
| `REDIS_PASSWORD` | Empty | Only for an externally managed authenticated Redis |
| `REDIS_PREFIX` | `shortify:` | Shared namespace across instances of the same deployment; isolate environments/tests |
| `CACHE_TTL` | `30s` | Positive maximum cache lifetime, at most `5m` |
| `RATE_LIMIT_WINDOW` | `60s` | Positive fixed-window duration, at most `1d` |
| `RATE_LIMIT_AUTH` / `RATE_LIMIT_CREATE` / `RATE_LIMIT_REDIRECT` | `20` / `60` / `300` | Positive per-peer requests per window |
| `RATE_LIMIT_REDIRECT_FALLBACK` | `100` | Positive global per-process redirect allowance during Redis failure |
| `ANALYTICS_WORKERS` | `2` | Fixed executor workers, 1–32 |
| `ANALYTICS_QUEUE_CAPACITY` | `1000` | Pending tasks, 1–100000 |
| `CLEANUP_INTERVAL` | `60000` | Fixed delay and initial delay, milliseconds |
| `CLEANUP_BATCH_SIZE` | `200` | Rows per tick, 1–10000 |

## Country and city geolocation

Geolocation is an **approximation from the public IP**, not GPS or a physical address. V4 adds nullable `country_code` and `city` fields to click records without altering historical events or V1–V3 migrations. Old events remain Unknown because no historical IPs were stored. The existing analytics response keeps its five fields; `geography` now groups by country code.

The additional owner-only endpoint is:

```text
GET /api/urls/{id}/analytics/geography?by=country
GET /api/urls/{id}/analytics/geography?by=city
```

It returns `{ "totalClicks": 12, "buckets": [{ "label": "US", "clicks": 8 }] }`, with at most ten buckets ordered by count descending then label. City labels include country context. Missing values are Unknown. `by` defaults to `country`; unsupported values return 400. Counts include all persisted successful GET clicks, including the existing Bot device category, rather than introducing new bot filtering. The frontend calculates rounded percentages against `totalClicks`, so the top ten need not add up to 100%. Reads are owner-protected and `no-store`; another owner's ID remains a concealed 404.

### Providers and configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `GEO_ENABLED` | `true` | Enable lookup; false skips address extraction and records Unknown |
| `GEO_DATABASE_PATH` | Empty | Native backend path to a readable MaxMind-compatible city MMDB file |
| `GEO_DATABASE_FILE` | Unset | Host MMDB file mounted by the optional Compose override |
| `GEO_API_KEY` | Empty | Paid ip-api HTTPS key; when set, takes precedence over the free fallback |
| `GEO_FREE_FALLBACK_ENABLED` | `true` | Use keyless ipwho.is HTTPS lookup when no paid key exists and local city data is missing |
| `GEO_TRUSTED_PROXIES` | Empty | Comma-separated trusted proxy CIDRs, for analytics IP extraction only |

1. Obtain a city database, such as **GeoLite2 City**, through [MaxMind](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data/), following its account, license, attribution, and update requirements. Keep the file outside Git. For a native backend set `GEO_DATABASE_PATH` to the downloaded file. The reader is reused until application restart; restart after replacing the file to load updates.
2. For Docker, set `GEO_DATABASE_FILE` in the ignored root `.env` to the absolute host path. Ensure the non-root container user can read it, then run:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.geo.yml --profile app up -d --build --wait
   ```

   The optional override mounts the existing file read-only and sets the container path. A missing source file fails the mount rather than creating an empty directory. The default Compose file remains usable without any database.
3. Without a paid key, the enabled-by-default **ipwho.is HTTPS fallback** requests only `success`, `country_code`, and `city`, so public-IP lookups work without provisioning a database/key. **It sends the visitor's public IP to an external provider.** Set `GEO_FREE_FALLBACK_ENABLED=false` for local-only operation without a key. Review [ipwho.is usage terms and limits](https://ipwhois.io/): the free service is intended for non-commercial use; use a licensed local database or paid provider for production/commercial workloads as appropriate. Disclose external processing in your privacy notice.
4. To use paid ip-api instead, put its key in `GEO_API_KEY` in the ignored server-side `.env`. Never place it in `NEXT_PUBLIC_*`, frontend files, committed configuration, or chat. Only `status`, `countryCode`, and `city` are requested over HTTPS. Both external modes have a maximum 1.5-second timeout and no per-click retry. The free **HTTP** ip-api endpoint is never used. A configured paid key does not silently fail over to another provider.
5. With the free fallback disabled and neither a readable database nor a key, the app starts but records Unknown and warns that no lookup source exists. Missing/corrupt local data and failed remote lookups do not stop redirects; any usable local country is retained when fallback fails. Provider errors/rate limits trigger temporary backoff, with sanitized warnings (no visitor IPs, keys, or response bodies). Historical Unknown events cannot be backfilled because raw IPs were never stored.

Lookups run on the bounded analytics worker **before** its database insert transaction, never on the redirect thread. Outbound fallback is limited to one request per process with no additional request queue; concurrent excess lookups retain local data or Unknown. Responses are limited to 8 KiB, and failures back off from 60 seconds up to 15 minutes (numeric provider retry delays are honored up to 24 hours). Slow providers may delay analytics or fill the bounded queue, but do not turn geolocation into synchronous redirect latency. Existing best-effort/drop behavior still applies. Redirects remain HTTP **302**, and HEAD does not record a click.

### Proxy trust and local development

By default analytics uses the socket peer and ignores forwarded IP headers. Local/private addresses, malformed values, reserved ranges, and loopback produce Unknown without provider calls; localhost testing is not a way to obtain the user's real country/city.

Configure `GEO_TRUSTED_PROXIES` only with the CIDRs of reverse proxies you control. When the immediate peer is trusted, the resolver validates the bounded `X-Forwarded-For` chain and walks from right to left through trusted hops, selecting the first untrusted address. The proxy must overwrite or safely append forwarding metadata, and direct backend access should be restricted appropriately. Do not trust all addresses (`0.0.0.0/0` or `::/0`) or a shared network merely to make location appear. `Forwarded`, `X-Real-IP`, and client-supplied country/city headers are not alternative sources. The Next.js frontend does not proxy public redirects in this application; short URLs point directly to the backend, so no frontend forwarding layer is added.

## Frontend development

With the backend running in another terminal:

```bash
cd frontend
npm ci
cp .env.example .env.local
# Set NEXT_PUBLIC_API_BASE_URL to the backend origin, e.g. http://localhost:8081.
npm run dev
```

Open http://localhost:3000. Only copy the example when `.env.local` does not already exist. Next.js reads this frontend environment file; it does not read the root backend `.env`. **Never copy `JWT_SECRET` or database credentials into frontend settings.** The sole public configuration value is the backend origin.

Routes:

- `/`: URL creation; guests can keep their destination while signing in.
- `/login` and `/register`: backend account authentication.
- `/dashboard`: paginated owner-only links and creation form.
- `/urls/{id}`: destination, availability schedule/cap/count, QR generation/sharing, short URL copying, and activation/deactivation.
- `/urls/{id}/analytics`: total clicks, daily activity, referrers, devices, and geography.

Next.js consumes the Spring JWT and sends bearer headers. Tokens live in per-tab `sessionStorage` and client memory, persist across a reload in that tab, and are cleared at logout or session expiry. There is no refresh token or independent frontend identity system. Session storage is readable by JavaScript: an XSS vulnerability could expose a token. Use TLS, keep dependencies updated, and never render untrusted HTML. Logging out discards the local token; it does not revoke copies held elsewhere.

Geography displays `Unknown` where no trustworthy information exists. Deactivation retains the URL and analytics, and reactivation cannot override expiration. Analytics may appear after a short processing delay; refresh the view after opening a short link.

```bash
npm run typecheck
npm test
npm run build
# Production build outside Docker:
npm start
```

See [`frontend/README.md`](frontend/README.md) for browser test commands and frontend-specific configuration.

## Layout and responsibilities

- `backend/src/main/java/com/shortify/controller/`: thin HTTP/DTO adapters.
- `service/`: authentication, validation, collision-safe writes, owner-scoped management, and public resolution.
- `security/` and `config/`: authenticated account lookup, shared JSON security errors, standard Nimbus/JWT configuration, CORS, strict JSON, and UTC clock.
- `entity/`, `repository/`, and `dto/`: JPA persistence, owner-constrained queries, and explicit public DTOs.
- `backend/src/main/resources/db/migration/`: versioned PostgreSQL schema.
- `backend/src/test/java/com/shortify/`: unit, PostgreSQL persistence/migration, and actual HTTP integration tests.
- `frontend/app/`, `components/`, and `lib/`: Next.js routes, shared UI/authentication state, API client, and typed responses.
- `backend/Dockerfile`, `frontend/Dockerfile`, and `docker-compose.yml`: independently buildable applications and local PostgreSQL/Redis infrastructure.
- `docs/verification.md`: phase-by-phase execution evidence.

## Stop PostgreSQL and Redis

From the repository root, `docker compose --profile app down` stops this project's containers while preserving the PostgreSQL volume. Redis cache/rate-limit state is intentionally lost. **Adding `-v` deletes database data**; use it only for an intentional development reset. Stop independently started development servers separately with Ctrl+C.

See `phases.md` and `scope.md` for approved boundaries and local Git history for each implementation stage. No Kafka, microservices, or extra product features have been added.
