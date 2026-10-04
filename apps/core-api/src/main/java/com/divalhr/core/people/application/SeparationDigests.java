package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.people.domain.history.TimelinePlan;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.util.List;
import java.util.UUID;

/**
 * Digests v1 of MVP-022: SHA-256 (lower-case hex) of exact UTF-8 texts, every line ending with LF.
 * They cover Restricted HR values, so they are only compared or stored as integrity references
 * (audit {@code after_state_sha256}, A22-6), never logged.
 *
 * <pre>
 * DIVALHR-SEPARATION-PREVIEW
 * version=1
 * employment=&lt;id&gt;
 * employmentVersion=&lt;n&gt;
 * command=&lt;canonical command&gt;
 * timing=&lt;timing&gt;
 * accessEndsAt=&lt;instant|commit|-&gt;
 * access=&lt;status&gt;;link=&lt;id|-&gt;;linkVersion=&lt;n|-&gt;
 * own.supersede=&lt;rowId&gt;  own.create=...       (the employee's own plan)
 * blocker=&lt;rowId&gt;                              (A22-3; must be empty to commit)
 * report=&lt;employeeId&gt;;&lt;employmentId&gt;;intervals=&lt;n&gt;
 * interval=&lt;rowId&gt;,&lt;rowId&gt;...                   (A22-2; ordered row IDs per interval)
 * intervals=&lt;total&gt;
 * checklist=&lt;code&gt;;&lt;due&gt;
 * </pre>
 */
final class SeparationDigests {

  private SeparationDigests() {}

  /** Builds a digest text. */
  static final class Text {

    private final StringBuilder text;

    Text(String name, UUID employmentId, long employmentVersion) {
      text =
          new StringBuilder(name)
              .append("\nversion=")
              .append(EmploymentHistoryDigests.VERSION)
              .append("\nemployment=")
              .append(employmentId)
              .append("\nemploymentVersion=")
              .append(employmentVersion)
              .append('\n');
    }

    Text line(String key, Object value) {
      text.append(key).append('=').append(value == null ? "-" : value.toString()).append('\n');
      return this;
    }

    Text plan(String prefix, TimelinePlan plan) {
      for (Assignment row : plan.superseded()) {
        line(prefix + ".supersede", row.id());
      }
      for (NewAssignment row : plan.created()) {
        line(
            prefix + ".create",
            row.kind().name()
                + ';'
                + row.from()
                + ';'
                + (row.to() == null ? "-" : row.to().toString())
                + ';'
                + row.value().canonical()
                + ";restores="
                + (row.restores() == null ? "-" : row.restores().toString()));
      }
      return this;
    }

    Text rows(String key, List<Assignment> rows) {
      return line(key, String.join(",", rows.stream().map(r -> r.id().toString()).toList()));
    }

    String sha256() {
      return Fingerprints.sha256(text.toString());
    }
  }

  /**
   * Digest of identifiers and closed codes (audit integrity of small transitions).
   *
   * @param kind what it is
   * @param id main identifier
   * @param parts further identifiers or codes
   * @return the digest
   */
  static String identifiers(String kind, UUID id, String... parts) {
    StringBuilder text =
        new StringBuilder("DIVALHR-SEPARATION-IDS\nversion=1\nkind=")
            .append(kind)
            .append("\nid=")
            .append(id)
            .append('\n');
    for (String part : parts) {
      text.append("part=").append(part).append('\n');
    }
    return Fingerprints.sha256(text.toString());
  }
}
