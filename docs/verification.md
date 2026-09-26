# Phase verification record

Each phase is committed only after its acceptance checks pass. This records checks actually executed during implementation, not promises about future verification.

## Phase 1 — Backend and database foundation

- Docker Compose PostgreSQL 17.11 started healthy with persistent storage (local override: port 5434).
- Official Maven wrapper bootstrapped Maven without a system Maven installation.
- `./mvnw clean verify` passed on JDK 21 and JDK 26: 8 PostgreSQL integration tests, no failures, errors, or skips.
- Tests proved JPA persistence and retrieval after clearing the persistence context, unique/non-null database constraints, creation timestamps, optional expiration, and explicit active status.
- Test transactions rolled back, leaving no test URL records.
- Packaged backend started on both Java runtimes, connected to PostgreSQL, migrated the schema with Flyway, and returned expected 404 responses before application endpoints were added.
- No authentication, Redis, analytics, or frontend functionality was introduced in this phase.

## Phase 2 — Core URL shortening and redirects

Verified on **2026-09-26**, with Phase 1 commit `162b271` unchanged as the Git HEAD. No Phase 2 commit was created during this implementation.

### Environment and command

- Existing Docker Compose PostgreSQL **17.11** remained healthy at `127.0.0.1:5434`; no unrelated containers were changed.
- Root `.env` was sourced and exported before Maven. Its application port is **8081**.
- `JAVA_HOME` pointed to the repository's `.local/jdk21`: Eclipse Temurin **21.0.12.1+1 LTS**.
- The official wrapper used Maven **3.9.16**; no system Maven was needed.
- Executed from the repository root in Bash:

```bash
set -a
source .env
set +a
export JAVA_HOME="$PWD/.local/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
cd backend
./mvnw --version
./mvnw clean verify
```

The final run reported **BUILD SUCCESS** at `2026-09-26T10:02:28+05:30`, Maven duration **10.675 seconds**. The executable JAR was packaged at `backend/target/shortify-0.0.1-SNAPSHOT.jar`.

### Executed tests

| Test class | Tests | Result |
| --- | ---: | --- |
| `service.OriginalUrlValidatorTest` | 47 | Passed |
| `service.ShortCodeGeneratorTest` | 2 | Passed |
| `service.ShortUrlServiceTest` | 22 | Passed |
| `ShortUrlApiIT` | 61 | Passed against real PostgreSQL and actual HTTP |
| `ShortUrlPersistenceIT` | 8 | Passed, Phase 1 test source unchanged |
| **Total** | **140** | **0 failures, 0 errors, 0 skips** |

Surefire ran **71 unit tests**; Failsafe ran **69 PostgreSQL integration tests**. Source paths:

- `backend/src/test/java/com/shortify/service/OriginalUrlValidatorTest.java`
- `backend/src/test/java/com/shortify/service/ShortCodeGeneratorTest.java`
- `backend/src/test/java/com/shortify/service/ShortUrlServiceTest.java`
- `backend/src/test/java/com/shortify/ShortUrlApiIT.java`
- `backend/src/test/java/com/shortify/ShortUrlPersistenceIT.java`

Reports are in `backend/target/surefire-reports/TEST-*.xml` and `backend/target/failsafe-reports/TEST-*.xml`, with matching human-readable `.txt` summaries.

### Behavior demonstrated

- POST creates an active URL with 201, management `Location`, fixed eight-character Base62 code, public short URL, and metadata DTO. GET metadata matches the created data and PostgreSQL row.
- Public GET returns **302**, the original destination in `Location`, an empty body, and `Cache-Control: no-store`, without fetching the destination or following redirects in the test client.
- Validation accepts HTTP(S), uppercase schemes, localhost, IPv4/IPv6, ports, queries, and fragments. It rejects missing/invalid hosts, unsupported schemes, credentials, malformed escapes, invalid ports, and literal/encoded control characters, including encoded C1 controls.
- Malformed/empty/null/wrong-type bodies, scalar coercion attempts, trailing JSON, invalid expiration strings, and unsupported fields such as `customAlias` return consistent JSON **400** responses before insertion.
- Missing IDs/codes return **404**. Inactive and expired redirects return **410** without `Location`. Future/no expiration redirects work; unit tests also verify the exact expiry boundary using a fixed clock.
- DELETE returns **204**, persists deactivation rather than deletion, and remains idempotent. Inactive/expired metadata remains available through GET.
- Creation/retrieval timestamps agree at PostgreSQL microsecond precision, including a regression test for nanosecond expiration input.
- Forty concurrent HTTP POSTs for the same destination produced forty distinct persisted codes.
- A deliberately repeated generated code caused a **real PostgreSQL `23505` unique-constraint violation**. The next attempt committed successfully in a separate transaction, preserving the original row and redirecting through the new code.
- Ten deliberately forced real collisions produced a sanitized **503**. Unit tests verified that unrelated constraint failures are not retried; an injected database exception through HTTP produced a generic **500** without database details in the response.
- The first verification run exposed Jackson string coercion and creation timestamp precision mismatches; both were fixed and the complete suite was rerun successfully.

