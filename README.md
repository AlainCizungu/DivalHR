# DivalHR

DivalHR is a bilingual workforce, operations, payroll, intelligence, and employee financial-wellness platform designed for African organizations.

## Product direction

- French and English are first-class product languages.
- The Democratic Republic of the Congo is the initial launch market.
- Initial target sectors include healthcare, education, NGOs, financial services, and manufacturing.
- The product is API-first, mobile-first, offline-tolerant, multi-tenant, and multi-country by design.
- Regulated banks and microfinance institutions—not DivalHR—make and fund lending decisions.

Product and architecture planning is maintained in the `docs/` directory.

## Repository layout

| Path | Contents |
|---|---|
| `apps/web` | React + TypeScript PWA (French/English shell, PKCE login, status screen) |
| `apps/core-api` | Java 21 + Spring Boot 4.1 modular Core API |
| `apps/ai-service` | Python 3.12 + FastAPI AI Service shell (no model integration yet) |
| `packages/localization` | French and English strings + parity checker |
| `packages/design-system` | Light/dark design tokens |
| `packages/api-client` | Typed client generated from the approved contracts |
| `packages/shared-contracts` | Event envelope, error schema, AI Service public contract |
| `infrastructure/docker` | Docker Compose stack, Keycloak development realm, PostgreSQL init |
| `infrastructure/terraform` | Placeholder only |
| `docs/API-SPEC.yaml` | **Authoritative** design-first Core API contract |

## Prerequisites

- Docker Desktop (or Docker Engine) with Compose v2.24+
- Node.js 24.21.0 (`nvm use` reads `.nvmrc`) and pnpm 10.34.6 (`corepack enable` or `npx pnpm@10.34.6`)
- JDK 17+ to run Gradle (it provisions JDK 21 automatically)
- [uv](https://docs.astral.sh/uv/) 0.12+ for the AI Service
- GNU make (optional; every target below lists the native commands it runs)

## Start the complete environment

```bash
cp .env.example .env    # optional; development-only placeholder values
docker compose -f infrastructure/docker/compose.yaml --env-file .env.example up -d --build --wait
# or: make up
```

Then open <http://localhost:5173>. Sign in with a **development-only** seed user, for example
`dev-admin-a` / `dev-only-Admin-A-2026` (full list: `infrastructure/docker/keycloak/README.md`).

| Service | URL |
|---|---|
| Web | http://localhost:5173 |
| Core API status | http://localhost:8080/api/v1/system/status |
| AI Service status | http://localhost:8090/api/v1/system/status |
| Keycloak | http://localhost:8180 (admin: `dev-kc-admin` / `dev-only-keycloak-admin`) |
| Mailpit (development mail catcher: invitations and password emails) | http://127.0.0.1:8025 |

Stop and remove data: `docker compose -f infrastructure/docker/compose.yaml --env-file .env.example down -v` (or `make down`).

## Test, lint and build

```bash
pnpm install --frozen-lockfile      # JavaScript workspace
pnpm format:check && pnpm lint && pnpm typecheck && pnpm test && pnpm build
pnpm i18n:check                     # fails if any French or English key is missing
pnpm api:lint && pnpm api:check     # contract lint + generated client is current

(cd apps/ai-service && uv sync --frozen && uv run ruff format --check . && uv run ruff check . && uv run mypy && uv run pytest)
(cd apps/core-api && ./gradlew check bootJar)   # needs Docker for Testcontainers

# End-to-end smoke tests against the running stack:
pnpm --filter @divalhr/web exec playwright install chromium
pnpm --filter @divalhr/web exec playwright test
```

`make lint`, `make test`, `make build` and `make e2e` wrap the same commands.
`scripts/dev/verify-on-host.sh all` runs the Core API checks and the full-stack smoke test and
writes logs to `.git/divalhr-verify/`.

## Contracts

`docs/API-SPEC.yaml` is the source of truth. Mark each operation `x-divalhr-lifecycle:
planned | implemented`. The Core API's `ApiContractDriftTest` and the AI Service's
`test_contract.py` fail when code and contract disagree. After changing a contract, run
`pnpm api:generate` and commit the regenerated client.

## Security notes

Everything under "development-only" in this repository (realm, users, passwords, tenant IDs,
`.env.example`) is public and must never be reused. See
`docs/security/SPRINT-0-THREAT-MODEL.md` and ADR 0005.
