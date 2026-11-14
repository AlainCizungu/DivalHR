package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.AccountingAccounts;
import org.junit.jupiter.api.Test;

/** MVP-096: account codes are strict, never echoed and never spreadsheet formulas. */
class AccountingAccountsTest {

  private static AccountingAccounts accounts(String salary) {
    return new AccountingAccounts(salary, "2100", "6200", "2200", "2300");
  }

  @Test
  void aValidMappingKeepsItsFiveCodesInContractOrder() {
    AccountingAccounts accounts = accounts("6100.A-1");
    assertThat(accounts.values()).containsExactly("6100.A-1", "2100", "6200", "2200", "2300");
  }

  @Test
  void codeTrimsAndAcceptsOnlyTheStrictAlphabet() {
    assertThat(AccountingAccounts.code("  6100 ")).contains("6100");
    assertThat(AccountingAccounts.code("-1+1")).isEmpty();
    assertThat(AccountingAccounts.code("=SUM(A1)")).isEmpty();
    assertThat(AccountingAccounts.code("@x")).isEmpty();
    assertThat(AccountingAccounts.code("a100")).isEmpty();
    assertThat(AccountingAccounts.code("")).isEmpty();
    assertThat(AccountingAccounts.code(null)).isEmpty();
    assertThat(AccountingAccounts.code("A".repeat(40))).isPresent();
    assertThat(AccountingAccounts.code("A".repeat(41))).isEmpty();
  }

  @Test
  void anInvalidCodeIsRefusedWithoutEchoingIt() {
    assertThatThrownBy(() -> accounts("-SECRET"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("SECRET");
    assertThatThrownBy(() -> accounts(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toStringIsRedacted() {
    assertThat(accounts("6100").toString()).doesNotContain("6100");
  }
}
