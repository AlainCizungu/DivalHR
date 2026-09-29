package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of departments.
 *
 * @param data rows in (code, id) byte order
 * @param nextCursor opaque cursor for the next page, absent on the last page
 */
@Schema(name = "DepartmentPage")
public record DepartmentPage(
    List<DepartmentResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Defensively copies the rows. */
  public DepartmentPage {
    data = List.copyOf(data);
  }
}
