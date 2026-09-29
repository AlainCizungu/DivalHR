package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code assignSiteRegion}: exactly {@code {"regionId": "uuid"}}. Any other
 * property (tenant, legal entity, site or server identifiers included) is rejected.
 */
@Schema(name = "AssignSiteRegion")
public final class AssignSiteRegionRequest extends StrictRequest {

  @JsonProperty("regionId")
  private String regionId;

  /** Creates an empty request (used by Jackson). */
  public AssignSiteRegionRequest() {}

  /**
   * Creates a request (used by tests).
   *
   * @param regionId region id
   * @return request
   */
  public static AssignSiteRegionRequest of(String regionId) {
    AssignSiteRegionRequest request = new AssignSiteRegionRequest();
    request.regionId = regionId;
    return request;
  }

  /**
   * Returns the region id.
   *
   * @return region id as submitted
   */
  public String getRegionId() {
    return regionId;
  }
}
