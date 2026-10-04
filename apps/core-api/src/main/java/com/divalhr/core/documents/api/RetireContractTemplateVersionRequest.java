package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Retirement of an approved version (MVP-030). Mirrors {@code RetireContractTemplateVersion}.
 * Values stay raw JSON values until the service validates them; they are never logged.
 */
@Schema(name = "RetireContractTemplateVersion")
public class RetireContractTemplateVersionRequest extends StrictRequest {

  private Object expectedVersion;

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
}
