package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-090A: generate the payroll inputs of a reviewed preview. */
@Schema(name = "GeneratePayrollInputsRequest")
public class GeneratePayrollInputsRequest extends PayrollInputPreviewRequest {

  private Object previewDigest;
  private Object sourceVersionsDigest;
  private Object confirm;

  /**
   * The reviewed preview's digest, exactly as returned.
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
   * The digest of the source versions the preview showed, exactly as returned.
   *
   * @return raw value
   */
  @Schema(type = "string", pattern = "^[0-9a-f]{64}$")
  public Object getSourceVersionsDigest() {
    return sourceVersionsDigest;
  }

  public void setSourceVersionsDigest(Object sourceVersionsDigest) {
    this.sourceVersionsDigest = sourceVersionsDigest;
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
