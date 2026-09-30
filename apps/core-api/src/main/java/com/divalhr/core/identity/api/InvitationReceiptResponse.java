package com.divalhr.core.identity.api;

import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.InvitationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of creating or reissuing an invitation (architecture amendment A2): no email address,
 * so the idempotency record stores no personal data, and it is replayed exactly.
 *
 * @param id invitation
 * @param role assigned role
 * @param locale language
 * @param status public status at the time of the operation
 * @param deliveryState delivery at the time of the operation (always QUEUED)
 * @param expiresAt link expiry
 * @param createdAt creation time
 */
@Schema(name = "InvitationReceipt")
public record InvitationReceiptResponse(
    UUID id,
    String role,
    String locale,
    InvitationStatus status,
    DeliveryState deliveryState,
    Instant expiresAt,
    Instant createdAt) {}
