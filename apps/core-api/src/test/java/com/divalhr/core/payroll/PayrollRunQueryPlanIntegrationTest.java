package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * MVP-094A (Issue #177, D94A-12): the plans of the run history, the run detail, the draft lookup,
 * the compensation facts and the population statements over a realistic tenant on the migrated
 * database (3,000 employees with a placement and a compensation, 48 chains of five versions and a
 * run of 3,000 lines, analyzed) among four other tenants of the same shape. Each path is driven by
 * an existing index and never scans the run, line, compensation or assignment tables sequentially.
 * No index is added.
 *
 * <p>The statements mirror the ones of {@code JdbcPayrollRunRepository} and {@code
 * JdbcEmploymentHistoryRepository} (the same text and parameters). The rows are written set-based
 * with {@code session_replication_role = replica} (no triggers or foreign keys; the plan depends on
 * the indexes and statistics only).
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayrollRunQueryPlanIntegrationTest {

  private static final int EMPLOYEES = 3_000;
  private static final String RUN_COLUMNS =
      "tenant_id, legal_entity_id, id, period_start, currency, version, status, policy_id,"
          + " policy_sequence, pay_day_kind, pay_day, proration_method, employee_count,"
          + " total_gross_minor, input_digest, created_at, created_by, superseded_at,"
          + " superseded_by_run_id";
  private static final String LINE_COLUMNS =
      "id, employee_id, employee_number, eligible_days, denominator, full_month, monthly_minor,"
          + " gross_minor, explanation_codes";
  private static final List<String> TABLES =
      List.of(
          "payroll.pay_run ",
          "payroll.pay_run_line ",
          "payroll.compensation ",
          "people.employment_assignment ");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private final UUID entity = UUID.randomUUID();
  private LocalDate month;
  private UUID run;
  private UUID line;
  private String ids;

  private void seedTenant(UUID tenantId, UUID entityId, boolean measured) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE plan_people ON COMMIT DROP AS SELECT g,"
                      + " gen_random_uuid() AS employee, gen_random_uuid() AS employment,"
                      + " gen_random_uuid() AS change_id FROM generate_series(1, "
                      + EMPLOYEES
                      + ") g");
              jdbc.update(
                  "INSERT INTO people.employee (id, tenant_id, employee_number, given_names,"
                      + " family_name, created_at, created_by, search_key) SELECT employee, ?,"
                      + " 'PLAN' || lpad(g::text, 6, '0'), 'Plan', 'Employe', now(), 'sub-test',"
                      + " ' plan employe ' FROM plan_people",
                  tenantId);
              jdbc.update(
                  "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from,"
                      + " created_at, created_by) SELECT employment, ?, employee, ?, now(),"
                      + " 'sub-test' FROM plan_people",
                  tenantId,
                  month.minusDays(400));
              jdbc.update(
                  "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                      + " employment_id, kind, effective_from, legal_entity_id, site_id,"
                      + " created_by_change_id, origin_change_id) SELECT gen_random_uuid(), ?,"
                      + " employee, employment, 'PLACEMENT', ?, ?, gen_random_uuid(), change_id,"
                      + " change_id"
                      + " FROM plan_people",
                  tenantId,
                  month.minusDays(400),
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.compensation (tenant_id, employee_id, id, origin_change_id,"
                      + " created_seq, frequency, currency, exponent, amount_minor, effective_from)"
                      + " SELECT ?, employee, gen_random_uuid(), change_id, 1, 'MONTHLY', 'USD',"
                      + " 2, 100000 + g, ? FROM plan_people",
                  tenantId,
                  month.minusDays(400));
              // 24 months of two currencies, five versions each; the latest is the DRAFT.
              jdbc.update(
                  "INSERT INTO payroll.pay_run (tenant_id, legal_entity_id, id, period_start,"
                      + " currency, version, status, policy_id, policy_sequence, pay_day_kind,"
                      + " pay_day, proration_method, employee_count, total_gross_minor,"
                      + " input_digest, created_at, created_by, superseded_at,"
                      + " superseded_by_run_id) SELECT ?, ?, gen_random_uuid(),"
                      + " (date_trunc('month', ?::date) - make_interval(months => m))::date, c, v,"
                      + " CASE WHEN v = 5 THEN 'DRAFT' ELSE 'SUPERSEDED' END, gen_random_uuid(),"
                      + " 1, 'LAST_DAY', NULL, 'CALENDAR_DAYS', "
                      + EMPLOYEES
                      + ", 100000, repeat('a', 64), now(), 'sub-test',"
                      + " CASE WHEN v = 5 THEN NULL ELSE now() END,"
                      + " CASE WHEN v = 5 THEN NULL ELSE gen_random_uuid() END"
                      + " FROM generate_series(0, 23) m CROSS JOIN (VALUES ('CDF'), ('USD')) AS cur(c)"
                      + " CROSS JOIN generate_series(1, 5) v",
                  tenantId,
                  entityId,
                  month);
              jdbc.update(
                  "INSERT INTO payroll.pay_run_line (tenant_id, run_id, id, employee_id,"
                      + " employee_number, eligible_days, denominator, full_month, monthly_minor,"
                      + " gross_minor, explanation_codes) SELECT ?, r.id, gen_random_uuid(),"
                      + " p.employee, 'PLAN' || lpad(p.g::text, 6, '0'), 30, NULL, true,"
                      + " 100000 + p.g, 100000 + p.g, ARRAY['FULL_MONTH']"
                      + " FROM (SELECT id FROM payroll.pay_run WHERE tenant_id = ?"
                      + " AND legal_entity_id = ? AND period_start = ?::date AND currency = 'USD'"
                      + " AND version = 5) r CROSS JOIN plan_people p",
                  tenantId,
                  tenantId,
                  entityId,
                  month);
            });
  }

  @BeforeAll
  void seed() {
    month = LocalDate.now().withDayOfMonth(1).minusMonths(1);
    for (int other = 0; other < 4; other++) {
      seedTenant(UUID.randomUUID(), UUID.randomUUID(), false);
    }
    seedTenant(tenant, entity, true);
    for (String table :
        List.of(
            "people.employee",
            "people.employment",
            "people.employment_assignment",
            "payroll.compensation",
            "payroll.pay_run",
            "payroll.pay_run_line")) {
      jdbc.execute("ANALYZE " + table);
    }
    run =
        jdbc.queryForObject(
            "SELECT id FROM payroll.pay_run WHERE tenant_id = ? AND legal_entity_id = ?"
                + " AND period_start = ? AND currency = 'USD' AND version = 5",
            UUID.class,
            tenant,
            entity,
            month);
    line =
        jdbc.queryForObject(
            "SELECT id FROM payroll.pay_run_line WHERE tenant_id = ? AND run_id = ?"
                + " AND employee_number = 'PLAN001500'",
            UUID.class,
            tenant,
            run);
    List<UUID> employees =
        jdbc.queryForList(
            "SELECT id FROM people.employee WHERE tenant_id = ? ORDER BY employee_number LIMIT 500",
            UUID.class,
            tenant);
    ids = "{" + String.join(",", employees.stream().map(UUID::toString).toList()) + "}";
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.pay_run_line WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo(EMPLOYEES);
  }

  private String plan(String sql, Map<String, Object> params) {
    return String.join("\n", named.query("EXPLAIN " + sql, params, (rs, row) -> rs.getString(1)));
  }

  private void driven(String label, String sql, Map<String, Object> params, String... indexes) {
    String plan = plan(sql, params);
    for (String index : indexes) {
      assertThat(plan).as(label + "\n" + plan).contains(index);
    }
    for (String table : TABLES) {
      assertThat(plan).as(label + "\n" + plan).doesNotContain("Seq Scan on " + table);
    }
  }

  @Test
  void theRunHistoryIsDrivenByTheHistoryIndexWithoutASort() {
    String first =
        "SELECT "
            + RUN_COLUMNS
            + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
            + " ORDER BY period_start DESC, currency, version DESC, id LIMIT :limit";
    Map<String, Object> params = Map.of("tenant", tenant, "entity", entity, "limit", 26);
    driven("first page", first, params, "payroll_pay_run_history");
    assertThat(plan(first, params)).doesNotContain("Sort");

    String keyset =
        "SELECT "
            + RUN_COLUMNS
            + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
            + " AND (period_start < :period"
            + " OR (period_start = :period AND currency > :currency)"
            + " OR (period_start = :period AND currency = :currency AND version < :version)"
            + " OR (period_start = :period AND currency = :currency AND version = :version"
            + " AND id > :id))"
            + " ORDER BY period_start DESC, currency, version DESC, id LIMIT :limit";
    driven(
        "keyset",
        keyset,
        Map.of(
            "tenant",
            tenant,
            "entity",
            entity,
            "period",
            month.minusMonths(6),
            "currency",
            "CDF",
            "version",
            3,
            "id",
            UUID.randomUUID(),
            "limit",
            26),
        "payroll_pay_run_history");
  }

  @Test
  void theDraftAndTheRunByIdAreDrivenByTheirKeys() {
    driven(
        "draft",
        "SELECT "
            + RUN_COLUMNS
            + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
            + " AND period_start = :period AND currency = :currency AND status = 'DRAFT'",
        Map.of("tenant", tenant, "entity", entity, "period", month, "currency", "USD"),
        "payroll_pay_run_");
    driven(
        "run by id",
        "SELECT "
            + RUN_COLUMNS
            + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
            + " AND id = :id",
        Map.of("tenant", tenant, "entity", entity, "id", run),
        "payroll_pay_run_pkey");
  }

  @Test
  void theRunDetailPagesInIndexOrderWithoutASort() {
    String first =
        "SELECT "
            + LINE_COLUMNS
            + " FROM payroll.pay_run_line WHERE tenant_id = :tenant AND run_id = :run"
            + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit";
    Map<String, Object> params = Map.of("tenant", tenant, "run", run, "limit", 51);
    driven("first page", first, params, "payroll_pay_run_line_page");
    assertThat(plan(first, params)).doesNotContain("Sort");

    String keyset =
        "SELECT "
            + LINE_COLUMNS
            + " FROM payroll.pay_run_line WHERE tenant_id = :tenant AND run_id = :run"
            + " AND ((employee_number COLLATE \"C\"), id) > (CAST(:number AS text) COLLATE"
            + " \"C\", CAST(:after AS uuid))"
            + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit";
    Map<String, Object> next =
        Map.of("tenant", tenant, "run", run, "number", "PLAN001500", "after", line, "limit", 51);
    driven("keyset", keyset, next, "payroll_pay_run_line_page");
    assertThat(plan(keyset, next)).doesNotContain("Sort");
    driven(
        "line by id",
        "SELECT "
            + LINE_COLUMNS
            + " FROM payroll.pay_run_line WHERE tenant_id = :tenant AND run_id = :run"
            + " AND id = :id",
        Map.of("tenant", tenant, "run", run, "id", line),
        "payroll_pay_run_line_pkey");
  }

  @Test
  void theCompensationFactsAndThePlacementsAreDrivenByTheirIndexes() {
    driven(
        "facts",
        "SELECT employee_id, id, origin_change_id, created_seq, currency, amount_minor,"
            + " effective_from, effective_to FROM payroll.compensation"
            + " WHERE tenant_id = :tenant AND employee_id = ANY(CAST(:ids AS uuid[]))"
            + " AND superseded_seq IS NULL AND effective_from <= :to"
            + " AND (effective_to IS NULL OR effective_to >= :from)"
            + " ORDER BY employee_id, effective_from",
        Map.of("tenant", tenant, "ids", ids, "from", month, "to", month.plusMonths(1).minusDays(1)),
        "payroll_compensation_active");
    driven(
        "placements",
        "SELECT id, employee_id, legal_entity_id, effective_from, effective_to"
            + " FROM people.employment_assignment WHERE tenant_id = :tenant"
            + " AND employee_id = ANY(CAST(:ids AS uuid[])) AND kind = 'PLACEMENT'"
            + " AND superseded_by_change_id IS NULL AND legal_entity_id IS NOT NULL"
            + " AND effective_from <= :to AND (effective_to IS NULL OR effective_to >= :from)"
            + " ORDER BY effective_from, id",
        Map.of("tenant", tenant, "ids", ids, "from", month, "to", month.plusMonths(1).minusDays(1)),
        "employment_assignment_");
  }

  @Test
  void thePopulationIsBoundedAndNeverTouchesTheRunTables() {
    String plan =
        plan(
            "SELECT e.id, e.employee_number FROM people.employee e WHERE e.tenant_id = :tenant"
                + " AND EXISTS (SELECT 1 FROM people.employment m"
                + " JOIN people.employment_assignment a ON a.tenant_id = m.tenant_id"
                + " AND a.employment_id = m.id AND a.employee_id = m.employee_id"
                + " WHERE m.tenant_id = e.tenant_id AND m.employee_id = e.id"
                + " AND m.effective_from <= :to AND (m.effective_to IS NULL OR m.effective_to >="
                + " :from)"
                + " AND a.kind = 'PLACEMENT' AND a.superseded_by_change_id IS NULL"
                + " AND a.legal_entity_id = :entity"
                + " AND a.effective_from <= :to AND (a.effective_to IS NULL OR a.effective_to >="
                + " :from)"
                + " AND (m.effective_to IS NULL OR a.effective_from <= m.effective_to)"
                + " AND (a.effective_to IS NULL OR a.effective_to >= m.effective_from))"
                + " ORDER BY e.employee_number COLLATE \"C\", e.id LIMIT :limit",
            Map.of(
                "tenant",
                tenant,
                "entity",
                entity,
                "from",
                month,
                "to",
                month.plusMonths(1).minusDays(1),
                "limit",
                5001));
    // The statement reads one tenant's employees (at most 5,000 are returned, the run is refused
    // beyond) and joins them to their placements; it never reads a payroll table.
    assertThat(plan).contains("Limit").doesNotContain("payroll.");
  }
}
