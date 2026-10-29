package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Compensation outbox events are internal: no webhook and no WhatsApp notification maps them. */
class CompensationEventsNotDeliveredTest {

  private static final List<String> TYPES =
      List.of(
          "payroll.compensation.established.v1",
          "payroll.compensation.changed.v1",
          "payroll.compensation.corrected.v1",
          "payroll.compensation.scheduled-change-cancelled.v1");

  @Test
  void noPublicEventTypeMapsACompensationEvent() {
    for (String type : TYPES) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
  }
}
