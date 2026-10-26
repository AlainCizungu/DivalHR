package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.integrations.domain.WebhookEventType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-6): the closed catalogue, and the single mapping from business events. */
class NotificationTypeTest {

  @Test
  void theCatalogueIsClosedAndOnlyTheTestIsNotSelectable() {
    assertThat(NotificationType.values())
        .extracting(Enum::name)
        .containsExactly(
            "LEAVE_DECISION",
            "TIMESHEET_APPROVED",
            "ATTENDANCE_CORRECTED",
            "PAYROLL_INPUT_LOCKED",
            "PAYROLL_EXPORT_READY",
            "CONNECTOR_TEST");
    assertThat(NotificationType.selectableTypes())
        .hasSize(5)
        .doesNotContain(NotificationType.CONNECTOR_TEST);
    assertThat(NotificationType.selectableCodes()).hasSize(5).doesNotContain("CONNECTOR_TEST");
    assertThat(NotificationType.CONNECTOR_TEST.selectable()).isFalse();
    assertThat(NotificationType.of("LEAVE_DECISION")).contains(NotificationType.LEAVE_DECISION);
    assertThat(NotificationType.of("leave_decision")).isEmpty();
    assertThat(NotificationType.of(null)).isEmpty();
  }

  @Test
  void everyInternalEventTypeOfTheSharedMapperMapsToAnExpectedNotification() {
    assertThat(NotificationType.fromInternal("people.leave-request.approved.v1"))
        .contains(NotificationType.LEAVE_DECISION);
    assertThat(NotificationType.fromInternal("people.leave-request.rejected.v1"))
        .contains(NotificationType.LEAVE_DECISION);
    assertThat(NotificationType.fromInternal("people.timesheet.approved.v1"))
        .contains(NotificationType.TIMESHEET_APPROVED);
    assertThat(NotificationType.fromInternal("people.attendance.corrected.v1"))
        .contains(NotificationType.ATTENDANCE_CORRECTED);
    assertThat(NotificationType.fromInternal("payroll.payroll-input.locked.v1"))
        .contains(NotificationType.PAYROLL_INPUT_LOCKED);
    assertThat(NotificationType.fromInternal("payroll.payroll-input-export.created.v1"))
        .contains(NotificationType.PAYROLL_EXPORT_READY);
    for (String internal : WebhookEventType.INTERNAL_TYPES) {
      assertThat(NotificationType.fromInternal(internal)).as(internal).isPresent();
    }
    assertThat(NotificationType.fromInternal("integrations.messaging-connector.created.v1"))
        .isEmpty();
    assertThat(NotificationType.fromInternal("unknown.event.v1")).isEmpty();
    assertThat(NotificationType.fromInternal(null)).isEmpty();
  }

  @Test
  void aTestIsNeverProducedByABusinessEvent() {
    for (String internal : WebhookEventType.INTERNAL_TYPES) {
      assertThat(NotificationType.fromInternal(internal))
          .isNotEqualTo(java.util.Optional.of(NotificationType.CONNECTOR_TEST));
    }
  }

  @Test
  void everyTypeHasAGenericBilingualLabelWithoutPersonalOrIdentifyingData() {
    for (NotificationType type : NotificationType.values()) {
      List<String> labels =
          List.of(type.label(MessagingLanguage.EN), type.label(MessagingLanguage.FR));
      for (String label : labels) {
        assertThat(label)
            .isNotBlank()
            .hasSizeLessThanOrEqualTo(60)
            .doesNotContain("{", "}", "@", "http", "\n");
        assertThat(label).doesNotMatch(".*[0-9]{3,}.*");
      }
      assertThat(labels.get(0)).isNotEqualTo(labels.get(1));
    }
    assertThat(NotificationType.LEAVE_DECISION.label(MessagingLanguage.FR)).contains("congé");
  }

  @Test
  void languagesAreExactlyEnglishAndFrench() {
    assertThat(MessagingLanguage.of("en")).contains(MessagingLanguage.EN);
    assertThat(MessagingLanguage.of("fr")).contains(MessagingLanguage.FR);
    for (Object bad : new Object[] {null, "EN", "fr-FR", "de", "", 1}) {
      assertThat(MessagingLanguage.of(bad)).as(String.valueOf(bad)).isEmpty();
    }
    assertThat(ConsentSource.of("WRITTEN")).contains(ConsentSource.WRITTEN);
    assertThat(ConsentSource.of("written")).isEmpty();
    assertThat(ConsentSource.of("UNKNOWN")).isEmpty();
  }
}
