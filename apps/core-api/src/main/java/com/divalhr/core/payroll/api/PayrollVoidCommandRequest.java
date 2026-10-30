package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094C: the void to preview. The explanation is restricted: it is never logged, audited or
 * echoed in an error.
 */
@Schema(name = "PayrollVoidCommand")
public class PayrollVoidCommandRequest extends StrictRequest {

  private Object reasonCode;
  private Object explanation;

  /**
   * The closed void reason.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }

  /**
   * Required for OTHER_CONTROLLED, otherwise null (10 to 500 characters).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getExplanation() {
    return explanation;
  }

  public void setExplanation(Object explanation) {
    this.explanation = explanation;
  }
}
