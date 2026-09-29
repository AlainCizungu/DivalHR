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

## Critical relationships

- A Person may have more than one Employment record over time.
- An Employment belongs to one LegalEntity and may have multiple effective-dated OrganizationalAssignments.
- Attendance and timesheet records reference Employment, not only Person.
- A PayInput references its source record and approval status.
- Financial DataDisclosure references a specific Consent, partner, purpose, field set, and expiration.
- AI outputs retain references to the authorized records or governed metrics used.
