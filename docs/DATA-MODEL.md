# DivalHR Data Model

## Modeling rules

- Use UUIDs for externally visible identifiers.
- Every tenant-owned record contains an immutable tenant ID.
- Effective-dated changes preserve history.
- Store timestamps in UTC and retain the business timezone used for interpretation.
- Store monetary amount and ISO currency together.
- Store translation keys or localized values explicitly; never use translated labels as identifiers.
- Soft deletion is not a substitute for retention and deletion policy.
- Sensitive data classifications and retention rules are defined per entity.

## Core entities

### Tenant and organization

- Organization
- CountryConfiguration
- LegalEntity
- Region
- Site
- Department
- CostCenter
- Team
- PublicHolidayCalendar
- FeatureFlag

### Identity and access

- User
- IdentityProvider
- Role
- Permission
- RoleAssignment
- AccessPolicy
- ServiceClient
- Session
- AuditEvent

### People

- Person
- Employee
- Employment
- OrganizationalAssignment
- ManagerRelationship
- Contract
- CompensationBasis
- EmployeeDocument
- LeavePolicy
- LeaveBalance
- LeaveRequest
- LifecycleEvent

### Operations

- ShiftTemplate
- Shift
- Rotation
- AttendanceEvent
- AttendanceCorrection
- Timesheet
- TimesheetEntry
- Project
- Grant
- Task
- Expense
- ExpenseItem
- Allowance
- Approval
- ApprovalStep

### Payroll

- PayGroup
- PayPeriod
- EarningCode
- DeductionCode
- PayInput
- PayrollRun
- PayrollResult
- Payslip
- ExchangeRate
- PayrollExport

### Integrations

- Connector
- ConnectorCredentialReference
- ExternalIdentifier
- WebhookSubscription
- WebhookDelivery
- ImportJob
- ExportJob

### AI and analytics

- MetricDefinition
- ReportDefinition
- AIConversation
- AIRequest
- AIRetrievalReference
- AIAction
- AIFeedback
- AIEvaluationResult

### Finance

- FinanceProgram
- Consent
- DataDisclosure
- EligibilityRequest
- LoanApplicationReference
- PartnerOffer
- DisbursementReference
- RepaymentStatus
- Complaint

## Implemented (MVP-001)

- `tenant.organization` and `tenant.organization_currency`: the organization's `id` **is** the tenant identifier; there is no separate `tenant_id` column on the tenant root.
- `platform.idempotency_record`: `(operation, principal, idempotency_key)`; a deferred trigger rejects any commit that leaves a record without its response.
- `platform.audit_event`: append-only.
- `platform.outbox_event`: stores the exact envelope to publish; checks keep `eventId`, `eventType` and `tenantId` consistent with indexed columns and require every envelope field, with `causationId` explicitly nullable.
- Supported countries, locales, time zones and currencies are defined in code (`SupportedConfiguration`) and tested against `docs/API-SPEC.yaml`, so runtime configuration cannot widen them.

## Implemented (MVP-002, first increment: legal entities and sites)

- `tenant.legal_entity`: `tenant_id` references `tenant.organization(id)`; `UNIQUE (tenant_id, id)` makes `(tenant_id, id)` a composite key for children. Codes are stored normalized (trimmed, upper-case, `^[A-Z0-9_-]{2,20}$`) and are unique per tenant regardless of case (`legal_entity_code_ci_unique` on `(tenant_id, upper(code))`). Names are trimmed, 2-160 characters, without control characters.
- `tenant.site`: belongs to exactly one legal entity of the **same** tenant through the composite foreign key `site_legal_entity_same_tenant (tenant_id, legal_entity_id) → legal_entity (tenant_id, id)`; a row can never point at another tenant's legal entity. Site codes are unique per tenant regardless of case (`site_code_ci_unique`).
- Effective dates are inclusive business dates (`date`, no time zone) between 1900-01-01 and 2999-12-31; `effective_to IS NULL` means open-ended.
- Containment is enforced twice: the service checks the site period against the parent it has locked `FOR SHARE`, and the `site_period_within_legal_entity` trigger rejects any insert or update (including direct SQL) whose period is outside its legal entity's; `legal_entity_period_covers_sites` rejects narrowing a legal entity below its sites. An open-ended site requires an open-ended legal entity.
- Ownership is immutable: triggers reject changing `tenant_id` (both tables) or `legal_entity_id` (sites).
- Listing uses keyset pagination on `(code COLLATE "C", id)` with matching indexes; order is byte order, stable across locales.
- V3 widened the operation and audit-action name checks to allow hyphenated resource names (`legal-entity.create`). The widening is forward-only: `db/rollback/V3__rollback.sql` removes the hierarchy tables but deliberately does not narrow the checks back, and rolling back business data is unsupported once real records exist.
- Departments and cost centers followed in Increment 2, regions in Increment 3A and teams in Increment 3B.

