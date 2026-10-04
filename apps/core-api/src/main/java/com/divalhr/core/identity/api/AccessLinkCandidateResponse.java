package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The membership holding a looked-up address (MVP-022). The address is never echoed.
 *
 * @param membershipId membership
 * @param role tenant role
 * @param linkable whether it can be linked to the employee
 * @param notLinkableReason {@code ALREADY_LINKED}, {@code ACCESS_REVOKED}, {@code EMPLOYEE_LINKED}
 *     or null
 */
@Schema(name = "AccessLinkCandidate")
public record AccessLinkCandidateResponse(
    UUID membershipId,
    String role,
    boolean linkable,
    @JsonInclude(JsonInclude.Include.ALWAYS) String notLinkableReason) {}
