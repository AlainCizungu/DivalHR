package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.schedule.application.WorkScheduleCommand;
import com.divalhr.core.people.schedule.application.WorkSchedulePlanner.Plan;
import com.divalhr.core.people.schedule.domain.EmployeeFacts;
import com.divalhr.core.people.schedule.domain.Lifecycle;
import com.divalhr.core.people.schedule.domain.OwnShift;
import com.divalhr.core.people.schedule.domain.PlannedSlot;
import com.divalhr.core.people.schedule.domain.PublishedSlot;
import com.divalhr.core.people.schedule.domain.ScheduleView;
import com.divalhr.core.people.schedule.domain.WorkSchedule;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Work schedule responses (MVP-050A). Never the publisher, an audit record or another employee's
 * data in the employee's own view.
 */
public final class WorkScheduleResponses {

  private WorkScheduleResponses() {}

  /**
   * The slot's two labels.
   *
   * @param en English label
   * @param fr French label
   */
  @Schema(name = "WorkScheduleLabels")
  public record Labels(String en, String fr) {}

  /**
   * A site, with its code and name (organization data).
   *
   * @param id site
   * @param code code
   * @param name display name
   */
  @Schema(name = "WorkScheduleSite")
  public record Site(UUID id, String code, String name) {

    static Site of(UnitView unit, UUID id) {
      return unit == null ? new Site(id, null, null) : new Site(id, unit.code(), unit.name());
    }
  }

  /**
   * An assigned employee, with current directory data (never stored with the slot).
   *
   * @param id employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "WorkScheduleEmployee")
  public record Employee(UUID id, String employeeNumber, String givenNames, String familyName) {}

  /**
   * Derived totals.
   *
   * @param slots slots
   * @param assigned slots with an employee
   * @param uncovered slots without one
   */
  @Schema(name = "WorkScheduleTotals")
  public record Totals(int slots, int assigned, int uncovered) {}

  /**
   * Why a slot cannot be published.
   *
   * @param code stable code
   * @param reason stable reason
   */
  @Schema(name = "WorkScheduleSlotIssue")
  public record Issue(String code, String reason) {}

  /**
   * One previewed slot.
   *
   * @param clientRef the client's reference
   * @param position position
   * @param start local start
   * @param end local end
   * @param startAt start instant, or {@code null} when the local time is not one instant
   * @param endAt end instant, or {@code null}
   * @param overnight whether it ends on a later date
   * @param labels labels
   * @param employee the assigned employee, or {@code null}
   * @param issue why it cannot be published, or {@code null}
   */
  @Schema(name = "WorkSchedulePreviewSlot")
  public record PreviewSlot(
      String clientRef,
      int position,
      String start,
      String end,
      @Schema(nullable = true) Instant startAt,
      @Schema(nullable = true) Instant endAt,
      boolean overnight,
      Labels labels,
      @Schema(nullable = true) Employee employee,
      @Schema(nullable = true) Issue issue) {}

  /**
   * A preview: what would be published, its totals and digest.
   *
   * @param asOf the site's business date (today in the site's time zone)
   * @param siteTimezone the site's IANA time zone: the clock of every slot, stored with the
   *     schedule
   * @param site the site
   * @param firstDate first date
   * @param lastDate last date
   * @param slots the slots in position order
   * @param totals totals
   * @param warnings stable warning codes
   * @param publishable whether every slot can be published
   * @param previewDigest the digest to publish with
   */
  @Schema(name = "WorkSchedulePreview")
  public record Preview(
      LocalDate asOf,
      String siteTimezone,
      Site site,
      LocalDate firstDate,
      LocalDate lastDate,
      List<PreviewSlot> slots,
      Totals totals,
      List<String> warnings,
      boolean publishable,
      String previewDigest) {

    /** Defensively copies the lists. */
    public Preview {
      slots = List.copyOf(slots);
      warnings = List.copyOf(warnings);
    }

    /**
     * The preview of a plan.
     *
     * @param plan the evaluated proposal
     * @return the preview
     */
    public static Preview of(Plan plan) {
      return of(plan, plan.digest());
    }

    /**
     * The preview of a plan under another digest (a replacement preview, MVP-050B, whose digest
     * also binds the original schedule and the reason).
     *
     * @param plan the evaluated proposal
     * @param digest the digest to publish with
     * @return the preview
     */
    public static Preview of(Plan plan, String digest) {
      List<PreviewSlot> slots = plan.slots().stream().map(slot -> slot(slot, plan)).toList();
      int assigned = plan.assigned();
      int uncovered = slots.size() - assigned;
      return new Preview(
          plan.asOf(),
          plan.zone().getId(),
          Site.of(plan.site(), plan.site().id()),
          plan.firstDate(),
          plan.lastDate(),
          slots,
          new Totals(slots.size(), assigned, uncovered),
          uncovered > 0 ? List.of("UNCOVERED_SLOTS") : List.of(),
          plan.publishable(),
          digest);
    }

    private static PreviewSlot slot(PlannedSlot slot, Plan plan) {
      UUID id = slot.draft().employeeId();
      EmployeeFacts facts = id == null ? null : plan.employees().get(id);
      return new PreviewSlot(
          slot.draft().clientRef(),
          slot.draft().position(),
          WorkScheduleCommand.wire(slot.draft().start()),
          WorkScheduleCommand.wire(slot.draft().end()),
          slot.startAt(),
          slot.endAt(),
          slot.overnight(),
          new Labels(slot.draft().labelEn(), slot.draft().labelFr()),
          facts == null
              ? null
              : new Employee(id, facts.employeeNumber(), facts.givenNames(), facts.familyName()),
          slot.issue() == null ? null : new Issue(slot.issue().code(), slot.issue().reason()));
    }
  }

