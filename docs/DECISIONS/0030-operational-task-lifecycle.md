# ADR 0030 Operational tasks: assign, start, complete or cancel, with immutable evidence

## Status

Decisions D80-1 to D80-12 approved by the architect on Issue #167 and implemented, as written, by the
MVP-080 pull request; pending architecture review of the implementation. Builds on ADR 0006
(idempotent commands), ADR 0007 (transactional outbox), ADR 0008 (lock order), ADR 0016 (neutral
wording) and ADR 0002 (bilingual first class). The decision-to-code map is
`docs/DECISIONS/0030-evidence.md`.

## Context

Epic 9 starts with the smallest useful slice of operational work: an organization administrator
hands one bounded task to one active employee, the employee reports progress, and both can see who
did what and when. Titles, instructions and notes are confidential tenant text, so most of the design
is about where that text may and may not travel.

## Decision

- **Roles (D80-1).** Tenant administrator (exact MFA, active membership): `/admin/tasks`, create,
  list, read, cancel. Linked active employee: `/me/tasks`, list and read their own, start and
  complete them. No manager authority. Unknown, foreign and unauthorized ids are the same 404.
- **Data (D80-2).** Immutable ids and assignee; title 1 to 120, instructions 0 to 1,000, priority
  `NORMAL | HIGH | URGENT`, optional due instant, state and version. The three text fields are
  restricted plain text (NFC, no markup, URLs, control characters or encoded content), stored once
  and shown unchanged; they are never in audit metadata, outbox payloads, metrics, logs, error
  responses or idempotency records (the command receipt carries identifiers only).
- **Eligibility (D80-3).** Same tenant, active employment at the server's business instant, active
  employee membership linked: any failure is `404 TASK_ASSIGNEE_NOT_FOUND`, with no hint. The link
  and membership are revalidated on every employee read and command (fail closed); administrators
  keep the history.
- **Lifecycle (D80-4).** `OPEN -> IN_PROGRESS | COMPLETED`, `IN_PROGRESS -> COMPLETED` by the
  assignee; `OPEN | IN_PROGRESS -> CANCELLED` by an administrator with a closed reason
  (`OTHER` needs a 10 to 500 character explanation, the others none). Terminal states are final.
  Every command carries the expected version: `409 TASK_VERSION_CONFLICT` when stale,
  `409 TASK_TRANSITION_NOT_ALLOWED` when the transition is not legal.
- **Dates (D80-5).** The server owns every timestamp. Due is after creation and at most 366 days
  (8,784 hours, so it does not depend on a time zone). Overdue is derived only: unfinished and now
  strictly after due; exactly at due is not overdue; it never changes stored state.
- **Persistence (D80-6).** V41 adds `operations.task` (projection) and `operations.task_action`
  (append-only). Triggers enforce tenant and task binding, one increasing sequence, legal
  transitions, state/version/action agreement (deferred check against the current row), actor shape,
  terminal immutability, no update or delete of evidence, no hard delete, and the 10,000 open-task
  limit. The rollback refuses while any row exists; there is no override.
- **Commands (D80-7).** Create, start, complete and cancel need `Idempotency-Key`. An identical
  replay returns the original receipt without a new action, audit row or event; a changed payload is
  `IDEMPOTENCY_KEY_REUSED`. The task row is locked before validation; projection, action, audit and
  outbox commit atomically.
- **API (D80-8).** Contract first (`docs/API-SPEC.yaml`), generated client, `private, no-store`,
  keyset pages (default 25, maximum 100) with a signed cursor bound to tenant, actor, filters, order
  and page size; stable problem codes, translated by the client.
- **Audit and events (D80-9).** Disclosure audits `task.list`, `task.read`; command audits
  `task.create`, `task.start`, `task.complete`, `task.cancel`. Events
  `operations.task.assigned|started|completed|cancelled.v1` carry identifiers, state, priority, due
  instant, reason code and counts only. They are not in the webhook or WhatsApp catalogues: that
  mapping needs a separate notification-policy decision.
- **UI (D80-10).** `/admin/tasks` (create with employee search, filters, list, detail with history,
  cancel confirmation) and `/me/tasks` (Open, In progress, Completed or cancelled; start; complete
  with an optional note after confirmation). Overdue and due-soon are text badges. Complete English
  and French.
- **Limits (D80-11).** 10,000 non-terminal tasks per tenant; creates 60 per subject and 300 per
  tenant per hour; start, complete and cancel combined 120 per subject and 600 per tenant per hour
  (subject hourly limits are counted durably from committed work, so they hold across instances;
  per-minute burst buckets sit in front); reads use their own bucket; explicit statement timeouts;
  capped pages; no user-controlled value in logs or metric labels.
- **Tests (D80-12).** See the evidence map.

## Consequences

- Task text exists in exactly one table and in authorized responses. Anything that later needs a task
  in a notification must use the identifiers and an authenticated deep link.
- Because overdue is derived, there is no job and nothing to reconcile; a clock change cannot corrupt
  state.
- No reassignment, editing or reopening: a mistake is cancelled with `CREATED_IN_ERROR` and assigned
  again.
- Rolling the application back is safe; rolling the schema back is refused once a task exists.

## Out of scope

Attachments, comments, recurrence, subtasks, dependencies, templates, reassignment, editing,
reopening, deletion, bulk actions, manager oversight, approvals, reminders and any notification
channel, projects, expenses, time or payroll linkage, AI, native and offline use.
