# Shortify — Phase 2

Shortify is a Java 21 / Spring Boot 3.5 modular backend backed by Docker PostgreSQL. Phase 2 adds URL creation, cryptographically random short codes, public redirects, metadata retrieval, deactivation, optional expiration, and consistent JSON errors. The Phase 1 entity, repository, Flyway schema, and persistence tests are retained.

**Phase 3 has not started.** Custom aliases, authentication/ownership, Redis, analytics, and the frontend are not implemented. Management endpoints are currently public: use this phase locally, not as an authenticated public service.

## Prerequisites

- JDK 21. Set `JAVA_HOME` and add `$JAVA_HOME/bin` to `PATH`.
- Docker Engine with Docker Compose.
- Internet access for the first build, plus `curl` or `wget` and `unzip` on Linux/macOS.
- No installed Maven is needed. The official Apache Maven Wrapper 3.3.4 in `backend/` bootstraps Maven 3.9.16. Windows users can use `mvnw.cmd`.

Commands below use **Bash**, starting at the repository root. If using Fish, enter `bash` first.

## Configure and start PostgreSQL

For a fresh checkout (do not overwrite an existing configured `.env`):

```bash
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD to a strong local password.
docker compose up -d --wait postgres
docker compose ps
```

`.env` is ignored by Git. Compose requires a nonempty `POSTGRES_PASSWORD`. PostgreSQL defaults to database `shortify`, user `shortify`, and a host port bound only to `127.0.0.1`. Data persists in the named `postgres_data` volume.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_DB` | `shortify` | Database name, shared by Compose and backend |
| `POSTGRES_USER` | `shortify` | Database user, shared by Compose and backend |
| `POSTGRES_PASSWORD` | Required | Database password |
| `DB_HOST` | `localhost` | Backend database hostname |
| `DB_PORT` | `5432` | Published PostgreSQL port and backend database port |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `SHORTIFY_BASE_URL` | `http://localhost:${SERVER_PORT}` (8080 by default) | Public origin used in response `shortUrl` values; set to the externally reachable HTTP(S) origin when needed |

Set alternative ports in `.env` **before** starting if necessary; do not stop unrelated containers. The verified local configuration uses PostgreSQL **5434** and HTTP **8081**. Changing credentials in `.env` does not update credentials already initialized in a PostgreSQL volume.

Compose loads `.env`, but Spring Boot/Maven do not. Before every build or application run in a new shell, export the settings from the repository root:

```bash
set -a
source .env
set +a
# This workspace has a local JDK 21; otherwise use your installed JDK 21 path.
export JAVA_HOME="$PWD/.local/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Only source your own trusted `.env`; keep values valid Bash assignments. A random hexadecimal password avoids shell quoting issues.

## Build and test

With PostgreSQL healthy and the variables exported:

```bash
cd backend
./mvnw --version
./mvnw clean verify
```

- Surefire runs unit tests for URL validation, the generator, and service behavior, including expiry boundaries and collision retry limits.
- Failsafe runs the unchanged `ShortUrlPersistenceIT` against real PostgreSQL with rolled-back transactions.
- `ShortUrlApiIT` starts the embedded HTTP server on a random port and makes actual HTTP requests with redirects disabled. It checks creation/retrieval/302, validation, missing records, expiration, deactivation, concurrent uniqueness, forced real PostgreSQL unique-constraint collisions, exhausted retries, and sanitized failures.
- API tests commit through HTTP and delete only their own tracked records afterward. They do not truncate tables or delete unrelated records. Identity sequences can advance. Flyway migrations are not rolled back.
- No H2 replacement or silent database-test skip is used. Use a local/development PostgreSQL database.

`./mvnw test` runs unit tests only; **use `clean verify` for phase acceptance**. Reports are in `backend/target/surefire-reports/` and `backend/target/failsafe-reports/`. See `docs/verification.md` for actual executed results.

## Start the backend

From `backend/`, with the same exported environment:

```bash
./mvnw spring-boot:run
# Alternatively, after verify:
# java -jar target/shortify-0.0.1-SNAPSHOT.jar
```

Startup should confirm a PostgreSQL connection, Flyway validation/migration, Hibernate initialization, and `Started ShortifyApplication`. Flyway owns the schema; Hibernate uses `ddl-auto: validate`.

Stop a manually started backend with Ctrl+C. The integration-test server terminates with the test JVM.

## Phase 2 API

Use your configured port (8081 in the verified local `.env`). These examples assume `.env` has been exported in the calling shell:

```bash
BASE="http://localhost:${SERVER_PORT:-8080}"
curl -i "$BASE/api/urls" \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/some/long/path"}'
```

Creation returns **201 Created**, `Location: /api/urls/{id}`, and a DTO like:

```json
{
  "id": 1,
  "shortCode": "a8K2xB9z",
  "shortUrl": "http://localhost:8081/a8K2xB9z",
  "originalUrl": "https://example.com/some/long/path",
  "createdAt": "2026-09-26T12:00:00Z",
  "expiresAt": null,
  "active": true
}
```

IDs, codes, and timestamps above are illustrative. Use the returned ID and code:

```bash
curl -i "$BASE/api/urls/1"
curl -i "$BASE/a8K2xB9z"
curl -i -X DELETE "$BASE/api/urls/1"
curl -i "$BASE/a8K2xB9z"
```

| Endpoint | Success | Behavior |
| --- | --- | --- |
| `POST /api/urls` | 201 + metadata DTO | Creates a new active short URL; repeated destinations get independent codes |
| `GET /{shortCode}` | 302 + `Location` | Redirects only active, unexpired URLs; `Cache-Control: no-store` prevents caching stale redirects |
| `GET /api/urls/{id}` | 200 + metadata DTO | Retrieves metadata even for inactive/expired URLs |
| `DELETE /api/urls/{id}` | 204, empty body | Deactivates without deleting; repeated deletion of an existing record remains 204 |

### Validation and expiration

- `originalUrl` must be a nonblank JSON string containing a syntactically valid absolute HTTP(S) URI, with a valid host and no credentials, literal or percent-encoded control characters.
- Relative URLs, unsupported schemes, malformed percent escapes, invalid hosts/ports, whitespace, and user-info (`user:password@host`, including empty user-info) are rejected. Ports, if supplied, must be at most 65535.
- Validation does **not** resolve DNS, fetch the destination, or promise that the destination is reachable or trustworthy. Localhost and syntactically valid IP hosts are accepted. Use punycode for internationalized hostnames.
- Only `originalUrl` and optional `expiresAt` are accepted. Unknown fields (including `customAlias`), wrong JSON types, malformed JSON, and trailing JSON are rejected. Entities and database exceptions are never serialized in responses.
- `expiresAt` is absent/null or an ISO-8601 timestamp string with an offset, for example `2099-01-01T00:00:00Z`. Past timestamps are intentionally accepted to allow testing expired links. A link is expired when `expiresAt <= current time`. Timestamps are normalized to PostgreSQL microsecond precision, truncating finer fractional digits.

```bash
curl -i "$BASE/api/urls" -H 'Content-Type: application/json' \
  -d '{"originalUrl":"https://example.com/expired","expiresAt":"2000-01-01T00:00:00Z"}'
