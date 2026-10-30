package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.payroll.application.StatutoryRecorder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Statutory activation, profile and calculation outbox events are internal (MVP-094B, ADR 0034
 * section 8): no webhook and no WhatsApp type maps them, so none of them can leave the platform.
 */
class StatutoryEventsNotDeliveredTest {

  private static final List<String> TYPES =
      List.of(
          "payroll.statutory-activation.created.v1",
          "payroll.statutory-profile.changed.v1",
          "payroll.statutory-calculation.created.v1");

  @Test
  void theRecorderEmitsExactlyTheThreeInternalTypes() {
    assertThat(
            List.of(
                StatutoryRecorder.ACTIVATION_EVENT,
                StatutoryRecorder.PROFILE_EVENT,
                StatutoryRecorder.CALCULATION_EVENT))
        .containsExactlyElementsOf(TYPES);
  }

  @Test
  void noPublicEventTypeMapsAStatutoryEvent() {
    for (String type : TYPES) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
  }
}
