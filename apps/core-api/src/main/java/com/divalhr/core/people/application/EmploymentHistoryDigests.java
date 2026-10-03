package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.ChangeTiming;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Digests v1 of MVP-021: the SHA-256 (lower-case hex) of exact UTF-8 texts, every line ending with
 * LF. They cover Restricted HR values, so they are only ever compared or stored as integrity
 * references, never logged.
 *
 * <pre>
 * DIVALHR-EMPLOYMENT-CHANGE-PREVIEW            DIVALHR-EMPLOYMENT-CANCELLATION-PREVIEW
 * version=1                                    version=1
 * employment=&lt;id&gt;                              employment=&lt;id&gt;
 * employmentVersion=&lt;n&gt;                        employmentVersion=&lt;n&gt;
 * timing=&lt;timing&gt;                              cancels=&lt;changeId&gt;
 * command=&lt;canonical command&gt;
 * supersede=&lt;rowId&gt;                     (per superseded row, by kind then start)
 * create=&lt;kind&gt;;&lt;from&gt;;&lt;to|-&gt;;&lt;value&gt;;origin=&lt;self|changeId&gt;;restores=&lt;rowId|-&gt;
 * </pre>
 *
 * <p>New row IDs are random and never part of a digest; the recorded change's own ID is written
 * {@code self}, so a preview and its commit agree. The timeline digest ({@code
 * DIVALHR-EMPLOYMENT-TIMELINE}) covers every active row after a write and is the audit integrity
 * reference ({@code after_state_sha256}, M21-1).
 */
final class EmploymentHistoryDigests {

  /** Version of every MVP-021 digest and audit metadata schema. */
  static final int VERSION = 1;

  private EmploymentHistoryDigests() {}

  static String preview(
      UUID employmentId,
      long employmentVersion,
      ChangeTiming timing,
      EmploymentChangeCommand command,
      UUID self,
      TimelinePlan plan) {
    return Fingerprints.sha256(
        previewText(employmentId, employmentVersion, timing, command, self, plan));
  }

  static String previewText(
      UUID employmentId,
      long employmentVersion,
      ChangeTiming timing,
      EmploymentChangeCommand command,
      UUID self,
      TimelinePlan plan) {
    StringBuilder text =
        header("DIVALHR-EMPLOYMENT-CHANGE-PREVIEW", employmentId, employmentVersion);
    text.append("timing=").append(timing.name()).append('\n');
    text.append("command=").append(command.canonical()).append('\n');
    rows(text, self, plan);
    return text.toString();
  }

  static String cancellation(
      UUID employmentId, long employmentVersion, UUID cancelledId, TimelinePlan plan) {
    return Fingerprints.sha256(
        cancellationText(employmentId, employmentVersion, cancelledId, plan));
  }

  static String cancellationText(
      UUID employmentId, long employmentVersion, UUID cancelledId, TimelinePlan plan) {
    StringBuilder text =
        header("DIVALHR-EMPLOYMENT-CANCELLATION-PREVIEW", employmentId, employmentVersion);
    text.append("cancels=").append(cancelledId).append('\n');
    rows(text, null, plan);
    return text.toString();
  }

  static String timeline(UUID employmentId, long employmentVersion, List<Assignment> rows) {
    StringBuilder text = header("DIVALHR-EMPLOYMENT-TIMELINE", employmentId, employmentVersion);
    rows.stream()
        .filter(Assignment::active)
        .sorted(
            Comparator.comparing(Assignment::kind)
                .thenComparing(Assignment::from)
                .thenComparing(Assignment::id))
        .forEach(
            row ->
                text.append("row=")
                    .append(row.id())
                    .append(';')
                    .append(row.kind().name())
                    .append(';')
                    .append(row.from())
                    .append(';')
                    .append(date(row.to()))
                    .append(';')
                    .append(row.value().canonical())
                    .append(";origin=")
                    .append(row.origin())
                    .append('\n'));
    return Fingerprints.sha256(text.toString());
  }

  /**
   * Integrity reference of a disclosure: the view and the disclosed IDs in response order, never
   * the values.
   */
  static String disclosure(String view, List<UUID> ids) {
    StringBuilder text = new StringBuilder("DIVALHR-EMPLOYEE-DISCLOSURE\nversion=1\n");
    text.append("view=").append(view).append('\n');
    ids.forEach(id -> text.append("id=").append(id).append('\n'));
    return Fingerprints.sha256(text.toString());
  }

  private static StringBuilder header(String name, UUID employmentId, long employmentVersion) {
    return new StringBuilder(name)
        .append("\nversion=")
        .append(VERSION)
        .append("\nemployment=")
        .append(employmentId)
        .append("\nemploymentVersion=")
        .append(employmentVersion)
        .append('\n');
  }

  private static void rows(StringBuilder text, UUID self, TimelinePlan plan) {
    for (Assignment row : plan.superseded()) {
      text.append("supersede=").append(row.id()).append('\n');
    }
    for (NewAssignment row : plan.created()) {
      text.append("create=")
          .append(row.kind().name())
          .append(';')
          .append(row.from())
          .append(';')
          .append(date(row.to()))
          .append(';')
          .append(row.value().canonical())
          .append(";origin=")
          .append(row.origin().equals(self) ? "self" : row.origin().toString())
          .append(";restores=")
          .append(row.restores() == null ? "-" : row.restores().toString())
          .append('\n');
    }
  }

  private static String date(LocalDate date) {
    return date == null ? "-" : date.toString();
  }
}
