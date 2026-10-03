package com.divalhr.core.people.domain;

/**
 * Stable row error codes of the employee import (MVP-020). Clients translate them; they never carry
 * the submitted value. The database accepts exactly this set ({@code
 * employee_import_row_error_codes_known}).
 */
public enum RowErrorCode {
  /** The row has another number of cells than the header. */
  ROW_SHAPE,
  /** A required value is missing. */
  ROW_REQUIRED,
  /** A value is longer than allowed (Unicode code points after NFC normalization). */
  ROW_TOO_LONG,
  /** A value contains a control or invisible formatting character, a line break included. */
  ROW_CONTROL_CHARACTER,
  /** A value does not match its allow-list. */
  ROW_FORMAT,
  /** A date is not written YYYY-MM-DD or does not exist. */
  ROW_DATE_FORMAT,
  /** A date is outside 1900-01-01 to 2999-12-31. */
  ROW_DATE_RANGE,
  /** The employee number appears more than once in the file. */
  ROW_EMPLOYEE_NUMBER_REPEATED,
  /** The employee number already belongs to an employee of the organization. */
  ROW_EMPLOYEE_NUMBER_EXISTS,
  /** The unit code is unknown in the organization. */
  ROW_UNIT_NOT_FOUND,
  /** The unit does not belong to the given parent unit. */
  ROW_UNIT_MISMATCH,
  /** The unit is not effective on the start date. */
  ROW_UNIT_NOT_EFFECTIVE,
  /** Both a department and a cost center are given. */
  ROW_PARENT_AMBIGUOUS
}
