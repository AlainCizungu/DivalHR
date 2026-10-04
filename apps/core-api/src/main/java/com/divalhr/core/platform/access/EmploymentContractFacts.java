package com.divalhr.core.platform.access;

import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * What the documents module may know about an employee to issue a contract (MVP-030, ADR 0009),
 * implemented by {@code people}. Documents never reads people's tables.
 */
public interface EmploymentContractFacts {

  /**
   * The employee's facts for a contract starting on a day, without locking (previews and reads,
   * inside the caller's transaction).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on contract start date (placement and classification in force that day)
   * @return the facts, or empty when the employee or an employment does not exist in the tenant
   */
  Optional<ContractFacts> read(TenantId tenant, UUID employeeId, LocalDate on);

  /**
   * The same facts with the employment row locked {@code FOR SHARE} until the caller's transaction
   * ends (MANDATORY; lock order step 2): a change, correction or separation of the employment
   * waits, and the employment version cannot move under the issue.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on contract start date
   * @return the facts, or empty when the employee or an employment does not exist in the tenant
   */
  Optional<ContractFacts> lockForContract(TenantId tenant, UUID employeeId, LocalDate on);

  /**
   * An employee's facts for a contract (Confidential and Restricted HR: never logged).
   *
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   * @param employmentId the employment (the one covering the day, else the nearest)
   * @param employmentVersion its timeline version
   * @param employmentStart its first day
   * @param employmentEnd its last day, or {@code null}
   * @param separationRecorded whether a separation that is not cancelled exists
   * @param legalEntityId placement legal entity on the day, or {@code null}
   * @param siteId placement site on the day, or {@code null}
   * @param classification contract classification code on the day, or {@code null}
   */
  record ContractFacts(
      UUID employeeId,
      String employeeNumber,
      String givenNames,
      String familyName,
      UUID employmentId,
      long employmentVersion,
      LocalDate employmentStart,
      LocalDate employmentEnd,
      boolean separationRecorded,
      UUID legalEntityId,
      UUID siteId,
      String classification) {

    @Override
    public String toString() {
      return "ContractFacts[" + employeeId + "]";
    }
  }
}
