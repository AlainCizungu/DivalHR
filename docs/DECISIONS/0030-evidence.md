# ADR 0030 evidence map (MVP-080, Issue #167)

Where each decision is implemented and which test proves it. Paths are under `apps/core-api/src`
(`main` or `test`, package `com.divalhr.core.operations`) unless they start with `apps/web`,
`docs` or `packages`. Integration tests run against real PostgreSQL in normal CI.

| Decision | Code | Evidence |
| --- | --- | --- |
| D80-1 roles and routes | `api/TaskController`, `api/MyTaskController`, `@TenantAdminOperation`, `@EmployeeSelfOperation`; `apps/web/src/app/routes.tsx` | `TaskApiIntegrationTest.employeeSeesOnlyOwnTasksAndLosesAccessWithTheLink`, `.tenantsAreIsolated`; `AuthorizationDenialAuditIntegrationTest`; `apps/web/src/shell/navigation.test.ts` |
| D80-2 data and text | `application/TaskText`, `application/TaskCommands`, `domain/Task` (`toString` ids only), V41 `task_text_valid` | `TaskTextTest`, `TaskCommandsTest`, `TaskMigrationIntegrationTest` (text checks, grammar superset corpus), `TaskApiIntegrationTest.validationRefusesWithoutEchoingText` |
| D80-3 eligibility | `application/TaskService.create` (people and identity ports), `MyTaskService` (link on every call) | `TaskApiIntegrationTest.assigneeEligibilityIsIndistinguishable`, `.identicalReplayAfterLossOfAccessIsRefused` |
| D80-4 lifecycle | `application/TaskTransitions`, `MyTaskService`, `TaskService.cancel`, V41 guards | `TaskApiIntegrationTest.everyLegalTransition`, `.illegalTransitionsAndTerminalImmutability`, `.cancellationReasonRules`, `.createReplayReuseAndStale` |
| D80-5 dates | `domain/Task.classify`, `application/OperationsCalendar`, V41 due check (8,784 hours) | `TaskDueBoundaryIntegrationTest`, `TaskDueClassTest`, `TaskMigrationIntegrationTest` |
| D80-6 persistence | `db/migration/V41__operations_task_lifecycle.sql`, `db/rollback/V41__rollback.sql`, `internal/JdbcTaskRepository` | `TaskMigrationIntegrationTest` (constraints, append-only, binding, sequence, rollback restore and refusal, 10,000 limit) |
| D80-7 idempotency, atomicity | `IdempotentOperation` use in `TaskService`/`MyTaskService`, row lock in `JdbcTaskRepository.lock` | `TaskApiIntegrationTest.createReplayReuseAndStale`, `TaskConcurrencyAndRollbackIntegrationTest` (race, identical retries, rollback of create and transitions) |
| D80-8 API | `docs/API-SPEC.yaml`, `api/*`, `application/TaskPaging`, `packages/api-client` | `ApiContractDriftTest`, `pnpm api:lint`, `pnpm api:check`, `TaskApiIntegrationTest.orderingFiltersAndPagination`, cursor binding in `.tenantsAreIsolated` |
| D80-9 audit and events | `application/TaskRecorder` | `TaskApiIntegrationTest.userEnteredTextNeverReachesAuditOutboxIdempotencyOrLogs`, `.eventsCarryOnlyIdentifiers`; `OperationNameTest` |
| D80-10 UI and accessibility | `apps/web/src/features/tasks/*` | `tasks.test.ts`, `tasksPages.test.tsx` (English and French, axe), `apps/web/e2e/tasks.spec.ts` (axe, 320, 390, 640 px) |
| D80-11 limits | `application/TaskQuotas`, `application.yaml` buckets, V41 open-task trigger | `TaskQuotasTest`, `TaskMigrationIntegrationTest` (10,000), request limits in `application.yaml` |
| D80-12 tests | all of the above | two Playwright journeys in `apps/web/e2e/tasks.spec.ts`; module rules in `TenantPredicateArchitectureTest`, `AccessPortArchitectureTest` |

## Deliberately not repeated

The full host suite and AWS verification are not repeated during development. Core integration
tests run once, in normal GitHub CI on the pull request head; AWS verification, merge and deployment
wait for architecture review.
