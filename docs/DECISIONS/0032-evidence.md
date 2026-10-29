# ADR 0032 evidence map (MVP-093A, Issue #175), backend

Where each decision is implemented and which test proves it. Paths are under `apps/core-api/src`
(`main` or `test`, package `com.divalhr.core.payroll`) unless they start with `docs` or `scripts`.
The web evidence (D93-12, D93-16) belongs to the web part of the story. Integration tests run
against real PostgreSQL in normal CI.

| Decision | Code | Evidence |
| --- | --- | --- |
| D93-1 meaning | `domain/CompensationCurrency`, `CompensationEvaluator` (no calculation anywhere) | `CompensationMoneyTest`, `CompensationEvaluatorTest` |
| D93-2 authority | `api/EmployeeCompensationController` (`@TenantAdminOperation`), `api/MyCompensationController` (`@EmployeeSelfOperation`), `application/MyCompensationService` | `CompensationApiIntegrationTest` (employee refused on every admin operation, own-read only, safe 404s, tenant isolation), `AuthorizationDenialAuditIntegrationTest` |
| D93-3 classification | `api/CompensationResponses` (`toString` hides amounts), `application/CompensationRecorder`, `CompensationCommands` | `CompensationApiIntegrationTest` (no-cache headers, privacy sentinel scan of audit, outbox, idempotency, logs, errors), `CompensationRecorderTest`, `CompensationCommandsTest` (nothing printed) |
| D93-4 money | `domain/CompensationMoney`, `CompensationCurrency`, V43 money checks | `CompensationMoneyTest` (mutation: USD exponent 2 to 0 caught), `CompensationMigrationIntegrationTest` |
| D93-5 dates, employment | `domain/CompensationDates`, `CompensationTimeline`, `platform/access/EmploymentIntervals`, `people/application/PeopleEmploymentIntervals`, `CompensationEmploymentGuard` + `EmployeeSeparationService` | `CompensationDatesTest`, `CompensationTimelineTest` (mutation: overlapping close caught), `CompensationApiIntegrationTest` (date before employment, separation guard), `AccessPortArchitectureTest` |
| D93-6 evidence (V43) | `internal/JdbcCompensationRepository`, `CompensationConstraintViolations`, `db/rollback/V43__rollback.sql` | `CompensationMigrationIntegrationTest` (every refusal by constraint name, rollback restores V42 and refuses once rows exist), `TenantPredicateArchitectureTest` |
| D93-7 actions | `domain/CompensationTimeline`, `CompensationEvaluator`, `application/CompensationService` | `CompensationApiIntegrationTest` (establish, schedule, correct, cancel, identical values, scheduled versus started cancellation) |
| D93-8 preview, confirm | `application/CompensationDigests`, `CompensationService`, `CompensationCalendar` | `CompensationApiIntegrationTest` (stale, expired, altered preview, replay, `KEY_REUSED`), `CompensationDigestsTest`, `CompensationConcurrencyIntegrationTest` (one change, others version conflicts), `CompensationRollbackIntegrationTest` |
| D93-9 API | `docs/API-SPEC.yaml`, `api/*`, `platform/error/ErrorCode`, `application/CompensationReader` (keyset) | `ApiContractDriftTest`, `CompensationApiIntegrationTest` (cursor bound to employee, role and size), `CompensationCommandsTest` |
| D93-10 limits | `application/CompensationQuotas`, `application.yaml` buckets, V43 limit triggers | `CompensationQuotasTest` (mutation: preview limit 60 to 600 caught), `CompensationMigrationIntegrationTest` (100 changes) |
| D93-11 audit and events | `application/CompensationRecorder` | `CompensationRecorderTest` (allow-list, mutation: amount in metadata caught), `CompensationEventsNotDeliveredTest`, `OperationNameTest` |
| Lock order | `CompensationService`, `PeopleEmploymentIntervals.lockShared`, V43 advisory-lock triggers | `AccessPortArchitectureTest`, `CompensationConcurrencyIntegrationTest` |

Mutation proofs: `scripts/dev/compensation-mutations.sh` (tenant predicate, USD exponent, timeline
close, amount in audit metadata, preview quota). It was written but not run in the development
sandbox; it requires Gradle and Docker.

## Not covered

D93-12 and D93-16 (web), the employment-digest drift through the API, and the web client regeneration
are outside this backend increment.
