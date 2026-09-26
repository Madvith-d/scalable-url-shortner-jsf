# Shortify — Phase 4

Shortify is a Java 21 / Spring Boot 3.5 modular backend backed by Docker PostgreSQL and Redis. Phases 1–4 implement collision-safe URL shortening, public redirects, BCrypt/JWT accounts, owner-only URL management, custom aliases, expiration, Redis caching/rate limiting, asynchronous click analytics, and scheduled expiry cleanup.

**No frontend is implemented.** Phase 5 remains separate work. Use TLS before exposing bearer tokens outside local development. Read the cache consistency, outage, and best-effort analytics limitations below before deployment.

## Prerequisites

- JDK 21; this workspace includes `.local/jdk21`.
- Docker Engine with Docker Compose.
- Internet access for the first build, plus `curl` or `wget` and `unzip` on Linux/macOS.
- No installed Maven is needed. The official Maven Wrapper 3.3.4 in `backend/` bootstraps Maven 3.9.16.

Commands below use **Bash**, starting at the repository root. If using Fish, enter `bash` first.

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

Set alternative ports in `.env` **before** starting; do not stop unrelated containers. The verified workspace uses PostgreSQL **5434** and HTTP **8081**. Changing credentials in `.env` does not change credentials already initialized in a database volume.

Compose reads `.env`; Spring Boot and Maven do not. Export the root `.env` in each new shell before running the backend or tests:

```bash
set -a
source .env
set +a
export JAVA_HOME="$PWD/.local/jdk21"
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
| `GET /{shortCode}` | Public | 302 + original `Location`, empty body, `Cache-Control: no-store`, only when active and unexpired |

Creation accepts required `originalUrl`, optional `customAlias`, and optional `expiresAt`. It creates an active URL owned by the JWT subject; passing `user`, `owner`, `userId`, `id`, or `active` in the creation JSON is not allowed.

URL DTO fields remain `id`, `shortCode`, `shortUrl`, `originalUrl`, `createdAt`, `expiresAt`, and `active`. A page contains `content` (URL DTOs), `page`, `size`, `totalElements`, and `totalPages`. Defaults: page 0, size 20. Bounds: page 0–1,000,000, size 1–100; invalid values return **400 `INVALID_PARAMETER`**. Totals and page contents include only the requesting owner's URLs, including inactive/expired ones. Pages beyond available results have empty content.

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

### Existing URLs and schema upgrades

V1 is unchanged. V2 adds the `users` table, a nullable foreign key `short_urls.user_id`, and an owner/list index. Existing rows retain their codes, destinations, timestamps, expiration, and active state. Their owner is **NULL**: active/unexpired links keep redirecting publicly, but no account can list, retrieve, modify, delete, or claim them through management APIs. New API-created URLs always have an authenticated owner. No data reset or arbitrary backfill account is required.

### CORS and errors

`CORS_ALLOWED_ORIGIN` permits exactly one frontend origin (default `http://localhost:3000`) on `/api/**`. Bearer `Authorization` and JSON `Content-Type` headers and the management methods are allowed; `Location` is exposed. Preflight needs no JWT. Credentials are **not** enabled, and origins cannot be wildcard patterns. Different ports, schemes, and lookalike hosts are rejected with JSON **403 `FORBIDDEN`**. CORS is a browser policy, not a substitute for authentication.

All application, authentication/authorization, and CORS errors use the same four fields: `timestamp` (ISO instant), `status` (HTTP status number), `code`, and a sanitized `message`. No SQL, constraint details, stack traces, tokens, or hashes are included.

