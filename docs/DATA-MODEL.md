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

## Implemented (Issue #17 maintenance)

- V4 restores the approved strict operation-name grammar on `idempotency_operation_format` and `audit_action_format`: `^[a-z]+(-[a-z]+)*(\.[a-z]+(-[a-z]+)*)+$`. A pre-flight counts non-conforming rows and aborts the (single-transaction) migration without rewriting or revealing data. V1-V3 are unchanged; `db/rollback/V4__rollback.sql` restores the V3 superset (non-destructive).

## Critical relationships

- A Person may have more than one Employment record over time.
- An Employment belongs to one LegalEntity and may have multiple effective-dated OrganizationalAssignments.
- Attendance and timesheet records reference Employment, not only Person.
- A PayInput references its source record and approval status.
- Financial DataDisclosure references a specific Consent, partner, purpose, field set, and expiration.
- AI outputs retain references to the authorized records or governed metrics used.
