# ADR 0031 evidence map (MVP-081, Issue #169)

Where each decision is implemented and which test proves it. Paths are under `apps/core-api/src`
(`main` or `test`, package `com.divalhr.core.operations`) unless they start with `apps/web`,
`docs` or `packages`. Integration tests run against real PostgreSQL in normal CI.

| Decision | Code | Evidence |
| --- | --- | --- |
| D81-1 roles and routes | `api/MyExpenseController`, `api/ExpenseApprovalController`, `api/ExpenseController`, `@EmployeeSelfOperation`, `@TenantAdminOperation`, `platform/access/ReportingLines`; `apps/web/src/app/routes.tsx` | `ExpenseApiIntegrationTest` (own-only reads, `tenantsAreIsolatedForEveryReaderAndEveryCursor`, `theAdministratorReads…`), `AccessPortArchitectureTest`, `OperationNameTest`, `apps/web/src/shell/navigation.test.ts` |
| D81-2 currency and amount | `domain/ExpenseCurrency`, V42 money check | `ExpenseCurrencyTest` (mutation: exponent 2 to 3 caught), `ExpenseMigrationIntegrationTest`, `apps/web/src/features/expenses/expenses.test.ts` |
| D81-3 expense data | `application/ExpenseCommands`, `application/TaskText`, V42 text checks | `ExpenseCommandsTest`, `ExpenseApiIntegrationTest` (validation without echo) |
| D81-4 business date | `ExpenseCommands` (90-day window), `application/OperationsCalendar` | `ExpenseCommandsTest` boundaries, `expenses.test.ts` date window |
| D81-5 receipt evidence | `application/ReceiptVerifier`, `ReceiptBody`, `ExpenseReceiptDownload`, V42 receipt checks | `ReceiptVerifierTest` (mutation: type-vs-magic caught), `ExpenseApiIntegrationTest` (authorization before body, no-store, nosniff), `ExpenseMigrationIntegrationTest` |
| D81-6 lifecycle | `domain/ExpenseState`, `MyExpenseService`, `ExpenseApprovalService`, V42 guards | `ExpenseApiIntegrationTest.returnCorrectionResubmissionAndApproval`, `ExpenseDatabaseAndConcurrencyIntegrationTest` |
| D81-7 manager routing | `application/ExpenseRouting`, `ReportingLines`, `people/application/PeopleReportingLines` | `ExpenseApiIntegrationTest.aFormerManagerLosesAccess…` (mutation: route revalidation caught), `…routeUnavailable…` |
| D81-8 revision and evidence | V42 `expense`, `expense_revision`, `expense_receipt`, `expense_action`, `internal/JdbcExpenseRepository`, `db/rollback/V42__rollback.sql` | `ExpenseDatabaseAndConcurrencyIntegrationTest.terminalExpensesAndAllEvidenceAreImmutableAndNeverDeleted` (mutation: binding trigger caught), `ExpenseMigrationIntegrationTest` (rollback restore and refusal) |
| D81-9 idempotency, transactions | `IdempotentOperation` in the services, row lock in `JdbcExpenseRepository` | `ExpenseDatabaseAndConcurrencyIntegrationTest` (races, identical retries), `ExpenseRollbackIntegrationTest` (audit and outbox failure roll back) |
| D81-10 API | `docs/API-SPEC.yaml`, `api/*`, `application/ExpensePaging`, `packages/api-client` | `ApiContractDriftTest`, `pnpm api:lint`, `pnpm api:check`, paging and cursor tests in `ExpenseApiIntegrationTest` |
| D81-11 audit and events | `application/ExpenseRecorder` | `ExpenseApiIntegrationTest` (text never in audit, outbox, idempotency, logs), `OperationNameTest` |
| D81-12 UI and accessibility | `apps/web/src/features/expenses/*`, `packages/localization/locales/{en,fr}/common.json` | `expenses.test.ts`, `expensesPages.test.tsx` (English, French, axe), `apps/web/e2e/expenses.spec.ts` |
| D81-13 limits and privacy | `application/ExpenseQuotas`, `application.yaml` buckets, V42 open-expense triggers | `ExpenseQuotasTest`, `ExpenseDatabaseAndConcurrencyIntegrationTest` (100 per employee, 5,000 per tenant, at database level) |
| D81-14 tests | all of the above | two Playwright journeys; mutation proofs for amount exponent, receipt magic, route revalidation, tenant binding and immutable revision binding |

## Deliberately not repeated

The full host suite and AWS verification are not repeated during development. Core integration
tests run once, in normal GitHub CI on the pull request head; AWS verification, merge and deployment
wait for architecture review. The two Playwright journeys were not run in the development sandbox
(no full stack); they run in normal CI.
