package com.divalhr.core.people.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.internal.CommonsCsvRecordSource;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * MVP-020 (A20-3): the accepted CSV subset around Apache Commons CSV, including random input that
 * may only produce a file or {@code IMPORT_FILE_INVALID}, never another failure.
 */
class ImportFileReaderTest {

  private final ImportFileReader reader = new ImportFileReader(new CommonsCsvRecordSource());

  /** Fixed seed: the fuzz inputs are reproducible. */
  private final Random random = new Random(20261003L);

  private static final String HEADER =
      "Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site";

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private String reason(String text) {
    return reason(bytes(text));
  }

  private String reason(byte[] bytes) {
    ApiException invalid = catchThrowableOfType(ApiException.class, () -> reader.read(bytes, 1000));
    assertThat(invalid.code()).isIn(ErrorCode.IMPORT_FILE_INVALID);
    return String.valueOf(invalid.params().get("reason"));
  }

  @Test
  void semicolonAndCommaFilesWithCrlfBomAndBlankLinesAreRead() {
    ImportFile semicolon =
        reader.read(bytes(HEADER + "\r\nA;B;C;2026-01-01;LE;ST\r\n;;;;;\r\n\r\n"), 10);
    assertThat(semicolon.delimiter()).isEqualTo("SEMICOLON");
    assertThat(semicolon.headerLanguage()).isEqualTo("fr");
    assertThat(semicolon.rows()).hasSize(1);
    assertThat(semicolon.rows().getFirst().cells().get(ImportColumn.GIVEN_NAMES)).isEqualTo("B");
    ImportFile comma =
        reader.read(
            bytes(
                "﻿Employee number,Given names,Family name,Start date,Legal entity code,Site"
                    + " code\n\"A\",\"B, with comma\",\"C \"\"q\"\"\",2026-01-01,LE,ST\n"),
            10);
    assertThat(comma.delimiter()).isEqualTo("COMMA");
    assertThat(comma.rows().getFirst().cells().get(ImportColumn.GIVEN_NAMES))
        .isEqualTo("B, with comma");
    assertThat(comma.rows().getFirst().cells().get(ImportColumn.FAMILY_NAME)).isEqualTo("C \"q\"");
  }

  @Test
  void fileLevelProblemsHaveStableReasons() {
    assertThat(reason("")).isEqualTo("EMPTY");
    assertThat(reason(new byte[] {(byte) 0xC3, (byte) 0x28})).isEqualTo("ENCODING");
    assertThat(reason(HEADER + ";Salaire\nA;B;C;D;E;F;G\n")).isEqualTo("COLUMN_UNKNOWN");
    assertThat(reason(HEADER + ";Matricule\nA;B;C;D;E;F;G\n")).isEqualTo("COLUMN_DUPLICATE");
    assertThat(reason("Matricule;Prénoms\nA;B\n")).isEqualTo("COLUMN_MISSING");
    assertThat(reason(HEADER + "\n\"abc\"def;B;C;D;E;F\n")).isEqualTo("MALFORMED");
    assertThat(reason(HEADER + "\n\"unterminated;B;C;D;E;F\n")).isEqualTo("MALFORMED");
    assertThat(reason(HEADER + "\n" + "x".repeat(4097) + "\n")).isEqualTo("LINE_TOO_LONG");
    assertThat(reason(HEADER + "\n" + "a;".repeat(25) + "\n")).isEqualTo("TOO_MANY_COLUMNS");
    assertThat(reason(HEADER + "\n")).isEqualTo("NO_ROWS");
    StringBuilder many = new StringBuilder(HEADER).append('\n');
    for (int i = 0; i < 1001; i++) {
      many.append("A;B;C;D;E;F\n");
    }
    assertThat(reason(many.toString())).isEqualTo("TOO_MANY_ROWS");
    // A header valid with both delimiters cannot exist (at least six columns are required), and a
    // header with neither reports the problem of the likelier delimiter.
    assertThat(reason("only-one-cell\nx\n")).isEqualTo("COLUMN_UNKNOWN");
  }

  @Test
  void rowsWithAnotherNumberOfCellsAreMarkedNotRejected() {
    ImportFile file = reader.read(bytes(HEADER + "\nA;B\nA;B;C;D;E;F\n"), 10);
    assertThat(file.rows().get(0).shapeError()).isTrue();
    assertThat(file.rows().get(1).shapeError()).isFalse();
    assertThat(file.rows().get(1).rowNumber()).isEqualTo(2);
  }

