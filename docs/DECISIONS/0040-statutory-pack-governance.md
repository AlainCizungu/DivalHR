# ADR 0040: Governed lifecycle and offline validation of statutory payroll packs (MVP-097A, Issue #200)

Status: accepted for implementation (decisions D97A-1 to D97A-14 of Issue #200 are binding; this ADR records the design
points the issue leaves to the implementation). Builds on ADR 0034 (statutory preview), ADR 0035 (approval and payslips)
and ADR 0039 (accounting journal). Migration: **V51**. Base: `main` at `55b130f5bde8eedee4589f5e00b9e17d927457f1`.

## 1. Legal boundary (read first)

This change builds a **mechanism**. It does not decide, infer, update or approve any statutory rate, threshold,
contribution, exemption, formula or filing rule, and it generates no statutory content. Passing tests never means that
DivalHR is legally compliant.

- **`CD-SALARY-PREVIEW` stays DRAFT.** It is not renamed, its formulas and values are untouched, and it is not
  validated, reviewed, scheduled or activated with synthetic fixtures. It carries no reference cases and no source
  evidence, so a validation of it fails honestly (`CASES_PRESENT`). The wording "DRAFT — not approved for operational
  payroll" / « BROUILLON — non approuvé pour la paie opérationnelle » stays visible. Activating the real DRC pack with
  authentic legal evidence and independent approval is MVP-097B.
- **VALIDATED is not legal approval.** "Technical validation passed" / « Validation technique réussie » says the pack is
  internally consistent and reproduces its reference cases. The platform page states this permanently.
- Synthetic fixtures (`SYNTHETIC-TEST-*`, effective in 2090 for the browser journeys) prove the mechanism only.

## 2. Lifecycle (D97A-2, D97A-3)

`DRAFT → VALIDATED → REVIEWED → SCHEDULED → ACTIVE → RETIRED`, enforced by a database trigger, not only by the service.

| Command | From → to | Needs |
|---|---|---|
| validate | DRAFT → VALIDATED (only on a passing run) | expected version, offline validator passes, at least one reference case |
| submit-review | VALIDATED (submitted marker) | complete source evidence |
| approve | VALIDATED → REVIEWED | reviewer ≠ submitter, explicit confirmation, exact MFA |
| reject | VALIDATED → RETIRED (reason `REJECTED`) | one bounded reason code, optional bounded note |
| schedule | REVIEWED → SCHEDULED | future effective date inside the pack interval, no overlap |
| activate | REVIEWED or SCHEDULED → ACTIVE | confirmation, no overlap, effective date reached when scheduled |
| retire | REVIEWED, SCHEDULED or ACTIVE → RETIRED | confirmation, reason code |

A rejection returns to DRAFT work by a **new immutable version** (`createRevision`, `supersedes_pack_id`); nothing is
edited in place and nothing is deleted. RETIRED is readable forever and never reactivated. Self-approval, a stale
`expectedVersion` and an invalid transition fail before any state change. Concurrent approve/activate is serialised by the
row lock plus an advisory lock per country, so exactly one transition wins.

## 3. Authority (D97A-1)

Mutations require the `platform-admin` role, the exact MFA authority and the active platform authority
(`@PlatformScoped`, checked before the body is bound). No new realm role. Tenant administrators read only the applicable
ACTIVE pack and its public provenance through the legal-entity route; employees and managers have no route.

A second seeded platform administrator (`dev-platform-reviewer`, development realm only, published seed) exists so that
the four-eyes journey uses two distinct verified subjects.

## 4. Evidence (D97A-4)

`payroll.statutory_pack_governance` (one row per pack version) holds immutable non-secret evidence: source authority,
document title, publication date, URL or reference id (https only), SHA-256 of the archived source bundle, evidence schema
version, submitter, reviewer, times, the pack canonical digest (`pack_sha256`, equal to the stored `rules_digest`) and the
fixture canonical digest. Evidence is editable only in DRAFT; from VALIDATED on, evidence and digests never change.
Copyrighted documents are never stored and the runtime never fetches the internet.

## 5. Validation (D97A-5)

`StatutoryPackValidator` is pure, offline and deterministic (`pack-validator-1`). Checks, in order: `PACK_PARSES`,
`SOURCES_DECLARED`, `MONEY_INTEGER_MINOR`, `BRACKETS_ORDERED`, `EFFECTIVE_PERIOD_VALID`, `CASES_PRESENT`,
`CASES_WELL_FORMED`, `CASES_COVER_RULES`, one `CASE:<key>` per reference case, `DETERMINISTIC`, `LOCALE_INDEPENDENT`,
`DIGESTS_STABLE`. It reuses the existing closed formula model (`StatutoryRules.parse`, `StatutoryEngine`), so the engine
that validates is the engine that calculates. Mutating a formula, threshold, rounding rule, fixture input or expected
result fails (`StatutoryPackValidatorTest`). Each run is appended to `statutory_pack_validation_run`, bound to both digests.

## 6. Selection and activation (D97A-6)

