package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-097A: the body of a statutory pack lifecycle command. One closed shape serves every command;
 * each command requires the members it needs and refuses the rest as a field error. Every value is
 * read as {@code Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "StatutoryPackCommand")
public class StatutoryPackCommandRequest extends StrictRequest {

  private Object expectedVersion;
  private Object confirmed;
  private Object reasonCode;
  private Object note;
  private Object effectiveFrom;

  /**
   * The row version the caller read (optimistic concurrency).
   *
   * @return raw value
   */
  @Schema(type = "integer")
  public Object getExpectedVersion() {
    return expectedVersion;
  }

  public void setExpectedVersion(Object expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  /**
   * Must be true for approve, reject, schedule, activate and retire.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getConfirmed() {
    return confirmed;
  }

  public void setConfirmed(Object confirmed) {
    this.confirmed = confirmed;
  }

  /**
   * One bounded reason code (reject and retire).
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
   * An optional bounded internal note, at most 500 characters (reject only).
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getNote() {
    return note;
  }

  public void setNote(Object note) {
    this.note = note;
  }

  /**
   * The future effective date, YYYY-MM-DD (schedule only).
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
}
