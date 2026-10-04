package com.divalhr.core.people.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The temporal engine (MVP-021, H1, H10, H11, M21-2, M21-5) on an in-memory timeline: plans are
 * applied the way the repository writes them (supersede, then insert), so sequences of changes and
 * cancellations can be checked end to end. Every resulting timeline is checked gap-free (placement)
 * and non-overlapping (every kind).
 */
class EmploymentTimelineTest {

  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final AssignmentValue A = placement();
  private static final AssignmentValue B = placement();
  private static final AssignmentValue C = placement();

  private final List<Assignment> rows = new ArrayList<>();
  private LocalDate end;
  private UUID hire;

  private static AssignmentValue placement() {
    return new AssignmentValue.Placement(UUID.randomUUID(), UUID.randomUUID(), null, null, null);
  }

  private static AssignmentValue manager() {
    return new AssignmentValue.Manager(UUID.randomUUID());
  }

  private static LocalDate day(int month, int dayOfMonth) {
    return LocalDate.of(2026, month, dayOfMonth);
  }

  @BeforeEach
  void hire() {
    hire = UUID.randomUUID();
    end = null;
    rows.add(
        new Assignment(
            UUID.randomUUID(),
            AssignmentKind.PLACEMENT,
            START,
            null,
            A,
            hire,
            hire,
            null,
            null,
            null));
  }

  private EmploymentTimeline timeline() {
    return new EmploymentTimeline(START, end, rows, UUID::randomUUID);
  }

  /** Applies a plan as the repository does: supersede, then insert. */
  private void apply(UUID changeId, TimelinePlan plan) {
    for (Assignment replaced : plan.superseded()) {
      int index = rows.indexOf(replaced);
      assertThat(index).as("superseded rows are current rows").isNotNegative();
      assertThat(replaced.active()).isTrue();
      rows.set(
          index,
          new Assignment(
              replaced.id(),
              replaced.kind(),
              replaced.from(),
              replaced.to(),
              replaced.value(),
              replaced.createdBy(),
              replaced.origin(),
              replaced.restores(),
              changeId,
              java.time.Instant.EPOCH));
    }
    for (NewAssignment created : plan.created()) {
      rows.add(
          new Assignment(
              created.id(),
              created.kind(),
              created.from(),
              created.to(),
              created.value(),
              changeId,
              created.origin(),
              created.restores(),
              null,
              null));
    }
    assertValid();
  }

  private UUID change(LocalDate day, AssignmentKind kind, AssignmentValue value) {
    UUID id = UUID.randomUUID();
    apply(id, timeline().planChange(id, day, Map.of(kind, Optional.ofNullable(value))));
    return id;
  }

  private UUID cancel(UUID cancelled, LocalDate day, Set<AssignmentKind> kinds) {
    UUID id = UUID.randomUUID();
    apply(id, timeline().planCancellation(cancelled, day, kinds));
    return id;
  }

  private List<Assignment> active(AssignmentKind kind) {
    return timeline().active(kind);
  }

  /** No overlap for any kind; placement covers the employment without a gap. */
  private void assertValid() {
    for (AssignmentKind kind : AssignmentKind.values()) {
      List<Assignment> active =
          rows.stream()
              .filter(r -> r.kind() == kind && r.active())
              .sorted(Comparator.comparing(Assignment::from))
              .toList();
      for (int i = 1; i < active.size(); i++) {
        Assignment previous = active.get(i - 1);
        assertThat(previous.to()).as("%s rows overlap", kind).isNotNull();
        assertThat(previous.to()).isBefore(active.get(i).from());
        if (kind == AssignmentKind.PLACEMENT) {
          assertThat(previous.to().plusDays(1)).as("placement gap").isEqualTo(active.get(i).from());
        }
      }
      if (kind == AssignmentKind.PLACEMENT) {
        assertThat(active.get(0).from()).isEqualTo(START);
        assertThat(active.get(active.size() - 1).to()).isEqualTo(end);
      }
    }
  }

  private static void assertPeriods(List<Assignment> rows, Object... expected) {
    List<Object> actual = new ArrayList<>();
    for (Assignment row : rows) {
      actual.add(row.from());
      actual.add(row.to());
      actual.add(row.value());
    }
    assertThat(actual).containsExactly(expected);
  }

  @Nested
  class Changes {

