package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-120C request bodies of the evidence-retention API. Values are read as {@code Object} so a
 * wrong type is a field error; unknown properties are recorded (the contract declares {@code
 * additionalProperties: false}).
 */
public final class SalaryAdvanceRetentionBodies {

  private SalaryAdvanceRetentionBodies() {}

  /** The organization's retention period in days (an operator choice, not legal advice). */
  @Schema(name = "SalaryAdvanceRetentionPolicyConfigure")
  public static class ConfigureRetention extends StrictRequest {

    private Object retentionDays;

    @Schema(type = "integer")
    public Object getRetentionDays() {
      return retentionDays;
    }

    public void setRetentionDays(Object retentionDays) {
      this.retentionDays = retentionDays;
    }
  }

  /** The explicit confirmation of exactly one previewed purge. */
  @Schema(name = "SalaryAdvancePurgeConfirm")
  public static class ConfirmPurge extends StrictRequest {

    private Object previewDigest;
    private Object confirmed;

    @Schema(type = "string")
    public Object getPreviewDigest() {
      return previewDigest;
    }

    public void setPreviewDigest(Object previewDigest) {
      this.previewDigest = previewDigest;
    }

    @Schema(type = "boolean")
    public Object getConfirmed() {
      return confirmed;
    }

    public void setConfirmed(Object confirmed) {
      this.confirmed = confirmed;
    }
  }
}
