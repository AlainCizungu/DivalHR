package com.divalhr.core.platform.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-020 (E10): the output rule for every CSV DivalHR produces. */
class CsvCellsTest {

  @Test
  void formulaPrefixesAreNeutralizedAndEveryCellQuoted() {
    assertThat(CsvCells.cell("=1+1")).isEqualTo("\"'=1+1\"");
    assertThat(CsvCells.cell("+1")).isEqualTo("\"'+1\"");
    assertThat(CsvCells.cell("-1")).isEqualTo("\"'-1\"");
    assertThat(CsvCells.cell("@SUM")).isEqualTo("\"'@SUM\"");
    assertThat(CsvCells.cell("\tx")).isEqualTo("\"'\tx\"");
    assertThat(CsvCells.cell("a\"b")).isEqualTo("\"a\"\"b\"");
    assertThat(CsvCells.cell(null)).isEqualTo("\"\"");
    assertThat(CsvCells.line(List.of("a", "b"), ';')).isEqualTo("\"a\";\"b\"\r\n");
  }
}
