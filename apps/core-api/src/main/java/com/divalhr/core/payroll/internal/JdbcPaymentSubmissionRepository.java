package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of provider submissions and their append-only evidence (MVP-095B; V48, ADR 0037). Every
 * method takes the verified {@link TenantId} and every statement filters on it, except the two
 * worker queries that claim due rows across tenants ({@link CrossTenantAccess}, each row then
 * handled under its own tenant). Rows are inserted once and then only change by a legal transition
 * recorded with its evidence row (the V48 triggers enforce it). No destination, signature, body or
 * provider text is ever stored or selected.
 */
@Repository
public class JdbcPaymentSubmissionRepository {

  private static final String COLUMNS =
      "s.tenant_id, s.legal_entity_id, s.id, s.batch_id, s.package_id, s.request_id, s.provider_code,"
          + " s.key_version, s.state, s.version, s.batch_version, s.batch_digest, s.currency,"
          + " s.line_count, s.bank_count, s.mobile_count, s.manual_count, s.total_net_minor,"
          + " s.attempt_count, s.outcome_code, s.provider_reference, s.next_attempt_at,"
          + " s.lease_owner, s.lease_expires_at, s.created_at, s.created_by_subject, s.updated_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcPaymentSubmissionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A stored submission.
   *
   * @param tenant owning tenant
   * @param legalEntityId legal entity
   * @param id submission
   * @param batchId batch
   * @param packageId package
   * @param requestId stable provider request ID
   * @param providerCode provider profile code
   * @param keyVersion signing key version
   * @param state closed state
   * @param version optimistic version (equals the latest evidence sequence)
   * @param batchVersion batch version
   * @param batchDigest batch digest
   * @param currency currency
   * @param lineCount lines
   * @param bank bank-account lines
   * @param mobile mobile-money lines
   * @param manual manual lines
   * @param totalNetMinor total net in minor units
   * @param attemptCount attempts claimed so far
   * @param outcomeCode allow-listed outcome code, or null
   * @param reference bounded provider reference, or null
   * @param nextAttemptAt when a QUEUED submission is due, or null
   * @param leaseOwner worker holding the lease, or null
   * @param leaseExpiresAt lease end, or null
   * @param createdAt creation instant
   * @param createdBy creating administrator
   * @param updatedAt last change
   */
  public record SubmissionRow(
      TenantId tenant,
      UUID legalEntityId,
      UUID id,
      UUID batchId,
      UUID packageId,
      UUID requestId,
      String providerCode,
      int keyVersion,
      String state,
      int version,
      int batchVersion,
      String batchDigest,
      String currency,
      int lineCount,
      int bank,
      int mobile,
      int manual,
      long totalNetMinor,
      int attemptCount,
      String outcomeCode,
      String reference,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseExpiresAt,
      Instant createdAt,
      String createdBy,
      Instant updatedAt) {
    @Override
    public String toString() {
      return "PaymentSubmissionRow[" + id + "]";
    }
  }

  /**
   * A submission to insert (QUEUED, version 1, no attempt).
   *
   * @param id new row
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param packageId package
   * @param requestId stable provider request ID
   * @param providerCode provider profile code
   * @param keyVersion signing key version
   * @param batchVersion batch version
   * @param batchDigest batch digest
   * @param currency currency
   * @param lineCount lines
   * @param bank bank-account lines
   * @param mobile mobile-money lines
   * @param manual manual lines
   * @param totalNetMinor total net in minor units
   * @param createdAt creation instant (also the first due time)
   * @param createdBy verified administrator
   */
  public record NewSubmission(
      UUID id,
      UUID legalEntityId,
      UUID batchId,
      UUID packageId,
      UUID requestId,
      String providerCode,
      int keyVersion,
      int batchVersion,
      String batchDigest,
      String currency,
      int lineCount,
      int bank,
      int mobile,
      int manual,
      long totalNetMinor,
      Instant createdAt,
      String createdBy) {}

  /**
   * The next state of a submission.
   *
   * @param state next state
   * @param attemptCount attempts after the change
   * @param outcomeCode outcome code, or null
   * @param reference provider reference, or null
   * @param nextAttemptAt next due time (QUEUED only), or null
   * @param leaseOwner lease owner (SENDING only), or null
   * @param leaseExpiresAt lease end (SENDING only), or null
   * @param at change instant
   */
  public record Next(
      String state,
      int attemptCount,
      String outcomeCode,
      String reference,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseExpiresAt,
      Instant at) {}

