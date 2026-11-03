package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** MVP-095C (ADR 0038 section 5): the reconciliation state machine, exhaustively. */
class PaymentReconciliationStateTest {

  private static final Map<PaymentReconciliationState, Set<PaymentReconciliationState>> LEGAL =
      Map.of(
          PaymentReconciliationState.OPEN,
          Set.of(
              PaymentReconciliationState.INQUIRY_IN_PROGRESS,
              PaymentReconciliationState.MANUAL_REVIEW_REQUIRED),
          PaymentReconciliationState.INQUIRY_IN_PROGRESS,
          Set.of(
              PaymentReconciliationState.OPEN,
              PaymentReconciliationState.CONFIRMED_ACCEPTED_FOR_PROCESSING,
              PaymentReconciliationState.CONFIRMED_REJECTED,
              PaymentReconciliationState.STILL_UNRESOLVED,
              PaymentReconciliationState.MANUAL_REVIEW_REQUIRED),
          PaymentReconciliationState.CONFIRMED_ACCEPTED_FOR_PROCESSING,
          Set.of(),
          PaymentReconciliationState.CONFIRMED_REJECTED,
          Set.of(),
          PaymentReconciliationState.STILL_UNRESOLVED,
          Set.of(),
          PaymentReconciliationState.MANUAL_REVIEW_REQUIRED,
          Set.of());

  @Test
  void theTransitionMatrixIsExactlyTheApprovedOne() {
    assertThat(LEGAL).hasSize(PaymentReconciliationState.values().length);
    for (PaymentReconciliationState from : PaymentReconciliationState.values()) {
      for (PaymentReconciliationState to : PaymentReconciliationState.values()) {
        assertThat(from.mayMoveTo(to))
            .as(from + " -> " + to)
            .isEqualTo(LEGAL.get(from).contains(to));
      }
    }
  }

  @Test
  void concludedStatesHaveNoExitAndOpenStatesAreNotConcluded() {
    assertThat(PaymentReconciliationState.OPEN.concluded()).isFalse();
    assertThat(PaymentReconciliationState.INQUIRY_IN_PROGRESS.concluded()).isFalse();
    for (PaymentReconciliationState state :
        Set.of(
            PaymentReconciliationState.CONFIRMED_ACCEPTED_FOR_PROCESSING,
            PaymentReconciliationState.CONFIRMED_REJECTED,
            PaymentReconciliationState.STILL_UNRESOLVED,
            PaymentReconciliationState.MANUAL_REVIEW_REQUIRED)) {
      assertThat(state.concluded()).as(state.name()).isTrue();
      for (PaymentReconciliationState next : PaymentReconciliationState.values()) {
        assertThat(state.mayMoveTo(next)).as(state + " -> " + next).isFalse();
      }
    }
  }

  @Test
  void aStateIsNeverItsOwnSuccessorAndParsingIsExactWithoutPaidOrSettled() {
    for (PaymentReconciliationState state : PaymentReconciliationState.values()) {
      assertThat(state.mayMoveTo(state)).as(state.name()).isFalse();
      assertThat(PaymentReconciliationState.parse(state.name())).contains(state);
      assertThat(state.name())
          .doesNotContainIgnoringCase("PAID")
          .doesNotContainIgnoringCase("SETTLE");
    }
    assertThat(PaymentReconciliationState.parse("open")).isEmpty();
    assertThat(PaymentReconciliationState.parse("PAID")).isEmpty();
    assertThat(PaymentReconciliationState.parse("SETTLED")).isEmpty();
    assertThat(PaymentReconciliationState.parse(null)).isEmpty();
  }
}
