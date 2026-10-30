package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One effective-dated statutory profile row of an employee (MVP-094B, D94B-4, ADR 0034 section 5):
 * country is always CD; no identifier, citizenship, dependant, health datum or free text.
 *
 * @param id the row
 * @param sequence 1, 2, 3 per employee (the employee's profile version is the number of rows)
 * @param effectiveFrom first day the profile is in force
 * @param classification standard resident, or unsupported
 * @param cnssSubject whether the employee is subject to CNSS
 * @param irppSubject whether the employee is subject to the IRPP salary withholding
 * @param evidence the evidence code
 */
public record StatutoryProfile(
    UUID id,
    int sequence,
    LocalDate effectiveFrom,
    StatutoryClassification classification,
    boolean cnssSubject,
    boolean irppSubject,
    StatutoryEvidence evidence) {

  /** Requires every component; an {@code UNSUPPORTED} profile has both subject flags false. */
  public StatutoryProfile {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    Objects.requireNonNull(classification, "classification");
    Objects.requireNonNull(evidence, "evidence");
    if (classification == StatutoryClassification.UNSUPPORTED && (cnssSubject || irppSubject)) {
      throw new IllegalArgumentException("an unsupported profile is subject to nothing");
    }
  }

  /**
   * Whether another row says the same thing: same classification, flags and evidence (the id, the
   * sequence and the effective date are not compared).
   *
   * @param other another profile
   * @return true when identical in content
   */
  public boolean sameContent(StatutoryProfile other) {
    return classification == other.classification
        && cnssSubject == other.cnssSubject
        && irppSubject == other.irppSubject
        && evidence == other.evidence;
  }
}
