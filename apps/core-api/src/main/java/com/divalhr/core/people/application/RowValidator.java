package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.ImportRow;
import com.divalhr.core.people.domain.RowError;
import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowValues;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Row-level validation and normalization of the employee import (MVP-020, E10, A20-6). Per cell, in
 * this order: control and invisible formatting characters (line breaks included) are rejected; the
 * value is normalized (NFC, every space separator to a space, runs of spaces collapsed, trimmed);
 * an empty value is absent; the length is checked in Unicode code points; then the column's
 * allow-list. Every allow-list starts with a letter or digit, so no stored value can begin with a
 * spreadsheet formula prefix ({@code = + - @}, tab or carriage return).
 */
@Component
public class RowValidator {

  /** Longest accepted cell, in Unicode code points after NFC normalization. */
  public static final int MAX_CELL_CODE_POINTS = 160;

  /** Longest accepted name part, in Unicode code points after NFC normalization. */
  public static final int MAX_NAME_CODE_POINTS = 100;

  private static final Pattern EMPLOYEE_NUMBER = Pattern.compile("^[A-Z0-9][A-Z0-9._/-]{0,31}$");

  /**
   * Unit codes as the hierarchy defines them, except that a code referenced by an import must start
   * with a letter or digit (a unit whose code starts with {@code -} or {@code _} cannot be imported
   * into; E10).
   */
  private static final Pattern UNIT_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,19}$");

  /** Name grammar; V13 people.person_name_valid mirrors it (EmployeeNameGrammarDriftTest). */
  static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L}\\p{M} '’.-]*$");

  private static final Pattern ISO_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);
  private static final LocalDate FIRST_DAY = LocalDate.of(1900, 1, 1);
  private static final LocalDate LAST_DAY = LocalDate.of(2999, 12, 31);

  /**
   * A row after its own checks, with the parsed employee number for the cross-row check.
   *
   * @param row the row
   * @param employeeNumber the normalized employee number when it is well formed, else {@code null}
   */
  public record Checked(ImportRow row, String employeeNumber) {}

  /**
   * Validates one raw row.
   *
   * @param raw raw row
   * @return the checked row
   */
  public Checked check(ImportFile.RawRow raw) {
    if (raw.shapeError()) {
      return new Checked(
          new ImportRow(
              raw.rowNumber(),
              List.of(new RowError(ImportColumn.EMPLOYEE_NUMBER, RowErrorCode.ROW_SHAPE)),
              null),
          null);
    }
    List<RowError> errors = new ArrayList<>();
    Map<ImportColumn, String> values = new EnumMap<>(ImportColumn.class);
    for (ImportColumn column : ImportColumn.values()) {
      String raw1 = raw.cells().get(column);
      if (raw1 == null) {
        if (column.required()) {
          errors.add(new RowError(column, RowErrorCode.ROW_REQUIRED));
        }
        continue;
      }
      RowErrorCode problem = null;
      String value = null;
      if (hasControlCharacter(raw1)) {
        problem = RowErrorCode.ROW_CONTROL_CHARACTER;
      } else {
        value = normalize(raw1);
        if (value.isEmpty()) {
          problem = column.required() ? RowErrorCode.ROW_REQUIRED : null;
          value = null;
        } else if (value.codePointCount(0, value.length()) > MAX_CELL_CODE_POINTS) {
          problem = RowErrorCode.ROW_TOO_LONG;
        } else {
          value = canonical(column, value);
          problem = formatProblem(column, value);
        }
      }
      if (problem != null) {
        errors.add(new RowError(column, problem));
      } else if (value != null) {
        values.put(column, value);
      }
    }
    if (values.containsKey(ImportColumn.DEPARTMENT_CODE)
        && values.containsKey(ImportColumn.COST_CENTER_CODE)) {
      errors.add(new RowError(ImportColumn.COST_CENTER_CODE, RowErrorCode.ROW_PARENT_AMBIGUOUS));
    }
    String employeeNumber = values.get(ImportColumn.EMPLOYEE_NUMBER);
    if (!errors.isEmpty()) {
      errors.sort((a, b) -> a.column().compareTo(b.column()));
      return new Checked(new ImportRow(raw.rowNumber(), errors, null), employeeNumber);
    }
    RowValues rowValues =
        new RowValues(
            employeeNumber,
            values.get(ImportColumn.GIVEN_NAMES),
            values.get(ImportColumn.FAMILY_NAME),
            LocalDate.parse(values.get(ImportColumn.START_DATE), DATE),
            values.get(ImportColumn.LEGAL_ENTITY_CODE),
            values.get(ImportColumn.SITE_CODE),
            values.get(ImportColumn.DEPARTMENT_CODE),
            values.get(ImportColumn.COST_CENTER_CODE),
            values.get(ImportColumn.TEAM_CODE));
    return new Checked(new ImportRow(raw.rowNumber(), List.of(), rowValues), employeeNumber);
  }

  /**
   * Whether the value contains a control character (C0, C1, line breaks), an invisible formatting
   * character (such as bidirectional overrides or zero-width characters) or a line or paragraph
   * separator.
   *
   * @param value raw cell
   * @return true when rejected
   */
  static boolean hasControlCharacter(String value) {
    return value
        .codePoints()
        .anyMatch(
            cp -> {
              int type = Character.getType(cp);
              return type == Character.CONTROL
                  || type == Character.FORMAT
                  || type == Character.LINE_SEPARATOR
                  || type == Character.PARAGRAPH_SEPARATOR
                  || type == Character.UNASSIGNED
                  || type == Character.SURROGATE
                  || type == Character.PRIVATE_USE;
            });
  }

  /**
   * NFC, every space separator to a plain space, runs collapsed, trimmed.
   *
   * @param value raw cell without control characters
   * @return normalized value
   */
  static String normalize(String value) {
    String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
    return nfc.replaceAll("\\p{Zs}+", " ").strip();
  }

  private static String canonical(ImportColumn column, String value) {
    return switch (column) {
      case GIVEN_NAMES, FAMILY_NAME, START_DATE -> value;
      default -> value.toUpperCase(Locale.ROOT);
    };
  }

  private static RowErrorCode formatProblem(ImportColumn column, String value) {
    return switch (column) {
      case EMPLOYEE_NUMBER ->
          EMPLOYEE_NUMBER.matcher(value).matches() ? null : RowErrorCode.ROW_FORMAT;
      case GIVEN_NAMES, FAMILY_NAME -> {
        if (value.codePointCount(0, value.length()) > MAX_NAME_CODE_POINTS) {
          yield RowErrorCode.ROW_TOO_LONG;
        }
        yield NAME.matcher(value).matches() ? null : RowErrorCode.ROW_FORMAT;
      }
      case START_DATE -> dateProblem(value);
      case LEGAL_ENTITY_CODE, SITE_CODE, DEPARTMENT_CODE, COST_CENTER_CODE, TEAM_CODE ->
          UNIT_CODE.matcher(value).matches() ? null : RowErrorCode.ROW_FORMAT;
    };
  }

  private static RowErrorCode dateProblem(String value) {
    if (!ISO_DATE.matcher(value).matches()) {
      return RowErrorCode.ROW_DATE_FORMAT;
    }
    LocalDate date;
    try {
      date = LocalDate.parse(value, DATE);
    } catch (DateTimeParseException notADate) {
      return RowErrorCode.ROW_DATE_FORMAT;
    }
    return date.isBefore(FIRST_DAY) || date.isAfter(LAST_DAY) ? RowErrorCode.ROW_DATE_RANGE : null;
  }
}
