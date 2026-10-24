package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.messaging.domain.Connector;
import com.divalhr.core.integrations.messaging.domain.MessagingDelivery;
import com.divalhr.core.integrations.messaging.domain.Recipient;
import com.divalhr.core.integrations.messaging.domain.TemplateConfig;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.AttemptView;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.CallbackView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of the WhatsApp management API (D111-12). None carries a token, a full number, a
 * provider message id, a message body or a deep link.
 */
public final class MessagingViews {

  private MessagingViews() {}

  /**
   * One configured template.
   *
   * @param notificationType notification type
   * @param language en or fr
   * @param templateName Meta template name (configuration)
   * @param verified whether the provider confirmed it for the current configuration
   */
  public record TemplateView(
      String notificationType, String language, String templateName, boolean verified) {
    static TemplateView of(TemplateConfig t) {
      return new TemplateView(
          t.type().name(), t.language().code(), t.name(), t.verifiedAt() != null);
    }
  }

  /**
   * The connector as the administrator sees it.
   *
   * @param id connector id
   * @param provider always WHATSAPP
   * @param state DRAFT, ACTIVE, DISABLED or SUSPENDED
   * @param configVersion optimistic configuration version
   * @param credentialVersion current credential version
   * @param wabaId WhatsApp Business Account id
   * @param phoneNumberId sending phone-number id
   * @param displayNumber sending number shown to recipients
   * @param graphApiVersion the operator-pinned Graph API version
   * @param verifiedAt last provider verification
   * @param consecutiveFailures consecutive terminally failed deliveries
   * @param lastSuccessAt last delivered or read message
   * @param lastFailureAt last terminal failure
   * @param recipientCount recipients registered
   * @param templates configured templates
   * @param createdAt created
   * @param updatedAt updated
   */
  public record ConnectorView(
      UUID id,
      String provider,
      String state,
      int configVersion,
      int credentialVersion,
      String wabaId,
      String phoneNumberId,
      String displayNumber,
      String graphApiVersion,
      Instant verifiedAt,
      int consecutiveFailures,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      int recipientCount,
      List<TemplateView> templates,
      Instant createdAt,
      Instant updatedAt) {
    /** Copies the list. */
    public ConnectorView {
      templates = List.copyOf(templates);
    }

    static ConnectorView of(
        Connector c, List<TemplateConfig> templates, int recipients, String graphVersion) {
      return new ConnectorView(
          c.id(),
          "WHATSAPP",
          c.state().name(),
          c.configVersion(),
          c.credentialVersion(),
          c.wabaId(),
          c.phoneNumberId(),
          c.displayNumber(),
          graphVersion,
          c.verifiedAt(),
          c.consecutiveFailures(),
          c.lastSuccessAt(),
          c.lastFailureAt(),
          recipients,
          templates.stream().map(TemplateView::of).toList(),
          c.createdAt(),
          c.updatedAt());
    }
  }

  /**
   * One template's provider check.
   *
   * @param notificationType notification type
   * @param language en or fr
   * @param result OK, MISSING, NOT_APPROVED, WRONG_LANGUAGE, WRONG_SHAPE, CREDENTIAL_REJECTED,
   *     UNAVAILABLE or NOT_CONFIGURED
   */
  public record TemplateCheckView(String notificationType, String language, String result) {}

  /**
   * The result of a provider verification.
   *
   * @param connector the connector after the command
   * @param credential VALID, REJECTED or UNAVAILABLE
   * @param templates one verdict per notification type and language
   * @param verified whether credential and every template passed
   */
  public record VerificationView(
      ConnectorView connector,
      String credential,
      List<TemplateCheckView> templates,
      boolean verified) {
    /** Copies the list. */
    public VerificationView {
      templates = List.copyOf(templates);
    }
  }

  /**
   * A recipient as the administrator sees it. The full number is never returned.
   *
   * @param id recipient id
   * @param label label
   * @param numberMasked masked number
   * @param language en or fr
   * @param notificationTypes selected notification types
   * @param status ACTIVE or DISABLED
   * @param consentSource how the opt-in was obtained
   * @param consentConfirmedAt when it was confirmed
   * @param version optimistic version
   * @param createdAt created
   * @param updatedAt updated
   */
  public record RecipientView(
      UUID id,
      String label,
      String numberMasked,
      String language,
      List<String> notificationTypes,
      String status,
      String consentSource,
      Instant consentConfirmedAt,
      int version,
      Instant createdAt,
      Instant updatedAt) {
    /** Copies the list. */
    public RecipientView {
      notificationTypes = List.copyOf(notificationTypes);
    }

    static RecipientView of(Recipient r) {
      return new RecipientView(
          r.id(),
          r.label(),
          r.numberMasked(),
          r.language().code(),
          r.notificationTypes().stream().map(Enum::name).toList(),
          r.status().name(),
          r.consentSource().name(),
          r.consentConfirmedAt(),
          r.version(),
          r.createdAt(),
          r.updatedAt());
    }
  }

