package com.divalhr.core.people.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.schedule.domain.OperationalStatus.Organization;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Scope;
import com.divalhr.core.people.schedule.domain.OperationalStatus.Team;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.Statement;
import com.divalhr.core.people.schedule.internal.JdbcOperationalStatusRepository;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-100 (Issue #154, amendment E): the plans of every statement of the operational status over a
 * realistic tenant on the migrated database (3,000 employees on 30 sites and three legal entities,
 * 75 managers, 120 schedules of 480 slots over 120 days, about 35,000 attendance records, 9,000
 * leave requests, 12,000 timesheets, analyzed) among four other tenants of the same shape, for the
 * organization (unfiltered, by site, by legal entity, by both) and for a manager. Every statement
 * is driven by an existing index and never scans a schedule, slot, attendance, employment, leave or
 * timesheet table sequentially. No index is added.
 *
 * <p>The rows are written set-based with {@code session_replication_role = replica} (no triggers or
 * foreign keys; the plan depends on the indexes and statistics only).
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalStatusQueryPlanIntegrationTest {

  private static final int EMPLOYEES = 3_000;
  private static final int SITES = 30;
  private static final int MANAGERS = 75;
  private static final List<String> TABLES =
      List.of(
          "attendance_record",
          "work_schedule_slot",
          "employment_assignment",
          "employment ",
          "leave_request",
          "timesheet",
          "timesheet_action");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private LocalDate today;
  private UUID manager;
  private UUID legalEntity;
  private UUID site;

  /** One tenant's rows (the measured tenant, or one of the others around it). */
  private void seedTenant(UUID tenantId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE plan_people ON COMMIT DROP AS SELECT g,"
                      + " gen_random_uuid() AS employee, gen_random_uuid() AS employment,"
                      + " ((g - 1) % "
                      + SITES
                      + ") AS site_no FROM generate_series(1, "
                      + EMPLOYEES
                      + ") g");
              jdbc.execute(
                  "CREATE TEMP TABLE plan_sites ON COMMIT DROP AS SELECT s AS site_no,"
                      + " gen_random_uuid() AS site FROM generate_series(0, "
                      + (SITES - 1)
                      + ") s");
              jdbc.update(
                  "INSERT INTO people.employee (id, tenant_id, employee_number, given_names,"
                      + " family_name, created_at, created_by, search_key) SELECT employee, ?,"
                      + " 'PLAN' || lpad(g::text, 6, '0'), 'Plan', 'Employé', now(), 'sub-test',"
                      + " ' plan employe ' FROM plan_people",
                  tenantId);
              jdbc.update(
                  "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from,"
                      + " created_at, created_by) SELECT employment, ?, employee, ?, now(),"
                      + " 'sub-test' FROM plan_people",
                  tenantId,
                  today.minusDays(400));
              // The first 75 employees manage the others, 39 or 40 reports each.
              jdbc.update(
                  "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                      + " employment_id, kind, effective_from, manager_employee_id,"
                      + " created_by_change_id, origin_change_id) SELECT gen_random_uuid(), ?,"
                      + " r.employee, r.employment, 'MANAGER', ?, m.employee, gen_random_uuid(),"
                      + " gen_random_uuid() FROM plan_people r JOIN plan_people m"
                      + " ON m.g = 1 + (r.g % "
                      + MANAGERS
                      + ") WHERE r.g > "
                      + MANAGERS,
                  tenantId,
                  today.minusDays(400));
              // Per site four consecutive 30-day schedules (90 days back to 30 days ahead).
              jdbc.update(
                  "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
                      + " site_timezone, slot_count, assigned_count, published_at, published_by)"
                      + " SELECT gen_random_uuid(), ?, s.site, ?::date + 30 * k, ?::date + 30 * k"
                      + " + 29, 'Africa/Kinshasa', 480, 480, now(), 'sub-test'"
                      + " FROM plan_sites s CROSS JOIN generate_series(0, 3) k",
                  tenantId,
                  today.minusDays(90),
                  today.minusDays(90));
              // 16 slots a day per schedule, each employee of the site at most once a day.
              jdbc.update(
                  "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position,"
                      + " start_local, end_local, start_at, end_at, label_en, label_fr,"
                      + " assigned_employee_id) SELECT gen_random_uuid(), w.tenant_id, w.id, i * 16"
                      + " + j + 1, (w.first_date + i) + time '08:00', (w.first_date + i) + time"
                      + " '16:00', ((w.first_date + i) + time '08:00') AT TIME ZONE"
                      + " 'Africa/Kinshasa', ((w.first_date + i) + time '16:00') AT TIME ZONE"
                      + " 'Africa/Kinshasa', 'Cashier', 'Caissière', p.employee FROM"
                      + " people.work_schedule w JOIN plan_sites s ON s.site = w.site_id CROSS JOIN"
                      + " generate_series(0, 29) i CROSS JOIN generate_series(0, 15) j JOIN"
                      + " plan_people p ON p.site_no = s.site_no AND (p.g - 1) / "
                      + SITES
                      + " = (j + 16 * (i % 6)) % 100 WHERE w.tenant_id = ?",
                  tenantId);
              // Four in five ended slots have a record: varied lateness, some still open.
              jdbc.update(
                  "INSERT INTO people.attendance_record (tenant_id, id, schedule_id, slot_id,"
                      + " employee_id, employment_id, slot_start_at, slot_end_at, check_out_until,"
                      + " check_in_event_id, checked_in_at, check_out_event_id, checked_out_at)"
                      + " SELECT l.tenant_id, gen_random_uuid(), l.schedule_id, l.id,"
                      + " l.assigned_employee_id, p.employment, l.start_at, l.end_at,"
                      + " l.end_at + interval '24 hours', gen_random_uuid(),"
                      + " l.start_at + make_interval(mins => (l.position % 13)),"
                      + " CASE WHEN l.position % 9 = 0 THEN NULL ELSE gen_random_uuid() END,"
                      + " CASE WHEN l.position % 9 = 0 THEN NULL"
                      + " ELSE l.end_at - make_interval(mins => (l.position % 11)) END"
                      + " FROM people.work_schedule_slot l JOIN plan_people p"
                      + " ON p.employee = l.assigned_employee_id"
                      + " WHERE l.tenant_id = ? AND l.end_at < now() AND l.position % 5 <> 0",
                  tenantId);
              jdbc.update(
                  "INSERT INTO people.work_schedule_cancellation (tenant_id, schedule_id, reason,"
                      + " cancelled_at, cancelled_by) SELECT tenant_id, id, 'Plan', now(),"
                      + " 'sub-test' FROM people.work_schedule WHERE tenant_id = ?"
                      + " AND first_date > ? ORDER BY id LIMIT 5",
                  tenantId,
                  today);
              // Placements: three legal entities, each employee on the site of their number.
              jdbc.execute(
                  "CREATE TEMP TABLE plan_entities ON COMMIT DROP AS SELECT e AS entity_no,"
                      + " gen_random_uuid() AS legal_entity FROM generate_series(0, 2) e");
              jdbc.update(
                  "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                      + " employment_id, kind, effective_from, legal_entity_id, site_id,"
                      + " created_by_change_id, origin_change_id) SELECT gen_random_uuid(), ?,"
                      + " p.employee, p.employment, 'PLACEMENT', ?, e.legal_entity, s.site,"
                      + " gen_random_uuid(), gen_random_uuid() FROM plan_people p"
                      + " JOIN plan_sites s ON s.site_no = p.site_no"
                      + " JOIN plan_entities e ON e.entity_no = p.site_no % 3",
                  tenantId, today.minusDays(400));
              jdbc.update(
                  "INSERT INTO people.leave_policy (id, tenant_id, code, created_at, created_by)"
                      + " SELECT gen_random_uuid(), ?, 'PLAN', now(), 'sub-test'",
                  tenantId);
              jdbc.update(
                  "INSERT INTO people.leave_policy_version (id, tenant_id, policy_id,"
                      + " version_number, name_en, name_fr, unit, balance_mode,"
                      + " minimum_service_days, approval_route, payroll_effect, effective_from,"
                      + " created_at, created_by) SELECT gen_random_uuid(), tenant_id, id, 1,"
                      + " 'Plan', 'Plan', 'DAYS', 'UNTRACKED', 0, 'MANAGER', 'PAID', ?, now(),"
                      + " 'sub-test' FROM people.leave_policy WHERE tenant_id = ? AND code ="
                      + " 'PLAN'",
                  today.minusDays(400),
                  tenantId);
              // Every employee has two decided requests; one in twenty also has a pending one.
              jdbc.update(
                  "INSERT INTO people.leave_request (id, tenant_id, employee_id, employment_id,"
                      + " policy_version_id, start_date, end_date, requested_amount, state,"
                      + " submitted_at, submitted_by) SELECT gen_random_uuid(), ?, p.employee,"
                      + " p.employment, v.id, ?::date + k, ?::date + k, 1,"
                      + " CASE WHEN k = 2 THEN 'PENDING' ELSE 'APPROVED' END,"
                      + " now() - make_interval(hours => (p.g % 120)), 'sub-test'"
                      + " FROM plan_people p CROSS JOIN generate_series(0, 2) k"
                      + " JOIN people.leave_policy_version v ON v.tenant_id = ?"
                      + " WHERE k < 2 OR p.g % 20 = 0",
                  tenantId, today.minusDays(60), today.minusDays(60), tenantId);
              // A timesheet a week for four weeks per employee; one in twenty is submitted.
              jdbc.update(
                  "INSERT INTO people.timesheet (tenant_id, id, generation_id, employee_id,"
                      + " site_id, week_start, status, current_revision, row_count, ready_rows,"
                      + " missing_rows, open_rows, changed_rows, worked_minutes, created_at,"
                      + " updated_at, submission_cycle, current_action_id) SELECT ?,"
                      + " gen_random_uuid(), gen_random_uuid(), p.employee, s.site,"
                      + " ?::date + 7 * k, CASE WHEN p.g % 20 = 0 AND k = 3 THEN 'SUBMITTED'"
                      + " ELSE 'APPROVED' END, 1, 5, 5, 0, 0, 0, 2400, now(), now(), 1,"
                      + " gen_random_uuid() FROM plan_people p"
                      + " JOIN plan_sites s ON s.site_no = p.site_no"
                      + " CROSS JOIN generate_series(0, 3) k",
                  tenantId, today.minusDays(40));
              jdbc.update(
                  "INSERT INTO people.timesheet_action (tenant_id, id, timesheet_id, sequence,"
                      + " action, cycle, revision, generation_version, source_digest, row_count,"
                      + " ready_rows, missing_rows, open_rows, changed_rows, worked_minutes,"
                      + " employee_id, site_id, employment_id, manager_employee_id, actor_subject,"
                      + " occurred_at) SELECT t.tenant_id, t.current_action_id, t.id, 1,"
                      + " 'SUBMITTED', 1, 1, 1, repeat('a', 64), 5, 5, 0, 0, 0, 2400,"
                      + " t.employee_id, t.site_id, p.employment, m.employee, 'sub-test', now() -"
                      + " make_interval(hours => (p.g % 150)) FROM people.timesheet t JOIN"
                      + " plan_people p ON p.employee = t.employee_id JOIN plan_people m ON m.g = 1"
                      + " + (p.g % "
                      + MANAGERS
                      + ") WHERE t.tenant_id = ?",
                  tenantId);
            });
  }

  @BeforeAll
  void seed() {
    today = LocalDate.now(ZoneOffset.UTC);
    // Four other tenants of the same shape: the measured tenant is one fifth of the data.
    for (int other = 0; other < 4; other++) {
      seedTenant(UUID.randomUUID());
    }
    seedTenant(tenant);
    for (String table :
        List.of(
            "people.employee",
            "people.employment",
            "people.employment_assignment",
            "people.work_schedule",
            "people.work_schedule_slot",
            "people.attendance_record",
            "people.work_schedule_cancellation",
            "people.leave_request",
            "people.timesheet",
            "people.timesheet_action")) {
      jdbc.execute("ANALYZE " + table);
    }
    manager =
        jdbc.queryForObject(
            "SELECT id FROM people.employee WHERE tenant_id = ? AND employee_number = 'PLAN000002'",
            UUID.class,
            tenant);
    legalEntity =
        jdbc.queryForObject(
            "SELECT legal_entity_id FROM people.employment_assignment WHERE tenant_id = ?"
                + " AND kind = 'PLACEMENT' LIMIT 1",
            UUID.class,
            tenant);
    site =
        jdbc.queryForObject(
            "SELECT site_id FROM people.work_schedule WHERE tenant_id = ? LIMIT 1",
            UUID.class,
            tenant);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.attendance_record WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isGreaterThan(30_000L);
  }

  private String plan(Statement statement) {
    List<String> lines =
        named.query("EXPLAIN " + statement.sql(), statement.params(), (rs, n) -> rs.getString(1));
    return String.join("\n", lines);
  }

  private void bounded(String label, Scope scope) {
    List<Statement> statements =
        JdbcOperationalStatusRepository.statements(new TenantId(tenant), scope, Instant.now());
    assertThat(statements).isNotEmpty();
    for (Statement statement : statements) {
      String plan = plan(statement);
      for (String table : TABLES) {
        assertThat(plan).as(label + "\n" + plan).doesNotContain("Seq Scan on " + table);
      }
    }
  }

  @Test
  void everyStatementOfEveryScopeAndFilterIsDrivenByAnExistingIndex() {
    bounded("organization", new Organization(null, null, Set.of()));
    bounded("site", new Organization(site, null, Set.of()));
    // The legal entity's sites, as the service resolves them for the uncovered slots.
    Set<UUID> sites =
        Set.copyOf(
            jdbc.queryForList(
                "SELECT DISTINCT site_id FROM people.employment_assignment WHERE tenant_id = ?"
                    + " AND kind = 'PLACEMENT' AND legal_entity_id = ?",
                UUID.class,
                tenant,
                legalEntity));
    bounded("legal entity", new Organization(null, legalEntity, sites));
    bounded("site and legal entity", new Organization(site, legalEntity, sites));
    bounded("manager", new Team(manager));
  }
}
