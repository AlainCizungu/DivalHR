package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-095A: the closed reason to cancel a prepared batch. Every value is read as {@code Object} so that a wrong type is a field error, never a
 * deserialization failure. Restricted financial and HR data: nothing here is ever logged.
 */
@Schema(name = "PaymentBatchCancelCommand")
public class PaymentBatchCancelCommandRequest extends StrictRequest {

  private Object reasonCode;

  /**
   * The reasonCode value.
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

}
