package com.divalhr.core.integrations.partner.catalogue;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Closed vocabularies and transition matrices of partner accounts and the product catalogue
 * (MVP-120E, ADR 0044). The database enforces the same matrices; these are the service-level checks
 * that give a precise error before any write.
 */
public final class PartnerAccountDomain {

  private PartnerAccountDomain() {}

  /** Partner lifecycle states. */
  public enum PartnerStatus {
    DRAFT,
    ACTIVE,
    SUSPENDED,
    CLOSED
  }

  /** Partner lifecycle actions. */
  public enum PartnerAction {
    ACTIVATE(PartnerStatus.DRAFT),
    SUSPEND(PartnerStatus.ACTIVE),
    REACTIVATE(PartnerStatus.SUSPENDED),
    CLOSE(null),
    AMEND_SCOPE(null);

    private final PartnerStatus from;

    PartnerAction(PartnerStatus from) {
      this.from = from;
    }

    /**
     * Whether the action is allowed from a state.
     *
     * @param status current state
     * @return true when allowed
     */
    public boolean allowedFrom(PartnerStatus status) {
      if (status == PartnerStatus.CLOSED) {
        return false;
      }
      return switch (this) {
        case CLOSE -> true;
        case AMEND_SCOPE -> true;
        default -> from == status;
      };
    }

    /**
     * The state after the action.
     *
     * @param current current state
     * @return next state
     */
    public PartnerStatus target(PartnerStatus current) {
      return switch (this) {
        case ACTIVATE, REACTIVATE -> PartnerStatus.ACTIVE;
        case SUSPEND -> PartnerStatus.SUSPENDED;
        case CLOSE -> PartnerStatus.CLOSED;
        case AMEND_SCOPE -> current;
      };
    }

    /**
     * The history event the action records.
     *
     * @return event type name
     */
    public String eventType() {
      return switch (this) {
        case ACTIVATE -> "ACTIVATED";
        case SUSPEND -> "SUSPENDED";
        case REACTIVATE -> "REACTIVATED";
        case CLOSE -> "CLOSED";
        case AMEND_SCOPE -> "SCOPE_AMENDED";
      };
    }

    /**
     * Whether the action needs explicit confirmation.
     *
     * @return true when {@code confirmed} must be true
     */
    public boolean needsConfirmation() {
      return this == ACTIVATE || this == REACTIVATE || this == CLOSE;
    }
  }

  /** Bounded reasons for suspension, closure, product pause and product retirement. */
  public enum Reason {
    COMPLIANCE_REVIEW,
    COMMERCIAL_HOLD,
    PARTNER_REQUEST,
    REGULATORY_RESTRICTION,
    SECURITY_CONCERN,
    PROGRAM_ENDED,
    OTHER
  }

  /** Product types. */
  public enum ProductType {
    SALARY_ADVANCE,
    EMPLOYEE_LOAN,
    HEALTH_INSURANCE,
    LIFE_INSURANCE,
    OTHER_INSURANCE,
    TELECOM_VOICE_DATA,
    DEVICE_FINANCING,
    HEALTHCARE,
    WELLNESS,
    TRAINING,
    OTHER_BENEFIT;

    /**
     * Whether submission requires monetary and duration limits.
     *
     * @return true for credit-like products
     */
    public boolean needsLimits() {
      return this == SALARY_ADVANCE || this == EMPLOYEE_LOAN;
    }
  }

  /** Product version states. */
  public enum ProductStatus {
    DRAFT,
    UNDER_REVIEW,
    ACTIVE,
    PAUSED,
    RETIRED;

    /**
     * Whether the state is terminal.
     *
     * @return true for retired versions
     */
    public boolean terminal() {
      return this == RETIRED;
    }
  }

  /** Product version actions. */
  public enum ProductAction {
    SUBMIT(ProductStatus.DRAFT, ProductStatus.UNDER_REVIEW, "SUBMITTED"),
    RETURN(ProductStatus.UNDER_REVIEW, ProductStatus.DRAFT, "RETURNED"),
    PUBLISH(ProductStatus.UNDER_REVIEW, ProductStatus.ACTIVE, "PUBLISHED"),
    PAUSE(ProductStatus.ACTIVE, ProductStatus.PAUSED, "PAUSED"),
    RESUME(ProductStatus.PAUSED, ProductStatus.ACTIVE, "RESUMED"),
    RETIRE(null, ProductStatus.RETIRED, "RETIRED");

    private final ProductStatus from;
    private final ProductStatus to;
    private final String event;

    ProductAction(ProductStatus from, ProductStatus to, String event) {
      this.from = from;
      this.to = to;
      this.event = event;
    }

    /**
     * Whether the action is allowed from a state.
     *
     * @param status current state
     * @return true when allowed
     */
    public boolean allowedFrom(ProductStatus status) {
      return this == RETIRE ? !status.terminal() : from == status;
    }

    /**
     * The state after the action.
     *
     * @return next state
     */
    public ProductStatus target() {
      return to;
    }

    /**
     * The history event type.
     *
     * @return event type name
     */
    public String eventType() {
      return event;
    }

    /**
     * Whether a reason code is required.
     *
     * @return true for pause and retire
     */
    public boolean needsReason() {
      return this == PAUSE || this == RETIRE;
    }

    /**
     * Whether the action needs explicit confirmation.
     *
     * @return true when {@code confirmed} must be true
     */
    public boolean needsConfirmation() {
      return this == PUBLISH || this == RESUME || this == RETIRE;
    }
  }

  /** Currencies offered now; storage accepts any ISO 4217 code so adding one is configuration. */
  public static final Set<String> OFFERED_CURRENCIES = Set.of("CDF", "USD");

  /** Partner actions that may carry or require a reason. */
  public static final Set<PartnerAction> REASONED =
      EnumSet.of(PartnerAction.SUSPEND, PartnerAction.CLOSE);

  /**
   * Parses a closed value strictly (exact upper-case name).
   *
   * @param type enum class
   * @param raw raw text
   * @param <E> enum type
   * @return the value, or {@code null} when it is not one of the closed values
   */
  public static <E extends Enum<E>> E parse(Class<E> type, String raw) {
    if (raw == null || !raw.equals(raw.toUpperCase(Locale.ROOT))) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
