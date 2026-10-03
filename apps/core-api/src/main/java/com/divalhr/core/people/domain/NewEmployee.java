package com.divalhr.core.people.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An employee and its first employment, ready to insert (MVP-020). Personal data: never logged,
 * audited or published.
 *
 * @param employeeId new employee ID
 * @param employmentId new employment ID
 * @param employeeNumber upper-case employee number
 * @param givenNames NFC given names
 * @param familyName NFC family name
 * @param startDate first day of employment
 * @param legalEntityId legal entity
 * @param siteId site
 * @param departmentId department, or {@code null}
 * @param costCenterId cost center, or {@code null}
 * @param teamId team, or {@code null}
 */
public record NewEmployee(
    UUID employeeId,
    UUID employmentId,
    String employeeNumber,
    String givenNames,
    String familyName,
    LocalDate startDate,
    UUID legalEntityId,
    UUID siteId,
    UUID departmentId,
    UUID costCenterId,
    UUID teamId) {

  /** Requires the mandatory values. */
  public NewEmployee {
    Objects.requireNonNull(employeeId, "employeeId");
    Objects.requireNonNull(employmentId, "employmentId");
    Objects.requireNonNull(employeeNumber, "employeeNumber");
    Objects.requireNonNull(givenNames, "givenNames");
    Objects.requireNonNull(familyName, "familyName");
    Objects.requireNonNull(startDate, "startDate");
    Objects.requireNonNull(legalEntityId, "legalEntityId");
    Objects.requireNonNull(siteId, "siteId");
  }

  @Override
  public String toString() {
    return "NewEmployee[" + employeeId + "]";
  }
}
