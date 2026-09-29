package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shared body of {@code createDepartment} and {@code createCostCenter}. Only these properties are
 * accepted; anything else (for example {@code tenantId}, {@code legalEntityId} or {@code id}) is
 * captured as unknown and rejected.
 */
public abstract class SiteUnitRequest extends StrictRequest {

  @JsonProperty("siteId")
  private String siteId;

  @JsonProperty("code")
  private String code;

  @JsonProperty("name")
  private String name;

  @JsonProperty("effectiveFrom")
  private String effectiveFrom;

  @JsonProperty("effectiveTo")
  private String effectiveTo;

  /** Creates an empty request (used by Jackson). */
  protected SiteUnitRequest() {}

  /**
   * Fills the fields (used by tests through the subclasses' factories).
   *
   * @param siteId parent id
   * @param code code
   * @param name name
   * @param effectiveFrom ISO date
   * @param effectiveTo ISO date or {@code null}
   */
  protected final void fill(
      String siteId, String code, String name, String effectiveFrom, String effectiveTo) {
    this.siteId = siteId;
    this.code = code;
    this.name = name;
    this.effectiveFrom = effectiveFrom;
    this.effectiveTo = effectiveTo;
  }

  /**
   * Returns the parent site id.
   *
   * @return site id as submitted
   */
  public String getSiteId() {
    return siteId;
  }

  /**
   * Returns the code.
   *
   * @return code as submitted
   */
  public String getCode() {
    return code;
  }

  /**
   * Returns the name.
   *
   * @return name as submitted
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the start date.
   *
   * @return ISO date as submitted
   */
  public String getEffectiveFrom() {
    return effectiveFrom;
  }

  /**
   * Returns the end date.
   *
   * @return ISO date as submitted, or {@code null}
   */
  public String getEffectiveTo() {
    return effectiveTo;
  }
}