  /**
   * One evidence row.
   *
   * @param seq sequence (1 is the creation)
   * @param attemptNumber attempt number
   * @param state state
   * @param outcomeCode outcome code, or null
   * @param reference provider reference, or null
   * @param occurredAt instant
   */
  public record AttemptRow(
      int seq,
      int attemptNumber,
      String state,
      String outcomeCode,
      String reference,
      Instant occurredAt) {}

  /**
   * One line of a batch as needed to build the provider payload. Nothing sensitive in the clear.
   *
   * @param id batch line
   * @param employeeId employee
   * @param method method code
   * @param country ISO country, or null
   * @param providerCode destination provider code, or null
   * @param destinationId destination version row, or null
   * @param destinationSequence destination version number, or null
   * @param net net in minor units
   */
  public record PayloadLine(
      UUID id,
      UUID employeeId,
      String method,
      String country,
      String providerCode,
      UUID destinationId,
      Integer destinationSequence,
      long net) {
    @Override
    public String toString() {
      return "PaymentSubmissionPayloadLine[" + id + "]";
    }
  }

  // -------------------------------------------------------------------------------------------
  // Locks and reads
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the batch's submission lock (the key the V48 trigger takes).
   *
   * @param tenant verified tenant
   * @param batchId batch
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockSubmission(TenantId tenant, UUID batchId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.payment-submission:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:batch AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("batch", batchId)
        .query()
        .singleRow();
  }

