package com.divalhr.core.documents.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Approval of a draft version (MVP-030). Mirrors {@code ApproveContractTemplateVersion}. Values
 * stay raw JSON values until the service validates them; they are never logged.
 */
@Schema(name = "ApproveContractTemplateVersion")
public class ApproveContractTemplateVersionRequest extends StrictRequest {

  private Object expectedVersion;
  private Object acknowledgements;

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
   * Confirmations (TEXT_VERIFIED).
   *
   * @return raw value
   */
  @Schema(type = "array")
  public Object getAcknowledgements() {
    return acknowledgements;
  }

  /**
   * Sets: confirmations (TEXT_VERIFIED).
   *
   * @param acknowledgements raw value
   */
  public void setAcknowledgements(Object acknowledgements) {
    this.acknowledgements = acknowledgements;
  }
}
