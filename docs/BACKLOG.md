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

## Status and test environment

Statuses below are reconciled against GitHub: "Complete" means the story's issue is closed as completed and its pull request is merged into `main`. As of 2026-10-07, the test environment `https://hr-dev.dival.ai` runs `main` at `194471e` (after PR #70), which contains every story marked complete here; it was deployed on 2026-10-06 after an exact-SHA host verification, passed the live acceptance suite and was accepted by the architect on 2026-10-07 (OPS-001, Issue #65). It holds synthetic data only.

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

**Issue #38. Complete (PR #39, merged 2026-10-01).** Four platform-scoped operations on `/organizations/{organizationId}/tenant-admin-bootstrap` (status, create, revoke, resend), available only while the organization has no tenant administrator and no open tenant-administrator invitation. Every tenant-admin creation path takes one organization lock in one order; acceptance of a bootstrap invitation fails closed if another administrator appeared; idempotency covers the organization; a per-platform-administrator hourly limit; audit, invitation and outbox commit together before any email. Invitations carry an immutable `origin` (V9). French and English panel after organization creation and under "First administrator". Architecture decision and amendments A1-A8 on Issue #38 apply.

Out of scope for MVP-014: replacing or removing an existing tenant administrator, platform administrators inviting employees or other roles, listing a tenant's members or invitations from the platform side, organization search, and bulk bootstrap.

### MVP-013 Durably audit privileged authorization denials