### Cleanup and scope

- Phase 1 persistence tests still roll back. HTTP tests delete only their own tracked committed rows; a post-run SQL count confirmed **zero rows** remained in `short_urls`.
- Flyway validated the unchanged V1 migration; no new migration or schema change was required.
- The final test server ran on random port **40949** and exited with the test JVM. No standalone smoke application was started. Post-run checks found no listener on 8080, 8081, or 40949 and no remaining Shortify/test Java process. PostgreSQL remained healthy.
- `git diff --check` passed. The Phase 1 test and migration files have no diff from `162b271`.
- Expected constraint-violation/error logs are emitted by deliberate failure tests; assertions confirm these details do not appear in HTTP responses. Mockito/Byte Buddy emitted JDK dynamic-agent warnings, but all tests passed without skips.
- README now documents Phase 2 setup, endpoint contracts, validation, expiry, error shapes, and collision-safe transactions. No aliases, authentication, Redis, analytics, or frontend implementation was added. **Phase 3 awaited explicit approval at this checkpoint.**

## Phase 3 — Authentication, ownership, and URL management

Verified on **2026-09-26**, on top of Phase 2 commit **`51ae9ac`** (140 passing tests at that checkpoint). Phase 3 changes are intentionally **uncommitted**. No Phase 4 implementation was added.

### Environment and executed commands

- Used the existing healthy Docker Compose PostgreSQL **17.11** at `127.0.0.1:5434`; unrelated containers were not modified.
- Exported the repository-root `.env` (configured application port 8081). Generated a random signing secret only in that ignored file, without printing its value, and restricted the file to mode 600.
- Used `.local/jdk21`: Eclipse Temurin **21.0.12.1+1 LTS**, and the official Maven wrapper with Maven **3.9.16**.
- Executed the full clean verification twice, including a final run after review and additional service-guard tests. The final command was:

```bash
set -a
source .env
set +a
export JAVA_HOME="$PWD/.local/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
cd backend
./mvnw --no-transfer-progress clean verify
```

The final run reported **BUILD SUCCESS** at **`2026-09-26T10:15:13+05:30`**, Maven duration **27.631 seconds**. The executable JAR is `backend/target/shortify-0.0.1-SNAPSHOT.jar`. The ignored local full log is `.local/phase3-verify-final.log`.

### Final executed tests

| Test class | Tests | Result |
| --- | ---: | --- |
| `config.SecurityConfigurationTest` | 12 | Passed |
| `service.OriginalUrlValidatorTest` | 47 | Passed |
| `service.ShortCodeGeneratorTest` | 2 | Passed |
| `service.ShortUrlServiceTest` | 25 | Passed |
| `AuthOwnershipApiIT` | 59 | Passed against PostgreSQL and actual HTTP |
| `OwnershipMigrationIT` | 1 | Passed against an isolated PostgreSQL schema |
| `ShortUrlApiIT` | 61 | Passed against PostgreSQL and actual HTTP, now authenticated |
| `ShortUrlPersistenceIT` | 8 | Passed with all original persistence assertions retained |
| **Total** | **215** | **0 failures, 0 errors, 0 skips** |

Surefire ran **86 unit tests**; Failsafe ran **129 integration tests**. XML and text reports are in `backend/target/surefire-reports/` and `backend/target/failsafe-reports/`. No H2 database, mock JWT authentication, blanket skips, or disabled security filters were used.

### Behavior demonstrated

