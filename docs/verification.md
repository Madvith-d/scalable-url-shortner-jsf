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

## Phase 4 — Redis, rate limiting, analytics, and background cleanup

Verified on **2026-09-26** on top of Phase 3 commit **`27057ce`** (215 tests at that checkpoint). The complete working tree, including the accompanying security/error/routing review tests, was verified. **No commit or frontend change was made.**

### Environment and actual commands

- Existing Docker Compose PostgreSQL **17.11** remained healthy on `127.0.0.1:5434`.
- Started the new loopback-only Redis service with `docker compose up -d --wait redis`; actual Redis version was **7.4.11**, published at `127.0.0.1:6379`, with persistence disabled and no local authentication.
- Used the existing ignored root `.env` without replacing its database settings or JWT secret, local `.local/jdk21` (Temurin **21.0.12.1+1**), and Maven wrapper **3.9.16**.
- The final verification command was:

```bash
set -a
source .env
set +a
export JAVA_HOME="$PWD/.local/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
cd backend
./mvnw --no-transfer-progress clean verify
```

The final run reported **BUILD SUCCESS** at **`2026-09-26T10:29:10+05:30`**, Maven duration **44.348 seconds**. Its full local log is `.local/phase4-verify-final.log`; XML/text reports are under `backend/target/{surefire-reports,failsafe-reports}/`. The executable JAR is `backend/target/shortify-0.0.1-SNAPSHOT.jar`.

### Final executed tests

| Test class | Tests | Result |
| --- | ---: | --- |
| `config.RedirectRateLimitRoutingTest` | 11 | Passed |
| `config.SecurityConfigurationTest` | 19 | Passed |
| `exception.ApiExceptionHandlerTest` | 6 | Passed |
| `service.AnalyticsPrivacyTest` | 13 | Passed |
| `service.OriginalUrlValidatorTest` | 47 | Passed |
| `service.RedisReliabilityTest` | 5 | Passed |
| `service.ShortCodeGeneratorTest` | 2 | Passed |
| `service.ShortUrlServiceTest` | 25 | Passed |
| `AuthOwnershipApiIT` | 59 | Passed against PostgreSQL/Redis and actual HTTP |
| `OwnershipMigrationIT` | 1 | Passed against an isolated PostgreSQL schema |
| `Phase4ApiIT` | 11 | Passed against PostgreSQL/Redis and actual HTTP |
| `RateLimitApiIT` | 5 | Passed against PostgreSQL/Redis and actual HTTP |
| `ShortUrlApiIT` | 61 | Passed against PostgreSQL/Redis and actual HTTP |
| `ShortUrlPersistenceIT` | 8 | Passed with the original persistence assertions retained |
| **Total** | **273** | **0 failures, 0 errors, 0 skips** |

Surefire ran **128 unit tests**; Failsafe ran **145 integration tests**. No H2 database, disabled security filters, or blanket test skips were introduced. Each Spring integration-test context gets its own random Redis prefix. Existing tests have high configured limits; dedicated limit tests use low real limits. The short-interval scheduler context is closed after its class so it cannot interfere with other suites.

### Behavior demonstrated

