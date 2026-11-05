package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
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
 * MVP-095C (Issue #190, ADR 0038): the plans of every statement of {@code
 * JdbcPaymentReconciliationRepository}: the reconciliation by submission, the existence check, the
 * locked read by ID, the paged evidence (first page and cursor page), one evidence row by sequence,
 * the worker's due claim, the expired lease recovery and the open count. Five tenants with 2,000
 * reconciliations each (mostly concluded, a few open and in progress) and six evidence rows each,
 * analyzed. Each path is driven by an existing index and never scans a reconciliation table
 * sequentially; the paged evidence, the due claim and the lease recovery need no sort. No index is
 * added. The plan text of every statement is printed for the pull request report.
 *
 * <p>The statements mirror the repository's (same predicates, orders and parameters; column lists
 * shortened). Rows are written set-based with {@code session_replication_role = replica}; the plan
 * depends on indexes and statistics only. Synthetic data only.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentReconciliationQueryPlanIntegrationTest {

  private static final int RECONCILIATIONS = 2_000;
  private static final Pattern SEQUENTIAL =
      Pattern.compile("Seq Scan on (payroll\\.)?payment_reconciliation(_inquiry)?\\b");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private UUID submission;
  private UUID reconciliation;

  private void seedTenant(UUID tenantId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE rec_seed ON COMMIT DROP AS SELECT g, gen_random_uuid() AS id,"
                      + " gen_random_uuid() AS submission_id, gen_random_uuid() AS batch_id,"
                      + " gen_random_uuid() AS request_id, gen_random_uuid() AS inquiry_id,"
                      + " CASE WHEN g % 200 = 1 THEN 'OPEN' WHEN g % 200 = 2 THEN"
                      + " 'INQUIRY_IN_PROGRESS' ELSE 'CONFIRMED_ACCEPTED_FOR_PROCESSING' END AS st"
                      + " FROM generate_series(1, "
                      + RECONCILIATIONS
                      + ") g");
              jdbc.update(
                  "INSERT INTO payroll.payment_reconciliation (tenant_id, legal_entity_id, id,"
                      + " submission_id, batch_id, request_id, provider_code, key_version,"
                      + " inquiry_id, opened_from_state, opened_from_version, state, version,"
                      + " attempt_count, outcome_code, next_attempt_at, lease_owner,"
                      + " lease_expires_at, understands_no_resend, understands_not_paid,"
                      + " created_at, created_by_subject, updated_at) SELECT ?, gen_random_uuid(),"
                      + " s.id, s.submission_id, s.batch_id, s.request_id, 'SYNTHETIC-TEST', 1,"
                      + " s.inquiry_id, 'DELIVERY_UNKNOWN', 1, s.st, 1,"
                      + " CASE WHEN s.st = 'OPEN' THEN 0 ELSE 1 END, CASE WHEN s.st ="
                      + " 'CONFIRMED_ACCEPTED_FOR_PROCESSING' THEN 'ACCEPTED_FOR_PROCESSING' END,"
                      + " CASE WHEN s.st = 'OPEN' THEN timestamptz '2020-01-01 00:00:00+00' +"
                      + " (s.g * interval '1 second') END, CASE WHEN s.st = 'INQUIRY_IN_PROGRESS'"
                      + " THEN 'worker-plan' END, CASE WHEN s.st = 'INQUIRY_IN_PROGRESS' THEN"
                      + " timestamptz '2020-01-01 00:00:00+00' + (s.g * interval '1 second') END,"
                      + " true, true, timestamptz '2020-01-01 00:00:00+00', 'rec-test',"
                      + " timestamptz '2020-01-01 00:00:00+00' FROM rec_seed s",
                  tenantId);
              jdbc.update(
                  "INSERT INTO payroll.payment_reconciliation_inquiry (tenant_id,"
                      + " reconciliation_id, seq, attempt_number, state, outcome_code,"
                      + " authenticated, occurred_at) SELECT ?, s.id, q.seq, CASE WHEN q.seq = 1"
                      + " THEN 0 ELSE 1 END, CASE WHEN q.seq = 1 THEN 'OPEN' ELSE"
                      + " 'INQUIRY_IN_PROGRESS' END, NULL, false, timestamptz '2020-01-01"
                      + " 00:00:00+00' + (q.seq * interval '1 second') FROM rec_seed s CROSS JOIN"
                      + " generate_series(1, 6) q(seq)",
                  tenantId);
            });
  }

  @BeforeAll
  void seed() {
    for (int other = 0; other < 4; other++) {
      seedTenant(UUID.randomUUID());
    }
    seedTenant(tenant);
    jdbc.execute("ANALYZE payroll.payment_reconciliation");
    jdbc.execute("ANALYZE payroll.payment_reconciliation_inquiry");
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id, submission_id FROM payroll.payment_reconciliation WHERE tenant_id = ?"
                + " AND state = 'CONFIRMED_ACCEPTED_FOR_PROCESSING' LIMIT 1",
            tenant);
    reconciliation = (UUID) row.get("id");
    submission = (UUID) row.get("submission_id");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.payment_reconciliation WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo((long) RECONCILIATIONS);
  }

  private String plan(String sql, Map<String, Object> params) {
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              jdbc.execute("SET LOCAL enable_bitmapscan = off");
              jdbc.execute("SET LOCAL enable_sort = off");
              return String.join(
                  "\n", named.query("EXPLAIN " + sql, params, (rs, r) -> rs.getString(1)));
            });
  }

  private String driven(String label, String sql, Map<String, Object> params, String... indexes) {
    String plan = plan(sql, params);
    System.out.println("PLAN[" + label + "]\n" + plan);
    assertThat(plan).as(label + "\n" + plan).containsAnyOf(indexes);
    assertThat(SEQUENTIAL.matcher(plan).find()).as(label + "\n" + plan).isFalse();
    return plan;
  }

  private static final String R = " FROM payroll.payment_reconciliation r WHERE ";

  @Test
  void theReconciliationOfASubmissionAndTheExistenceCheckAreDrivenByTheOnePerSubmissionKey() {
    driven(
        "by submission",
        "SELECT r.id" + R + "r.tenant_id = :tenant AND r.submission_id = :submission",
        Map.of("tenant", tenant, "submission", submission),
        "payment_reconciliation_one_per_submission");
    driven(
        "exists",
        "SELECT EXISTS (SELECT 1" + R + "r.tenant_id = :tenant AND r.submission_id = :submission)",
        Map.of("tenant", tenant, "submission", submission),
        "payment_reconciliation_one_per_submission");
  }

  @Test
  void theLockedReadByIdentifierIsDrivenByThePrimaryKey() {
    driven(
        "lock by id",
        "SELECT r.id" + R + "r.tenant_id = :tenant AND r.id = :id FOR UPDATE",
        Map.of("tenant", tenant, "id", reconciliation),
        "payment_reconciliation_pkey");
  }

  @Test
  void thePagedEvidenceIsDrivenByThePrimaryKeyInOrderWithoutASort() {
    String first =
        driven(
            "inquiry first page",
            "SELECT i.seq FROM payroll.payment_reconciliation_inquiry i WHERE i.tenant_id ="
                + " :tenant AND i.reconciliation_id = :reconciliation ORDER BY i.seq DESC LIMIT"
                + " :limit",
            Map.of("tenant", tenant, "reconciliation", reconciliation, "limit", 26),
            "payment_reconciliation_inquiry_pkey");
    assertThat(first).doesNotContain("Sort");
    String next =
        driven(
            "inquiry cursor page",
            "SELECT i.seq FROM payroll.payment_reconciliation_inquiry i WHERE i.tenant_id ="
                + " :tenant AND i.reconciliation_id = :reconciliation AND i.seq < :before ORDER BY"
                + " i.seq DESC LIMIT :limit",
            Map.of("tenant", tenant, "reconciliation", reconciliation, "before", 4, "limit", 26),
            "payment_reconciliation_inquiry_pkey");
    assertThat(next).doesNotContain("Sort");
  }

  @Test
  void oneEvidenceRowBySequenceIsDrivenByThePrimaryKey() {
    driven(
        "inquiry by seq",
        "SELECT i.seq FROM payroll.payment_reconciliation_inquiry i WHERE i.tenant_id = :tenant"
            + " AND i.reconciliation_id = :reconciliation AND i.seq = :seq",
        Map.of("tenant", tenant, "reconciliation", reconciliation, "seq", 3),
        "payment_reconciliation_inquiry_pkey");
  }

  @Test
  void theWorkerClaimAndTheLeaseRecoveryAreDrivenByTheirPartialIndexesWithoutASort() {
    String due =
        driven(
            "lock due",
            "SELECT r.id"
                + R
                + "r.state = 'OPEN' AND r.next_attempt_at <= :now"
                + " ORDER BY r.next_attempt_at, r.tenant_id, r.id LIMIT :limit FOR UPDATE SKIP"
                + " LOCKED",
            Map.of("now", java.sql.Timestamp.valueOf("2030-01-01 00:00:00"), "limit", 8),
            "payment_reconciliation_due");
    assertThat(due).doesNotContain("Sort");
    String lease =
        driven(
            "lock expired",
            "SELECT r.id"
                + R
                + "r.state = 'INQUIRY_IN_PROGRESS' AND r.lease_expires_at < :now"
                + " ORDER BY r.lease_expires_at, r.tenant_id, r.id LIMIT :limit FOR UPDATE SKIP"
                + " LOCKED",
            Map.of("now", java.sql.Timestamp.valueOf("2030-01-01 00:00:00"), "limit", 8),
            "payment_reconciliation_lease");
    assertThat(lease).doesNotContain("Sort");
  }

  @Test
  void theOpenCountIsServedByThePartialIndexesNotATableScan() {
    driven(
        "open count",
        "SELECT (SELECT count(*) FROM payroll.payment_reconciliation r WHERE r.state = 'OPEN')"
            + " + (SELECT count(*) FROM payroll.payment_reconciliation r WHERE r.state ="
            + " 'INQUIRY_IN_PROGRESS')",
        Map.of(),
        "payment_reconciliation_due",
        "payment_reconciliation_lease");
  }

  @Test
  void noIndexWasAddedBeyondTheMigration() {
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'payroll'"
                    + " AND tablename = 'payment_reconciliation' ORDER BY indexname",
                String.class))
        .containsExactlyInAnyOrder(
            "payment_reconciliation_pkey",
            "payment_reconciliation_one_per_submission",
            "payment_reconciliation_inquiry_id",
            "payment_reconciliation_due",
            "payment_reconciliation_lease");
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'payroll'"
                    + " AND tablename = 'payment_reconciliation_inquiry' ORDER BY indexname",
                String.class))
        .containsExactly("payment_reconciliation_inquiry_pkey");
  }
}
