# ADR 0007 Effective-dated employment history

## Status

Accepted (Issue #47, architect approval with amendments M21-1 to M21-5).

## Context

MVP-020 stored one placement on each employment. MVP-021 must record changes to placement,
manager, contract classification and compensation basis over time, including scheduled,
retroactive and corrected values, without destroying history, and other modules (time,
payroll inputs, separations) will read "the value on a date".

Options considered:

1. **Columns on the employment, plus an audit trail.** Simple reads, but history lives only in
   audit metadata, which M21-1 forbids from carrying Restricted HR values.
2. **Snapshot per change** (one row with every attribute per change). Easy to read, but one
   change to one attribute rewrites all others, and corrections and cancellations are hard to
   express without ambiguity.
3. **One effective-dated timeline per kind, with transaction-time supersession**, written only
   through an append-only change log.

## Decision

Option 3.

- `people.employment_assignment` holds one timeline per kind (`PLACEMENT`, `MANAGER`,
  `CONTRACT`, `COMPENSATION`). Business values and dates never change; a later write supersedes a
  row once (`superseded_by_change_id`, `superseded_at`) and inserts new rows. Lineage columns
  (`created_by`, `origin`, `restores`) keep how every row came to be.
- `people.employment_change` is the command log (`HIRE`, `CHANGE`, `CORRECTION`,
  `CANCELLATION`). Every write is one change row plus its superseded and inserted rows, and the
  employment's `version` increments.
- A change at date D replaces the row covering D, keeps its earlier part as a copy, and ends where
  the next change starts: later changes are never altered. A correction replaces one row's value
  for its own dates. A cancellation (scheduled changes only) restores the replaced value up to the
  next active row, or is refused when a later row depends on the change.
- Cancelling an earlier change after a later one (architect decision on PR #48): with A, then B,
  then C recorded, cancelling B restores A only until C, and C is unchanged. Cancelling C afterwards
  extends that restored A through C's former end. The restored row names the row it extends
  (`restores_assignment_id`), which must itself be a restoration with the same value and origin,
  so transaction-time lineage is kept. Any other replacement of the copy (a later change or a
  correction) still makes the cancellation `EMPLOYMENT_CHANGE_HAS_DEPENDENTS`.
- PostgreSQL is the final authority (M21-4, M21-5): exclusion constraints (`btree_gist`) for
  non-overlap, deferred constraint triggers for gap-free placement, acyclic reporting lines under
  the trigger's own per-tenant advisory lock, and the exact shape of each change type; triggers
  refuse updates of values, deletes and truncation. The shape of a change is validated by change
  ID whenever its row is inserted and whenever an assignment is later inserted or superseded in
  its name, so a committed change can never be extended afterwards (R21-1). A change, and a
  cancellation, must touch exactly its `kinds[]`: no extra kind, no partial cancellation. The application computes the same plan first
  (`EmploymentTimeline`) so users get stable business codes; only named constraints are mapped.
- Commits are bound to their preview by the employment version and a digest; writes are
  idempotent.

## Consequences

- "Value on a date" is a range query on active rows; the full transaction-time history (what was
  believed when) stays queryable for audit and dispute handling.
- Storage grows with every change (rows are never deleted); no retention is approved yet.
- Cancelling an earlier change after a later one is allowed only when restoration does not alter
  the later change; otherwise the administrator cancels or corrects the later change first.
- `btree_gist` becomes a database prerequisite; rollbacks leave it installed (M21-3).
- Separations (MVP-022) end the employment and its open rows through the same change log.
