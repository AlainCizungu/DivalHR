package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceResponses.ConsentDocument;
import com.divalhr.core.platform.idempotency.Fingerprints;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The canonical consent disclosures of MVP-120A (D120A-6). A disclosure is a versioned document of
 * stable codes that the client translates into plain English and French; its digest is the SHA-256
 * of the canonical JSON (sorted keys, no whitespace) and does not depend on the language. The
 * server holds the document of every known version, recomputes the digest at submission and never
 * stores rendered text. Nothing in this step is sent externally.
 */
final class SalaryAdvanceConsent {

  /** The first consent document. */
  static final String V1 = "SA-CONSENT-1";

  private static final String PURPOSE = "EVALUATION_BY_FUTURE_APPROVED_PARTNER";
  private static final String EXTERNAL = "NONE_IN_THIS_STEP";
  private static final String WITHDRAWAL = "BEFORE_EXTERNAL_HANDOFF";
  private static final String RETENTION = "NO_DELETION_SCHEDULE_YET";

  private static final List<String> SHARED =
      List.of(
          "REQUEST_ID",
          "EMPLOYER_LEGAL_ENTITY",
          "EMPLOYMENT_STATUS",
          "EMPLOYMENT_START_DATE",
          "PAYROLL_REFERENCE_MONTH",
          "CURRENCY",
          "REQUESTED_AMOUNT",
          "EMPLOYER_POLICY_CEILING",
          "EMPLOYER_PARTICIPATION_STATUS");

  private static final List<String> NOT_SHARED =
      List.of(
          "HEALTH",
          "LEAVE_REASON",
          "ATTENDANCE_DETAIL",
          "PERFORMANCE",
          "DISCIPLINARY",
          "RECEIPT_OR_DOCUMENT_CONTENT",
          "PASSWORD",
          "MFA_DATA",
          "FULL_PAYSLIP",
          "BANK_OR_MOBILE_MONEY_DESTINATION",
          "NATIONAL_IDENTIFIER",
          "UNRELATED_HR_HISTORY");

  private SalaryAdvanceConsent() {}

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
  static Optional<ConsentDocument> document(String version) {
    if (!known(version)) {
      return Optional.empty();
    }
    return Optional.of(
        new ConsentDocument(
            version,
            Fingerprints.sha256(canonical(version)),
            PURPOSE,
            SHARED,
            NOT_SHARED,
            EXTERNAL,
            WITHDRAWAL,
            RETENTION));
  }

  /**
   * The canonical JSON the digest covers: keys in byte order, arrays in their declared order, no
   * whitespace.
   *
   * @param version the version
   * @return the canonical text
   */
  static String canonical(String version) {
    return "{\"externalSharing\":\""
        + EXTERNAL
        + "\",\"notShared\":"
        + array(NOT_SHARED)
        + ",\"purpose\":\""
        + PURPOSE
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
    return codes.stream().map(code -> "\"" + code + "\"").collect(Collectors.joining(",", "[", "]"));
  }
}
