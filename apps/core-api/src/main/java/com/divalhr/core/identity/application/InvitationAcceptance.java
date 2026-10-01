package com.divalhr.core.identity.application;

import com.divalhr.core.identity.application.IdentityDirectory.IdentityConflict;
import com.divalhr.core.identity.application.IdentityDirectory.Provisioned;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningResult;
import com.divalhr.core.identity.domain.CredentialSetupState;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.identity.internal.JdbcInvitationRepository.AcceptanceRow;
import com.divalhr.core.identity.internal.JdbcMembershipRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance across PostgreSQL and the identity provider, recoverable rather than distributed:
 *
 * <ol>
 *   <li>T1: the invitation is found by its token hash and locked; a usable one becomes {@code
 *       ACCEPTING} under a lease with a random owner.
 *   <li>Outside any transaction: the identity is provisioned idempotently (found again by
 *       invitation id when an earlier attempt already created it).
 *   <li>T2: only the current lease owner may complete (guardrail 5): the membership is inserted,
 *       the token erased, the invitation {@code ACCEPTED}, the credential setup scheduled, and the
 *       audit record and event written.
 *   <li>After T2: the provider is asked to email a "choose your password" link; the result is
 *       recorded durably and retried by the reconciler (guardrail 4).
 * </ol>
 *
 * <p>A crash between steps leaves an {@code ACCEPTING} row whose lease expires; the reconciler
 * takes it over and repeats steps 2 and 3, or compensates and expires it when the link has expired
 * meanwhile. The email address is marked verified by the provider adapter only because this flow
 * starts from possession of the single-use token sent to that address.
 */
@Component
public class InvitationAcceptance {

  /** Operation name for audit, logs and metrics. */
  public static final String OPERATION = "invitation.accept";

  /**
   * Credential-setup outcome metric (tag {@code result}: sent, completed, retry, failed,
   * invalid_state).
   */
  public static final String CREDENTIAL_METRIC = "divalhr.invitation.credential_setup";

  /** Compensations refused by the identity provider (Issue #31). */
  public static final String COMPENSATION_METRIC = "divalhr.invitation.compensation_refused";

  private static final Logger LOG = LoggerFactory.getLogger(InvitationAcceptance.class);

  private final JdbcInvitationRepository invitations;
  private final JdbcMembershipRepository memberships;
  private final EmailLookup lookups;
  private final InvitationEvents events;
  private final InvitationExpiry expiry;
  private final IdentityDirectory directory;
  private final TransactionTemplate transactions;
  private final InvitationProperties properties;
  private final MeterRegistry registry;
  private final Clock clock;

  /**
   * Creates the workflow.
   *
   * @param invitations invitation repository
   * @param memberships membership repository
   * @param lookups address lookups
   * @param events audit and outbox
   * @param expiry expiry helper
   * @param directory identity provider port
   * @param transactions transaction template
   * @param properties settings
   * @param registry metrics
   */
  public InvitationAcceptance(
      JdbcInvitationRepository invitations,
      JdbcMembershipRepository memberships,
      EmailLookup lookups,
      InvitationEvents events,
      InvitationExpiry expiry,
      IdentityDirectory directory,
      TransactionTemplate transactions,
      InvitationProperties properties,
      MeterRegistry registry) {
    this.invitations = invitations;
    this.memberships = memberships;
    this.lookups = lookups;
    this.events = events;
    this.expiry = expiry;
    this.directory = directory;
    this.transactions = transactions;
    this.properties = properties;
    this.registry = registry;
    this.clock = Clock.systemUTC();
  }

