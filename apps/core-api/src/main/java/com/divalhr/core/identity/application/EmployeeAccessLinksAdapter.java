package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.LinkRow;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.RevocationRow;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.tenancy.TenantId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity's implementation of {@link EmployeeAccessLinks} for the separation coordinator in {@code
 * people} (MVP-022, A22-4). Every locking or writing method is {@code MANDATORY}: it joins the
 * coordinator's transaction and never opens one of its own (the architecture test enforces it).
 */
@Component
public class EmployeeAccessLinksAdapter implements EmployeeAccessLinks {

  private final JdbcAccessLinkRepository links;
  private final AccessLinkEvents events;

  /**
   * Creates the adapter.
   *
   * @param links link and revocation repository
   * @param events audit and outbox
   */
  public EmployeeAccessLinksAdapter(JdbcAccessLinkRepository links, AccessLinkEvents events) {
    this.links = links;
    this.events = events;
  }

  @Override
  public Optional<Link> activeLink(TenantId tenant, UUID employeeId, String callerSubject) {
    return links.activeLink(tenant, employeeId).map(row -> link(row, callerSubject));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Link> lockForSeparation(TenantId tenant, UUID employeeId, String callerSubject) {
    links.lockTenant(tenant);
    return links.lockActiveLink(tenant, employeeId).map(row -> link(row, callerSubject));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID scheduleRevocation(TenantId tenant, RevocationRequest request) {
    UUID id = UUID.randomUUID();
    boolean effective =
        links.insertRevocation(
            tenant,
            id,
            request.membershipId(),
            request.linkId(),
            request.employeeId(),
            request.separationId(),
            request.effectiveAt());
    events.requested(
        tenant, request.actor(), id, request.membershipId(), effective, request.correlationId());
    return id;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public CancelOutcome cancelRevocation(
      TenantId tenant, UUID separationId, String actor, String correlationId) {
    Optional<RevocationRow> open = links.lockOpenRevocation(tenant, separationId);
    if (open.isEmpty()) {
      return CancelOutcome.NONE;
    }
    RevocationRow row = open.get();
    if (!"SCHEDULED".equals(row.state()) || row.due()) {
      return CancelOutcome.ALREADY_EFFECTIVE;
    }
    if (links.cancel(tenant, row.id(), row.version(), actor) != 1) {
      // The instant passed between the read and the update (database clock).
      return CancelOutcome.ALREADY_EFFECTIVE;
    }
    events.transition(
        tenant,
        actor,
        "access-revocation.cancel",
        row.id(),
        "SCHEDULED",
        "CANCELLED",
        correlationId);
    return CancelOutcome.CANCELLED;
  }

  @Override
  public Map<UUID, Revocation> revocations(TenantId tenant, Collection<UUID> separationIds) {
    Map<UUID, Revocation> found = new HashMap<>();
    for (RevocationRow row : links.revocationsOf(tenant, separationIds)) {
      // Newest first: an open revocation wins over an older cancelled one.
      found.putIfAbsent(row.separationId(), new Revocation(state(row), row.effectiveAt()));
    }
    return found;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public RetryOutcome retry(
      TenantId tenant, UUID separationId, String actor, String correlationId) {
    Optional<RevocationRow> open = links.lockOpenRevocation(tenant, separationId);
    if (open.isEmpty()
        || !"MANUAL_INTERVENTION".equals(open.get().state())
        || links.requeue(tenant, open.get().id(), open.get().version()) != 1) {
      return RetryOutcome.NOT_RETRYABLE;
    }
    events.transition(
        tenant,
        actor,
        "access-revocation.retry",
        open.get().id(),
        "MANUAL_INTERVENTION",
        "IDP_PENDING",
        correlationId);
    return RetryOutcome.QUEUED;
  }

  private static RevocationState state(RevocationRow row) {
    return switch (row.state()) {
      case "SCHEDULED" -> row.due() ? RevocationState.SIGN_OUT_PENDING : RevocationState.SCHEDULED;
      case "IDP_PENDING" -> RevocationState.SIGN_OUT_PENDING;
      case "COMPLETED" -> RevocationState.COMPLETED;
      case "MANUAL_INTERVENTION" -> RevocationState.MANUAL_INTERVENTION;
      default -> RevocationState.CANCELLED;
    };
  }

  private static Link link(LinkRow row, String callerSubject) {
    return new Link(
        row.id(),
        row.membershipId(),
        TenantRole.TENANT_ADMIN.wireName().equals(row.role()),
        row.version(),
        row.subject().equals(callerSubject),
        row.revoked());
  }
}