- A miss resolves through real PostgreSQL and populates Redis with ID/destination/active/expiry. A second real HTTP redirect hits Redis; repository spying confirms exactly one code lookup across the two resolutions, while two click events persist separately. Redis TTL is bounded by configured lifetime and by link expiry.
- PATCH deactivation/reactivation and DELETE invalidate cached state after transaction commit. A separate transaction test proves that invalidation does not happen before commit or after rollback. A latch-controlled concurrent miss proves that a load begun before a committed mutation cannot repopulate the cache afterward under normal Redis availability.
- An expired active target deliberately retained in real Redis returns `410 URL_EXPIRED` without a PostgreSQL code lookup, proving that expiry validation is not merely delegated to Redis TTL. Invalid and HEAD redirects do not produce click events.
- With the executor's only worker blocked, a valid HTTP redirect still returns 302 while its analytics event remains queued; releasing the worker persists the event. With the worker and queue both occupied, another redirect still returns 302, the queue remains bounded, and the drop counter increments. There is no caller-runs policy.
- PostgreSQL rows contain only the referrer host, coarse device, `Unknown` geography, access time, and URL relation. Tests strip credentials/path/query/fragment, classify devices, reject malformed referrers, and confirm spoofed country/forwarding data and raw client headers are absent from persisted rows.
- Analytics HTTP tests verify the exact five-field contract, empty arrays for zero clicks, UTC day aggregation across midnight, category counts/order, and owner isolation. Other-owner/missing IDs return concealed 404, and anonymous access returns 401. Aggregation uses SQL COUNT/GROUP BY, not fetching click entities into memory.
- The actual scheduled job runs every **250 ms** in its dedicated test context with **batch size 2**. Five URLs become inactive over multiple automatic ticks; URL rows, owner IDs, and click history remain. Tests never invoke the cleanup method manually to prove scheduling.
- Authentication, creation, and redirect categories each return JSON 429 with positive `Retry-After`. Twenty concurrent checks permit exactly the configured two requests through real Lua. An expired short window permits requests again. Management/analytics reads and actual framework resource/error handlers remain outside the limiter; selected redirect handlers are protected even for encoded/legacy/invalid codes. Direct-client forwarding headers do not produce new identities. Redis limiter keys contain only HMAC digests, with bounded expirations.
- Simulated Redis unavailability is exercised through actual HTTP while retaining real PostgreSQL: login/register/create return JSON 503; redirects fall back to PostgreSQL; deactivation commits despite invalidation failure. After Redis recovery, the old value cannot survive its absolute TTL. Separate unit tests prove the fixed-memory global redirect fallback budget stops additional clients with 503, and cover read/corrupt-payload fallback, failed invalidation, absolute deadlines, and slow-loader suppression. Redis was **not physically stopped** during these tests; availability was mocked to avoid affecting other contexts/services.
- Early runs caught test-harness constructor/spy changes and stale Phase 3 analytics-error expectations; they were corrected, then clean verification was repeated. A successful full run was followed by the final clean run after strengthening TTL bounds and closing the short-scheduler context. Expected constraint-error and sampled outage/drop logs remain diagnostic; Mockito emits its existing JDK dynamic-agent warnings.

### Cleanup, scope, and explicit limitations

- Post-run SQL confirmed **0 users, 0 short URLs, 0 click events, and 0 migration-test schemas**. Flyway migrations **1, 2, and 3** are successful; V1/V2 remain unchanged. Tests delete only their tracked records/schema; no database truncation or volume reset was performed.
- PostgreSQL and Redis were left healthy. No listener remained on 8080/8081, and the test JVM exited. Redis test namespaces are temporary and expire naturally; no global Redis flush was used.
- `git diff --check` passed. The local JWT secret was absent from nonignored project files. HEAD remained `27057ce`; frontend and the unrelated untracked `.vsix` were untouched.
- Cache invalidation is **bounded-staleness, not linearizable**. Normally the generation comparison prevents stale repopulation after invalidation. An already in-flight redirect may complete with old state. Redis failure, generation loss/restart, or a crash between DB commit and invalidation may expose old cached state until its original absolute deadline: **30 seconds by default, configurable up to five minutes**, never extended by cache hits. There is no durable invalidation outbox or immediate cross-instance guarantee; synchronized clocks are required.
- Auth/create fail closed when Redis cannot enforce limits. Redirect fallback is globally bounded **per process**, not across the cluster, and resets after restart. Redis timeouts can increase outage latency. Proxy forwarding/geography header trust is disabled; NAT/proxy peers share a budget.
- Analytics is best effort: bounded-queue saturation, persistence failure, shutdown, or process loss may drop events, counted and sampled in logs. There is no durable queue/retry/exactly-once promise. Geography remains `Unknown`; no trusted-proxy geography option was added. Lifetime aggregate bucket counts can grow with history.
- README and `.env.example` document startup, settings, exact analytics/error contracts, privacy, cache race guarantees, outage behavior, scheduler bounds, and frontend integration expectations. No frontend, Kafka, microservices, new public endpoints beyond analytics, or unapproved infrastructure was added. Parent review and commit followed these checks.

### Additional review regression

- A read-only account-security review identified an expiration-range validation gap. Real HTTP regression tests reproduced HTTP 500 for parseable Java timestamps outside PostgreSQL's supported range.
- Expiration now rejects timestamps outside UTC calendar years 0001–9999 with HTTP 400 before database insertion. Four regression cases cover extreme positive/negative years and both calendar boundaries; README documents the supported range.
- The full `clean verify` suite was rerun after this fix: **277 tests passed (128 unit + 149 integration), zero failures/errors/skips**. The local log is `.local/phase4-final-verify.log`.
- The two records created by the intentionally failing boundary tests were removed by their exact test IDs before the successful full rerun.