## Implemented (MVP-002, Increment 2: departments and cost centers)

- `tenant.department` and `tenant.cost_center` (V5) have the same shape: `id`, `tenant_id` (references `tenant.organization`), `site_id`, normalized `code`, trimmed `name`, inclusive `effective_from`/`effective_to` business dates (1900-2999), `created_at`, `created_by`, `version`.
- `(tenant_id, site_id)` references `tenant.site (tenant_id, id)` (`department_site_same_tenant`, `cost_center_site_same_tenant`), so a department or cost center can never belong to another tenant's site, even through direct SQL.
- Codes are unique per tenant **per type**, regardless of case (`department_code_ci_unique`, `cost_center_code_ci_unique`): a department and a cost center may share a code.
- Containment is enforced twice: the service locks the site `FOR SHARE` and checks the period, and `tenant.site_unit_period_within_site()` rejects any insert or update outside the site's period (`department_period_within_site`, `cost_center_period_within_site`). An open-ended child requires an open-ended site.
- `tenant.site_period_covers_units()` stops a site update from stranding its departments or cost centers (`site_period_covers_units`).
- Ownership and parent are immutable: triggers reject changing `id`, `tenant_id` or `site_id`.
- Listing uses keyset indexes `(tenant_id, site_id, code COLLATE "C", id)` matching the query order exactly.
- Idempotency, audit and outbox reuse the platform tables; no entity-specific copies exist.
- Manual rollback: `db/rollback/V5__rollback.sql` (drops the tables, triggers and functions; business-data rollback is unsupported once records exist).

## Implemented (MVP-002, Increment 3A: regions and optional site assignment)

- Hierarchy: Organization → Legal entity → [Region] → Site → Department or Cost center. The region layer is **optional**.
- `tenant.region` (V6): `id`, `tenant_id` (references `tenant.organization`), `legal_entity_id`, normalized `code`, trimmed `name`, inclusive `effective_from`/`effective_to` (1900-2999), `created_at`, `created_by`, `version`. `(tenant_id, legal_entity_id)` references `tenant.legal_entity (tenant_id, id)` (`region_legal_entity_same_tenant`).
- Region codes are unique per tenant across legal entities, regardless of case (`region_code_ci_unique`); they may equal the code of another resource type.
- `tenant.site.region_id` is nullable, with no default and no backfill: every site created before V6 stays valid with `region_id IS NULL`, and no default region is ever created. `site.legal_entity_id` stays stored explicitly; it is never inferred through the region.
- `(tenant_id, legal_entity_id, region_id)` references `tenant.region (tenant_id, legal_entity_id, id)` with `MATCH SIMPLE` (`site_region_same_tenant_and_legal_entity`): a NULL region is not checked, and a non-NULL region must belong to the site's own tenant **and** legal entity, even through direct SQL.
- Containment chain, enforced by the services under parent-before-child locks and independently by triggers: legal entity ⊇ region (`region_period_within_legal_entity`), region ⊇ assigned site (`site_period_within_region`), legal entity ⊇ every site (V3), site ⊇ departments and cost centers (V5). Backstops stop narrowing a legal entity below its regions (`legal_entity_period_covers_regions`) or a region below its assigned sites (`region_period_covers_sites`).
- First assignment only: `site_region_assigned_once` allows `NULL → region` and a no-op rewrite of the same region, and rejects changing or clearing an assigned region. Region `id`, `tenant_id` and `legal_entity_id` are immutable.
- Lock order (all paths): legal entity → region → site → department/cost center. `site.region.assign` reads the site without a lock, locks the region `FOR SHARE`, then the site `FOR UPDATE`.
- Listing uses `region_tenant_legal_entity_code_id (tenant_id, legal_entity_id, code COLLATE "C", id)`; `site_tenant_region (tenant_id, region_id) WHERE region_id IS NOT NULL` serves the backstop and foreign-key checks.
- Events: `tenant.region-created.v1` and `tenant.site-region-assigned.v1` (ids only). `tenant.site-created.v1` gains an **optional, additive** `data.regionId`, present only for a site created with a region; existing v1 consumers and the shared envelope schema are unaffected (validated with and without it).
- Manual rollback: `db/rollback/V6__rollback.sql` drops the region layer (every region and assignment is lost) and purges `region.create` / `site.region.assign` idempotency records. Rolling back only the application is preferred: the previous version works unchanged against V6.

