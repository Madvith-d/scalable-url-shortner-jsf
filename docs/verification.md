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
- README now documents Phase 2 setup, endpoint contracts, validation, expiry, error shapes, and collision-safe transactions. No aliases, authentication, Redis, analytics, or frontend implementation was added. **Phase 3 awaits explicit approval.**
