package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Separation cancellation command (MVP-022). Mirrors {@code CancelSeparation}. */
@Schema(name = "CancelSeparation")
public class CancelSeparationRequest extends StrictRequest {

  private Object expectedVersion;
  private Object cancellationDigest;

  /**
   * Employment version from the cancellation preview.
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  /**
   * Sets: employment version from the cancellation preview.
   *
   * @param expectedVersion raw value
   */
  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  /**
   * Digest from the cancellation preview.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCancellationDigest() {
    return cancellationDigest;
  }

  /**
   * Sets: digest from the cancellation preview.
   *
   * @param cancellationDigest raw value
   */
  public void setCancellationDigest(Object cancellationDigest) {
    this.cancellationDigest = cancellationDigest;
  }
}
