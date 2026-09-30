package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.InvitationAcceptanceResponse;
import com.divalhr.core.identity.api.InvitationPreviewResponse;
import com.divalhr.core.identity.api.InvitationTokenRequest;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationState;
import com.divalhr.core.identity.domain.InvitationToken;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.ratelimit.PublicRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The anonymous invitation flow. Every unusable token (unknown, malformed, expired, revoked,
 * accepted) gives the same {@code INVITATION_INVALID}; nothing about the organization, tenant or
 * any account is returned. Requests are rate limited per client before any lookup.
 */
@Service
public class PublicInvitationService {

  /** Inspection operation (metrics). */
  public static final String INSPECT = "invitation.inspect";

  /** Rate-limit bucket shared by inspection and acceptance. */
  public static final String BUCKET = "invitation.public";

  private final InvitationValidator validator;
  private final JdbcInvitationRepository invitations;
  private final InvitationAcceptance acceptance;
  private final PublicRateLimiter limiter;
  private final OperationMetrics metrics;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param validator validator
   * @param invitations invitation repository
   * @param acceptance acceptance workflow
   * @param limiter anonymous rate limiter
   * @param metrics operation metrics
   */
  public PublicInvitationService(
      InvitationValidator validator,
      JdbcInvitationRepository invitations,
      InvitationAcceptance acceptance,
      PublicRateLimiter limiter,
      OperationMetrics metrics) {
    this.validator = validator;
    this.invitations = invitations;
    this.acceptance = acceptance;
    this.limiter = limiter;
    this.metrics = metrics;
    this.clock = Clock.systemUTC();
  }

  /**
   * Inspects an invitation.
   *
   * @param request body
   * @param clientAddress rate-limit key source (never stored)
   * @return role, locale and expiry
   */
  public InvitationPreviewResponse inspect(InvitationTokenRequest request, String clientAddress) {
    return measured(
        INSPECT,
        Outcome.LISTED,
        () -> {
          byte[] hash = checkedHash(request, clientAddress);
          Invitation invitation =
              invitations.findByTokenHash(hash).orElseThrow(PublicInvitationService::invalid);
          Instant now = Instant.now(clock);
          boolean usable =
              (invitation.state() == InvitationState.PENDING
                      || invitation.state() == InvitationState.ACCEPTING)
                  && invitation.expiresAt().isAfter(now);
          if (!usable) {
            throw invalid();
          }
          return new InvitationPreviewResponse(
              invitation.role().wireName(), invitation.locale().tag(), invitation.expiresAt());
        });
  }

  /**
   * Accepts an invitation.
   *
   * @param request body
   * @param clientAddress rate-limit key source (never stored)
   * @param correlationId correlation ID
   * @return acceptance result
   */
  public InvitationAcceptanceResponse accept(
      InvitationTokenRequest request, String clientAddress, String correlationId) {
    return measured(
        InvitationAcceptance.OPERATION,
        Outcome.UPDATED,
        () -> {
          byte[] hash = checkedHash(request, clientAddress);
          acceptance.accept(hash, correlationId);
          return InvitationAcceptanceResponse.ACCEPTED;
        });
  }

  private byte[] checkedHash(InvitationTokenRequest request, String clientAddress) {
    limiter.acquire(BUCKET, clientAddress);
    validator.tokenBody(request);
    if (!InvitationToken.isWellFormed(request.getToken())) {
      throw invalid();
    }
    return InvitationToken.hashOf(request.getToken());
  }

  private <T> T measured(String operation, Outcome success, java.util.function.Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, success);
      return result;
    } catch (ApiException rejected) {
      metrics.record(
          operation,
          switch (rejected.code()) {
            case INVITATION_INVALID -> Outcome.NOT_FOUND;
            case INVITATION_CANNOT_BE_ACCEPTED, INVITATION_ACCEPTANCE_IN_PROGRESS ->
                Outcome.STATE_CONFLICT;
            case RATE_LIMITED -> Outcome.RATE_LIMITED;
            case IDENTITY_PROVIDER_UNAVAILABLE -> Outcome.FAILURE;
            default -> Outcome.VALIDATION_FAILED;
          });
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.INVITATION_INVALID, Map.of());
  }
}
