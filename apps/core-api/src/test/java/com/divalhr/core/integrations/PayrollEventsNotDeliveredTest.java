package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Payroll policy and run outbox events are internal: no webhook and no WhatsApp type maps them. */
class PayrollEventsNotDeliveredTest {

  private static final List<String> TYPES =
      List.of("payroll.payroll-policy.changed.v1", "payroll.payroll-run.created.v1");

  @Test
  void noPublicEventTypeMapsAPayrollPolicyOrRunEvent() {
    for (String type : TYPES) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
  }
}
