package com.divalhr.core.integrations.messaging.domain;

import com.divalhr.core.integrations.domain.WebhookEventType;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed WhatsApp notification catalogue (D111-6). Each type has exactly one allow-listed
 * relative deep-link path and one generic label per language; no personal, HR or identifier data is
 * ever a template parameter. A type is reached from a committed business event only through the
 * MVP-110 public-event mapper ({@link WebhookEventType#fromInternal}).
 */
public enum NotificationType {
  /** A leave request was approved or rejected. */
  LEAVE_DECISION("/admin/leave-approvals", "Leave decision", "Décision sur un congé"),
  /** A timesheet was approved. */
  TIMESHEET_APPROVED("/admin/timesheets", "Timesheet approved", "Feuille de temps approuvée"),
  /** An attendance record was corrected. */
  ATTENDANCE_CORRECTED("/admin/attendance-review", "Attendance corrected", "Présence corrigée"),
  /** A payroll input run was locked. */
  PAYROLL_INPUT_LOCKED(
      "/admin/payroll-preparation", "Payroll inputs locked", "Éléments de paie verrouillés"),
  /** A payroll input export is ready. */
  PAYROLL_EXPORT_READY("/admin/payroll-preparation", "Payroll export ready", "Export de paie prêt"),
  /** The administrator's connector test; never produced by a business event. */
  CONNECTOR_TEST("/admin/integrations/whatsapp", "Connection test", "Test de connexion");

  private final String path;
  private final String labelEn;
  private final String labelFr;

  NotificationType(String path, String labelEn, String labelFr) {
    this.path = path;
    this.labelEn = labelEn;
    this.labelFr = labelFr;
  }

  /**
   * @return the single allow-listed relative path of the deep link (leading slash)
   */
  public String path() {
    return path;
  }

  /**
   * The generic closed-enum label a template body may take as its only text parameter.
   *
   * @param language recipient language
   * @return the label
   */
  public String label(MessagingLanguage language) {
    return language == MessagingLanguage.FR ? labelFr : labelEn;
  }

  /**
   * @return whether an administrator may select it for a recipient (everything but the test)
   */
  public boolean selectable() {
    return this != CONNECTOR_TEST;
  }

  /**
   * @return the selectable types in catalogue order
   */
  public static List<NotificationType> selectableTypes() {
    return Arrays.stream(values()).filter(NotificationType::selectable).toList();
  }

  /**
   * @return the selectable codes
   */
  public static Set<String> selectableCodes() {
    return selectableTypes().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Looks a code up.
   *
   * @param value candidate
   * @return the type, or empty when it is not in the catalogue
   */
  public static Optional<NotificationType> of(String value) {
    return Arrays.stream(values()).filter(t -> t.name().equals(value)).findFirst();
  }

  /**
   * Maps an internal outbox event type to the notification it produces, through the public event.
   *
   * @param internalType internal outbox event type
   * @return the notification type, or empty when the event is not notified
   */
  public static Optional<NotificationType> fromInternal(String internalType) {
    return WebhookEventType.fromInternal(internalType)
        .map(
            event ->
                switch (event) {
                  case LEAVE_REQUEST_APPROVED, LEAVE_REQUEST_REJECTED -> LEAVE_DECISION;
                  case TIMESHEET_APPROVED -> TIMESHEET_APPROVED;
                  case ATTENDANCE_CORRECTED -> ATTENDANCE_CORRECTED;
                  case PAYROLL_INPUT_LOCKED -> PAYROLL_INPUT_LOCKED;
                  case PAYROLL_INPUT_EXPORT_CREATED -> PAYROLL_EXPORT_READY;
                  case WEBHOOK_TEST -> null;
                });
  }
}
