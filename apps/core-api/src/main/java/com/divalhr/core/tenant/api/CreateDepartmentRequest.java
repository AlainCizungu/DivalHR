package com.divalhr.core.tenant.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request body for {@code createDepartment}. */
@Schema(name = "CreateDepartment")
public final class CreateDepartmentRequest extends SiteUnitRequest {

  /** Creates an empty request (used by Jackson). */
  public CreateDepartmentRequest() {}

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
  public static CreateDepartmentRequest of(
      String siteId, String code, String name, String effectiveFrom, String effectiveTo) {
    CreateDepartmentRequest request = new CreateDepartmentRequest();
    request.fill(siteId, code, name, effectiveFrom, effectiveTo);
    return request;
  }
}