## Implemented (MVP-002, Increment 3B: teams)

- Hierarchy: … → Site → Department or Cost center → Team. A team has **exactly one** parent: a department or a cost center of one site, never both and never neither.
- `tenant.team` (V7): `id`, `tenant_id`, `site_id`, `department_id` (nullable), `cost_center_id` (nullable), normalized `code`, trimmed `name`, inclusive `effective_from`/`effective_to` (1900-2999), `created_at`, `created_by`, `version`.
- `team_exactly_one_parent CHECK (num_nonnulls(department_id, cost_center_id) = 1)` is the authoritative cardinality rule. The parent foreign keys use `MATCH SIMPLE`, so the NULL side is unchecked and the non-NULL side is fully checked.
- `site_id` is **derived** from the locked parent row by the service and never accepted from a request; it is persisted and immutable. V7 adds `UNIQUE (tenant_id, site_id, id)` to `tenant.department` and `tenant.cost_center` (`department_tenant_site_id_unique`, `cost_center_tenant_site_id_unique`) so that `(tenant_id, site_id, department_id)` and `(tenant_id, site_id, cost_center_id)` can reference them (`team_department_same_tenant_and_site`, `team_cost_center_same_tenant_and_site`). The redundant `(tenant_id, site_id)` reference to `tenant.site` (`team_site_same_tenant`) is kept. A team can therefore never point at another tenant's parent or at a parent of another site, even through direct SQL.
- Team codes are unique per tenant across all parents and both parent types, regardless of case (`team_code_ci_unique`); they may equal the code of another resource type.
- Containment is enforced twice: the service locks the parent `FOR SHARE` and checks the period, and `tenant.team_period_within_parent()` rejects any insert or update outside the parent's period (`team_period_within_department`, `team_period_within_cost_center`). The trigger returns early when the parent cardinality is invalid, so `team_exactly_one_parent` is the reported violation for both invalid shapes. An open-ended team requires an open-ended parent.
- Backstops stop narrowing a department or cost center below its teams (`department_period_covers_teams`, `cost_center_period_covers_teams`, one fixed-SQL branch per table in `tenant.site_unit_period_covers_teams()`).
- `id`, `tenant_id`, `site_id`, `department_id` and `cost_center_id` are immutable (`team_ownership_immutable`, `team_parent_immutable`).
- Lock order (all paths): legal entity → region → site → department/cost center → team.
- The domain names the parent with `TeamParentKind { DEPARTMENT, COST_CENTER }`; each kind maps through a closed `switch` to fixed SQL for its table and column. No request value is ever interpolated into SQL.
- Listing filters by exactly one parent and uses the partial keyset indexes `team_tenant_department_code_id` / `team_tenant_cost_center_code_id` `(tenant_id, <parent>_id, code COLLATE "C", id) WHERE <parent>_id IS NOT NULL`. `team_tenant_site_code_id` supports site-level queries.
- Event: `tenant.team-created.v1` with ids, parent type, the selected parent id, code and dates (an open-ended `effectiveTo` is omitted); never the name.
- Manual rollback: `db/rollback/V7__rollback.sql` drops the team table, its triggers and functions and the two composite unique constraints, and purges `team.create` idempotency records (every team is lost). Rolling back only the application is preferred: the previous version works unchanged against V7 (tested with its exact statements).

