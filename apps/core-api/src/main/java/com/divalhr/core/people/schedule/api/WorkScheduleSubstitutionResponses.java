package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Labels;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Site;
import com.divalhr.core.people.schedule.application.WorkScheduleCommand;
import com.divalhr.core.people.schedule.domain.Substitution;
import com.divalhr.core.people.schedule.domain.Substitution.State;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shift-substitution response bodies (MVP-051, D51-5). Reasons appear only in the requesting
 * employee's own history and in the deciding manager's inbox; receipts never carry one. No body
 * names the deciding manager, the verified subjects or why a candidate is absent.
 */
public final class WorkScheduleSubstitutionResponses {

  private WorkScheduleSubstitutionResponses() {}

  /**
   * The immutable published shift a request is about.
   *
   * @param scheduleId schedule
   * @param slotId slot
   * @param site the site
   * @param siteTimezone the schedule's site time zone snapshot
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param overnight whether it ends on a later local date
   * @param labels labels
   */
  @Schema(name = "WorkScheduleSubstitutionShift")
  public record ShiftView(
      UUID scheduleId,
      UUID slotId,
      Site site,
      String siteTimezone,
      String start,
      String end,
      Instant startAt,
      Instant endAt,
      boolean overnight,
      Labels labels) {

    /**
     * The view of a shift.
     *
     * @param shift the shift
     * @param site its site, or {@code null}
     * @return the view
     */
    public static ShiftView of(Substitution.Shift shift, UnitView site) {
      return new ShiftView(
          shift.scheduleId(),
          shift.slotId(),
          Site.of(site, shift.siteId()),
          shift.siteTimezone(),
          WorkScheduleCommand.wire(shift.start()),
          WorkScheduleCommand.wire(shift.end()),
          shift.startAt(),
          shift.endAt(),
          shift.overnight(),
          new Labels(shift.labelEn(), shift.labelFr()));
    }
  }

  /**
   * An employee's current minimal directory identity.
   *
   * @param id employee
   * @param employeeNumber number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "WorkScheduleSubstitutionPerson")
  public record Person(UUID id, String employeeNumber, String givenNames, String familyName) {

    /**
     * The view of an identity.
     *
     * @param person the identity
     * @return the view
     */
    public static Person of(Substitution.Person person) {
      return new Person(
          person.id(), person.employeeNumber(), person.givenNames(), person.familyName());
    }

    @Override
    public String toString() {
      return "Person[id=" + id + "]";
    }
  }

  /**
   * The decision of one of the caller's requests; never the deciding manager.
   *
   * @param outcome {@code APPROVED} or {@code REJECTED}
   * @param decidedAt decision instant
   * @param reasonLocale the reason's language
   * @param reason the reason
   * @param substitute the approved substitute, or {@code null}
   */
  @Schema(name = "MyWorkScheduleSubstitutionDecision")
  public record MyDecision(
      String outcome,
      Instant decidedAt,
      String reasonLocale,
      String reason,
      @Schema(nullable = true) Person substitute) {

    @Override
    public String toString() {
      return "MyDecision[outcome=" + outcome + "]";
    }
  }

  /**
   * One of the caller's substitution requests.
   *
   * @param id request
   * @param requestedAt request instant
   * @param state derived state
   * @param shift the shift
   * @param reasonLocale the request reason's language
   * @param reason the request reason
   * @param decision the decision, or {@code null}
   */
  @Schema(name = "MyWorkScheduleSubstitution")
  public record MyRequest(
      UUID id,
      Instant requestedAt,
      State state,
      ShiftView shift,
      String reasonLocale,
      String reason,
      @Schema(nullable = true) MyDecision decision) {

    @Override
    public String toString() {
      return "MyRequest[id=" + id + ", state=" + state + "]";
    }
  }

  /**
   * One page of the caller's requests, newest first.
   *
   * @param items requests
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the instant every state is derived at
   */
  @Schema(name = "MyWorkScheduleSubstitutionPage")
  public record MyPage(
      List<MyRequest> items, @Schema(nullable = true) String nextCursor, Instant asOfInstant) {

    /** Defensively copies. */
    public MyPage {
      items = List.copyOf(items);
    }
  }

  /**
   * The requesting employee's number and current name; never their ID.
   *
   * @param employeeNumber number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "WorkScheduleSubstitutionApprovalEmployee")
  public record ApprovalEmployee(String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "ApprovalEmployee[redacted]";
    }
  }

  /**
   * A pending request routed to the caller.
   *
   * @param id request
   * @param requestedAt request instant
   * @param employee the requesting employee
   * @param reasonLocale the reason's language
   * @param reason the reason
   * @param shift the shift
   */
  @Schema(name = "WorkScheduleSubstitutionApproval")
  public record Approval(
      UUID id,
      Instant requestedAt,
      ApprovalEmployee employee,
      String reasonLocale,
      String reason,
      ShiftView shift) {

    @Override
    public String toString() {
      return "Approval[id=" + id + "]";
    }
  }

  /**
   * One page of the caller's inbox, oldest first.
   *
   * @param items requests
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the instant shifts start after
   */
  @Schema(name = "WorkScheduleSubstitutionApprovalPage")
  public record ApprovalPage(
      List<Approval> items, @Schema(nullable = true) String nextCursor, Instant asOfInstant) {

    /** Defensively copies. */
    public ApprovalPage {
      items = List.copyOf(items);
    }
  }

  /**
   * One page of eligible substitutes.
   *
   * @param items candidates in employee-number order
   * @param nextCursor next page, or {@code null}
   */
  @Schema(name = "WorkScheduleSubstitutionCandidatePage")
  public record CandidatePage(List<Person> items, @Schema(nullable = true) String nextCursor) {

    /** Defensively copies. */
    public CandidatePage {
      items = List.copyOf(items);
    }
  }

  /**
   * The minimal receipt of a decision; never a reason or a name.
   *
   * @param requestId request
   * @param decisionId decision
   * @param state {@code APPROVED} or {@code REJECTED}
   * @param decidedAt decision instant
   */
  @Schema(name = "WorkScheduleSubstitutionDecisionReceipt")
  public record DecisionReceipt(UUID requestId, UUID decisionId, String state, Instant decidedAt) {}
}
