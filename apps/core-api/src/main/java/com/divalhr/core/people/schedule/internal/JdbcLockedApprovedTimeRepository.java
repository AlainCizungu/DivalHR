package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.platform.access.LockedApprovedTime.ApprovedRow;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reads behind the payroll module's locked-approved-time port (MVP-090A, ADR 0023): the
 * generated and the scheduled weeks of a set of sites, and the approved rows of a generation with
 * the legal entity their employment is placed in. Identifiers, dates and minutes only.
 */
@Repository
public class JdbcLockedApprovedTimeRepository {

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter template
   */
  public JdbcLockedApprovedTimeRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A generated week of a site.
   *
   * @param id generation
   * @param siteId site
   * @param weekStart site-local Monday
   * @param timezone the site's zone as generated
   * @param version the generation version
   * @param periodStartAt exact start
   * @param periodEndAt exact end (exclusive)
   */
  public record GeneratedWeek(
      UUID id,
      UUID siteId,
      LocalDate weekStart,
      String timezone,
      int version,
      Instant periodStartAt,
      Instant periodEndAt) {}

  /**
   * A site-local week holding at least one operational published shift.
   *
   * @param siteId site
   * @param weekStart site-local Monday
   * @param timezone the schedule's zone
   */
  public record ScheduledWeek(UUID siteId, LocalDate weekStart, String timezone) {}

  /**
   * The generations of the sites whose week starts between two Mondays (inclusive).
   *
   * @param tenant verified tenant
   * @param siteIds sites
   * @param firstMonday first Monday
   * @param lastMonday last Monday
   * @return the generations, by site then week
   */
  public List<GeneratedWeek> generations(
      TenantId tenant, Collection<UUID> siteIds, LocalDate firstMonday, LocalDate lastMonday) {
    if (siteIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT g.id, g.site_id, g.week_start, g.site_timezone, g.version, g.period_start_at,"
            + " g.period_end_at FROM people.timesheet_generation g WHERE g.tenant_id = :tenant"
            + " AND g.site_id = ANY(:sites) AND g.week_start BETWEEN :first AND :last"
            + " ORDER BY g.site_id, g.week_start",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("sites", siteIds.toArray(UUID[]::new))
            .addValue("first", firstMonday)
            .addValue("last", lastMonday),
        (rs, n) ->
            new GeneratedWeek(
                rs.getObject("id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getObject("week_start", LocalDate.class),
                rs.getString("site_timezone"),
                rs.getInt("version"),
                rs.getTimestamp("period_start_at").toInstant(),
                rs.getTimestamp("period_end_at").toInstant()));
  }

  /**
   * The site-local weeks of the sites that hold an operational published shift (not cancelled, not
   * replaced) starting between two Mondays (the second one exclusive of its following Monday).
   *
   * @param tenant verified tenant
   * @param siteIds sites
   * @param firstMonday first Monday
   * @param lastMonday last Monday
   * @return the weeks, by site then week
   */
  public List<ScheduledWeek> scheduledWeeks(
      TenantId tenant, Collection<UUID> siteIds, LocalDate firstMonday, LocalDate lastMonday) {
    if (siteIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT s.site_id, w.monday, min(s.site_timezone) AS site_timezone FROM"
            + " people.work_schedule_slot l JOIN people.work_schedule s ON s.tenant_id ="
            + " l.tenant_id AND s.id = l.schedule_id CROSS JOIN LATERAL (SELECT"
            + " (date_trunc('week', (l.start_at AT TIME ZONE s.site_timezone)::date::timestamp))"
            + "::date AS monday) w WHERE l.tenant_id = :tenant AND s.site_id = ANY(:sites)"
            + " AND w.monday BETWEEN :first AND :last"
            + " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_cancellation c WHERE"
            + " c.tenant_id = s.tenant_id AND c.schedule_id = s.id)"
            + " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_replacement r WHERE"
            + " r.tenant_id = s.tenant_id AND r.schedule_id = s.id)"
            + " GROUP BY s.site_id, w.monday ORDER BY s.site_id, w.monday",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("sites", siteIds.toArray(UUID[]::new))
            .addValue("first", firstMonday)
            .addValue("last", lastMonday),
        (rs, n) ->
            new ScheduledWeek(
                rs.getObject("site_id", UUID.class),
                rs.getObject("monday", LocalDate.class),
                rs.getString("site_timezone")));
  }

  /**
   * The approved rows of a generation now (the export's eligibility, T70E-1) with the legal entity
   * the row's employment is placed in on the work date (the active PLACEMENT assignment covering
   * that date), or none.
   *
   * @param tenant verified tenant
   * @param generationId generation
   * @return the rows, in no particular order
   */
  public List<ApprovedRow> approvedRows(TenantId tenant, UUID generationId) {
    return jdbc.query(
        "SELECT t.id AS timesheet_id, t.current_revision, a.id AS action_id, a.cycle,"
            + " t.employee_id, r.employment_id, r.cost_center_id,"
            + " (r.scheduled_start_at AT TIME ZONE g.site_timezone)::date AS work_date,"
            + " r.slot_id, r.worked_minutes,"
            + " (SELECT p.legal_entity_id FROM people.employment_assignment p WHERE p.tenant_id ="
            + " r.tenant_id AND p.employment_id = r.employment_id AND p.kind = 'PLACEMENT'"
            + " AND p.superseded_by_change_id IS NULL AND p.effective_from <="
            + " (r.scheduled_start_at AT TIME ZONE g.site_timezone)::date AND (p.effective_to IS"
            + " NULL OR p.effective_to >= (r.scheduled_start_at AT TIME ZONE"
            + " g.site_timezone)::date) ORDER BY p.effective_from DESC LIMIT 1) AS placed_in"
            + " FROM people.timesheet t"
            + " JOIN people.timesheet_generation g"
            + " ON g.tenant_id = t.tenant_id AND g.id = t.generation_id"
            + " JOIN people.timesheet_action a"
            + " ON a.tenant_id = t.tenant_id AND a.id = t.current_action_id"
            + " AND a.timesheet_id = t.id AND a.action = 'APPROVED'"
            + " AND a.revision = t.current_revision AND a.cycle = t.submission_cycle"
            + " JOIN people.timesheet_row r"
            + " ON r.tenant_id = t.tenant_id AND r.timesheet_id = t.id"
            + " AND r.revision = t.current_revision AND r.state = 'READY'"
            + " WHERE t.tenant_id = :tenant AND t.generation_id = :generation"
            + " AND t.status = 'APPROVED'",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("generation", generationId),
        (rs, n) ->
            new ApprovedRow(
                rs.getObject("timesheet_id", UUID.class),
                rs.getInt("current_revision"),
                rs.getObject("action_id", UUID.class),
                rs.getInt("cycle"),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("employment_id", UUID.class),
                rs.getObject("placed_in", UUID.class),
                rs.getObject("cost_center_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getObject("slot_id", UUID.class),
                rs.getInt("worked_minutes")));
  }
}
