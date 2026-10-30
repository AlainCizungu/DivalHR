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
 * MVP-094B (Issue #179, ADR 0034 section 9): the plans of the activation timeline, the profile
 * timelines, the frozen source lines, the calculation chain, the calculation history and the
 * calculation detail over a realistic tenant on the migrated database (2,000 employees with two
 * profile entries, 60 activations, a run of 2,000 lines, a chain of 25 calculation versions with
 * 2,000 lines of five components, analyzed) among four other tenants of the same shape. Each path
 * is driven by an existing index and never scans a tenant table sequentially. No index is added.
 *
 * <p>The platform rule pack list has no dedicated index (the table holds a handful of rows and is
 * not tenant data); its plan is only asserted to be bounded by the limit.
 *
 * <p>The statements mirror the ones of {@code JdbcStatutoryRepository} (the same predicates, orders
 * and parameters; the column lists are shortened). The rows are written set-based with {@code
 * session_replication_role = replica} (no triggers or foreign keys; the plan depends on the indexes
 * and statistics only).
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatutoryQueryPlanIntegrationTest {

  private static final int EMPLOYEES = 2_000;
  private static final String PACK = "4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94";
  private static final List<String> TABLES =
      List.of(
          "payroll.statutory_activation ",
          "payroll.statutory_profile ",
          "payroll.statutory_calculation ",
          "payroll.statutory_calculation_line ",
          "payroll.statutory_calculation_component ",
          "payroll.pay_run_line ");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private final UUID entity = UUID.randomUUID();
  private LocalDate month;
  private UUID run;
  private UUID latest;
  private UUID first;
  private UUID line;
  private String ids;

  private void seedTenant(UUID tenantId, UUID entityId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE stat_people ON COMMIT DROP AS SELECT g,"
                      + " gen_random_uuid() AS employee, gen_random_uuid() AS line,"
                      + " gen_random_uuid() AS calc_line FROM generate_series(1, "
                      + EMPLOYEES
                      + ") g");
              jdbc.execute(
                  "CREATE TEMP TABLE stat_chain ON COMMIT DROP AS SELECT v,"
                      + " gen_random_uuid() AS id FROM generate_series(1, 25) v");
              jdbc.update(
                  "INSERT INTO payroll.statutory_profile (tenant_id, employee_id, id, sequence,"
                      + " effective_from, country, classification, cnss_subject, irpp_subject,"
                      + " evidence_code, created_at, created_by) SELECT ?, employee,"
                      + " gen_random_uuid(), s, CASE WHEN s = 1 THEN ?::date ELSE ?::date END,"
                      + " 'CD', 'STANDARD_RESIDENT', true, s = 1, 'EMPLOYMENT_CONTRACT', now(),"
                      + " 'sub-test' FROM stat_people CROSS JOIN generate_series(1, 2) s",
                  tenantId,
                  month.minusDays(400),
                  month.minusDays(100));
              jdbc.update(
                  "INSERT INTO payroll.statutory_activation (tenant_id, legal_entity_id, id,"
                      + " sequence, pack_id, from_month, acknowledged, activated_at, activated_by)"
                      + " SELECT ?, ?, gen_random_uuid(), s, ?::uuid,"
                      + " (date_trunc('month', ?::date) - make_interval(months => 60 - s))::date,"
                      + " true, now(), 'sub-test' FROM generate_series(1, 60) s",
                  tenantId,
                  entityId,
                  PACK,
                  month);
              jdbc.update(
                  "INSERT INTO payroll.pay_run (tenant_id, legal_entity_id, id, period_start,"
                      + " currency, version, status, policy_id, policy_sequence, pay_day_kind,"
                      + " pay_day, proration_method, employee_count, total_gross_minor,"
                      + " input_digest, created_at, created_by) SELECT ?, ?, ?::uuid, ?::date,"
                      + " 'CDF', 1, 'DRAFT', gen_random_uuid(), 1, 'LAST_DAY', NULL,"
                      + " 'CALENDAR_DAYS', "
                      + EMPLOYEES
                      + ", "
                      + EMPLOYEES * 100_000L
                      + ", repeat('a', 64), now(), 'sub-test'",
                  tenantId,
                  entityId,
                  tenantId.equals(tenant) ? runId() : UUID.randomUUID(),
                  month);
              jdbc.update(
                  "INSERT INTO payroll.pay_run_line (tenant_id, run_id, id, employee_id,"
                      + " employee_number, eligible_days, denominator, full_month, monthly_minor,"
                      + " gross_minor, explanation_codes) SELECT ?, r.id, p.line, p.employee,"
                      + " 'PLAN' || lpad(p.g::text, 6, '0'), 30, NULL, true, 100000, 100000,"
                      + " ARRAY['FULL_MONTH'] FROM payroll.pay_run r CROSS JOIN stat_people p"
                      + " WHERE r.tenant_id = ? AND r.legal_entity_id = ?",
                  tenantId,
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.pay_run_line_range (tenant_id, run_id, line_id, seq,"
                      + " range_from, range_to) SELECT ?, l.run_id, l.id, 1, ?::date,"
                      + " (?::date + 29) FROM payroll.pay_run_line l WHERE l.tenant_id = ?",
                  tenantId,
                  month,
                  month,
                  tenantId);
              jdbc.update(
                  "INSERT INTO payroll.statutory_calculation (tenant_id, legal_entity_id, id,"
                      + " run_id, run_version, period_start, currency, pack_id, activation_id,"
                      + " version, status, supported_count, excluded_count, blocked_count,"
                      + " gross_total_minor, employee_deductions_total_minor,"
                      + " employer_contributions_total_minor, preview_net_total_minor,"
                      + " employer_cost_total_minor, source_run_digest, profile_digest,"
                      + " rule_pack_digest, calculation_digest, created_at, created_by,"
                      + " superseded_at, superseded_by_id) SELECT ?, ?, c.id, r.id, 1, ?::date,"
                      + " 'CDF', ?::uuid, gen_random_uuid(), c.v,"
                      + " CASE WHEN c.v = 25 THEN 'DRAFT' ELSE 'SUPERSEDED' END, "
                      + EMPLOYEES
                      + ", 0, 0, "
                      + EMPLOYEES * 100_000L
                      + ", "
                      + EMPLOYEES * 8_000L
                      + ", "
                      + EMPLOYEES * 13_000L
                      + ", "
                      + EMPLOYEES * 92_000L
                      + ", "
                      + EMPLOYEES * 113_000L
                      + ", repeat('a', 64), repeat('b', 64), repeat('c', 64), repeat('d', 64),"
                      + " now(), 'sub-test', CASE WHEN c.v = 25 THEN NULL ELSE now() END,"
                      + " n.id FROM stat_chain c LEFT JOIN stat_chain n ON n.v = c.v + 1"
                      + " CROSS JOIN (SELECT id FROM payroll.pay_run WHERE tenant_id = ?"
                      + " AND legal_entity_id = ?) r",
                  tenantId,
                  entityId,
                  month,
                  PACK,
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.statutory_calculation_line (tenant_id, calculation_id, id,"
                      + " run_id, source_line_id, employee_id, employee_number, gross_minor,"
                      + " employee_deductions_minor, employer_contributions_minor,"
                      + " preview_net_minor, employer_cost_minor, excluded) SELECT ?, c.id,"
                      + " p.calc_line, r.id, p.line, p.employee,"
                      + " 'PLAN' || lpad(p.g::text, 6, '0'), 100000, 8000, 13000, 92000, 113000,"
                      + " false FROM stat_chain c CROSS JOIN stat_people p CROSS JOIN"
                      + " (SELECT id FROM payroll.pay_run WHERE tenant_id = ? AND"
                      + " legal_entity_id = ?) r WHERE c.v = 25",
                  tenantId,
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.statutory_calculation_component (tenant_id, calculation_id,"
                      + " line_id, seq, component_id, party, category, base_minor, amount_minor,"
                      + " explanation_codes, trace) SELECT ?, l.calculation_id, l.id, k.seq,"
                      + " k.component_id, k.party, k.category, 100000, 1000,"
                      + " ARRAY['RATE_APPLIED', 'BASE_GROSS'], NULL"
                      + " FROM payroll.statutory_calculation_line l CROSS JOIN (VALUES"
                      + " (1, 'CNSS_EMPLOYEE_PENSION', 'EMPLOYEE', 'SOCIAL_CONTRIBUTION'),"
                      + " (2, 'CNSS_EMPLOYER_PENSION', 'EMPLOYER', 'SOCIAL_CONTRIBUTION'),"
                      + " (3, 'CNSS_EMPLOYER_OCCUPATIONAL_RISK', 'EMPLOYER', 'SOCIAL_CONTRIBUTION'),"
                      + " (4, 'CNSS_EMPLOYER_FAMILY_BENEFITS', 'EMPLOYER', 'SOCIAL_CONTRIBUTION'),"
                      + " (5, 'IRPP_SALARY_WITHHOLDING', 'EMPLOYEE', 'INCOME_TAX'))"
                      + " AS k(seq, component_id, party, category) WHERE l.tenant_id = ?",
                  tenantId,
                  tenantId);
            });
  }

  private UUID measuredRun;

  private UUID runId() {
    if (measuredRun == null) {
      measuredRun = UUID.randomUUID();
    }
    return measuredRun;
  }

  @BeforeAll
  void seed() {
    month = LocalDate.now().withDayOfMonth(1).minusMonths(1);
    for (int other = 0; other < 4; other++) {
      seedTenant(UUID.randomUUID(), UUID.randomUUID());
    }
    seedTenant(tenant, entity);
    for (String table :
        List.of(
            "payroll.statutory_activation",
            "payroll.statutory_profile",
            "payroll.statutory_calculation",
            "payroll.statutory_calculation_line",
            "payroll.statutory_calculation_component",
            "payroll.pay_run",
            "payroll.pay_run_line",
            "payroll.pay_run_line_range")) {
      jdbc.execute("ANALYZE " + table);
    }
    run = measuredRun;
    latest =
        jdbc.queryForObject(
            "SELECT id FROM payroll.statutory_calculation WHERE tenant_id = ? AND version = 25",
            UUID.class,
            tenant);
    first =
        jdbc.queryForObject(
            "SELECT id FROM payroll.statutory_calculation WHERE tenant_id = ? AND version = 1",
            UUID.class,
            tenant);
    line =
        jdbc.queryForObject(
            "SELECT id FROM payroll.statutory_calculation_line WHERE tenant_id = ?"
                + " AND calculation_id = ? AND employee_number = 'PLAN001500'",
            UUID.class,
            tenant,
            latest);
    List<UUID> employees =
        jdbc.queryForList(
            "SELECT DISTINCT employee_id FROM payroll.statutory_profile WHERE tenant_id = ?"
                + " LIMIT 500",
            UUID.class,
            tenant);
    ids = "{" + String.join(",", employees.stream().map(UUID::toString).toList()) + "}";
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_calculation_line WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo(EMPLOYEES);
  }

  private String plan(String sql, Map<String, Object> params) {
    // Tiny test data lets the planner pick a bitmap scan plus a sort; with those two off the plan
    // shows whether an index can serve the query and its order, which is what is asserted.
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              jdbc.execute("SET LOCAL enable_bitmapscan = off");
              jdbc.execute("SET LOCAL enable_sort = off");
              return String.join(
                  "\n", named.query("EXPLAIN " + sql, params, (rs, row) -> rs.getString(1)));
            });
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
  void theActivationTimelineAndTheActivationInEffectAreDrivenByTheirKeys() {
    driven(
        "timeline",
        "SELECT a.id, a.sequence, a.from_month FROM payroll.statutory_activation a"
            + " JOIN payroll.statutory_rule_pack p ON p.id = a.pack_id"
            + " WHERE a.tenant_id = :tenant AND a.legal_entity_id = :entity"
            + " ORDER BY a.sequence DESC LIMIT 60",
        Map.of("tenant", tenant, "entity", entity),
        "payroll_statutory_activation_");
    driven(
        "in effect",
        "SELECT a.id, a.sequence, a.from_month FROM payroll.statutory_activation a"
            + " JOIN payroll.statutory_rule_pack p ON p.id = a.pack_id"
            + " WHERE a.tenant_id = :tenant AND a.legal_entity_id = :entity"
            + " AND a.from_month <= :month"
            + " ORDER BY a.from_month DESC, a.sequence DESC LIMIT 1",
        Map.of("tenant", tenant, "entity", entity, "month", month),
        "payroll_statutory_activation_");
    driven(
        "version",
        "SELECT COALESCE(max(sequence), 0) FROM payroll.statutory_activation"
            + " WHERE tenant_id = :tenant AND legal_entity_id = :entity",
        Map.of("tenant", tenant, "entity", entity),
        "payroll_statutory_activation_");
  }

  @Test
  void theProfileTimelinesAreDrivenByTheTimelineIndex() {
    String timeline =
        "SELECT id, sequence, effective_from FROM payroll.statutory_profile"
            + " WHERE tenant_id = :tenant AND employee_id = :employee"
            + " ORDER BY effective_from DESC, sequence DESC LIMIT 100";
    Map<String, Object> params =
        Map.of("tenant", tenant, "employee", UUID.fromString(ids.substring(1, 37)));
    driven("one employee", timeline, params, "payroll_statutory_profile_timeline");
    assertThat(plan(timeline, params)).doesNotContain("Sort");
    driven(
        "many employees",
        "SELECT employee_id, id, sequence, effective_from FROM payroll.statutory_profile"
            + " WHERE tenant_id = :tenant AND employee_id = ANY(CAST(:ids AS uuid[]))"
            + " ORDER BY employee_id, effective_from, sequence",
        Map.of("tenant", tenant, "ids", ids),
        "payroll_statutory_profile_");
  }

  @Test
  void theFrozenSourceLinesPageInIndexOrderWithTheirRanges() {
    String sql =
        "SELECT l.id, l.employee_id, l.employee_number, l.gross_minor,"
            + " (SELECT min(r.range_from) FROM payroll.pay_run_line_range r"
            + " WHERE r.tenant_id = l.tenant_id AND r.run_id = l.run_id AND r.line_id = l.id)"
            + " AS first_day,"
            + " (SELECT max(r.range_to) FROM payroll.pay_run_line_range r"
            + " WHERE r.tenant_id = l.tenant_id AND r.run_id = l.run_id AND r.line_id = l.id)"
            + " AS last_day"
            + " FROM payroll.pay_run_line l WHERE l.tenant_id = :tenant AND l.run_id = :run"
            + " ORDER BY (l.employee_number COLLATE \"C\"), l.id LIMIT :limit";
    Map<String, Object> params = Map.of("tenant", tenant, "run", run, "limit", 5001);
    driven("source lines", sql, params, "payroll_pay_run_line_page");
    String plan = plan(sql, params);
    assertThat(plan)
        .contains("pay_run_line_range")
        .doesNotContain("Seq Scan on payroll.pay_run_line_range");
  }

  @Test
  void theChainTheDraftAndTheCalculationAreDrivenByTheirKeys() {
    driven(
        "chain version",
        "SELECT COALESCE(max(version), 0) FROM payroll.statutory_calculation"
            + " WHERE tenant_id = :tenant AND run_id = :run AND pack_id = CAST(:pack AS uuid)",
        Map.of("tenant", tenant, "run", run, "pack", PACK),
        "payroll_statutory_calculation_version_unique");
    driven(
        "draft",
        "SELECT c.id FROM payroll.statutory_calculation c"
            + " JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id"
            + " WHERE c.tenant_id = :tenant AND c.run_id = :run AND c.pack_id = CAST(:pack AS uuid)"
            + " AND c.status = 'DRAFT'",
        Map.of("tenant", tenant, "run", run, "pack", PACK),
        "payroll_statutory_calculation_");
    driven(
        "by id",
        "SELECT c.id FROM payroll.statutory_calculation c"
            + " JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id"
            + " WHERE c.tenant_id = :tenant AND c.legal_entity_id = :entity AND c.run_id = :run"
            + " AND c.id = :id",
        Map.of("tenant", tenant, "entity", entity, "run", run, "id", latest),
        "payroll_statutory_calculation_");
  }

  @Test
  void theCalculationHistoryPagesInIndexOrderWithoutASort() {
    String firstPage =
        "SELECT c.id, c.version FROM payroll.statutory_calculation c"
            + " JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id"
            + " WHERE c.tenant_id = :tenant AND c.legal_entity_id = :entity AND c.run_id = :run"
            + " ORDER BY c.version DESC, c.id LIMIT :limit";
    Map<String, Object> params =
        Map.of("tenant", tenant, "entity", entity, "run", run, "limit", 26);
    driven("first page", firstPage, params, "payroll_statutory_calculation_history");
    assertThat(plan(firstPage, params)).doesNotContain("Sort");

    String keyset =
        "SELECT c.id, c.version FROM payroll.statutory_calculation c"
            + " JOIN payroll.statutory_rule_pack p ON p.id = c.pack_id"
            + " WHERE c.tenant_id = :tenant AND c.legal_entity_id = :entity AND c.run_id = :run"
            + " AND (c.version < :version OR (c.version = :version AND c.id > :after))"
            + " ORDER BY c.version DESC, c.id LIMIT :limit";
    Map<String, Object> next =
        Map.of(
            "tenant", tenant, "entity", entity, "run", run, "version", 20, "after", first, "limit",
            26);
    driven("keyset", keyset, next, "payroll_statutory_calculation_history");
    assertThat(plan(keyset, next)).doesNotContain("Sort");
  }

  @Test
  void theCalculationDetailPagesInIndexOrderWithoutASort() {
    String firstPage =
        "SELECT id, employee_id, employee_number, gross_minor FROM"
            + " payroll.statutory_calculation_line WHERE tenant_id = :tenant"
            + " AND calculation_id = :calculation"
            + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit";
    Map<String, Object> params = Map.of("tenant", tenant, "calculation", latest, "limit", 51);
    driven("first page", firstPage, params, "payroll_statutory_calculation_line_page");
    assertThat(plan(firstPage, params)).doesNotContain("Sort");

    String keyset =
        "SELECT id, employee_id, employee_number, gross_minor FROM"
            + " payroll.statutory_calculation_line WHERE tenant_id = :tenant"
            + " AND calculation_id = :calculation"
            + " AND ((employee_number COLLATE \"C\"), id) > (CAST(:number AS text) COLLATE \"C\","
            + " CAST(:after AS uuid))"
            + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit";
    Map<String, Object> next =
        Map.of(
            "tenant",
            tenant,
            "calculation",
            latest,
            "number",
            "PLAN001500",
            "after",
            line,
            "limit",
            51);
    driven("keyset", keyset, next, "payroll_statutory_calculation_line_page");
    assertThat(plan(keyset, next)).doesNotContain("Sort");
    driven(
        "line by id",
        "SELECT id FROM payroll.statutory_calculation_line WHERE tenant_id = :tenant"
            + " AND calculation_id = :calculation AND id = :id",
        Map.of("tenant", tenant, "calculation", latest, "id", line),
        "payroll_statutory_calculation_line_pkey");
    driven(
        "components",
        "SELECT line_id, seq, component_id, amount_minor FROM"
            + " payroll.statutory_calculation_component WHERE tenant_id = :tenant"
            + " AND calculation_id = :calculation AND line_id = ANY(CAST(:ids AS uuid[]))"
            + " ORDER BY line_id, seq",
        Map.of("tenant", tenant, "calculation", latest, "ids", "{" + line + "}"),
        "payroll_statutory_calculation_component_pkey");
  }

  @Test
  void thePlatformRulePackListIsBoundedByItsLimit() {
    String plan =
        plan(
            "SELECT p.id FROM payroll.statutory_rule_pack p"
                + " WHERE (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))"
                + " ORDER BY p.effective_from DESC, p.code, p.version DESC LIMIT :limit",
            Map.of("status", "VERIFIED", "limit", 26));
    assertThat(plan).contains("Limit");
    assertThat(plan).doesNotContain("statutory_calculation").doesNotContain("pay_run");
  }
}
