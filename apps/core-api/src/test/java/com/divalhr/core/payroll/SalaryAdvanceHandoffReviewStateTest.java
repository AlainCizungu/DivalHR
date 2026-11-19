package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffReviewState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * MVP-120C (D120C-1, D120C-2): the closed partner-review catalogue and its transitions, every pair
 * checked. There is no approved, funded, paid or disbursed status.
 */
class SalaryAdvanceHandoffReviewStateTest {

  private static final Map<SalaryAdvanceHandoffReviewState, Set<SalaryAdvanceHandoffReviewState>>
      ALLOWED = new EnumMap<>(SalaryAdvanceHandoffReviewState.class);

  static {
    ALLOWED.put(
        SalaryAdvanceHandoffReviewState.RECEIVED,
        EnumSet.of(
            SalaryAdvanceHandoffReviewState.UNDER_REVIEW,
            SalaryAdvanceHandoffReviewState.MORE_INFORMATION_REQUIRED,
            SalaryAdvanceHandoffReviewState.DECLINED,
            SalaryAdvanceHandoffReviewState.CANCELLED_BY_PARTNER));
    ALLOWED.put(
        SalaryAdvanceHandoffReviewState.UNDER_REVIEW,
        EnumSet.of(
            SalaryAdvanceHandoffReviewState.MORE_INFORMATION_REQUIRED,
            SalaryAdvanceHandoffReviewState.DECLINED,
            SalaryAdvanceHandoffReviewState.CANCELLED_BY_PARTNER));
    ALLOWED.put(
        SalaryAdvanceHandoffReviewState.MORE_INFORMATION_REQUIRED,
        EnumSet.of(
            SalaryAdvanceHandoffReviewState.UNDER_REVIEW,
            SalaryAdvanceHandoffReviewState.DECLINED,
            SalaryAdvanceHandoffReviewState.CANCELLED_BY_PARTNER));
    ALLOWED.put(
        SalaryAdvanceHandoffReviewState.DECLINED,
        EnumSet.noneOf(SalaryAdvanceHandoffReviewState.class));
    ALLOWED.put(
        SalaryAdvanceHandoffReviewState.CANCELLED_BY_PARTNER,
        EnumSet.noneOf(SalaryAdvanceHandoffReviewState.class));
  }

  @Test
  void theCatalogueIsClosedAndNamesNoApprovalOrFundingStatus() {
    assertThat(SalaryAdvanceHandoffReviewState.values())
        .extracting(Enum::name)
        .containsExactly(
            "RECEIVED",
            "UNDER_REVIEW",
            "MORE_INFORMATION_REQUIRED",
            "DECLINED",
            "CANCELLED_BY_PARTNER");
    for (String forbidden : new String[] {"APPROVED", "FUNDED", "PAID", "DISBURSED", "OFFERED"}) {
      assertThat(SalaryAdvanceHandoffReviewState.parse(forbidden)).isEmpty();
    }
    assertThat(SalaryAdvanceHandoffReviewState.parse("under_review")).isEmpty();
  }

  @Test
  void everyTransitionPairIsAllowedOrRefusedExactlyAsSpecified() {
    for (SalaryAdvanceHandoffReviewState from : SalaryAdvanceHandoffReviewState.values()) {
      for (SalaryAdvanceHandoffReviewState to : SalaryAdvanceHandoffReviewState.values()) {
        assertThat(from.mayMoveTo(to))
            .as(from + " -> " + to)
            .isEqualTo(ALLOWED.get(from).contains(to));
      }
    }
  }

  @Test
  void onlyDeclinedAndPartnerCancellationAreTerminal() {
    for (SalaryAdvanceHandoffReviewState state : SalaryAdvanceHandoffReviewState.values()) {
      boolean terminal =
          state == SalaryAdvanceHandoffReviewState.DECLINED
              || state == SalaryAdvanceHandoffReviewState.CANCELLED_BY_PARTNER;
      assertThat(state.terminal()).as(state.name()).isEqualTo(terminal);
      assertThat(ALLOWED.get(state).isEmpty()).isEqualTo(terminal);
    }
  }
}
