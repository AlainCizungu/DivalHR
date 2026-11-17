package com.divalhr.core.payroll.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The minimal, closed partner payload of schema version 1 (ADR 0042 §4). It carries no name, email,
 * phone, address, identifier, destination, payslip, leave, attendance, performance, disciplinary
 * data, document, credential or MFA data. The canonical bytes (UTF-8 JSON, sorted keys, no
 * whitespace, integers only) are built once at authorization, stored as a digest only, and rebuilt
 * byte for byte for every attempt.
 *
 * @param applicationId opaque id for the partner (never the request id)
 * @param requestId the DivalHR request id
 * @param legalEntityRef opaque per-handoff reference of the legal entity (never the real id or
 *     display name)
 * @param employmentActive whether the employment is active
 * @param employmentStart employment start date
 * @param payrollReferenceMonth first day of the payroll reference month
 * @param currency {@code CDF} or {@code USD}
 * @param requestedMinor requested minor units
 * @param ceilingMinor employer ceiling minor units
 * @param consentVersion handoff consent version
 * @param consentSha256 handoff consent digest
 * @param issuedAt authorization instant
 * @param correlationId correlation id
 */
public record SalaryAdvanceHandoffPayload(
    UUID applicationId,
    UUID requestId,
    UUID legalEntityRef,
    boolean employmentActive,
    LocalDate employmentStart,
    LocalDate payrollReferenceMonth,
    String currency,
    long requestedMinor,
    long ceilingMinor,
    String consentVersion,
    String consentSha256,
    Instant issuedAt,
    UUID correlationId) {

  /** The payload schema version. */
  public static final int SCHEMA_VERSION = 1;

  /** The closed, enumerated categories of data shared with the partner. */
  public static final List<String> SHARED_CATEGORIES =
      List.of(
          "APPLICATION_REFERENCE",
          "REQUEST_REFERENCE",
          "LEGAL_ENTITY_REFERENCE",
          "EMPLOYMENT_STATUS",
          "EMPLOYMENT_START",
          "PAYROLL_REFERENCE_MONTH",
          "CURRENCY",
          "REQUESTED_AMOUNT",
          "EMPLOYER_CEILING",
          "EMPLOYER_CONFIRMATION",
          "CONSENT_EVIDENCE",
          "CORRELATION_REFERENCE");

  /**
   * The canonical bytes: sorted keys, no whitespace, integers and strings only.
   *
   * @return UTF-8 JSON bytes, identical for identical inputs
   */
  public byte[] canonicalBytes() {
    Map<String, Object> fields = new TreeMap<>();
    fields.put("applicationId", applicationId.toString());
    fields.put("ceilingMinor", ceilingMinor);
    fields.put("consentSha256", consentSha256);
    fields.put("consentVersion", consentVersion);
    fields.put("correlationId", correlationId.toString());
    fields.put("currency", currency);
    fields.put("employerConfirmed", true);
    fields.put("employmentActive", employmentActive);
    fields.put("employmentStart", employmentStart.toString());
    fields.put("issuedAt", issuedAt.toString());
    fields.put("legalEntityRef", legalEntityRef.toString());
    fields.put("payrollReferenceMonth", payrollReferenceMonth.toString());
    fields.put("requestId", requestId.toString());
    fields.put("requestedMinor", requestedMinor);
    fields.put("schemaVersion", SCHEMA_VERSION);
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> field : fields.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('"').append(field.getKey()).append("\":");
      Object value = field.getValue();
      if (value instanceof String text) {
        json.append('"').append(escape(text)).append('"');
      } else {
        json.append(value);
      }
    }
    return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * @return the SHA-256 of the canonical bytes, lower-case hex
   */
  public String sha256() {
    return PackageDigests.sha256(new String(canonicalBytes(), StandardCharsets.UTF_8));
  }

  private static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
