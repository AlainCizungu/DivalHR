package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A page of regions.
 *
 * @param data rows in code order
 * @param nextCursor continuation, absent on the last page
 */
@Schema(name = "RegionPage")
public record RegionPage(
    List<RegionResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Copies the rows. */
  public RegionPage {
    data = List.copyOf(data);
  }
}
