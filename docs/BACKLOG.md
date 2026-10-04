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
- **Increment 3A (Issue #21): regions and optional site assignment. Complete.** Tenant administrators create and list regions beneath a legal entity (codes unique per tenant, periods within the legal entity's), create sites with an optional region, and give an existing site without a region its first region. Regions are optional: sites created before this increment stay valid and unassigned, and every site stays directly reachable.
- **Increment 3B (Issue #23): teams. Complete.** Tenant administrators create and list (keyset-paginated) teams beneath exactly one department or exactly one cost center of a site. The team's site is derived from its parent; team periods lie within the parent's period; team codes are unique per tenant across all parents. The `/admin/hierarchy` page adds a team-parent selection beneath the department and cost-center lists.
- Future increments (each a separate, approved story): editing, closing and moving hierarchy records, including changing or clearing a site's region or a team's parent; assigning workers to teams; hierarchy views for other roles.

Out of scope for increments 1 to 3B: teams beneath both a department and a cost center, nested teams, nested regions, moving a region or a site, changing or clearing a site's region, changing a team's parent, allocations, budgets, employee assignments, managers, editing, ending, deletion, bulk import, per-unit or region-specific permissions, platform impersonation, external event publication and AI features.

## Epic 2 Identity and Access

### MVP-010 Invite a user

As an administrator, I can invite a user and assign only scoped roles.

**Issue #25. Complete.** Tenant administrators invite a person by email with exactly one tenant role (`employee` or `tenant-admin`), list invitations (newest first, filterable by status), revoke pending invitations and resend them with a new link (at most 3 times). The invitee opens the link, sees the role and expiry, and accepts anonymously; the Core API then creates their identity in Keycloak with the tenant and role, records a tenant membership and Keycloak emails a link to choose a password. French and English UI at `/admin/users` and `/invitation`; French and English email templates. Architecture amendments A1-A4 and guardrails 1-7 of the review on Issue #25 apply.

**Hardening (Issue #31). Complete.** The Core provisioner client no longer holds any Keycloak admin permission. Provisioning goes through the narrow `divalhr-provisioning` Keycloak extension (contract `packages/shared-contracts/openapi/keycloak-provisioning.yaml`, ADR 0006): three operations keyed by invitation ID, authorized by audience, client, live service account and the single `provision-invitations` capability before any input is read. Credential setup distinguishes a proven completed setup from an inconsistent one (`SETUP_STATE_INVALID`, failed and alerted, never recorded as sent); compensation deletes only an identity with no credential set up. `realm:verify` runs in final mode by default and fails if the provisioner holds any broad permission, directly or transitively. Architecture amendments A1-A3 and the guardrails of the review on Issue #31 apply.

Out of scope for MVP-010: editing or removing memberships and roles, deactivating users, site- or legal-entity-scoped roles (MVP-012), privileged MFA (MVP-011), bulk invitations, SMTPUTF8 addresses, SSO or SCIM provisioning, person or employee records, and invitations by platform administrators across tenants.

### MVP-011 Enforce privileged MFA

Privileged roles must complete multifactor authentication.

**Issue #29. Complete.** `platform-admin` and `tenant-admin` sign in with a password and a TOTP code from an authenticator app; employees with a password only. Keycloak's browser flow uses levels of authentication (password, then TOTP for privileged roles) and denies privileged users without an authenticator; enrollment happens only through an administrator-sent setup link, which invited tenant administrators receive for their password and authenticator together. The Core API requires the access token's `acr` to be exactly `urn:divalhr:loa:mfa` on every platform-scoped and tenant-admin operation (`403 MFA_REQUIRED` with an RFC 9470 challenge otherwise); the web app steps up once and otherwise shows a French or English MFA-required page. A read-only script verifies a realm's configuration. Architecture decisions D1-D11, amendments A1-A5 and guardrails 1-7 of the review on Issue #29 apply.

Out of scope for MVP-011: passkeys and passwordless sign-in, SMS or email codes, recovery codes, in-app or delegated factor reset, fresh step-up for sensitive operations, site- or department-specific MFA policy, adaptive risk, device trust or "remember this device", employee MFA, external identity-provider MFA, a custom Keycloak theme, and durable denial auditing (MVP-013).

### MVP-012 Review access

Authorized security administrators can review active access by user, role, site, and legal entity.

**Issue #37. Complete.** Delivered as two pull requests, 12A then 12B.

**12A membership authority: complete (PR #41).** Every tenant-scoped operation requires a matching active membership (token ∩ membership, exact role, after MFA and before binding); `/session` reports effective roles and supports tenantless platform administrators; the membership keeps its source invitation's address (V10, with a database invariant); development seed memberships; a read-only rollout preflight. Architecture decisions D1-D9, A1-A7, M1-M11, A12A-1 and A12A-2 on Issue #37 apply. 

**12B access review: complete (PR #42).** Read-only review for tenant administrators with exact MFA: paginated listing, exact-address lookup and role summary on the 12A active-membership predicate; legal-entity and site views show organization-wide access as inherited; HMAC-bound keyset cursors; 30 review requests per minute per subject; fail-closed `access-review.read` audit with canonical digest v1; French and English page at `/admin/access`. Architecture decisions R1-R8 and guardrails B1-B5 on Issue #37 apply. Follow-ups: S1 scoped roles, S2 grant and revoke, S3 membership lifecycle, a shared rate limiter for production scaling.

### MVP-014 Invite an organization's first administrator

A platform administrator invites the first tenant administrator of an active organization, who then invites everyone else.

**Issue #38. Complete.** Four platform-scoped operations on `/organizations/{organizationId}/tenant-admin-bootstrap` (status, create, revoke, resend), available only while the organization has no tenant administrator and no open tenant-administrator invitation. Every tenant-admin creation path takes one organization lock in one order; acceptance of a bootstrap invitation fails closed if another administrator appeared; idempotency covers the organization; a per-platform-administrator hourly limit; audit, invitation and outbox commit together before any email. Invitations carry an immutable `origin` (V9). French and English panel after organization creation and under "First administrator". Architecture decision and amendments A1-A8 on Issue #38 apply.

Out of scope for MVP-014: replacing or removing an existing tenant administrator, platform administrators inviting employees or other roles, listing a tenant's members or invitations from the platform side, organization search, and bulk bootstrap.

### MVP-013 Durably audit privileged authorization denials

Denied attempts at privileged operations by a verified identity are recorded as append-only evidence, in addition to the structured security log and metric introduced by MVP-001. Follow-up from the MVP-001 architect review (#10).

**Issue #43. In review.** Covers all 25 privileged operations (5 platform-scoped, 20 tenant-admin; D9). A dedicated typed append-only table `platform.authorization_denial` (V12, no free-form metadata) receives one row per eligible denial: role, tenant context, MFA, membership, the first per-subject rate-limit refusal of a window, and proven method-security drift. Only a verified non-blank JWT subject is an actor; anonymous, invalid-token and subjectless traffic stays log/metric-only. The tenant column holds only the effective tenant (after the membership gate), never a platform token's tenant. Rows are written in their own transaction on a two-connection bulkhead pool with its own transaction manager; a storage failure keeps the normal denial response and is signalled by metric, error log and alert. Per-actor and per-instance budgets bound writes; suppressed attempts are telemetry only. No retention schedule is approved: nothing is deleted. Public responses, FR/EN texts and the MFA challenge are unchanged; the stage-2 security log no longer names the actor. Architecture decisions D1-D11 and amendments A13-1 to A13-6 on Issue #43 apply.

Out of scope for MVP-013: an audit export or search UI, a SIEM, durable counts of suppressed attempts, a retention or archive job, auditing anonymous traffic, and post-binding denials (`TENANT_ACCESS_DENIED`, foreign-parent `404`s, validation errors).

## Epic 3 Employee Core

### MVP-020 Import employees

HR can import a validated CSV, preview errors, and commit valid records idempotently.

**Issue #45. In review.** Tenant administrators download a French or English template, upload a UTF-8 CSV (semicolon or comma, at most 2 MiB and 1,000 rows), review valid and invalid rows with stable error codes, and commit the valid rows in one all-or-nothing, idempotent transaction that re-validates against current employees and units, or cancel. V13 adds the `people` schema (`employee`, one `employment` per employee, `employee_import`, `employee_import_row`). Parsing uses Apache Commons CSV behind a people-module port; per-subject and per-tenant request limits, 3 open imports per tenant, explicit body, database and job timeouts; audit and outbox carry IDs and counts only; staged values are erased within 2 hours and import details deleted after 30 days. French and English page at "Importer des employés" / "Import employees". Approved proposal and amendments A20-1 to A20-6 on Issue #45 apply.

Out of scope for MVP-020: updating, merging or deleting employees, employment history changes (MVP-021) and separations (MVP-022); creating users, invitations, memberships or roles from rows; national IDs, bank accounts, salaries, contracts, documents, photos, dependants or health data; `.xlsx`, `.ods`, connectors, SFTP, scheduled or API-to-API imports; an employee directory or search UI; exporting imported data; a new HR role; and AI-assisted mapping or cleaning.

### MVP-021 Manage employment history

HR can create effective-dated employment, manager, site, department, contract, and compensation-basis changes without destroying history.

**Issue #47. In review.** Tenant administrators find employees (directory by employee number; search by employee-number prefix or name words, accents and case ignored), read an employee's profile on the business date (today in the organization's time zone), their effective-dated history and recorded changes, and record changes to four kinds of facts: placement (legal entity, site, department or cost center, team), manager, descriptive contract classification and compensation basis (never an amount). Every change is previewed (rows before and after, timing, warnings) and committed with the preview's employment version and digest; retroactive changes need a reason and an acknowledgement and stay within a configurable pilot window (60 days by default). Corrections replace one row's value for its own dates; active scheduled changes can be cancelled, restoring the replaced value up to the next change, which is never altered. Nothing is updated in place: V14 adds the append-only `people.employment_change` log and the transaction-time `people.employment_assignment` timeline, backfills each MVP-020 employment as a HIRE change, and lets PostgreSQL enforce non-overlap, gap-free placement, acyclic reporting lines and the exact shape of every write. Reads are audited disclosures; audit and outbox carry identifiers and counts only. French and English pages at "Employés" / "Employees". Approved proposal H1-H20 with amendments M21-1 to M21-5 on Issue #47 apply; ADR 0007.

Out of scope for MVP-021: separations and rehires (MVP-022); editing names or employee numbers; salaries, amounts, legal contract classification, documents and acknowledgements (MVP-030); approvals or workflows for changes; bulk or imported changes; manager self-service, org charts and reporting-line views; exports; deleting or purging history; and AI features.

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
