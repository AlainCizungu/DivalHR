package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** MVP-092: create the worked-time export of a locked payroll-input run. */
@Schema(name = "CreatePayrollInputExportRequest")
public class CreatePayrollInputExportRequest extends StrictRequest {

  private Object runId;
  private Object confirm;

  /**
   * The locked run.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getRunId() {
    return runId;
  }

  public void setRunId(Object runId) {
    this.runId = runId;
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
