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

## Web application shell

UI-001 (Issue #53) gives the web app one shell for every module:

- **Route registry** (`apps/web/src/app/routes.tsx`): each URL with its existing guard, breadcrumb trail and frame. It is presentation metadata only; the guards remain usability measures and the Core API authorizes every request.
- **Navigation model** (`apps/web/src/shell/navigation.ts`): groups of registry paths with the role each requires. Only a loaded, server-verified session grants roles, so anonymous, loading and failed sessions see Home only. A unit test keeps the navigation and the registry consistent.
- **Frames:** signed-in users get the sidebar, top bar and account menu; anonymous visitors and the public flows (invitation, sign-in callback) get a public frame without them.
- **Landing page (UI-002, Issue #63):** an anonymous visitor at `/` gets the public landing page in its own frame (`features/landing`); a signed-in user at `/` keeps the role home. The URL, `signIn('/')`, the post-logout redirect and every other route are unchanged. The landing content is lazy-loaded, static and truthful: one typed catalogue (`features/landing/catalogue.ts`) decides which modules are "Available now" and which are "Coming next", holds the two product-state lists (UI-004), and integrations and payout rails are shown as planned. Third-party marks are local files in `public/brands/`, recorded in `docs/BRAND-ASSETS.md`; the page makes no third-party request. It does not hard-code a deployment host.
- **Browser storage:** only UI preferences through `app/preferences.ts` (language, theme, sidebar collapse). No tokens, roles, tenant identifiers, route history or HR content.
- **Design system:** tokens in `packages/design-system`; primitives in `apps/web/src/ui`, extracted to the package when a second application needs them.

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
- Documents use encrypted object storage; databases retain metadata and access references. Exception (ADR 0009): the rendered text of an issued contract (at most 64 KiB) is stored in PostgreSQL with its digest; no file is generated.
- Search indexes enforce tenant and authorization boundaries.
- Analytical projections are fed by governed events.
- Effective-dated records preserve employment, organization, policy, and country-rule history.
- Scheduling (MVP-050A, ADR 0014) starts as a bounded package of the people module (`com.divalhr.core.people.schedule`, architecture-tested) because its eligibility reads people's own employment facts; publication serializes on a tenant-wide advisory scheduling lock taken before the assigned employees' employments (`FOR SHARE`). Each schedule runs on its site's time zone (read through the `OrganizationPlacementDirectory` port, stored as a snapshot), not the organization's. Its site key to `tenant.site` and the snapshot check are a documented integrity exception; extraction to the Operations module needs an employment-facts port. MVP-050B (ADR 0014) adds cancellation and full replacement of an entirely future schedule as append-only evidence with a derived status; the site and employee conflict exclusions live on guarded operational coverage projections, and a change takes the scheduling lock, then the schedule row `FOR UPDATE`, then the employments `FOR SHARE`. The schedule package keeps its own copy of the decision-reason grammar (equivalence-tested) rather than depend on the leave package.
- Payroll preparation (MVP-090A, ADR 0023) is its own bounded module (`com.divalhr.core.payroll`, schema `payroll`, architecture-tested): it reads people's locked approved time only through the platform port `LockedApprovedTime`, implemented by the people schedule package, which also joins the caller's transaction to the tenant-wide scheduling lock so a period is evaluated and written under the same lock every timesheet command takes. Payroll keys only to `tenant.legal_entity` and `tenant.site`; its runs are append-only evidence that later upstream changes never alter.

- Payroll variance review (MVP-091, ADR 0024) lives in the same payroll module and is a pure read: it compares two V37 runs by employee ID in `PayrollVariance` (domain), served by `PayrollVarianceService` and `PayrollVarianceController`; no table, event or port is added.

- Payroll-input versions, the final lock and the worked-time export (MVP-092, ADR 0025) extend the payroll module: `PayrollLifecycle` (domain: blockers, CSV, manifest, notices), `JdbcPayrollLifecycleRepository`, `PayrollLifecycleService` and two controllers; V38 adds append-only tables referencing V37 keys only. Every command takes the tenant scheduling lock first, then a scope advisory lock; lock, audit and outbox commit atomically.
- Operational status (MVP-100, ADR 0026) is a pure read inside `people.schedule` (the ArchUnit boundaries forbid a new package depending on schedules and leave): `OperationalStatus` (domain: thresholds, scope, counts), `JdbcOperationalStatusRepository`, `OperationalStatusService` and `OperationalStatusController`. It reuses the MVP-060C shared SQL (effective assignee, terminal schedules, team predicate) and the MVP-070B routing predicate; no table, event or port is added.

## Offline architecture

Essential attendance, roster, task, and expense workflows use a secure local queue. Synchronization preserves device event time and server receipt time, uses idempotent requests, and sends uncertain conflicts to human review.

Implemented for attendance (MVP-061A, MVP-061B): the development increment is a **foreground** queue for screens that are already loaded.

- **Capture.** On « Mon horaire » and « Ma présence », a confirmed check-in or check-out made while the browser is offline, or whose online command fails without any HTTP response, is saved in IndexedDB (`divalhr-attendance-queue`, one row per event). It is sent through `POST /me/attendance/offline-events:sync` while the app is open: once when the employee's verified session is ready, on the browser's `online` event, and on "Sync now".
- **Isolation.** Rows are separated per signed-in user by a SHA-256 owner scope of the verified tenant and subject.
- **Not encrypted.** The rows are not encrypted by DivalHR.
- **Service worker.** It is unchanged: it caches the application shell only, has no runtime caching, and never answers API, identity, auth or AI requests (`src/pwa/workbox.ts`).
- **Not provided:** a cold start offline, Background Sync, push, or a queue shared across devices.
- **Production follow-ups:**
  - cold-start offline launch;
  - background synchronization;
  - browser encryption or native secure storage;
  - device attestation;
  - support recovery of a stuck device queue;
  - cross-device behaviour;
  - queue monitoring.

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

### Outbound webhooks (MVP-110, ADR 0028)

The `integrations` module owns webhooks, secrets, deliveries and attempts (schema `integrations`, V39).
It never reads another module's tables: a platform `OutboxReader` port exposes the durable outbox
events, a fan-out job copies each event once into a local ledger (`fanout_event`, watermark in
`fanout_state`, advisory lock, periodic rescan) and creates one delivery per matching enabled
webhook. Workers claim deliveries with `FOR UPDATE SKIP LOCKED` and a lease token, resolve the host,
validate every address, and send through an address-pinned HTTPS transport without redirects.
`AccessPortArchitectureTest` keeps other modules out of the `integrations` tables and the module out
of theirs. See `docs/WEBHOOKS.md` and ADR 0028.

### Operational tasks (MVP-080, ADR 0030)

The `operations` module owns `operations.task` (projection) and `operations.task_action`
(append-only evidence), V41. Commands lock the task row, validate the expected version and the
legal transition, write projection, action, audit and outbox event in one transaction and are
idempotent. The assignee is checked through the people and identity ports; the module reads no
other module's tables and no module reads its tables (`AccessPortArchitectureTest`). Overdue is
derived at read time, never stored.

### WhatsApp Business notifications (MVP-111, ADR 0029)

The `integrations` module also owns the tenant's one WhatsApp connector, its recipients, deliveries,
attempts and provider callbacks (V40, package `integrations.messaging`; the domain, application,
`internal` and `api` layers follow MVP-110). It reuses the MVP-110 outbox reader, public-event mapper and
fan-out ledger idea (a separate ledger and watermark, so the two features never share state): a
fan-out job turns each committed event of the closed catalogue into one delivery per opted-in
recipient, and a worker claims deliveries with `FOR UPDATE SKIP LOCKED` and a lease, calls the Graph
API through `MessagingProvider` outside any transaction, and records the closed outcome. The Graph
origin is operator configuration (only `https://graph.facebook.com` in production). Meta's delivery
statuses return on the one anonymous door `/api/v1/public/messaging/whatsapp/callback`, served by the
token-free public chain and authenticated by the app-secret signature over the raw bytes.
Tokens and recipient numbers are encrypted at rest with a dedicated key; messages are generic
template messages with a deep link only. `AccessPortArchitectureTest` keeps other modules out of the
`integrations` tables. See `docs/WHATSAPP.md`, ADR 0029 and
`docs/security/MVP-111-WHATSAPP-DATA-FLOW.md`.

### Grounded operational summary (MVP-101, ADR 0027)

Core calls the AI service over HTTP with a Keycloak client-credentials token (`platform.ai`:
`AiServiceProperties`, `ServiceTokenProvider`, `AiServiceOperationalSummaryClient`, the
`OperationalSummaryPort`). The use case lives in `people.schedule` beside MVP-100:
`OperationalSummary` (domain: facts, severity bands, validation of the answer),
`OperationalSummaryService` and `OperationalSummaryController`. The AI service
(`apps/ai-service`, `summary/`) owns the policy, templates and a provider-neutral gateway; the
browser never calls it. No table, migration, event or stored prompt is added.
