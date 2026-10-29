# ADR 0032 evidence map (MVP-093A, Issue #175), backend

Where each decision is implemented and which test proves it. Paths are under `apps/core-api/src`
(`main` or `test`, package `com.divalhr.core.payroll`) unless they start with `docs` or `scripts`.
The web and truthfulness rows follow the backend rows. Integration tests run
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

| D93-12 UI | `apps/web/src/features/compensation/*` (`EmployeeCompensationSection` on the employee profile, `CompensationForm`, `CompensationCancelPanel`, `MyCompensationPage` at `/me/compensation`), `app/routes.tsx`, `shell/navigation.ts` | `compensation.test.ts`, `compensationPages.test.tsx`, `navigation.test.ts`, `AppShell.test.tsx`, `pwa/workbox.test.ts`, `e2e/compensation.spec.ts` (exactly two journeys) |
| D93-13 backlog | `docs/BACKLOG.md` (Git evidence table, deployed release, MVP-093A section), `scripts/ops/backlog.test.mjs` | `pnpm test:ops` backlog test: every row checked against Git (merge subject, PR number, date, ancestry) |
| D93-14 landing | `features/landing/catalogue.ts`, `LandingContent.tsx`, `landing.css` | `LandingPage.test.tsx`, `e2e/landing.spec.ts` (extended, no new screenshot-only journey) |
| D93-15 role homes | `features/home/HomePage.tsx`, `roadmap.ts` | `HomePage.test.tsx`, `e2e/role-homes.spec.ts` (extended) |
| D93-16 accessibility, localization | `packages/localization/locales/{en,fr}/common.json` (`compensation`, `nav.myCompensation`, `errors.COMPENSATION_*`) | key-parity tests, `compensation.test.ts` (Intl formatting), axe, keyboard and reflow checks (390, 320 and 640 px) inside the two compensation journeys |

## Test and mutation results (Mac, JDK 21, Docker)

- Core API build `evidence-175/build-20261029T152236Z.txt`: unit, compensation integration, Separation and architecture/contract/denial-audit stages all exit 0 at `1ed8423`.
- Mutations `scripts/dev/compensation-mutations.sh` at `c940ef6`: all five killed by a failing test (tenant predicate in `version()` by `JdbcCompensationRepositoryTenantTest`; USD exponent by `CompensationMoneyTest`; timeline close by `CompensationTimelineTest`; amount in audit metadata by `CompensationRecorderTest`; preview quota by `CompensationQuotasTest`). The first run left mutations 1 and 5 surviving; the tests were strengthened, not the mutations weakened.
- The two Playwright journeys run in normal GitHub CI against the Compose stack.

## Known gaps

- The employment-digest drift through the API and an EN/FR error-body test are not covered by a dedicated test.
- The preview quota is a per-instance counter; commit quotas and platform buckets hold across instances.
