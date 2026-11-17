package com.divalhr.core.payroll.application;

import com.divalhr.core.platform.idempotency.Fingerprints;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The canonical handoff disclosure of MVP-120B (ADR 0042 §3), a fresh versioned document that is
 * never the MVP-120A consent and never reused from it. The document is stable codes the client
 * translates into English and French; its digest is the SHA-256 of the canonical JSON (sorted keys,
 * no whitespace) and is independent of the language. The server holds every known version,
 * recomputes the digest at authorization and stores no rendered text.
 */
final class SalaryAdvanceHandoffConsent {

  /** The first handoff consent document. */
  static final String V1 = "SA-HANDOFF-1";

  static final String PURPOSE = "SUBMIT_CONFIRMED_REQUEST_FOR_PARTNER_REVIEW";
  static final String RECIPIENT = "SYNTHETIC-TEST";
  static final String PROMISE = "NO_APPROVAL_OR_PAYOUT_PROMISED";
  static final String WITHDRAWAL = "BEFORE_DURABLE_PARTNER_RECEIPT";
  static final String RETENTION = "NO_PRODUCTION_RETENTION_SCHEDULE_CLAIMED";
  static final String LENDER = "DIVALHR_IS_NOT_THE_LENDER";

  static final List<String> SHARED =
      List.of(
          "APPLICATION_ID",
          "REQUEST_ID",
          "EMPLOYER_LEGAL_ENTITY_REFERENCE",
          "EMPLOYMENT_ACTIVE",
          "EMPLOYMENT_START_DATE",
          "PAYROLL_REFERENCE_MONTH",
          "CURRENCY",
          "REQUESTED_AMOUNT",
          "EMPLOYER_POLICY_CEILING",
          "EMPLOYER_CONFIRMED_STATUS",
          "CONSENT_VERSION_AND_DIGEST");

  static final List<String> NOT_SHARED =
      List.of(
          "NAME",
          "EMAIL",
          "PHONE",
          "HOME_ADDRESS",
          "NATIONAL_IDENTIFIER",
          "BANK_OR_MOBILE_MONEY_DESTINATION",
          "FULL_PAYSLIP",
          "HEALTH",
          "LEAVE_DETAIL",
          "ATTENDANCE_DETAIL",
          "PERFORMANCE",
          "DISCIPLINARY",
          "DOCUMENTS",
          "PASSWORD",
          "MFA_DATA",
          "UNRELATED_HR_HISTORY");

  private SalaryAdvanceHandoffConsent() {}

  /** The disclosure of one known version. */
  record Document(
      String version,
      String contentSha256,
      String purpose,
      String recipient,
      List<String> shared,
      List<String> notShared,
      String promise,
      String withdrawal,
      String retention,
      String lender) {}

  /**
   * Whether the server knows a consent version.
   *
   * @param version the version
   * @return true for a known version
   */
  static boolean known(String version) {
    return V1.equals(version);
  }

  /**
   * The disclosure of a known version.
   *
   * @param version the version
   * @return the document, or empty for an unknown version
   */
  static Optional<Document> document(String version) {
    if (!known(version)) {
      return Optional.empty();
    }
    return Optional.of(
        new Document(
            version,
            Fingerprints.sha256(canonical(version)),
            PURPOSE,
            RECIPIENT,
            SHARED,
            NOT_SHARED,
            PROMISE,
            WITHDRAWAL,
            RETENTION,
            LENDER));
  }

  /**
   * The canonical JSON the digest covers: keys in byte order, arrays in their declared order, no
   * whitespace.
   *
   * @param version the version
   * @return the canonical text
   */
  static String canonical(String version) {
    return "{\"promise\":\""
        + PROMISE
        + "\",\"lender\":\""
        + LENDER
        + "\",\"notShared\":"
        + array(NOT_SHARED)
        + ",\"purpose\":\""
        + PURPOSE
        + "\",\"recipient\":\""
        + RECIPIENT
        + "\",\"retention\":\""
        + RETENTION
        + "\",\"shared\":"
        + array(SHARED)
        + ",\"version\":\""
        + version
        + "\",\"withdrawal\":\""
        + WITHDRAWAL
        + "\"}";
  }

  private static String array(List<String> codes) {
    return codes.stream()
        .map(code -> "\"" + code + "\"")
        .collect(Collectors.joining(",", "[", "]"));
  }
}