  /**
   * A publication receipt.
   *
   * @param scheduleId the published schedule
   * @param publishedAt publication instant
   * @param totals totals
   */
  @Schema(name = "WorkSchedulePublicationReceipt")
  public record Receipt(UUID scheduleId, Instant publishedAt, Totals totals) {}

  /**
   * The receipt of a cancellation or replacement (MVP-050B). Never the reason or the actor.
   *
   * @param scheduleId the cancelled or replaced schedule
   * @param status its derived state now ({@code CANCELLED} or {@code REPLACED})
   * @param changedAt the action's instant
   * @param replacementScheduleId the successor of a replacement, or {@code null}
   * @param totals the successor's totals, or {@code null} for a cancellation
   */
  @Schema(name = "WorkScheduleChangeReceipt")
  public record ChangeReceipt(
      UUID scheduleId,
      Lifecycle.Status status,
      Instant changedAt,
      @Schema(nullable = true) UUID replacementScheduleId,
      @Schema(nullable = true) Totals totals) {}

  /**
   * A published schedule, without its slots, with its derived state (MVP-050B). The change reason
   * is Restricted HR, returned to tenant administrators only; the actor is never returned.
   *
   * @param id schedule
   * @param site the site
   * @param firstDate first date
   * @param lastDate last date
   * @param siteTimezone the stored site time zone snapshot: the clock of every slot
   * @param totals totals
   * @param publishedAt publication instant
   * @param status derived state
   * @param changedAt the cancellation or replacement instant, or {@code null}
   * @param changeReason the cancellation or replacement reason, or {@code null}
   * @param replacesScheduleId the schedule this one replaced, or {@code null}
   * @param replacedByScheduleId the schedule that replaced this one, or {@code null}
   */
  @Schema(name = "WorkScheduleSummary")
  public record Summary(
      UUID id,
      Site site,
      LocalDate firstDate,
      LocalDate lastDate,
      String siteTimezone,
      Totals totals,
      Instant publishedAt,
      Lifecycle.Status status,
      @Schema(nullable = true) Instant changedAt,
      @Schema(nullable = true) String changeReason,
      @Schema(nullable = true) UUID replacesScheduleId,
      @Schema(nullable = true) UUID replacedByScheduleId) {

    /**
     * The summary of a schedule.
     *
     * @param view schedule and lifecycle
     * @param site its site, or {@code null} when the port no longer resolves it
     * @return the summary
     */
    public static Summary of(ScheduleView view, UnitView site) {
      WorkSchedule schedule = view.schedule();
      Lifecycle lifecycle = view.lifecycle();
      return new Summary(
          schedule.id(),
          Site.of(site, schedule.siteId()),
          schedule.firstDate(),
          schedule.lastDate(),
          schedule.siteTimezone(),
          new Totals(schedule.slotCount(), schedule.assignedCount(), schedule.uncoveredCount()),
          schedule.publishedAt(),
          lifecycle.status(),
          lifecycle.changedAt(),
          lifecycle.reason(),
          lifecycle.predecessorId(),
          lifecycle.successorId());
    }

    /**
     * Identifiers and state only: the reason never reaches a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Summary[id=" + id + ", status=" + status + "]";
    }
  }

  /**
   * One page of schedules.
   *
   * @param items schedules, latest period first
   * @param nextCursor next page, or {@code null}
   * @param asOf the organization's business date (informational; each schedule's clock is its
   *     {@code siteTimezone})
   */
  @Schema(name = "WorkSchedulePage")
  public record Page(
      List<Summary> items, @Schema(nullable = true) String nextCursor, LocalDate asOf) {

    /** Defensively copies the lists. */
    public Page {
      items = List.copyOf(items);
    }
  }

  /**
   * One published slot.
   *
   * @param id slot
   * @param position position
   * @param start local start
   * @param end local end
   * @param startAt start instant
   * @param endAt end instant
   * @param overnight whether it ends on a later date
   * @param labels labels
   * @param employee the assigned employee, or {@code null} for an uncovered slot
   */
  @Schema(name = "WorkScheduleSlot")
  public record Slot(
      UUID id,
      int position,
      String start,
      String end,
      Instant startAt,
      Instant endAt,
      boolean overnight,
      Labels labels,
      @Schema(nullable = true) Employee employee) {

    static Slot of(PublishedSlot slot) {
      return new Slot(
          slot.id(),
          slot.position(),
          WorkScheduleCommand.wire(slot.start()),
          WorkScheduleCommand.wire(slot.end()),
          slot.startAt(),
          slot.endAt(),
          slot.end().toLocalDate().isAfter(slot.start().toLocalDate()),
          new Labels(slot.labelEn(), slot.labelFr()),
          slot.employeeId() == null
              ? null
              : new Employee(
                  slot.employeeId(), slot.employeeNumber(), slot.givenNames(), slot.familyName()));
    }
  }

  /**
   * A published schedule with all its slots and its derived state (MVP-050B).
   *
   * @param id schedule
   * @param site the site
   * @param firstDate first date
   * @param lastDate last date
   * @param siteTimezone the stored site time zone snapshot: the clock of every slot
   * @param totals totals
   * @param publishedAt publication instant
   * @param status derived state
   * @param changedAt the cancellation or replacement instant, or {@code null}
   * @param changeReason the cancellation or replacement reason, or {@code null}
   * @param replacesScheduleId the schedule this one replaced, or {@code null}
   * @param replacedByScheduleId the schedule that replaced this one, or {@code null}
   * @param slots slots in publication order
   */
  @Schema(name = "WorkScheduleDetail")
  public record Detail(
      UUID id,
      Site site,
      LocalDate firstDate,
      LocalDate lastDate,
      String siteTimezone,
      Totals totals,
      Instant publishedAt,
      Lifecycle.Status status,
      @Schema(nullable = true) Instant changedAt,
      @Schema(nullable = true) String changeReason,
      @Schema(nullable = true) UUID replacesScheduleId,
      @Schema(nullable = true) UUID replacedByScheduleId,
      List<Slot> slots) {

    /** Defensively copies the lists. */
    public Detail {
      slots = List.copyOf(slots);
    }

    /**
     * The detail of a schedule.
     *
     * @param schedule schedule and lifecycle
     * @param site its site, or {@code null}
     * @param slots its slots
     * @return the detail
     */
    public static Detail of(ScheduleView schedule, UnitView site, List<PublishedSlot> slots) {
      Summary summary = Summary.of(schedule, site);
      return new Detail(
          summary.id(),
          summary.site(),
          summary.firstDate(),
          summary.lastDate(),
          summary.siteTimezone(),
          summary.totals(),
          summary.publishedAt(),
          summary.status(),
          summary.changedAt(),
          summary.changeReason(),
          summary.replacesScheduleId(),
          summary.replacedByScheduleId(),
          slots.stream().map(Slot::of).toList());
    }

    /**
     * Identifiers and state only: the reason and the slots never reach a log line.
     *
     * @return safe text
     */
    @Override
    public String toString() {
      return "Detail[id=" + id + ", status=" + status + "]";
    }
  }

  /**
   * One of the caller's own shifts.
   *
   * @param id slot
   * @param scheduleId schedule
   * @param site the site
   * @param start local start
   * @param end local end
   * @param siteTimezone the stored site time zone snapshot of the shift's schedule
   * @param overnight whether it ends on a later date
   * @param labels labels
   */
  @Schema(name = "MyWorkShift")
  public record Shift(
      UUID id,
      UUID scheduleId,
      Site site,
      String start,
      String end,
      String siteTimezone,
      boolean overnight,
      Labels labels) {

    /**
     * The view of an own shift.
     *
     * @param shift the shift
     * @param site its site, or {@code null}
     * @return the view
     */
    public static Shift of(OwnShift shift, UnitView site) {
      return new Shift(
          shift.slotId(),
          shift.scheduleId(),
          Site.of(site, shift.siteId()),
          WorkScheduleCommand.wire(shift.start()),
          WorkScheduleCommand.wire(shift.end()),
          shift.siteTimezone(),
          shift.end().toLocalDate().isAfter(shift.start().toLocalDate()),
          new Labels(shift.labelEn(), shift.labelFr()));
    }
  }

  /**
   * One page of the caller's own shifts (R100-2). Without {@code from}, the view is the shifts not
   * yet ended at one server-owned instant ({@code asOfInstant}), pinned across its pages; with
   * {@code from} (and optionally {@code to}), the shifts whose local start date, in their own
   * schedule's site time zone, is within the dates.
   *
   * @param items shifts in start order
   * @param nextCursor next page, or {@code null}
   * @param asOfInstant the default view's anchor instant, or {@code null} with explicit dates
   * @param from first local start date of an explicit view, or {@code null}
   * @param to last local start date of an explicit view, or {@code null}
   */
  @Schema(name = "MyWorkSchedulePage")
  public record ShiftPage(
      List<Shift> items,
      @Schema(nullable = true) String nextCursor,
      @Schema(nullable = true) Instant asOfInstant,
      @Schema(nullable = true) LocalDate from,
      @Schema(nullable = true) LocalDate to) {

    /** Defensively copies the lists. */
    public ShiftPage {
      items = List.copyOf(items);
    }
  }
}
