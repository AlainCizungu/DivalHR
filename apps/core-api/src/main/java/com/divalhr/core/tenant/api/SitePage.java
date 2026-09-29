package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A page of sites.
 *
 * @param data rows in code order
 * @param nextCursor continuation, absent on the last page
 */
@Schema(name = "SitePage")
public record SitePage(
    List<SiteResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Copies the rows. */
  public SitePage {
    data = List.copyOf(data);
  }
}
