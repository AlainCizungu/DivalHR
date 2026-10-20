package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-092: replace a run by a new version after corrected upstream evidence. */
@Schema(name = "SupersedePayrollInputRunRequest")
public class SupersedePayrollInputRunRequest extends StrictRequest {

  private Object reasonCode;
  private Object explanation;
  private Object previewDigest;
  private Object confirm;

  /**
   * Why the version is replaced.
   *
   * @return raw value
   */
  @Schema(
      type = "string",
      allowableValues = {"UPSTREAM_CORRECTION", "UPSTREAM_REAPPROVAL", "SCOPE_CORRECTION", "OTHER"})
  public Object getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }

  /**
   * The bounded plain-text explanation of OTHER (1 to 200 characters; no link, markup or personal
   * data); absent for every other reason.
   *
   * @return raw value
   */
  @Schema(type = "string", minLength = 1, maxLength = 200, nullable = true)
  public Object getExplanation() {
    return explanation;
  }

  public void setExplanation(Object explanation) {
    this.explanation = explanation;
  }

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