  /**
   * The tenant's recipients (at most 25, so no cursor).
   *
   * @param items rows, oldest first
   */
  public record RecipientList(List<RecipientView> items) {
    /** Copies the list. */
    public RecipientList {
      items = List.copyOf(items);
    }
  }

  /**
   * One delivery in history.
   *
   * @param id delivery id
   * @param recipientId recipient
   * @param recipientLabel the recipient's label
   * @param numberMasked the recipient's masked number
   * @param notificationType notification type
   * @param test whether it is an administrator test
   * @param language en or fr
   * @param state delivery state
   * @param attemptCount attempts made
   * @param createdAt created
   * @param lastAttemptAt last attempt
   * @param nextAttemptAt next scheduled attempt
   * @param acceptedAt provider acceptance
   * @param deliveredAt provider delivery report
   * @param readAt provider read report
   * @param completedAt final state reached
   * @param lastHttpStatus exact status of the last answer
   * @param failureClass closed failure class
   */
  public record WhatsAppDeliveryView(
      UUID id,
      UUID recipientId,
      String recipientLabel,
      String numberMasked,
      String notificationType,
      boolean test,
      String language,
      String state,
      int attemptCount,
      Instant createdAt,
      Instant lastAttemptAt,
      Instant nextAttemptAt,
      Instant acceptedAt,
      Instant deliveredAt,
      Instant readAt,
      Instant completedAt,
      Integer lastHttpStatus,
      String failureClass) {
    static WhatsAppDeliveryView of(MessagingDelivery d) {
      return new WhatsAppDeliveryView(
          d.id(),
          d.recipientId(),
          d.recipientLabel(),
          d.numberMasked(),
          d.type().name(),
          d.test(),
          d.language().code(),
          d.state().name(),
          d.attemptCount(),
          d.createdAt(),
          d.lastAttemptAt(),
          d.nextAttemptAt(),
          d.acceptedAt(),
          d.deliveredAt(),
          d.readAt(),
          d.completedAt(),
          d.lastHttpStatus(),
          d.failure() == null ? null : d.failure().name());
    }
  }

  /**
   * A page of deliveries.
   *
   * @param items rows
   * @param nextCursor opaque signed cursor, or null
   */
  public record WhatsAppDeliveryPage(List<WhatsAppDeliveryView> items, String nextCursor) {
    /** Copies the list. */
    public WhatsAppDeliveryPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One attempt.
   *
   * @param number attempt number
   * @param startedAt start
   * @param finishedAt end
   * @param httpStatus exact status when a response arrived
   * @param failureClass closed class, null when accepted
   * @param latencyMs wall time
   */
  public record WhatsAppAttemptItem(
      int number,
      Instant startedAt,
      Instant finishedAt,
      Integer httpStatus,
      String failureClass,
      int latencyMs) {
    static WhatsAppAttemptItem of(AttemptView a) {
      return new WhatsAppAttemptItem(
          a.number(),
          a.startedAt(),
          a.finishedAt(),
          a.httpStatus(),
          a.failure() == null ? null : a.failure().name(),
          a.latencyMillis());
    }
  }

  /**
   * One verified provider status callback.
   *
   * @param status SENT, DELIVERED, READ or FAILED
   * @param occurredAt provider time
   * @param receivedAt our time
   * @param errorClass closed class for FAILED
   */
  public record StatusReportItem(
      String status, Instant occurredAt, Instant receivedAt, String errorClass) {
    static StatusReportItem of(CallbackView c) {
      return new StatusReportItem(
          c.status(),
          c.occurredAt(),
          c.receivedAt(),
          c.errorClass() == null ? null : c.errorClass().name());
    }
  }

  /**
   * A delivery with its attempts and provider callbacks.
   *
   * @param delivery the delivery
   * @param attempts attempts, oldest first
   * @param statusReports provider status reports, oldest first
   */
  public record WhatsAppDeliveryDetail(
      WhatsAppDeliveryView delivery,
      List<WhatsAppAttemptItem> attempts,
      List<StatusReportItem> statusReports) {
    /** Copies the lists. */
    public WhatsAppDeliveryDetail {
      attempts = List.copyOf(attempts);
      statusReports = List.copyOf(statusReports);
    }
  }

  /**
   * An accepted test message.
   *
   * @param deliveryId the delivery to follow in the history
   * @param recipientId the recipient
   * @param eventId the test event id
   */
  public record WhatsAppTestDeliveryResult(UUID deliveryId, UUID recipientId, UUID eventId) {}
}