# GET the returned /{shortCode}: 410 URL_EXPIRED, not a redirect.
```

### Consistent errors

Errors use the same JSON structure, without SQL, database constraint names, stack traces, or entity internals:

```json
{
  "timestamp": "2026-09-26T12:00:00Z",
  "status": 404,
  "code": "URL_NOT_FOUND",
  "message": "The short URL was not found."
}
```

| Status | Codes |
| --- | --- |
| 400 | `INVALID_BODY`, `INVALID_URL`, `INVALID_PARAMETER` |
| 404 | `URL_NOT_FOUND` (unknown ID/code), `NOT_FOUND` (unknown route) |
| 410 | `URL_INACTIVE`, `URL_EXPIRED` (redirect resolution only) |
| 405 / 415 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` |
| 503 | `CODE_GENERATION_UNAVAILABLE` after exhausting collision retries |
| 500 | `INTERNAL_ERROR`, generic message for unexpected failures |

### Collision-safe code generation

`ShortCodeGenerator` uses `SecureRandom.nextInt(62)` to generate uniformly selected **eight-character Base62** codes (`0-9A-Za-z`). Randomness alone does not guarantee uniqueness: PostgreSQL's existing `uk_short_urls_short_code` constraint is authoritative.

`ShortUrlService` attempts creation at most ten times. Every insert runs through a separate Spring-managed `ShortUrlWriter` bean using `REQUIRES_NEW` and `saveAndFlush`. The failed transaction is rolled back before the service retries, avoiding PostgreSQL's aborted-transaction trap. Only SQLSTATE `23505` on that exact short-code constraint is retried; other persistence failures produce a sanitized error. There is no race-prone exists-then-insert check. Inactive and expired codes remain reserved.

## Layout and responsibilities

- `backend/src/main/java/com/shortify/controller/`: thin HTTP/DTO adapters and redirect response construction.
- `backend/src/main/java/com/shortify/service/`: validation, code generation, resolution rules, metadata mapping, transactional writes/deactivation; Phase 1 persistence accessors are retained.
- `backend/src/main/java/com/shortify/{dto,exception,config}/`: request/response DTOs, global JSON errors, strict JSON configuration, and injectable UTC clock.
- `backend/src/main/java/com/shortify/{entity,repository}/`: JPA mapping and persistence access.
- `backend/src/main/resources/db/migration/`: versioned PostgreSQL schema; Phase 2 reuses the Phase 1 schema without modification.
- `backend/src/test/java/com/shortify/`: PostgreSQL persistence and HTTP integration tests; `service/` contains unit tests.
- `frontend/.gitkeep`: directory only; frontend work is deferred to Phase 5.

## Stop PostgreSQL

From the repository root:

```bash
docker compose down
```

The volume is preserved. `docker compose down -v` **deletes all project database data**; use it only when intentionally resetting this development database.

See `phases.md` and `scope.md` for the approved boundaries. Do not begin Phase 3 until explicitly approved.