    @Test
    void aChangeSplitsTheCoveringRowAndKeepsItsEarlierPartAsACopy() {
      Assignment hired = rows.get(0);
      UUID id = UUID.randomUUID();
      TimelinePlan plan =
          timeline().planChange(id, day(6, 1), Map.of(AssignmentKind.PLACEMENT, Optional.of(B)));

      assertThat(plan.superseded()).containsExactly(hired);
      assertThat(plan.created()).hasSize(2);
      NewAssignment copy = plan.created().get(0);
      NewAssignment next = plan.created().get(1);
      assertThat(copy.from()).isEqualTo(START);
      assertThat(copy.to()).isEqualTo(day(5, 31));
      assertThat(copy.value()).isEqualTo(A);
      assertThat(copy.origin()).isEqualTo(hire);
      assertThat(next.from()).isEqualTo(day(6, 1));
      assertThat(next.to()).isNull();
      assertThat(next.origin()).isEqualTo(id);
      assertThat(plan.laterChangeLimits()).isFalse();
    }

    @Test
    void anEarlierChangeEndsWhereTheNextOneStartsAndNeverAltersIt() {
      change(day(9, 1), AssignmentKind.PLACEMENT, C);
      UUID id = UUID.randomUUID();
      TimelinePlan plan =
          timeline().planChange(id, day(6, 1), Map.of(AssignmentKind.PLACEMENT, Optional.of(B)));
      apply(id, plan);

      assertThat(plan.laterChangeLimits()).isTrue();
      assertPeriods(
          active(AssignmentKind.PLACEMENT),
          START,
          day(5, 31),
          A,
          day(6, 1),
          day(8, 31),
          B,
          day(9, 1),
          null,
          C);
    }

    @Test
    void aKindAlreadyChangingOnTheDateIsRefused() {
      change(day(6, 1), AssignmentKind.PLACEMENT, B);

      assertThatThrownBy(
              () ->
                  timeline()
                      .planChange(
                          UUID.randomUUID(),
                          day(6, 1),
                          Map.of(AssignmentKind.PLACEMENT, Optional.of(C))))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> assertThat(e.rule()).isEqualTo(TimelineRule.DATE_TAKEN));
    }