## Implemented (MVP-010: invitations and tenant memberships)

- New module `identity` with its own schema. It never reads another module's tables; organization name and locale come through the `OrganizationDirectory` port implemented by the tenant module.
- `identity.invitation` (V8): `id`, `tenant_id` (foreign key `invitation_tenant_fk` to `tenant.organization`), normalized `email` (confidential), `email_lookup` (HMAC-SHA256 of the normalized address under `DIVALHR_EMAIL_LOOKUP_KEY`), `role` (`tenant-admin` or `employee` only, `invitation_role_assignable`; `tenant_membership_role_assignable` on memberships), `locale` (`fr`/`en`), `state` (`PENDING`, `ACCEPTING`, `ACCEPTED`, `EXPIRED`, `REVOKED`), `token_sha256` (SHA-256 of the current link token; the token itself is never stored), `token_issued_at`, `expires_at`, `issue_count` (1-4: the first link plus at most 3 reissues), `delivery_state` (`QUEUED`, `SENT`, `FAILED`) and `delivery_updated_at` for the newest issuance, acceptance lease (`lease_owner`, `lease_until`), `credential_setup_state` (`PENDING`, `SENT`, `FAILED`) with `credential_setup_attempts` and `credential_setup_next_at`, actor and timestamps, `version`.
- Normalized addresses: trimmed, Unicode NFC, lower-cased, domain in ASCII (IDNA); the local part must be ASCII (SMTPUTF8 is not supported yet). Comparisons use `email_lookup`, so indexes never contain the plain address.
- `invitation_one_open_per_email`: one `PENDING` or `ACCEPTING` invitation per `(tenant_id, email_lookup)`. Other tenants are independent.
- `invitation_state_consistent` fixes, per state, which columns must be set or empty (for example `ACCEPTED` requires `accepted_at` and an erased token; `ACCEPTING` requires a lease). `invitation_transition_allowed` allows only `PENDING → ACCEPTING | EXPIRED | REVOKED`, `ACCEPTING → ACCEPTED | PENDING | EXPIRED`, keeps `id`, `tenant_id`, `email`, `email_lookup`, `role` and `locale` immutable, and requires a new token and a `QUEUED` delivery for every reissue. Terminal rows accept only delivery, credential-setup and version changes.
- Delivery results are written conditionally on `(id, issue_count)`: a late result for an older link never overwrites the newest issuance. A delivery still `QUEUED` after `delivery-stale-after` (10 minutes) becomes `FAILED` and is never retried automatically; the administrator resends, which issues a new link. An older message may have been delivered, but only the newest link is valid.
- `identity.tenant_membership` (V8): `id`, `tenant_id` (foreign key to `tenant.organization`), identity-provider `subject` (`UNIQUE`: one tenant per identity), `role`, `email_lookup`, `source_invitation_id`, `created_at`. Rows are immutable (`tenant_membership_immutable`).
- Tenant-aware composite keys (A1): the membership references `(tenant_id, source_invitation_id, role, email_lookup)` of its invitation (`ON DELETE SET NULL (source_invitation_id)`, so retention keeps the membership), and the invitation references `(tenant_id, id, source_invitation_id)` of its membership through the deferred `invitation_membership_same_tenant_and_source`. A membership can never point at another tenant's invitation or disagree with it on role or address, even through direct SQL.
- Acceptance is split in two short transactions around the identity-provider call: T1 takes a lease (`ACCEPTING`, random `lease_owner`, `lease_until` = now + 2 minutes); provisioning runs outside any transaction; T2 completes only if the row is still `ACCEPTING` with the same `lease_owner`, so a worker whose lease expired and was taken over can never commit.
- Jobs (`InvitationJobScheduler`, every minute or five minutes; `FOR UPDATE SKIP LOCKED` or conditional updates, so several instances are safe): expire due invitations (with audit and outbox), fail stale deliveries, recover stale acceptances, retry credential setup (every 5 minutes, at most 5 attempts, then `FAILED`) and delete terminal invitations after the 90-day retention.
- Audit actions `invitation.create`, `invitation.resend`, `invitation.revoke`, `invitation.accept`, `invitation.expire`; events `identity.invitation-*.v1`. Audit metadata and events carry ids, role, locale and states, never the address, token or subject.
- Manual rollback: `db/rollback/V8__rollback.sql` drops both tables with their triggers and functions and purges `invitation.*` idempotency records (every invitation and membership is lost; identities already created in Keycloak stay and must be removed there). Rolling back only the application is preferred: the previous version works unchanged against V8.

