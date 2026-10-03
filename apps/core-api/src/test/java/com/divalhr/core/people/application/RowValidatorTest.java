package com.divalhr.core.people.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.RowErrorCode;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MVP-020 (E10, A20-6): row normalization, allow-lists and Unicode code-point limits. */
class RowValidatorTest {

  private final RowValidator validator = new RowValidator();

  private static Map<ImportColumn, String> base() {
    Map<ImportColumn, String> cells = new EnumMap<>(ImportColumn.class);
    cells.put(ImportColumn.EMPLOYEE_NUMBER, "e-001");
    cells.put(ImportColumn.GIVEN_NAMES, "Ana");
    cells.put(ImportColumn.FAMILY_NAME, "Mbuyi");
    cells.put(ImportColumn.START_DATE, "2026-01-01");
    cells.put(ImportColumn.LEGAL_ENTITY_CODE, "le-1");
    cells.put(ImportColumn.SITE_CODE, "st-1");
    return cells;
  }

  private RowErrorCode problem(ImportColumn column, String value) {
    Map<ImportColumn, String> cells = base();
    cells.put(column, value);
    var row = validator.check(new ImportFile.RawRow(1, cells, false)).row();
    return row.valid() ? null : row.errors().getFirst().code();
  }

  @Test
  void valuesAreNormalizedAndCodesUpperCased() {
    Map<ImportColumn, String> cells = base();
    cells.put(ImportColumn.GIVEN_NAMES, "  Jean  Pierre ");
    cells.put(ImportColumn.FAMILY_NAME, "élodie");
    var checked = validator.check(new ImportFile.RawRow(1, cells, false));
    assertThat(checked.row().valid()).isTrue();
    assertThat(checked.row().values().givenNames()).isEqualTo("Jean Pierre");
    assertThat(checked.row().values().familyName()).isEqualTo("élodie").hasSize(6);
    assertThat(checked.row().values().employeeNumber()).isEqualTo("E-001");
    assertThat(checked.row().values().legalEntityCode()).isEqualTo("LE-1");
    assertThat(checked.employeeNumber()).isEqualTo("E-001");
  }

  @Test
  void formulaPrefixesAndControlCharactersAreRejectedInEveryField() {
    for (String payload : new String[] {"=1+1", "+1", "-1", "@SUM(A1)", "=cmd|' /C calc'!A0"}) {
      for (ImportColumn column : ImportColumn.values()) {
        if (column == ImportColumn.START_DATE) {
          assertThat(problem(column, payload)).isEqualTo(RowErrorCode.ROW_DATE_FORMAT);
        } else {
          assertThat(problem(column, payload))
              .as("%s %s", column, payload)
              .isEqualTo(RowErrorCode.ROW_FORMAT);
        }
      }
    }
    for (String control : new String[] {"a\tb", "a\rb", "a\nb", "a\u0000b", "a‮b", "a​b", "a b"}) {
      assertThat(problem(ImportColumn.GIVEN_NAMES, control))
          .isEqualTo(RowErrorCode.ROW_CONTROL_CHARACTER);
    }
  }

  @Test
  void lengthsCountCodePointsAfterNfc() {
    assertThat(problem(ImportColumn.FAMILY_NAME, "é".repeat(100))).isNull();
    assertThat(problem(ImportColumn.FAMILY_NAME, "é".repeat(100))).isNull();
    assertThat(problem(ImportColumn.FAMILY_NAME, "é".repeat(101)))
        .isEqualTo(RowErrorCode.ROW_TOO_LONG);
    // A supplementary-plane letter is one code point (two UTF-16 units).
    String gothic = new String(Character.toChars(0x10330));
    assertThat(problem(ImportColumn.FAMILY_NAME, gothic.repeat(100))).isNull();
    assertThat(problem(ImportColumn.FAMILY_NAME, gothic.repeat(101)))
        .isEqualTo(RowErrorCode.ROW_TOO_LONG);
    assertThat(problem(ImportColumn.EMPLOYEE_NUMBER, "A".repeat(161)))
        .isEqualTo(RowErrorCode.ROW_TOO_LONG);
    assertThat(problem(ImportColumn.EMPLOYEE_NUMBER, "A".repeat(33)))
        .isEqualTo(RowErrorCode.ROW_FORMAT);
  }

  @Test
  void datesAreIsoOnlyAndInRange() {
    assertThat(problem(ImportColumn.START_DATE, "31/12/2026"))
        .isEqualTo(RowErrorCode.ROW_DATE_FORMAT);
    assertThat(problem(ImportColumn.START_DATE, "2026-02-29"))
        .isEqualTo(RowErrorCode.ROW_DATE_FORMAT);
    assertThat(problem(ImportColumn.START_DATE, "2028-02-29")).isNull();
    assertThat(problem(ImportColumn.START_DATE, "1899-12-31"))
        .isEqualTo(RowErrorCode.ROW_DATE_RANGE);
    assertThat(problem(ImportColumn.START_DATE, "３０２６-01-01"))
        .isEqualTo(RowErrorCode.ROW_DATE_FORMAT);
  }

  @Test
  void requiredValuesAndAmbiguousParents() {
    assertThat(problem(ImportColumn.GIVEN_NAMES, "   ")).isEqualTo(RowErrorCode.ROW_REQUIRED);
    Map<ImportColumn, String> cells = base();
    cells.put(ImportColumn.DEPARTMENT_CODE, "DP-1");
    cells.put(ImportColumn.COST_CENTER_CODE, "CC-1");
    var row = validator.check(new ImportFile.RawRow(1, cells, false)).row();
    assertThat(row.errors())
        .extracting(e -> e.code())
        .containsExactly(RowErrorCode.ROW_PARENT_AMBIGUOUS);
    var shape = validator.check(new ImportFile.RawRow(2, Map.of(), true)).row();
    assertThat(shape.errors()).extracting(e -> e.code()).containsExactly(RowErrorCode.ROW_SHAPE);
  }
}
