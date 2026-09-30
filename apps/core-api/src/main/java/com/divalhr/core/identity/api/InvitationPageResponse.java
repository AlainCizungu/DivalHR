package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of invitations, newest first.
 *
 * @param data invitations
 * @param nextCursor opaque continuation token, absent on the last page
 */
@Schema(name = "InvitationPage")
public record InvitationPageResponse(
    List<InvitationResponse> data, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

  /** Defensively copies the rows. */
  public InvitationPageResponse {
    data = List.copyOf(data);
  }
}
