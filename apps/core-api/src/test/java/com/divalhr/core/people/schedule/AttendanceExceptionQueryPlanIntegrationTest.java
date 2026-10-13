package com.divalhr.core.people.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Filter;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Position;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Scope;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Self;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Team;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Tenant;
import com.divalhr.core.people.schedule.domain.AttendanceExceptions.Type;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository;
import com.divalhr.core.people.schedule.internal.JdbcAttendanceExceptionRepository.Statement;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * MVP-060C (C60C-6, C60C-8 item 12): the plans of the three list paths' real statements over a
 * realistic tenant on the migrated database (3,000 employees on 30 sites, 75 managers, 120
 * schedules of 480 slots over 120 days, about 35,000 attendance records, analyzed) among four other
 * tenants of the same shape, with their primary filters (no dates, explicit 31 and 93 dates, a
 * type, a site, a keyset position). Each path is driven by an existing index (the employee's
 * records and slots, the manager's reports, the tenant's schedules by date) and never scans a
 * schedule, slot, attendance or employment table sequentially. No index is added.
 *
 * <p>The rows are written set-based with {@code session_replication_role = replica} (no triggers or
 * foreign keys; the plan depends on the indexes and statistics only).
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttendanceExceptionQueryPlanIntegrationTest {

  private static final int EMPLOYEES = 3_000;
  private static final int SITES = 30;
  private static final int MANAGERS = 75;
  private static final List<String> TABLES =
      List.of("attendance_record", "work_schedule_slot", "employment_assignment", "employment ");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private LocalDate today;
  private UUID employee;
  private UUID manager;
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
            "people.work_schedule_cancellation")) {
      jdbc.execute("ANALYZE " + table);
    }
    employee =
        jdbc.queryForObject(
            "SELECT id FROM people.employee WHERE tenant_id = ? AND employee_number = 'PLAN000100'",
            UUID.class,
            tenant);
    manager =
        jdbc.queryForObject(
            "SELECT id FROM people.employee WHERE tenant_id = ? AND employee_number = 'PLAN000002'",
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

  private void driven(Scope scope, Filter filter, Position after, String... indexes) {
    String plan =
        plan(
            JdbcAttendanceExceptionRepository.statement(
                new TenantId(tenant), scope, filter, Instant.now(), after, 21));
    for (String index : indexes) {
      assertThat(plan).as(scope + " " + filter + "\n" + plan).contains(index);
    }
    for (String table : TABLES) {
      assertThat(plan)
          .as(scope + " " + filter + "\n" + plan)
          .doesNotContain("Seq Scan on " + table);
    }
  }

  @Test
  void everyPathAndPrimaryFilterIsDrivenByAnExistingIndex() {
    Position after =
        new Position(
            Instant.now().minusSeconds(86_400L * 20),
            UUID.randomUUID(),
            employee,
            Type.LATE_CHECK_IN);
    List<Filter> filters = new ArrayList<>();
    filters.add(new Filter(null, null, null));
    filters.add(new Filter(null, today.minusDays(30), today));
    filters.add(new Filter(null, today.minusDays(92), today));
    filters.add(new Filter(Type.LATE_CHECK_IN, null, null));
    for (Filter filter : filters) {
      for (Position position : Arrays.asList(null, after)) {
        driven(
            new Self(employee),
            filter,
            position,
            filter.type() == null
                ? new String[] {"attendance_record_history", "work_schedule_slot_employee_start"}
                : new String[] {"attendance_record_history"});
        driven(new Team(manager), filter, position, "employment_assignment_reports");
        driven(
            new Tenant(null),
            filter,
            position,
            "work_schedule_slot_position",
            "attendance_record_slot_employee_unique");
        driven(new Tenant(site), filter, position, "work_schedule_slot_position");
      }
    }
    driven(
        new Self(employee),
        new Filter(Type.MISSING_CHECK_IN, null, null),
        null,
        "work_schedule_slot_employee_start");
    driven(
        new Team(manager),
        new Filter(Type.MISSING_CHECK_IN, null, null),
        null,
        "employment_assignment_reports");
  }
}
