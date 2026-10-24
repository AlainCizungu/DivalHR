package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.api.StrictRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-111: the administrator's attestation that the recipient opted in. */
@Schema(name = "ConsentRequest")
public class ConsentRequest extends StrictRequest {

  private Object source;

  private Object confirmedAt;

  private Object attested;

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {
        "WRITTEN",
        "WEB_FORM",
        "VERBAL_RECORDED_OUTSIDE_DIVALHR",
        "OTHER_DOCUMENTED"
      })
  public Object getSource() {
    return source;
  }

  /**
   * Sets the raw value.
   *
   * @param source raw value
   */
  public void setSource(Object source) {
    this.source = source;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date-time")
  public Object getConfirmedAt() {
    return confirmedAt;
  }

  /**
   * Sets the raw value.
   *
   * @param confirmedAt raw value
   */
  public void setConfirmedAt(Object confirmedAt) {
    this.confirmedAt = confirmedAt;
  }

  /**
   * Raw value; the service types and validates it.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getAttested() {
    return attested;
  }

  /**
   * Sets the raw value.
   *
   * @param attested raw value
   */
  public void setAttested(Object attested) {
    this.attested = attested;
  }
}
