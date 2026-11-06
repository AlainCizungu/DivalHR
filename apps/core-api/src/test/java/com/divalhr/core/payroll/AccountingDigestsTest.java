package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.AccountingAccounts;
import com.divalhr.core.payroll.domain.AccountingCurrency;
import com.divalhr.core.payroll.domain.AccountingDigests;
import com.divalhr.core.payroll.domain.AccountingDigests.JournalFacts;
import com.divalhr.core.payroll.domain.AccountingDirection;
import com.divalhr.core.payroll.domain.AccountingLine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-096: digests are stable, sensitive to every input and never expose their inputs. */
class AccountingDigestsTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID ENTITY = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final Instant EXPIRES = Instant.parse("2026-02-01T10:15:00Z");

  private static AccountingAccounts accounts(String salary) {
    return new AccountingAccounts(salary, "2100", "6200", "2200", "2300");
  }

  private static String mapping(AccountingCurrency currency, int version, String salary) {
    return AccountingDigests.mapping(
        TENANT, ENTITY, currency, version, LocalDate.parse("2026-01-01"), accounts(salary));
  }

  private static JournalFacts facts(long debits) {
    return new JournalFacts(
        TENANT,
        ENTITY,
        ENTITY,
        1,
        "a".repeat(64),
        "b".repeat(64),
        ENTITY,
        1,
        "c".repeat(64),
        "CDF",
        5,
        debits,
        debits);
  }

  @Test
  void sha256IsLowerCaseHexOfSixtyFourCharacters() {
    String digest = AccountingDigests.sha256("abc".getBytes(StandardCharsets.UTF_8));
    assertThat(digest)
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void theMappingDigestIsStableAndSensitiveToEveryInput() {
    String base = mapping(AccountingCurrency.CDF, 1, "6100");
    assertThat(base).hasSize(64).isEqualTo(mapping(AccountingCurrency.CDF, 1, "6100"));
    assertThat(mapping(AccountingCurrency.USD, 1, "6100")).isNotEqualTo(base);
    assertThat(mapping(AccountingCurrency.CDF, 2, "6100")).isNotEqualTo(base);
    assertThat(mapping(AccountingCurrency.CDF, 1, "6101")).isNotEqualTo(base);
  }

  @Test
  void thePreviewDigestDependsOnTheExpiry() {
    String mapping = mapping(AccountingCurrency.CDF, 1, "6100");
    String first = AccountingDigests.mappingPreview(mapping, null, EXPIRES);
    assertThat(AccountingDigests.mappingPreview(mapping, null, EXPIRES)).isEqualTo(first);
    assertThat(AccountingDigests.mappingPreview(mapping, null, EXPIRES.plusSeconds(900)))
        .isNotEqualTo(first);
    assertThat(AccountingDigests.mappingPreview(mapping, LocalDate.parse("2025-01-01"), EXPIRES))
        .isNotEqualTo(first);
  }

  @Test
  void theJournalPreviewDependsOnTheFactsAndTheExpiry() {
    String first = AccountingDigests.journalPreview(facts(1100), EXPIRES);
    assertThat(AccountingDigests.journalPreview(facts(1100), EXPIRES)).isEqualTo(first);
    assertThat(AccountingDigests.journalPreview(facts(1101), EXPIRES)).isNotEqualTo(first);
    assertThat(AccountingDigests.journalPreview(facts(1100), EXPIRES.plusSeconds(900)))
        .isNotEqualTo(first);
  }

  @Test
  void theJournalDigestDependsOnTheCsvAndTheLines() {
    UUID journal = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    String lines =
        AccountingDigests.lines(
            List.of(new AccountingLine(1, "6100", AccountingDirection.DEBIT, 5, null, null)));
    String first = AccountingDigests.journal(journal, 1, facts(5), lines, "d".repeat(64));
    assertThat(AccountingDigests.journal(journal, 1, facts(5), lines, "d".repeat(64)))
        .isEqualTo(first);
    assertThat(AccountingDigests.journal(journal, 1, facts(5), lines, "e".repeat(64)))
        .isNotEqualTo(first);
    assertThat(AccountingDigests.journal(journal, 2, facts(5), lines, "d".repeat(64)))
        .isNotEqualTo(first);
  }

  @Test
  void theFactsAreRedactedInText() {
    assertThat(facts(123456).toString()).doesNotContain("123456");
  }
}
