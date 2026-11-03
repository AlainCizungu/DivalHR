package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PaymentInquiryOutcome.Kind;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MVP-095C (ADR 0038 section 4): the closed inquiry outcome allow-list and its mapping. */
class PaymentInquiryOutcomeTest {

  private static final Map<PaymentInquiryOutcome, Kind> KINDS =
      Map.ofEntries(
          Map.entry(PaymentInquiryOutcome.ACCEPTED_FOR_PROCESSING, Kind.ACCEPTED),
          Map.entry(PaymentInquiryOutcome.REJECTED_INVALID_REQUEST, Kind.REJECTED),
          Map.entry(PaymentInquiryOutcome.REJECTED_SIGNATURE, Kind.REJECTED),
          Map.entry(PaymentInquiryOutcome.REJECTED_UNSUPPORTED, Kind.REJECTED),
          Map.entry(PaymentInquiryOutcome.REJECTED_DUPLICATE_MISMATCH, Kind.REJECTED),
          Map.entry(PaymentInquiryOutcome.REJECTED_LIMIT, Kind.REJECTED),
          Map.entry(PaymentInquiryOutcome.REJECTED_OTHER, Kind.REJECTED),
          Map.entry(PaymentInquiryOutcome.NOT_FOUND, Kind.NON_FINAL),
          Map.entry(PaymentInquiryOutcome.PENDING, Kind.NON_FINAL),
          Map.entry(PaymentInquiryOutcome.UNKNOWN, Kind.NON_FINAL),
          Map.entry(PaymentInquiryOutcome.BAD_RESPONSE, Kind.BAD),
          Map.entry(PaymentInquiryOutcome.TRANSIENT_CONNECT, Kind.TRANSIENT),
          Map.entry(PaymentInquiryOutcome.TRANSIENT_TLS, Kind.TRANSIENT),
          Map.entry(PaymentInquiryOutcome.TRANSIENT_TIMEOUT, Kind.TRANSIENT),
          Map.entry(PaymentInquiryOutcome.TRANSIENT_RATE_LIMITED, Kind.TRANSIENT),
          Map.entry(PaymentInquiryOutcome.TRANSIENT_SERVER, Kind.TRANSIENT),
          Map.entry(PaymentInquiryOutcome.RETRIES_EXHAUSTED, Kind.EXHAUSTED));

  @Test
  void thereAreExactlySeventeenOutcomesAndEachHasItsKind() {
    assertThat(PaymentInquiryOutcome.values()).hasSize(17);
    assertThat(KINDS).hasSize(17);
    for (PaymentInquiryOutcome outcome : PaymentInquiryOutcome.values()) {
      assertThat(outcome.kind()).as(outcome.name()).isEqualTo(KINDS.get(outcome));
      assertThat(PaymentInquiryOutcome.parse(outcome.name())).contains(outcome);
      assertThat(outcome.name())
          .doesNotContainIgnoringCase("PAID")
          .doesNotContainIgnoringCase("SETTLE");
    }
    assertThat(PaymentInquiryOutcome.parse("pending")).isEmpty();
    assertThat(PaymentInquiryOutcome.parse("PAID")).isEmpty();
    assertThat(PaymentInquiryOutcome.parse(null)).isEmpty();
  }

  @Test
  void onlyAnAuthenticatedAnswerIsAuthenticated() {
    for (PaymentInquiryOutcome outcome : PaymentInquiryOutcome.values()) {
      boolean expected =
          switch (KINDS.get(outcome)) {
            case ACCEPTED, REJECTED, NON_FINAL -> true;
            case BAD, TRANSIENT, EXHAUSTED -> false;
          };
      assertThat(outcome.authenticated()).as(outcome.name()).isEqualTo(expected);
    }
    assertThat(PaymentInquiryOutcome.BAD_RESPONSE.authenticated()).isFalse();
    assertThat(PaymentInquiryOutcome.RETRIES_EXHAUSTED.authenticated()).isFalse();
  }

  @Test
  void onlyAnAuthenticatedAcceptanceOrRejectionCanConclude() {
    for (PaymentInquiryOutcome outcome : PaymentInquiryOutcome.values()) {
      for (boolean exhausted : new boolean[] {false, true}) {
        PaymentReconciliationState next = outcome.nextState(exhausted);
        String label = outcome + " exhausted=" + exhausted;
        switch (KINDS.get(outcome)) {
          case ACCEPTED ->
              assertThat(next)
                  .as(label)
                  .isEqualTo(PaymentReconciliationState.CONFIRMED_ACCEPTED_FOR_PROCESSING);
          case REJECTED ->
              assertThat(next).as(label).isEqualTo(PaymentReconciliationState.CONFIRMED_REJECTED);
          case NON_FINAL, BAD ->
              assertThat(next).as(label).isEqualTo(PaymentReconciliationState.STILL_UNRESOLVED);
          case TRANSIENT ->
              assertThat(next)
                  .as(label)
                  .isEqualTo(
                      exhausted
                          ? PaymentReconciliationState.MANUAL_REVIEW_REQUIRED
                          : PaymentReconciliationState.OPEN);
          case EXHAUSTED ->
              assertThat(next)
                  .as(label)
                  .isEqualTo(PaymentReconciliationState.MANUAL_REVIEW_REQUIRED);
        }
        // An unauthenticated outcome can never reach a confirmed state.
        if (!outcome.authenticated()) {
          assertThat(next)
              .as(label)
              .isNotIn(
                  PaymentReconciliationState.CONFIRMED_ACCEPTED_FOR_PROCESSING,
                  PaymentReconciliationState.CONFIRMED_REJECTED);
        }
        // Every worker result is a legal move from an in-progress inquiry.
        assertThat(PaymentReconciliationState.INQUIRY_IN_PROGRESS.mayMoveTo(next))
            .as(label)
            .isTrue();
      }
    }
  }
}
