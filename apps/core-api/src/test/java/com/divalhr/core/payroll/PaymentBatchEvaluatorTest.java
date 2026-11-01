package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PaymentBatchBlocker;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Destination;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Evaluation;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Slip;
import com.divalhr.core.payroll.domain.PaymentBatchEvaluator.Source;
import com.divalhr.core.payroll.domain.PaymentBatchLineIssue;
import com.divalhr.core.payroll.domain.PaymentBatchWarning;
import com.divalhr.core.payroll.domain.PaymentDigests;
import com.divalhr.core.payroll.domain.PaymentMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-095A (D95A-4/5): the batch copies approved nets exactly; zero-net payslips are excluded;
 * every positive-net line needs one authentic destination or the whole batch is blocked.
 */
class PaymentBatchEvaluatorTest {

  private static final Source OPEN = new Source(true, true, false, 0);

  private final UUID e1 = UUID.randomUUID();
  private final UUID e2 = UUID.randomUUID();
  private final UUID e3 = UUID.randomUUID();

  private static Slip slip(UUID employee, String number, long net) {
    return new Slip(UUID.randomUUID(), employee, number, net, 0, net, false);
  }

  private static Destination bank(boolean authentic, boolean changed) {
    return new Destination(
        UUID.randomUUID(), 1, PaymentMethod.BANK_ACCOUNT, "CD", "RAWBANK", "Rawbank",
        "****8901", "f".repeat(64), authentic, changed);
  }

  private static Destination manual() {
    return new Destination(
        UUID.randomUUID(), 2, PaymentMethod.MANUAL, null, null, null, null, null, true, false);
  }

