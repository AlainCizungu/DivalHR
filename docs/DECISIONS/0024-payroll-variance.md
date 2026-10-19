# ADR 0024 Payroll variance: a read-only comparison of two immutable runs

## Status

Proposed with the MVP-091 pull request (Issue #143); pending architecture review. The second
increment of one combined payroll release. Builds on ADR 0023 (payroll inputs).

## Context

A payroll officer must see where one period's worked-time inputs differ materially from the prior
period's before release. The evidence already exists and is immutable (V37). The comparison must
never produce a pay figure, never invent a baseline, and never leak a person.

## Decision

- **No migration, no stored result.** The variance is computed on every read from V37
  `input_employee` rows through the existing payroll repository. V37 is untouched.
- **Eligibility.** A comparison run has the same tenant and legal entity, ends before the current
  period begins and has the same inclusive day count. SQL narrows to at most 50 candidates ordered
  newest first; the domain re-checks each. The default is the newest eligible run; with none the
  candidates endpoint returns an empty list and the review is a safe 404.
- **Matching.** By immutable employee ID: the employments of one employee in a run are aggregated.
  NEW (absent before), MISSING (absent now), CHANGED, UNCHANGED. Both runs' employment IDs are
  returned.
- **Materiality.** NEW and MISSING always; CHANGED when |delta| >= the absolute threshold or, with
  comparison minutes above 0, |delta| x 100 >= percentage x comparison minutes. UNCHANGED is never
  material, even at zero thresholds (the only reading that keeps "any applicable difference" true).
  Integer arithmetic only; percentages are signed basis points rounded half up on the absolute value.
- **Ordering and paging.** Material first, then largest |delta|, then employee ID. The review is
  sorted in memory and paged by keyset position. The cursor is signed and bound to tenant, both
  runs, thresholds, view and page size.
- **Totals reconcile exactly** and never depend on the page or filter: employee counts, minutes,
  signed delta, counts per classification and the material count.
- **Authority and limits.** Tenant administrator with exact MFA; the existing `payroll-input-read`
  bucket is reused (no new bucket, no weakened limit). `Cache-Control: private, no-store`.
- **Audit and privacy.** `payroll-variance.candidates` and `.review`, disclosed fail closed, with
  bands and bounded counts only; identifiers and the delta live only in the state digest. No outbox
  event. Logs and metrics carry bands only.
- **UI.** Inside payroll preparation; the selection stays in component state, never in storage or a
  URL. Source runs are opened through the existing detail view.

## Consequences

- Reads are O(employees of two runs); acceptable for the bounded run size, and the 50-candidate cap
  bounds the selector.
- A later increment may store an approved variance; nothing here prevents it.
- Run links are top level (current and comparison summaries), not per row.