  /**
   * Accepts the invitation a well-formed token belongs to.
   *
   * @param tokenSha256 SHA-256 of the token
   * @param correlationId correlation ID
   * @throws ApiException INVITATION_INVALID, INVITATION_ACCEPTANCE_IN_PROGRESS,
   *     INVITATION_CANNOT_BE_ACCEPTED or IDENTITY_PROVIDER_UNAVAILABLE
   */
  public void accept(byte[] tokenSha256, String correlationId) {
    UUID owner = UUID.randomUUID();
    Invitation claimed =
        transactions.execute(
            status -> {
              Instant now = now();
              AcceptanceRow row =
                  invitations.findByTokenHashForUpdate(tokenSha256).orElseThrow(this::invalid);
              Invitation invitation = row.invitation();
              boolean open =
                  invitation.state() == InvitationState.PENDING
                      || invitation.state() == InvitationState.ACCEPTING;
              if (!open || !invitation.expiresAt().isAfter(now)) {
                throw invalid();
              }
              if (invitation.state() == InvitationState.ACCEPTING
                  && row.leaseUntil() != null
                  && row.leaseUntil().isAfter(now)) {
                throw new ApiException(ErrorCode.INVITATION_ACCEPTANCE_IN_PROGRESS, Map.of());
              }
              invitations.startAcceptance(
                  invitation.id(), owner, now.plus(properties.acceptanceLease()));
              return invitation;
            });
    if (claimed == null) {
      throw new IllegalStateException("transaction returned no result");
    }
    provisionAndComplete(claimed, owner, correlationId);
  }

  /**
   * Resumes an acceptance whose lease the reconciler has just taken over.
   *
   * @param invitationId invitation
   * @param owner the reconciler's lease owner
   * @param correlationId correlation ID of the job run
   */
  public void recover(UUID invitationId, UUID owner, String correlationId) {
    Invitation invitation =
        transactions.execute(
            status ->
                invitations
                    .lockOwnedAcceptance(invitationId, owner)
                    .map(AcceptanceRow::invitation)
                    .orElse(null));
    if (invitation == null) {
      return;
    }
    if (!invitation.expiresAt().isAfter(now())) {
      // The link expired while the acceptance was stalled: remove any identity it created, then
      // expire it. If the provider is unreachable the lease simply expires again and we retry.
      compensate(invitationId);
      transactions.executeWithoutResult(
          status ->
              invitations
                  .lockOwnedAcceptance(invitationId, owner)
                  .ifPresent(row -> expiry.expire(row.invitation(), now(), correlationId)));
      return;
    }
    try {
      provisionAndComplete(invitation, owner, correlationId);
    } catch (ApiException stillOpen) {
      LOG.atInfo()
          .addKeyValue("operation", OPERATION)
          .addKeyValue("invitationId", invitationId)
          .addKeyValue("code", stillOpen.code().name())
          .log("invitation_accept_recovery_deferred");
    }
  }

  private void provisionAndComplete(Invitation invitation, UUID owner, String correlationId) {
    ProvisioningResult result;
    try {
      result =
          directory.provision(
              new ProvisioningRequest(
                  invitation.id(),
                  invitation.tenant(),
                  invitation.email(),
                  invitation.role(),
                  invitation.locale()));
    } catch (RuntimeException unavailable) {
      invitations.releaseAcceptance(invitation.id(), owner);
      throw new ApiException(ErrorCode.IDENTITY_PROVIDER_UNAVAILABLE, Map.of());
    }
    if (result instanceof IdentityConflict) {
      deny(invitation, owner, correlationId);
      throw new ApiException(ErrorCode.INVITATION_CANNOT_BE_ACCEPTED, Map.of());
    }
    String subject = ((Provisioned) result).subject();
    UUID membershipId = UUID.randomUUID();
    Boolean completed;
    try {
      completed =
          transactions.execute(
              status -> {
                if (invitations.lockOwnedAcceptance(invitation.id(), owner).isEmpty()) {
                  return false;
                }
                Instant now = now();
                memberships.insert(
                    invitation.tenant(),
                    membershipId,
                    subject,
                    invitation.role(),
                    lookups.of(invitation.email()),
                    invitation.id(),
                    now);
                if (!invitations.completeAcceptance(invitation.id(), owner, membershipId, now)) {
                  throw new IllegalStateException("lease lost during completion");
                }
                events.accepted(invitation, subject, membershipId, correlationId);
                return true;
              });
    } catch (DataIntegrityViolationException conflicting) {
      // The subject or address already has a membership: undo only what this invitation created.
      try {
        compensate(invitation.id());
      } catch (RuntimeException compensationDeferred) {
        LOG.atWarn()
            .addKeyValue("operation", OPERATION)
            .addKeyValue("invitationId", invitation.id())
            .log("invitation_accept_compensation_deferred");
      }
      deny(invitation, owner, correlationId);
      throw new ApiException(ErrorCode.INVITATION_CANNOT_BE_ACCEPTED, Map.of());
    }
    if (!Boolean.TRUE.equals(completed)) {
      throw new ApiException(ErrorCode.INVITATION_ACCEPTANCE_IN_PROGRESS, Map.of());
    }
    LOG.atInfo()
        .addKeyValue("operation", OPERATION)
        .addKeyValue("invitationId", invitation.id())
        .addKeyValue("outcome", "accepted")
        .log("invitation_accepted");
    requestCredentialSetup(invitation.id(), 0);
  }

