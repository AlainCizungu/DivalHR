package com.divalhr.core.people.leave.domain;

import java.util.UUID;

/**
 * An employee's safe directory fields (MVP-041G): what an administrator needs to recognize them.
 *
 * @param id employee
 * @param employeeNumber employee number
 * @param givenNames given names
 * @param familyName family name
 */
public record DirectoryEmployee(
    UUID id, String employeeNumber, String givenNames, String familyName) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "DirectoryEmployee[id=" + id + "]";
  }
}
