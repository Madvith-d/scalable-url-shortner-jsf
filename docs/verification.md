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
