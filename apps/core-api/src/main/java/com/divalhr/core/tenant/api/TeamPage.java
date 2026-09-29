package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A page of teams.
 *
 * @param data rows in code order
 * @param nextCursor continuation, absent on the last page
 */
@Schema(name = "TeamPage")
public record TeamPage(
    List<TeamResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Copies the rows. */
  public TeamPage {
    data = List.copyOf(data);
  }
}
