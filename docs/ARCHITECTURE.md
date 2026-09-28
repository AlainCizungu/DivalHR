# DivalHR Architecture

## Architecture approach

DivalHR is microservice-ready but begins with a small number of deployable units suitable for a solo founder.

### Initial deployables

1. **Web and PWA** — administrator, employee, and supervisor interfaces.
2. **Core API** — modular People, Operations, Payroll Preparation, Documents, Tenant, and Integration domains.
3. **AI and Workers** — background processing, document extraction, reports, notifications, and AI orchestration.

Domain modules must have explicit interfaces, separate ownership, and publish business events. They may be extracted into independent services when scale, regulatory isolation, or team ownership justifies the operational cost.

## Domain boundaries

- Identity and Access
- Tenant and Configuration
- People
- Operations
- Payroll
- Documents
- Notifications and Integrations
- Analytics and AI
- Finance

No module reads another module's private tables directly.

## Technology direction

- React and TypeScript for web and PWA
- Java and Spring Boot for the core domain API
- Python for AI orchestration and selected document or analytics workloads
- PostgreSQL-compatible transactional storage
- Encrypted object storage for documents
- Redis-compatible caching for reconstructible data only
- OpenAPI for HTTP contracts
- Durable events and an outbox pattern
- Containers and infrastructure as code
- Managed services before self-managed distributed infrastructure

Kafka is a target event platform when scale or integration requires it. A simpler managed queue or event service is acceptable initially if event contracts remain portable.

## Tenant hierarchy

Organization → Country → Legal Entity → Region → Site → Department or Cost Center → Team → Worker

All business records include a trusted tenant scope. Tenant identifiers supplied in request bodies are never sufficient authorization.

## API rules

- REST and JSON for primary APIs
- Base path `/api/v1`
- OAuth 2.0 and OpenID Connect
- Stable machine-readable error codes
- Cursor pagination
- Idempotency keys for retryable creates
- Signed, replay-protected webhooks
- Explicit deprecation policy
- Authorization performed inside every service or module

## Event rules

Every business event includes:

- event ID
- event type
- schema version
- tenant ID
- source
- subject
- event time
- correlation ID
- causation ID

Events are published through a transactional outbox. Consumers must be idempotent.

## Data architecture

- Transactional domain data remains in PostgreSQL.
- Documents use encrypted object storage; databases retain metadata and access references.
- Search indexes enforce tenant and authorization boundaries.
- Analytical projections are fed by governed events.
- Effective-dated records preserve employment, organization, policy, and country-rule history.

## Offline architecture

Essential attendance, roster, task, and expense workflows use a secure local queue. Synchronization preserves device event time and server receipt time, uses idempotent requests, and sends uncertain conflicts to human review.

## Reliability baseline

- 99.9% monthly availability target for core APIs
- No acknowledged attendance-event loss
- Automated backups and restore tests
- Structured logs, metrics, distributed traces, and business-journey dashboards
- Recovery point objective of 15 minutes or better
- Initial recovery time objective of four hours

## Extraction triggers

A module becomes an independent service when at least one condition applies:

- materially different scaling characteristics
- regulatory or data-isolation requirement
- independent release cadence
- separate operational ownership
- reliability blast-radius reduction
- partner-facing boundary that requires independent controls