  @Test
  void aCompleteBatchCopiesTheApprovedNetsAndCountsByMethod() {
    List<Slip> slips = List.of(slip(e1, "E1", 149040), slip(e2, "E2", 0), slip(e3, "E3", 1200));
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, false));
    destinations.put(e3, manual());
    Evaluation result = PaymentBatchEvaluator.evaluate(OPEN, slips, destinations);
    assertThat(result.ready()).isTrue();
    assertThat(result.lines()).hasSize(2);
    assertThat(result.totalNet()).isEqualTo(150240);
    assertThat(result.zeroNetExcluded()).isEqualTo(1);
    assertThat(result.bank()).isEqualTo(1);
    assertThat(result.mobile()).isZero();
    assertThat(result.manual()).isEqualTo(1);
    assertThat(result.warnings())
        .containsExactlyInAnyOrder(
            PaymentBatchWarning.MANUAL_LINES_PRESENT, PaymentBatchWarning.ZERO_NET_EXCLUDED);
    assertThat(result.lines().stream().mapToLong(PaymentBatchEvaluator.Line::net).sum())
        .isEqualTo(result.totalNet());
  }

  @Test
  void aMissingDestinationBlocksTheWholeBatchNoPartialBatch() {
    List<Slip> slips = List.of(slip(e1, "E1", 100), slip(e2, "E2", 200));
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, false));
    Evaluation result = PaymentBatchEvaluator.evaluate(OPEN, slips, destinations);
    assertThat(result.ready()).isFalse();
    assertThat(result.blockers()).contains(PaymentBatchBlocker.MISSING_DESTINATION);
    assertThat(result.lines().stream().map(PaymentBatchEvaluator.Line::issue))
        .contains(PaymentBatchLineIssue.MISSING_DESTINATION);
  }

  @Test
  void aDestinationThatDoesNotAuthenticateBlocks() {
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(false, false));
    Evaluation result =
        PaymentBatchEvaluator.evaluate(OPEN, List.of(slip(e1, "E1", 100)), destinations);
    assertThat(result.blockers()).contains(PaymentBatchBlocker.DESTINATION_INVALID);
  }

  @Test
  void aNegativeNetIsAnIntegrityFailureNeverAPayableLine() {
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, false));
    Evaluation result =
        PaymentBatchEvaluator.evaluate(OPEN, List.of(slip(e1, "E1", -5)), destinations);
    assertThat(result.blockers()).contains(PaymentBatchBlocker.INTEGRITY_FAILURE);
  }

  @Test
  void aNetThatDoesNotFollowFromGrossAndDeductionsIsAnIntegrityFailure() {
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, false));
    Slip inconsistent = new Slip(UUID.randomUUID(), e1, "E1", 1000, 100, 950, false);
    Evaluation result = PaymentBatchEvaluator.evaluate(OPEN, List.of(inconsistent), destinations);
    assertThat(result.blockers()).contains(PaymentBatchBlocker.INTEGRITY_FAILURE);
  }

  @Test
  void aDestinationChangedAfterApprovalIsAWarningNotAChange() {
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, true));
    Evaluation result =
        PaymentBatchEvaluator.evaluate(OPEN, List.of(slip(e1, "E1", 100)), destinations);
    assertThat(result.ready()).isTrue();
    assertThat(result.warnings()).contains(PaymentBatchWarning.DESTINATION_CHANGED_AFTER_APPROVAL);
  }

  @Test
  void packageAndChainStateBlock() {
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, false));
    List<Slip> slips = List.of(slip(e1, "E1", 100));
    assertThat(PaymentBatchEvaluator.evaluate(new Source(false, true, false, 0), slips, destinations).blockers())
        .contains(PaymentBatchBlocker.PACKAGE_VOIDED);
    assertThat(PaymentBatchEvaluator.evaluate(new Source(true, false, false, 0), slips, destinations).blockers())
        .contains(PaymentBatchBlocker.PACKAGE_NOT_LATEST);
    assertThat(PaymentBatchEvaluator.evaluate(new Source(true, true, true, 1), slips, destinations).blockers())
        .contains(PaymentBatchBlocker.BATCH_ALREADY_PREPARED);
    assertThat(
            PaymentBatchEvaluator.evaluate(
                    new Source(true, true, false, PaymentBatchEvaluator.MAX_VERSIONS), slips, destinations)
                .blockers())
        .contains(PaymentBatchBlocker.LIMIT_REACHED);
    assertThat(PaymentBatchEvaluator.evaluate(OPEN, List.of(slip(e1, "E1", 0)), destinations).blockers())
        .contains(PaymentBatchBlocker.NO_PAYABLE_LINES);
  }

  @Test
  void moreThanFiveThousandLinesIsTheLimit() {
    List<Slip> slips = new ArrayList<>();
    Map<UUID, Destination> destinations = new HashMap<>();
    for (int i = 0; i <= PaymentBatchEvaluator.MAX_LINES; i++) {
      UUID employee = UUID.randomUUID();
      slips.add(slip(employee, "E" + i, 1));
      destinations.put(employee, manual());
    }
    assertThat(PaymentBatchEvaluator.evaluate(OPEN, slips, destinations).blockers())
        .contains(PaymentBatchBlocker.LIMIT_REACHED);
  }

  @Test
  void theLineDigestsAreDeterministicAndBindEveryAmountAndDestination() {
    Map<UUID, Destination> destinations = new HashMap<>();
    destinations.put(e1, bank(true, false));
    List<Slip> slips = List.of(slip(e1, "E1", 100));
    Evaluation first = PaymentBatchEvaluator.evaluate(OPEN, slips, destinations);
    assertThat(PaymentDigests.lines(first.lines())).isEqualTo(PaymentDigests.lines(first.lines()));
    Evaluation other =
        PaymentBatchEvaluator.evaluate(
            OPEN, List.of(new Slip(slips.get(0).id(), e1, "E1", 101, 0, 101, false)), destinations);
    assertThat(PaymentDigests.lines(other.lines())).isNotEqualTo(PaymentDigests.lines(first.lines()));
    assertThat(PaymentDigests.destinations(other.lines()))
        .isEqualTo(PaymentDigests.destinations(first.lines()));
  }
}
