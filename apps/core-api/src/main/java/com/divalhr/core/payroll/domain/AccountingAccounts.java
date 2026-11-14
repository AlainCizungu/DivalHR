package com.divalhr.core.payroll.domain;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The five account codes of one accounting mapping version (MVP-096, ADR 0039 section 3). A code is
 * trimmed and must match {@code ^[A-Z0-9][A-Z0-9._-]{0,39}$}: the first character is a letter or a
 * digit, so no code can start with a hyphen and be read as a formula by a spreadsheet. Codes are
 * never names, descriptions or free text. The constructor refuses an invalid code without echoing
 * it.
 *
 * @param salaryExpense debit account of the gross salary
 * @param employeeDeductionLiability credit account of the employee deductions
 * @param employerContributionExpense debit account of the employer contributions
 * @param employerContributionLiability credit account of the employer contributions
 * @param netSalaryPayable credit account of the net salary
 */
public record AccountingAccounts(
    String salaryExpense,
    String employeeDeductionLiability,
    String employerContributionExpense,
    String employerContributionLiability,
    String netSalaryPayable) {

  /** The property names of the five codes, in the contract's order. */
  public static final List<String> FIELDS =
      List.of(
          "salaryExpense",
          "employeeDeductionLiability",
          "employerContributionExpense",
          "employerContributionLiability",
          "netSalaryPayable");

  private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,39}$");

  /** Requires five valid codes. */
  public AccountingAccounts {
    require(salaryExpense);
    require(employeeDeductionLiability);
    require(employerContributionExpense);
    require(employerContributionLiability);
    require(netSalaryPayable);
  }

  private static void require(String code) {
    if (code == null || !CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("account code is not valid");
    }
  }

  /**
   * Trims a raw code and checks it.
   *
   * @param raw the raw value, or {@code null}
   * @return the trimmed code, or empty when it is not a valid code
   */
  public static Optional<String> code(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.strip();
    return CODE.matcher(trimmed).matches() ? Optional.of(trimmed) : Optional.empty();
  }

  /**
   * The five codes in the contract's order.
   *
   * @return the codes
   */
  public List<String> values() {
    return List.of(
        salaryExpense,
        employeeDeductionLiability,
        employerContributionExpense,
        employerContributionLiability,
        netSalaryPayable);
  }

  @Override
  public String toString() {
    return "AccountingAccounts[redacted]";
  }
}
