package com.divalhr.core.payroll.domain;

import static com.divalhr.core.payroll.domain.AccountingDirection.CREDIT;
import static com.divalhr.core.payroll.domain.AccountingDirection.DEBIT;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the accounting journal of one approved payroll package from the frozen amounts of its
 * payslips and a mapping's five account codes (MVP-096, ADR 0039 section 4). Pure and
 * deterministic: nothing is recalculated and no currency is converted.
 *
 * <p>Per payslip, an entry exists only when its amount is positive: DEBIT salary expense (gross),
 * CREDIT employee deduction liability (employee deductions), DEBIT employer contribution expense
 * and CREDIT employer contribution liability (employer contributions), CREDIT net salary payable
 * (net). Entries merge by (account code, cost center, site, direction). The lines are ordered by
 * account code in byte order, DEBIT before CREDIT, cost center (empty first), site (empty first)
 * and numbered 1 to n. A package that cannot balance, or that exceeds 10,000 entries before or
 * 2,000 lines after aggregation, is refused and no line is produced.
 */
public final class AccountingJournalBuilder {

  /** Most entries before aggregation. */
  public static final int MAX_ENTRIES = 10_000;

  /** Most lines after aggregation. */
  public static final int MAX_LINES = 2_000;

  private AccountingJournalBuilder() {}

  /**
   * The frozen amounts of one payslip, with the cost center and site its frozen line carried.
   *
   * @param grossMinor gross pay
   * @param employeeDeductionsMinor employee deductions
   * @param employerContributionsMinor employer contributions
   * @param netMinor net pay
   * @param costCenterCode cost center code, or {@code null} (none today)
   * @param siteCode site code, or {@code null} (none today)
   */
  public record Row(
      long grossMinor,
      long employeeDeductionsMinor,
      long employerContributionsMinor,
      long netMinor,
      String costCenterCode,
      String siteCode) {

    @Override
    public String toString() {
      return "AccountingRow[redacted]";
    }
  }

  /**
   * The four frozen totals of the package header.
   *
   * @param grossMinor gross total
   * @param employeeDeductionsMinor employee deductions total
   * @param employerContributionsMinor employer contributions total
   * @param netMinor net total
   */
  public record Totals(
      long grossMinor,
      long employeeDeductionsMinor,
      long employerContributionsMinor,
      long netMinor) {

    @Override
    public String toString() {
      return "AccountingTotals[redacted]";
    }
  }

  /** Why a journal cannot be built. */
  public enum Refusal {
    /** The amounts do not balance or do not equal the frozen package totals. */
    UNBALANCED,
    /** More than 10,000 entries before, or 2,000 lines after, aggregation. */
    TOO_LARGE
  }

  /**
   * The outcome of a build.
   *
   * @param refusal why nothing was built, or {@code null}
   * @param sourceEntryCount entries before aggregation
   * @param lines the ordered, numbered lines (empty when refused)
   * @param totalDebitsMinor sum of the DEBIT lines (0 when refused)
   * @param totalCreditsMinor sum of the CREDIT lines (0 when refused)
   */
  public record Result(
      Refusal refusal,
      int sourceEntryCount,
      List<AccountingLine> lines,
      long totalDebitsMinor,
      long totalCreditsMinor) {

    /** Copies the lines. */
    public Result {
      lines = List.copyOf(lines);
    }

    /**
     * Whether a journal was built.
     *
     * @return true when there is no refusal
     */
    public boolean ready() {
      return refusal == null;
    }

    @Override
    public String toString() {
      return "AccountingJournalResult[" + refusal + "]";
    }
  }

  /** The aggregation key; its natural order is the journal order. */
  private record Key(String account, AccountingDirection side, String center, String site)
      implements Comparable<Key> {

    @Override
    public int compareTo(Key other) {
      int byAccount = account.compareTo(other.account);
      if (byAccount != 0) {
        return byAccount;
      }
      int bySide = side.compareTo(other.side);
      if (bySide != 0) {
        return bySide;
      }
      int byCenter = center.compareTo(other.center);
      if (byCenter != 0) {
        return byCenter;
      }
      return site.compareTo(other.site);
    }
  }

