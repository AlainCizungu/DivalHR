package com.divalhr.core.people.domain.history;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * What one change, correction or cancellation does: the active rows it supersedes and the rows it
 * inserts, per kind, ordered by kind then start date.
 *
 * @param superseded rows replaced (kept, with their supersession pair set)
 * @param created rows inserted
 * @param laterChangeLimits whether a later row of the same kind bounds a new row
 */
public record TimelinePlan(
    List<Assignment> superseded, List<NewAssignment> created, boolean laterChangeLimits) {

  /** Orders the rows deterministically. */
  public TimelinePlan {
    superseded =
        List.copyOf(
            superseded.stream()
                .sorted(Comparator.comparing(Assignment::kind).thenComparing(Assignment::from))
                .toList());
    created =
        List.copyOf(
            created.stream()
                .sorted(
                    Comparator.comparing(NewAssignment::kind).thenComparing(NewAssignment::from))
                .toList());
  }

  /**
   * Kinds the plan touches.
   *
   * @return kinds
   */
  public Set<AssignmentKind> kinds() {
    java.util.EnumSet<AssignmentKind> kinds = java.util.EnumSet.noneOf(AssignmentKind.class);
    superseded.forEach(row -> kinds.add(row.kind()));
    created.forEach(row -> kinds.add(row.kind()));
    return kinds;
  }
}
