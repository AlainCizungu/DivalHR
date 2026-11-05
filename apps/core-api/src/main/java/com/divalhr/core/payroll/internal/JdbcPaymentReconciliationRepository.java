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
 * Storage of payment reconciliations and their append-only inquiry evidence (MVP-095C; V49, ADR
 * 0038). Every method takes the verified {@link TenantId} and every statement filters on it, except
 * the two worker queries that claim due rows across tenants and the open-count gauge ({@link
 * CrossTenantAccess}, each row then handled under its own tenant). Rows are inserted once and then
 * only change by a legal transition recorded with its evidence row (the V49 triggers enforce it).
 * Nothing here reads or writes the payment submission tables, so a reconciliation cannot change a
 * submission, its attempts or its request ID. No destination, amount, signature, body or provider
 * text is ever stored or selected.
 */
@Repository
public class JdbcPaymentReconciliationRepository {

  private static final String COLUMNS =
      "r.tenant_id, r.legal_entity_id, r.id, r.submission_id, r.batch_id, r.request_id,"
          + " r.provider_code, r.key_version, r.inquiry_id, r.opened_from_state,"
          + " r.opened_from_version, r.state, r.version, r.attempt_count, r.outcome_code,"
          + " r.next_attempt_at, r.lease_owner, r.lease_expires_at, r.created_at,"
          + " r.created_by_subject, r.updated_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcPaymentReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A stored reconciliation.
   *
   * @param tenant owning tenant
   * @param legalEntityId legal entity
   * @param id reconciliation
   * @param submissionId the investigated submission
   * @param batchId batch
   * @param requestId the submission's stable payment request ID (never changed, never shown)
   * @param providerCode provider profile code
   * @param keyVersion signing key version
   * @param inquiryId the stable inquiry ID of every attempt
   * @param openedFromState the submission state when the investigation was opened
   * @param openedFromVersion the submission version when the investigation was opened
   * @param state closed state
   * @param version optimistic version (equals the latest evidence sequence)
   * @param attemptCount inquiry attempts claimed so far
   * @param outcomeCode allow-listed outcome code, or null
   * @param nextAttemptAt when an OPEN reconciliation is due, or null
   * @param leaseOwner worker holding the lease, or null
   * @param leaseExpiresAt lease end, or null
   * @param createdAt creation instant
   * @param createdBy creating administrator
   * @param updatedAt last change
   */
  public record ReconciliationRow(
      TenantId tenant,
      UUID legalEntityId,
      UUID id,
      UUID submissionId,
      UUID batchId,
      UUID requestId,
      String providerCode,
      int keyVersion,
      UUID inquiryId,
      String openedFromState,
      int openedFromVersion,
      String state,
      int version,
      int attemptCount,
      String outcomeCode,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseExpiresAt,
      Instant createdAt,
      String createdBy,
      Instant updatedAt) {
    @Override
    public String toString() {
      return "PaymentReconciliationRow[" + id + "]";
    }
  }

  /**
   * A reconciliation to insert (OPEN, version 1, no attempt).
   *
   * @param id new row
   * @param legalEntityId legal entity
   * @param submissionId submission
   * @param batchId batch
   * @param requestId the submission's payment request ID
   * @param providerCode provider profile code
   * @param keyVersion signing key version
   * @param inquiryId new stable inquiry ID
   * @param openedFromState the submission state
   * @param openedFromVersion the submission version
   * @param createdAt creation instant (also the first due time)
   * @param createdBy verified administrator
   */
  public record NewReconciliation(
      UUID id,
      UUID legalEntityId,
      UUID submissionId,
      UUID batchId,
      UUID requestId,
      String providerCode,
      int keyVersion,
      UUID inquiryId,
      String openedFromState,
      int openedFromVersion,
      Instant createdAt,
      String createdBy) {
    @Override
    public String toString() {
      return "NewPaymentReconciliation[" + id + "]";
    }
  }

  /**
   * The next state of a reconciliation.
   *
   * @param state next state
   * @param attemptCount attempts after the change
   * @param outcomeCode outcome code, or null
   * @param authenticated whether the evidence rests on a verified provider answer
   * @param nextAttemptAt next due time (OPEN only), or null
   * @param leaseOwner lease owner (INQUIRY_IN_PROGRESS only), or null
   * @param leaseExpiresAt lease end (INQUIRY_IN_PROGRESS only), or null
   * @param at change instant
   */
  public record Next(
      String state,
      int attemptCount,
      String outcomeCode,
      boolean authenticated,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseExpiresAt,
      Instant at) {}

  /**
   * One evidence row.
   *
   * @param seq sequence (1 is the opening)
   * @param attemptNumber inquiry attempt number
   * @param state state
   * @param outcomeCode outcome code, or null
   * @param authenticated whether the answer carried a valid signature
   * @param occurredAt instant
   */
  public record InquiryRow(
      int seq,
      int attemptNumber,
      String state,
      String outcomeCode,
      boolean authenticated,
      Instant occurredAt) {}

