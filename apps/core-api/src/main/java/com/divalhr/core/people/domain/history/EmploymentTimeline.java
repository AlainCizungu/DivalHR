package com.divalhr.core.people.domain.history;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The temporal engine of one employment (MVP-021, H1, H10, H11; M21-2, M21-5). It plans, without
 * side effects, which active rows a change, correction or cancellation supersedes and which rows it
 * inserts. The database enforces the same invariants at commit (no overlap, gap-free placement,
 * change shape), so a plan that breaks them never persists.
 *
 * <p>Rules: a change at date D for a kind replaces the active row covering D, keeping its part
 * before D as a copy and giving D through that row's end the new value; it never alters a later row
 * (a change ends where the next one starts). A correction replaces one active row's value for the
 * same dates. A cancellation (scheduled changes only) restores the replaced value over the
 * cancelled change's current span, bounded by the next active row, and refuses when a later row was
 * derived from the change in a way restoration would alter.
 */
public final class EmploymentTimeline {

  private final LocalDate start;
  private final LocalDate end;
  private final List<Assignment> rows;
  private final Supplier<UUID> ids;

  /**
   * Creates the timeline.
   *
   * @param start employment start date
   * @param end employment end date, or {@code null} when open
   * @param rows every row of the employment, active and superseded
   * @param ids generator of new row IDs
   */
  public EmploymentTimeline(
      LocalDate start, LocalDate end, List<Assignment> rows, Supplier<UUID> ids) {
    this.start = Objects.requireNonNull(start, "start");
    this.end = end;
    this.rows = List.copyOf(rows);
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  /**
   * Active rows of a kind, by start date.
   *
   * @param kind kind
   * @return rows
   */
  public List<Assignment> active(AssignmentKind kind) {
    return rows.stream()
        .filter(row -> row.kind() == kind && row.active())
        .sorted(Comparator.comparing(Assignment::from))
        .toList();
  }

  /**
   * The active row of a kind covering a day.
   *
   * @param kind kind
   * @param day day
   * @return the row, if any
   */
  public Optional<Assignment> covering(AssignmentKind kind, LocalDate day) {
    return active(kind).stream().filter(row -> row.covers(day)).findFirst();
  }

  /**
   * Whether a day is inside the employment.
   *
   * @param day day
   * @return true when {@code start <= day <= end}
   */
  public boolean employed(LocalDate day) {
    return !day.isBefore(start) && (end == null || !day.isAfter(end));
  }

  /**
   * Plans a business change. An empty value ends the kind's current row the day before D (only
   * meaningful for MANAGER; the application refuses it for other kinds).
   *
   * @param changeId the change's ID (origin of the new rows)
   * @param day effective date D
   * @param changes per kind, the new value or empty to clear
   * @return the plan
   * @throws TimelineRuleException when a rule refuses it
   */
  public TimelinePlan planChange(
      UUID changeId, LocalDate day, Map<AssignmentKind, Optional<AssignmentValue>> changes) {
    if (changes.isEmpty()) {
      throw new IllegalArgumentException("no kind to change");
    }
    if (!employed(day)) {
      throw new TimelineRuleException(TimelineRule.OUTSIDE_EMPLOYMENT, null);
    }
    List<Assignment> superseded = new ArrayList<>();
    List<NewAssignment> created = new ArrayList<>();
    boolean limited = false;
    for (Map.Entry<AssignmentKind, Optional<AssignmentValue>> change :
        new EnumMap<>(changes).entrySet()) {
      AssignmentKind kind = change.getKey();
      Optional<AssignmentValue> value = change.getValue();
      value.ifPresent(
          v -> {
            if (v.kind() != kind) {
              throw new IllegalArgumentException("value of another kind");
            }
          });
      Optional<Assignment> covering = covering(kind, day);
      if (covering.isPresent()) {
        Assignment row = covering.get();
        if (value.isPresent() && value.get().equals(row.value())) {
          throw new TimelineRuleException(TimelineRule.NO_EFFECT, kind);
        }
        if (row.from().equals(day)) {
          throw new TimelineRuleException(TimelineRule.DATE_TAKEN, kind);
        }
        superseded.add(row);
        created.add(
            new NewAssignment(
                ids.get(), kind, row.from(), day.minusDays(1), row.value(), row.origin(), null));
        if (value.isPresent()) {
          created.add(
              new NewAssignment(ids.get(), kind, day, row.to(), value.get(), changeId, null));
        }
        limited |= row.to() != null && nextStart(kind, row.to()).isPresent();
      } else {
        if (kind == AssignmentKind.PLACEMENT) {
          throw new IllegalStateException("placement does not cover the employment");
        }
        if (value.isEmpty()) {
          throw new TimelineRuleException(TimelineRule.NO_EFFECT, kind);
        }
        Optional<LocalDate> next = nextStart(kind, day);
        LocalDate until = next.map(d -> d.minusDays(1)).orElse(end);
        created.add(new NewAssignment(ids.get(), kind, day, until, value.get(), changeId, null));
        limited |= next.isPresent();
      }
    }
    return new TimelinePlan(superseded, created, limited);
  }

  /**
   * Plans a correction: one active row's value replaced for the same dates.
   *
   * @param correctionId the correction's ID
   * @param target the active row to correct
   * @param value corrected value
   * @return the plan
   * @throws TimelineRuleException when the value is unchanged
   */
  public TimelinePlan planCorrection(UUID correctionId, Assignment target, AssignmentValue value) {
    if (!target.active() || !rows.contains(target)) {
      throw new IllegalArgumentException("not an active row of this employment");
    }
    if (value.kind() != target.kind()) {
      throw new IllegalArgumentException("value of another kind");
    }
    if (value.equals(target.value())) {
      throw new TimelineRuleException(TimelineRule.NO_EFFECT, target.kind());
    }
    return new TimelinePlan(
        List.of(target),
        List.of(
            new NewAssignment(
                ids.get(), target.kind(), target.from(), target.to(), value, correctionId, null)),
        false);
  }

  /**
   * Plans the cancellation of a scheduled business change (M21-2). Per kind of the cancelled
   * change: the value it replaced is restored over the change's current span, bounded by the next
   * active row, and the copies and pieces it wrote are superseded. When the change that wrote the
   * replaced value was itself cancelled first, the value restored over the copy is the one
   * extended. Later rows are never altered; when restoration would alter one, the cancellation is
   * refused.
   *
   * @param cancelledId the cancelled change
   * @param day its effective date D
   * @param kinds its kinds
   * @return the plan
   * @throws TimelineRuleException {@link TimelineRule#HAS_DEPENDENTS}
   */
  public TimelinePlan planCancellation(
      UUID cancelledId, LocalDate day, java.util.Collection<AssignmentKind> kinds) {
    List<Assignment> superseded = new ArrayList<>();
    List<NewAssignment> created = new ArrayList<>();
    for (AssignmentKind kind : new java.util.TreeSet<>(kinds)) {
      cancelKind(cancelledId, day, kind, superseded, created);
    }
    if (superseded.isEmpty()) {
      throw new TimelineRuleException(TimelineRule.HAS_DEPENDENTS, null);
    }
    return new TimelinePlan(superseded, created, false);
  }

  private void cancelKind(
      UUID cancelledId,
      LocalDate day,
      AssignmentKind kind,
      List<Assignment> superseded,
      List<NewAssignment> created) {
    // The row the cancelled change replaced (none when it filled a gap).
    Optional<Assignment> replaced =
        rows.stream()
            .filter(row -> row.kind() == kind && cancelledId.equals(row.supersededBy()))
            .findFirst();
    List<Assignment> written =
        rows.stream()
            .filter(row -> row.kind() == kind && row.createdBy().equals(cancelledId))
            .toList();
    // The copy of the replaced row's earlier part the cancelled change wrote.
    Optional<Assignment> copy =
        written.stream().filter(row -> !row.origin().equals(cancelledId)).findFirst();
    // The active row the restored value extends: the copy itself, or the row restored over the
    // copy when the change that wrote the replaced value was cancelled in turn. Anything else that
    // replaced the copy (a later change or a correction) depends on the cancelled change.
    Optional<Assignment> predecessor = copy.map(c -> predecessor(c, kind, day));
    List<Assignment> active = active(kind);
    // The cancelled change's value as it currently stands: one contiguous run of active rows
    // carrying its origin, starting on D.
    List<Assignment> run = new ArrayList<>();
    LocalDate expected = day;
    for (Assignment row : active) {
      if (!row.origin().equals(cancelledId)) {
        continue;
      }
      if (!row.from().equals(expected)) {
        throw dependents(kind);
      }
      run.add(row);
      expected = row.to() == null ? null : row.to().plusDays(1);
      if (expected == null) {
        break;
      }
    }
    long carried = active.stream().filter(row -> row.origin().equals(cancelledId)).count();
    if (carried != run.size()) {
      throw dependents(kind);
    }
    boolean cleared = written.stream().noneMatch(row -> row.origin().equals(cancelledId));
    if (cleared) {
      // The change ended the kind on D: nothing may have started on D since.
      if (covering(kind, day).isPresent() || replaced.isEmpty() || copy.isEmpty()) {
        throw dependents(kind);
      }
    } else if (run.isEmpty() || !run.get(0).from().equals(day)) {
      throw dependents(kind);
    }
    superseded.addAll(run);
    if (replaced.isEmpty()) {
      return;
    }
    Assignment before = replaced.get();
    Assignment extended = predecessor.orElseThrow(() -> dependents(kind));
    superseded.add(extended);
    LocalDate until;
    if (cleared) {
      Optional<LocalDate> next = nextStart(kind, day.minusDays(1));
      until = earliest(before.to(), next.map(d -> d.minusDays(1)).orElse(null));
    } else {
      until = run.get(run.size() - 1).to();
    }
    // The restored row names the row whose value and origin it carries: the replaced row when it
    // extends the cancelled change's own copy, else the restored row it extends.
    UUID restores = extended.equals(copy.get()) ? before.id() : extended.id();
    created.add(
        new NewAssignment(
            ids.get(),
            kind,
            extended.from(),
            until,
            extended.value(),
            extended.origin(),
            restores));
  }

  /**
   * The active row ending the day before D that a cancellation extends: the cancelled change's copy
   * while it is active, else the row a cancellation restored over that copy.
   */
  private Assignment predecessor(Assignment copy, AssignmentKind kind, LocalDate day) {
    if (copy.active()) {
      return copy;
    }
    return active(kind).stream()
        .filter(
            row ->
                row.restores() != null
                    && row.createdBy().equals(copy.supersededBy())
                    && day.minusDays(1).equals(row.to())
                    && !row.from().isAfter(copy.from()))
        .findFirst()
        .orElseThrow(() -> dependents(kind));
  }

  private static LocalDate earliest(LocalDate a, LocalDate b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.isBefore(b) ? a : b;
  }

  private static TimelineRuleException dependents(AssignmentKind kind) {
    return new TimelineRuleException(TimelineRule.HAS_DEPENDENTS, kind);
  }

  /** The start of the first active row of a kind starting after a day. */
  private Optional<LocalDate> nextStart(AssignmentKind kind, LocalDate after) {
    return active(kind).stream()
        .map(Assignment::from)
        .filter(from -> from.isAfter(after))
        .findFirst();
  }
}
