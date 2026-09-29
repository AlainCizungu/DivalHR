package com.divalhr.core.tenant.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A page of legal entities.
 *
 * @param data rows in code order
 * @param nextCursor continuation, absent on the last page
 */
@Schema(name = "LegalEntityPage")
public record LegalEntityPage(
    List<LegalEntityResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Copies the rows. */
  public LegalEntityPage {
    data = List.copyOf(data);
  }
}
