package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-092: the final lock of a reviewed payroll-input run. */
@Schema(name = "LockPayrollInputRunRequest")
public class LockPayrollInputRunRequest extends StrictRequest {

  private Object previewDigest;
  private Object confirm;

  /**
   * The reviewed lock preview's digest, exactly as returned.
   *
   * @return raw value
   */
  @Schema(type = "string", pattern = "^[0-9a-f]{64}$")
  public Object getPreviewDigest() {
    return previewDigest;
  }

  public void setPreviewDigest(Object previewDigest) {
    this.previewDigest = previewDigest;
  }

  /**
   * The explicit confirmation.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirm() {
    return confirm;
  }

  public void setConfirm(Object confirm) {
    this.confirm = confirm;
  }
}
