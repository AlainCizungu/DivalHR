package com.divalhr.core.people.domain.history;

import java.util.Objects;
import java.util.UUID;

/**
 * The business value of an assignment row (Restricted HR). {@link #canonical()} is a stable,
 * versioned text used only inside digests and integrity hashes; it is never logged or published.
 */
public sealed interface AssignmentValue {

  /**
   * The kind this value belongs to.
   *
   * @return kind
   */
  AssignmentKind kind();

  /**
   * Stable text form for digests.
   *
   * @return canonical text
   */
  String canonical();

  private static String id(UUID id) {
    return id == null ? "-" : id.toString();
  }

  /**
   * A placement: legal entity and site, at most one of department and cost center, and a team under
   * that department or cost center.
   *
   * @param legalEntityId legal entity
   * @param siteId site
   * @param departmentId department, or {@code null}
   * @param costCenterId cost center, or {@code null}
   * @param teamId team, or {@code null}
   */
  record Placement(
      UUID legalEntityId, UUID siteId, UUID departmentId, UUID costCenterId, UUID teamId)
      implements AssignmentValue {

    /** Requires the legal entity and site. */
    public Placement {
      Objects.requireNonNull(legalEntityId, "legalEntityId");
      Objects.requireNonNull(siteId, "siteId");
    }

    @Override
    public AssignmentKind kind() {
      return AssignmentKind.PLACEMENT;
    }

    @Override
    public String canonical() {
      return "placement:"
          + id(legalEntityId)
          + ","
          + id(siteId)
          + ","
          + id(departmentId)
          + ","
          + id(costCenterId)
          + ","
          + id(teamId);
    }
  }

  /**
   * A manager.
   *
   * @param employeeId the manager's employee
   */
  record Manager(UUID employeeId) implements AssignmentValue {

    /** Requires the employee. */
    public Manager {
      Objects.requireNonNull(employeeId, "employeeId");
    }

    @Override
    public AssignmentKind kind() {
      return AssignmentKind.MANAGER;
    }

    @Override
    public String canonical() {
      return "manager:" + employeeId;
    }
  }

  /**
   * A contract classification.
   *
   * @param classification code
   */
  record Contract(ContractClassification classification) implements AssignmentValue {

    /** Requires the code. */
    public Contract {
      Objects.requireNonNull(classification, "classification");
    }

    @Override
    public AssignmentKind kind() {
      return AssignmentKind.CONTRACT;
    }

    @Override
    public String canonical() {
      return "contract:" + classification.name();
    }
  }

  /**
   * A compensation basis.
   *
   * @param basis code
   */
  record Compensation(CompensationBasis basis) implements AssignmentValue {

    /** Requires the code. */
    public Compensation {
      Objects.requireNonNull(basis, "basis");
    }

    @Override
    public AssignmentKind kind() {
      return AssignmentKind.COMPENSATION;
    }

    @Override
    public String canonical() {
      return "compensation:" + basis.name();
    }
  }
}
