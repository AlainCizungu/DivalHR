package com.divalhr.core.people.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.Timesheet.Row;
import com.divalhr.core.people.schedule.domain.Timesheet.Source;
import com.divalhr.core.people.schedule.domain.Timesheet.State;
import com.divalhr.core.people.schedule.domain.Timesheet.Totals;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-070A (T70A-12 items 1, 5 and 6): the site-local Monday boundaries at positive and negative
 * UTC offsets and across a daylight-saving change; the row states that are never guessed; the exact
 * whole-minute truncation and the totals; the next revision's SOURCE_CHANGED rows and change count.
 */
class TimesheetTest {

  private static final UUID SITE = UUID.randomUUID();
  private static final Instant START = Instant.parse("2026-10-13T06:00:00Z");

  @Test
  void aWeekIsMondayToMondayInTheSitesZoneAtEveryOffsetAndAcrossDaylightSaving() {
    LocalDate monday = LocalDate.of(2026, 10, 12);
    Period kinshasa = Period.of(SITE, "Africa/Kinshasa", monday); // UTC+1
    assertThat(kinshasa.startAt()).isEqualTo(Instant.parse("2026-10-11T23:00:00Z"));
    assertThat(kinshasa.endAt()).isEqualTo(Instant.parse("2026-10-18T23:00:00Z"));
    assertThat(kinshasa.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 19));
    assertThat(kinshasa.lastDay()).isEqualTo(LocalDate.of(2026, 10, 18));
    Period lubumbashi = Period.of(SITE, "Africa/Lubumbashi", monday); // UTC+2
    assertThat(lubumbashi.startAt()).isEqualTo(Instant.parse("2026-10-11T22:00:00Z"));
    Period chicago = Period.of(SITE, "America/Chicago", monday); // UTC-5 (daylight time)
    assertThat(chicago.startAt()).isEqualTo(Instant.parse("2026-10-12T05:00:00Z"));
    assertThat(Duration.between(chicago.startAt(), chicago.endAt()))
        .isEqualTo(Duration.ofHours(168));
    // Paris leaves daylight time on Sunday 25 October 2026: the week has 169 hours.
    Period paris = Period.of(SITE, "Europe/Paris", LocalDate.of(2026, 10, 19));
    assertThat(paris.startAt()).isEqualTo(Instant.parse("2026-10-18T22:00:00Z"));
    assertThat(paris.endAt()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
    assertThat(Duration.between(paris.startAt(), paris.endAt())).isEqualTo(Duration.ofHours(169));
    // Spring: 167 hours.
    Period spring = Period.of(SITE, "Europe/Paris", LocalDate.of(2026, 3, 23));
    assertThat(Duration.between(spring.startAt(), spring.endAt())).isEqualTo(Duration.ofHours(167));
    // Only a Monday is a week; the Monday of any date.
    assertThatThrownBy(() -> Period.of(SITE, "Africa/Kinshasa", monday.plusDays(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Period.mondayOf(LocalDate.of(2026, 10, 18))).isEqualTo(monday);
    assertThat(Period.mondayOf(monday)).isEqualTo(monday);
  }

  @Test
  void missingAndOpenAttendanceBlockAndAreNeverGuessed() {
    Row missing = Row.of(source(null, null, null, null));
    assertThat(missing.state()).isEqualTo(State.MISSING_ATTENDANCE);
    assertThat(missing.workedMinutes()).isNull();
    UUID record = UUID.randomUUID();
    Row open = Row.of(source(record, START, null, 0));
    assertThat(open.state()).isEqualTo(State.OPEN_ATTENDANCE);
    assertThat(open.workedMinutes()).isNull();
    assertThat(open.source().checkedInAt()).isEqualTo(START);
    assertThat(State.READY.blocking()).isFalse();
    assertThat(State.MISSING_ATTENDANCE.blocking()).isTrue();
    assertThat(State.OPEN_ATTENDANCE.blocking()).isTrue();
    assertThat(State.SOURCE_CHANGED.blocking()).isTrue();
  }

  @Test
  void workedMinutesAreTheExactElapsedWholeMinutesTruncated() {
    UUID record = UUID.randomUUID();
    Instant almost = START.plus(Duration.ofMinutes(480)).minus(1, ChronoUnit.MICROS);
    Row ready = Row.of(source(record, START, almost, 2));
    assertThat(ready.state()).isEqualTo(State.READY);
    assertThat(ready.workedMinutes()).isEqualTo(479);
    assertThat(ready.source().correctionVersion()).isEqualTo(2);
    assertThat(Row.of(source(record, START, START.plusSeconds(59), 0)).workedMinutes()).isZero();
    assertThat(Row.of(source(record, START, START.plusSeconds(60), 0)).workedMinutes())
        .isEqualTo(1);
    Totals totals =
        Totals.of(
            List.of(
                ready,
                Row.of(source(record, START, START.plusSeconds(3_659), 0)),
                Row.of(source(null, null, null, null)),
                Row.of(source(record, START, null, 0))));
    assertThat(totals).isEqualTo(new Totals(4, 2, 1, 1, 0, 479 + 60));
    assertThat(totals.blocked()).isEqualTo(2);
  }

  @Test
  void aRefreshKeepsCurrentFactsAndMarksSlotsNoLongerIncluded() {
    UUID record = UUID.randomUUID();
    Source kept = source(record, START, START.plusSeconds(3_600), 0);
    Source dropped = source(null, null, null, null);
    List<Row> previous = Timesheet.ordered(List.of(Row.of(kept), Row.of(dropped)));
    // Kept, now corrected: new facts; dropped: SOURCE_CHANGED with its schedule facts only.
    Source corrected =
        new Source(
            kept.slotId(),
            kept.scheduleId(),
            kept.employeeId(),
            kept.employmentId(),
            kept.siteId(),
            kept.costCenterId(),
            kept.labelEn(),
            kept.labelFr(),
            kept.scheduledStartAt(),
            kept.scheduledEndAt(),
            record,
            START,
            START.plusSeconds(7_200),
            1);
    List<Row> next = Timesheet.refreshed(previous, List.of(Row.of(corrected)));
    assertThat(next).hasSize(2);
    Row changed =
        next.stream().filter(r -> r.source().slotId().equals(dropped.slotId())).findFirst().get();
    assertThat(changed.state()).isEqualTo(State.SOURCE_CHANGED);
    assertThat(changed.workedMinutes()).isNull();
    assertThat(changed.source().recordId()).isNull();
    assertThat(Timesheet.changes(previous, next)).isEqualTo(2);
    assertThat(Totals.of(next)).isEqualTo(new Totals(2, 1, 0, 0, 1, 120));
    // A further identical refresh changes nothing; the digest is stable.
    List<Row> again = Timesheet.refreshed(next, List.of(Row.of(corrected)));
    assertThat(Timesheet.changes(next, again)).isZero();
    assertThat(Timesheet.digest(again)).isEqualTo(Timesheet.digest(next));
  }

  private static Source source(UUID record, Instant in, Instant out, Integer correction) {
    return new Source(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        SITE,
        null,
        "Cashier",
        "Caissière",
        START.minus(Duration.ofMinutes(10)),
        START.plus(Duration.ofHours(8)),
        record,
        in,
        out,
        correction);
  }
}
