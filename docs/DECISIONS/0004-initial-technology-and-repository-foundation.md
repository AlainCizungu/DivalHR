# ADR 0004 Initial Technology and Repository Foundation

## Status

Accepted

## Context

DivalHR begins with a solo founder working with ChatGPT as product owner and architect and Claude as senior software developer. The platform must support bilingual delivery, strong tenant isolation, offline-capable operations, AI services, and later service extraction without creating unnecessary operational complexity at the start.

## Decision

### Repository

Use a monorepo with:

- `apps/web` for the React and TypeScript PWA
- `apps/core-api` for the Java 21 and Spring Boot API
- `apps/ai-service` for the Python and FastAPI service
- `packages/design-system`
- `packages/api-client`
- `packages/localization`
- `packages/shared-contracts`
- `infrastructure/docker`
- `infrastructure/terraform`

### Technology

- React and TypeScript
- Java 21 and Spring Boot
- Python and FastAPI
- PostgreSQL
- Keycloak
- PostgreSQL transactional outbox initially
- Kafka after scale or integration needs justify it
- Docker Compose for local development
- AWS ECS/Fargate for initial production deployment
- EKS only when platform scale or isolation justifies it
- GitHub Actions for continuous integration and delivery

### First vertical slice

Organization creation → French/English configuration → legal entity and site creation → administrator invitation → tenant-isolated dashboard.

## Consequences

The architecture remains manageable for one founder while preserving clear domain and extraction boundaries. The team accepts a polyglot build environment because Java and Python serve distinct platform needs. Operational complexity remains lower than an early Kubernetes and multi-service deployment.