| Status | Codes |
| --- | --- |
| 400 | `INVALID_BODY`, `INVALID_URL`, `INVALID_ALIAS`, `INVALID_PARAMETER` |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN` |
| 404 | `URL_NOT_FOUND`, `NOT_FOUND` for unimplemented/unknown routes |
| 409 | `EMAIL_IN_USE`, `ALIAS_IN_USE` |
| 410 | `URL_INACTIVE`, `URL_EXPIRED` |
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
- User agents are reduced immediately to `Bot`, `Tablet`, `Mobile`, `Desktop`, or `Unknown`. No raw UA or client IP is stored in PostgreSQL or queued; tasks capture only the sanitized event, not the HTTP request.
- Geography is always `Unknown`. No IP geolocation lookup or geography header trust is enabled; spoofed country/forwarding headers are ignored. Optional trusted-proxy geography is not implemented.
- Spring's `analyticsExecutor` uses a fixed worker maximum and bounded queue, with `AbortPolicy`, never caller-runs or blocking queue insertion. Rejection and persistence failure drop the event, increment `AnalyticsService.droppedCount()`, and emit a sanitized warning on the first and every 100th drop. There is no public metrics API.
- Analytics is best effort, not a durable queue: saturation, DB failure, shutdown timeout, or process crashes can lose events. Shutdown allows up to ten seconds to drain. Aggregate bucket cardinality can grow with history; no BI/range/export/retention feature is added.
- Cleanup runs automatically after each configured fixed delay, one transaction and at most one batch per tick. PostgreSQL `FOR UPDATE SKIP LOCKED` allows concurrent workers without selecting locked rows. It marks expired active URLs inactive and invalidates after commit. A backlog takes multiple ticks; redirect expiry checks do not wait for cleanup.

### Cache consistency and outage policy

Spring Data Redis caches URL ID, destination, active flag, expiration, and an absolute validity deadline. Public cache hits do not query PostgreSQL **for resolution**; the separate asynchronous click INSERT is expected. Misses load PostgreSQL. Every hit still checks active/expiry; expiry is exclusive (`expiresAt <= now` is gone). Redis entry TTL and the serialized deadline are bounded by both `CACHE_TTL` and URL expiration and are never extended on hits.

PATCH, DELETE/deactivation, and scheduled cleanup publish transaction events; only **AFTER_COMMIT** invalidates. Rollbacks leave the cache unchanged. Invalidation atomically rotates a per-code random generation and deletes data. A miss samples generation before loading PostgreSQL; a Lua compare-and-set permits population only if that generation is unchanged. Generation markers expire after twice the maximum cache lifetime; a slow loader cannot write after its original absolute deadline. This prevents an in-flight pre-mutation miss from repopulating after a successful invalidation. Redis is configured without eviction/persistence locally; generation loss through eviction/restart is treated as degraded consistency, still bounded by the absolute deadline.

**This is bounded-staleness, not linearizable invalidation.** A redirect already in flight may finish with its prior snapshot. If Redis invalidation fails, the committed mutation still succeeds; another instance or a recovered Redis may serve its old cache until the original deadline, **at most 30 seconds by default (configurable up to five minutes)**, never indefinitely. Hits cannot refresh this deadline. The same bound covers a process crash between DB commit and invalidation, or lost generation metadata. There is no transactional Redis/PostgreSQL outbox, delivery retry, or instant multi-instance guarantee. Keep instance clocks synchronized. Direct SQL mutations bypass events and have the same TTL bound.

Redis read/write errors or corrupt payloads fall back to PostgreSQL; cache failures are counted internally and logged without sensitive payloads on the first/every 100th failure. Redis connect and command timeouts are 500 ms each, so outages can increase request latency. Cache fallback does not disable request protection:

- Auth and URL creation **fail closed with JSON 503 `RATE_LIMIT_UNAVAILABLE` and `Retry-After`** if Redis cannot enforce their budget.
- Redirects use a fixed-memory, per-process **global** fallback budget (default 100 per 60 seconds, shared across all clients); after exhaustion they also return 503. This bounds fallback DB load without an unbounded per-client map. The fallback budget resets on process restart and multiplies across application instances; it is not a distributed substitute for Redis.
- Healthy Redis enforces an atomic Lua `INCR` + first-hit `PEXPIRE` fixed window per category/client. Auth register/login share one category; creation and redirect each have their own. Exceeded limits return the normal four-field JSON error with **429 `RATE_LIMITED`**, `Retry-After` (whole seconds rounded up), and `Cache-Control: no-store`.
- Keys contain HMAC-SHA256 of the socket peer IP using the configured secret, never the raw IP, and expire with the window. NAT clients share a budget. `server.forward-headers-strategy=none`: neither `X-Forwarded-For` nor `Forwarded` is trusted. Behind a proxy the proxy's socket address is the client key; explicit trusted-proxy handling is future work, not a reason to enable arbitrary forwarding headers.
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

## Phase 5 frontend integration plan (not implemented)

Next.js will be a client of these Spring APIs, **not a second authentication system**. Login/register will consume the JWT response; the API client will attach `Authorization: Bearer` to URL create/list/get/patch/delete and analytics requests. Keep tokens in client memory for the initial implementation, clear them on logout/401, and require login again after reload/expiry. Do not expose `JWT_SECRET` as a `NEXT_PUBLIC_*` setting or add a separate NextAuth/session issuer. Requests use the configured backend origin without credentialed cookies. Dashboard and URL management screens will consume the page/URL DTOs and display the shared JSON error messages. No frontend files or dependencies are introduced in Phases 1–4.

## Layout and responsibilities

- `backend/src/main/java/com/shortify/controller/`: thin HTTP/DTO adapters.
- `service/`: authentication, validation, collision-safe writes, owner-scoped management, and public resolution.
- `security/` and `config/`: authenticated account lookup, shared JSON security errors, standard Nimbus/JWT configuration, CORS, strict JSON, and UTC clock.
- `entity/`, `repository/`, and `dto/`: JPA persistence, owner-constrained queries, and explicit public DTOs.
- `backend/src/main/resources/db/migration/`: versioned PostgreSQL schema.
- `backend/src/test/java/com/shortify/`: unit, PostgreSQL persistence/migration, and actual HTTP integration tests.
- `frontend/.gitkeep`: directory only; frontend work is deferred to Phase 5.

## Stop PostgreSQL and Redis

From the repository root, `docker compose down` stops this project's PostgreSQL and Redis while preserving the PostgreSQL volume. Redis cache/rate-limit state is intentionally lost. **`docker compose down -v` deletes the database data**; use it only for an intentional development reset.

See `phases.md` and `scope.md` for approved boundaries. Phase 4 is implemented; frontend/Phase 5 requires separate approval. No Kafka, microservices, or extra infrastructure has been added.
