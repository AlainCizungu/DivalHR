# LAUNCH-001 evidence map (Issue #226, ADR 0046)

Each decision of ADR 0046 maps to the code that enforces it and the tests that prove it. Paths are relative to the repository root. This is a governance framework, not legal advice or a certification.

| Decision | Enforced by | Proved by |
| --- | --- | --- |
| L1 No invented legal rule | No duration in `V58__privacy_governance.sql`, seeds or fixtures; operator-entered rules only (`PrivacyPolicyService.parseDraft`) | `PrivacyGovernanceIntegrationTest` (synthetic country; every class is refused as `POLICY_PENDING` before any policy is applied) |
| L2 Four data classes, one purpose each | `PrivacyDomain.DataClass`, rule table CHECKs in V58 | `PrivacyMigrationIntegrationTest` (orphaning rule set refused); `privacy.test.ts` |
| L3 Country policy packs | `PrivacyPolicyService.checkRules`, V58 rule guard | `PrivacyMigrationIntegrationTest` (policy lifecycle, checklist, overlap) |
| L4 Governance lifecycle | `PrivacyDomain.POLICY_TRANSITIONS`, V58 policy guard, `PrivacyPolicyService.moved` | `PrivacyMigrationIntegrationTest`; `PrivacyGovernanceIntegrationTest` (draft to active through the API); `privacy.test.ts` (table) |
| L5 Existing and new referrals | V58 backfill and `privacy_stamp_new_referral()`, `PrivacyPolicyApplicationService` | `PrivacyMigrationIntegrationTest` (stamping); `PrivacyGovernanceIntegrationTest` (pending until applied, stamp kept, preview reuse refused) |
| L6 Hold | `PrivacyHoldService`, append-only V58 tables, purge eligibility | `PrivacyMigrationIntegrationTest` (hold append-only); `PrivacyGovernanceIntegrationTest` (hold blocks the purge, release makes it eligible) |
| L7 Data-rights requests | `PrivacyRequestService`, `PlatformPrivacyRequestService`, V58 request guard | `PrivacyMigrationIntegrationTest` (request lifecycle); `PrivacyEmployeeRequestsIntegrationTest`; `PrivacyQuotasTest` |
| L8 Access package | `PrivacyExportService`, `PrivacyExportStore` | `PrivacyEmployeeRequestsIntegrationTest` (digest equals header, no other employee data, stable repeat download); Playwright journey 1 |
| L9 Minimization and erasure | `PrivacyPurgeService`, `PrivacyGovernanceStore`, V58 `privacy_purge_item` guard and evidence guard | `PrivacyMigrationIntegrationTest` (item guards, deletion order, rollback refusal); `PrivacyGovernanceIntegrationTest` (stale digest, reuse refused, value-free evidence); Playwright journey 2 |
| L10 Privacy boundary | `@PlatformScoped`, `@EmployeeSelfOperation`, own-reference lookups | `PrivacyGovernanceIntegrationTest` (403 and 401 on every platform route); `PrivacyEmployeeRequestsIntegrationTest` (another employee and tenant administrator see nothing); both Playwright journeys |
| L11 Notifications | None are sent | No notification code exists in the package (reviewed in the PR; the platform notification port is not referenced) |
| L12 Evidence retention | Append-only V58 tables, no delete path | `PrivacyMigrationIntegrationTest` |
| Rate limits | `PrivacyQuotas`, `request-limits.subject.privacy-*` in `application.yaml` | `PrivacyQuotasTest` (fixed clock: exact limits, window edges, retry-after, per-subject counters) |
| Contract | `docs/API-SPEC.yaml`, `ErrorCode.java` | `ApiContractDriftTest`, `PrivacyErrorCodesTest`, `pnpm api:lint`, `pnpm api:check` |
| English and French, no compliance claim | `packages/localization/locales/*/common.json` (`privacy.*`) | `privacy.test.ts` (key parity, no claim wording) |
| Navigation | `navigationModel.ts`, `routes.tsx` | `navigationModel.test.ts`, `AppShell.test.tsx` |