  /**
   * Compensates the identity of an invitation. A refusal (the identity is no longer pristine) is
   * alerted and left to a realm administrator; it never loops (Issue #31).
   */
  private void compensate(UUID invitationId) {
    if (directory.compensate(invitationId) == IdentityDirectory.CompensationOutcome.REFUSED) {
      Counter.builder(COMPENSATION_METRIC)
          .description("Identity compensations refused because the identity is not pristine")
          .register(registry)
          .increment();
      LOG.atError()
          .addKeyValue("operation", OPERATION)
          .addKeyValue("invitationId", invitationId)
          .log("invitation_accept_compensation_refused");
    }
  }

  private void deny(Invitation invitation, UUID owner, String correlationId) {
    transactions.executeWithoutResult(
        status -> {
          if (invitations.releaseAcceptance(invitation.id(), owner)) {
            events.acceptanceDenied(invitation, correlationId);
          }
        });
    LOG.atInfo()
        .addKeyValue("operation", OPERATION)
        .addKeyValue("invitationId", invitation.id())
        .addKeyValue("outcome", "not_acceptable")
        .log("invitation_accept_rejected");
  }

  /**
   * Asks the provider for the "choose your password" email and records the attempt durably.
   *
   * <p>Policy (Issue #31, A1): an email sent, or the provider's proven completed state, is recorded
   * as {@code SENT}. An invalid state (partial, contradictory or drifted) is recorded as {@code
   * FAILED} at once, never as sent, and alerted: retrying cannot repair it and would send nothing.
   * An unreachable provider is retried with backoff, then {@code FAILED}.
   *
   * @param invitationId accepted invitation
   * @param previousAttempts attempts before this one
   * @return the recorded state
   */
  public CredentialSetupState requestCredentialSetup(UUID invitationId, int previousAttempts) {
    int attempts = previousAttempts + 1;
    CredentialSetupState state;
    Instant nextAt = null;
    String detail = null;
    try {
      IdentityDirectory.CredentialSetupOutcome result =
          directory.requestCredentialSetup(invitationId);
      switch (result) {
        case EMAIL_SENT -> state = CredentialSetupState.SENT;
        case COMPLETED -> {
          state = CredentialSetupState.SENT;
          detail = "completed";
        }
        default -> {
          state = CredentialSetupState.FAILED;
          detail = "invalid_state";
        }
      }
    } catch (RuntimeException failed) {
      if (attempts >= properties.credentialSetupMaxAttempts()) {
        state = CredentialSetupState.FAILED;
      } else {
        state = CredentialSetupState.PENDING;
        nextAt = now().plus(properties.credentialSetupRetry().multipliedBy(attempts));
      }
    }
    invitations.recordCredentialSetup(invitationId, state.name(), attempts, nextAt);
    String outcome =
        detail != null
            ? detail
            : state == CredentialSetupState.PENDING
                ? "retry"
                : state.name().toLowerCase(Locale.ROOT);
    Counter.builder(CREDENTIAL_METRIC)
        .description("Identity-provider credential setup requests after acceptance")
        .tag("result", outcome)
        .register(registry)
        .increment();
    ("invalid_state".equals(outcome) ? LOG.atError() : LOG.atInfo())
        .addKeyValue("invitationId", invitationId)
        .addKeyValue("attempts", attempts)
        .addKeyValue("result", outcome)
        .log("invitation_credential_setup_" + outcome);
    return state;
  }

  private ApiException invalid() {
    return new ApiException(ErrorCode.INVITATION_INVALID, Map.of());
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