## Phase 5 — Next.js, browser integration, and deployment

Verified on **2026-09-26**, after Phase 4 commit **`9c4872f`**.

### Delivered and executed checks

- Next.js **16.3.6**, React **19.3.0**, and TypeScript implement landing/creation, login, registration, dashboard, URL details/management, and analytics. Fonts are bundled locally. Spring Boot remains the only account/JWT authority.
- Frontend `npm run build` passed, including the standalone production output. `npm run typecheck` passed. `npm test` passed **39 unit tests**, covering transport, errors, sessions, safe return routes, validation, timestamps, and status helpers.
- Both Docker images built successfully from source: Java 21 backend and Node 24 standalone frontend. Application containers run as non-root users (verified with `id` inside each container).
- `docker compose --profile app up -d --wait` started **four healthy services**. Local overrides use frontend **3002**, backend **8081**, PostgreSQL **5434**, and Redis **6379**. An unrelated Next.js process on 3000 was left running. Earlier unrelated Docker containers were stopped at the user's request without deleting their data or volumes.
- No browser integration tools were connected, so real Chromium automation was executed through the installed Playwright CLI. This was interaction testing, not just screenshots or HTTP-only checks.

### Browser verification

```bash
cd frontend
npx playwright install chromium
E2E_BASE_URL=http://localhost:3002 E2E_API_URL=http://localhost:8081 npm run test:e2e
```

All **four browser scenarios passed against both the development servers and the production Docker deployment**. The final production run took **16.6 seconds**, with no retries. It exercises:

1. **Pagination and cross-page creation:** seed an owned list through the actual API; navigate both pages; verify page-size changes and disabled navigation; create from page two and verify return to page one; create on the authenticated landing page and verify the dashboard shows the new link.
2. **Complete desktop account/link flow:** preserve a guest destination/alias through login-to-registration navigation; register; create an expiring custom link; navigate list → detail → analytics; copy the real short URL; open it in a new browser tab and follow the actual 302 to the destination; refresh and observe recorded analytics; deactivate/reactivate and verify redirect behavior plus consistent status/history on all shared pages; reject duplicate/reserved aliases and unsafe URL input; reload; logout/back navigation; verify a second account cannot read another owner's details/analytics; log back in as the owner.
3. **Mobile and responsive behavior:** register, create an already expired URL, manage it, verify HTTP 410 persists after toggling, and inspect all shared pages. Landing, login, registration, dashboard, details, and analytics were checked at **320, 375, 414, 768, and 1440 pixel widths**. The document does not overflow horizontally; large tables scroll within their own containers. Screenshots were reviewed for desktop landing/analytics and the populated mobile dashboard, including production output.
4. **Error and session states:** inject a network failure for list loading, retry against the real API, inspect missing/malformed routes, expire the locally stored session, and replace the token with an invalid JWT. Protected data is cleared and login is required. The backend handles the rejected token; no frontend token issuer is involved.

The suite uses real PostgreSQL, Redis, HTTP, and browser interactions for primary flows. Only the network-recovery failure is intercepted. No fake success data replaces the backend. Source: `frontend/tests/e2e/`; final local logs: `.local/browser-tests.log` and `.local/browser-production-tests.log`. Screenshots and reports are under ignored `frontend/test-results/` and `frontend/playwright-report/`.

### Regression fixed during browser verification

A populated dashboard overflowed horizontally at mobile widths despite its table scroll container. Absolutely positioned screen-reader labels escaped the clipping ancestor because the container was not positioned. Adding `position: relative` to the table's scrolling container fixed the underlying bounds. The entire responsive matrix and end-to-end suite were rerun successfully. Test navigation assertions were also corrected to wait for destination pages before filling forms with shared labels, and to distinguish application controls from Next.js development controls/route announcements.

### Final state and limits

