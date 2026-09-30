package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Materializes the expiry of one invitation (token erased) with its audit record and event. */
@Component
public class InvitationExpiry {

  private final JdbcInvitationRepository invitations;
  private final InvitationEvents events;

  /**
   * Creates the helper.
   *
   * @param invitations invitation repository
   * @param events audit and outbox
   */
  public InvitationExpiry(JdbcInvitationRepository invitations, InvitationEvents events) {
    this.invitations = invitations;
    this.events = events;
  }

  /**
   * Expires a locked invitation that is past its expiry.
   *
   * @param invitation locked invitation (pending or accepting)
   * @param now current time
   * @param correlationId correlation ID
   * @return true when it was expired now
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean expire(Invitation invitation, Instant now, String correlationId) {
    if (!invitations.markExpired(invitation.id(), now)) {
      return false;
    }
    events.expired(invitation, correlationId);
    return true;
  }
}
