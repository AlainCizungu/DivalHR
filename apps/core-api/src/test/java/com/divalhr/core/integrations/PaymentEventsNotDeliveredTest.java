package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.payroll.application.PaymentRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Payment destination and payment batch outbox events are internal (MVP-095A, ADR 0036, R184-1): no
 * webhook and no WhatsApp type maps them, the fan-out allow-list does not contain them, and no
 * other payment event exists, so none of them can leave the platform.
 */
class PaymentEventsNotDeliveredTest {

  private static final List<String> TYPES =
      List.of(
          "payroll.payment-destination.recorded.v1",
          "payroll.payment-batch.prepared.v1",
          "payroll.payment-batch.cancelled.v1");

  @Test
  void theRecorderEmitsExactlyTheThreeInternalTypes() {
    assertThat(
            List.of(
                PaymentRecorder.DESTINATION_EVENT,
                PaymentRecorder.PREPARED_EVENT,
                PaymentRecorder.CANCELLED_EVENT))
        .containsExactlyElementsOf(TYPES);
  }

  @Test
  void theFanOutAllowListsNameNoPaymentEventAndEveryPublicTypeIsOutsidePayments() {
    assertThat(WebhookEventType.INTERNAL_TYPES).doesNotContainAnyElementsOf(TYPES);
    assertThat(WebhookEventType.INTERNAL_TYPES).noneMatch(t -> t.startsWith("payroll.payment"));
    for (WebhookEventType type : WebhookEventType.values()) {
      assertThat(type.code()).as(type.name()).doesNotContain("payment");
      assertThat(type.link().orElse("")).as(type.name()).doesNotContain("payment");
    }
    for (NotificationType type : NotificationType.values()) {
      assertThat(type.name()).as(type.name()).doesNotContain("PAYMENT");
    }
    assertThat(WebhookEventType.subscribableCodes()).noneMatch(code -> code.contains("payment"));
  }

  @Test
  void noPublicEventTypeMapsAPaymentEvent() {
    for (String type : TYPES) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
    // Nor does a download, read, or destination-change spelling, or a version bump.
    for (String type :
        List.of(
            "payroll.payment-batch.downloaded.v1",
            "payroll.payment-batch.read.v1",
            "payroll.payment-destination.read.v1",
            "payroll.payment-destination.recorded.v2",
            "payroll.payment-batch.paid.v1",
            "payroll.payment.sent.v1")) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
  }

  @Test
  void theRecorderDeclaresNoOtherEventType() {
    List<String> declared = new ArrayList<>();
    for (Field field : PaymentRecorder.class.getDeclaredFields()) {
      if (Modifier.isPublic(field.getModifiers())
          && Modifier.isStatic(field.getModifiers())
          && field.getType() == String.class) {
        try {
          String value = (String) field.get(null);
          if (value != null && value.startsWith("payroll.") && value.endsWith(".v1")) {
            declared.add(value);
          }
        } catch (IllegalAccessException inaccessible) {
          throw new AssertionError(field.getName(), inaccessible);
        }
      }
    }
    assertThat(declared).containsExactlyInAnyOrderElementsOf(TYPES);
  }
}
