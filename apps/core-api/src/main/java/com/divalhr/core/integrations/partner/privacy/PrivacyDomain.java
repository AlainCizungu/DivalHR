package com.divalhr.core.integrations.partner.privacy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The closed vocabulary of LAUNCH-001 (ADR 0046): data classes, actions, triggers, purposes, the
 * policy and request lifecycles. The platform ships no retention duration and no legal claim.
 */
public final class PrivacyDomain {

  private PrivacyDomain() {}

  /** The four data classes of MVP-120F partner-referral data. */
  public enum DataClass {
    PARTNER_REFERRAL_DISCLOSURE(Purpose.CONSENTED_INTRODUCTION),
    PARTNER_REFERRAL_CONSENT(Purpose.CONSENT_EVIDENCE),
    PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS(Purpose.DELIVERY_TRUTH),
    PARTNER_REFERRAL_LIFECYCLE_EVIDENCE(Purpose.LIFECYCLE_EVIDENCE);

    private final Purpose purpose;

    DataClass(Purpose purpose) {
      this.purpose = purpose;
    }

    /** The only purpose a class may be paired with. */
    public Purpose purpose() {
      return purpose;
    }
  }

  /** Purposes. */
  public enum Purpose {
    CONSENTED_INTRODUCTION,
    CONSENT_EVIDENCE,
    DELIVERY_TRUTH,
    LIFECYCLE_EVIDENCE
  }

  /** Actions. */
  public enum Action {
    HOLD,
    MINIMIZE,
    ERASE
  }

  /** Lifecycle triggers. */
  public enum Trigger {
    REFERRAL_CREATED,
    REFERRAL_CLOSED
  }

  /** Policy lifecycle. */
  public enum PolicyState {
    DRAFT,
    APPROVED,
    SCHEDULED,
    ACTIVE,
    RETIRED
  }

  /** Hold reasons. */
  public enum HoldReason {
    LEGAL_PROCEEDING,
    REGULATORY_REQUEST,
    DISPUTE,
    INVESTIGATION,
    OTHER_DOCUMENTED
  }

  /** Request types. */
  public enum RequestType {
    ACCESS,
    ERASURE,
    RESTRICTION,
    OBJECTION
  }

  /** Request lifecycle. */
  public enum RequestState {
    SUBMITTED,
    UNDER_REVIEW,
    MORE_INFORMATION_REQUIRED,
    APPROVED,
    PARTIALLY_APPROVED,
    REFUSED,
    COMPLETED,
    CANCELLED_BY_EMPLOYEE
  }

  /** Decision reasons. */
  public enum DecisionReason {
    IDENTITY_NOT_VERIFIED,
    LEGAL_HOLD_ACTIVE,
    RETENTION_REQUIRED,
    POLICY_PENDING,
    REQUEST_OUT_OF_SCOPE,
    NO_MATCHING_DATA
  }

  /** The closed policy transitions. */
  public static final Map<PolicyState, Set<PolicyState>> POLICY_TRANSITIONS = policyTransitions();

  /** The closed request transitions made by platform privacy operations. */
  public static final Map<RequestState, Set<RequestState>> PLATFORM_TRANSITIONS =
      platformTransitions();

  /** States in which a request is still open. */
  public static final List<RequestState> OPEN =
      List.of(
          RequestState.SUBMITTED,
          RequestState.UNDER_REVIEW,
          RequestState.MORE_INFORMATION_REQUIRED,
          RequestState.APPROVED,
          RequestState.PARTIALLY_APPROVED);

  private static Map<PolicyState, Set<PolicyState>> policyTransitions() {
    Map<PolicyState, Set<PolicyState>> map = new EnumMap<>(PolicyState.class);
    map.put(PolicyState.DRAFT, Set.of(PolicyState.APPROVED, PolicyState.RETIRED));
    map.put(
        PolicyState.APPROVED,
        Set.of(PolicyState.SCHEDULED, PolicyState.ACTIVE, PolicyState.RETIRED));
    map.put(PolicyState.SCHEDULED, Set.of(PolicyState.ACTIVE, PolicyState.RETIRED));
    map.put(PolicyState.ACTIVE, Set.of(PolicyState.RETIRED));
    map.put(PolicyState.RETIRED, Set.of());
    return Map.copyOf(map);
  }

  private static Map<RequestState, Set<RequestState>> platformTransitions() {
    Map<RequestState, Set<RequestState>> map = new EnumMap<>(RequestState.class);
    map.put(RequestState.SUBMITTED, Set.of(RequestState.UNDER_REVIEW));
    map.put(
        RequestState.UNDER_REVIEW,
        Set.of(
            RequestState.MORE_INFORMATION_REQUIRED,
            RequestState.APPROVED,
            RequestState.PARTIALLY_APPROVED,
            RequestState.REFUSED));
    map.put(RequestState.MORE_INFORMATION_REQUIRED, Set.of(RequestState.UNDER_REVIEW));
    map.put(RequestState.APPROVED, Set.of(RequestState.COMPLETED));
    map.put(RequestState.PARTIALLY_APPROVED, Set.of(RequestState.COMPLETED));
    map.put(RequestState.REFUSED, Set.of());
    map.put(RequestState.COMPLETED, Set.of());
    map.put(RequestState.CANCELLED_BY_EMPLOYEE, Set.of());
    return Map.copyOf(map);
  }
}
