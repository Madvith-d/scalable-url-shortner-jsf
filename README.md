# Shortify — Phase 1

This phase provides a Java 21 / Spring Boot 3.5 backend connected to Docker PostgreSQL. It includes the initial `ShortUrl` entity, repository, transactional service, and Flyway schema migration. There are **no application endpoints**, short-code generation, redirects, authentication, Redis, analytics, or frontend application yet.

## Prerequisites

- JDK 21 (set `JAVA_HOME` to its installation directory and add `$JAVA_HOME/bin` to `PATH`). The project compiles with Java release 21; use JDK 21 for the baseline build/runtime rather than assuming newer JDK compatibility.
- Docker Engine with Docker Compose.
- Internet access for the first build, plus `curl` or `wget` and `unzip` on Linux/macOS.
- No installed Maven is needed. The official Apache Maven Wrapper 3.3.4 scripts in `backend/` bootstrap Maven 3.9.16. Windows users can use `mvnw.cmd`.

The commands below use **Bash**, starting at the repository root. If using Fish, enter `bash` first.

## Configure and start PostgreSQL

```bash
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD to a strong local password.
docker compose up -d --wait postgres
docker compose ps
```

`.env` is ignored by Git. Compose refuses to start without a nonempty `POSTGRES_PASSWORD`. PostgreSQL defaults to database `shortify`, user `shortify`, and a host port bound only to `127.0.0.1`. Data is stored in the named `postgres_data` volume.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_DB` | `shortify` | Database name, shared by Compose and the backend |
| `POSTGRES_USER` | `shortify` | Database user, shared by Compose and the backend |
| `POSTGRES_PASSWORD` | Required, no default | Database password |
| `DB_HOST` | `localhost` | Backend database hostname |
| `DB_PORT` | `5432` | Published PostgreSQL port and backend database port |
| `SERVER_PORT` | `8080` | Backend HTTP port |

If a port is occupied, set `DB_PORT=5433` (or another free port) and/or `SERVER_PORT=8081` in `.env` **before** starting. Do not stop unrelated containers. Changing PostgreSQL credentials in `.env` does not change credentials already initialized in the volume.

Compose reads `.env` automatically, but Spring Boot/Maven do not. Export the settings before every build or application run in a new shell:

```bash
set -a
source .env
set +a
```

Only source your own trusted `.env`; keep its values valid Bash assignments. A random hexadecimal password avoids shell quoting issues.

## Build and run the integration tests

With PostgreSQL healthy and the variables exported, run from the repository root:

```bash
cd backend
./mvnw --version
./mvnw clean verify
```

`verify` packages the application and runs `ShortUrlPersistenceIT` through Maven Failsafe. The `@SpringBootTest` uses the real PostgreSQL datasource from the environment, with `@Transactional` and `@Rollback`; there is no H2 dependency, in-memory replacement, or silent database-test skip. The tests:

- Confirm the database product is PostgreSQL.
- Save through the service/JPA repository, flush and clear the persistence context, then retrieve by ID and short code.
- Check creation timestamps, nullable expiration, and explicit true/false active status.
- Verify unique short codes and database-level non-null constraints.

Use a local/development database: Flyway applies the schema before tests, and its migrations are not rolled back. Test rows are rolled back (PostgreSQL identity sequences can still advance). Results are in `backend/target/failsafe-reports/`. `./mvnw test` alone does not run these integration tests; use `verify` for Phase 1 acceptance.

## Start the backend

From `backend/`, with the same exported environment:

```bash
./mvnw spring-boot:run
# Alternatively, after a successful verify:
# java -jar target/shortify-0.0.1-SNAPSHOT.jar
```

Startup should log a successful PostgreSQL connection, Flyway migration/validation, Hibernate initialization, and `Started ShortifyApplication`. Flyway owns the schema; Hibernate uses `ddl-auto: validate`, not automatic schema creation/update.

In another terminal, check the configured HTTP port:

```bash
curl -i http://localhost:8080/
```

An HTTP **404 is expected** in Phase 1 because there are no application controllers or endpoints. Use your configured `SERVER_PORT` if different. Stop the backend with Ctrl+C.

## Layout and responsibilities

- `backend/src/main/java/com/shortify/entity/`: `ShortUrl` mapping and persistence constraints.
- `backend/src/main/java/com/shortify/repository/`: Spring Data JPA database access.
- `backend/src/main/java/com/shortify/service/`: transactional persistence/retrieval, with no URL-shortening logic yet.
- `backend/src/main/java/com/shortify/{controller,dto,exception,config}/`: retained empty packages; controllers/APIs are deferred to Phase 2.
- `backend/src/main/resources/db/migration/`: versioned PostgreSQL schema.
- `frontend/.gitkeep`: directory only; there is no frontend startup command until Phase 5.

The entity requires a short code (maximum 64 characters), a nonblank original URL, and an explicit active argument. Creation time is assigned on persistence, expiration is optional, and the database enforces unique/non-null short codes and non-null original URL, creation time, and active status. URL syntax checks, alias rules, code generation, and expiration behavior are not part of this phase.

## Stop PostgreSQL

From the repository root:

```bash
docker compose down
```

The volume is preserved. `docker compose down -v` **deletes all project database data**; use it only when intentionally resetting this development database.

See `phases.md` and `scope.md` for the approved requirements. The local Git history records the requirements baseline and each verified implementation phase.