  @Test
  void quotedLineBreaksReachTheRowRulesAsControlCharacters() {
    ImportFile file = reader.read(bytes(HEADER + "\nA;\"B\nC\";C;D;E;F\n"), 10);
    assertThat(file.rows()).hasSize(1);
    assertThat(
            RowValidator.hasControlCharacter(
                file.rows().getFirst().cells().get(ImportColumn.GIVEN_NAMES)))
        .isTrue();
  }

  @Test
  void headerLabelsNeverCollideAfterNormalization() {
    Set<String> seen = new HashSet<>();
    for (ImportColumn column : ImportColumn.values()) {
      assertThat(seen.add(ImportColumn.normalizeLabel(column.label(true)))).isTrue();
      String english = ImportColumn.normalizeLabel(column.label(false));
      assertThat(english.equals(ImportColumn.normalizeLabel(column.key())) || seen.add(english))
          .isTrue();
      seen.add(english);
    }
    assertThat(ImportColumn.match("Nom")).isEmpty();
    assertThat(ImportColumn.match("  DATE D'ENTREE ")).isPresent();
  }

  @Test
  void randomInputIsEitherAFileOrAFileLevelProblem() {
    String alphabet = "ab;,\"\n\r\t é’=+-@\u0000‮";
    for (int i = 0; i < 2000; i++) {
      byte[] data;
      if (i % 3 == 0) {
        data = new byte[random.nextInt(300)];
        random.nextBytes(data);
      } else {
        StringBuilder text = new StringBuilder(i % 2 == 0 ? HEADER + "\n" : "");
        int length = random.nextInt(400);
        for (int j = 0; j < length; j++) {
          text.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        data = bytes(text.toString());
      }
      try {
        ImportFile file = reader.read(data, 1000);
        assertThat(file.rows()).isNotEmpty();
      } catch (ApiException invalid) {
        assertThat(invalid.code()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID);
      }
    }
  }

  private static final int MAX_BYTES = 2 * 1024 * 1024;

  /** Bytes allocated by this thread while reading, or -1 when the JVM cannot measure it. */
  private long allocatedWhileReading(byte[] data) {
    if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean threads)
        || !threads.isThreadAllocatedMemorySupported()) {
      return -1;
    }
    threads.setThreadAllocatedMemoryEnabled(true);
    long before = threads.getCurrentThreadAllocatedBytes();
    try {
      reader.read(data, 1000);
    } catch (ApiException invalid) {
      assertThat(invalid.code()).isEqualTo(ErrorCode.IMPORT_FILE_INVALID);
    }
    return threads.getCurrentThreadAllocatedBytes() - before;
  }

  private static byte[] fill(String head, String unit, String tail) {
    StringBuilder text = new StringBuilder(head);
    int budget = MAX_BYTES - bytes(head).length - bytes(tail).length;
    int unitBytes = bytes(unit).length;
    for (int used = 0; used + unitBytes <= budget; used += unitBytes) {
      text.append(unit);
    }
    byte[] data = bytes(text.append(tail).toString());
    assertThat(data.length).isLessThanOrEqualTo(MAX_BYTES);
    return data;
  }

  @Test
  void worstCaseFilesAtTheTransportCapStayWithinBoundedMemory() {
    String longName = "é".repeat(160);
    String row = "N;" + longName + ";" + longName + ";2026-01-01;LE;ST\n";
    StringBuilder rows = new StringBuilder(HEADER + "\n");
    for (int i = 0; i < 1000 && bytes(rows + row).length <= MAX_BYTES; i++) {
      rows.append(row);
    }
    byte[] manyRows = bytes(rows.toString());
    assertThat(reader.read(manyRows, 1000).rows()).hasSizeGreaterThan(900);
    byte[][] worst = {
      manyRows,
      // One line of 2 MiB without a line break.
      fill(HEADER + "\nA;", "x", ""),
      // A quoted field that never closes, spread over short lines.
      fill(HEADER + "\nA;\"", "xxxxxxxxxxxxxxx\n", ""),
      // Only blank lines.
      fill(HEADER + "\n", "\r\n", ""),
      // Rows made of separators only.
      fill(HEADER + "\n", ";;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;\n", ""),
      // Rows of 1,000 one-character cells.
      fill(HEADER + "\n", "a;".repeat(1000) + "\n", ""),
    };
    for (byte[] data : worst) {
      long allocated = allocatedWhileReading(data);
      // Linear in the input (at most 64 bytes, mostly short-lived, per input byte; about 38 for
      // the worst case of a million blank lines), so bounded by the 2 MiB transport cap.
      assertThat(allocated).isLessThan(64L * data.length);
    }
  }
}
