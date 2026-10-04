package com.divalhr.core.documents.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The allow-listed placeholders of grammar v1 (MVP-030, D15). Values come only from server data: no
 * expressions, filters, conditionals or nesting.
 */
public enum ContractPlaceholder {
  /** The employee's given names. */
  EMPLOYEE_GIVEN_NAMES("employee.givenNames"),
  /** The employee's family name. */
  EMPLOYEE_FAMILY_NAME("employee.familyName"),
  /** Given names, a space, family name. */
  EMPLOYEE_FULL_NAME("employee.fullName"),
  /** The employee number. */
  EMPLOYEE_NUMBER("employee.number"),
  /** The organization's name. */
  ORGANIZATION_NAME("organization.name"),
  /** The legal entity of the employee's placement on the contract start date. */
  LEGAL_ENTITY_NAME("legalEntity.name"),
  /** The site of the employee's placement on the contract start date. */
  SITE_NAME("site.name"),
  /** The employment start date. */
  EMPLOYMENT_START_DATE("employment.startDate"),
  /** The contract type label in the contract's language. */
  CONTRACT_TYPE("contract.type"),
  /** The contract start date. */
  CONTRACT_START_DATE("contract.startDate"),
  /** The contract end date (missing for an open-ended contract). */
  CONTRACT_END_DATE("contract.endDate"),
  /** The business date of issue. */
  ISSUE_DATE("issue.date");

  private final String key;

  ContractPlaceholder(String key) {
    this.key = key;
  }

  /**
   * The name written between double braces.
   *
   * @return key
   */
  public String key() {
    return key;
  }

  /**
   * Finds a placeholder by its exact key.
   *
   * @param key candidate
   * @return the placeholder, or empty
   */
  public static Optional<ContractPlaceholder> byKey(String key) {
    return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
  }
}