- The backend remains at its verified **277-test** checkpoint; Phase 5 adds **39 frontend unit tests and 4 complete browser scenarios**. Docker builds compile/package and are not represented as substitutes for integration tests.
- `.env`, frontend `.env.local`, dependency folders, build output, browser artifacts, and local logs are ignored. No backend secret is a public frontend variable. Build-time API origins, CORS, startup, Docker commands, API usage, and reliability/security limitations are documented in both READMEs and environment examples.
- Removed only this session's namespaced `shortify-e2e-…@example.test` browser test data: **27 accounts and 61 URL records**, with click records removed by their foreign-key cascade. No tables or volumes were reset; unrelated data and files were preserved. The test accounts remaining count is zero.
- Development servers were stopped after verification; the four healthy Docker services remain running for use. Default clean-checkout ports differ from this workspace's documented local overrides.
- Analytics remains asynchronous/best effort, geography is explicitly `Unknown`, and cache failure consistency remains bounded as documented in Phase 4. Session storage is JavaScript-readable and tab-scoped; logout discards the token rather than revoking copies. A manual screen-reader/assistive-technology audit was not performed.
- All five requested phases have now been implemented and verified in sequence. The final phase commit records the frontend, deployment configuration, tests, and documentation.

## UI redesign — Minimal layouts and dark mode

Verified on **2026-09-26**, after Phase 5 commit **`769aa2f`**.

### Delivered

- Reworked creation, authentication, dashboard, details, analytics, and not-found screens with compact headings, simpler copy, neutral surfaces, consistent controls, and responsive spacing. Dashboard rows become stacked cards on smaller screens, keeping status, expiration, copy, manage, and analytics actions visible.
- Added a shared **System / Light / Dark** appearance selector. Preferences persist in `shortify.theme` localStorage, synchronize across tabs, and survive navigation and logout independently of authentication. System tracks operating-system changes. Blocked storage falls back to an in-memory choice; invalid preferences resolve to System.
- Applied the stored appearance before React hydration. Semantic colors cover fields, native controls, focus, errors, statuses, and charts. Existing reduced-motion and forced-color support remains.
- No backend, database, authentication protocol, or API contract changes were made.

### Executed verification

- `npm test`: **51 unit tests passed**, zero failures or skips. The 12 added tests cover preference parsing, System resolution, and pre-hydration initialization, including invalid and blocked storage.
- `npm run typecheck`: passed.
- `docker compose --profile app build frontend`: passed, including the Next.js standalone production build. Stopped the owned development server and deployed only the updated frontend; backend, PostgreSQL, and Redis remained running. All four Compose services were healthy.
- Real Chromium interactions ran through Playwright, since no connected browser integration tools were available. The complete production suite passed **16 tests in 27.2 seconds**, without retries, at `http://localhost:3002` against backend `http://localhost:8081`.
- Both Light and Dark projects exercised registration/login/logout, draft preservation, creation, copying, actual redirects and analytics, activation consistency, ownership isolation, pagination, expired links, network recovery, missing routes, and expired/rejected sessions. Responsive route checks covered **320, 375, 414, 768, and 1440 pixels** without document overflow.
- Appearance tests verified explicit persistence through reload/navigation, cross-tab synchronization, System changes, blocked storage, semantic text contrast of at least **4.5:1** for the tested color pairs, and saved dark appearance before hydration with application scripts blocked but stylesheets allowed.
- Reviewed production screenshots of the populated dashboard, detail, and analytics screens across desktop/mobile and both palettes, in addition to the landing and authentication design previews. Mobile cards retain visible actions and status, long URLs wrap, and empty analytics remain readable.
- Initial theme-test failures were test-harness issues: a route interception blocked CSS alongside JavaScript, and the contrast helper initially assumed six-digit hex while CSS output included shorthand hex. Both were corrected without weakening the checks, then the complete production suite passed.
- `git diff --check -- frontend docs/verification.md`: passed for the redesign changes. Unrelated concurrent `.gitignore` edits were left untouched and excluded from the commit. Local logs are `.local/ui-redesign-unit.log`, `.local/ui-redesign-typecheck.log`, `.local/ui-redesign-image-build.log`, and `.local/ui-redesign-production-browser.log`; browser screenshots/reports remain ignored under `frontend/test-results/` and `frontend/playwright-report/`.

### Limits and running state

The production application is available at **http://localhost:3002**. The unrelated application on port 3000 and unrelated untracked files were preserved. Browser tests created uniquely named test accounts/links in the development database; this redesign verification did not reset the database. The backend's previously verified 277-test checkpoint is unchanged and was not rerun for CSS/theme-only work. Contrast checks and Chromium interaction tests are not a full accessibility certification; manual screen-reader testing and additional browser engines were not performed.
