package com.divalhr.core.people.schedule.application;

import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Action;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.LockState;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Member;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Readiness;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Scope;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.State;
import com.divalhr.core.people.schedule.internal.JdbcLockedApprovedTimeRepository;
import com.divalhr.core.people.schedule.internal.JdbcLockedApprovedTimeRepository.GeneratedWeek;
import com.divalhr.core.people.schedule.internal.JdbcLockedApprovedTimeRepository.ScheduledWeek;
import com.divalhr.core.people.schedule.internal.JdbcTimesheetPeriodRepository;
import com.divalhr.core.people.schedule.internal.JdbcWorkScheduleRepository;
import com.divalhr.core.platform.access.LockedApprovedTime;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people module's side of the payroll port (MVP-090A, ADR 0023): the weeks that matter for a
 * legal entity's sites and dates with their current lock evidence, and the approved rows of a
 * generation. The current evidence digest is the one a lock binds ({@link
 * TimesheetPeriod#evidenceDigest}), so payroll can prove the locked week is unchanged without
 * reading a people table.
 */
@Component
public class ScheduleLockedApprovedTime implements LockedApprovedTime {

  private final JdbcWorkScheduleRepository schedules;
  private final JdbcTimesheetPeriodRepository periods;
  private final JdbcLockedApprovedTimeRepository reads;

  /**
   * Creates the adapter.
   *
   * @param schedules schedule storage (the tenant's scheduling lock)
   * @param periods period lock storage
   * @param reads the port's reads
   */
  public ScheduleLockedApprovedTime(
      JdbcWorkScheduleRepository schedules,
      JdbcTimesheetPeriodRepository periods,
      JdbcLockedApprovedTimeRepository reads) {
    this.schedules = schedules;
    this.periods = periods;
    this.reads = reads;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockForPayrollInputs(TenantId tenant) {
    schedules.lock(tenant);
  }

  @Override
  public List<Week> weeks(TenantId tenant, Collection<UUID> siteIds, LocalDate from, LocalDate to) {
    if (siteIds.isEmpty() || to.isBefore(from)) {
      return List.of();
    }
    LocalDate firstMonday = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate lastMonday = to.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    Map<Key, Week> weeks = new TreeMap<>();
    for (ScheduledWeek scheduled : reads.scheduledWeeks(tenant, siteIds, firstMonday, lastMonday)) {
      weeks.put(
          new Key(scheduled.siteId(), scheduled.weekStart()),
          new Week(scheduled.siteId(), scheduled.weekStart(), scheduled.timezone(), true, null));
    }
    List<GeneratedWeek> generated = reads.generations(tenant, siteIds, firstMonday, lastMonday);
    List<UUID> generationIds = generated.stream().map(GeneratedWeek::id).toList();
    Map<UUID, LockState> states = periods.lockStates(tenant, generationIds);
    Map<UUID, Action> actions = new HashMap<>();
    for (LockState state : states.values()) {
      if (state.currentActionId() != null) {
        periods
            .action(tenant, state.currentActionId())
            .ifPresent(action -> actions.put(state.currentActionId(), action));
      }
    }
    for (GeneratedWeek week : generated) {
      Key key = new Key(week.siteId(), week.weekStart());
      Week scheduled = weeks.get(key);
      LockState state = states.getOrDefault(week.id(), LockState.NEVER);
      Action action = state.currentActionId() == null ? null : actions.get(state.currentActionId());
      weeks.put(
          key,
          new Week(
              week.siteId(),
              week.weekStart(),
              week.timezone(),
              scheduled != null && scheduled.scheduled(),
              generation(tenant, week, state, action)));
    }
    return new ArrayList<>(weeks.values());
  }

  @Override
  public List<ApprovedRow> approvedRows(TenantId tenant, UUID generationId) {
    return reads.approvedRows(tenant, generationId);
  }

  /** The generation with its current evidence digest and lock. */
  private Generation generation(
      TenantId tenant, GeneratedWeek week, LockState state, Action action) {
    List<Member> members = periods.members(tenant, week.id(), false);
    // The digest the lock binds is computed over the lock's scope (its legal entity); a week that
    // was never locked has no lock to compare with, and its digest is informative only.
    UUID legalEntity = action == null ? new UUID(0L, 0L) : action.scope().legalEntityId();
    Scope scope =
        new Scope(
            legalEntity,
            new Period(
                week.siteId(),
                week.timezone(),
                week.weekStart(),
                week.periodStartAt(),
                week.periodEndAt()),
            week.id(),
            week.version());
    Readiness readiness = Readiness.of(scope, members, state.state() == State.LOCKED);
    Lock lock =
        action == null
            ? null
            : new Lock(
                state.state() == State.LOCKED,
                state.version(),
                action.id(),
                action.scope().generationVersion(),
                action.evidenceDigest(),
                action.totals().timesheets(),
                action.totals().rows(),
                action.totals().workedMinutes());
    return new Generation(
        week.id(),
        week.version(),
        week.periodStartAt(),
        week.periodEndAt(),
        readiness.evidenceDigest(),
        members.size(),
        (int) members.stream().filter(Member::approvedNow).count(),
        lock);
  }

  /** A site's week, ordered by site then week. */
  private record Key(UUID siteId, LocalDate weekStart) implements Comparable<Key> {
    private static final Comparator<Key> ORDER =
        Comparator.comparing((Key k) -> k.siteId().toString()).thenComparing(Key::weekStart);

    @Override
    public int compareTo(Key other) {
      return ORDER.compare(this, other);
    }
  }
}
