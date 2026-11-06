package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.AccountingCsv;
import com.divalhr.core.payroll.domain.AccountingDirection;
import com.divalhr.core.payroll.domain.AccountingFileNames;
import com.divalhr.core.payroll.domain.AccountingLine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-096: the CSV is byte-exact and deterministic; file names are strict. */
class AccountingCsvTest {

  private static final UUID JOURNAL = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID PACKAGE = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID ENTITY = UUID.fromString("00000000-0000-4000-8000-000000000003");

  private static byte[] csv(List<AccountingLine> lines) {
    return AccountingCsv.csv(JOURNAL, PACKAGE, ENTITY, "CDF", lines);
  }

  @Test
  void theBytesAreExactlyBomHeaderAndCrlfRows() {
    List<AccountingLine> lines =
        List.of(
            new AccountingLine(1, "2100", AccountingDirection.CREDIT, 150, null, null),
            new AccountingLine(2, "6100", AccountingDirection.DEBIT, 1000, null, null));
    String expected =
        "﻿"
            + AccountingCsv.HEADER
            + "\r\n"
            + "00000000-0000-4000-8000-000000000001,00000000-0000-4000-8000-000000000002,"
            + "00000000-0000-4000-8000-000000000003,CDF,1,2100,CREDIT,150,,\r\n"
            + "00000000-0000-4000-8000-000000000001,00000000-0000-4000-8000-000000000002,"
            + "00000000-0000-4000-8000-000000000003,CDF,2,6100,DEBIT,1000,,\r\n";
    assertThat(new String(csv(lines), StandardCharsets.UTF_8)).isEqualTo(expected);
    assertThat(csv(lines)[0]).isEqualTo((byte) 0xEF);
    assertThat(csv(lines)[1]).isEqualTo((byte) 0xBB);
    assertThat(csv(lines)[2]).isEqualTo((byte) 0xBF);
  }

  @Test
  void theSameInputGivesTheSameBytes() {
    List<AccountingLine> lines =
        List.of(new AccountingLine(1, "6100", AccountingDirection.DEBIT, 5, null, null));
    assertThat(csv(lines)).isEqualTo(csv(lines));
  }

  @Test
  void theHeaderHasTheTenFixedColumns() {
    assertThat(AccountingCsv.HEADER.split(",")).hasSize(10);
    assertThat(AccountingCsv.HEADER).startsWith("journal_id,package_id,legal_entity_id,currency");
    assertThat(AccountingCsv.HEADER).endsWith("cost_center_code,site_code");
  }

  @Test
  void optionalCodesAreQuotedOnlyWhenTheyNeedIt() {
    List<AccountingLine> lines =
        List.of(new AccountingLine(1, "6100", AccountingDirection.DEBIT, 5, "C,1", "S\"1"));
    String text = new String(csv(lines), StandardCharsets.UTF_8);
    assertThat(text).endsWith(",5,\"C,1\",\"S\"\"1\"\r\n");
  }

  @Test
  void fileNamesAreDeterministicAndStrict() {
    assertThat(AccountingFileNames.csv("ENT-1", "2026-01", "CDF", 2))
        .isEqualTo("accounting-journal-ENT-1-2026-01-CDF-v2.csv");
    assertThat(AccountingFileNames.manifest("ENT-1", "2026-01", "USD", 1))
        .isEqualTo("accounting-journal-ENT-1-2026-01-USD-v1.manifest.json");
    assertThatThrownBy(() -> AccountingFileNames.csv("../x", "2026-01", "CDF", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountingFileNames.csv("ENT-1", "2026-13", "CDF", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountingFileNames.csv("ENT-1", "2026-01", "EUR", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
