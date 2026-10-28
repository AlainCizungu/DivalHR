package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A command that needs only the version the caller saw: submit, withdraw or approve (MVP-081).
 */
@Schema(name = "ExpenseVersionRequest")
public class ExpenseVersionRequest extends StrictRequest {
  private Object expectedVersion;

  /**
   * The raw expectedVersion.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets the raw expectedVersion.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }
}
