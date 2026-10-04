package com.divalhr.core.people.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The fields of an employment change command (MVP-021). Values are validated by the service as raw
 * JSON values so that types, formats and unknown properties give stable field errors.
 */
public abstract class EmploymentChangeFields extends StrictRequest {

  private Object type;
  private Object effectiveFrom;
  private Object placement;
  private Object manager;
  private Object contractClassification;
  private Object compensationBasis;
  private Object reasonCode;
  private Object correctsAssignmentId;

  /**
   * Type: CHANGE or CORRECTION.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getType() {
    return type;
  }

  /**
   * Sets: type: CHANGE or CORRECTION.
   *
   * @param type raw value
   */
  public void setType(Object type) {
    this.type = type;
  }

  /**
   * Effective date (ISO).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  /**
   * Sets: effective date (ISO).
   *
   * @param effectiveFrom raw value
   */
  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * New placement, or null when unchanged.
   *
   * @return raw value
   */
  @Schema(type = "object")
  public Object getPlacement() {
    return placement;
  }

  /**
   * Sets: new placement, or null when unchanged.
   *
   * @param placement raw value
   */
  public void setPlacement(Object placement) {
    this.placement = placement;
  }

  /**
   * New manager ({employeeId} or {employeeId: null}), or null when unchanged.
   *
   * @return raw value
   */
  @Schema(type = "object")
  public Object getManager() {
    return manager;
  }

  /**
   * Sets: new manager ({employeeId} or {employeeId: null}), or null when unchanged.
   *
   * @param manager raw value
   */
  public void setManager(Object manager) {
    this.manager = manager;
  }

  /**
   * New contract classification, or null when unchanged.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getContractClassification() {
    return contractClassification;
  }

  /**
   * Sets: new contract classification, or null when unchanged.
   *
   * @param contractClassification raw value
   */
  public void setContractClassification(Object contractClassification) {
    this.contractClassification = contractClassification;
  }

  /**
   * New compensation basis, or null when unchanged.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCompensationBasis() {
    return compensationBasis;
  }

  /**
   * Sets: new compensation basis, or null when unchanged.
   *
   * @param compensationBasis raw value
   */
  public void setCompensationBasis(Object compensationBasis) {
    this.compensationBasis = compensationBasis;
  }

  /**
   * Reason code, or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getReasonCode() {
    return reasonCode;
  }

  /**
   * Sets: reason code, or null.
   *
   * @param reasonCode raw value
   */
  public void setReasonCode(Object reasonCode) {
    this.reasonCode = reasonCode;
  }

  /**
   * Row to correct (corrections only), or null.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getCorrectsAssignmentId() {
    return correctsAssignmentId;
  }

  /**
   * Sets: row to correct (corrections only), or null.
   *
   * @param correctsAssignmentId raw value
   */
  public void setCorrectsAssignmentId(Object correctsAssignmentId) {
    this.correctsAssignmentId = correctsAssignmentId;
  }
}
