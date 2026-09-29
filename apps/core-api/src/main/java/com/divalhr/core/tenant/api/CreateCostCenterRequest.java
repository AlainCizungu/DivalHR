package com.divalhr.core.tenant.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request body for {@code createCostCenter}. */
@Schema(name = "CreateCostCenter")
public final class CreateCostCenterRequest extends SiteUnitRequest {

  /** Creates an empty request (used by Jackson). */
  public CreateCostCenterRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param siteId parent id
   * @param code code
   * @param name name
   * @param effectiveFrom ISO date
   * @param effectiveTo ISO date or {@code null}
   * @return request
   */
  public static CreateCostCenterRequest of(
      String siteId, String code, String name, String effectiveFrom, String effectiveTo) {
    CreateCostCenterRequest request = new CreateCostCenterRequest();
    request.fill(siteId, code, name, effectiveFrom, effectiveTo);
    return request;
  }
}
