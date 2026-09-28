# DivalHR Core API

Java 21 + Spring Boot 4.1 modular monolith (see `docs/ARCHITECTURE.md`, ADR 0001 and ADR 0004).

- `platform/` — cross-cutting infrastructure (security, tenancy, errors, correlation IDs, status).
  It must not depend on any domain module.
- `identity/`, `tenant/`, `people/`, … — domain modules. Each owns its own database schema and
  never reads another module's tables or internal classes (enforced by ArchUnit).

The public HTTP contract is `docs/API-SPEC.yaml` (design-first). `ApiContractDriftTest` fails the
build when the implementation drifts from it; springdoc output is verification input only.

## Native commands (no `make` required)

Requires a JDK 17+ to run Gradle (Gradle provisions JDK 21 for compilation) and Docker for
Testcontainers.

```bash
./gradlew spotlessApply     # format
./gradlew check             # Spotless, SpotBugs, unit + integration + architecture + contract tests
./gradlew bootJar           # build the executable jar

# Run against the Compose PostgreSQL and Keycloak (development only):
# (publish the PostgreSQL port in compose.yaml first, e.g. "5432:5432")
DIVALHR_ENVIRONMENT=development DIVALHR_DB_PASSWORD=dev-only-core-db ./gradlew bootRun
```

`DIVALHR_ENVIRONMENT` is required and has no default. The API refuses to start outside
`development`/`test` if it is configured to trust the development-only `divalhr-dev` realm.
