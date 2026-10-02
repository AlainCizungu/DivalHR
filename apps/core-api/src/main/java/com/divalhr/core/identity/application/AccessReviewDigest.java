package com.divalhr.core.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Canonical disclosure digest of the access review, version 1 (MVP-012B; architect decisions A4, R1
 * and B1). It is the audit row's {@code after_state_sha256}: tamper-evidence that exactly these
 * membership IDs (or these role counts) were disclosed in this order, not a reconstruction of
 * access after memberships change.
 *
 * <p>Exact bytes: UTF-8; every line, including the last, ends with LF (0x0A); no blank lines, no
 * carriage returns, no locale formatting, no JSON; canonical lower-case UUIDs; decimal integers
 * without signs or leading zeros:
 *
 * <pre>
 * DIVALHR-ACCESS-REVIEW-DIGEST
 * version=1
 * view=list|lookup|summary
 * page=first|next|single          (list: first or next; lookup and summary: single)
 * more=true|false                 (list: whether nextCursor was returned; otherwise false)
 * count=N                         (entries returned; summary: 2)
 * m=UUID                          (list and lookup: one line per entry, in response order)
 * role=tenant-admin;n=N           (summary only, first)
 * role=employee;n=N               (summary only, second)
 * </pre>
 *
 * <p>The digest is lower-case hexadecimal SHA-256. Any structural change needs a new version. The
 * unhashed text is never logged or stored.
 */
public final class AccessReviewDigest {

  /** Digest version recorded in the audit metadata. */
  public static final int VERSION = 1;

  private static final String HEADER = "DIVALHR-ACCESS-REVIEW-DIGEST";

  private AccessReviewDigest() {}

  /**
   * Canonical text of a list page.
   *
   * @param firstPage whether the request carried no cursor
   * @param more whether a next cursor was returned
   * @param membershipIds disclosed IDs in response order
   * @return canonical text
   */
  public static String listText(boolean firstPage, boolean more, List<UUID> membershipIds) {
    return entries("list", firstPage ? "first" : "next", more, membershipIds);
  }

  /**
   * Canonical text of a lookup.
   *
   * @param membershipIds zero or one disclosed ID
   * @return canonical text
   */
  public static String lookupText(List<UUID> membershipIds) {
    return entries("lookup", "single", false, membershipIds);
  }

  /**
   * Canonical text of a summary.
   *
   * @param tenantAdmins tenant-admin count
   * @param employees employee count
   * @return canonical text
   */
  public static String summaryText(long tenantAdmins, long employees) {
    return header("summary", "single", false, 2)
        + "role=tenant-admin;n="
        + count(tenantAdmins)
        + "\n"
        + "role=employee;n="
        + count(employees)
        + "\n";
  }

  /**
   * Lower-case hexadecimal SHA-256 of the UTF-8 bytes of a canonical text.
   *
   * @param canonical canonical text
   * @return 64 hexadecimal characters
   */
  public static String sha256(String canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String entries(String view, String page, boolean more, List<UUID> ids) {
    StringBuilder text = new StringBuilder(header(view, page, more, ids.size()));
    for (UUID id : ids) {
      text.append("m=").append(id.toString().toLowerCase(Locale.ROOT)).append('\n');
    }
    return text.toString();
  }

  private static String header(String view, String page, boolean more, long count) {
    return HEADER
        + "\n"
        + "version="
        + VERSION
        + "\n"
        + "view="
        + view
        + "\n"
        + "page="
        + page
        + "\n"
        + "more="
        + more
        + "\n"
        + "count="
        + count(count)
        + "\n";
  }

  private static String count(long value) {
    if (value < 0) {
      throw new IllegalArgumentException("negative count");
    }
    return Long.toString(value);
  }
}
