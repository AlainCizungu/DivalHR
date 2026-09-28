# Sprint 0 Platform Foundation

## Objective

Create a reproducible development foundation for DivalHR without implementing HR business functionality.

## Deliverables

### Repository

Create the approved monorepo structure. Add a root task runner or documented commands that start, test, lint, and build every application.

### Web and PWA

- React and TypeScript application
- Responsive application shell
- French and English locale switching
- Locale persistence
- No hard-coded user-facing text
- Accessible navigation
- Light and dark theme foundation consistent with the Dival product direction
- Health/status screen that calls the Core API and AI Service

### Core API

- Java 21 and Spring Boot
- Modular package structure
- Health and readiness endpoints
- PostgreSQL connectivity
- Flyway migrations
- Correlation ID handling
- Structured logging
- Error-response contract
- OpenAPI generation
- Initial tenant-context abstraction without business endpoints

### AI Service

- Python and FastAPI
- Health and readiness endpoints
- Configuration management
- Structured logging and correlation IDs
- Test harness
- No production model or provider integration yet

### Identity

- Keycloak local container
- Development realm configuration as code
- Administrator and employee roles as placeholders
- Web login flow
- API JWT validation
- Privileged MFA documented; production enablement may wait for an external environment

### Local environment

Docker Compose must start PostgreSQL, Keycloak, Core API, AI Service, and Web. Add sample environment files containing no secrets. One documented command should start the stack.

### CI

GitHub Actions must validate:

- frontend format, lint, type check, tests, and build
- Java format or static analysis, tests, and build
- Python format, lint, type check, and tests
- translation-key parity
- OpenAPI validation
- secret scanning
- dependency review where supported
- container build validation

## Required quality

- Unit tests for each deployable
- One end-to-end smoke test covering login and both health calls
- French and English interface tests
- Accessibility smoke test
- Tenant-context isolation test scaffold
- No critical or high dependency findings left unexplained
- README setup instructions verified from a clean environment

## Non-goals

- Employee records
- Attendance
- Scheduling
- Payroll
- Finance
- Real AI model integration
- Kafka
- Kubernetes
- Production infrastructure deployment

## Completion report

Claude must provide the report required by `CLAUDE.md`, including commands and test results, known limitations, and the recommended first functional story.

## Acceptance criteria

1. A new developer can start the complete environment from documented commands.
2. Web, Core API, AI Service, PostgreSQL, and Keycloak become healthy.
3. A user can authenticate and switch between complete French and English shells.
4. The browser calls both service health endpoints through configured URLs.
5. CI passes on the pull request.
6. No secret is committed.
7. Core modules are structured for future domain boundaries.
8. The pull request contains no HR business feature beyond the agreed scaffolding.