At most one SCHEDULED/ACTIVE version per country, applicability and date range (trigger with an advisory lock, constraint
`payroll_statutory_pack_period_overlap`). Calculation selects the pack by **legal-entity country and payroll period**
(`applicableActivePackId`), never by a client pack id alone; a client pack id that disagrees is refused
(`STATUTORY_PACK_NO_ACTIVE_VERSION`). A run without an applicable ACTIVE pack is refused with that stable error. Frozen
calculations keep their pack code, version and digest. The legacy `VERIFIED`/`RETIRED` status of `statutory_rule_pack` now
follows the governed lifecycle and can only be written from the governance trigger (trigger depth 2); direct writes are
refused (`payroll_statutory_rule_pack_governed`). The DRAFT preview path stays visibly non-production.

## 7. Persistence (V51)

New tables: `statutory_pack_governance`, `statutory_reference_case` (≤100 per version), `statutory_pack_validation_run`,
`statutory_pack_review` (one decision per version, `confirmed AND mfa_verified`, reviewer ≠ submitter) and
`statutory_pack_transition` (append-only history). Evidence tables are append-only (update, delete and truncate refused).
At most 25 versions per pack code. The rollback script refuses while any new business row exists, then restores the V45
guard. Existing packs are handled by the legacy quarantine of section 7a.

## 7a. Legacy quarantine (R200-1, amends section 7)

The first deployment of V51 to hr-dev was refused by the migration itself: the original version raised if any non-DRAFT
pack existed, and hr-dev holds seventeen VERIFIED or RETIRED synthetic test packs created before governance existed. The
runbook rolled back; no data changed. The architect's decision R200-1 replaces that refusal with a quarantine:

- The state `LEGACY` is added to the closed set. It is **not** a lifecycle step: no command leads to it or from it, and the
  guard refuses every update of a LEGACY row. V51 backfills it for every existing pack whose legacy status is VERIFIED or
  RETIRED; a DRAFT pack keeps a DRAFT row. The backfill sets nothing else: no evidence, no digest, no period, no review, no
  case, no history row. Nothing is invented, inferred or approved, and LEGACY is not a statement that the pack is correct
  or legally valid.
- Not one column of the pack changes. Its legacy status stays as it was, and every historical reference is preserved
  exactly: frozen calculations, approved packages, payslips and evidence keep the pack code, version and digest they
  were made with and stay readable and unchanged, and the activation rows are never edited or deleted. The legacy status
  can still move only from the governance guard, which refuses LEGACY, so a legacy pack is frozen.
- A LEGACY version is never the applicable ACTIVE version (`applicableActivePackId` requires governed ACTIVE), so no new
  employer activation can rest on it. **R202-1: it powers no new calculation either.** `StatutoryEvaluator.resolve` uses
  only a pack whose governance state is exactly ACTIVE (selected by country and period, then checked again by state),
  and an older activation that points to a LEGACY pack resolves nothing: a preview is BLOCKED with `RULE_PACK_MISSING`
  and a commit is refused with `STATUTORY_PACK_NO_ACTIVE_VERSION`. No other pack (a DRAFT one, or another governed ACTIVE
  one that applies to the same country and period) is substituted for it. Applicability reads never report LEGACY as
  applicable. Frozen calculations never go through this resolution: they read their stored pack by identifier. The one
  consequence to know: a package approval that re-reads the current pack digest of a month whose activation points to a
  LEGACY pack no longer finds one, so it is not offered as current. That is the intended quarantine. It is readable by platform administrators with the state
  `LEGACY` and a plain notice, offers no command, and is excluded from overlap checks. A correction is a new governed
  version, created DRAFT and walked through the full lifecycle with independent review.
- `LEGACY` is never assigned to a new row: a trigger refuses an INSERT with that state once the backfill has run.
- The rollback script treats an untouched LEGACY row like an untouched DRAFT row and still restores V50 exactly.
- V51 was edited in place because it had not applied successfully anywhere: every database it ran on was fresh
  (continuous integration, the AWS verification environment) or rolled back (hr-dev). Flyway on hr-dev has no V51 row.

Evidence: `StatutoryLegacyPackResolutionIntegrationTest` and `StatutoryLegacyActivationMigrationIntegrationTest` (R202-1: a legacy VERIFIED pack with an existing activation migrates, history stays readable, a new calculation and another substituted pack are refused, applicability never reports it, nothing is fabricated), `StatutoryPackLegacyMigrationIntegrationTest` (a V50 database holding a VERIFIED and a RETIRED pack migrates, the
packs are byte-identical, the rows are LEGACY with no evidence, the rows are frozen, never applicable and never inserted,
the rollback still restores V50), the web tests of the LEGACY state and notice, and the English and French locale files.

## 8. API (D97A-8)