  /**
   * The submission of a batch.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @return the submission, if any
   */
  public Optional<SubmissionRow> byBatch(TenantId tenant, UUID legalEntityId, UUID batchId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_submission s WHERE s.tenant_id = :tenant"
                + " AND s.legal_entity_id = :entity AND s.batch_id = :batch")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("batch", batchId)
        .query(JdbcPaymentSubmissionRepository::row)
        .optional();
  }

  /**
   * Whether a batch already has a submission (any state).
   *
   * @param tenant verified tenant
   * @param batchId batch
   * @return {@code true} when one exists
   */
  public boolean existsForBatch(TenantId tenant, UUID batchId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM payroll.payment_submission s"
                + " WHERE s.tenant_id = :tenant AND s.batch_id = :batch)")
        .param("tenant", tenant.value())
        .param("batch", batchId)
        .query(Boolean.class)
        .single();
  }

  /**
   * One submission by ID, locked {@code FOR UPDATE} for a transition.
   *
   * @param tenant verified tenant
   * @param id submission
   * @return the submission, if any
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<SubmissionRow> lockById(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_submission s WHERE s.tenant_id = :tenant AND s.id = :id"
                + " FOR UPDATE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcPaymentSubmissionRepository::row)
        .optional();
  }

  /**
   * The lines of a batch in line-ID order, as the payload needs them.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @return every line
   */
  public List<PayloadLine> payloadLines(TenantId tenant, UUID legalEntityId, UUID batchId) {
    return jdbc.sql(
            "SELECT l.id, l.employee_id, l.method, l.country_code, l.provider_code,"
                + " l.destination_id, l.destination_sequence, l.net_minor"
                + " FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
                + " AND l.legal_entity_id = :entity AND l.batch_id = :batch ORDER BY l.id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("batch", batchId)
        .query(
            (rs, row) -> {
              int sequence = rs.getInt("destination_sequence");
              Integer destinationSequence = rs.wasNull() ? null : sequence;
              return new PayloadLine(
                  rs.getObject("id", UUID.class),
                  rs.getObject("employee_id", UUID.class),
                  rs.getString("method"),
                  rs.getString("country_code"),
                  rs.getString("provider_code"),
                  rs.getObject("destination_id", UUID.class),
                  destinationSequence,
                  rs.getLong("net_minor"));
            })
        .list();
  }

  /**
   * A page of a submission's evidence, newest first.
   *
   * @param tenant verified tenant
   * @param submissionId submission
   * @param beforeSeq sequence of the last row of the previous page, or null for the first page
   * @param limit rows to read
   * @return the evidence rows
   */
  public List<AttemptRow> attemptPage(
      TenantId tenant, UUID submissionId, Integer beforeSeq, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT a.seq, a.attempt_number, a.state, a.outcome_code, a.provider_reference,"
                + " a.occurred_at FROM payroll.payment_submission_attempt a"
                + " WHERE a.tenant_id = :tenant AND a.submission_id = :submission");
    if (beforeSeq != null) {
      sql.append(" AND a.seq < :before");
    }
    sql.append(" ORDER BY a.seq DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenant.value())
            .param("submission", submissionId)
            .param("limit", limit);
    if (beforeSeq != null) {
      spec = spec.param("before", beforeSeq);
    }
    return spec.query(
            (rs, row) ->
                new AttemptRow(
                    rs.getInt("seq"),
                    rs.getInt("attempt_number"),
                    rs.getString("state"),
                    rs.getString("outcome_code"),
                    rs.getString("provider_reference"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /**
   * One evidence row by sequence (cursor positions).
   *
   * @param tenant verified tenant
   * @param submissionId submission
   * @param seq sequence
   * @return the row, if any
   */
  public Optional<AttemptRow> attempt(TenantId tenant, UUID submissionId, int seq) {
    return jdbc.sql(
            "SELECT a.seq, a.attempt_number, a.state, a.outcome_code, a.provider_reference,"
                + " a.occurred_at FROM payroll.payment_submission_attempt a"
                + " WHERE a.tenant_id = :tenant AND a.submission_id = :submission AND a.seq = :seq")
        .param("tenant", tenant.value())
        .param("submission", submissionId)
        .param("seq", seq)
        .query(
            (rs, row) ->
                new AttemptRow(
                    rs.getInt("seq"),
                    rs.getInt("attempt_number"),
                    rs.getString("state"),
                    rs.getString("outcome_code"),
                    rs.getString("provider_reference"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .optional();
  }

  // -------------------------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------------------------

  /**
   * Inserts a QUEUED submission and its first evidence row.
   *
   * @param tenant verified tenant
   * @param row the submission
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, NewSubmission row) {
    jdbc.sql(
            "INSERT INTO payroll.payment_submission (tenant_id, legal_entity_id, id, batch_id,"
                + " package_id, request_id, provider_code, key_version, state, version,"
                + " batch_version, batch_digest, currency, line_count, bank_count, mobile_count,"
                + " manual_count, total_net_minor, attempt_count, next_attempt_at,"
                + " reviewed_provider_totals, understands_not_paid, created_at,"
                + " created_by_subject, updated_at) VALUES (:tenant, :entity, :id, :batch,"
                + " :package, :request, :provider, :keyVersion, 'QUEUED', 1, :batchVersion,"
                + " :digest, :currency, :lines, :bank, :mobile, :manual, :total, 0, :at, TRUE,"
                + " TRUE, :at, :by, :at)")
        .param("tenant", tenant.value())
        .param("entity", row.legalEntityId())
        .param("id", row.id())
        .param("batch", row.batchId())
        .param("package", row.packageId())
        .param("request", row.requestId())
        .param("provider", row.providerCode())
        .param("keyVersion", row.keyVersion())
        .param("batchVersion", row.batchVersion())
        .param("digest", row.batchDigest())
        .param("currency", row.currency())
        .param("lines", row.lineCount())
        .param("bank", row.bank())
        .param("mobile", row.mobile())
        .param("manual", row.manual())
        .param("total", row.totalNetMinor())
        .param("at", Timestamp.from(row.createdAt()))
        .param("by", row.createdBy())
        .update();
    insertEvidence(tenant, row.id(), 1, 0, "QUEUED", null, null, row.createdAt());
  }

  /**
   * Applies one legal transition and appends its evidence row, only when the stored version is the
   * one the caller read.
   *
   * @param tenant verified tenant
   * @param current the row as read (under its lock)
   * @param next the next state
   * @return {@code false} when the version moved, in which case nothing was written
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean transition(TenantId tenant, SubmissionRow current, Next next) {
    int version = current.version() + 1;
    int updated =
        jdbc.sql(
                "UPDATE payroll.payment_submission SET state = :state, version = :version,"
                    + " attempt_count = :attempts, outcome_code = :outcome,"
                    + " provider_reference = :reference, next_attempt_at = :next,"
                    + " lease_owner = :owner, lease_expires_at = :lease, updated_at = :at"
                    + " WHERE tenant_id = :tenant AND id = :id AND version = :expected")
            .param("state", next.state())
            .param("version", version)
            .param("attempts", next.attemptCount())
            .param("outcome", next.outcomeCode())
            .param("reference", next.reference())
            .param("next", timestamp(next.nextAttemptAt()))
            .param("owner", next.leaseOwner())
            .param("lease", timestamp(next.leaseExpiresAt()))
            .param("at", Timestamp.from(next.at()))
            .param("tenant", tenant.value())
            .param("id", current.id())
            .param("expected", current.version())
            .update();
    if (updated != 1) {
      return false;
    }
    insertEvidence(
        tenant,
        current.id(),
        version,
        next.attemptCount(),
        next.state(),
        next.outcomeCode(),
        next.reference(),
        next.at());
    return true;
  }

  private void insertEvidence(
      TenantId tenant,
      UUID submissionId,
      int seq,
      int attemptNumber,
      String state,
      String outcomeCode,
      String reference,
      Instant at) {
    jdbc.sql(
            "INSERT INTO payroll.payment_submission_attempt (tenant_id, submission_id, seq,"
                + " attempt_number, state, outcome_code, provider_reference, occurred_at)"
                + " VALUES (:tenant, :submission, :seq, :attempt, :state, :outcome, :reference,"
                + " :at)")
        .param("tenant", tenant.value())
        .param("submission", submissionId)
        .param("seq", seq)
        .param("attempt", attemptNumber)
        .param("state", state)
        .param("outcome", outcomeCode)
        .param("reference", reference)
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Runs the deferred V48 exactness checks now, inside the caller's transaction, so a violation
   * surfaces where it is mapped by constraint name rather than at commit. Call it last: the
   * checks stay immediate for the rest of the transaction.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payment_submission_exact,"
                + " payroll.payment_submission_attempt_exact IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Worker (cross-tenant claims; each row is then handled under its own tenant)
  // -------------------------------------------------------------------------------------------

  /**
   * Locks due QUEUED submissions, skipping rows other workers hold.
   *
   * @param now current instant
   * @param limit most rows
   * @return the locked rows, oldest due first
   */
  @CrossTenantAccess("the delivery worker claims due submissions of every tenant")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<SubmissionRow> lockDue(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_submission s WHERE s.state = 'QUEUED'"
                + " AND s.next_attempt_at <= :now ORDER BY s.next_attempt_at, s.tenant_id, s.id"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcPaymentSubmissionRepository::row)
        .list();
  }

  /**
   * Locks SENDING submissions whose lease has expired (a crashed worker), skipping held rows.
   *
   * @param now current instant
   * @param limit most rows
   * @return the locked rows, oldest lease first
   */
  @CrossTenantAccess("the delivery worker recovers expired leases of every tenant")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<SubmissionRow> lockExpired(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_submission s WHERE s.state = 'SENDING'"
                + " AND s.lease_expires_at < :now ORDER BY s.lease_expires_at, s.tenant_id, s.id"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcPaymentSubmissionRepository::row)
        .list();
  }

  /**
   * The number of submissions still being delivered (QUEUED or SENDING), for the queue-depth gauge.
   *
   * @return the count across tenants
   */
  @CrossTenantAccess("the queue-depth gauge counts open submissions of every tenant")
  public int openCount() {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM payroll.payment_submission s WHERE s.state = 'QUEUED')"
                + " + (SELECT count(*) FROM payroll.payment_submission s WHERE s.state ="
                + " 'SENDING')")
        .query(Integer.class)
        .single();
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static SubmissionRow row(ResultSet rs, int rowNumber) throws SQLException {
    Timestamp next = rs.getTimestamp("next_attempt_at");
    Timestamp lease = rs.getTimestamp("lease_expires_at");
    return new SubmissionRow(
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getObject("package_id", UUID.class),
        rs.getObject("request_id", UUID.class),
        rs.getString("provider_code"),
        rs.getInt("key_version"),
        rs.getString("state"),
        rs.getInt("version"),
        rs.getInt("batch_version"),
        rs.getString("batch_digest"),
        rs.getString("currency"),
        rs.getInt("line_count"),
        rs.getInt("bank_count"),
        rs.getInt("mobile_count"),
        rs.getInt("manual_count"),
        rs.getLong("total_net_minor"),
        rs.getInt("attempt_count"),
        rs.getString("outcome_code"),
        rs.getString("provider_reference"),
        next == null ? null : next.toInstant(),
        rs.getString("lease_owner"),
        lease == null ? null : lease.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by_subject"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
