package com.divalhr.core.integrations.partner.referral;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The closed vocabulary of the consented partner referral (MVP-120F, ADR 0045): the disclosure
 * allow-list with each field's source and purpose, the categories a referral never shares, the
 * consent wording version and the lifecycle. Nothing here decides, prices or approves anything.
 */
public final class PartnerReferralDomain {

  /** The consent wording version the employee affirms. */
  public static final String CONSENT_VERSION = "PR-REFERRAL-1";

  /** The only provider a referral is ever delivered to (this story). */
  public static final String PROVIDER = "SYNTHETIC-TEST";

  /** How long a preview digest stays valid. */
  public static final java.time.Duration PREVIEW_TTL = java.time.Duration.ofMinutes(15);

  /** Opaque product reference. */
  public static final Pattern PRODUCT_REFERENCE = Pattern.compile("^PP-[0-9A-HJKMNP-TV-Z]{16}$");

  /** Opaque referral reference. */
  public static final Pattern REFERRAL_REFERENCE = Pattern.compile("^PR-[0-9A-HJKMNP-TV-Z]{16}$");

  /** International phone number the employee may type. */
  public static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");

  /** Digest as 64 lowercase hex characters. */
  public static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

  /** Categories a referral never discloses, in the contract order. */
  public static final List<String> NEVER_SHARED =
      List.of(
          "SALARY",
          "COMPENSATION",
          "PAYROLL",
          "ATTENDANCE",
          "LEAVE",
          "PERFORMANCE",
          "DOCUMENTS",
          "NATIONAL_IDENTIFIERS",
          "BANK_OR_MOBILE_MONEY_DETAILS",
          "DEDUCTIONS",
          "CREDIT_HISTORY",
          "MEDICAL_INFORMATION",
          "DEPENDANTS",
          "MANAGER",
          "SITE",
          "COST_CENTRE",
          "EMPLOYMENT_HISTORY");

  private PartnerReferralDomain() {}

  /**
   * A disclosable field: the allow-list. {@code optional} fields appear only when the employee
   * includes them; {@code assigned} fields get their value at submission.
   */
  public enum Field {
    /** The opaque referral reference (assigned at submission). */
    REFERRAL_REFERENCE("DIVALHR_GENERATED", "ROUTE_REFERRAL", false, true),
    /** The opaque product reference. */
    PRODUCT_REFERENCE("CATALOGUE", "SELECT_PRODUCT", false, false),
    /** The product name in the employee's language. */
    PRODUCT_NAME("CATALOGUE", "SELECT_PRODUCT", false, false),
    /** The partner display name. */
    PARTNER_NAME("CATALOGUE", "SELECT_PRODUCT", false, false),
    /** The employee's legal name. */
    EMPLOYEE_LEGAL_NAME("EMPLOYEE_RECORD", "IDENTIFY_EMPLOYEE", false, false),
    /** The verified membership email. */
    EMPLOYEE_EMAIL("VERIFIED_MEMBERSHIP", "CONTACT_EMPLOYEE", false, false),
    /** The phone the employee typed (optional). */
    EMPLOYEE_PHONE("EMPLOYEE_ENTERED", "CONTACT_EMPLOYEE", true, false),
    /** The employee's country (the organization's country). */
    EMPLOYEE_COUNTRY("ORGANIZATION_RECORD", "IDENTIFY_EMPLOYEE", false, false),
    /** The preferred language. */
    PREFERRED_LANGUAGE("EMPLOYEE_ENTERED", "LOCALIZE_CONTACT", false, false),
    /** The organization legal name (optional). */
    ORGANIZATION_LEGAL_NAME("ORGANIZATION_RECORD", "IDENTIFY_ORGANIZATION", true, false),
    /** The consent timestamp (assigned at submission). */
    CONSENT_TIMESTAMP("CONSENT_EVIDENCE", "EVIDENCE_CONSENT", false, true),
    /** The consent wording version. */
    CONSENT_VERSION("CONSENT_EVIDENCE", "EVIDENCE_CONSENT", false, false);

    private final String source;
    private final String purpose;
    private final boolean optional;
    private final boolean assigned;

    Field(String source, String purpose, boolean optional, boolean assigned) {
      this.source = source;
      this.purpose = purpose;
      this.optional = optional;
      this.assigned = assigned;
    }

    /**
     * Where the value comes from.
     *
     * @return the source code
     */
    public String source() {
      return source;
    }

    /**
     * Why the partner needs it.
     *
     * @return the purpose code
     */
    public String purpose() {
      return purpose;
    }

    /**
     * Whether the employee may leave it out.
     *
     * @return true for the optional fields
     */
    public boolean optional() {
      return optional;
    }

    /**
     * Whether DivalHR assigns the value at submission.
     *
     * @return true for the assigned fields
     */
    public boolean assigned() {
      return assigned;
    }
  }

  /** The lifecycle of a referral. */
  public enum State {
    /** Recorded with consent; delivery not yet claimed. */
    SUBMITTED,
    /** Being delivered or waiting for a retry. */
    DELIVERY_PENDING,
    /** The synthetic provider acknowledged receipt. */
    DELIVERED,
    /** The outcome is uncertain; never resent. */
    DELIVERY_UNKNOWN,
    /** Delivery proved impossible. */
    DELIVERY_FAILED,
    /** The employee withdrew before delivery. */
    WITHDRAWN
  }

  /**
   * Parses a language.
   *
   * @param raw raw value
   * @return {@code en} or {@code fr}, or empty
   */
  public static Optional<String> language(Object raw) {
    return "en".equals(raw) || "fr".equals(raw) ? Optional.of((String) raw) : Optional.empty();
  }
}