OpenAPI first (`docs/API-SPEC.yaml`), client regenerated. Platform: `GET /api/v1/platform/statutory-packs` (keyset
cursor, default 25, max 100), `GET …/{packCode}/versions/{version}`, `POST …/versions/{version}:validate | :submit-review |
:approve | :reject | :schedule | :activate | :retire`. Tenant: `GET /api/v1/legal-entities/{legalEntityId}/statutory-pack-applicability?payrollPeriod=YYYY-MM`.
Every response is `private, no-store`. Commands take `Idempotency-Key`, `expectedVersion` and, where consequential, an
explicit `confirmed` flag. Eleven stable `STATUTORY_PACK_*` errors carry EN and FR messages.

## 9. Audit, events, privacy (D97A-11)

Audit actions `statutory-pack.read|validate|submit-review|approve|reject|schedule|activate|retire|applicability-read`.
Internal events `payroll.statutory-pack.validated|reviewed|activated|retired.v1` through the transactional outbox. A
platform action has no tenant, so it uses the nil-UUID platform scope sentinel; payload keys pass an allow-list (ids,
country, version, state, digests only), so rule values, notes and fixtures never reach audit, events, logs or metrics.
Nothing is sent to public webhooks or WhatsApp. The tenant read is audited with the tenant id.

## 10. Limits and rate limits

25 versions per pack code; 100 reference cases per version; 1 MiB canonical pack plus fixtures. Validation: 10 per subject
and 50 per platform per hour. Review and activation commands: 10 per subject per hour. Request buckets
`statutory-pack-read` (60/min) and `statutory-pack-command` (5/min) in `application.yaml`.

## 11. UI (D97A-12, D97A-14)

Platform page "Statutory packs" / « Référentiels légaux de paie » (list and detail): lifecycle badge with text (never
colour alone), evidence and digest summary, validation results, review and activation history, only the actions legal for
the current state, confirmations for approval, activation and retirement, and the permanent "technical validation is not
legal approval" warning. The tenant administrator gets a read-only applicable-pack panel in the legal-entity payroll area
with a clear refusal when no ACTIVE pack applies; no formulas, notes or documents are shown to tenants. English and French
keys are in step; keyboard operation, axe, reflow at 320/390/640 px, 200 % zoom and long French labels are covered.

## 12. Truth (D97A-13)

The landing page keeps saying the real DRC pack is DRAFT and marks no DRC payroll operational. The release pointer is
corrected: `55b130f5bde8eedee4589f5e00b9e17d927457f1` (TRUTH-003) is the presentation and truth release now running, and
`c368d58b15e1aeddb755997728154a497348c07c` (MVP-096) is preserved as the latest capability release before MVP-097A. The
backlog test pins both. MVP-097A is recorded as merged or deployed only when that happens.

## 13. Evidence map

| Decision | Evidence |
|---|---|
| D97A-1 | `StatutoryPackGovernanceController` (`@PlatformScoped`), `StatutoryPackApplicabilityController` (`@TenantAdminOperation`), authority tests in `StatutoryPackGovernanceApiIntegrationTest` and `StatutoryPackApplicabilityIntegrationTest` |
| D97A-2/3 | V51 `statutory_pack_governance_guard`, `StatutoryPackGovernanceService`, `StatutoryPackGovernanceDatabaseIntegrationTest` (database guard), `StatutoryPackGovernanceApiIntegrationTest` (API lifecycle, four-eyes, concurrency, idempotency) |
| D97A-4 | V51 evidence columns and `statutory_pack_evidence_complete` |
| D97A-5 | `StatutoryPackValidator`, `StatutoryPackValidatorTest` |
| D97A-6 | `JdbcStatutoryRulePackRepository.applicableActivePackId`, `StatutoryEvaluator.resolve`, `StatutoryCalculationService.requireCommittable`, `StatutoryPackApplicabilityIntegrationTest`, `StatutoryApiIntegrationTest` (`STATUTORY_PACK_NO_ACTIVE_VERSION`) |
| D97A-7 | `StatutoryPackGovernanceDatabaseIntegrationTest` and `StatutoryPackGovernanceApiIntegrationTest` (seeded `CD-SALARY-PREVIEW` stays DRAFT, no evidence, no cases), web DRAFT notice |
| D97A-8 | `docs/API-SPEC.yaml`, `ApiContractDriftTest`, regenerated `core-api.ts` |
| D97A-9 | `ErrorCode`, `problem.schema.json`, locale files |
| D97A-10 | `V51__statutory_pack_governance.sql`, `V51__rollback.sql`, `StatutoryPackMigrationRollbackIntegrationTest`, `StatutoryPackLegacyMigrationIntegrationTest` (R200-1) |
| D97A-11 | `StatutoryPackRecorder` allow-list, privacy test in `StatutoryPackGovernanceApiIntegrationTest`, `JdbcStatutoryPackGovernanceRepositoryScopeTest`, `TenantPredicateArchitectureTest` |
| D97A-12/14 | `apps/web/src/features/statutoryPacks`, `statutory-pack-governance.spec.ts` (two journeys) |
| D97A-13 | `docs/BACKLOG.md`, `scripts/ops/backlog.test.mjs` |

## 14. Not included

Activation of the real DRC pack, any legal content, filing and declarations, real bank or mobile-money connectivity,
settlement, paid status, loans and salary advances (MVP-097B and later).