Denied attempts at privileged operations by a verified identity are recorded as append-only evidence, in addition to the structured security log and metric introduced by MVP-001. Follow-up from the MVP-001 architect review (#10).

**Issue #43. Complete (PR #44, merged 2026-10-03).** Covers all 25 privileged operations (5 platform-scoped, 20 tenant-admin; D9). A dedicated typed append-only table `platform.authorization_denial` (V12, no free-form metadata) receives one row per eligible denial: role, tenant context, MFA, membership, the first per-subject rate-limit refusal of a window, and proven method-security drift. Only a verified non-blank JWT subject is an actor; anonymous, invalid-token and subjectless traffic stays log/metric-only. The tenant column holds only the effective tenant (after the membership gate), never a platform token's tenant. Rows are written in their own transaction on a two-connection bulkhead pool with its own transaction manager; a storage failure keeps the normal denial response and is signalled by metric, error log and alert. Per-actor and per-instance budgets bound writes; suppressed attempts are telemetry only. No retention schedule is approved: nothing is deleted. Public responses, FR/EN texts and the MFA challenge are unchanged; the stage-2 security log no longer names the actor. Architecture decisions D1-D11 and amendments A13-1 to A13-6 on Issue #43 apply.

Out of scope for MVP-013: an audit export or search UI, a SIEM, durable counts of suppressed attempts, a retention or archive job, auditing anonymous traffic, and post-binding denials (`TENANT_ACCESS_DENIED`, foreign-parent `404`s, validation errors).

## Epic 3 Employee Core

### MVP-020 Import employees

HR can import a validated CSV, preview errors, and commit valid records idempotently.

**Issue #45. Complete (PR #46, merged 2026-10-03).** Tenant administrators download a French or English template, upload a UTF-8 CSV (semicolon or comma, at most 2 MiB and 1,000 rows), review valid and invalid rows with stable error codes, and commit the valid rows in one all-or-nothing, idempotent transaction that re-validates against current employees and units, or cancel. V13 adds the `people` schema (`employee`, one `employment` per employee, `employee_import`, `employee_import_row`). Parsing uses Apache Commons CSV behind a people-module port; per-subject and per-tenant request limits, 3 open imports per tenant, explicit body, database and job timeouts; audit and outbox carry IDs and counts only; staged values are erased within 2 hours and import details deleted after 30 days. French and English page at "Importer des employés" / "Import employees". Approved proposal and amendments A20-1 to A20-6 on Issue #45 apply.

Out of scope for MVP-020: updating, merging or deleting employees, employment history changes (MVP-021) and separations (MVP-022); creating users, invitations, memberships or roles from rows; national IDs, bank accounts, salaries, contracts, documents, photos, dependants or health data; `.xlsx`, `.ods`, connectors, SFTP, scheduled or API-to-API imports; an employee directory or search UI; exporting imported data; a new HR role; and AI-assisted mapping or cleaning.

### MVP-021 Manage employment history

HR can create effective-dated employment, manager, site, department, contract, and compensation-basis changes without destroying history.

**Issue #47. Complete (PR #48, merged 2026-10-04).** Tenant administrators find employees (directory by employee number; search by employee-number prefix or name words, accents and case ignored), read an employee's profile on the business date (today in the organization's time zone), their effective-dated history and recorded changes, and record changes to four kinds of facts: placement (legal entity, site, department or cost center, team), manager, descriptive contract classification and compensation basis (never an amount). Every change is previewed (rows before and after, timing, warnings) and committed with the preview's employment version and digest; retroactive changes need a reason and an acknowledgement and stay within a configurable pilot window (60 days by default). Corrections replace one row's value for its own dates; active scheduled changes can be cancelled, restoring the replaced value up to the next change, which is never altered. Nothing is updated in place: V14 adds the append-only `people.employment_change` log and the transaction-time `people.employment_assignment` timeline, backfills each MVP-020 employment as a HIRE change, and lets PostgreSQL enforce non-overlap, gap-free placement, acyclic reporting lines and the exact shape of every write. Reads are audited disclosures; audit and outbox carry identifiers and counts only. French and English pages at "Employés" / "Employees". Approved proposal H1-H20 with amendments M21-1 to M21-5 on Issue #47 apply; ADR 0007.

Out of scope for MVP-021: separations and rehires (MVP-022); editing names or employee numbers; salaries, amounts, legal contract classification, documents and acknowledgements (MVP-030); approvals or workflows for changes; bulk or imported changes; manager self-service, org charts and reporting-line views; exports; deleting or purging history; and AI features.

### MVP-022 Separate an employee

HR can complete an approved separation that revokes access and creates asset and document follow-up tasks.

**Issue #49. Complete (PR #50, merged 2026-10-04).** Tenant administrators link an employee record to the DivalHR access of an exact address, and separate an employee from an inclusive last day with a closed reason. The separation is previewed (the employee's rows, future changes that must be cancelled first, every affected direct-report interval, the access to revoke, the reminders and the required acknowledgements) and committed with the preview's version and digest. Direct reports are reassigned or left without a manager for every affected interval (at most 200). DivalHR access ends at the start of the day after the last day, or immediately when the last day is today or earlier, through a database-checked revocation the membership gate enforces at once; a job then disables the Keycloak identity through the provisioning extension, with retries, revalidation of the binding before every call, and an administrator retry from manual intervention. Two follow-up reminders (recover equipment, collect or archive documents) can be completed, marked not applicable or reopened. A scheduled separation can be cancelled before it takes effect, reversing everything. Tenant administrators and the caller's own access are never separated here. V15; ADR 0008. French and English sections on the employee record and an access column in the access review. Approved proposal D22-1 to D22-22 with amendments A22-1 to A22-6 on Issue #49 apply.

Out of scope for MVP-022: separating tenant administrators (a later governance story), corrections of a separation after it takes effect, rehires, final pay, documents and asset records, notes or attachments, approvals, an HR role, bulk separations, re-enabling identities, and AI features.

## Epic 4 Contracts and Documents

### MVP-030 Create and acknowledge a contract

HR can create a contract from an approved template in French or English and record employee acknowledgment.

**Issue #51. Complete (PR #52, merged 2026-10-04).** Tenant administrators write contract templates in a restricted plain-text grammar (headings, paragraphs, lists and 12 allow-listed fields; links, web addresses, encoded text and markup are refused with a closed reason and a line number), one language per version, and approve each version with an explicit confirmation that the organization verified the legal wording; approving a new version retires the previous one, and approved text never changes. From an employee's record they preview the exact contract text for a period (warnings when the type differs from the recorded classification; missing values and invalid dates block issue) and issue it with the preview's employment version and digest; the rendered snapshot is stored once, immutable, with a domain-separated SHA-256 digest. An issued contract can be voided with a closed reason until it is acknowledged. Employees linked to their record see « Mes contrats » / "My contracts", read the contract and acknowledge receipt with a server-owned statement in their language; the evidence binds the snapshot, the statement, their membership and active link and the database time, and is not an electronic signature. Employee self-service denials are not durable privileged-denial evidence. V16; ADR 0009. Approved proposal D1–D16 with amendments A30-1 to A30-4 on Issue #51 apply.

Out of scope for MVP-030: PDF files, uploads and object storage, e-signature providers, amendments, renewals and replacement contracts, termination documents, notifications, bulk issue, offline or administrator-recorded acknowledgement, four-eyes approval, job titles, salaries and addresses in templates, bilingual documents, a retention period, and AI wording or translation.

### MVP-031 Track document expiration

Authorized users receive alerts for expiring contracts, credentials, and identity records.

**MVP-031A (Issue #73). In review.** Contract expirations: a tenant-administrator queue and a home card "Contracts needing attention" / « Contrats à traiter » over issued contracts. One row per employment, its coverage head (the last contract of the contiguous chain that includes the latest started contract, else the earliest future one; voided contracts ignored); open-ended heads never warn; a recorded separation whose last day is on or before the head's end suppresses it. Categories on the organization's business date: expired, next 30 days, 31–60, 61–90. Search by employee number or name, filter by department or cost center through the hierarchy, keyset pages whose HMAC cursor pins the business date and filters. Fail-closed disclosure audit, no new data. V17 adds an index. Approved proposal with amendments A31A-1 to A31A-6 on Issue #73 apply.

**MVP-031B. Reserved.** Credential and identity-document expiration, once the underlying document model exists.

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

## Epic 13 Product Experience

### UI-001 Product shell and role-based home experience

The web application becomes a workforce operations product with a reusable interface foundation for every module.

**Issue #53. Complete (PR #54, merged 2026-10-05; follow-up PR #62 moved the landing-page identity into the semantic tokens).** A responsive shell replaces the horizontal header: a persistent navy sidebar on desktop that collapses to an icon rail (remembered), an icon rail by default on tablet and a native modal navigation drawer on phones; a top bar with the workspace context, a textual badge for every non-production environment ("Development environment" / « Environnement de développement », "Test environment", "Staging environment" / « Environnement de préproduction »), the English/French switch and an account menu holding the roles, the organization ID, the theme, the system status link and sign-out. Navigation shows only destinations the verified session's roles can open, grouped (Overview, Platform, People, Organization, Documents, Access and security, My space); the route registry is presentation metadata and the existing guards and the Core API remain authoritative; URLs, deep links and history are unchanged; the invitation and sign-in callback flows keep a public frame. Role homes for platform administrators, organization administrators and employees are static entry points with no figures; planned modules appear only as "Coming later" / « À venir » text. Bounded primitives (icon registry, page header, breadcrumbs, navigation group, cards, status badge, empty state, skeleton, error panel) and a navy, teal and gold token set (packages/design-system/README.md). Frontend only. Approved proposal D1–D14 (D9 as amended) with amendments UI1-1 to UI1-3 on Issue #53 apply.

Out of scope for UI-001: backend, API, realm or database changes (the organization's display name and the user's own name are follow-ups), redesign of feature pages, tables and forms, live metrics, notifications or activity, global search, an organization switcher, and staging or production deployment.

### UI-002 Public landing page at `/`, connected to sign-in

Anonymous visitors to `/` see the DivalHR landing page in English or French; existing customers sign in from it through the existing flow; signed-in users keep the application shell.

**Issue #63. Complete (PR #64, merged 2026-10-05, architect final review approved).** One typed catalogue drives the twelve approved vision modules (Employee Management, Leave Management, Payroll & Payments, Loans & Salary Advances, Benefits & Insurance, Performance, Documents, Analytics, Workflow Automation, Dival AI, Integrations, Mobile Apps), each labelled "Available now" or "Coming later" against the actual product, plus an "In DivalHR today" list (employee management, organization structure, contracts, access and security). The planned integration ecosystem shows twenty official names, with eight locally served marks and seven documented name-only exceptions (`docs/BRAND-ASSETS.md`); the payout story and finance flow are marked "Coming later" with the approved responsibility wording. Static illustrative product preview with a bilingual caption and no figures or data. Request a demo opens `mailto:contact@dival.ai`. Frontend only; no new dependency. Live on `hr-dev.dival.ai`: the English and French landing page passes the live acceptance suite.

Deployment handoff (UI2-6), for the hr-dev hosting story: `hr-dev.dival.ai` must send `X-Robots-Tag: noindex, nofollow, noarchive`, must not be submitted to search engines or included in production sitemaps; the landing page does not hard-code that host.

## Epic 14 Operations

### OPS-001 Deploy the DivalHR test environment to hr-dev.dival.ai

A released commit of `main` runs at `https://hr-dev.dival.ai` with synthetic data only, so the product can be demonstrated and accepted outside a workstation.

**Issue #65. Complete (PR #66, merged 2026-10-06; deployed and accepted).** Same-origin routing through Caddy (`/`, `/api`, `/ai`, `/identity`), a production-mode Keycloak build under `/identity` with the `divalhr-test` realm, Core in the `test` environment with file-based secrets, Mailpit, PostgreSQL 17 on a dedicated encrypted EBS volume with nightly logical dumps and daily encrypted snapshots, a single health watchdog, a host-wide operation lock, fail-closed deployment provenance, automatic rollback (with dump restore when migrations changed), an isolated restore drill, and a full rehearsal in every complete AWS verification (`docs/OPS-HR-DEV.md`). Approved proposal D1–D13 with amendments A65-1 to A65-7 on Issue #65 apply.

Follow-ups found during deployment, both merged before the accepted release: PR #69 stops the web app's service worker from answering navigations to the same-origin server routes (`/auth`, `/api`, `/identity`, `/ai`), which had broken sign-in in real browsers, and adds a live test with the service worker in control; Issue #68 (PR #70) corrects the snapshot-policy description, reads the running release with `sudo -n` and makes the deployment log follower print every line once. Deployed state: `hr-dev.dival.ai` runs `194471e`, deployed on 2026-10-06 with the previous release and a pre-deploy dump kept for rollback; seven of seven containers healthy; the live acceptance suite (landing page in English and French, OIDC sign-in, refresh and logout, the web-app round trip, privileged MFA sign-in, the admin console, the employee flows in English and French and the service-worker-controlled sign-in) passed with nothing skipped; architect acceptance on 2026-10-07. Synthetic data only.

Out of scope for OPS-001: production or staging, a dedicated identity origin (a production requirement, D1), high availability, external monitoring, real e-mail delivery and any change to application behaviour.

### DEVX-001 Reduce verification cycle time without reducing coverage

Maintenance (Issue #75; approved proposal with amendments A75-1 to A75-8). Two increments.

**DEVX-001A (complete, PR #76).** The review sequence in `CLAUDE.md`; the Playwright `auth-setup`, `features` (parallel, reusing one real MFA sign-in per privileged seed role) and `identity` (serial) projects, both required; credential hygiene of generated output; per-phase timings; and the `changed`, `pr` and `full` verification profiles. No deployment-gate or rehearsal change.

**DEVX-001B (in progress; amendments A75B-1 to A75B-7).** Risk classes with fail-closed precedence, the canonical rehearsal step matrix, checksummed read-only evidence for every finished run, and a deployment gate that recomputes the release class and tree and reuses the newest qualifying `pr` or `full` evidence, with a class-aware fallback. Closes #75.
