package com.divalhr.core.people.domain;

import java.util.List;

/**
 * One validated row of an open import.
 *
 * @param rowNumber 1-based data row number
 * @param errors errors in column order (at most 10); empty for a valid row
 * @param values normalized values of a valid row, or {@code null}
 */
public record ImportRow(int rowNumber, List<RowError> errors, RowValues values) {

  /** At most ten errors are kept per row. */
  public static final int MAX_ERRORS = 10;

  /** Copies the errors and requires values exactly for valid rows. */
  public ImportRow {
    errors = List.copyOf(errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors);
    if (rowNumber < 1) {
      throw new IllegalArgumentException("row number");
    }
    if (errors.isEmpty() != (values != null)) {
      throw new IllegalArgumentException("values exactly for valid rows");
    }
  }

  /**
   * Whether the row is valid.
   *
   * @return true without errors
   */
  public boolean valid() {
    return errors.isEmpty();
  }

  /**
   * The row with more errors, its values dropped.
   *
   * @param more additional errors
   * @return an invalid row
   */
  public ImportRow withErrors(List<RowError> more) {
    if (more.isEmpty()) {
      return this;
    }
    List<RowError> all = new java.util.ArrayList<>(errors);
    all.addAll(more);
    return new ImportRow(rowNumber, all, null);
  }

  @Override
  public String toString() {
    return "ImportRow[" + rowNumber + ", errors=" + errors + "]";
  }
}
