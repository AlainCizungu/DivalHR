# DivalHR MVP Backlog

## Definition of Ready

A story is ready when it has a business owner, French and English copy, acceptance criteria, authorization rules, data classification, API or UI contract, analytics events, error behavior, and test examples.

## Definition of Done

- Acceptance criteria pass.
- French and English experiences are complete.
- Authorization and tenant-isolation tests pass.
- Audit events exist for sensitive actions.
- Accessibility requirements pass.
- API documentation is updated.
- Unit, integration, and end-to-end tests pass.
- Observability and operational documentation are updated.
- No unresolved high-severity security issue remains.

## Epic 1 Tenant Foundation

### MVP-001 Create an organization

As a platform administrator, I can create an organization with a default country, language, timezone, and currencies.

Acceptance criteria:

- French or English may be the default language.
- DRC supports CDF and USD.
- The tenant receives a unique immutable identifier.
- A tenant-created audit event is recorded.
- Users from another tenant cannot read the organization.

### MVP-002 Configure organizational hierarchy

As an administrator, I can create legal entities, regions, sites, departments, cost centers, and teams with effective dates.

MVP-002 is delivered incrementally:

- **Increment 1 (Issue #12): legal entities and sites. Complete.** Tenant administrators create and list (keyset-paginated) legal entities and the sites beneath them, with effective dates, case-insensitive unique codes and site periods contained in their legal entity's period. French and English UI at `/admin/hierarchy`.
- **Increment 2 (Issue #19): departments and cost centers. Complete.** Tenant administrators create and list departments and cost centers beneath a site, with effective dates contained in the site's period and codes unique per tenant for each type. The `/admin/hierarchy` page adds a site selection step.
- **Increment 3A (Issue #21): regions and optional site assignment.** Tenant administrators create and list regions beneath a legal entity (codes unique per tenant, periods within the legal entity's), create sites with an optional region, and give an existing site without a region its first region. Regions are optional: sites created before this increment stay valid and unassigned, and every site stays directly reachable.
- **Increment 3B: teams.** Future.
- Future increments (each a separate, approved story): editing, closing and moving hierarchy records, including changing or clearing a site's region; hierarchy views for other roles.

Out of scope for increments 1 to 3A: teams, nested regions, moving a region or a site, changing or clearing a site's region, allocations, budgets, employee assignments, managers, editing, ending, deletion, bulk import, per-unit or region-specific permissions, platform impersonation, external event publication and AI features.

## Epic 2 Identity and Access

### MVP-010 Invite a user

As an administrator, I can invite a user and assign only scoped roles.

### MVP-011 Enforce privileged MFA

Privileged roles must complete multifactor authentication.

### MVP-012 Review access

Authorized security administrators can review active access by user, role, site, and legal entity.

### MVP-013 Durably audit privileged authorization denials

Denied attempts at platform-scoped operations are recorded as append-only audit events (actor subject, operation, result `DENIED`, correlation ID; no request content), in addition to the structured security log and metric introduced by MVP-001. Follow-up from the MVP-001 architect review (#10).

## Epic 3 Employee Core

### MVP-020 Import employees

HR can import a validated CSV, preview errors, and commit valid records idempotently.

### MVP-021 Manage employment history

HR can create effective-dated employment, manager, site, department, contract, and compensation-basis changes without destroying history.

### MVP-022 Separate an employee

HR can complete an approved separation that revokes access and creates asset and document follow-up tasks.

## Epic 4 Contracts and Documents

### MVP-030 Create and acknowledge a contract

HR can create a contract from an approved template in French or English and record employee acknowledgment.

### MVP-031 Track document expiration

Authorized users receive alerts for expiring contracts, credentials, and identity records.

## Epic 5 Leave and Approvals

### MVP-040 Configure leave policy

HR can configure eligibility, balance behavior, approval routing, and payroll effect.

### MVP-041 Request and approve leave

Employees can submit leave; managers can approve or reject with an auditable reason.

## Epic 6 Scheduling

### MVP-050 Publish a schedule

Operations can assign shifts, rotations, sites, and supervisors and detect uncovered positions.

### MVP-051 Process substitutions

A manager can approve a shift substitution without losing the original schedule history.

## Epic 7 Attendance and Offline Sync

### MVP-060 Record attendance

Employees or authorized supervisors can record attendance using an approved method.

### MVP-061 Synchronize offline attendance

Queued events synchronize idempotently and preserve device event time and server receipt time.

### MVP-062 Correct attendance

A correction requires authorization, reason, before-and-after values, and audit history.

## Epic 8 Timesheets and Costing

### MVP-070 Approve time

Supervisors can approve hours by project, grant, site, task, or cost center.

### MVP-071 Lock a period

Approved periods can be locked; reopening requires privileged approval and audit.

## Epic 9 Tasks and Expenses

### MVP-080 Assign and complete a task

A worker can receive, update, and complete a task with optional evidence.

### MVP-081 Submit an expense

A worker can submit CDF or USD expenses, attach a receipt, and follow approval status.

## Epic 10 Payroll Preparation

### MVP-090 Generate pay inputs

Approved attendance, time, leave, allowances, and deductions produce traceable payroll inputs.

### MVP-091 Review payroll variance

Payroll officers can compare the current period with the prior period and investigate material differences.

### MVP-092 Export payroll inputs

Authorized users can export a locked, versioned payroll-input file.

## Epic 11 Dashboards and AI

### MVP-100 View operational status

Authorized leaders see scheduled workers, presence, lateness, absence, uncovered shifts, and overdue approvals within their scope.

### MVP-101 Generate grounded daily summary

Dival AI produces a French or English summary using governed metrics and links to authorized source views.

## Epic 12 Integrations

### MVP-110 Manage webhooks

Authorized administrators can create signed webhooks, inspect delivery, rotate secrets, and disable failing endpoints.

### MVP-111 Deliver one messaging connector

The first design partner selects WhatsApp Business, Microsoft Teams, or Slack. Sensitive details require an authenticated DivalHR deep link.
