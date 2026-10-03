package com.divalhr.core.people.domain;

import java.util.Objects;

/**
 * One row error: a column key and a stable code, never a value.
 *
 * @param column the column
 * @param code the code
 */
public record RowError(ImportColumn column, RowErrorCode code) {

  /** Requires both components. */
  public RowError {
    Objects.requireNonNull(column, "column");
    Objects.requireNonNull(code, "code");
  }
}
