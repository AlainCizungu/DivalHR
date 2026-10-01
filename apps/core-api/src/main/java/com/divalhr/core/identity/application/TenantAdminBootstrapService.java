package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.CreateTenantAdminBootstrapRequest;
import com.divalhr.core.identity.api.InvitationReceiptResponse;
import com.divalhr.core.identity.api.TenantAdminBootstrapResponse;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationOrigin;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.identity.internal.JdbcInvitationRepository.BootstrapWindow;
import com.divalhr.core.identity.internal.JdbcMembershipRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.OrganizationDirectory;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-014: a platform administrator invites an organization's first tenant administrator.
 *
 * <p>The organization comes only from the path, never from the caller's {@code tenant_id} claim
 * (architect decision on #38, A4). The bootstrap rule (no tenant-admin membership and no open
 * tenant-admin invitation of any origin) is evaluated inside the write transaction under the
 * organization's tenant-administration lock ({@link TenantAdministrationLock}), which every
 * tenant-admin creation path shares (A1). The invitation then follows the unchanged MVP-010
 * pipeline: one insertion path ({@link CreateInvitationService#insertNew}), audit and outbox in the
 * same transaction, the email once after commit (A7), and acceptance through the narrow Keycloak
 * extension, which re-checks the rule (A2).
 *
 * <p>Responses never contain the address, tenant-created invitations, memberships, actors or
 * identity-provider state (A5). Logs and metrics carry operation and outcome only: never the
 * organization ID, address, idempotency key or subject.
 */
@Service
public class TenantAdminBootstrapService {

  /** Create operation (idempotency scope, audit, logs, metrics). */
  public static final String CREATE = "tenant-admin-bootstrap.create";

  /** Resend operation. */
  public static final String RESEND = "tenant-admin-bootstrap.resend";

  /** Revoke operation. */
  public static final String REVOKE = "tenant-admin-bootstrap.revoke";

  /** Status read. */
  public static final String READ = "tenant-admin-bootstrap.read";

  private static final Logger LOG = LoggerFactory.getLogger(TenantAdminBootstrapService.class);

  private static final Duration HOUR = Duration.ofHours(1);

  private static final IdempotentCreate.Operation CREATE_SPEC =
      new IdempotentCreate.Operation(CREATE, "tenant_admin_bootstrap");

  private static final IdempotentOperation.Spec RESEND_SPEC =
      new IdempotentOperation.Spec(RESEND, "tenant_admin_bootstrap", "resend", "resent", 200);

  private final InvitationValidator validator;
  private final IdempotentCreate creates;
  private final IdempotentOperation operations;
  private final OrganizationDirectory organizations;
  private final TenantAdministrationLock administration;
  private final JdbcInvitationRepository invitations;
  private final JdbcMembershipRepository memberships;
  private final InvitationExpiry expiry;
  private final InvitationEvents events;
  private final CreateInvitationService createService;
  private final ResendInvitationService resendService;
  private final TenantAdministrationProperties properties;
  private final OperationMetrics metrics;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator validator
   * @param creates idempotent-create flow
   * @param operations idempotent command flow
   * @param organizations organization port (read-only status)
   * @param administration the shared tenant-administration lock
   * @param invitations invitation repository
   * @param memberships membership repository
   * @param expiry inline expiry
   * @param events audit and outbox
   * @param createService the shared insertion path
   * @param resendService the shared reissue step
   * @param properties per-actor limit
   * @param metrics operation metrics
   * @param transactions transaction template
   */
  public TenantAdminBootstrapService(
      InvitationValidator validator,
      IdempotentCreate creates,
      IdempotentOperation operations,
      OrganizationDirectory organizations,
      TenantAdministrationLock administration,
      JdbcInvitationRepository invitations,
      JdbcMembershipRepository memberships,
      InvitationExpiry expiry,
      InvitationEvents events,
      CreateInvitationService createService,
      ResendInvitationService resendService,
      TenantAdministrationProperties properties,
      OperationMetrics metrics,
      TransactionTemplate transactions) {
    this.validator = validator;
    this.creates = creates;
    this.operations = operations;
    this.organizations = organizations;
    this.administration = administration;
    this.invitations = invitations;
    this.memberships = memberships;
    this.expiry = expiry;
    this.events = events;
    this.createService = createService;
    this.resendService = resendService;
    this.properties = properties;
    this.metrics = metrics;
    this.transactions = transactions;
    this.clock = Clock.systemUTC();
  }

  /**
   * Invites the organization's first tenant administrator, or replays an earlier identical request.
   *
   * @param actorSubject verified subject of the platform administrator
   * @param rawOrganizationId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId correlation ID
   * @return created or replayed receipt (no address)
   */
  public IdempotentCreate.Result<InvitationReceiptResponse> create(
      String actorSubject,
      String rawOrganizationId,
      String idempotencyKey,
      CreateTenantAdminBootstrapRequest request,
      String correlationId) {
    TenantId tenant =
        creates.validated(CREATE_SPEC, () -> validator.organizationId(rawOrganizationId));
    InvitationValidator.CreateCommand command =
        creates.validated(CREATE_SPEC, () -> validator.bootstrap(idempotencyKey, request));
    // A3: the target organization is part of the fingerprint, so a key reused for another
    // organization (or payload) is IDEMPOTENCY_KEY_REUSED, never a replay.
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("organizationId", tenant.toString());
    canonical.put("email", command.email().value());
    canonical.put("role", command.role().wireName());
    canonical.put("locale", command.locale().tag());
    AtomicReference<CreateInvitationService.Pending> sendAfterCommit = new AtomicReference<>();
    IdempotentCreate.Result<InvitationReceiptResponse> result =
        creates.execute(
            CREATE_SPEC,
            actorSubject,
            idempotencyKey,
            canonical,
            InvitationReceiptResponse.class,
            () -> {
              CreateInvitationService.Pending pending =
                  createInTransaction(tenant, actorSubject, command, correlationId);
              sendAfterCommit.set(pending);
              return new IdempotentCreate.Created<>(
                  CreateInvitationService.receipt(pending.invitation()), pending.invitation().id());
            });
    createService.deliverAfterCommit(result.replayed(), sendAfterCommit.get());
    return result;
  }

  private CreateInvitationService.Pending createInTransaction(
      TenantId tenant,
      String actorSubject,
      InvitationValidator.CreateCommand command,
      String correlationId) {
    Instant now = now();
    // Lock order step 0: one platform administrator's bootstrap creations, across organizations.
    invitations.lockBootstrapActor(actorSubject);
    BootstrapWindow window = invitations.bootstrapsCreatedBy(actorSubject, now.minus(HOUR));
    if (window.created() >= properties.bootstrapPerActorPerHour()) {
      Instant oldest = window.oldest() == null ? now : window.oldest();
      throw new RateLimitedException(
          ErrorCode.RATE_LIMITED, Duration.between(now, oldest.plus(HOUR)).toSeconds() + 1);
    }
    // Step 1, then step 2 before any invitation row lock (step 3).
    OrganizationSummary organization = lockedOrganization(tenant);
    invitations.lockTenant(tenant);
    requireAvailable(tenant, now, correlationId);
    return createService.insertNew(
        tenant,
        actorSubject,
        command,
        InvitationOrigin.PLATFORM_BOOTSTRAP,
        organization,
        correlationId);
  }

  /**
   * Reissues the open bootstrap invitation, or replays an earlier identical request.
   *
   * @param actorSubject verified subject
   * @param rawOrganizationId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param correlationId correlation ID
   * @return reissued or replayed receipt
   */
  public IdempotentOperation.Result<InvitationReceiptResponse> resend(
      String actorSubject, String rawOrganizationId, String idempotencyKey, String correlationId) {
    TenantId tenant =
        operations.validated(RESEND_SPEC, () -> validator.organizationId(rawOrganizationId));
    operations.validated(RESEND_SPEC, () -> validator.idempotencyKey(idempotencyKey));
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("organizationId", tenant.toString());
    AtomicReference<ResendInvitationService.Reissued> sendAfterCommit = new AtomicReference<>();
    IdempotentOperation.Result<InvitationReceiptResponse> result =
        operations.execute(
            RESEND_SPEC,
            actorSubject,
            idempotencyKey,
            canonical,
            InvitationReceiptResponse.class,
            () -> {
              ResendInvitationService.Reissued reissued =
                  resendInTransaction(tenant, actorSubject, correlationId);
              sendAfterCommit.set(reissued);
              return new IdempotentOperation.Completed<>(
                  CreateInvitationService.receipt(reissued.invitation()),
                  reissued.invitation().id(),
                  Outcome.UPDATED);
            });
    resendService.deliverAfterCommit(result.replayed(), sendAfterCommit.get());
    return result;
  }

  private ResendInvitationService.Reissued resendInTransaction(
      TenantId tenant, String actorSubject, String correlationId) {
    Instant now = now();
    OrganizationSummary organization = lockedOrganization(tenant);
    invitations.lockTenant(tenant);
    List<Invitation> open = invitations.findOpenTenantAdminForUpdate(tenant);
    Invitation bootstrap =
        open.stream()
            .filter(TenantAdminBootstrapService::isBootstrap)
            .findFirst()
            .orElseThrow(() -> new ApiException(ErrorCode.INVITATION_NOT_FOUND, Map.of()));
    if (bootstrap.state() != InvitationState.PENDING || !bootstrap.expiresAt().isAfter(now)) {
      throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
    }
    // A5: still the organization's only path to a first administrator.
    boolean otherOpen =
        open.stream()
            .filter(other -> !other.id().equals(bootstrap.id()))
            .anyMatch(other -> isOpen(other, now));
    if (otherOpen || memberships.tenantAdminExists(tenant, null)) {
      throw new ApiException(ErrorCode.TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE, Map.of());
    }
    return resendService.reissueLocked(
        tenant, bootstrap, actorSubject, organization, now, correlationId);
  }

  /**
   * Revokes the open bootstrap invitation. Idempotent by state: without one, nothing changes.
   *
   * @param actorSubject verified subject
   * @param rawOrganizationId path value
   * @param correlationId correlation ID
   */
  public void revoke(String actorSubject, String rawOrganizationId, String correlationId) {
    try {
      TenantId tenant = validator.organizationId(rawOrganizationId);
      Outcome outcome =
          transactions.execute(
              status -> {
                Instant now = now();
                lockedOrganization(tenant);
                Optional<Invitation> bootstrap =
                    invitations.findOpenTenantAdminForUpdate(tenant).stream()
                        .filter(TenantAdminBootstrapService::isBootstrap)
                        .findFirst();
                if (bootstrap.isEmpty()) {
                  return Outcome.UNCHANGED;
                }
                Invitation current = bootstrap.get();
                if (current.state() == InvitationState.ACCEPTING) {
                  throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
                }
                if (!current.expiresAt().isAfter(now)) {
                  expiry.expire(current, now, correlationId);
                  return Outcome.UNCHANGED;
                }
                if (!invitations.revoke(tenant, current.id(), actorSubject, now)) {
                  throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
                }
                events.revoked(current, actorSubject, correlationId);
                return Outcome.UPDATED;
              });
      if (outcome == null) {
        throw new IllegalStateException("transaction returned no result");
      }
      record(REVOKE, outcome);
    } catch (ApiException rejected) {
      record(
          REVOKE,
          switch (rejected.code()) {
            case ORGANIZATION_NOT_FOUND -> Outcome.NOT_FOUND;
            case INVITATION_NOT_PENDING -> Outcome.STATE_CONFLICT;
            default -> Outcome.VALIDATION_FAILED;
          });
      throw rejected;
    } catch (RuntimeException failure) {
      record(REVOKE, Outcome.FAILURE);
      throw failure;
    }
  }

  /**
   * The organization's bootstrap state (read-only; no lock).
   *
   * @param rawOrganizationId path value
   * @return availability and the open bootstrap invitation's receipt
   */
  public TenantAdminBootstrapResponse status(String rawOrganizationId) {
    TenantId tenant = validator.organizationId(rawOrganizationId);
    if (organizations.find(tenant).isEmpty()) {
      throw new ApiException(ErrorCode.ORGANIZATION_NOT_FOUND, Map.of());
    }
    Instant now = now();
    boolean available =
        !memberships.tenantAdminExists(tenant, null)
            && !invitations.openTenantAdminExists(tenant, now);
    InvitationReceiptResponse receipt =
        invitations
            .findOpenBootstrap(tenant)
            .filter(invitation -> isOpen(invitation, now))
            .map(invitation -> InvitationReceiptResponse.current(invitation, now))
            .orElse(null);
    return new TenantAdminBootstrapResponse(available, receipt);
  }

  /**
   * The bootstrap rule under the organization lock (steps 1 and 2 held): open tenant-admin
   * invitations past expiry are expired inline first; then neither a tenant-admin membership nor
   * another open tenant-admin invitation may exist.
   */
  private void requireAvailable(TenantId tenant, Instant now, String correlationId) {
    boolean blocked = memberships.tenantAdminExists(tenant, null);
    for (Invitation open : invitations.findOpenTenantAdminForUpdate(tenant)) {
      if (open.state() == InvitationState.PENDING && !open.expiresAt().isAfter(now)) {
        expiry.expire(open, now, correlationId);
      } else {
        blocked = true;
      }
    }
    if (blocked) {
      throw new ApiException(ErrorCode.TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE, Map.of());
    }
  }

  private OrganizationSummary lockedOrganization(TenantId tenant) {
    return administration
        .acquire(tenant)
        .orElseThrow(() -> new ApiException(ErrorCode.ORGANIZATION_NOT_FOUND, Map.of()));
  }

  private static boolean isBootstrap(Invitation invitation) {
    return invitation.origin() == InvitationOrigin.PLATFORM_BOOTSTRAP;
  }

  private static boolean isOpen(Invitation invitation, Instant now) {
    return invitation.state() == InvitationState.ACCEPTING
        || (invitation.state() == InvitationState.PENDING && invitation.expiresAt().isAfter(now));
  }

  private void record(String operation, Outcome outcome) {
    metrics.record(operation, outcome);
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
        .log("tenant_admin_bootstrap_" + outcome.name().toLowerCase(Locale.ROOT));
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
