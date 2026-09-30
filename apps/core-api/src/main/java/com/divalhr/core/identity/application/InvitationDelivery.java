package com.divalhr.core.identity.application;

import com.divalhr.core.identity.application.InvitationMailer.InvitationMessage;
import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationToken;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends one invitation email after the creating (or reissuing) transaction has committed
 * (architecture amendment A3). The plaintext token exists only in memory during this call; it is
 * never persisted or queued, so a failed or interrupted delivery is never retried automatically.
 * The result is recorded only for the same issuance and only while still QUEUED, so a late result
 * for an older link can never overwrite a newer one. A crash before the result is recorded leaves
 * QUEUED, which the stale-delivery job turns into FAILED.
 */
@Component
public class InvitationDelivery {

  /** Delivery outcome metric (tag {@code result}: sent, failed). */
  public static final String METRIC = "divalhr.invitation.delivery";

  private static final Logger LOG = LoggerFactory.getLogger(InvitationDelivery.class);

  private final InvitationMailer mailer;
  private final JdbcInvitationRepository invitations;
  private final InvitationProperties properties;
  private final MeterRegistry registry;
  private final Clock clock;

  /**
   * Creates the delivery.
   *
   * @param mailer mail port
   * @param invitations invitation repository
   * @param properties settings (web base URL)
   * @param registry metrics
   */
  public InvitationDelivery(
      InvitationMailer mailer,
      JdbcInvitationRepository invitations,
      InvitationProperties properties,
      MeterRegistry registry) {
    this.mailer = mailer;
    this.invitations = invitations;
    this.properties = properties;
    this.registry = registry;
    this.clock = Clock.systemUTC();
  }

  /**
   * Sends the email for one issuance and records its result. Never throws.
   *
   * @param invitation the committed invitation (the issuance to send)
   * @param token the issuance's token (memory only)
   * @param organization inviting organization
   * @return the recorded result
   */
  public DeliveryState deliver(
      Invitation invitation, InvitationToken token, OrganizationSummary organization) {
    DeliveryState result;
    try {
      result =
          mailer.send(
              new InvitationMessage(
                  invitation.email(),
                  invitation.locale(),
                  organization.name(),
                  organization.timezone(),
                  invitation.role(),
                  invitation.expiresAt(),
                  properties.webBaseUrl() + "/invitation#token=" + token.secret()));
    } catch (RuntimeException unexpected) {
      result = DeliveryState.FAILED;
    }
    if (result != DeliveryState.SENT) {
      result = DeliveryState.FAILED;
    }
    boolean recorded;
    try {
      recorded =
          invitations.recordDelivery(
              invitation.tenant(),
              invitation.id(),
              invitation.issueCount(),
              result,
              Instant.now(clock));
    } catch (RuntimeException unrecorded) {
      // The stale-delivery job turns the remaining QUEUED into FAILED.
      recorded = false;
    }
    String outcome = result.name().toLowerCase(Locale.ROOT);
    Counter.builder(METRIC)
        .description("Invitation email delivery attempts")
        .tag("result", outcome)
        .register(registry)
        .increment();
    LOG.atInfo()
        .addKeyValue("invitationId", invitation.id())
        .addKeyValue("issue", invitation.issueCount())
        .addKeyValue("result", outcome)
        .addKeyValue("recorded", recorded)
        .log("invitation_delivery_" + outcome);
    return result;
  }
}
