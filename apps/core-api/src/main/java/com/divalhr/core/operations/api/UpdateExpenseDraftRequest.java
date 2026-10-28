package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The employee's replacement of the editable revision (MVP-081, D81-10). */
@Schema(name = "UpdateExpenseDraftRequest")
public class UpdateExpenseDraftRequest extends ExpenseDraftRequest {
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
