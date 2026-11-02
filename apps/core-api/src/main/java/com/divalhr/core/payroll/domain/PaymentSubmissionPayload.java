package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The canonical provider payload of a batch (MVP-095B, D95B-3/D95B-4): UTF-8 JSON with sorted keys,
 * no whitespace and integers only, built in memory per attempt and signed byte for byte. Restricted
 * financial data: the destination fields exist only in the returned bytes. {@code toString} is
 * redacted and {@link #wipe(byte[])} clears a buffer best-effort.
 *
 * <p>Nothing is calculated here: every amount is the batch line's immutable net minor units.
 */
public final class PaymentSubmissionPayload {

  /** Schema version of the payload. */
  public static final int SCHEMA_VERSION = 1;

  /**
   * One line of the payload.
   *
   * @param lineRef the batch line ID
   * @param amountMinor net minor units copied from the batch line
   * @param method {@code BANK_ACCOUNT} or {@code MOBILE_MONEY}
   * @param provider controlled destination provider code
   * @param country ISO country
   * @param account bank account (BANK_ACCOUNT only)
   * @param branch optional branch (BANK_ACCOUNT only)
   * @param phone E.164 phone (MOBILE_MONEY only)
   * @param holder holder name
   */
  public record Line(
      UUID lineRef,
      long amountMinor,
      PaymentMethod method,
      String provider,
      String country,
      String account,
      String branch,
      String phone,
      String holder) {
    @Override
    public String toString() {
      return "Line[redacted]";
    }
  }

  private PaymentSubmissionPayload() {}

  /**
   * Builds the canonical bytes.
   *
   * @param requestId the stable request ID
   * @param batchDigest the batch digest
   * @param currency the single currency
   * @param totalMinor the batch total (must equal the sum of the lines)
   * @param lines the lines, in batch line ID order
   * @return the canonical UTF-8 bytes
   * @throws IllegalArgumentException when the lines are inconsistent with the total or a method is
   *     not submittable
   */
  public static byte[] canonical(
      UUID requestId, String batchDigest, String currency, long totalMinor, List<Line> lines) {
    long sum = 0;
    for (Line line : lines) {
      if (line.method() == PaymentMethod.MANUAL || line.amountMinor() < 0) {
        throw new IllegalArgumentException("line is not submittable");
      }
      sum = Math.addExact(sum, line.amountMinor());
    }
    if (sum != totalMinor || lines.isEmpty()) {
      throw new IllegalArgumentException("lines do not add up to the batch total");
    }
    StringBuilder json = new StringBuilder(256 + lines.size() * 220);
    json.append("{\"batchDigest\":");
    string(json, batchDigest);
    json.append(",\"currency\":");
    string(json, currency);
    json.append(",\"lineCount\":").append(lines.size());
    json.append(",\"lines\":[");
    boolean first = true;
    for (Line line : lines) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('{');
      boolean bank = line.method() == PaymentMethod.BANK_ACCOUNT;
      json.append("\"account\":");
      if (bank) {
        string(json, line.account());
      } else {
        json.append("null");
      }
      json.append(",\"amountMinor\":").append(line.amountMinor());
      json.append(",\"branch\":");
      if (bank && line.branch() != null) {
        string(json, line.branch());
      } else {
        json.append("null");
      }
      json.append(",\"country\":");
      string(json, line.country());
      json.append(",\"holder\":");
      string(json, line.holder());
      json.append(",\"lineRef\":");
      string(json, line.lineRef().toString());
      json.append(",\"method\":");
      string(json, line.method().name());
      json.append(",\"phone\":");
      if (!bank) {
        string(json, line.phone());
      } else {
        json.append("null");
      }
      json.append(",\"provider\":");
      string(json, line.provider());
      json.append('}');
    }
    json.append("],\"requestId\":");
    string(json, requestId.toString());
    json.append(",\"schemaVersion\":").append(SCHEMA_VERSION);
    json.append(",\"totalMinor\":").append(totalMinor).append('}');
    byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
    json.setLength(0);
    return bytes;
  }

  /**
   * Clears a buffer best-effort.
   *
   * @param bytes the buffer, or {@code null}
   */
  public static void wipe(byte[] bytes) {
    if (bytes != null) {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static void string(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