- Registration strips/lowercases emails and persists a BCrypt hash, never the raw password. Tests verify the hash matches the correct password, rejects another password, uses distinct salts for equal passwords, and records `createdAt`.
- Register/login response fields are exactly `accessToken`, `tokenType`, `expiresIn`, and normalized `email`; responses have `Cache-Control: no-store` and no session cookie. A login-issued token successfully lists and creates URLs. Parsed JWT claims confirm HS256, configured issuer/lifetime, numeric account subject, and no password/hash claims.
- Case/whitespace variants of an existing email return 409. Six concurrent normalized-email registrations have exactly one successful account creation. Wrong-password and unknown-account logins share the same 401 error code/message. Invalid auth JSON and passwords exceeding BCrypt's 72-byte limit are rejected; the 72-byte UTF-8 boundary works.
- Anonymous create/list/get/delete/patch and the future analytics URL return JSON 401. Query-string tokens are not accepted. Malformed, expired (only one second past expiry), tampered, wrong-issuer, future-not-before, missing-expiration, invalid-subject, nonexistent-account, and unsigned tokens are rejected through actual HTTP requests and the real resource-server filter chain.
- Two distinct accounts cannot get, delete, or patch each other's URLs: all return concealed 404 responses without changing the rows. Lists and totals include only the caller's records. JSON owner injection is rejected. Service unit tests also require authentication for every management method, including the common ownership guard intended for future analytics.
- Paginated lists use stable newest-ID-first ordering, enforce page/size limits and types, include owned inactive/expired records, and return empty content beyond the final page.
- PATCH persists deactivation/reactivation. Inactive redirects return 410, reactivated active links return 302, and reactivating an expired link still returns 410. Unsupported PATCH fields and incorrect Boolean types return 400.
- Alias tests cover reserved names case-insensitively, invalid length/ASCII/whitespace characters, 3- and 32-character boundaries, and case-sensitive distinct aliases. Generated codes also skip reserved names.
- Sixteen concurrent same-alias HTTP creates across two owners produce exactly one 201 and fifteen 409 responses. The winning destination/owner is preserved. Deactivation does not release the alias. The retained Phase 2 tests also force real PostgreSQL random-code collisions, successful isolated-transaction retry, retry exhaustion, and forty concurrent unique generated codes.
- V1 remains byte-for-byte unchanged. The migration test applies V1 in a temporary schema, inserts a URL before ownership exists, migrates to V2, and verifies the URL remains intact and unowned, with no fabricated user account. Separate HTTP tests prove unowned links still redirect and cannot be managed, listed, or claimed by either account.
- Exact `http://localhost:3000` bearer/JSON CORS preflight succeeds without credentials or a JWT. Other ports, hostile/lookalike origins, and the `null` origin return the same JSON 403 shape used for security errors. Unit tests reject wildcard/non-origin CORS configuration.
- The existing URL validation, malformed-body, redirect, expiration boundary/precision, deletion, persistence-constraint, collision, and sanitized database-error tests continue to pass under the new authentication contract.

### Additional checks and cleanup

- Started the packaged application separately with `JWT_SECRET` absent and with an undersized value, on a random port. **Both startups failed as required**; the checks did not print the real configured secret. Local diagnostic logs are ignored under `.local/`.
- Post-run SQL confirmed **0 URL rows, 0 user rows, and 0 leftover migration-test schemas**. Tests only removed their own tracked records/schema; no table truncation or volume reset was performed. Flyway versions **1 and 2** both show successful application.
- PostgreSQL remained healthy on port 5434. No listener remained on 8080/8081 and no Shortify application or Surefire JVM remained after verification.
- `git diff --check` passed. Git confirms `.env` is ignored; a value-based scan confirmed the locally generated signing secret is absent from nonignored project files. The unrelated `.vsix` remains untouched and untracked.
- Expected constraint/error logs from deliberate failure tests and Mockito dynamic-agent warnings remain diagnostic only; the HTTP assertions verify sanitized responses.
- Updated `.env.example` and README with configuration, exact API contracts, ownership/legacy behavior, alias case/reserved rules, accepted past expiration, CORS, and the Phase 5 bearer-client plan.

### Scope and limitations

- Analytics is **not implemented**. `/api/urls/{id}/analytics` is authentication-protected but returns 404 to authenticated callers; future analytics must reuse the owner guard before reading data.
- No Redis, rate limiting, background cleanup, frontend implementation, second authentication system, refresh tokens, password reset, or server-side logout was added. Frontend logout is planned as discarding the bearer token; existing tokens otherwise expire normally or become invalid on secret rotation.
- The Phase 5 plan keeps the JWT in client memory and attaches it to backend API calls; no Next.js authentication issuer is planned. Legacy unowned links have deliberately no management/claim path.
- Use TLS outside local development. Authentication rate limiting remains Phase 4 work. No commit was created.
