package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A published slot, with the assigned employee's current directory names when there is one, and
 * (MVP-051) its current operational coverage and approved substitution.
 *
 * @param id slot
 * @param scheduleId schedule
 * @param position position in publication order
 * @param start local start
 * @param end local end
 * @param startAt start instant
 * @param endAt end instant
 * @param labelEn English label
 * @param labelFr French label
 * @param employeeId assigned employee, or {@code null}
 * @param employeeNumber current employee number, or {@code null}
 * @param givenNames current given names, or {@code null}
 * @param familyName current family name, or {@code null}
 * @param effective the employee of the slot's current operational coverage, or {@code null}
 * @param substitution the slot's approved substitution, or {@code null}
 */
public record PublishedSlot(
    UUID id,
    UUID scheduleId,
    int position,
    LocalDateTime start,
    LocalDateTime end,
    Instant startAt,
    Instant endAt,
    String labelEn,
    String labelFr,
    UUID employeeId,
    String employeeNumber,
    String givenNames,
    String familyName,
    Substitution.Person effective,
    ApprovedSubstitution substitution) {

  /**
   * A slot's approved substitution: identifiers and instant only.
   *
   * @param requestId request
   * @param decisionId approving decision
   * @param approvedAt decision instant
   */
  public record ApprovedSubstitution(UUID requestId, UUID decisionId, Instant approvedAt) {}

  @Override
  public String toString() {
    return "PublishedSlot[id=" + id + "]";
  }
}
