package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.application.BusinessCalendar;
import com.divalhr.core.people.schedule.domain.EmployeeFacts;
import com.divalhr.core.people.schedule.domain.PlannedSlot;
import com.divalhr.core.people.schedule.domain.SlotDraft;
import com.divalhr.core.people.schedule.domain.SlotIssue;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Evaluates a proposed schedule against the current facts (MVP-050A, D50A-2 to D50A-5): the same
 * evaluation serves the preview and, under the scheduling lock, the publication, so a publication
 * is exactly what was reviewed or is refused.
 *
 * <p>Whole-schedule rules throw: the period (today or later in the organization's calendar, in
 * order, at most 31 days: {@code WORK_SCHEDULE_PERIOD_INVALID}), the site (a site of the tenant in
 * effect on every date: {@code WORK_SCHEDULE_SITE_NOT_ACTIVE}; unknown and foreign alike) and the
 * site's published coverage ({@code WORK_SCHEDULE_OVERLAP}). Slot rules become per-slot issues, the
 * first one found: the local times ({@code WORK_SCHEDULE_SLOT_INVALID}: outside the period, not
 * after the start, beyond the next date or 24 hours, or a local time that does not exist or repeats
 * in the time zone), the assigned employee ({@code WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE}: not an
 * employee of the tenant, separated before the slot ends, not employed through it, not placed at
 * the site on its start date) and conflicts ({@code WORK_SCHEDULE_EMPLOYEE_CONFLICT}: an
 * overlapping published assignment, or an earlier overlapping slot of this proposal).
 *
 * <p>The digest binds the business date, the time zone, the site's period, every normalized slot
 * with its instants and issue, and every fact the eligibility read: employment IDs, periods and
 * versions, placement rows and separations.
 */
@Component
public class WorkSchedulePlanner {

  /** Longest schedule: 31 inclusive days. */
  static final int MAX_DAYS = 31;

  private static final Duration MAX_SLOT = Duration.ofHours(24);

  private final BusinessCalendar calendar;
  private final OrganizationPlacementDirectory directory;
  private final JdbcWorkScheduleRepository schedules;

  /**
   * Creates the planner.
   *
   * @param calendar the organization's business date and time zone
   * @param directory the tenant module's unit port
   * @param schedules schedule storage and facts
   */
  public WorkSchedulePlanner(
      BusinessCalendar calendar,
      OrganizationPlacementDirectory directory,
      JdbcWorkScheduleRepository schedules) {
    this.calendar = calendar;
    this.directory = directory;
    this.schedules = schedules;
  }

  /**
   * An evaluated proposal.
   *
   * @param asOf the organization's business date
   * @param zone the organization's time zone
   * @param site the site
   * @param firstDate first date
   * @param lastDate last date
   * @param slots the slots in position order
   * @param employees the assigned employees found in the tenant, by ID
   * @param digest the preview digest
   */
  public record Plan(
      LocalDate asOf,
      ZoneId zone,
      UnitView site,
      LocalDate firstDate,
      LocalDate lastDate,
      List<PlannedSlot> slots,
      Map<UUID, EmployeeFacts> employees,
      String digest) {

    /** Defensively copies. */
    public Plan {
      slots = List.copyOf(slots);
      employees = Map.copyOf(employees);
    }

    /**
     * Slots with an assigned employee.
     *
     * @return the count
     */
    public int assigned() {
      return (int) slots.stream().filter(slot -> slot.draft().employeeId() != null).count();
    }

    /**
     * Whether every slot can be published.
     *
     * @return true without any issue
     */
    public boolean publishable() {
      return slots.stream().allMatch(slot -> slot.issue() == null);
    }

    @Override
    public String toString() {
      return "Plan[slots=" + slots.size() + "]";
    }
  }

