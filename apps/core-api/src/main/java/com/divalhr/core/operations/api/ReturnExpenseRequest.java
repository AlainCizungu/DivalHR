package com.divalhr.core.operations.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The routed manager's return of an expense with its reason (MVP-081, D81-6).
 */
@Schema(name = "ReturnExpenseRequest")
public class ReturnExpenseRequest extends StrictRequest {
  private Object expectedVersion;
  private Object reason;

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

  /**
   * The raw reason.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReason() {
    return reason;
  }

  /**
   * Sets the raw reason.
   *
   * @param reason raw value
   */
  public void setReason(Object reason) {
    this.reason = reason;
  }
}
