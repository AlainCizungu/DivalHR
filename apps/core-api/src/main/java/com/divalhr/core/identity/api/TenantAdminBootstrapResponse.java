package com.divalhr.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bootstrap state of one organization (MVP-014). Never contains addresses, memberships,
 * tenant-created invitations, actors or identity-provider state.
 *
 * @param available whether a bootstrap invitation could be created now
 * @param invitation the open bootstrap invitation's receipt, or {@code null}
 */
@Schema(name = "TenantAdminBootstrap")
public record TenantAdminBootstrapResponse(
    boolean available,
    @JsonInclude(JsonInclude.Include.ALWAYS) InvitationReceiptResponse invitation) {}
