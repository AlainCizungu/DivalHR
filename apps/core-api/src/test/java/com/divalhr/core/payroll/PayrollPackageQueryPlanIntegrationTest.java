package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import java.sql.Timestamp;
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
 * MVP-094C (Issue #181, ADR 0035 sections 6 to 8): the plans of the package list and its keyset
 * page, the package by identifier, the chain, the packages of a run and calculation, the source
 * lock look-up, the voided-pair check, the payslip pages of a package by employee number in byte
 * order, the payslip by identifier and the employee's own payslip list, over a realistic tenant on
 * the migrated database (180 packages of 60 months with one or two voids each, 20 payslips each
 * except the latest approval with 2,000, analyzed) among four other tenants of the same shape. Each
 * path is driven by an existing index and never scans a tenant table sequentially. No index is
 * added.
 *
 * <p>The statements mirror the ones of {@code JdbcPayrollPackageRepository} (the same predicates,
 * orders and parameters; the column lists are shortened). The rows are written set-based with
 * {@code session_replication_role = replica} (no triggers or foreign keys; the plan depends on the
 * indexes and statistics only). The void table is small by nature (one row per voided package) and
 * is left out of the sequential scan rule.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayrollPackageQueryPlanIntegrationTest {

  private static final int EMPLOYEES = 2_000;
  private static final int PACKAGES = 180;
  private static final String PACK = "4f6c2d1a-0945-4a94-8d3b-5d0a3b0c0d94";
  private static final List<String> TABLES =
      List.of(
          "payroll.payroll_package ",
          "payroll.payroll_package_source ",
          "payroll.payroll_payslip ");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private final UUID entity = UUID.randomUUID();
  private LocalDate month;
  private UUID big;
  private UUID bigRun;
  private UUID bigCalculation;
  private LocalDate bigPeriod;
  private UUID employee;
  private UUID keysetSlip;
  private Timestamp keysetAt;
  private UUID keysetPackage;
  private LocalDate keysetPeriod;

  private void seedTenant(UUID tenantId, UUID entityId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE pkg_people ON COMMIT DROP AS SELECT g,"
                      + " gen_random_uuid() AS employee FROM generate_series(1, "
                      + EMPLOYEES
                      + ") g");
              jdbc.execute(
                  "CREATE TEMP TABLE pkg_chain ON COMMIT DROP AS SELECT n,"
                      + " gen_random_uuid() AS id, gen_random_uuid() AS run_id,"
                      + " gen_random_uuid() AS calc_id,"
                      + " (date_trunc('month', DATE '"
                      + month
                      + "') - make_interval(months => (n - 1) / 3))::date AS period,"
                      + " ((n - 1) % 3) + 1 AS version, ((n - 1) % 3) = 2 AS approved,"
                      + " timestamptz '2020-01-01 00:00:00+00' + (n * interval '1 hour')"
                      + " AS approved_at FROM generate_series(1, "
                      + PACKAGES
                      + ") n");
              jdbc.update(
                  "INSERT INTO payroll.payroll_package (tenant_id, legal_entity_id, id,"
                      + " period_start, currency, version, status, run_id, run_version,"
                      + " run_digest, calculation_id, calculation_version, calculation_digest,"
                      + " calculation_source_run_digest, calculation_profile_digest,"
                      + " calculation_rule_pack_digest, pack_id, pack_code, pack_version,"
                      + " rules_digest, policy_id, policy_sequence, payslip_count,"
                      + " gross_total_minor, employee_deductions_total_minor,"
                      + " employer_contributions_total_minor, net_total_minor,"
                      + " employer_cost_total_minor, reviewed_totals, reviewed_exceptions,"
                      + " understands_no_payment, same_preparer, approval_digest, approved_at,"
                      + " approved_by_subject, voided_at) SELECT ?, ?, c.id, c.period, 'CDF',"
                      + " c.version, CASE WHEN c.approved THEN 'APPROVED' ELSE 'VOIDED' END,"
                      + " c.run_id, 1, repeat('a', 64), c.calc_id, 1, repeat('b', 64),"
                      + " repeat('c', 64), repeat('d', 64), repeat('e', 64), ?::uuid,"
                      + " 'SYNTHETIC-TEST-PLAN', 1, repeat('f', 64), gen_random_uuid(), 1,"
                      + " CASE WHEN c.n = 3 THEN "
                      + EMPLOYEES
                      + " ELSE 20 END, 100000, 8000, 13000, 92000, 113000, true, true, true,"
                      + " false, repeat('a', 64), c.approved_at, 'sub-test',"
                      + " CASE WHEN c.approved THEN NULL ELSE c.approved_at + interval '1 minute'"
                      + " END FROM pkg_chain c",
                  tenantId,
                  entityId,
                  PACK);
              jdbc.update(
                  "INSERT INTO payroll.payroll_package_source (tenant_id, package_id, kind,"
                      + " source_id, source_version, source_digest) SELECT ?, c.id, k.kind,"
                      + " CASE WHEN k.kind = 'RUN' THEN c.run_id ELSE c.calc_id END, 1,"
                      + " repeat('a', 64) FROM pkg_chain c CROSS JOIN"
                      + " (VALUES ('RUN'), ('CALCULATION')) AS k(kind)",
                  tenantId);
              jdbc.update(
                  "INSERT INTO payroll.payroll_package_void (tenant_id, legal_entity_id,"
                      + " package_id, id, reason_code, explanation, no_payment_acknowledged,"
                      + " approval_digest, voided_at, voided_by_subject) SELECT ?, ?, c.id,"
                      + " gen_random_uuid(), 'ADMINISTRATIVE_ERROR', NULL, true, repeat('a', 64),"
                      + " c.approved_at + interval '1 minute', 'sub-test' FROM pkg_chain c"
                      + " WHERE NOT c.approved",
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.payroll_payslip (tenant_id, legal_entity_id, package_id,"
                      + " id, period_start, approved_at, employee_id, employee_number,"
                      + " calculation_id, calculation_line_id, excluded, gross_minor,"
                      + " employee_deductions_minor, employer_contributions_minor, net_minor,"
                      + " employer_cost_minor, snapshot_version, snapshot_text) SELECT ?, ?,"
                      + " c.id, gen_random_uuid(), c.period, c.approved_at, p.employee,"
                      + " 'PLAN' || lpad(p.g::text, 6, '0'), c.calc_id, gen_random_uuid(), false,"
                      + " 100000, 8000, 13000, 92000, 113000, 1, '{}' FROM pkg_chain c JOIN"
                      + " pkg_people p ON p.g <= CASE WHEN c.n = 3 THEN "
                      + EMPLOYEES
                      + " ELSE 20 END",
                  tenantId,
                  entityId);
            });
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
            "payroll.payroll_package",
            "payroll.payroll_package_source",
            "payroll.payroll_package_void",
            "payroll.payroll_payslip")) {
      jdbc.execute("ANALYZE " + table);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.payroll_package WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo(PACKAGES);
    big =
        jdbc.queryForObject(
            "SELECT id FROM payroll.payroll_package WHERE tenant_id = ? AND payslip_count = ?",
            UUID.class,
            tenant,
            EMPLOYEES);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT run_id, calculation_id, period_start FROM payroll.payroll_package"
                + " WHERE id = ?",
            big);
    bigRun = (UUID) row.get("run_id");
    bigCalculation = (UUID) row.get("calculation_id");
    bigPeriod = ((java.sql.Date) row.get("period_start")).toLocalDate();
    employee =
        jdbc.queryForObject(
            "SELECT employee_id FROM payroll.payroll_payslip WHERE tenant_id = ?"
                + " AND package_id = ? AND employee_number = 'PLAN000001'",
            UUID.class,
            tenant,
            big);
    keysetSlip =
        jdbc.queryForObject(
            "SELECT id FROM payroll.payroll_payslip WHERE tenant_id = ? AND package_id = ?"
                + " AND employee_number = 'PLAN000500'",
            UUID.class,
            tenant,
            big);
    Map<String, Object> mine =
        jdbc.queryForMap(
            "SELECT approved_at, id FROM payroll.payroll_payslip WHERE tenant_id = ?"
                + " AND employee_id = ? ORDER BY approved_at DESC, id DESC OFFSET 50 LIMIT 1",
            tenant,
            employee);
    keysetAt = (Timestamp) mine.get("approved_at");
    keysetPackage =
        jdbc.queryForObject(
            "SELECT id FROM payroll.payroll_package WHERE tenant_id = ? AND version = 2"
                + " ORDER BY period_start DESC LIMIT 1 OFFSET 20",
            UUID.class,
            tenant);
    keysetPeriod =
        jdbc.queryForObject(
            "SELECT period_start FROM payroll.payroll_package WHERE id = ?",
            LocalDate.class,
            keysetPackage);
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

  private void drivenByOneOf(
      String label, String sql, Map<String, Object> params, String... indexes) {
    String plan = plan(sql, params);
    assertThat(plan).as(label + "\n" + plan).containsAnyOf(indexes);
    for (String table : TABLES) {
      assertThat(plan).as(label + "\n" + plan).doesNotContain("Seq Scan on " + table);
    }
  }

  private static final String PACKAGE_FROM =
      " FROM payroll.payroll_package p LEFT JOIN payroll.payroll_package_void v"
          + " ON v.tenant_id = p.tenant_id AND v.package_id = p.id";

  private static final String SLIP_FROM =
      " FROM payroll.payroll_payslip s JOIN payroll.payroll_package p"
          + " ON p.tenant_id = s.tenant_id AND p.id = s.package_id"
          + " LEFT JOIN payroll.payroll_package_void v"
          + " ON v.tenant_id = p.tenant_id AND v.package_id = p.id";

  @Test
  void thePackageListPagesInIndexOrderWithoutASort() {
    String firstPage =
        "SELECT p.id, p.period_start, p.version, v.reason_code AS void_reason"
            + PACKAGE_FROM
            + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
            + " ORDER BY p.period_start DESC, p.version DESC, p.id LIMIT :limit";
    Map<String, Object> params = Map.of("tenant", tenant, "entity", entity, "limit", 26);
    driven("first page", firstPage, params, "payroll_package_history");
    assertThat(plan(firstPage, params)).doesNotContain("Sort");

    String keyset =
        "SELECT p.id, p.period_start, p.version, v.reason_code AS void_reason"
            + PACKAGE_FROM
            + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
            + " AND (p.period_start < :period OR (p.period_start = :period AND (p.version <"
            + " :version OR (p.version = :version AND p.id > :after))))"
            + " ORDER BY p.period_start DESC, p.version DESC, p.id LIMIT :limit";
    Map<String, Object> next =
        Map.of(
            "tenant",
            tenant,
            "entity",
            entity,
            "period",
            keysetPeriod,
            "version",
            2,
            "after",
            keysetPackage,
            "limit",
            26);
    driven("keyset", keyset, next, "payroll_package_history");
    assertThat(plan(keyset, next)).doesNotContain("Sort");

    String filtered =
        "SELECT p.id, p.period_start, p.version"
            + PACKAGE_FROM
            + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
            + " AND p.status = :status"
            + " ORDER BY p.period_start DESC, p.version DESC, p.id LIMIT :limit";
    driven(
        "status filter",
        filtered,
        Map.of("tenant", tenant, "entity", entity, "status", "VOIDED", "limit", 26),
        "payroll_package_history");
  }

  @Test
  void thePackageByIdentifierAndItsVoidEvidenceAreDrivenByTheirKeys() {
    driven(
        "package by id",
        "SELECT p.id, v.reason_code"
            + PACKAGE_FROM
            + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity AND p.id = :id",
        Map.of("tenant", tenant, "entity", entity, "id", big),
        "payroll_package_tenant_id_unique");
    driven(
        "void evidence",
        "SELECT reason_code, explanation, voided_at FROM payroll.payroll_package_void"
            + " WHERE tenant_id = :tenant AND legal_entity_id = :entity AND package_id = :id",
        Map.of("tenant", tenant, "entity", entity, "id", big),
        "payroll_package_void_");
  }

  @Test
  void theChainThePairAndTheApprovedPackageAreDrivenByTheirIndexes() {
    driven(
        "chain",
        "SELECT p.id, p.version, p.status"
            + PACKAGE_FROM
            + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
            + " AND p.period_start = :period AND p.currency = :currency ORDER BY p.version",
        Map.of("tenant", tenant, "entity", entity, "period", bigPeriod, "currency", "CDF"),
        "payroll_package_history");
    drivenByOneOf(
        "approved package of the chain",
        "SELECT p.id FROM payroll.payroll_package p WHERE p.tenant_id = :tenant"
            + " AND p.legal_entity_id = :entity AND p.period_start = :period"
            + " AND p.currency = :currency AND p.status = 'APPROVED'",
        Map.of("tenant", tenant, "entity", entity, "period", bigPeriod, "currency", "CDF"),
        "payroll_package_one_approved",
        "payroll_package_history");
    driven(
        "packages of the pair",
        "SELECT p.id, p.version, p.status"
            + PACKAGE_FROM
            + " WHERE p.tenant_id = :tenant AND p.run_id = :run"
            + " AND p.calculation_id = :calculation ORDER BY p.version",
        Map.of("tenant", tenant, "run", bigRun, "calculation", bigCalculation),
        "payroll_package_pair");
    driven(
        "voided pair",
        "SELECT 1 FROM payroll.payroll_package p WHERE p.tenant_id = :tenant"
            + " AND p.run_id = :run AND p.calculation_id = :calculation AND p.status = 'VOIDED'",
        Map.of("tenant", tenant, "run", bigRun, "calculation", bigCalculation),
        "payroll_package_pair");
  }

  @Test
  void theSourceLockLookUpIsDrivenByTheSourceIndex() {
    String lock =
        "SELECT 1 FROM payroll.payroll_package_source s JOIN payroll.payroll_package p"
            + " ON p.tenant_id = s.tenant_id AND p.id = s.package_id WHERE s.tenant_id = :tenant"
            + " AND s.kind = :kind AND s.source_id = :source AND p.status = 'APPROVED'";
    driven(
        "run lock",
        lock,
        Map.of("tenant", tenant, "kind", "RUN", "source", bigRun),
        "payroll_package_source_lookup");
    driven(
        "calculation lock",
        lock,
        Map.of("tenant", tenant, "kind", "CALCULATION", "source", bigCalculation),
        "payroll_package_source_lookup");
    driven(
        "sources of a package",
        "SELECT kind, source_id, source_version, source_digest"
            + " FROM payroll.payroll_package_source WHERE tenant_id = :tenant"
            + " AND package_id = :id",
        Map.of("tenant", tenant, "id", big),
        "payroll_package_source_pkey");
  }

  @Test
  void thePackagePayslipsPageInByteOrderOfTheEmployeeNumberWithoutASort() {
    String firstPage =
        "SELECT s.id, s.employee_number, p.status, v.reason_code"
            + SLIP_FROM
            + " WHERE s.tenant_id = :tenant AND s.legal_entity_id = :entity"
            + " AND s.package_id = :package"
            + " ORDER BY (s.employee_number COLLATE \"C\"), s.id LIMIT :limit";
    Map<String, Object> params =
        Map.of("tenant", tenant, "entity", entity, "package", big, "limit", 51);
    driven("first page", firstPage, params, "payroll_payslip_package_page");
    assertThat(plan(firstPage, params)).doesNotContain("Sort");

    String keyset =
        "SELECT s.id, s.employee_number, p.status, v.reason_code"
            + SLIP_FROM
            + " WHERE s.tenant_id = :tenant AND s.legal_entity_id = :entity"
            + " AND s.package_id = :package"
            + " AND ((s.employee_number COLLATE \"C\"), s.id) >"
            + " (CAST(:number AS text) COLLATE \"C\", CAST(:after AS uuid))"
            + " ORDER BY (s.employee_number COLLATE \"C\"), s.id LIMIT :limit";
    Map<String, Object> next =
        Map.of(
            "tenant",
            tenant,
            "entity",
            entity,
            "package",
            big,
            "number",
            "PLAN000500",
            "after",
            keysetSlip,
            "limit",
            51);
    driven("keyset", keyset, next, "payroll_payslip_package_page");
    assertThat(plan(keyset, next)).doesNotContain("Sort");
  }

  @Test
  void aPayslipByIdentifierAndThePackageSumsAreDrivenByTheirKeys() {
    driven(
        "payslip by id",
        "SELECT s.id, s.snapshot_text"
            + SLIP_FROM
            + " WHERE s.tenant_id = :tenant AND s.legal_entity_id = :entity"
            + " AND s.package_id = :package AND s.id = :id",
        Map.of("tenant", tenant, "entity", entity, "package", big, "id", keysetSlip),
        "payroll_payslip_tenant_id_unique");
    drivenByOneOf(
        "package sums",
        "SELECT count(*), coalesce(sum(gross_minor), 0), coalesce(sum(net_minor), 0)"
            + " FROM payroll.payroll_payslip WHERE tenant_id = :tenant AND package_id = :package",
        Map.of("tenant", tenant, "package", big),
        "payroll_payslip_package_page",
        "payroll_payslip_package_fk",
        "payroll_payslip_line_unique",
        "payroll_payslip_tenant_id_unique");
  }

  @Test
  void theEmployeesOwnPayslipsPageNewestFirstWithoutASort() {
    String firstPage =
        "SELECT s.id, s.approved_at, p.status, v.reason_code"
            + SLIP_FROM
            + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee"
            + " ORDER BY s.approved_at DESC, s.id DESC LIMIT :limit";
    Map<String, Object> params = Map.of("tenant", tenant, "employee", employee, "limit", 26);
    driven("first page", firstPage, params, "payroll_payslip_employee_page");
    assertThat(plan(firstPage, params)).doesNotContain("Sort");

    String keyset =
        "SELECT s.id, s.approved_at, p.status, v.reason_code"
            + SLIP_FROM
            + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee"
            + " AND (s.approved_at < :at OR (s.approved_at = :at AND s.id < :after))"
            + " ORDER BY s.approved_at DESC, s.id DESC LIMIT :limit";
    Map<String, Object> next =
        Map.of(
            "tenant",
            tenant,
            "employee",
            employee,
            "at",
            keysetAt,
            "after",
            keysetSlip,
            "limit",
            26);
    driven("keyset", keyset, next, "payroll_payslip_employee_page");
    assertThat(plan(keyset, next)).doesNotContain("Sort");
    drivenByOneOf(
        "own payslip by id",
        "SELECT s.id"
            + SLIP_FROM
            + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee AND s.id = :id",
        Map.of("tenant", tenant, "employee", employee, "id", keysetSlip),
        "payroll_payslip_employee_page",
        "payroll_payslip_tenant_id_unique",
        "payroll_payslip_employee_unique");
  }
}
