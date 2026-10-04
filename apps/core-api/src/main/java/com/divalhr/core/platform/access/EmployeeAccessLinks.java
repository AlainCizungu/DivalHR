package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The employee's DivalHR access, for the separation coordinator in {@code people} (MVP-022, A22-4).
 * Implemented by {@code identity}, which owns the employee-to-membership link and the access
 * revocation; callers never read its tables.
 *
 * <p>Transaction boundary: every method that locks or writes is {@code @Transactional(propagation =
 * MANDATORY)} in the implementation. It joins the coordinator's transaction and fails when there is
 * none, so it never opens an independent business transaction. Locks are taken in the global order
 * of ADR 0008: the coordinator has already taken the manager-graph lock and the employment row
 * locks; {@link #lockForSeparation} takes the per-tenant access-link lock, then the link row and
 * the membership row; revocation rows are locked last.
 *
 * <p>Nothing returned here is ever logged: link, membership and revocation IDs are personal-data
 * references, and the role is Confidential.
 */
public interface EmployeeAccessLinks {

  /**
   * The employee's active link, without locking (previews).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param callerSubject the verified subject of the caller (to detect self-separation)
   * @return the link, or empty when the employee has none
   */
  Optional<Link> activeLink(TenantId tenant, UUID employeeId, String callerSubject);

  /**
   * Takes the per-tenant access-link lock and locks the employee's active link and its membership
   * (MANDATORY).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param callerSubject the verified subject of the caller
   * @return the locked link, or empty when the employee has none
   */
  Optional<Link> lockForSeparation(TenantId tenant, UUID employeeId, String callerSubject);

  /**
   * Records the revocation of the linked employee membership at an instant (MANDATORY). The
   * membership gate denies the membership from that instant on, whatever happens to the identity
   * provider. When the instant has passed the revocation is recorded as effective at once.
   *
   * @param tenant verified tenant
   * @param request what to revoke
   * @return the revocation ID
   */
  UUID scheduleRevocation(TenantId tenant, RevocationRequest request);

  /**
   * Cancels the separation's scheduled revocation before it takes effect (MANDATORY). Never waits
   * on a row a background worker holds: that raises a lock-not-available failure (SQLSTATE 55P03)
   * which rolls the coordinator's transaction back as a retryable timeout.
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @param actor verified subject
   * @param correlationId correlation ID
   * @return the outcome
   */
  CancelOutcome cancelRevocation(
      TenantId tenant, UUID separationId, String actor, String correlationId);

  /**
   * The access state of separations (read).
   *
   * @param tenant verified tenant
   * @param separationIds separations
   * @return per separation that has a revocation, its state
   */
  Map<UUID, Revocation> revocations(TenantId tenant, Collection<UUID> separationIds);

  /**
   * Re-queues a revocation that needs manual intervention (MANDATORY).
   *
   * @param tenant verified tenant
   * @param separationId separation
   * @param actor verified subject
   * @param correlationId correlation ID
   * @return whether it was re-queued
   */
  RetryOutcome retry(TenantId tenant, UUID separationId, String actor, String correlationId);

  /**
   * The caller's own employee binding (MVP-030 self-service; MANDATORY): the active link of the
   * caller's {@code employee} membership in the tenant, with the link and the membership locked
   * {@code FOR SHARE} until the caller's transaction ends (an unlink or a revocation waits). Lock
   * order step 4 (ADR 0008/0009).
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @return the binding, or empty when the caller has no active link
   */
  Optional<SelfLink> linkedEmployee(TenantId tenant, String subject);

  /**
   * An employee's own active link.
   *
   * @param employeeId the linked employee
   * @param membershipId the caller's membership
   * @param linkId the active link
   */
  record SelfLink(UUID employeeId, UUID membershipId, UUID linkId) {

    /** Requires every ID. */
    public SelfLink {
      Objects.requireNonNull(employeeId, "employeeId");
      Objects.requireNonNull(membershipId, "membershipId");
      Objects.requireNonNull(linkId, "linkId");
    }

    @Override
    public String toString() {
      return "SelfLink[redacted]";
    }
  }

  /**
   * An employee's active link.
   *
   * @param linkId link
   * @param membershipId membership
   * @param tenantAdmin whether the membership is a tenant administrator
   * @param version link version
   * @param self whether the membership's subject is the caller
   * @param revoked whether a revocation that is not cancelled already exists for the membership
   */
  record Link(
      UUID linkId,
      UUID membershipId,
      boolean tenantAdmin,
      long version,
      boolean self,
      boolean revoked) {

    /** Requires the IDs. */
    public Link {
      Objects.requireNonNull(linkId, "linkId");
      Objects.requireNonNull(membershipId, "membershipId");
    }

    @Override
    public String toString() {
      return "Link[redacted]";
    }
  }

  /**
   * A revocation to record.
   *
   * @param separationId separation
   * @param employeeId employee
   * @param linkId the locked active link
   * @param membershipId its membership
   * @param effectiveAt when DivalHR access ends
   * @param actor verified subject
   * @param correlationId correlation ID
   */
  record RevocationRequest(
      UUID separationId,
      UUID employeeId,
      UUID linkId,
      UUID membershipId,
      Instant effectiveAt,
      String actor,
      String correlationId) {

    /** Requires every component. */
    public RevocationRequest {
      Objects.requireNonNull(separationId, "separationId");
      Objects.requireNonNull(employeeId, "employeeId");
      Objects.requireNonNull(linkId, "linkId");
      Objects.requireNonNull(membershipId, "membershipId");
      Objects.requireNonNull(effectiveAt, "effectiveAt");
      Objects.requireNonNull(actor, "actor");
    }

    @Override
    public String toString() {
      return "RevocationRequest[redacted]";
    }
  }

  /** Revocation states as the separation shows them. */
  enum RevocationState {
    /** Access ends at {@link Revocation#effectiveAt()}. */
    SCHEDULED,
    /** DivalHR access is denied; sign-in removal is in progress. */
    SIGN_OUT_PENDING,
    /** DivalHR access is denied and sign-in removed. */
    COMPLETED,
    /** DivalHR access is denied; sign-in removal needs an administrator. */
    MANUAL_INTERVENTION,
    /** Cancelled before it took effect. */
    CANCELLED
  }

  /**
   * One separation's revocation.
   *
   * @param state state (a scheduled one whose instant passed is reported SIGN_OUT_PENDING)
   * @param effectiveAt when DivalHR access ends
   */
  record Revocation(RevocationState state, Instant effectiveAt) {}

  /** Outcome of {@link #cancelRevocation}. */
  enum CancelOutcome {
    /** The scheduled revocation was cancelled. */
    CANCELLED,
    /** The separation has no revocation (no access was linked). */
    NONE,
    /** Access already ended: the separation can no longer be cancelled. */
    ALREADY_EFFECTIVE
  }

  /** Outcome of {@link #retry}. */
  enum RetryOutcome {
    /** Re-queued with a fresh attempt budget. */
    QUEUED,
    /** Nothing to retry (no revocation, or not in manual intervention). */
    NOT_RETRYABLE
  }
}
