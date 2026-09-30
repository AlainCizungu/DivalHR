package com.divalhr.core.identity.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * What an anonymous token holder may learn: nothing about the organization, tenant or accounts.
 *
 * @param role role the invitation assigns
 * @param locale invitation language
 * @param expiresAt link expiry
 */
@Schema(name = "InvitationPreview")
public record InvitationPreviewResponse(String role, String locale, Instant expiresAt) {}
