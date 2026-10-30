package com.divalhr.core.payroll.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * MVP-094B: the activation of a verified rule pack for a legal entity. Every value is read as
 * {@code Object} so that a wrong type is a field error, never a deserialization failure.
 */
@Schema(name = "ActivateStatutoryRulePack")
public class ActivateStatutoryRulePackRequest extends StrictRequest {

  private Object packId;
  private Object fromMonth;
  private Object expectedVersion;
  private Object acknowledged;

  /**
   * The rule pack to activate.
   *
   * @return raw value
   */
  @Schema(type = "string", format = "uuid")
  public Object getPackId() {
    return packId;
  }

  public void setPackId(Object packId) {
    this.packId = packId;
  }

  /**
   * The first payroll month, YYYY-MM.
   *
   * @return raw value
   */
  @Schema(type = "string")
  public Object getFromMonth() {
    return fromMonth;
  }

  public void setFromMonth(Object fromMonth) {
    this.fromMonth = fromMonth;
  }

  /**
   * The number of activations the read showed.
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
   * Must be true: the employer remains responsible for legal review.
   *
   * @return raw value
   */
  @Schema(type = "boolean")
  public Object getAcknowledged() {
    return acknowledged;
  }

  public void setAcknowledged(Object acknowledged) {
    this.acknowledged = acknowledged;
  }
}
