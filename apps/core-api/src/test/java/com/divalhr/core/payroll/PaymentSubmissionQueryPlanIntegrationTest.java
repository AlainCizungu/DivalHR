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
 * <p>R188-1C: the payload-line read ({@code payloadLines()}: tenant, entity and batch predicate,
 * {@code ORDER BY l.id}) is covered here too, over two 5,000-line batches of the tenant (the batch
 * cap) beside other tenants' batches. It runs with the planner's sort left enabled and must be
 * served in order by {@code payment_batch_line_pkey (tenant_id, batch_id, id)} with no sort node
 * and no sequential scan; the 5,000-line cap is not what makes it acceptable.
 *
 * <p>The statements mirror the repository's (same predicates, orders and parameters; column lists
 * shortened). Rows are written set-based with {@code session_replication_role = replica}; the plan
 * depends on indexes and statistics only. Synthetic data only.
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
  private UUID lineBatch;
  private UUID otherLineBatch;
  private static final int LINES = 5_000;

  private void seedLines(UUID tenantId, UUID entityId, UUID batchId) {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SET LOCAL session_replication_role = replica");
              jdbc.update(
                  "INSERT INTO payroll.payment_batch_line (tenant_id, legal_entity_id, batch_id,"
                      + " package_id, id, payslip_id, employee_id, employee_number, method,"
                      + " country_code, provider_code, provider_name, masked_value, destination_id,"
                      + " destination_sequence, net_minor) SELECT ?, ?, ?, gen_random_uuid(),"
                      + " gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
                      + " 'E' || lpad(g::text, 6, '0'), 'BANK_ACCOUNT', 'CD', 'SYNTHETIC-BANK',"
                      + " 'Synthetic bank', '****' || lpad((g % 10000)::text, 4, '0'),"
                      + " gen_random_uuid(), 1, 92000 FROM generate_series(1, "
                      + LINES
                      + ") g",
                  tenantId,
                  entityId,
                  batchId);
            });
  }

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
    lineBatch = UUID.randomUUID();
    otherLineBatch = UUID.randomUUID();
    seedLines(tenant, entity, lineBatch);
    seedLines(tenant, entity, otherLineBatch);
    // Many other batches, so the batch under test is a small fraction of the table (about 5%).
    for (int other = 0; other < 38; other++) {
      seedLines(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
    jdbc.execute("ANALYZE payroll.payment_batch_line");
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

  /** Only bitmap scans are disabled: the planner stays free to sort, so a sort would show. */
  private String planWithSortAllowed(String sql, Map<String, Object> params) {
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              jdbc.execute("SET LOCAL enable_bitmapscan = off");
              return String.join(
                  "\n", named.query("EXPLAIN " + sql, params, (rs, r) -> rs.getString(1)));
            });
  }

  @Test
  void thePayloadLineReadIsServedInOrderByThePrimaryKeyWithoutASortOrASequentialScan() {
    String sql =
        "SELECT l.id, l.employee_id, l.method, l.country_code, l.provider_code, l.destination_id,"
            + " l.destination_sequence, l.net_minor FROM payroll.payment_batch_line l"
            + " WHERE l.tenant_id = :tenant AND l.legal_entity_id = :entity"
            + " AND l.batch_id = :batch ORDER BY l.id";
    String plan =
        planWithSortAllowed(sql, Map.of("tenant", tenant, "entity", entity, "batch", lineBatch));
    assertThat(plan).as(plan).contains("payment_batch_line_pkey");
    assertThat(plan).as(plan).doesNotContain("Sort");
    assertThat(plan).as(plan).doesNotContain("Seq Scan");
  }

  @Test
  void thePayloadLineReadReturnsOnlyTheBatchOfTheTenantInIdOrder() {
    java.util.List<UUID> ids =
        named.query(
            "SELECT l.id FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
                + " AND l.legal_entity_id = :entity AND l.batch_id = :batch ORDER BY l.id",
            Map.of("tenant", tenant, "entity", entity, "batch", lineBatch),
            (rs, r) -> rs.getObject(1, UUID.class));
    assertThat(ids).hasSize(LINES).doesNotHaveDuplicates();
    // PostgreSQL orders uuid bytewise, which is the order of the lowercase hex text (not of
    // java.util.UUID's signed comparison).
    assertThat(ids.stream().map(UUID::toString).toList()).isSorted();
    // Another tenant asking for the same batch, or the right tenant with another entity, sees none.
    assertThat(
            named.queryForObject(
                "SELECT count(*) FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
                    + " AND l.legal_entity_id = :entity AND l.batch_id = :batch",
                Map.of("tenant", UUID.randomUUID(), "entity", entity, "batch", lineBatch),
                Long.class))
        .isZero();
    assertThat(
            named.queryForObject(
                "SELECT count(*) FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
                    + " AND l.legal_entity_id = :entity AND l.batch_id = :batch",
                Map.of("tenant", tenant, "entity", UUID.randomUUID(), "batch", lineBatch),
                Long.class))
        .isZero();
    assertThat(otherLineBatch).isNotEqualTo(lineBatch);
  }

  @Test
  void noIndexWasAddedBeyondTheMigration() {
    assertThat(
            jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'payroll'"
                    + " AND tablename = 'payment_batch_line' ORDER BY indexname",
                String.class))
        .containsExactlyInAnyOrder(
            "payment_batch_line_pkey",
            "payment_batch_line_employee_unique",
            "payment_batch_line_payslip_unique",
            "payment_batch_line_page",
            "payment_batch_line_payslip_fk",
            "payment_batch_line_destination_fk");
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