    @Test
    void anUnchangedValueHasNoEffect() {
      assertThatThrownBy(
              () ->
                  timeline()
                      .planChange(
                          UUID.randomUUID(),
                          day(6, 1),
                          Map.of(AssignmentKind.PLACEMENT, Optional.of(A))))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> {
                assertThat(e.rule()).isEqualTo(TimelineRule.NO_EFFECT);
                assertThat(e.kind()).isEqualTo(AssignmentKind.PLACEMENT);
              });
    }

    @Test
    void aDateOutsideTheEmploymentIsRefused() {
      end = day(12, 31);
      rows.set(
          0,
          new Assignment(
              rows.get(0).id(),
              AssignmentKind.PLACEMENT,
              START,
              end,
              A,
              hire,
              hire,
              null,
              null,
              null));
      for (LocalDate outside : List.of(START.minusDays(1), end.plusDays(1))) {
        assertThatThrownBy(
                () ->
                    timeline()
                        .planChange(
                            UUID.randomUUID(),
                            outside,
                            Map.of(AssignmentKind.PLACEMENT, Optional.of(B))))
            .isInstanceOfSatisfying(
                TimelineRuleException.class,
                e -> assertThat(e.rule()).isEqualTo(TimelineRule.OUTSIDE_EMPLOYMENT));
      }
    }

    @Test
    void aKindWithoutARowStartsOnTheDateUntilTheNextRowOrTheEmploymentEnd() {
      end = day(12, 31);
      rows.set(
          0,
          new Assignment(
              rows.get(0).id(),
              AssignmentKind.PLACEMENT,
              START,
              end,
              A,
              hire,
              hire,
              null,
              null,
              null));
      AssignmentValue later = manager();
      change(day(9, 1), AssignmentKind.MANAGER, later);
      AssignmentValue first = manager();
      change(day(3, 1), AssignmentKind.MANAGER, first);

      assertPeriods(
          active(AssignmentKind.MANAGER), day(3, 1), day(8, 31), first, day(9, 1), end, later);
    }

    @Test
    void clearingTheManagerEndsTheRowTheDayBefore() {
      AssignmentValue boss = manager();
      change(day(2, 1), AssignmentKind.MANAGER, boss);
      change(day(6, 1), AssignmentKind.MANAGER, null);

      assertPeriods(active(AssignmentKind.MANAGER), day(2, 1), day(5, 31), boss);
    }

    @Test
    void clearingAKindWithoutARowHasNoEffect() {
      assertThatThrownBy(
              () ->
                  timeline()
                      .planChange(
                          UUID.randomUUID(),
                          day(6, 1),
                          Map.of(AssignmentKind.MANAGER, Optional.empty())))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> assertThat(e.rule()).isEqualTo(TimelineRule.NO_EFFECT));
    }

    @Test
    void severalKindsChangeTogether() {
      AssignmentValue contract = new AssignmentValue.Contract(ContractClassification.PERMANENT);
      UUID id = UUID.randomUUID();
      Map<AssignmentKind, Optional<AssignmentValue>> changes = new EnumMap<>(AssignmentKind.class);
      changes.put(AssignmentKind.PLACEMENT, Optional.of(B));
      changes.put(AssignmentKind.CONTRACT, Optional.of(contract));
      TimelinePlan plan = timeline().planChange(id, day(6, 1), changes);
      apply(id, plan);

      assertThat(plan.kinds()).containsExactly(AssignmentKind.PLACEMENT, AssignmentKind.CONTRACT);
      assertPeriods(active(AssignmentKind.CONTRACT), day(6, 1), null, contract);
    }
  }

  @Nested
  class Corrections {

    @Test
    void aCorrectionReplacesOneRowsValueForTheSameDates() {
      change(day(6, 1), AssignmentKind.PLACEMENT, B);
      Assignment target = active(AssignmentKind.PLACEMENT).get(1);
      UUID id = UUID.randomUUID();
      TimelinePlan plan = timeline().planCorrection(id, target, C);
      apply(id, plan);

      assertThat(plan.superseded()).containsExactly(target);
      assertThat(plan.created())
          .singleElement()
          .satisfies(
              row -> {
                assertThat(row.from()).isEqualTo(target.from());
                assertThat(row.to()).isEqualTo(target.to());
                assertThat(row.origin()).isEqualTo(id);
                assertThat(row.restores()).isNull();
              });
      assertPeriods(active(AssignmentKind.PLACEMENT), START, day(5, 31), A, day(6, 1), null, C);
    }

    @Test
    void anUnchangedCorrectionHasNoEffectAndSupersededRowsCannotBeCorrected() {
      Assignment hired = rows.get(0);
      assertThatThrownBy(() -> timeline().planCorrection(UUID.randomUUID(), hired, A))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> assertThat(e.rule()).isEqualTo(TimelineRule.NO_EFFECT));
      change(day(6, 1), AssignmentKind.PLACEMENT, B);
      Assignment superseded = rows.get(0);
      assertThatThrownBy(() -> timeline().planCorrection(UUID.randomUUID(), superseded, C))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  /** M21-2: A (hire) then B then C, cancelled in each order. */
  @Nested
  class Cancellations {

    private UUID changeB;
    private UUID changeC;

    @BeforeEach
    void abc() {
      changeB = change(day(6, 1), AssignmentKind.PLACEMENT, B);
      changeC = change(day(9, 1), AssignmentKind.PLACEMENT, C);
    }

    @Test
    void cancellingTheLatestChangeRestoresTheValueItReplacedToTheEnd() {
      Assignment replaced =
          rows.stream().filter(r -> changeC.equals(r.supersededBy())).findFirst().orElseThrow();
      cancel(changeC, day(9, 1), Set.of(AssignmentKind.PLACEMENT));

      assertPeriods(active(AssignmentKind.PLACEMENT), START, day(5, 31), A, day(6, 1), null, B);
      Assignment restored = active(AssignmentKind.PLACEMENT).get(1);
      assertThat(restored.restores()).isEqualTo(replaced.id());
      assertThat(restored.origin()).isEqualTo(changeB);
    }

    @Test
    void cancellingAMiddleChangeIsBoundedByTheNextChangeAndNeverAltersIt() {
      Assignment later = active(AssignmentKind.PLACEMENT).get(2);
      Assignment replaced =
          rows.stream().filter(r -> changeB.equals(r.supersededBy())).findFirst().orElseThrow();
      UUID cancellation = cancel(changeB, day(6, 1), Set.of(AssignmentKind.PLACEMENT));

      assertPeriods(active(AssignmentKind.PLACEMENT), START, day(8, 31), A, day(9, 1), null, C);
      assertThat(active(AssignmentKind.PLACEMENT).get(1)).isEqualTo(later);
      Assignment restored = active(AssignmentKind.PLACEMENT).get(0);
      assertThat(restored.restores()).isEqualTo(replaced.id());
      assertThat(restored.origin()).isEqualTo(hire);
      assertThat(restored.createdBy()).isEqualTo(cancellation);
      // Lineage: every row the cancelled change wrote is kept, superseded.
      assertThat(rows.stream().filter(r -> changeB.equals(r.createdBy())))
          .allSatisfy(r -> assertThat(r.active()).isFalse());
    }

    @Test
    void thenCancellingTheOtherChangeRestoresTheHire() {
      cancel(changeC, day(9, 1), Set.of(AssignmentKind.PLACEMENT));
      cancel(changeB, day(6, 1), Set.of(AssignmentKind.PLACEMENT));

      assertPeriods(active(AssignmentKind.PLACEMENT), START, null, A);
    }

    @Test
    void cancellingTheMiddleChangeThenTheLatestRestoresTheHireToTheEnd() {
      cancel(changeB, day(6, 1), Set.of(AssignmentKind.PLACEMENT));
      Assignment extended = active(AssignmentKind.PLACEMENT).get(0);
      cancel(changeC, day(9, 1), Set.of(AssignmentKind.PLACEMENT));

      assertPeriods(active(AssignmentKind.PLACEMENT), START, null, A);
      Assignment restored = active(AssignmentKind.PLACEMENT).get(0);
      assertThat(restored.restores()).isEqualTo(extended.id());
      assertThat(restored.origin()).isEqualTo(hire);
    }

    @Test
    void aChangeWhoseCopyWasSplitByAnEarlierChangeHasDependents() {
      change(day(3, 1), AssignmentKind.PLACEMENT, C);

      assertThatThrownBy(
              () ->
                  timeline().planCancellation(changeB, day(6, 1), Set.of(AssignmentKind.PLACEMENT)))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> assertThat(e.rule()).isEqualTo(TimelineRule.HAS_DEPENDENTS));
    }

    @Test
    void aChangeWhoseValueWasCorrectedHasDependents() {
      Assignment valueOfC = active(AssignmentKind.PLACEMENT).get(2);
      UUID correction = UUID.randomUUID();
      apply(correction, timeline().planCorrection(correction, valueOfC, B));

      assertThatThrownBy(
              () ->
                  timeline().planCancellation(changeC, day(9, 1), Set.of(AssignmentKind.PLACEMENT)))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> assertThat(e.rule()).isEqualTo(TimelineRule.HAS_DEPENDENTS));
    }

    @Test
    void severalKindsAreCancelledTogether() {
      AssignmentValue contract = new AssignmentValue.Contract(ContractClassification.FIXED_TERM);
      UUID id = UUID.randomUUID();
      Map<AssignmentKind, Optional<AssignmentValue>> changes = new EnumMap<>(AssignmentKind.class);
      changes.put(AssignmentKind.PLACEMENT, Optional.of(A));
      changes.put(AssignmentKind.CONTRACT, Optional.of(contract));
      apply(id, timeline().planChange(id, day(11, 1), changes));

      cancel(id, day(11, 1), Set.of(AssignmentKind.PLACEMENT, AssignmentKind.CONTRACT));

      assertThat(active(AssignmentKind.CONTRACT)).isEmpty();
      assertPeriods(
          active(AssignmentKind.PLACEMENT),
          START,
          day(5, 31),
          A,
          day(6, 1),
          day(8, 31),
          B,
          day(9, 1),
          null,
          C);
    }
  }

  @Nested
  class ClearedManager {

    private final AssignmentValue boss = manager();
    private UUID clear;

    @BeforeEach
    void clearOnJune() {
      change(day(2, 1), AssignmentKind.MANAGER, boss);
      clear = change(day(6, 1), AssignmentKind.MANAGER, null);
    }

    @Test
    void cancellingTheClearRestoresTheManagerToTheNextActiveBoundary() {
      AssignmentValue next = manager();
      change(day(9, 1), AssignmentKind.MANAGER, next);
      cancel(clear, day(6, 1), Set.of(AssignmentKind.MANAGER));

      assertPeriods(
          active(AssignmentKind.MANAGER), day(2, 1), day(8, 31), boss, day(9, 1), null, next);
    }

    @Test
    void aManagerStartingOnTheClearedDateIsADependent() {
      change(day(6, 1), AssignmentKind.MANAGER, manager());

      assertThatThrownBy(
              () -> timeline().planCancellation(clear, day(6, 1), Set.of(AssignmentKind.MANAGER)))
          .isInstanceOfSatisfying(
              TimelineRuleException.class,
              e -> assertThat(e.rule()).isEqualTo(TimelineRule.HAS_DEPENDENTS));
    }
  }
}
