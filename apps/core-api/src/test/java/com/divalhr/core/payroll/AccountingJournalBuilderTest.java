package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.AccountingAccounts;
import com.divalhr.core.payroll.domain.AccountingDirection;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder.Refusal;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder.Result;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder.Row;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder.Totals;
import com.divalhr.core.payroll.domain.AccountingLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-096: the journal builder is pure, balanced, aggregated and deterministic. */
class AccountingJournalBuilderTest {

  private static final AccountingAccounts ACCOUNTS =
      new AccountingAccounts("6100", "2100", "6200", "2200", "2300");

  private static Row row(long gross, long deductions, long employer, long net) {
    return new Row(gross, deductions, employer, net, null, null);
  }

  private static Totals totals(List<Row> rows) {
    long gross = 0;
    long deductions = 0;
    long employer = 0;
    long net = 0;
    for (Row row : rows) {
      gross += row.grossMinor();
      deductions += row.employeeDeductionsMinor();
      employer += row.employerContributionsMinor();
      net += row.netMinor();
    }
    return new Totals(gross, deductions, employer, net);
  }

  private static Result build(List<Row> rows) {
    return AccountingJournalBuilder.build(rows, ACCOUNTS, totals(rows));
  }

  @Test
  void onePayslipProducesFiveBalancedLinesInAccountOrder() {
    Result result = build(List.of(row(1000, 150, 100, 850)));
    assertThat(result.ready()).isTrue();
    assertThat(result.lines()).hasSize(5);
    assertThat(result.totalDebitsMinor()).isEqualTo(1100);
    assertThat(result.totalCreditsMinor()).isEqualTo(1100);
    List<String> accounts = result.lines().stream().map(AccountingLine::accountCode).toList();
    assertThat(accounts).containsExactly("2100", "2200", "2300", "6100", "6200");
    List<Integer> numbers = result.lines().stream().map(AccountingLine::lineNumber).toList();
    assertThat(numbers).containsExactly(1, 2, 3, 4, 5);
  }

  @Test
  void zeroAmountEntriesProduceNoLine() {
    Result result = build(List.of(row(1000, 0, 0, 1000)));
    assertThat(result.ready()).isTrue();
    assertThat(result.lines()).hasSize(2);
    assertThat(result.lines().get(0).direction()).isEqualTo(AccountingDirection.CREDIT);
    assertThat(result.lines().get(1).direction()).isEqualTo(AccountingDirection.DEBIT);
  }

  @Test
  void payslipsAreAggregatedPerAccountAndSide() {
    Result result = build(List.of(row(1000, 150, 100, 850), row(500, 50, 40, 450)));
    assertThat(result.ready()).isTrue();
    assertThat(result.sourceEntryCount()).isEqualTo(10);
    assertThat(result.lines()).hasSize(5);
    assertThat(result.lines().get(3).amountMinor()).isEqualTo(1500);
  }

  @Test
  void sameAccountForDebitAndCreditKeepsDebitFirst() {
    AccountingAccounts same = new AccountingAccounts("6100", "2100", "6100", "2100", "2300");
    List<Row> rows = List.of(row(1000, 150, 100, 850));
    Result result = AccountingJournalBuilder.build(rows, same, totals(rows));
    assertThat(result.ready()).isTrue();
    assertThat(result.lines()).hasSize(3);
    assertThat(result.lines().get(1).accountCode()).isEqualTo("2300");
    assertThat(result.lines().get(2).direction()).isEqualTo(AccountingDirection.DEBIT);
    assertThat(result.lines().get(2).amountMinor()).isEqualTo(1100);
  }

  @Test
  void theOrderOfThePayslipsDoesNotChangeTheLines() {
    List<Row> rows = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      rows.add(row(1000L + i, 100L + i, 50L, 900L));
    }
    List<Row> shuffled = new ArrayList<>(rows);
    Collections.reverse(shuffled);
    Totals same = totals(rows);
    Result first = AccountingJournalBuilder.build(rows, ACCOUNTS, same);
    Result second = AccountingJournalBuilder.build(shuffled, ACCOUNTS, same);
    assertThat(first.lines()).isEqualTo(second.lines());
  }

  @Test
  void rowsThatDoNotAddUpToTheFrozenTotalsAreUnbalanced() {
    List<Row> rows = List.of(row(1000, 150, 100, 850));
    Totals wrong = new Totals(1001, 150, 100, 850);
    Result result = AccountingJournalBuilder.build(rows, ACCOUNTS, wrong);
    assertThat(result.refusal()).isEqualTo(Refusal.UNBALANCED);
    assertThat(result.lines()).isEmpty();
  }

  @Test
  void aPayslipWhoseAmountsDoNotBalanceIsUnbalanced() {
    Result result = build(List.of(row(1000, 150, 100, 800)));
    assertThat(result.refusal()).isEqualTo(Refusal.UNBALANCED);
  }

  @Test
  void anEmptyOrZeroPackageIsUnbalanced() {
    assertThat(build(List.of()).refusal()).isEqualTo(Refusal.UNBALANCED);
    assertThat(build(List.of(row(0, 0, 0, 0))).refusal()).isEqualTo(Refusal.UNBALANCED);
  }

  @Test
  void moreThanTenThousandEntriesIsTooLarge() {
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < 2001; i++) {
      rows.add(row(1000, 150, 100, 850));
    }
    Result result = build(rows);
    assertThat(result.refusal()).isEqualTo(Refusal.TOO_LARGE);
    assertThat(result.lines()).isEmpty();
  }

  @Test
  void exactlyTenThousandEntriesIsAccepted() {
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      rows.add(row(1000, 150, 100, 850));
    }
    Result result = build(rows);
    assertThat(result.ready()).isTrue();
    assertThat(result.sourceEntryCount()).isEqualTo(10_000);
  }

  @Test
  void toStringsAreRedacted() {
    Row row = row(123456, 0, 0, 123456);
    assertThat(row.toString()).doesNotContain("123456");
    assertThat(totals(List.of(row)).toString()).doesNotContain("123456");
  }
}
