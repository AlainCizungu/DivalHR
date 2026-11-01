package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.payroll.application.PayrollPackageRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Payroll package approval and void outbox events are internal (MVP-094C, ADR 0035 section 14): no
 * webhook and no WhatsApp type maps them, so none of them can leave the platform, and no payslip
 * event exists.
 */
class PayrollPackageEventsNotDeliveredTest {

  private static final List<String> TYPES =
      List.of("payroll.package.approved.v1", "payroll.package.voided.v1");

  @Test
  void theRecorderEmitsExactlyTheTwoInternalTypes() {
    assertThat(List.of(PayrollPackageRecorder.APPROVED_EVENT, PayrollPackageRecorder.VOIDED_EVENT))
        .containsExactlyElementsOf(TYPES);
  }

  @Test
  void noPublicEventTypeMapsAPayrollPackageEvent() {
    for (String type : TYPES) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
    // Nor does a payslip event exist, in any spelling.
    for (String type :
        List.of(
            "payroll.payslip.created.v1",
            "payroll.payslip.downloaded.v1",
            "payroll.payslip.read.v1",
            "payroll.package.read.v1")) {
      assertThat(WebhookEventType.fromInternal(type)).as(type).isEmpty();
      assertThat(NotificationType.fromInternal(type)).as(type).isEmpty();
    }
  }

  @Test
  void theRecorderDeclaresNoOtherEventType() {
    List<String> declared = new ArrayList<>();
    for (Field field : PayrollPackageRecorder.class.getDeclaredFields()) {
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
