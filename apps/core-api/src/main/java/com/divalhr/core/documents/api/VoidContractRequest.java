package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Voiding of an issued contract (MVP-030). Mirrors {@code VoidContract}. Values stay raw JSON
 * values until the service validates them; they are never logged.
 */
@Schema(name = "VoidContract")
public class VoidContractRequest extends StrictRequest {

  private Object expectedVersion;
  private Object reasonCode;

  /**
   * Expected row version.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets: expected row version.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  /**
   * Closed reason code.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonCode() {
    return reasonCode;
  }

  /**
   * Sets: closed reason code.
   *
   * @param reasonCode raw value
   */
  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }
}
