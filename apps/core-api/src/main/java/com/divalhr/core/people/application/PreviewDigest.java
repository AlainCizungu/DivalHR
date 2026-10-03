package com.divalhr.core.people.application;

import com.divalhr.core.people.domain.ImportRow;
import com.divalhr.core.people.domain.RowError;
import com.divalhr.core.people.domain.RowValues;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.util.List;

/**
 * Digest v1 of an import preview (MVP-020, E9): the SHA-256 (lower-case hex) of these exact UTF-8
 * bytes, every line ending with LF, the last included:
 *
 * <pre>
 * DIVALHR-EMPLOYEE-IMPORT-PREVIEW
 * version=1
 * rows=&lt;n&gt;
 * r=&lt;row&gt;|VALID|&lt;number&gt;|&lt;given&gt;|&lt;family&gt;|&lt;date&gt;|&lt;le&gt;|&lt;site&gt;|&lt;dept&gt;|&lt;cc&gt;|&lt;team&gt;
 * r=&lt;row&gt;|INVALID|&lt;column&gt;:&lt;code&gt;,...
 * </pre>
 *
 * <p>Rows in row order; absent optional codes are {@code -} (never a valid code); values are the
 * normalized values, whose allow-lists exclude {@code |}, {@code ,}, {@code :} and line breaks. The
 * commit must repeat this digest, binding it to exactly what the administrator reviewed. The digest
 * covers personal data and is only ever compared or stored as an integrity reference.
 */
public final class PreviewDigest {

  private PreviewDigest() {}

  /**
   * Computes the digest.
   *
   * @param rows all rows in row order
   * @return lower-case hex SHA-256
   */
  public static String of(List<ImportRow> rows) {
    return Fingerprints.sha256(text(rows));
  }

  /**
   * The canonical text (tests pin it with golden vectors).
   *
   * @param rows all rows in row order
   * @return canonical text
   */
  static String text(List<ImportRow> rows) {
    StringBuilder out = new StringBuilder("DIVALHR-EMPLOYEE-IMPORT-PREVIEW\nversion=1\n");
    out.append("rows=").append(rows.size()).append('\n');
    for (ImportRow row : rows) {
      out.append("r=").append(row.rowNumber()).append('|');
      if (row.valid()) {
        RowValues v = row.values();
        out.append("VALID|")
            .append(v.employeeNumber())
            .append('|')
            .append(v.givenNames())
            .append('|')
            .append(v.familyName())
            .append('|')
            .append(v.startDate())
            .append('|')
            .append(v.legalEntityCode())
            .append('|')
            .append(v.siteCode())
            .append('|')
            .append(orDash(v.departmentCode()))
            .append('|')
            .append(orDash(v.costCenterCode()))
            .append('|')
            .append(orDash(v.teamCode()));
      } else {
        out.append("INVALID|");
        for (int i = 0; i < row.errors().size(); i++) {
          RowError error = row.errors().get(i);
          if (i > 0) {
            out.append(',');
          }
          out.append(error.column().key()).append(':').append(error.code().name());
        }
      }
      out.append('\n');
    }
    return out.toString();
  }

  private static String orDash(String value) {
    return value == null ? "-" : value;
  }
}
