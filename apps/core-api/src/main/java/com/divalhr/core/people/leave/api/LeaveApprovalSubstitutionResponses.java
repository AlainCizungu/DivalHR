package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.leave.domain.DirectoryEmployee;
import com.divalhr.core.people.leave.domain.LeaveSubstitution;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionEntry;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionRevocation;
import com.divalhr.core.people.leave.domain.LeaveSubstitutionState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Leave approval substitute responses (MVP-041G): tenant administrators only. The list may carry
 * the reasons as written; receipts never do, and no response names a subject or actor.
 */
public final class LeaveApprovalSubstitutionResponses {

  private LeaveApprovalSubstitutionResponses() {}

  /**
   * An employee as an administrator recognizes them (plain text).
   *
   * @param id employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "LeaveApprovalSubstitutionEmployee")
  public record Employee(UUID id, String employeeNumber, String givenNames, String familyName) {

    static Employee of(DirectoryEmployee employee) {
      return new Employee(
          employee.id(), employee.employeeNumber(), employee.givenNames(), employee.familyName());
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "LeaveApprovalSubstitutionEmployee[id=" + id + "]";
    }
  }

  /**
   * The revocation of an appointment.
   *
   * @param revokedAt revocation time
   * @param reasonLocale the language the reason was written in
   * @param reason the reason, as written
   */
  @Schema(name = "LeaveApprovalSubstitutionRevocation")
  public record Revocation(Instant revokedAt, String reasonLocale, String reason) {

    static Revocation of(LeaveSubstitutionRevocation revocation) {
      return new Revocation(revocation.revokedAt(), revocation.reasonLocale(), revocation.reason());
    }

    /**
     * No reason.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "LeaveApprovalSubstitutionRevocation[revokedAt=" + revokedAt + "]";
    }
  }

  /**
   * An appointment as tenant administrators list it.
   *
   * @param id appointment
   * @param manager the delegating manager
   * @param substitute the substitute
   * @param effectiveFrom first day (inclusive)
   * @param effectiveTo last day (inclusive)
   * @param state derived on the page's business date
   * @param reasonLocale the language the reason was written in
   * @param reason the reason, as written
   * @param createdAt creation time
   * @param revocation the revocation, or {@code null}
   */
  @Schema(name = "LeaveApprovalSubstitution")
  public record Item(
      UUID id,
      Employee manager,
      Employee substitute,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      LeaveSubstitutionState state,
      String reasonLocale,
      String reason,
      Instant createdAt,
      Revocation revocation) {

    /**
     * The view of an appointment on a business date.
     *
     * @param entry the appointment
     * @param today the organization's business date
     * @return the view
     */
    public static Item of(LeaveSubstitutionEntry entry, LocalDate today) {
      LeaveSubstitution substitution = entry.substitution();
      return new Item(
          substitution.id(),
          Employee.of(entry.manager()),
          Employee.of(entry.substitute()),
          substitution.effectiveFrom(),
          substitution.effectiveTo(),
          LeaveSubstitutionState.of(
              substitution.effectiveFrom(),
              substitution.effectiveTo(),
              entry.revocation() != null,
              today),
          substitution.reasonLocale(),
          substitution.reason(),
          substitution.createdAt(),
          entry.revocation() == null ? null : Revocation.of(entry.revocation()));
    }

    /**
     * Identifiers only.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "LeaveApprovalSubstitution[id=" + id + "]";
    }
  }

  /**
   * One page of appointments, newest first.
   *
   * @param items appointments
   * @param nextCursor continuation or {@code null}
   * @param asOf the organization's business date the states were derived on
   */
  @Schema(name = "LeaveApprovalSubstitutionPage")
  public record Page(List<Item> items, String nextCursor, LocalDate asOf) {

    /** Keeps an unmodifiable copy of the items. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * The minimal receipt of an appointment: never the reason.
   *
   * @param substitutionId appointment
   * @param createdAt creation time
   */
  @Schema(name = "LeaveApprovalSubstitutionReceipt")
  public record Receipt(UUID substitutionId, Instant createdAt) {}

  /**
   * The minimal receipt of a revocation: never the reason.
   *
   * @param substitutionId the revoked appointment
   * @param revocationId revocation
   * @param state {@code REVOKED}
   * @param revokedAt revocation time
   */
  @Schema(name = "LeaveApprovalSubstitutionRevocationReceipt")
  public record RevocationReceipt(
      UUID substitutionId, UUID revocationId, LeaveSubstitutionState state, Instant revokedAt) {}
}