  /**
   * Builds the journal.
   *
   * @param rows the frozen amounts of every payslip of the package
   * @param accounts the mapping's five codes
   * @param expected the frozen totals of the package header; the rows must add up to them
   * @return the lines, or the refusal
   */
  public static Result build(List<Row> rows, AccountingAccounts accounts, Totals expected) {
    try {
      return construct(rows, accounts, expected);
    } catch (ArithmeticException overflow) {
      return refused(Refusal.UNBALANCED, 0);
    }
  }

  private static Result construct(List<Row> rows, AccountingAccounts accounts, Totals expected) {
    long gross = 0;
    long deductions = 0;
    long contributions = 0;
    long net = 0;
    int entries = 0;
    for (Row row : rows) {
      if (!consistent(row)) {
        return refused(Refusal.UNBALANCED, 0);
      }
      gross = Math.addExact(gross, row.grossMinor());
      deductions = Math.addExact(deductions, row.employeeDeductionsMinor());
      contributions = Math.addExact(contributions, row.employerContributionsMinor());
      net = Math.addExact(net, row.netMinor());
      entries += positive(row.grossMinor()) + positive(row.employeeDeductionsMinor());
      entries += 2 * positive(row.employerContributionsMinor()) + positive(row.netMinor());
    }
    if (entries > MAX_ENTRIES) {
      return refused(Refusal.TOO_LARGE, entries);
    }
    if (gross != expected.grossMinor()
        || deductions != expected.employeeDeductionsMinor()
        || contributions != expected.employerContributionsMinor()
        || net != expected.netMinor()) {
      return refused(Refusal.UNBALANCED, entries);
    }
    Map<Key, Long> merged = aggregate(rows, accounts);
    if (merged.size() > MAX_LINES) {
      return refused(Refusal.TOO_LARGE, entries);
    }
    List<AccountingLine> lines = new ArrayList<>();
    long debits = 0;
    long credits = 0;
    int number = 0;
    for (Map.Entry<Key, Long> entry : merged.entrySet()) {
      Key key = entry.getKey();
      number++;
      lines.add(
          new AccountingLine(
              number,
              key.account(),
              key.side(),
              entry.getValue(),
              key.center().isEmpty() ? null : key.center(),
              key.site().isEmpty() ? null : key.site()));
      if (key.side() == AccountingDirection.DEBIT) {
        debits = Math.addExact(debits, entry.getValue());
      } else {
        credits = Math.addExact(credits, entry.getValue());
      }
    }
    if (lines.isEmpty() || debits != credits) {
      return refused(Refusal.UNBALANCED, entries);
    }
    return new Result(null, entries, lines, debits, credits);
  }

  private static Map<Key, Long> aggregate(List<Row> rows, AccountingAccounts accounts) {
    Map<Key, Long> merged = new TreeMap<>();
    for (Row row : rows) {
      String center = row.costCenterCode() == null ? "" : row.costCenterCode();
      String site = row.siteCode() == null ? "" : row.siteCode();
      long deductions = row.employeeDeductionsMinor();
      long contributions = row.employerContributionsMinor();
      add(merged, accounts.salaryExpense(), DEBIT, center, site, row.grossMinor());
      add(merged, accounts.employeeDeductionLiability(), CREDIT, center, site, deductions);
      add(merged, accounts.employerContributionExpense(), DEBIT, center, site, contributions);
      add(merged, accounts.employerContributionLiability(), CREDIT, center, site, contributions);
      add(merged, accounts.netSalaryPayable(), CREDIT, center, site, row.netMinor());
    }
    return merged;
  }

  private static boolean consistent(Row row) {
    long gross = row.grossMinor();
    long deductions = row.employeeDeductionsMinor();
    return gross >= 0
        && deductions >= 0
        && row.employerContributionsMinor() >= 0
        && row.netMinor() == gross - deductions
        && row.netMinor() >= 0;
  }

  private static int positive(long amount) {
    return amount > 0 ? 1 : 0;
  }

  private static void add(
      Map<Key, Long> merged,
      String account,
      AccountingDirection side,
      String center,
      String site,
      long amount) {
    if (amount > 0) {
      merged.merge(new Key(account, side, center, site), amount, Math::addExact);
    }
  }

  private static Result refused(Refusal refusal, int entries) {
    return new Result(refusal, entries, List.of(), 0L, 0L);
  }
}
