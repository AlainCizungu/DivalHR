package com.divalhr.core.people.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.ImportRow;
import com.divalhr.core.people.domain.RowError;
import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowValues;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-020 (E9): preview digest v1, pinned by a golden vector. */
class PreviewDigestTest {

  @Test
  void theCanonicalTextAndDigestArePinned() {
    List<ImportRow> rows =
        List.of(
            new ImportRow(
                1,
                List.of(),
                new RowValues(
                    "E-001",
                    "Élodie",
                    "N’Kanza",
                    LocalDate.of(2026, 3, 1),
                    "LE-1",
                    "ST-1",
                    "DP-1",
                    null,
                    "TM-1")),
            new ImportRow(
                2,
                List.of(
                    new RowError(ImportColumn.EMPLOYEE_NUMBER, RowErrorCode.ROW_FORMAT),
                    new RowError(ImportColumn.START_DATE, RowErrorCode.ROW_DATE_FORMAT)),
                null));
    String text = PreviewDigest.text(rows);
    assertThat(text)
        .isEqualTo(
            "DIVALHR-EMPLOYEE-IMPORT-PREVIEW\nversion=1\nrows=2\n"
                + "r=1|VALID|E-001|Élodie|N’Kanza|2026-03-01|LE-1|ST-1|DP-1|-|TM-1\n"
                + "r=2|INVALID|employee_number:ROW_FORMAT,start_date:ROW_DATE_FORMAT\n");
    assertThat(PreviewDigest.of(rows))
        .isEqualTo(com.divalhr.core.platform.idempotency.Fingerprints.sha256(text))
        .matches("^[0-9a-f]{64}$");
  }
}
