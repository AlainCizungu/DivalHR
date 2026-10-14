package com.divalhr.core.people.schedule.domain;

import com.divalhr.core.platform.idempotency.Fingerprints;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MVP-070A weekly draft timesheets (Issue #128, T70A-2 to T70A-5; ADR 0018): the fixed site-local
 * week, the row derived from one included slot and its effective attendance, the closed row states,
 * the worked duration and the totals. Pure rules: no I/O, no clock.
 */
public final class Timesheet {

  /** At most this many drafts in one generation or refresh (T70A-3). */
  public static final int MAX_DRAFTS = 500;

  /** At most this many source rows in one generation or refresh (T70A-3). */
  public static final int MAX_ROWS = 5000;

  private Timesheet() {}

  /** The closed row states (T70A-5). */
  public enum State {
    /** Completed attendance: the whole minutes between the effective instants. */
    READY,
    /** No attendance record for the slot and its effective assignee. */
    MISSING_ATTENDANCE,
    /** A record without an effective check-out. */
    OPEN_ATTENDANCE,
    /** A slot of the previous revision that is no longer included (refresh only). */
    SOURCE_CHANGED;

    /**
     * Whether the state blocks a later submission.
     *
     * @return true for every state but READY
     */
    public boolean blocking() {
      return this != READY;
    }
  }

  /**
   * One site-local week: Monday 00:00 to the next Monday 00:00 in the site's IANA time zone.
   *
   * @param siteId site
   * @param timezone the site's IANA time zone
   * @param weekStart the local Monday
   * @param startAt Monday 00:00 at the site, as an instant
   * @param endAt the next Monday 00:00 at the site, as an instant
   */
  public record Period(
      UUID siteId, String timezone, LocalDate weekStart, Instant startAt, Instant endAt) {

    /** Requires every component, a Monday and seven local days. */
    public Period {
      Objects.requireNonNull(siteId, "siteId");
      Objects.requireNonNull(timezone, "timezone");
      Objects.requireNonNull(weekStart, "weekStart");
      if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY || !endAt.isAfter(startAt)) {
        throw new IllegalArgumentException("not a weekly period");
      }
    }

    /**
     * The week of a local Monday at a site: the boundaries are the zone's first instants of the two
     * Mondays (a daylight-saving gap at midnight moves the boundary to the first valid instant).
     *
     * @param siteId site
     * @param timezone the site's IANA time zone
     * @param weekStart the local Monday
     * @return the period
     */
    public static Period of(UUID siteId, String timezone, LocalDate weekStart) {
      ZoneId zone = ZoneId.of(timezone);
      return new Period(
          siteId,
          timezone,
          weekStart,
          weekStart.atStartOfDay(zone).toInstant(),
          weekStart.plusDays(7).atStartOfDay(zone).toInstant());
    }

    /**
     * The next Monday (exclusive end date).
     *
     * @return the date
     */
    public LocalDate weekEnd() {
      return weekStart.plusDays(7);
    }

    /**
     * The Sunday (the last local date of the week).
     *
     * @return the date
     */
    public LocalDate lastDay() {
      return weekStart.plusDays(6);
    }

    /**
     * The local Monday of the week containing a date.
     *
     * @param day any date
     * @return its Monday
     */
    public static LocalDate mondayOf(LocalDate day) {
      return day.minusDays(day.getDayOfWeek().getValue() - 1L);
    }
  }

  /**
   * The facts of one included slot (T70A-3, T70A-4): the slot, its effective assignee's single
   * qualifying employment and dated placement, and the effective attendance projection.
   *
   * @param slotId slot
   * @param scheduleId schedule
   * @param employeeId effective assignee
   * @param employmentId the single qualifying employment
   * @param siteId site
   * @param costCenterId the placement's cost center on the slot's local date, or {@code null}
   * @param labelEn English label
   * @param labelFr French label
   * @param scheduledStartAt scheduled start
   * @param scheduledEndAt scheduled end
   * @param recordId the effective attendance record, or {@code null}
   * @param checkedInAt effective check-in, or {@code null}
   * @param checkedOutAt effective check-out, or {@code null}
   * @param correctionVersion the record's correction version, or {@code null}
   */
  public record Source(
      UUID slotId,
      UUID scheduleId,
      UUID employeeId,
      UUID employmentId,
      UUID siteId,
      UUID costCenterId,
      String labelEn,
      String labelFr,
      Instant scheduledStartAt,
      Instant scheduledEndAt,
      UUID recordId,
      Instant checkedInAt,
      Instant checkedOutAt,
      Integer correctionVersion) {}

  /**
   * One timesheet row: the snapshot, its state and, for READY only, the worked minutes.
   *
   * @param source the snapshot facts (attendance fields absent for MISSING_ATTENDANCE and
   *     SOURCE_CHANGED)
   * @param state the state
   * @param workedMinutes worked minutes (READY), or {@code null}
   */
  public record Row(Source source, State state, Integer workedMinutes) {

    /**
     * The row of a slot's current facts (T70A-5): READY for completed attendance, otherwise
     * blocked; never guessed.
     *
     * @param source the facts
     * @return the row
     */
    public static Row of(Source source) {
      if (source.recordId() == null) {
        return new Row(withoutAttendance(source), State.MISSING_ATTENDANCE, null);
      }
      if (source.checkedOutAt() == null) {
        return new Row(source, State.OPEN_ATTENDANCE, null);
      }
      return new Row(source, State.READY, minutes(source.checkedInAt(), source.checkedOutAt()));
    }

    /**
     * The row of a previously captured slot that is no longer included: its schedule facts as
     * captured, no attendance and no worked time.
     *
     * @param previous the previous revision's row
     * @return the SOURCE_CHANGED row
     */
    public static Row changed(Row previous) {
      return new Row(withoutAttendance(previous.source()), State.SOURCE_CHANGED, null);
    }

    /**
     * The canonical text of the row (the revision's source digest and change detection).
     *
     * @return canonical text
     */
    public String canonical() {
      Source s = source;
      return "slot="
          + s.slotId()
          + "|schedule="
          + s.scheduleId()
          + "|employee="
          + s.employeeId()
          + "|employment="
          + s.employmentId()
          + "|site="
          + s.siteId()
          + "|costCenter="
          + s.costCenterId()
          + "|labelEn="
          + s.labelEn()
          + "|labelFr="
          + s.labelFr()
          + "|start="
          + s.scheduledStartAt()
          + "|end="
          + s.scheduledEndAt()
          + "|record="
          + s.recordId()
          + "|in="
          + s.checkedInAt()
          + "|out="
          + s.checkedOutAt()
          + "|correction="
          + s.correctionVersion()
          + "|state="
          + state
          + "|minutes="
          + workedMinutes;
    }

    private static Source withoutAttendance(Source s) {
      return new Source(
          s.slotId(),
          s.scheduleId(),
          s.employeeId(),
          s.employmentId(),
          s.siteId(),
          s.costCenterId(),
          s.labelEn(),
          s.labelFr(),
          s.scheduledStartAt(),
          s.scheduledEndAt(),
          null,
          null,
          null,
          null);
    }
  }

  /**
   * The exact elapsed whole minutes between two instants, truncated toward zero (the instants are
   * already microsecond-normalized); never rounded, never a break or overtime.
   *
   * @param in effective check-in
   * @param out effective check-out (after the check-in)
   * @return whole minutes
   */
  public static int minutes(Instant in, Instant out) {
    return Math.toIntExact(Duration.between(in, out).toMinutes());
  }

  /**
   * Totals of a revision: counts by state and the READY rows' worked minutes.
   *
   * @param rows row count
   * @param ready READY rows
   * @param missing MISSING_ATTENDANCE rows
   * @param open OPEN_ATTENDANCE rows
   * @param changed SOURCE_CHANGED rows
   * @param workedMinutes sum of READY rows' minutes
   */
  public record Totals(int rows, int ready, int missing, int open, int changed, int workedMinutes) {

    /** No row. */
    public static final Totals NONE = new Totals(0, 0, 0, 0, 0, 0);

    /**
     * The totals of rows.
     *
     * @param rows the rows
     * @return the totals
     */
    public static Totals of(List<Row> rows) {
      int ready = 0;
      int missing = 0;
      int open = 0;
      int changed = 0;
      int worked = 0;
      for (Row row : rows) {
        switch (row.state()) {
          case READY -> {
            ready++;
            worked = Math.addExact(worked, row.workedMinutes());
          }
          case MISSING_ATTENDANCE -> missing++;
          case OPEN_ATTENDANCE -> open++;
          case SOURCE_CHANGED -> changed++;
        }
      }
      return new Totals(rows.size(), ready, missing, open, changed, worked);
    }

    /**
     * Rows that block a later submission.
     *
     * @return missing, open and changed rows
     */
    public int blocked() {
      return missing + open + changed;
    }

    /**
     * The sum of two totals.
     *
     * @param other other totals
     * @return the sum
     */
    public Totals plus(Totals other) {
      return new Totals(
          rows + other.rows,
          ready + other.ready,
          missing + other.missing,
          open + other.open,
          changed + other.changed,
          Math.addExact(workedMinutes, other.workedMinutes));
    }
  }

  /**
   * Rows in their stable order: scheduled start, then slot.
   *
   * @param rows rows
   * @return a sorted copy
   */
  public static List<Row> ordered(List<Row> rows) {
    List<Row> sorted = new ArrayList<>(rows);
    sorted.sort(
        Comparator.comparing((Row r) -> r.source().scheduledStartAt())
            .thenComparing(r -> r.source().slotId()));
    return List.copyOf(sorted);
  }

  /**
   * The next revision's rows (T70A-7): every currently included slot with its current facts, and
   * every slot of the previous revision that is no longer included as SOURCE_CHANGED (kept until a
   * later story resolves it; never dropped silently).
   *
   * @param previous the previous revision's rows
   * @param current the current facts of the employee's included slots
   * @return the next revision's rows, ordered
   */
  public static List<Row> refreshed(List<Row> previous, List<Row> current) {
    Map<UUID, Row> next = new LinkedHashMap<>();
    current.forEach(row -> next.put(row.source().slotId(), row));
    for (Row row : previous) {
      next.computeIfAbsent(
          row.source().slotId(),
          slot -> row.state() == State.SOURCE_CHANGED ? row : Row.changed(row));
    }
    return ordered(List.copyOf(next.values()));
  }

  /**
   * How many slots differ between two revisions: added, removed or with any different fact.
   *
   * @param previous the previous rows
   * @param next the next rows
   * @return the change count
   */
  public static int changes(List<Row> previous, List<Row> next) {
    Map<UUID, String> before = new LinkedHashMap<>();
    previous.forEach(row -> before.put(row.source().slotId(), row.canonical()));
    int changed = 0;
    for (Row row : next) {
      String old = before.remove(row.source().slotId());
      if (!row.canonical().equals(old)) {
        changed++;
      }
    }
    return changed + before.size();
  }

  /**
   * The source digest of a revision: SHA-256 over its ordered canonical rows.
   *
   * @param rows the ordered rows
   * @return hex digest
   */
  public static String digest(List<Row> rows) {
    StringBuilder text = new StringBuilder("DIVALHR-TIMESHEET-REVISION\nversion=1\n");
    rows.forEach(row -> text.append(row.canonical()).append('\n'));
    return Fingerprints.sha256(text.toString());
  }
}