  // -------------------------------------------------------------------------------------------
  // Locks and reads
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the submission's reconciliation lock (the key the V49 trigger takes).
   *
   * @param tenant verified tenant
   * @param submissionId submission
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockReconciliation(TenantId tenant, UUID submissionId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.payment-reconciliation:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:submission AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("submission", submissionId)
        .query()
        .singleRow();
  }

  /**
   * The reconciliation of a submission.
   *
   * @param tenant verified tenant
   * @param submissionId submission
   * @return the reconciliation, if any
   */
  public Optional<ReconciliationRow> bySubmission(TenantId tenant, UUID submissionId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_reconciliation r WHERE r.tenant_id = :tenant"
                + " AND r.submission_id = :submission")
        .param("tenant", tenant.value())
        .param("submission", submissionId)
        .query(JdbcPaymentReconciliationRepository::row)
        .optional();
  }

  /**
   * Whether a submission already has a reconciliation (any state).
   *
   * @param tenant verified tenant
   * @param submissionId submission
   * @return {@code true} when one exists
   */
  public boolean existsForSubmission(TenantId tenant, UUID submissionId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM payroll.payment_reconciliation r"
                + " WHERE r.tenant_id = :tenant AND r.submission_id = :submission)")
        .param("tenant", tenant.value())
        .param("submission", submissionId)
        .query(Boolean.class)
        .single();
  }

  /**
   * One reconciliation by ID, locked {@code FOR UPDATE} for a transition.
   *
   * @param tenant verified tenant
   * @param id reconciliation
   * @return the reconciliation, if any
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ReconciliationRow> lockById(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_reconciliation r WHERE r.tenant_id = :tenant"
                + " AND r.id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcPaymentReconciliationRepository::row)
        .optional();
  }

  /**
   * A page of a reconciliation's evidence, newest first.
   *
   * @param tenant verified tenant
   * @param reconciliationId reconciliation
   * @param beforeSeq sequence of the last row of the previous page, or null for the first page
   * @param limit rows to read
   * @return the evidence rows
   */
  public List<InquiryRow> inquiryPage(
      TenantId tenant, UUID reconciliationId, Integer beforeSeq, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT i.seq, i.attempt_number, i.state, i.outcome_code, i.authenticated,"
                + " i.occurred_at FROM payroll.payment_reconciliation_inquiry i"
                + " WHERE i.tenant_id = :tenant AND i.reconciliation_id = :reconciliation");
    if (beforeSeq != null) {
      sql.append(" AND i.seq < :before");
    }
    sql.append(" ORDER BY i.seq DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenant.value())
            .param("reconciliation", reconciliationId)
            .param("limit", limit);
    if (beforeSeq != null) {
      spec = spec.param("before", beforeSeq);
    }
    return spec.query(JdbcPaymentReconciliationRepository::inquiryRow).list();
  }

  /**
   * One evidence row by sequence (cursor positions).
   *
   * @param tenant verified tenant
   * @param reconciliationId reconciliation
   * @param seq sequence
   * @return the row, if any
   */
  public Optional<InquiryRow> inquiry(TenantId tenant, UUID reconciliationId, int seq) {
    return jdbc.sql(
            "SELECT i.seq, i.attempt_number, i.state, i.outcome_code, i.authenticated,"
                + " i.occurred_at FROM payroll.payment_reconciliation_inquiry i"
                + " WHERE i.tenant_id = :tenant AND i.reconciliation_id = :reconciliation"
                + " AND i.seq = :seq")
        .param("tenant", tenant.value())
        .param("reconciliation", reconciliationId)
        .param("seq", seq)
        .query(JdbcPaymentReconciliationRepository::inquiryRow)
        .optional();
  }

  // -------------------------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------------------------

  /**
   * Inserts an OPEN reconciliation and its opening evidence row.
   *
   * @param tenant verified tenant
   * @param row the reconciliation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, NewReconciliation row) {
    jdbc.sql(
            "INSERT INTO payroll.payment_reconciliation (tenant_id, legal_entity_id, id,"
                + " submission_id, batch_id, request_id, provider_code, key_version, inquiry_id,"
                + " opened_from_state, opened_from_version, state, version, attempt_count,"
                + " next_attempt_at, understands_no_resend, understands_not_paid, created_at,"
                + " created_by_subject, updated_at) VALUES (:tenant, :entity, :id, :submission,"
                + " :batch, :request, :provider, :keyVersion, :inquiry, :fromState,"
                + " :fromVersion, 'OPEN', 1, 0, :at, TRUE, TRUE, :at, :by, :at)")
        .param("tenant", tenant.value())
        .param("entity", row.legalEntityId())
        .param("id", row.id())
        .param("submission", row.submissionId())
        .param("batch", row.batchId())
        .param("request", row.requestId())
        .param("provider", row.providerCode())
        .param("keyVersion", row.keyVersion())
        .param("inquiry", row.inquiryId())
        .param("fromState", row.openedFromState())
        .param("fromVersion", row.openedFromVersion())
        .param("at", Timestamp.from(row.createdAt()))
        .param("by", row.createdBy())
        .update();
    insertEvidence(tenant, row.id(), 1, 0, "OPEN", null, false, row.createdAt());
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
  public boolean transition(TenantId tenant, ReconciliationRow current, Next next) {
    int version = current.version() + 1;
    int updated =
        jdbc.sql(
                "UPDATE payroll.payment_reconciliation SET state = :state, version = :version,"
                    + " attempt_count = :attempts, outcome_code = :outcome,"
                    + " next_attempt_at = :next, lease_owner = :owner,"
                    + " lease_expires_at = :lease, updated_at = :at"
                    + " WHERE tenant_id = :tenant AND id = :id AND version = :expected")
            .param("state", next.state())
            .param("version", version)
            .param("attempts", next.attemptCount())
            .param("outcome", next.outcomeCode())
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
        next.authenticated(),
        next.at());
    return true;
  }

  private void insertEvidence(
      TenantId tenant,
      UUID reconciliationId,
      int seq,
      int attemptNumber,
      String state,
      String outcomeCode,
      boolean authenticated,
      Instant at) {
    jdbc.sql(
            "INSERT INTO payroll.payment_reconciliation_inquiry (tenant_id, reconciliation_id,"
                + " seq, attempt_number, state, outcome_code, authenticated, occurred_at)"
                + " VALUES (:tenant, :reconciliation, :seq, :attempt, :state, :outcome,"
                + " :authenticated, :at)")
        .param("tenant", tenant.value())
        .param("reconciliation", reconciliationId)
        .param("seq", seq)
        .param("attempt", attemptNumber)
        .param("state", state)
        .param("outcome", outcomeCode)
        .param("authenticated", authenticated)
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Runs the deferred V49 exactness checks now, inside the caller's transaction, so a violation
   * surfaces where it is mapped by constraint name rather than at commit. Call it last.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payment_reconciliation_exact,"
                + " payroll.payment_reconciliation_inquiry_exact IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Worker (cross-tenant claims; each row is then handled under its own tenant)
  // -------------------------------------------------------------------------------------------

  /**
   * Locks due OPEN reconciliations, skipping rows other workers hold.
   *
   * @param now current instant
   * @param limit most rows
   * @return the locked rows, oldest due first
   */
  @CrossTenantAccess("the inquiry worker claims due reconciliations of every tenant")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ReconciliationRow> lockDue(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_reconciliation r WHERE r.state = 'OPEN'"
                + " AND r.next_attempt_at <= :now ORDER BY r.next_attempt_at, r.tenant_id, r.id"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcPaymentReconciliationRepository::row)
        .list();
  }

  /**
   * Locks INQUIRY_IN_PROGRESS reconciliations whose lease has expired (a crashed worker), skipping
   * held rows.
   *
   * @param now current instant
   * @param limit most rows
   * @return the locked rows, oldest lease first
   */
  @CrossTenantAccess("the inquiry worker recovers expired leases of every tenant")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ReconciliationRow> lockExpired(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM payroll.payment_reconciliation r WHERE r.state = 'INQUIRY_IN_PROGRESS'"
                + " AND r.lease_expires_at < :now ORDER BY r.lease_expires_at, r.tenant_id, r.id"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcPaymentReconciliationRepository::row)
        .list();
  }

  /**
   * The number of reconciliations still being worked on (OPEN or INQUIRY_IN_PROGRESS), for the
   * queue-depth gauge.
   *
   * @return the count across tenants
   */
  @CrossTenantAccess("the queue-depth gauge counts open reconciliations of every tenant")
  public int openCount() {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM payroll.payment_reconciliation r WHERE r.state ="
                + " 'OPEN') + (SELECT count(*) FROM payroll.payment_reconciliation r WHERE"
                + " r.state = 'INQUIRY_IN_PROGRESS')")
        .query(Integer.class)
        .single();
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static InquiryRow inquiryRow(ResultSet rs, int rowNumber) throws SQLException {
    return new InquiryRow(
        rs.getInt("seq"),
        rs.getInt("attempt_number"),
        rs.getString("state"),
        rs.getString("outcome_code"),
        rs.getBoolean("authenticated"),
        rs.getTimestamp("occurred_at").toInstant());
  }

  private static ReconciliationRow row(ResultSet rs, int rowNumber) throws SQLException {
    Timestamp next = rs.getTimestamp("next_attempt_at");
    Timestamp lease = rs.getTimestamp("lease_expires_at");
    return new ReconciliationRow(
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getObject("submission_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getObject("request_id", UUID.class),
        rs.getString("provider_code"),
        rs.getInt("key_version"),
        rs.getObject("inquiry_id", UUID.class),
        rs.getString("opened_from_state"),
        rs.getInt("opened_from_version"),
        rs.getString("state"),
        rs.getInt("version"),
        rs.getInt("attempt_count"),
        rs.getString("outcome_code"),
        next == null ? null : next.toInstant(),
        rs.getString("lease_owner"),
        lease == null ? null : lease.toInstant(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by_subject"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
