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
- Regions, departments, cost centers and teams remain future increments of MVP-002.

## Critical relationships

- A Person may have more than one Employment record over time.
- An Employment belongs to one LegalEntity and may have multiple effective-dated OrganizationalAssignments.
- Attendance and timesheet records reference Employment, not only Person.
- A PayInput references its source record and approval status.
- Financial DataDisclosure references a specific Consent, partner, purpose, field set, and expiration.
- AI outputs retain references to the authorized records or governed metrics used.
