package com.divalhr.core.people.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Normalized values of a valid row (Confidential and Restricted HR personal data). Never logged,
 * audited or published.
 *
 * @param employeeNumber upper-case employee number
 * @param givenNames NFC given names
 * @param familyName NFC family name
 * @param startDate first day of employment
 * @param legalEntityCode upper-case legal entity code
 * @param siteCode upper-case site code
 * @param departmentCode upper-case department code, or {@code null}
 * @param costCenterCode upper-case cost center code, or {@code null}
 * @param teamCode upper-case team code, or {@code null}
 */
public record RowValues(
    String employeeNumber,
    String givenNames,
    String familyName,
    LocalDate startDate,
    String legalEntityCode,
    String siteCode,
    String departmentCode,
    String costCenterCode,
    String teamCode) {

  /** Requires the mandatory values. */
  public RowValues {
    Objects.requireNonNull(employeeNumber, "employeeNumber");
    Objects.requireNonNull(givenNames, "givenNames");
    Objects.requireNonNull(familyName, "familyName");
    Objects.requireNonNull(startDate, "startDate");
    Objects.requireNonNull(legalEntityCode, "legalEntityCode");
    Objects.requireNonNull(siteCode, "siteCode");
  }

  @Override
  public String toString() {
    return "RowValues[redacted]";
  }
}