  /**
   * Evaluates a proposal now.
   *
   * @param tenant verified tenant
   * @param command the proposal
   * @return the plan
   * @throws ApiException on a whole-schedule rule
   */
  public Plan plan(TenantId tenant, WorkScheduleCommand command) {
    ZoneId zone = calendar.zone(tenant);
    LocalDate today = calendar.today(zone);
    LocalDate first = command.firstDate();
    LocalDate last = command.lastDate();
    if (last.isBefore(first)) {
      throw period("ORDER");
    }
    if (first.isBefore(today)) {
      throw period("PAST");
    }
    if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
      throw period("TOO_LONG");
    }
    UnitView site = directory.resolveIds(tenant, Set.of(command.siteId())).get(command.siteId());
    if (site == null || site.kind() != UnitKind.SITE || !site.effectiveThroughout(first, last)) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_SITE_NOT_ACTIVE, Map.of());
    }
    if (schedules.siteCovered(tenant, site.id(), first, last)) {
      throw new ApiException(ErrorCode.WORK_SCHEDULE_OVERLAP, Map.of());
    }
    List<PlannedSlot> slots = new ArrayList<>(command.slots().size());
    for (SlotDraft draft : command.slots()) {
      slots.add(convert(draft, zone, first, last));
    }
    Set<UUID> employees = new LinkedHashSet<>();
    Instant from = first.atStartOfDay(zone).toInstant();
    Instant to = last.plusDays(2).atStartOfDay(zone).toInstant();
    for (PlannedSlot slot : slots) {
      if (slot.draft().employeeId() != null) {
        employees.add(slot.draft().employeeId());
      }
    }
    Map<UUID, EmployeeFacts> facts =
        schedules.facts(tenant, employees, first, last.plusDays(1), from, to);
    slots = eligible(slots, facts, site.id());
    String digest = digest(tenant, today, zone, site, first, last, slots, facts);
    return new Plan(today, zone, site, first, last, slots, facts, digest);
  }

  private static ApiException period(String reason) {
    return new ApiException(ErrorCode.WORK_SCHEDULE_PERIOD_INVALID, Map.of("reason", reason));
  }

  // ------------------------------------------------------------------------------------------
  // Local times
  // ------------------------------------------------------------------------------------------

  private static PlannedSlot convert(
      SlotDraft draft, ZoneId zone, LocalDate first, LocalDate last) {
    LocalDate day = draft.start().toLocalDate();
    if (day.isBefore(first) || day.isAfter(last)) {
      return invalid(draft, "OUTSIDE_PERIOD");
    }
    if (!draft.end().isAfter(draft.start())) {
      return invalid(draft, "END_NOT_AFTER_START");
    }
    if (draft.end().toLocalDate().isAfter(day.plusDays(1))) {
      return invalid(draft, "TOO_LONG");
    }
    Instant start = instant(draft.start(), zone);
    Instant end = instant(draft.end(), zone);
    if (start == null || end == null) {
      return invalid(draft, "LOCAL_TIME_INVALID");
    }
    if (Duration.between(start, end).compareTo(MAX_SLOT) > 0 || !end.isAfter(start)) {
      return new PlannedSlot(draft, start, end, new SlotIssue(SlotIssue.SLOT_INVALID, "TOO_LONG"));
    }
    return new PlannedSlot(draft, start, end, null);
  }

  private static PlannedSlot invalid(SlotDraft draft, String reason) {
    return new PlannedSlot(draft, null, null, new SlotIssue(SlotIssue.SLOT_INVALID, reason));
  }

  /**
   * The one instant of a local time in the zone, or {@code null} when the local time does not exist
   * (a gap) or happens twice (an overlap): never guessed.
   */
  static Instant instant(LocalDateTime local, ZoneId zone) {
    List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
    return offsets.size() == 1 ? local.toInstant(offsets.get(0)) : null;
  }

  // ------------------------------------------------------------------------------------------
  // Eligibility and conflicts
  // ------------------------------------------------------------------------------------------

  private static List<PlannedSlot> eligible(
      List<PlannedSlot> slots, Map<UUID, EmployeeFacts> facts, UUID siteId) {
    List<PlannedSlot> checked = new ArrayList<>(slots.size());
    Map<UUID, List<PlannedSlot>> accepted = new HashMap<>();
    for (PlannedSlot slot : slots) {
      UUID employee = slot.draft().employeeId();
      if (employee == null || slot.issue() != null) {
        checked.add(slot);
        continue;
      }
      EmployeeFacts found = facts.get(employee);
      PlannedSlot result = slot;
      if (found == null) {
        result = result.withIssue(notEligible("NOT_FOUND"));
      } else {
        LocalDate day = slot.draft().start().toLocalDate();
        LocalDate lastDay = lastOccupied(slot.draft());
        if (found.separations().stream()
            .anyMatch(separation -> separation.effectiveAt().isBefore(slot.endAt()))) {
          result = result.withIssue(notEligible("SEPARATED"));
        } else if (!employed(found.employments(), day, lastDay)) {
          result = result.withIssue(notEligible("NOT_EMPLOYED"));
        } else if (found.placements().stream()
            .noneMatch(
                placement ->
                    placement.siteId().equals(siteId)
                        && !day.isBefore(placement.from())
                        && (placement.to() == null || !day.isAfter(placement.to())))) {
          result = result.withIssue(notEligible("NOT_AT_SITE"));
        } else if (found.published().stream()
            .anyMatch(
                interval ->
                    interval.start().isBefore(slot.endAt())
                        && slot.startAt().isBefore(interval.end()))) {
          result = result.withIssue(new SlotIssue(SlotIssue.CONFLICT, "PUBLISHED"));
        } else {
          List<PlannedSlot> mine = accepted.computeIfAbsent(employee, k -> new ArrayList<>());
          if (mine.stream()
              .anyMatch(
                  other ->
                      other.startAt().isBefore(slot.endAt())
                          && slot.startAt().isBefore(other.endAt()))) {
            result = result.withIssue(new SlotIssue(SlotIssue.CONFLICT, "IN_SCHEDULE"));
          } else {
            mine.add(slot);
          }
        }
      }
      checked.add(result);
    }
    return checked;
  }

  private static SlotIssue notEligible(String reason) {
    return new SlotIssue(SlotIssue.NOT_ELIGIBLE, reason);
  }

  /** The last local date the slot occupies: a slot ending at midnight ends on the date before. */
  static LocalDate lastOccupied(SlotDraft draft) {
    LocalDate end = draft.end().toLocalDate();
    if (draft.end().toLocalTime().equals(LocalTime.MIDNIGHT)
        && end.isAfter(draft.start().toLocalDate())) {
      return end.minusDays(1);
    }
    return end;
  }

  /** Whether the employments together cover every date from {@code from} to {@code to}. */
  static boolean employed(
      List<EmployeeFacts.Employment> employments, LocalDate from, LocalDate to) {
    List<EmployeeFacts.Employment> sorted =
        employments.stream().sorted(Comparator.comparing(EmployeeFacts.Employment::from)).toList();
    LocalDate next = from;
    for (EmployeeFacts.Employment employment : sorted) {
      if (employment.from().isAfter(next)) {
        return false;
      }
      if (employment.to() == null) {
        return true;
      }
      if (!employment.to().isBefore(next)) {
        next = employment.to().plusDays(1);
      }
      if (next.isAfter(to)) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------------------
  // Digest
  // ------------------------------------------------------------------------------------------

  /**
   * Digest v1 of a preview: the SHA-256 (lower-case hex) of these exact UTF-8 bytes, every line
   * ending with LF:
   *
   * <pre>
   * DIVALHR-WORK-SCHEDULE-PREVIEW
   * version=1
   * tenant=&lt;id&gt;
   * asOf=&lt;date&gt;
   * timezone=&lt;zone&gt;
   * site=&lt;id&gt;|&lt;from&gt;|&lt;to or -&gt;
   * period=&lt;first&gt;|&lt;last&gt;
   * slots=&lt;n&gt;
   * s=&lt;pos&gt;|&lt;ref&gt;|&lt;start&gt;|&lt;end&gt;|&lt;startAt or -&gt;|&lt;endAt or -&gt;|&lt;employee or -&gt;|&lt;issue or -&gt;|en=&lt;label&gt;|fr=&lt;label&gt;
   * e=&lt;employee&gt;|employments=&lt;id:from:to:version;...&gt;|placements=&lt;id:site:from:to;...&gt;|separations=&lt;id:state:at;...&gt;
   * </pre>
   *
   * <p>Slots in position order; employees by ID, their facts by ID. Labels and references exclude
   * {@code |} and line breaks by grammar. The digest covers personal data and is only ever
   * compared.
   */
  static String digest(
      TenantId tenant,
      LocalDate asOf,
      ZoneId zone,
      UnitView site,
      LocalDate first,
      LocalDate last,
      List<PlannedSlot> slots,
      Map<UUID, EmployeeFacts> facts) {
    return Fingerprints.sha256(text(tenant, asOf, zone, site, first, last, slots, facts));
  }

  static String text(
      TenantId tenant,
      LocalDate asOf,
      ZoneId zone,
      UnitView site,
      LocalDate first,
      LocalDate last,
      List<PlannedSlot> slots,
      Map<UUID, EmployeeFacts> facts) {
    StringBuilder out = new StringBuilder("DIVALHR-WORK-SCHEDULE-PREVIEW\nversion=1\n");
    out.append("tenant=").append(tenant).append('\n');
    out.append("asOf=").append(asOf).append('\n');
    out.append("timezone=").append(zone.getId()).append('\n');
    out.append("site=")
        .append(site.id())
        .append('|')
        .append(site.effectiveFrom())
        .append('|')
        .append(orDash(site.effectiveTo()))
        .append('\n');
    out.append("period=").append(first).append('|').append(last).append('\n');
    out.append("slots=").append(slots.size()).append('\n');
    for (PlannedSlot slot : slots) {
      SlotDraft d = slot.draft();
      out.append("s=")
          .append(d.position())
          .append('|')
          .append(d.clientRef())
          .append('|')
          .append(WorkScheduleCommand.wire(d.start()))
          .append('|')
          .append(WorkScheduleCommand.wire(d.end()))
          .append('|')
          .append(orDash(slot.startAt()))
          .append('|')
          .append(orDash(slot.endAt()))
          .append('|')
          .append(orDash(d.employeeId()))
          .append('|')
          .append(slot.issue() == null ? "-" : slot.issue().code() + ":" + slot.issue().reason())
          .append("|en=")
          .append(d.labelEn())
          .append("|fr=")
          .append(d.labelFr())
          .append('\n');
    }
    for (EmployeeFacts f : new TreeMap<>(facts).values()) {
      out.append("e=").append(f.employeeId()).append("|employments=");
      f.employments().stream()
          .sorted(Comparator.comparing(EmployeeFacts.Employment::id))
          .forEach(
              e ->
                  out.append(e.id())
                      .append(':')
                      .append(e.from())
                      .append(':')
                      .append(orDash(e.to()))
                      .append(':')
                      .append(e.version())
                      .append(';'));
      out.append("|placements=");
      f.placements().stream()
          .sorted(Comparator.comparing(EmployeeFacts.Placement::id))
          .forEach(
              p ->
                  out.append(p.id())
                      .append(':')
                      .append(p.siteId())
                      .append(':')
                      .append(p.from())
                      .append(':')
                      .append(orDash(p.to()))
                      .append(';'));
      out.append("|separations=");
      f.separations().stream()
          .sorted(Comparator.comparing(EmployeeFacts.Separation::id))
          .forEach(
              s ->
                  out.append(s.id())
                      .append(':')
                      .append(s.state())
                      .append(':')
                      .append(s.effectiveAt())
                      .append(';'));
      out.append('\n');
    }
    return out.toString();
  }

  private static String orDash(Object value) {
    return value == null ? "-" : value.toString();
  }
}