## Implemented (MVP-014: first tenant administrator)

- `identity.invitation.origin` (V9): `TENANT_ADMIN` or `PLATFORM_BOOTSTRAP` (`invitation_origin_valid`), `NOT NULL DEFAULT 'TENANT_ADMIN'`, so existing rows and the previous application's inserts become `TENANT_ADMIN`. Origin is immutable (the transition trigger now also guards it). `invitation_bootstrap_is_tenant_admin`: a bootstrap invitation always has role `tenant-admin`. `invitation_superseded_is_bootstrap`: only a bootstrap invitation may carry the system marker `revoked_by = 'system:bootstrap-superseded'`, and the transition function allows `ACCEPTING → REVOKED` only for that case.
- Partial unique index `invitation_one_open_bootstrap (tenant_id) WHERE origin = 'PLATFORM_BOOTSTRAP' AND state IN ('PENDING','ACCEPTING')`: at most one open bootstrap per organization, verified under concurrent connections. Supporting index `invitation_open_tenant_admin` for the open tenant-administrator check. No development data.
- Manual rollback: `db/rollback/V9__rollback.sql` refuses while an open bootstrap invitation exists, purges `tenant-admin-bootstrap.*` idempotency records, rewrites the supersession marker (so V8's constraints accept the rows), drops the column, constraints and indexes and restores the V8 transition function exactly (verified by function, constraint, index and column signature). Rolling back only the application is preferred: the previous version works unchanged against V9. MVP-012A uses V10.

## Implemented (MVP-012A: membership authority)

- `identity.tenant_membership.email` (V10): confidential normalized address, format-checked like the invitation's. Existing rows are backfilled only from their own `source_invitation_id` (same tenant, role and `email_lookup`); rows whose invitation was already purged stay `NULL`.
- Insert invariant `tenant_membership_email_from_source` (A12A-2): a membership inserted with a source invitation ends its insert with exactly that invitation's address, tenant, role and lookup. A writer that omits the address (the previous application version during a rolling deployment) gets it copied from the locked source row; any other value, a foreign source or an unanchored address (no source) is rejected. The application writes address and lookup from one validated `EmailAddress`.
- The address is immutable (`tenant_membership_immutable` now covers it); retention may still clear `source_invitation_id` and keep the address. `NULL` remains only for historical rows and development seed rows.
- No development data in V10. Manual rollback: `db/rollback/V10__rollback.sql` restores V9 exactly (function, constraints, triggers, indexes and columns, verified by signature) and keeps every membership; only stored addresses are lost. Rolling back only the application is preferred and is a documented security downgrade (`SECURITY.md`).

## Implemented (MVP-012B: access review)

- V11 adds two keyset indexes on `identity.tenant_membership`: `tenant_membership_review_order (tenant_id, created_at DESC, id DESC)` and `tenant_membership_review_role (tenant_id, role, created_at DESC, id DESC)`. Address lookups use the existing `UNIQUE (tenant_id, email_lookup)`. No column, row or development data change.
- Each successful review writes one `platform.audit_event` (`access-review.read`, digest v1 in `after_state_sha256`).
- Manual rollback: `db/rollback/V11__rollback.sql` drops the two indexes and restores V10 exactly; no data is touched. Rolling back only the application is preferred.

## Implemented (MVP-013: authorization denial audit)

- V12 adds `platform.authorization_denial`: `id`, `occurred_at`, `actor_subject` (the verified JWT `sub`, unchanged; `text`, non-empty, no length limit), `action` (always `authorization.denied`), `operation` (operation-name grammar), `scope` (`platform`, `tenant`), `stage` (`role`, `tenant_context`, `mfa`, `membership`, `rate_limit`, `method_security`), `tenant_id` (nullable) and `correlation_id` (`^[A-Za-z0-9._-]{8,64}$`). No metadata column.
- Checks: platform rows carry only `role`, `mfa` or `method_security`; `tenant_id` is present exactly for tenant-scoped `rate_limit` and `method_security` rows (the effective tenant after the membership gate) and never for platform rows.
- Indexes: `(occurred_at)`, `(actor_subject, occurred_at)`, and `(tenant_id, occurred_at) WHERE tenant_id IS NOT NULL`. Append-only triggers reject `UPDATE`, `DELETE` and `TRUNCATE`.
- Classification: security evidence. `actor_subject` is a pseudonymous identity-provider identifier; no names, addresses, claims, request data or targets are stored. No retention period is approved yet: rows are kept and never deleted automatically.
- Writes go through a dedicated two-connection pool with its own transaction manager (`DenialAuditStore`), independent of business transactions.
- Manual rollback: `db/rollback/V12__rollback.sql` restores V11 exactly, but refuses while any row exists (no override). Destroying evidence needs the operator procedure in SECURITY.md.

## Implemented (MVP-020: employee import)

- V13 creates the `people` schema with four tables. Unit IDs in `people.employment` carry no foreign keys: the people module never reads the tenant module's tables, and placements are resolved through the `OrganizationPlacementDirectory` port. Constraint: hierarchy units cannot be deleted or re-parented today (MVP-002), so these references cannot dangle; any future story that deletes or moves units must first consult `people` through an event or port.
- `people.employee`: `id`, `tenant_id` (FK to `tenant.organization`), `employee_number` (`^[A-Z0-9][A-Z0-9._/-]{0,31}$`, unique per tenant), `given_names` and `family_name` (NFC-normalized, 1-100 characters, trimmed, no control characters, no double space, never starting with `=`, `+`, `-` or `@`), `created_at`, `created_by`, `version`.
- `people.employment`: `id`, `tenant_id`, `employee_id` (same-tenant FK), `legal_entity_id`, `site_id`, optional `department_id` or `cost_center_id` (at most one), optional `team_id` (only with a parent), `effective_from`, `effective_to` (open), `created_at`, `created_by`, `version`. One employment per imported employee; history changes belong to MVP-021.
- `people.employee_import`: status `VALIDATED`, `COMMITTED`, `DISCARDED` or `EXPIRED`; `expires_at` (2 hours after creation), `file_sha256`, `preview_digest`, `delimiter`, `header_language`, the row counts, `created_count`, `committed_at` and `committed_by` (set exactly when committed), and `closed_at` (null exactly while open).
- `people.employee_import_row`: primary key `(tenant_id, import_id, row_number)`, cascading from its import; `status` (`VALID`, `INVALID`, `CREATED`, `NOT_IMPORTED`), `error_columns` and `error_codes` (paired arrays from closed sets; required for invalid rows and absent otherwise), and the staged normalized values, which exist only for valid rows of an open import.
- Classification: names and staged values are Restricted HR; employee numbers, start dates and placements are Confidential. Import records and row results (numbers, statuses, codes) are Internal. Employee IDs are personal-data references (A20-2): they appear only in audit and outbox records, never in import results.
- Lifecycle: staged values are erased at commit, discard or expiry. Closed imports and their rows are deleted 30 days after closing by default (operational policy, 1-90 days); employees, employments, audit and outbox records are never deleted by this job.
- Manual rollback: `db/rollback/V13__rollback.sql` locks the four tables and restores V12 exactly, but refuses while any row exists.

## Implemented (Issue #17 maintenance)

- V4 restores the approved strict operation-name grammar on `idempotency_operation_format` and `audit_action_format`: `^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$`. A pre-flight counts non-conforming rows and aborts the (single-transaction) migration without rewriting or revealing data. V1-V3 are unchanged; `db/rollback/V4__rollback.sql` restores the V3 superset (non-destructive).

## Critical relationships

- A Person may have more than one Employment record over time.
- An Employment belongs to one LegalEntity and may have multiple effective-dated OrganizationalAssignments.
- Attendance and timesheet records reference Employment, not only Person.
- A PayInput references its source record and approval status.
- Financial DataDisclosure references a specific Consent, partner, purpose, field set, and expiration.
- AI outputs retain references to the authorized records or governed metrics used.
