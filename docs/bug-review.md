# Parallel bug-review handoff

## Fixes from the independent review

- `backend/src/main/java/com/shortify/config/RateLimitConfiguration.java`: use Spring's decoded `shortCode` path variable rather than the raw request URI. Percent-encoded aliases and matrix parameters previously bypassed redirect rate limiting. Preserve reserved-route exclusions; limit non-reserved malformed codes too, since these still reach the lookup handler.
- `backend/src/main/java/com/shortify/exception/ApiExceptionHandler.java`: return 400 for IDs that become null during conversion (e.g. whitespace), while retaining 500 for broken controller mappings. Preserve Spring's `Allow` and supported media-type headers on 405/415 responses. Handle 406 explicitly as JSON rather than logging an unexpected server failure and falling through to Spring's default resolver.
- `backend/src/main/java/com/shortify/config/SecurityConfiguration.java`: reject CORS origins with out-of-range ports or an empty trailing port. Preserve the concurrent `Retry-After` exposure change.

Regression coverage: 24 added cases across `config/SecurityConfigurationTest.java`, `config/RedirectRateLimitRoutingTest.java`, and `exception/ApiExceptionHandlerTest.java` under `backend/src/test/java/com/shortify/`.

## Verification

- Baseline regressions were reproduced before applying fixes, including encoded redirects returning 302 despite an exhausted mocked limiter.
- The pre-Redis committed backend plus the HTTP/CORS fixes passed 228 tests.
- The combined working-tree snapshot passed `./mvnw --no-transfer-progress verify` on JDK 21: **128 unit tests + 143 PostgreSQL/Redis integration tests = 271**, with zero failures, errors, or skips. Completed at `2026-09-26T10:27:26+05:30`.
- Builds ran in `/tmp/shortify-review.pUrQJF/`, not the shared `backend/target/`. Each full integration run used a separately created PostgreSQL database, removed afterwards. Redis integration tests used their UUID namespaces; no shared Redis flush was performed.
- Final full log: `/tmp/shortify-review.pUrQJF/final-verify.log` (temporary local artifact).
- The application source still matched the verified snapshot at the post-run comparison. The other agent continued editing `Phase4ApiIT.java` during verification; that later test-only change is not included in the reported run.
- `git diff --check` passed. No commits were created. Other concurrent changes were preserved, including the ownership and analytics-test corrections made by the implementation agent.

This records a completed review pass, not an ongoing background monitor.
