package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-095A (Issue #183, ADR 0036, R184-1): the plans of every statement of {@code
 * JdbcPaymentRepository}: the latest, by-identifier, effective, scheduled, by-version and paged
 * history reads of a destination, the effective destinations of a package and the shared-holder
 * count, the payslip look-up, the package and batch chain versions, the prepared checks, the batch
 * lock and read, the batch pages (plain, keyset, package and status filters), the line pages (plain
 * and keyset) and look-up, the cancellation read and the cancelling update. The tenant under test
 * has 1,000 employees with four destination versions each (pairs share a holder fingerprint), 180
 * packages (one with 1,000 payslips), 155 batches (one with 1,000 lines) among four other tenants
 * of the same shape, analyzed. Each path is driven by an existing index and never scans a payment
 * table, the payslip table or the package table sequentially; the ordered and paged paths need no
 * sort. No index is added.
 *
 * <p>The statements mirror the ones of the repository (the same predicates, orders and parameters;
 * the column lists are shortened). The rows are written set-based with {@code
 * session_replication_role = replica} (no triggers or foreign keys; the plan depends on the indexes
 * and statistics only). Synthetic data only.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentQueryPlanIntegrationTest {

  private static final int EMPLOYEES = 1_000;
  private static final int PACKAGES = 180;
  private static final int HISTORY_BATCHES = 150;
  private static final Pattern SEQUENTIAL =
      Pattern.compile("Seq Scan on (payroll\\.)?(payment_|payroll_payslip|payroll_package)");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private final UUID entity = UUID.randomUUID();
  private LocalDate month;
  private UUID employee;
  private UUID destination;
  private String fingerprint;
  private UUID bigPackage;
  private LocalDate bigPeriod;
  private UUID preparedBatch;
  private UUID cancelledBatch;
  private Timestamp pageAt;
  private UUID pageAfter;

  private void seedTenant(UUID tenantId, UUID entityId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE pay_people ON COMMIT DROP AS SELECT g,"
                      + " gen_random_uuid() AS employee FROM generate_series(1, "
                      + EMPLOYEES
                      + ") g");
              jdbc.execute(
                  "CREATE TEMP TABLE pay_chain ON COMMIT DROP AS SELECT n,"
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
                      + " repeat('c', 64), repeat('d', 64), repeat('e', 64), gen_random_uuid(),"
                      + " 'SYNTHETIC-TEST-PLAN', 1, repeat('f', 64), gen_random_uuid(), 1,"
                      + " CASE WHEN c.n = 3 THEN "
                      + EMPLOYEES
                      + " ELSE 20 END, 100000, 8000, 13000, 92000, 113000, true, true, true,"
                      + " false, repeat('a', 64), c.approved_at, 'sub-test',"
                      + " CASE WHEN c.approved THEN NULL ELSE c.approved_at + interval '1 minute'"
                      + " END FROM pay_chain c",
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
                      + " 100000, 8000, 13000, 92000, 113000, 1, '{}' FROM pay_chain c JOIN"
                      + " pay_people p ON p.g <= CASE WHEN c.n = 3 THEN "
                      + EMPLOYEES
                      + " ELSE 20 END",
                  tenantId,
                  entityId);
              // Four versions per employee; employees 2k-1 and 2k share a holder fingerprint.
              jdbc.update(
                  "INSERT INTO payroll.payment_destination (tenant_id, employee_id, id,"
                      + " sequence, effective_from, method, country_code, provider_code,"
                      + " provider_name, masked_value, fingerprint, sealed, key_version, status,"
                      + " reason_code, recorded_at, recorded_by_subject) SELECT ?, p.employee,"
                      + " gen_random_uuid(), s.seq, DATE '2025-01-01' + (s.seq * interval '3"
                      + " months')::interval, CASE WHEN s.seq % 2 = 1 THEN 'BANK_ACCOUNT' ELSE"
                      + " 'MOBILE_MONEY' END, 'CD', CASE WHEN s.seq % 2 = 1 THEN 'RAWBANK' ELSE"
                      + " 'MPESA' END, CASE WHEN s.seq % 2 = 1 THEN 'Rawbank' ELSE 'M-Pesa' END,"
                      + " '****' || lpad((p.g % 10000)::text, 4, '0'), md5(((p.g + 1) / 2)::text"
                      + " || ':' || s.seq) || md5(s.seq || ':' || ((p.g + 1) / 2)::text),"
                      + " decode(repeat('ab', 40), 'hex'), 1, 'RECORDED', 'INITIAL_SETUP',"
                      + " timestamptz '2025-01-01 00:00:00+00' + (s.seq * interval '1 day'),"
                      + " 'sub-test' FROM pay_people p CROSS JOIN generate_series(1, 4) AS"
                      + " s(seq)",
                  tenantId);
              // Three batches of the big package (two cancelled, one prepared), one prepared and
              // one cancelled for two other packages, and a monthly history.
              jdbc.execute(
                  "CREATE TEMP TABLE pay_batch ON COMMIT DROP AS SELECT b.id, b.package_id,"
                      + " b.version, b.status, b.period, b.lines, b.at FROM ("
                      + " SELECT gen_random_uuid() AS id, c.id AS package_id, v AS version,"
                      + " CASE WHEN v = 3 THEN 'PREPARED' ELSE 'CANCELLED' END AS status,"
                      + " c.period AS period, CASE WHEN v = 3 THEN "
                      + EMPLOYEES
                      + " ELSE 20 END AS lines, c.approved_at + (v * interval '1 minute') AS at"
                      + " FROM pay_chain c CROSS JOIN generate_series(1, 3) v WHERE c.n = 3"
                      + " UNION ALL SELECT gen_random_uuid(), c.id, 1,"
                      + " CASE WHEN c.n = 6 THEN 'PREPARED' ELSE 'CANCELLED' END, c.period, 20,"
                      + " c.approved_at + interval '1 minute' FROM pay_chain c"
                      + " WHERE c.n IN (6, 9)"
                      + " UNION ALL SELECT gen_random_uuid(), gen_random_uuid(), 1,"
                      + " CASE WHEN h % 3 = 0 THEN 'CANCELLED' ELSE 'PREPARED' END,"
                      + " (date_trunc('month', DATE '"
                      + month
                      + "') - make_interval(months => h))::date, 1,"
                      + " timestamptz '2020-01-01 00:00:00+00' + (h * interval '1 day')"
                      + " FROM generate_series(1, "
                      + HISTORY_BATCHES
                      + ") h) b");
              jdbc.update(
                  "INSERT INTO payroll.payment_batch (tenant_id, legal_entity_id, id, package_id,"
                      + " version, status, period_start, currency, package_version,"
                      + " package_digest, line_count, zero_net_excluded_count, bank_count,"
                      + " mobile_count, manual_count, total_net_minor, destinations_digest,"
                      + " batch_digest, reviewed_totals, understands_no_money, prepared_at,"
                      + " prepared_by_subject, cancelled_at) SELECT ?, ?, b.id, b.package_id,"
                      + " b.version, b.status, b.period, 'CDF', 3, repeat('a', 64), b.lines, 0,"
                      + " b.lines, 0, 0, b.lines * 92000, repeat('b', 64), repeat('c', 64), true,"
                      + " true, b.at, 'sub-test', CASE WHEN b.status = 'CANCELLED' THEN b.at +"
                      + " interval '1 hour' END FROM pay_batch b",
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.payment_batch_cancel (tenant_id, legal_entity_id,"
                      + " batch_id, id, reason_code, no_money_acknowledged, batch_digest,"
                      + " cancelled_at, cancelled_by_subject) SELECT ?, ?, b.id,"
                      + " gen_random_uuid(), 'PREPARED_IN_ERROR', true, repeat('c', 64), b.at +"
                      + " interval '1 hour', 'sub-test' FROM pay_batch b"
                      + " WHERE b.status = 'CANCELLED'",
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.payment_batch_line (tenant_id, legal_entity_id, batch_id,"
                      + " package_id, id, payslip_id, employee_id, employee_number, method,"
                      + " country_code, provider_code, provider_name, masked_value,"
                      + " destination_id, destination_sequence, net_minor) SELECT ?, ?, b.id,"
                      + " b.package_id, gen_random_uuid(), gen_random_uuid(), p.employee,"
                      + " 'PLAN' || lpad(p.g::text, 6, '0'), 'BANK_ACCOUNT', 'CD', 'RAWBANK',"
                      + " 'Rawbank', '****' || lpad(p.g::text, 4, '0'), NULL, NULL, 92000"
                      + " FROM pay_batch b JOIN pay_people p ON p.g <= b.lines",
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
            "payroll.payroll_payslip",
            "payroll.payment_destination",
            "payroll.payment_batch",
            "payroll.payment_batch_line",
            "payroll.payment_batch_cancel")) {
      jdbc.execute("ANALYZE " + table);
    }
    assertThat(count("payroll.payment_destination")).isEqualTo(EMPLOYEES * 4L);
    assertThat(count("payroll.payment_batch")).isEqualTo(HISTORY_BATCHES + 5L);
    assertThat(count("payroll.payment_batch_line"))
        .isEqualTo(EMPLOYEES + 20L * 4 + HISTORY_BATCHES);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT employee_id, id, fingerprint FROM payroll.payment_destination"
                + " WHERE tenant_id = ? AND sequence = 4 ORDER BY employee_id LIMIT 1",
            tenant);
    employee = (UUID) row.get("employee_id");
    destination = (UUID) row.get("id");
    fingerprint = ((String) row.get("fingerprint")).trim();
    Map<String, Object> pkg =
        jdbc.queryForMap(
            "SELECT id, period_start FROM payroll.payroll_package WHERE tenant_id = ?"
                + " AND payslip_count = ?",
            tenant,
            EMPLOYEES);
    bigPackage = (UUID) pkg.get("id");
    bigPeriod = ((java.sql.Date) pkg.get("period_start")).toLocalDate();
    preparedBatch =
        jdbc.queryForObject(
            "SELECT id FROM payroll.payment_batch WHERE tenant_id = ? AND package_id = ?"
                + " AND status = 'PREPARED'",
            UUID.class,
            tenant,
            bigPackage);
    cancelledBatch =
        jdbc.queryForObject(
            "SELECT id FROM payroll.payment_batch WHERE tenant_id = ? AND package_id = ?"
                + " AND status = 'CANCELLED' LIMIT 1",
            UUID.class,
            tenant,
            bigPackage);
    Map<String, Object> page =
        jdbc.queryForMap(
            "SELECT prepared_at, id FROM payroll.payment_batch WHERE tenant_id = ?"
                + " AND legal_entity_id = ? ORDER BY prepared_at DESC, id DESC OFFSET 20 LIMIT 1",
            tenant,
            entity);
    pageAt = (Timestamp) page.get("prepared_at");
    pageAfter = (UUID) page.get("id");
  }

  private long count(String table) {
    Long value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenant);
    return value == null ? 0 : value;
  }

  private String plan(String sql, Map<String, Object> params) {
    // Tiny test data lets the planner pick a bitmap scan plus a sort; with those two off the plan
    // shows whether an index can serve the query and its order, which is what is asserted.
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              jdbc.execute("SET LOCAL enable_bitmapscan = off");
              jdbc.execute("SET LOCAL enable_sort = off");
              // The tiny cancellation table is otherwise materialized and scanned once; a real one is probed by key.
              jdbc.execute("SET LOCAL enable_material = off");
              return String.join(
                  "\n", named.query("EXPLAIN " + sql, params, (rs, row) -> rs.getString(1)));
            });
  }

  /** Driven by one of the indexes, with no sequential scan of a payment-related table. */
  private String driven(String label, String sql, Map<String, Object> params, String... indexes) {
    String plan = plan(sql, params);
    assertThat(plan).as(label + "\n" + plan).containsAnyOf(indexes);
    assertThat(SEQUENTIAL.matcher(plan).find()).as(label + "\n" + plan).isFalse();
    return plan;
  }

  /** As {@link #driven} and the index delivers the order: no sort node at all. */
  private void drivenInOrder(
      String label, String sql, Map<String, Object> params, String... indexes) {
    String plan = driven(label, sql, params, indexes);
    assertThat(plan).as(label + "\n" + plan).doesNotContain("Sort");
  }

  private static final String HISTORY = "payment_destination_history";
  private static final String D = " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant";
  private static final String BATCH_JOIN =
      " FROM payroll.payment_batch b LEFT JOIN payroll.payment_batch_cancel c"
          + " ON c.tenant_id = b.tenant_id AND c.batch_id = b.id";

  @Test
  void theDestinationReadsOfOneEmployeeAreDrivenByTheHistoryIndexInOrder() {
    Map<String, Object> mine = Map.of("tenant", tenant, "employee", employee);
    drivenInOrder(
        "latest",
        "SELECT d.id" + D + " AND d.employee_id = :employee ORDER BY d.sequence DESC LIMIT 1",
        mine,
        HISTORY);
    driven(
        "by identifier",
        "SELECT d.id" + D + " AND d.employee_id = :employee AND d.id = :id",
        Map.of("tenant", tenant, "employee", employee, "id", destination),
        HISTORY,
        "payment_destination_tenant_id_unique",
        "payment_destination_pkey");
    Map<String, Object> dated =
        Map.of("tenant", tenant, "employee", employee, "date", LocalDate.of(2025, 12, 31));
    drivenInOrder(
        "effective",
        "SELECT d.id"
            + D
            + " AND d.employee_id = :employee AND d.effective_from <= :date"
            + " ORDER BY d.sequence DESC LIMIT 1",
        dated,
        HISTORY);
    drivenInOrder(
        "scheduled",
        "SELECT d.id"
            + D
            + " AND d.employee_id = :employee AND d.effective_from > :date"
            + " ORDER BY d.sequence DESC LIMIT 1",
        dated,
        HISTORY);
    driven(
        "by version",
        "SELECT d.id" + D + " AND d.employee_id = :employee AND d.sequence = :sequence",
        Map.of("tenant", tenant, "employee", employee, "sequence", 2),
        HISTORY,
        "payment_destination_sequence_unique");
  }

  @Test
  void theDestinationHistoryPagesInIndexOrderWithoutASort() {
    drivenInOrder(
        "first page",
        "SELECT d.id" + D + " AND d.employee_id = :employee ORDER BY d.sequence DESC LIMIT :limit",
        Map.of("tenant", tenant, "employee", employee, "limit", 26),
        HISTORY);
    drivenInOrder(
        "keyset",
        "SELECT d.id"
            + D
            + " AND d.employee_id = :employee AND d.sequence < :before"
            + " ORDER BY d.sequence DESC LIMIT :limit",
        Map.of("tenant", tenant, "employee", employee, "before", 4, "limit", 26),
        HISTORY);
  }

  @Test
  void theEffectiveDestinationsOfAPackageAndTheSharedHolderCountAreIndexDriven() {
    // One look-up of the latest effective version of each payslip employee: the payslips by their
    // package index, each employee's destinations by the history index (an in-memory sort of the
    // package's own rows is allowed here).
    driven(
        "effective destinations of a package",
        "SELECT DISTINCT ON (d.employee_id) d.id"
            + D
            + " AND d.effective_from <= :date AND d.employee_id IN"
            + " (SELECT s.employee_id FROM payroll.payroll_payslip s"
            + " WHERE s.tenant_id = :tenant AND s.package_id = :package)"
            + " ORDER BY d.employee_id, d.sequence DESC",
        Map.of("tenant", tenant, "package", bigPackage, "date", LocalDate.of(2025, 12, 31)),
        HISTORY);
    driven(
        "other holders of a fingerprint",
        "SELECT count(*)"
            + D
            + " AND d.fingerprint = :fingerprint AND d.employee_id <> :employee"
            + " AND d.sequence = (SELECT max(l.sequence) FROM payroll.payment_destination l"
            + " WHERE l.tenant_id = d.tenant_id AND l.employee_id = d.employee_id)",
        Map.of("tenant", tenant, "fingerprint", fingerprint, "employee", employee),
        "payment_destination_fingerprint");
  }

  @Test
  void thePayslipsOfAPackageAndThePackageChainVersionAreIndexDriven() {
    drivenInOrder(
        "payslips of a package by employee",
        "SELECT s.id FROM payroll.payroll_payslip s WHERE s.tenant_id = :tenant"
            + " AND s.legal_entity_id = :entity AND s.package_id = :package"
            + " ORDER BY s.employee_id",
        Map.of("tenant", tenant, "entity", entity, "package", bigPackage),
        "using payroll_payslip_");
    driven(
        "latest package version",
        "SELECT coalesce(max(p.version), 0) FROM payroll.payroll_package p"
            + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
            + " AND p.period_start = :period AND p.currency = :currency",
        Map.of("tenant", tenant, "entity", entity, "period", bigPeriod, "currency", "CDF"),
        "payroll_package_history");
  }

  @Test
  void theBatchChainTheLifecycleChecksTheLockAndTheReadAreDrivenByTheirKeys() {
    driven(
        "batch chain version",
        "SELECT coalesce(max(b.version), 0) FROM payroll.payment_batch b"
            + " WHERE b.tenant_id = :tenant AND b.package_id = :package",
        Map.of("tenant", tenant, "package", bigPackage),
        "payment_batch_version_unique");
    driven(
        "prepared batch of a package",
        "SELECT EXISTS (SELECT 1 FROM payroll.payment_batch b WHERE b.tenant_id = :tenant"
            + " AND b.package_id = :package AND b.status = 'PREPARED')",
        Map.of("tenant", tenant, "package", bigPackage),
        "payment_batch_one_prepared");
    driven(
        "prepared batch of a package of the entity",
        "SELECT EXISTS (SELECT 1 FROM payroll.payment_batch b WHERE b.tenant_id = :tenant"
            + " AND b.legal_entity_id = :entity AND b.package_id = :package"
            + " AND b.status = 'PREPARED')",
        Map.of("tenant", tenant, "entity", entity, "package", bigPackage),
        "payment_batch_one_prepared");
    Map<String, Object> one = Map.of("tenant", tenant, "entity", entity, "id", preparedBatch);
    driven(
        "batch lock",
        "SELECT id FROM payroll.payment_batch WHERE tenant_id = :tenant"
            + " AND legal_entity_id = :entity AND id = :id FOR UPDATE",
        one,
        "payment_batch_tenant_id_unique",
        "payment_batch_pkey");
    String read =
        plan(
            "SELECT b.id, c.reason_code"
                + BATCH_JOIN
                + " WHERE b.tenant_id = :tenant AND b.legal_entity_id = :entity AND b.id = :id",
            Map.of("tenant", tenant, "entity", entity, "id", cancelledBatch));
    assertThat(read).contains("payment_batch_cancel_batch_unique");
    driven(
        "batch with its cancellation",
        "SELECT b.id, c.reason_code"
            + BATCH_JOIN
            + " WHERE b.tenant_id = :tenant AND b.legal_entity_id = :entity AND b.id = :id",
        Map.of("tenant", tenant, "entity", entity, "id", cancelledBatch),
        "payment_batch_tenant_id_unique",
        "payment_batch_pkey");
    driven(
        "cancellation of a batch",
        "SELECT c.reason_code FROM payroll.payment_batch_cancel c"
            + " WHERE c.tenant_id = :tenant AND c.batch_id = :id",
        Map.of("tenant", tenant, "id", cancelledBatch),
        "payment_batch_cancel_batch_unique");
  }

  @Test
  void theCancellingUpdateIsDrivenByTheBatchKey() {
    driven(
        "mark cancelled",
        "UPDATE payroll.payment_batch SET status = 'CANCELLED', cancelled_at = :at"
            + " WHERE tenant_id = :tenant AND legal_entity_id = :entity AND id = :id"
            + " AND status = 'PREPARED'",
        Map.of(
            "at",
            Timestamp.valueOf("2026-01-01 00:00:00"),
            "tenant",
            tenant,
            "entity",
            entity,
            "id",
            preparedBatch),
        "payment_batch_tenant_id_unique",
        "payment_batch_pkey");
  }

  @Test
  void theBatchListPagesNewestFirstInIndexOrderWithoutASort() {
    String select = "SELECT b.id, c.reason_code" + BATCH_JOIN;
    String where = " WHERE b.tenant_id = :tenant AND b.legal_entity_id = :entity";
    String order = " ORDER BY b.prepared_at DESC, b.id DESC LIMIT :limit";
    drivenInOrder(
        "first page",
        select + where + order,
        Map.of("tenant", tenant, "entity", entity, "limit", 26),
        "payment_batch_history");
    drivenInOrder(
        "keyset",
        select
            + where
            + " AND (b.prepared_at < :preparedAt OR (b.prepared_at = :preparedAt"
            + " AND b.id < :after))"
            + order,
        Map.of(
            "tenant",
            tenant,
            "entity",
            entity,
            "preparedAt",
            pageAt,
            "after",
            pageAfter,
            "limit",
            26),
        "payment_batch_history");
    drivenInOrder(
        "package filter",
        select + where + " AND b.package_id = :package" + order,
        Map.of("tenant", tenant, "entity", entity, "package", bigPackage, "limit", 26),
        "payment_batch_history");
    drivenInOrder(
        "status filter",
        select + where + " AND b.status = :status" + order,
        Map.of("tenant", tenant, "entity", entity, "status", "CANCELLED", "limit", 26),
        "payment_batch_history");
  }

  @Test
  void theLinePagesAndLookUpAreDrivenByTheLineIndexesInEmployeeOrderWithoutASort() {
    String page =
        "SELECT l.id FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
            + " AND l.legal_entity_id = :entity AND l.batch_id = :batch";
    drivenInOrder(
        "first page",
        page + " ORDER BY l.employee_id LIMIT :limit",
        Map.of("tenant", tenant, "entity", entity, "batch", preparedBatch, "limit", 51),
        "payment_batch_line_page");
    drivenInOrder(
        "keyset",
        page + " AND l.employee_id > :after ORDER BY l.employee_id LIMIT :limit",
        Map.of(
            "tenant",
            tenant,
            "entity",
            entity,
            "batch",
            preparedBatch,
            "after",
            employee,
            "limit",
            51),
        "payment_batch_line_page");
    // One line per employee and batch: any of the three indexes on the employee reaches it.
    driven(
        "line of an employee",
        page + " AND l.employee_id = :employee",
        Map.of("tenant", tenant, "entity", entity, "batch", preparedBatch, "employee", employee),
        "payment_batch_line_employee_unique",
        "payment_batch_line_page",
        "payment_batch_line_destination_fk");
  }
}
