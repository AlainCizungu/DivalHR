package com.divalhr.core.people.leave.domain;

/**
 * An appointment as tenant administrators list it (MVP-041G): the appointment, both employees' safe
 * directory fields and its revocation, if any.
 *
 * @param substitution the appointment
 * @param manager the delegating manager
 * @param substitute the substitute
 * @param revocation the revocation, or {@code null}
 */
public record LeaveSubstitutionEntry(
    LeaveSubstitution substitution,
    DirectoryEmployee manager,
    DirectoryEmployee substitute,
    LeaveSubstitutionRevocation revocation) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveSubstitutionEntry[id=" + substitution.id() + "]";
  }
}
