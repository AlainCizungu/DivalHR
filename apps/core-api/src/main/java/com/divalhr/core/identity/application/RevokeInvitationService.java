package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.InvitationResponse;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Revokes a pending invitation; the link stops working immediately because its hash is erased.
 * Idempotent by state: revoking a revoked invitation returns it and records nothing new.
 */
@Service
public class RevokeInvitationService {

  /** Operation name used for audit, logs and metrics. */
  public static final String OPERATION = "invitation.revoke";

  private static final Logger LOG = LoggerFactory.getLogger(RevokeInvitationService.class);

  private final InvitationValidator validator;
  private final JdbcInvitationRepository invitations;
  private final InvitationEvents events;
  private final OperationMetrics metrics;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator validator
   * @param invitations invitation repository
   * @param events audit and outbox
   * @param metrics operation metrics
   * @param transactions transaction template
   */
  public RevokeInvitationService(
      InvitationValidator validator,
      JdbcInvitationRepository invitations,
      InvitationEvents events,
      OperationMetrics metrics,
      TransactionTemplate transactions) {
    this.validator = validator;
    this.invitations = invitations;
    this.events = events;
    this.metrics = metrics;
    this.transactions = transactions;
    this.clock = Clock.systemUTC();
  }

  /**
   * Revokes an invitation.
   *
   * @param tenant verified tenant
   * @param actorSubject verified subject
   * @param rawId path value
   * @param correlationId correlation ID
   * @return the invitation after the call
   */
  public InvitationResponse revoke(
      TenantId tenant, String actorSubject, String rawId, String correlationId) {
    try {
      UUID id = validator.invitationId(null, false, rawId);
      Result result =
          transactions.execute(
              status -> {
                Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                Invitation current =
                    invitations
                        .findForUpdate(tenant, id)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.INVITATION_NOT_FOUND, Map.of()));
                if (current.state() == InvitationState.REVOKED) {
                  return new Result(InvitationResponse.from(current, now), Outcome.UNCHANGED);
                }
                if (current.state() != InvitationState.PENDING
                    || !current.expiresAt().isAfter(now)) {
                  throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
                }
                if (!invitations.revoke(tenant, id, actorSubject, now)) {
                  throw new ApiException(ErrorCode.INVITATION_NOT_PENDING, Map.of());
                }
                events.revoked(current, actorSubject, correlationId);
                return new Result(
                    InvitationResponse.from(
                        invitations.findForUpdate(tenant, id).orElseThrow(), now),
                    Outcome.UPDATED);
              });
      if (result == null) {
        throw new IllegalStateException("transaction returned no result");
      }
      metrics.record(OPERATION, result.outcome());
      LOG.atInfo()
          .addKeyValue("operation", OPERATION)
          .addKeyValue("outcome", result.outcome().name().toLowerCase(java.util.Locale.ROOT))
          .log(
              result.outcome() == Outcome.UNCHANGED
                  ? "invitation_revoke_unchanged"
                  : "invitation_revoked");
      return result.response();
    } catch (ApiException rejected) {
      metrics.record(
          OPERATION,
          switch (rejected.code()) {
            case INVITATION_NOT_FOUND -> Outcome.NOT_FOUND;
            case INVITATION_NOT_PENDING -> Outcome.STATE_CONFLICT;
            default -> Outcome.VALIDATION_FAILED;
          });
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(OPERATION, Outcome.FAILURE);
      throw failure;
    }
  }

  private record Result(InvitationResponse response, Outcome outcome) {}
}
