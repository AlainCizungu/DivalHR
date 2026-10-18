package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Timesheet.Period;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Action;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Kind;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.LockState;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Member;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Scope;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.State;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.Totals;
import com.divalhr.core.people.schedule.domain.TimesheetPeriod.UnlockReason;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * MVP-071 (V36): period locks. The members query reads, for one generation, every timesheet with
 * its current action's kind and bound evidence (driven by {@code timesheet_generation_member} and
 * the action primary key); the lock state is read by the projection's primary key, locked {@code
 * FOR UPDATE} by the commands; the history by {@code timesheet_period_action_history}; the
 * correction guard's lookup by {@code timesheet_period_lock_site_week}. Every query names the
 * verified tenant. Runs in the caller's transaction; the caller holds the tenant's scheduling lock
 * for every write.
 */
@Repository
public class JdbcTimesheetPeriodRepository {

  private static final String ACTION_COLUMNS =
      "a.id, a.generation_id, a.sequence, a.action, a.legal_entity_id, a.site_id, a.week_start,"
          + " a.site_timezone, a.period_start_at, a.period_end_at, a.generation_version,"
          + " a.timesheet_count, a.employee_count, a.row_count, a.worked_minutes,"
          + " a.evidence_digest, a.locked_action_id, a.unlock_reason, a.unlock_explanation,"
          + " a.occurred_at";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcTimesheetPeriodRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The generation's timesheets with their current action's evidence, in no particular order. With
   * {@code lock} the timesheets are locked {@code FOR UPDATE} first (in ID order, the lock order of
   * a refresh).
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @param lock whether to lock the timesheets
   * @return the members
   */
  public List<Member> members(TenantId tenant, UUID generationId, boolean lock) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", generationId);
    if (lock) {
      jdbc.query(
          "SELECT id FROM people.timesheet WHERE tenant_id = :tenant"
              + " AND generation_id = :generation ORDER BY id FOR UPDATE",
          params,
          (rs, n) -> 0);
    }
    return jdbc.query(
        "SELECT t.id, t.employee_id, t.status, t.current_revision, t.submission_cycle,"
            + " t.current_action_id, a.action, a.revision AS action_revision, a.cycle AS"
            + " action_cycle, a.generation_version, a.source_digest, t.row_count, t.worked_minutes"
            + " FROM people.timesheet t LEFT JOIN people.timesheet_action a ON a.tenant_id ="
            + " t.tenant_id AND a.id = t.current_action_id AND a.timesheet_id = t.id WHERE"
            + " t.tenant_id = :tenant AND t.generation_id = :generation",
        params,
        (rs, n) ->
            new Member(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("status"),
                rs.getInt("current_revision"),
                rs.getInt("submission_cycle"),
                rs.getObject("current_action_id", UUID.class),
                rs.getString("action"),
                rs.getInt("action_revision"),
                rs.getInt("action_cycle"),
                rs.getInt("generation_version"),
                rs.getString("source_digest"),
                rs.getInt("row_count"),
                rs.getInt("worked_minutes")));
  }

  /**
   * The generation's lock state.
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @param lock whether to lock the projection {@code FOR UPDATE}
   * @return the state ({@link LockState#NEVER} without a projection)
   */
  public LockState lockState(TenantId tenant, UUID generationId, boolean lock) {
    return jdbc
        .query(
            "SELECT k.state, k.version, k.current_action_id, k.updated_at"
                + " FROM people.timesheet_period_lock k"
                + " WHERE k.tenant_id = :tenant AND k.generation_id = :generation"
                + (lock ? " FOR UPDATE" : ""),
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("generation", generationId),
            (rs, n) ->
                new LockState(
                    State.valueOf(rs.getString("state")),
                    rs.getInt("version"),
                    rs.getObject("current_action_id", UUID.class),
                    rs.getTimestamp("updated_at").toInstant()))
        .stream()
        .findFirst()
        .orElse(LockState.NEVER);
  }

  /**
   * The lock states of several generations (the badges of lists).
   *
   * @param tenant verified tenant
   * @param generationIds generations
   * @return generation to state; absent generations are never locked
   */
  public Map<UUID, LockState> lockStates(TenantId tenant, Collection<UUID> generationIds) {
    Map<UUID, LockState> states = new HashMap<>();
    if (generationIds.isEmpty()) {
      return states;
    }
    jdbc.query(
        "SELECT k.generation_id, k.state, k.version, k.current_action_id, k.updated_at"
            + " FROM people.timesheet_period_lock k"
            + " WHERE k.tenant_id = :tenant AND k.generation_id IN (:generations)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generations", List.copyOf(generationIds)),
        rs -> {
          states.put(
              rs.getObject("generation_id", UUID.class),
              new LockState(
                  State.valueOf(rs.getString("state")),
                  rs.getInt("version"),
                  rs.getObject("current_action_id", UUID.class),
                  rs.getTimestamp("updated_at").toInstant()));
        });
    return states;
  }

  /**
   * Whether a shift starting at an instant lies in a locked week of its site (the correction
   * guard): the generation's zone decides the local date. Whatever the zone, that local date is
   * within a day of the UTC date, so the week's Monday lies between seven days before and one day
   * after it: the range narrows the site's locked weeks by their key before the exact comparison.
   *
   * @param tenant verified tenant
   * @param siteId the shift's site
   * @param shiftStart the shift's start
   * @return whether a LOCKED generation of the site covers it
   */
  public boolean lockedWeekCovers(TenantId tenant, UUID siteId, Instant shiftStart) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM people.timesheet_period_lock k JOIN people.timesheet_generation g"
                + " ON g.tenant_id = k.tenant_id AND g.id = k.generation_id WHERE k.tenant_id ="
                + " :tenant AND k.site_id = :site AND k.state = 'LOCKED' AND k.week_start BETWEEN"
                + " :from AND :to AND (CAST(:at AS timestamptz) AT TIME ZONE g.site_timezone)::date"
                + " >= g.week_start AND (CAST(:at AS timestamptz) AT TIME ZONE"
                + " g.site_timezone)::date < g.week_end",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("site", siteId)
                .addValue("at", Timestamp.from(shiftStart))
                .addValue("from", java.sql.Date.valueOf(utcDate(shiftStart).minusDays(7)))
                .addValue("to", java.sql.Date.valueOf(utcDate(shiftStart).plusDays(1))),
            Integer.class);
    return found != null && found > 0;
  }

  private static LocalDate utcDate(Instant at) {
    return at.atOffset(ZoneOffset.UTC).toLocalDate();
  }

  /**
   * The generation's period actions, newest first.
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @return the actions
   */
  public List<Action> history(TenantId tenant, UUID generationId) {
    return jdbc.query(
        "SELECT "
            + ACTION_COLUMNS
            + " FROM people.timesheet_period_action a WHERE a.tenant_id = :tenant"
            + " AND a.generation_id = :generation ORDER BY a.sequence DESC",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", generationId),
        (rs, n) -> action(rs));
  }

  /**
   * One period action.
   *
   * @param tenant verified tenant
   * @param id action
   * @return the action, or empty for an unknown or foreign one
   */
  public Optional<Action> action(TenantId tenant, UUID id) {
    return jdbc
        .query(
            "SELECT "
                + ACTION_COLUMNS
                + " FROM people.timesheet_period_action a WHERE a.tenant_id = :tenant"
                + " AND a.id = :id",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> action(rs))
        .stream()
        .findFirst();
  }

  /**
   * Appends a period action and moves the projection to it (the transition trigger proves the
   * sequence, the scope and, for a lock, the fully approved generation; the projection guard proves
   * the version; the deferred check proves both agree at commit).
   *
   * @param tenant verified tenant
   * @param action the action
   * @param actorSubject verified subject
   * @param firstLock whether this is the generation's first lock (no projection row yet)
   */
  public void append(TenantId tenant, Action action, String actorSubject, boolean firstLock) {
    Scope scope = action.scope();
    Period period = scope.period();
    Totals totals = action.totals();
    jdbc.update(
        "INSERT INTO people.timesheet_period_action (tenant_id, id, generation_id, sequence,"
            + " action, legal_entity_id, site_id, week_start, week_end, site_timezone,"
            + " period_start_at, period_end_at, generation_version, timesheet_count,"
            + " employee_count, row_count, worked_minutes, evidence_digest, locked_action_id,"
            + " unlock_reason, unlock_explanation, actor_subject, occurred_at)"
            + " VALUES (:tenant, :id, :generation, :sequence, :kind, :legalEntity, :site, :week,"
            + " :weekEnd, :zone, :from, :to, :generationVersion, :timesheets, :employees, :rows,"
            + " :minutes, :evidence, :locked, :reason, :explanation, :by, :at)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", action.id())
            .addValue("generation", action.generationId())
            .addValue("sequence", action.sequence())
            .addValue("kind", action.kind().name())
            .addValue("legalEntity", scope.legalEntityId())
            .addValue("site", period.siteId())
            .addValue("week", period.weekStart())
            .addValue("weekEnd", period.weekEnd())
            .addValue("zone", period.timezone())
            .addValue("from", Timestamp.from(period.startAt()))
            .addValue("to", Timestamp.from(period.endAt()))
            .addValue("generationVersion", scope.generationVersion())
            .addValue("timesheets", totals.timesheets())
            .addValue("employees", totals.employees())
            .addValue("rows", totals.rows())
            .addValue("minutes", totals.workedMinutes())
            .addValue("evidence", action.evidenceDigest())
            .addValue("locked", action.lockedActionId())
            .addValue("reason", action.unlockReason() == null ? null : action.unlockReason().name())
            .addValue("explanation", action.unlockExplanation())
            .addValue("by", actorSubject)
            .addValue("at", Timestamp.from(action.occurredAt())));
    MapSqlParameterSource projection =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", action.generationId())
            .addValue("site", period.siteId())
            .addValue("week", period.weekStart())
            .addValue("state", action.kind() == Kind.LOCKED ? "LOCKED" : "OPEN")
            .addValue("version", action.sequence())
            .addValue("action", action.id())
            .addValue("at", Timestamp.from(action.occurredAt()));
    if (firstLock) {
      jdbc.update(
          "INSERT INTO people.timesheet_period_lock (tenant_id, generation_id, site_id,"
              + " week_start, state, version, current_action_id, updated_at)"
              + " VALUES (:tenant, :generation, :site, :week, :state, :version, :action, :at)",
          projection);
    } else if (jdbc.update(
            "UPDATE people.timesheet_period_lock SET state = :state, version = :version,"
                + " current_action_id = :action, updated_at = :at"
                + " WHERE tenant_id = :tenant AND generation_id = :generation"
                + " AND version = :version - 1",
            projection)
        != 1) {
      throw new IllegalStateException("the period lock projection moved under the lock order");
    }
  }

  /**
   * Runs the deferred projection check now (inside the caller's transaction), so a violation is
   * mapped like any constraint instead of failing at commit.
   *
   * @param tenant verified tenant (the check is per generation)
   */
  public void checkConsistent(TenantId tenant) {
    jdbc.getJdbcTemplate()
        .execute(
            "SET CONSTRAINTS people.timesheet_period_lock_current,"
                + " people.timesheet_period_action_current IMMEDIATE");
  }

  private static Action action(ResultSet rs) throws SQLException {
    String reason = rs.getString("unlock_reason");
    return new Action(
        rs.getObject("id", UUID.class),
        rs.getObject("generation_id", UUID.class),
        rs.getInt("sequence"),
        Kind.valueOf(rs.getString("action")),
        new Scope(
            rs.getObject("legal_entity_id", UUID.class),
            new Period(
                rs.getObject("site_id", UUID.class),
                rs.getString("site_timezone"),
                rs.getObject("week_start", LocalDate.class),
                rs.getTimestamp("period_start_at").toInstant(),
                rs.getTimestamp("period_end_at").toInstant()),
            rs.getObject("generation_id", UUID.class),
            rs.getInt("generation_version")),
        new Totals(
            rs.getInt("timesheet_count"),
            rs.getInt("employee_count"),
            rs.getInt("row_count"),
            rs.getInt("worked_minutes")),
        rs.getString("evidence_digest"),
        rs.getObject("locked_action_id", UUID.class),
        reason == null ? null : UnlockReason.valueOf(reason),
        rs.getString("unlock_explanation"),
        rs.getTimestamp("occurred_at").toInstant());
  }
}
