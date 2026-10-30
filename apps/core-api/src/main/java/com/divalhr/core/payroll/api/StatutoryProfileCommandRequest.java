package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094B: the statutory profile entry to preview. Every value is read as {@code Object} so that a
 * wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "StatutoryProfileCommand")
public class StatutoryProfileCommandRequest extends StrictRequest {

  private Object effectiveFrom;
  private Object classification;
  private Object cnssSubject;
  private Object irppSubject;
  private Object evidenceCode;

  /**
   * The first day the entry is in force.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "date")
  public Object getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(Object effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  /**
   * STANDARD_RESIDENT or UNSUPPORTED.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getClassification() {
    return classification;
  }

  public void setClassification(Object classification) {
    this.classification = classification;
  }

  /**
   * Whether the employee is subject to CNSS.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getCnssSubject() {
    return cnssSubject;
  }

  public void setCnssSubject(Object cnssSubject) {
    this.cnssSubject = cnssSubject;
  }

  /**
   * Whether the employee is subject to the IRPP salary withholding.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getIrppSubject() {
    return irppSubject;
  }

  public void setIrppSubject(Object irppSubject) {
    this.irppSubject = irppSubject;
  }

  /**
   * The evidence code, from a closed list.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getEvidenceCode() {
    return evidenceCode;
  }

  public void setEvidenceCode(Object evidenceCode) {
    this.evidenceCode = evidenceCode;
  }
}
