package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of cost centers.
 *
 * @param data rows in (code, id) byte order
 * @param nextCursor opaque cursor for the next page, absent on the last page
 */
@Schema(name = "CostCenterPage")
public record CostCenterPage(
    List<CostCenterResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Defensively copies the rows. */
  public CostCenterPage {
    data = List.copyOf(data);
  }
}
