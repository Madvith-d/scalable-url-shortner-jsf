# Shortify — Phase 3

Shortify is a Java 21 / Spring Boot 3.5 modular backend backed by Docker PostgreSQL. Phases 1–3 implement collision-safe URL shortening, public redirects, BCrypt/JWT accounts, owner-only URL management, custom aliases, and optional expiration.

**Phase 4 has not started.** Redis, analytics, rate limiting, cleanup jobs, and the Next.js frontend are not implemented. Use TLS before exposing bearer tokens outside local development. Authentication endpoints are not yet rate-limited.

## Prerequisites

- JDK 21; this workspace includes `.local/jdk21`.
- Docker Engine with Docker Compose.
- Internet access for the first build, plus `curl` or `wget` and `unzip` on Linux/macOS.
- No installed Maven is needed. The official Maven Wrapper 3.3.4 in `backend/` bootstraps Maven 3.9.16.

Commands below use **Bash**, starting at the repository root. If using Fish, enter `bash` first.

## Configure and start PostgreSQL

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
docker compose up -d --wait postgres
docker compose ps
```

Only source your own trusted `.env`; keep values valid Bash assignments. PostgreSQL binds to loopback and persists data in the named `postgres_data` volume.

## Build, test, and run

With PostgreSQL healthy and the settings exported:

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
- Failsafe starts real HTTP servers on random ports and exercises the full Spring Security filter chain against **real PostgreSQL**, not H2 or mock authentication.
- Phase 2 HTTP tests now register an account and attach its bearer token to management requests; public redirect requests remain unauthenticated. Existing validation/collision/expiry coverage is retained.
- Phase 1 persistence assertions are retained using the repository directly, rather than preserving unauthenticated service accessors.
- New tests cover account normalization/BCrypt/login, owner isolation, pagination, activation, invalid JWTs, aliases/concurrency, legacy links, and CORS. A migration test creates a uniquely named schema, applies V1, inserts a legacy URL, applies V2, verifies preservation, and drops only that schema.
- Persistence tests roll back. HTTP tests delete only their own tracked URLs/accounts; tables are never truncated. Identity sequences can advance. Flyway changes to the main schema persist.
- No database tests are silently skipped. Use a local/development database whose user can create a temporary schema for the migration test.

`./mvnw test` runs unit tests only; **use `clean verify` for acceptance**. Actual counts and results are in `docs/verification.md`. Reports are under `backend/target/{surefire-reports,failsafe-reports}/`.

## Phase 3 API contract

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

Missing, other-owner, and legacy-unowned IDs all return the same **404 `URL_NOT_FOUND`** for get/delete/patch. Ownership is queried in the service/repository, never inferred from a caller-supplied JSON owner. `ShortUrlService.requireOwned` is the common guard to reuse before future analytics reads. `/api/urls/{id}/analytics` is already covered by the authentication matcher but **has no endpoint yet**: authenticated callers get 404 and anonymous callers get 401.

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
- `expiresAt` is absent/null or an ISO-8601 timestamp string with an offset. As in Phase 2, **past timestamps are intentionally accepted**; the resulting public link immediately returns **410 `URL_EXPIRED`**. Expiration is `expiresAt <= current time`, normalized to PostgreSQL microsecond precision. Reactivation does not bypass expiration. Inactive links return **410 `URL_INACTIVE`**.

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
| 503 | `CODE_GENERATION_UNAVAILABLE` after exhausting random-code attempts |
| 500 | `INTERNAL_ERROR` for unexpected failures |

## Phase 5 frontend integration plan (not implemented)

Next.js will be a client of these Spring APIs, **not a second authentication system**. Login/register will consume the JWT response; the API client will attach `Authorization: Bearer` to URL create/list/get/patch/delete and, after Phase 4, analytics requests. Keep tokens in client memory for the initial implementation, clear them on logout/401, and require login again after reload/expiry. Do not expose `JWT_SECRET` as a `NEXT_PUBLIC_*` setting or add a separate NextAuth/session issuer. Requests use the configured backend origin without credentialed cookies. Dashboard and URL management screens will consume the page/URL DTOs and display the shared JSON error messages. No frontend files or dependencies are introduced in Phase 3.

## Layout and responsibilities

- `backend/src/main/java/com/shortify/controller/`: thin HTTP/DTO adapters.
- `service/`: authentication, validation, collision-safe writes, owner-scoped management, and public resolution.
- `security/` and `config/`: authenticated account lookup, shared JSON security errors, standard Nimbus/JWT configuration, CORS, strict JSON, and UTC clock.
- `entity/`, `repository/`, and `dto/`: JPA persistence, owner-constrained queries, and explicit public DTOs.
- `backend/src/main/resources/db/migration/`: versioned PostgreSQL schema.
- `backend/src/test/java/com/shortify/`: unit, PostgreSQL persistence/migration, and actual HTTP integration tests.
- `frontend/.gitkeep`: directory only; frontend work is deferred to Phase 5.

## Stop PostgreSQL

From the repository root, `docker compose down` stops this project's database while preserving its volume. **`docker compose down -v` deletes the database data**; use it only for an intentional development reset.

See `phases.md` and `scope.md` for approved boundaries. Phase 4 requires separate implementation approval.
