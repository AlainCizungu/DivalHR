package com.divalhr.core.integrations.partner;

import java.util.EnumSet;
import java.util.Set;

/**
 * The closed catalogues and the status machine of partner applications (MVP-120D, ADR 0043). The
 * database enforces the same sets and transitions; these types let the application refuse early
 * with a precise safe code.
 */
public final class PartnerDomain {

  private PartnerDomain() {}

  /** Partner categories (D120D-2). */
  public enum Category {
    BANK(true),
    MICROFINANCE(true),
    INSURANCE(true),
    HEALTH_BENEFITS(false),
    TELECOM(true),
    MOBILE_MONEY_PAYMENT(true),
    PENSION_SAVINGS(true),
    OTHER_REGULATED_PROVIDER(false);

    private final boolean regulated;

    Category(boolean regulated) {
      this.regulated = regulated;
    }

    /**
     * Whether regulatory information is required when this is the primary category.
     *
     * @return true for the regulated categories
     */
    public boolean regulated() {
      return regulated;
    }
  }

  /** Capabilities, applicant assertions only (D120D-3). */
  public enum Capability {
    SALARY_ADVANCE,
    EMPLOYEE_LOANS,
    HEALTH_INSURANCE,
    LIFE_INSURANCE,
    ACCIDENT_INSURANCE,
    HEALTHCARE_BENEFITS,
    AIRTIME_DATA_BENEFITS,
    SMS_USSD_SERVICES,
    MOBILE_MONEY_DISBURSEMENT,
    PAYMENT_COLLECTION,
    PENSION_SAVINGS,
    OTHER_EMPLOYEE_SERVICE
  }

  /** Application statuses (D120D-4). */
  public enum Status {
    SUBMITTED,
    UNDER_REVIEW,
    MORE_INFORMATION_REQUIRED,
    APPROVED_FOR_ONBOARDING,
    DECLINED,
    WITHDRAWN;

    /**
     * Whether the status is final.
     *
     * @return true for approved-for-onboarding, declined and withdrawn
     */
    public boolean terminal() {
      return this == APPROVED_FOR_ONBOARDING || this == DECLINED || this == WITHDRAWN;
    }

    /**
     * The statuses reachable from this one.
     *
     * @return allowed next statuses (empty when terminal)
     */
    public Set<Status> next() {
      return switch (this) {
        case SUBMITTED -> EnumSet.of(UNDER_REVIEW, WITHDRAWN);
        case UNDER_REVIEW ->
            EnumSet.of(MORE_INFORMATION_REQUIRED, APPROVED_FOR_ONBOARDING, DECLINED, WITHDRAWN);
        case MORE_INFORMATION_REQUIRED -> EnumSet.of(UNDER_REVIEW, DECLINED, WITHDRAWN);
        default -> EnumSet.noneOf(Status.class);
      };
    }
  }

  /** Due-diligence checks (D120D-12). */
  public enum CheckCode {
    BUSINESS_IDENTITY(true),
    REGISTRATION_REFERENCE(true),
    REGULATORY_INFORMATION(true),
    SERVICE_SCOPE(true),
    PRIVACY_SECURITY_CONTACT(true),
    TECHNICAL_API_READINESS(true),
    COMMERCIAL_DISCUSSION(false);

    private final boolean mandatory;

    CheckCode(boolean mandatory) {
      this.mandatory = mandatory;
    }

    /**
     * Whether the check gates approval.
     *
     * @return true for the six mandatory checks
     */
    public boolean mandatory() {
      return mandatory;
    }
  }

  /** Check results. */
  public enum CheckResult {
    PENDING,
    SATISFIED,
    NOT_APPLICABLE,
    CONCERN;

    /**
     * Whether a mandatory check with this result lets approval proceed.
     *
     * @return true for satisfied and not applicable
     */
    public boolean clears() {
      return this == SATISFIED || this == NOT_APPLICABLE;
    }
  }

  /** Closed, bounded reasons (D120D-14). */
  public enum Reason {
    APPLICANT_REQUEST,
    OUT_OF_SCOPE,
    INCOMPLETE_INFORMATION,
    REGULATORY_CONCERN,
    DUE_DILIGENCE_CONCERN,
    NOT_PURSUING,
    CLARIFY_REGULATORY_INFORMATION,
    CLARIFY_SERVICE_SCOPE,
    CLARIFY_TECHNICAL_READINESS,
    OTHER
  }

  /** API availability. */
  public enum ApiAvailability {
    YES,
    NO,
    PLANNED
  }

  /** History event types. */
  public enum EventType {
    SUBMITTED,
    REVIEW_STARTED,
    INFORMATION_REQUESTED,
    CHECKLIST_UPDATED,
    APPROVED_FOR_ONBOARDING,
    DECLINED,
    WITHDRAWAL_RECORDED
  }

  /**
   * Strict case-sensitive enum lookup.
   *
   * @param type the enum
   * @param raw the text
   * @param <E> enum type
   * @return the constant, or {@code null} when absent or unknown
   */
  public static <E extends Enum<E>> E parse(Class<E> type, String raw) {
    if (raw == null) {
      return null;
    }
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(raw)) {
        return constant;
      }
    }
    return null;
  }
}
