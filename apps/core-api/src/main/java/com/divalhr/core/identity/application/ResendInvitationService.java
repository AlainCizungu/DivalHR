package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.InvitationReceiptResponse;
import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationOrigin;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.domain.InvitationToken;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Reissues a pending invitation (decision D-10): a new token replaces the old hash (the old link
 * stops working), the expiry restarts, the issuance number grows and the email is sent once after
 * commit. At most {@link Invitation#MAX_RESENDS} reissues, at least the configured interval apart.
 * The receipt has no email address and is replayed exactly; a replay sends nothing.
 */
@Service
public class ResendInvitationService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "invitation.resend";

  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(OPERATION, "invitation", "resend", "resent", 200);

  private final InvitationValidator validator;
  private final IdempotentOperation operations;
  private final OrganizationDirectory organizations;
  private final JdbcInvitationRepository invitations;
  private final InvitationEvents events;
  private final InvitationDelivery delivery;
  private final InvitationProperties properties;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator validator
   * @param operations shared idempotent command flow
   * @param organizations organization directory
   * @param invitations invitation repository
   * @param events audit and outbox
   * @param delivery after-commit email delivery
   * @param properties settings
   */
  public ResendInvitationService(
      InvitationValidator validator,
      IdempotentOperation operations,
      OrganizationDirectory organizations,
      JdbcInvitationRepository invitations,
      InvitationEvents events,
      InvitationDelivery delivery,
      InvitationProperties properties) {
    this.validator = validator;
    this.operations = operations;
    this.organizations = organizations;
    this.invitations = invitations;
    this.events = events;
    this.delivery = delivery;
    this.properties = properties;
    this.clock = Clock.systemUTC();
  }

  /**
   * Reissues an invitation, or replays an earlier identical request.
   *
   * @param tenant verified tenant
   * @param actorSubject verified subject
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param rawId path value
   * @param correlationId correlation ID
   * @return reissued or replayed receipt
   */
  public IdempotentOperation.Result<InvitationReceiptResponse> resend(
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      String rawId,
      String correlationId) {
    UUID id = operations.validated(SPEC, () -> validator.invitationId(idempotencyKey, true, rawId));
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", tenant.toString());
    canonical.put("invitationId", id.toString());
    AtomicReference<Reissued> sendAfterCommit = new AtomicReference<>();
    IdempotentOperation.Result<InvitationReceiptResponse> result =
        operations.execute(
            SPEC,
            actorSubject,
            idempotencyKey,
            canonical,
            InvitationReceiptResponse.class,
            () -> {
              Reissued reissued = reissueInTransaction(tenant, actorSubject, id, correlationId);
              sendAfterCommit.set(reissued);
              return new IdempotentOperation.Completed<>(
                  CreateInvitationService.receipt(reissued.invitation()), id, Outcome.UPDATED);
            });
    deliverAfterCommit(result.replayed(), sendAfterCommit.get());
    return result;
  }

  /**
   * Sends the email of a committed, non-replayed reissue.
   *
   * @param replayed whether the result was replayed
   * @param reissued the committed reissue, or null
   */
  void deliverAfterCommit(boolean replayed, Reissued reissued) {
    if (!replayed && reissued != null) {
      delivery.deliver(reissued.invitation(), reissued.token(), reissued.organization());
    }
  }

  private Reissued reissueInTransaction(
      TenantId tenant, String actorSubject, UUID id, String correlationId) {
    OrganizationSummary organization =
        organizations
            .find(tenant)
            .orElseThrow(() -> new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of()));
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    Invitation current =
        invitations
            .findForUpdate(tenant, id)
            .orElseThrow(() -> new ApiException(ErrorCode.INVITATION_NOT_FOUND, Map.of()));
    if (current.state() != InvitationState.PENDING || !current.expiresAt().isAfter(now)) {
      throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
    }
    if (current.origin() == InvitationOrigin.PLATFORM_BOOTSTRAP) {
      // MVP-014: the organization has a tenant administrator (the caller), so the bootstrap can no
      // longer succeed; tenant administrators may revoke it but never reissue it.
      throw new ApiException(ErrorCode.TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE, Map.of());
    }
    return reissueLocked(tenant, current, actorSubject, organization, now, correlationId);
  }

  /**
   * Applies the MVP-010 reissue limits to a locked, pending invitation and reissues it with its
   * audit record and outbox event (shared with the MVP-014 bootstrap resend). The address never
   * changes.
   *
   * @param tenant the invitation's tenant
   * @param current the locked invitation, PENDING and not past expiry
   * @param actorSubject verified subject
   * @param organization the inviting organization
   * @param now current time
   * @param correlationId correlation ID
   * @return the reissue awaiting its after-commit email
   */
  Reissued reissueLocked(
      TenantId tenant,
      Invitation current,
      String actorSubject,
      OrganizationSummary organization,
      Instant now,
      String correlationId) {
    UUID id = current.id();
    if (current.resendsRemaining() == 0) {
      throw new RateLimitedException(
          ErrorCode.INVITATION_RESEND_LIMITED,
          Math.max(1, Duration.between(now, current.expiresAt()).toSeconds()));
    }
    Instant allowedAt = current.tokenIssuedAt().plus(properties.resendInterval());
    if (now.isBefore(allowedAt)) {
      throw new RateLimitedException(
          ErrorCode.INVITATION_RESEND_LIMITED, Duration.between(now, allowedAt).toSeconds() + 1);
    }
    InvitationToken token = InvitationToken.generate();
    Instant expiresAt = now.plus(properties.ttl());
    if (!invitations.reissue(tenant, id, current.issueCount(), token.sha256(), now, expiresAt)) {
      throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
    }
    Invitation reissued =
        new Invitation(
            current.id(),
            current.tenant(),
            current.email(),
            current.role(),
            current.locale(),
            InvitationState.PENDING,
            now,
            expiresAt,
            current.issueCount() + 1,
            DeliveryState.QUEUED,
            null,
            null,
            current.createdAt(),
            current.origin());
    events.reissued(reissued, actorSubject, correlationId);
    return new Reissued(reissued, token, organization);
  }

  /**
   * A committed reissue awaiting its after-commit email.
   *
   * @param invitation the reissued invitation
   * @param token its in-memory token
   * @param organization the inviting organization
   */
  record Reissued(Invitation invitation, InvitationToken token, OrganizationSummary organization) {}
}
