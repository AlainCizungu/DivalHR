package com.divalhr.core.people.domain;

/**
 * Outcome of one import row (MVP-020). {@code VALID} and {@code INVALID} describe an open import;
 * once it closes, rows are {@code CREATED} or {@code NOT_IMPORTED}.
 */
public enum RowStatus {
  /** Will be created on commit. */
  VALID,
  /** Has errors and will not be imported. */
  INVALID,
  /** An employee was created from the row (no link to it is kept). */
  CREATED,
  /** Not imported: invalid, or the import was discarded or expired. */
  NOT_IMPORTED
}
