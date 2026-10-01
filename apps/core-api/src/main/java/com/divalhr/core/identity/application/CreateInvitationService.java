package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.CreateInvitationRequest;
import com.divalhr.core.identity.api.InvitationReceiptResponse;
import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationOrigin;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.domain.InvitationStatus;
import com.divalhr.core.identity.domain.InvitationToken;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.identity.internal.JdbcMembershipRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * MVP-010: a tenant administrator invites a person by email with one tenant role.
 *
 * <p>Only the caller's verified tenant is consulted (enumeration resistance): an address known in
 * another tenant, or to the identity provider, gives the same 201 as any other. Inside one
 * transaction, serialized per tenant: organization check, quotas, "already member" and "already
 * pending" checks, insert with the token's SHA-256, audit and outbox. The receipt has no email
 * address and is replayed exactly (amendment A2). After commit, and only for a new creation, the
 * email is sent once with the in-memory token (amendment A3).
 *
 * <p>MVP-014 (A1): a {@code tenant-admin} invitation first takes the organization's
 * tenant-administration lock ({@link TenantAdministrationLock}), shared with the platform bootstrap
 * and with tenant-admin acceptances. {@link #insertNew} is the one insertion path for both origins.
 */
@Service
public class CreateInvitationService {

  /** Operation name used for idempotency scope, audit, logs and metrics. */
  public static final String OPERATION = "invitation.create";

  private static final IdempotentCreate.Operation SPEC =
      new IdempotentCreate.Operation(OPERATION, "invitation");

  private final InvitationValidator validator;
  private final IdempotentCreate creates;
  private final OrganizationDirectory organizations;
  private final JdbcInvitationRepository invitations;
  private final JdbcMembershipRepository memberships;
  private final EmailLookup lookups;
  private final InvitationEvents events;
  private final InvitationExpiry expiry;
  private final InvitationDelivery delivery;
  private final InvitationProperties properties;
  private final TenantAdministrationLock administration;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator request validator
   * @param creates shared idempotent-create flow
   * @param organizations organization directory (tenant module)
   * @param invitations invitation repository
   * @param memberships membership repository
   * @param lookups address lookups
   * @param events audit and outbox
   * @param expiry inline expiry of due invitations
   * @param delivery after-commit email delivery
   * @param properties settings
   * @param administration the shared tenant-administration lock (MVP-014)
   */
  public CreateInvitationService(
      InvitationValidator validator,
      IdempotentCreate creates,
      OrganizationDirectory organizations,
      JdbcInvitationRepository invitations,
      JdbcMembershipRepository memberships,
      EmailLookup lookups,
      InvitationEvents events,
      InvitationExpiry expiry,
      InvitationDelivery delivery,
      InvitationProperties properties,
      TenantAdministrationLock administration) {
    this.validator = validator;
    this.creates = creates;
    this.organizations = organizations;
    this.invitations = invitations;
    this.memberships = memberships;
    this.lookups = lookups;
    this.events = events;
    this.expiry = expiry;
    this.delivery = delivery;
    this.properties = properties;
    this.administration = administration;
    this.clock = Clock.systemUTC();
  }

  /**
   * Creates an invitation, or replays an earlier identical request.
   *
   * @param tenant verified tenant
   * @param actorSubject verified JWT {@code sub}
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId correlation ID
   * @return created or replayed receipt
   */
  public IdempotentCreate.Result<InvitationReceiptResponse> create(
      TenantId tenant,
      String actorSubject,
      String idempotencyKey,
      CreateInvitationRequest request,
      String correlationId) {
    InvitationValidator.CreateCommand command =
        creates.validated(SPEC, () -> validator.create(idempotencyKey, request));
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", tenant.toString());
    canonical.put("email", command.email().value());
    canonical.put("role", command.role().wireName());
    canonical.put("locale", command.locale().tag());
    AtomicReference<Pending> sendAfterCommit = new AtomicReference<>();
    IdempotentCreate.Result<InvitationReceiptResponse> result =
        creates.execute(
            SPEC,
            actorSubject,
            idempotencyKey,
            canonical,
            InvitationReceiptResponse.class,
            () -> {
              Pending pending = createInTransaction(tenant, actorSubject, command, correlationId);
              sendAfterCommit.set(pending);
              return new IdempotentCreate.Created<>(
                  receipt(pending.invitation()), pending.invitation().id());
            });
    deliverAfterCommit(result.replayed(), sendAfterCommit.get());
    return result;
  }

  /**
   * Sends the email of a committed, non-replayed creation (amendment A3; architect decision on #38,
   * A7: only after the invitation, audit and outbox rows committed together).
   *
   * @param replayed whether the result was replayed
   * @param pending the committed creation, or null
   */
  void deliverAfterCommit(boolean replayed, Pending pending) {
    if (!replayed && pending != null) {
      delivery.deliver(pending.invitation(), pending.token(), pending.organization());
    }
  }

  private Pending createInTransaction(
      TenantId tenant,
      String actorSubject,
      InvitationValidator.CreateCommand command,
      String correlationId) {
    // MVP-014 A1: a tenant-admin invitation is created under the shared organization lock.
    OrganizationSummary organization =
        (command.role() == TenantRole.TENANT_ADMIN
                ? administration.acquire(tenant)
                : organizations.find(tenant))
            .orElseThrow(() -> new ApiException(ErrorCode.TENANT_CONTEXT_MISSING, Map.of()));
    return insertNew(
        tenant, actorSubject, command, InvitationOrigin.TENANT_ADMIN, organization, correlationId);
  }

  /**
   * Inserts a new invitation with its audit record and outbox event, inside the caller's
   * transaction (lock order step 2 onwards). Quotas, the "already member" and "already pending"
   * checks, inline expiry of a due invitation, the insert and the events are shared by both
   * origins.
   *
   * @param tenant target tenant
   * @param actorSubject verified subject
   * @param command normalized command
   * @param origin who creates it
   * @param organization the inviting organization (already checked or locked by the caller)
   * @param correlationId correlation ID
   * @return the new invitation with its in-memory token
   */
  Pending insertNew(
      TenantId tenant,
      String actorSubject,
      InvitationValidator.CreateCommand command,
      InvitationOrigin origin,
      OrganizationSummary organization,
      String correlationId) {
    invitations.lockTenant(tenant);
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

    Instant windowStart = now.minus(Duration.ofHours(1));
    if (invitations.countCreatedSince(tenant, windowStart) >= properties.createPerHour()) {
      Instant oldest = invitations.oldestCreatedSince(tenant, windowStart).orElse(now);
      throw new RateLimitedException(
          ErrorCode.INVITATION_RATE_LIMITED,
          Duration.between(now, oldest.plus(Duration.ofHours(1))).toSeconds() + 1);
    }
    if (invitations.countOpen(tenant, now) >= properties.maxOpen()) {
      throw new RateLimitedException(ErrorCode.INVITATION_RATE_LIMITED, 3600);
    }

    byte[] lookup = lookups.of(command.email());
    if (memberships.memberExists(tenant, lookup)) {
      throw new ApiException(
          ErrorCode.INVITATION_RECIPIENT_ALREADY_MEMBER, Map.of("field", "email"));
    }
    var open = invitations.findOpenForUpdate(tenant, lookup);
    if (open.isPresent()) {
      Invitation existing = open.get();
      if (existing.state() == InvitationState.PENDING && !existing.expiresAt().isAfter(now)) {
        // Past expiry but not yet materialized by the job: expire it now, then create anew.
        expiry.expire(existing, now, correlationId);
      } else {
        throw new ApiException(ErrorCode.INVITATION_ALREADY_PENDING, Map.of("field", "email"));
      }
    }

    InvitationToken token = InvitationToken.generate();
    Invitation invitation =
        new Invitation(
            UUID.randomUUID(),
            tenant,
            command.email(),
            command.role(),
            command.locale(),
            InvitationState.PENDING,
            now,
            now.plus(properties.ttl()),
            1,
            DeliveryState.QUEUED,
            null,
            null,
            now,
            origin);
    try {
      invitations.insert(tenant, invitation, lookup, token.sha256(), actorSubject);
    } catch (DuplicateKeyException concurrent) {
      throw new ApiException(ErrorCode.INVITATION_ALREADY_PENDING, Map.of("field", "email"));
    }
    events.created(invitation, actorSubject, correlationId);
    return new Pending(invitation, token, organization);
  }

  /**
   * The receipt of a creation or reissue: no email address.
   *
   * @param invitation invitation
   * @return receipt
   */
  static InvitationReceiptResponse receipt(Invitation invitation) {
    return new InvitationReceiptResponse(
        invitation.id(),
        invitation.role().wireName(),
        invitation.locale().tag(),
        InvitationStatus.PENDING,
        DeliveryState.QUEUED,
        invitation.expiresAt(),
        invitation.createdAt());
  }

  /**
   * A committed creation awaiting its after-commit email.
   *
   * @param invitation the invitation
   * @param token its in-memory token
   * @param organization the inviting organization
   */
  record Pending(Invitation invitation, InvitationToken token, OrganizationSummary organization) {}
}
