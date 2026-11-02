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
 * MVP-095B (Issue #187, ADR 0037): the plans of every statement of {@code
 * JdbcPaymentSubmissionRepository}: the submission by batch, the existence check, the locked read
 * by ID, the paged evidence, the worker's due claim, the expired lease recovery and the queue-depth
 * count. Five tenants with 2,000 submissions each (mostly finished, a few queued and sending),
 * analyzed. Each path is driven by an existing index and never scans a submission table
 * sequentially; the paged evidence needs no sort. No index is added.
 *
 * <p>The statements mirror the repository's (same predicates, orders and parameters; column lists
 * shortened). The batch-line read of the payload uses the 095A batch-line indexes (covered by
 * {@link PaymentQueryPlanIntegrationTest}; it is bounded by the 5,000-line cap). Rows are written
 * set-based with {@code session_replication_role = replica}; the plan depends on indexes and
 * statistics only. Synthetic data only.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentSubmissionQueryPlanIntegrationTest {

  private static final int SUBMISSIONS = 2_000;
  private static final Pattern SEQUENTIAL =
      Pattern.compile("Seq Scan on (payroll\\.)?payment_submission");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;

  private final UUID tenant = UUID.randomUUID();
  private final UUID entity = UUID.randomUUID();
  private UUID batch;
  private UUID submission;

  private void seedTenant(UUID tenantId, UUID entityId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.execute(
                  "CREATE TEMP TABLE sub_seed ON COMMIT DROP AS SELECT g, gen_random_uuid() AS id,"
                      + " gen_random_uuid() AS batch_id, gen_random_uuid() AS request_id,"
                      + " CASE WHEN g % 200 = 1 THEN 'QUEUED' WHEN g % 200 = 2 THEN 'SENDING'"
                      + " ELSE 'ACCEPTED_FOR_PROCESSING' END AS st FROM generate_series(1, "
                      + SUBMISSIONS
                      + ") g");
              jdbc.update(
                  "INSERT INTO payroll.payment_submission (tenant_id, legal_entity_id, id,"
                      + " batch_id, package_id, request_id, provider_code, key_version, state,"
                      + " version, batch_version, batch_digest, currency, line_count, bank_count,"
                      + " mobile_count, manual_count, total_net_minor, attempt_count, outcome_code,"
                      + " provider_reference, next_attempt_at, lease_owner, lease_expires_at,"
                      + " reviewed_provider_totals, understands_not_paid, created_at,"
                      + " created_by_subject, updated_at) SELECT ?, ?, s.id, s.batch_id,"
                      + " gen_random_uuid(), s.request_id, 'SYNTHETIC-TEST', 1, s.st, 1, 1,"
                      + " repeat('a', 64), 'CDF', 1, 1, 0, 0, 92000,"
                      + " CASE WHEN s.st = 'QUEUED' THEN 0 ELSE 1 END, CASE WHEN s.st ="
                      + " 'ACCEPTED_FOR_PROCESSING' THEN 'ACCEPTED' END, CASE WHEN s.st ="
                      + " 'ACCEPTED_FOR_PROCESSING' THEN 'SYNTH-' || lpad(s.g::text, 8, '0') END,"
                      + " CASE WHEN s.st = 'QUEUED' THEN timestamptz '2020-01-01 00:00:00+00' +"
                      + " (s.g * interval '1 second') END, CASE WHEN s.st = 'SENDING' THEN"
                      + " 'worker-plan' END, CASE WHEN s.st = 'SENDING' THEN timestamptz"
                      + " '2020-01-01 00:00:00+00' + (s.g * interval '1 second') END, true, true,"
                      + " timestamptz '2020-01-01 00:00:00+00', 'sub-test', timestamptz"
                      + " '2020-01-01 00:00:00+00' FROM sub_seed s",
                  tenantId,
                  entityId);
              jdbc.update(
                  "INSERT INTO payroll.payment_submission_attempt (tenant_id, submission_id, seq,"
                      + " attempt_number, state, outcome_code, provider_reference, occurred_at)"
                      + " SELECT ?, s.id, q.seq, CASE WHEN q.seq = 1 THEN 0 ELSE q.seq - 1 END,"
                      + " CASE WHEN q.seq = 1 THEN 'QUEUED' ELSE 'SENDING' END, NULL, NULL,"
                      + " timestamptz '2020-01-01 00:00:00+00' + (q.seq * interval '1 second')"
                      + " FROM sub_seed s CROSS JOIN generate_series(1, 6) q(seq)",
                  tenantId);
            });
  }

  @BeforeAll
  void seed() {
    for (int other = 0; other < 4; other++) {
      seedTenant(UUID.randomUUID(), UUID.randomUUID());
    }
    seedTenant(tenant, entity);
    jdbc.execute("ANALYZE payroll.payment_submission");
    jdbc.execute("ANALYZE payroll.payment_submission_attempt");
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT id, batch_id FROM payroll.payment_submission WHERE tenant_id = ?"
                + " AND state = 'ACCEPTED_FOR_PROCESSING' LIMIT 1",
            tenant);
    submission = (UUID) row.get("id");
    batch = (UUID) row.get("batch_id");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.payment_submission WHERE tenant_id = ?",
                Long.class,
                tenant))
        .isEqualTo((long) SUBMISSIONS);
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
    assertThat(plan).as(label + "\n" + plan).containsAnyOf(indexes);
    assertThat(SEQUENTIAL.matcher(plan).find()).as(label + "\n" + plan).isFalse();
    return plan;
  }

  private static final String S = " FROM payroll.payment_submission s WHERE ";

  @Test
  void theSubmissionOfABatchAndTheExistenceCheckAreDrivenByTheOnePerBatchKey() {
    Map<String, Object> params = Map.of("tenant", tenant, "entity", entity, "batch", batch);
    driven(
        "by batch",
        "SELECT s.id"
            + S
            + "s.tenant_id = :tenant AND s.legal_entity_id = :entity"
            + " AND s.batch_id = :batch",
        params,
        "payment_submission_one_per_batch");
    driven(
        "exists",
        "SELECT EXISTS (SELECT 1" + S + "s.tenant_id = :tenant AND s.batch_id = :batch)",
        Map.of("tenant", tenant, "batch", batch),
        "payment_submission_one_per_batch");
  }

  @Test
  void theLockedReadByIdentifierIsDrivenByThePrimaryKey() {
    driven(
        "lock by id",
        "SELECT s.id" + S + "s.tenant_id = :tenant AND s.id = :id FOR UPDATE",
        Map.of("tenant", tenant, "id", submission),
        "payment_submission_pkey");
  }

  @Test
  void thePagedEvidenceIsDrivenByThePrimaryKeyInOrderWithoutASort() {
    String first =
        driven(
            "first page",
            "SELECT a.seq FROM payroll.payment_submission_attempt a WHERE a.tenant_id = :tenant"
                + " AND a.submission_id = :submission ORDER BY a.seq DESC LIMIT :limit",
            Map.of("tenant", tenant, "submission", submission, "limit", 26),
            "payment_submission_attempt_pkey");
    assertThat(first).doesNotContain("Sort");
    String next =
        driven(
            "next page",
            "SELECT a.seq FROM payroll.payment_submission_attempt a WHERE a.tenant_id = :tenant"
                + " AND a.submission_id = :submission AND a.seq < :before ORDER BY a.seq DESC"
                + " LIMIT :limit",
            Map.of("tenant", tenant, "submission", submission, "before", 4, "limit", 26),
            "payment_submission_attempt_pkey");
    assertThat(next).doesNotContain("Sort");
  }

  @Test
  void theWorkerClaimAndTheLeaseRecoveryAreDrivenByTheirPartialIndexes() {
    String due =
        driven(
            "due",
            "SELECT s.id"
                + S
                + "s.state = 'QUEUED' AND s.next_attempt_at <= :now"
                + " ORDER BY s.next_attempt_at, s.tenant_id, s.id LIMIT :limit FOR UPDATE SKIP LOCKED",
            Map.of("now", java.sql.Timestamp.valueOf("2030-01-01 00:00:00"), "limit", 8),
            "payment_submission_due");
    assertThat(due).doesNotContain("Sort");
    String lease =
        driven(
            "lease",
            "SELECT s.id"
                + S
                + "s.state = 'SENDING' AND s.lease_expires_at < :now"
                + " ORDER BY s.lease_expires_at, s.tenant_id, s.id LIMIT :limit FOR UPDATE SKIP LOCKED",
            Map.of("now", java.sql.Timestamp.valueOf("2030-01-01 00:00:00"), "limit", 8),
            "payment_submission_lease");
    assertThat(lease).doesNotContain("Sort");
  }

  @Test
  void theQueueDepthCountIsServedByThePartialIndexesNotATableScan() {
    driven(
        "open count",
        "SELECT (SELECT count(*) FROM payroll.payment_submission s WHERE s.state = 'QUEUED')"
            + " + (SELECT count(*) FROM payroll.payment_submission s WHERE s.state = 'SENDING')",
        Map.of(),
        "payment_submission_due",
        "payment_submission_lease");
  }

  @Test
  void noIndexWasAddedBeyondTheMigration() {
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'payroll'"
                    + " AND tablename = 'payment_submission' ORDER BY indexname",
                String.class))
        .containsExactlyInAnyOrder(
            "payment_submission_pkey",
            "payment_submission_one_per_batch",
            "payment_submission_request_id",
            "payment_submission_due",
            "payment_submission_lease",
            "payment_submission_state");
  }
}
