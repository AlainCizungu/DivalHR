package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationReason;
import com.divalhr.core.payroll.domain.CompensationTimeline.Fact;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of monthly compensation (MVP-093A; V43): the append-only command evidence and the
 * transaction-time timeline of facts. Every method takes the verified {@link TenantId} and every
 * statement filters on it and on the employee. Rows are only ever inserted, or superseded once;
 * nothing is deleted or updated in place (the V43 triggers enforce it). Restricted financial data:
 * no record here prints an amount or an explanation.
 */
@Repository
public class JdbcCompensationRepository {

  private static final String ACTOR_ROLE = "tenant-admin";

  private static final String CHANGE_COLUMNS =
      "id, seq, action, occurred_at, currency, exponent, amount_minor, effective_from,"
          + " target_change_id, reason_code, explanation, retroactive_acknowledged";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcCompensationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * An active fact with the action of the change that introduced it.
   *
   * @param fact the fact
   * @param originAction the action of its origin change
   */
  public record ActiveFact(Fact fact, CompensationAction originAction) {}

  /**
   * One recorded change (Restricted financial data).
   *
   * @param id change
   * @param seq sequence (the compensation version after it)
   * @param action action
   * @param occurredAt when it was recorded
   * @param money the value it introduced (for a cancellation, the cancelled value)
   * @param effectiveFrom the first day of that value
   * @param targetChangeId the change it acts on, or {@code null}
   * @param reason correction reason, or {@code null}
   * @param explanation administrator explanation, or {@code null}
   * @param acknowledged whether the retroactive effect was acknowledged
   */
  public record ChangeRow(
      UUID id,
      int seq,
      CompensationAction action,
      Instant occurredAt,
      CompensationMoney money,
      LocalDate effectiveFrom,
      UUID targetChangeId,
      CompensationReason reason,
      String explanation,
      boolean acknowledged) {

    @Override
    public String toString() {
      return "ChangeRow[" + id + "]";
    }
  }

  /**
   * A change to append.
   *
   * @param id new change
   * @param seq next sequence
   * @param action action
   * @param occurredAt server instant
   * @param actorSubject verified subject
   * @param money the value introduced (or cancelled)
   * @param effectiveFrom first day of that value
   * @param targetChangeId the change acted on, or {@code null}
   * @param reason correction reason, or {@code null}
   * @param explanation administrator explanation, or {@code null}
   * @param acknowledged whether the retroactive effect was acknowledged
   * @param previewDigest the confirmed preview or cancellation digest
   */
  public record NewChange(
      UUID id,
      int seq,
      CompensationAction action,
      Instant occurredAt,
      String actorSubject,
      CompensationMoney money,
      LocalDate effectiveFrom,
      UUID targetChangeId,
      CompensationReason reason,
      String explanation,
      boolean acknowledged,
      String previewDigest) {

    @Override
    public String toString() {
      return "NewChange[" + id + "]";
    }
  }

  /**
   * A count of committed changes in a window.
   *
   * @param count how many
   * @param oldest the oldest instant counted, or {@code null}
   */
  public record Usage(int count, Instant oldest) {}

  // -------------------------------------------------------------------------------------------
  // Transaction controls
  // -------------------------------------------------------------------------------------------

  /**
   * Bounds every following statement and lock wait of the current transaction.
   *
   * @param tenant verified tenant whose transaction it is
   * @param statementTimeout per-statement limit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void limits(TenantId tenant, Duration statementTimeout) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    long millis = Math.max(1, statementTimeout.toMillis());
    // Values are server-owned integers; SET does not accept bind parameters.
    jdbc.sql("SET LOCAL statement_timeout = " + millis).update();
    jdbc.sql("SET LOCAL lock_timeout = " + millis).update();
  }

  /**
   * Takes the employee's compensation lock for the rest of the transaction (the same key the V43
   * trigger takes), serializing every command on the employee.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lock(TenantId tenant, UUID employeeId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.compensation:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:employee AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query()
        .singleRow();
  }

  /**
   * Runs the deferred V43 checks now (inside the caller's transaction), so a violation surfaces
   * inside the business work, where it is mapped by constraint name, rather than at commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payroll_compensation_exact,"
                + " payroll.payroll_compensation_change_exact IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------------------------

  /**
   * The employee's compensation version: the last change sequence, 0 when none.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the version
   */
  public int version(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT COALESCE(max(seq), 0) FROM payroll.compensation_change"
                + " WHERE tenant_id = :tenant AND employee_id = :employee")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(Integer.class)
        .single();
  }

  /**
   * The employee's active facts in timeline order.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the active facts with the action that introduced each
   */
  public List<ActiveFact> active(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT f.id, f.created_seq, f.origin_change_id, f.currency, f.amount_minor,"
                + " f.effective_from, f.effective_to, c.action AS origin_action"
                + " FROM payroll.compensation f JOIN payroll.compensation_change c"
                + " ON c.tenant_id = f.tenant_id AND c.employee_id = f.employee_id"
                + " AND c.id = f.origin_change_id"
                + " WHERE f.tenant_id = :tenant AND f.employee_id = :employee"
                + " AND f.superseded_seq IS NULL ORDER BY f.effective_from")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(JdbcCompensationRepository::active)
        .list();
  }

  /**
   * One change of an employee.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param changeId change
   * @return the change, if it belongs to that employee in the tenant
   */
  public Optional<ChangeRow> change(TenantId tenant, UUID employeeId, UUID changeId) {
    return jdbc.sql(
            "SELECT "
                + CHANGE_COLUMNS
                + " FROM payroll.compensation_change WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", changeId)
        .query(JdbcCompensationRepository::change)
        .optional();
  }

  /**
   * A page of the employee's changes, newest first.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param beforeSeq continue after (below) this sequence, or {@code null} for the first page
   * @param limit rows to read
   * @return the changes in descending sequence
   */
  public List<ChangeRow> page(TenantId tenant, UUID employeeId, Integer beforeSeq, int limit) {
    if (beforeSeq == null) {
      return jdbc.sql(
              "SELECT "
                  + CHANGE_COLUMNS
                  + " FROM payroll.compensation_change WHERE tenant_id = :tenant"
                  + " AND employee_id = :employee ORDER BY seq DESC LIMIT :limit")
          .param("tenant", tenant.value())
          .param("employee", employeeId)
          .param("limit", limit)
          .query(JdbcCompensationRepository::change)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + CHANGE_COLUMNS
                + " FROM payroll.compensation_change WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND seq < :before ORDER BY seq DESC LIMIT :limit")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("before", beforeSeq)
        .param("limit", limit)
        .query(JdbcCompensationRepository::change)
        .list();
  }

  /**
   * The changes a subject committed since an instant (the durable hourly quota of commands).
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param since window start
   * @return the count and the oldest instant
   */
  public Usage changesSince(TenantId tenant, String subject, Instant since) {
    return jdbc.sql(
            "SELECT count(*) AS n, min(occurred_at) AS oldest FROM payroll.compensation_change"
                + " WHERE tenant_id = :tenant AND actor_subject = :subject"
                + " AND occurred_at > :since")
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("since", Timestamp.from(since))
        .query(
            (rs, row) -> {
              Timestamp oldest = rs.getTimestamp("oldest");
              return new Usage(rs.getInt("n"), oldest == null ? null : oldest.toInstant());
            })
        .single();
  }

  /**
   * The latest start of an employee's active facts (the separation guard).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the latest start of an active interval, if any
   */
  public Optional<LocalDate> latestActiveStart(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT max(effective_from) FROM payroll.compensation WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND superseded_seq IS NULL")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query((rs, row) -> rs.getObject(1, LocalDate.class))
        .optional();
  }

  // -------------------------------------------------------------------------------------------
  // Writes (each inside the caller's transaction; the database re-checks every rule)
  // -------------------------------------------------------------------------------------------

  /**
   * Appends a change.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param change the change
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertChange(TenantId tenant, UUID employeeId, NewChange change) {
    jdbc.sql(
            "INSERT INTO payroll.compensation_change (tenant_id, employee_id, id, seq, action,"
                + " occurred_at, actor_subject, actor_role, currency, exponent, amount_minor,"
                + " effective_from, target_change_id, reason_code, explanation,"
                + " retroactive_acknowledged, preview_digest) VALUES (:tenant, :employee, :id,"
                + " :seq, :action, :at, :actor, :role, :currency, :exponent, :minor, :from,"
                + " :target, :reason, :explanation, :acknowledged, :digest)")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", change.id())
        .param("seq", change.seq())
        .param("action", change.action().name())
        .param("at", Timestamp.from(change.occurredAt()))
        .param("actor", change.actorSubject())
        .param("role", ACTOR_ROLE)
        .param("currency", change.money().currency().name())
        .param("exponent", change.money().currency().exponent())
        .param("minor", change.money().minor())
        .param("from", change.effectiveFrom())
        .param("target", change.targetChangeId())
        .param("reason", change.reason() == null ? null : change.reason().name())
        .param("explanation", change.explanation())
        .param("acknowledged", change.acknowledged())
        .param("digest", change.previewDigest())
        .update();
  }

  /**
   * Marks active facts superseded by the latest change.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param factIds the facts
   * @param seq the sequence of the change that supersedes them
   * @return the number superseded
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int supersede(TenantId tenant, UUID employeeId, Collection<UUID> factIds, int seq) {
    if (factIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            "UPDATE payroll.compensation SET superseded_seq = :seq WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND id = ANY(:ids) AND superseded_seq IS NULL")
        .param("seq", seq)
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("ids", factIds.toArray(UUID[]::new))
        .update();
  }

  /**
   * Inserts a new active fact written by the latest change.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param seq the sequence of the change that creates it
   * @param interval its value and period
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertFact(TenantId tenant, UUID employeeId, int seq, Interval interval) {
    jdbc.sql(
            "INSERT INTO payroll.compensation (tenant_id, employee_id, id, origin_change_id,"
                + " created_seq, frequency, currency, exponent, amount_minor, effective_from,"
                + " effective_to) VALUES (:tenant, :employee, :id, :origin, :seq, 'MONTHLY',"
                + " :currency, :exponent, :minor, :from, :to)")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", UUID.randomUUID())
        .param("origin", interval.origin())
        .param("seq", seq)
        .param("currency", interval.money().currency().name())
        .param("exponent", interval.money().currency().exponent())
        .param("minor", interval.money().minor())
        .param("from", interval.from())
        .param("to", interval.to())
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Row mapping
  // -------------------------------------------------------------------------------------------

  private static ActiveFact active(ResultSet rs, int row) throws SQLException {
    CompensationCurrency currency = CompensationCurrency.valueOf(rs.getString("currency"));
    Interval interval =
        new Interval(
            rs.getObject("origin_change_id", UUID.class),
            CompensationMoney.ofMinor(currency, rs.getLong("amount_minor")),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class));
    return new ActiveFact(
        new Fact(rs.getObject("id", UUID.class), rs.getInt("created_seq"), interval),
        CompensationAction.valueOf(rs.getString("origin_action")));
  }

  private static ChangeRow change(ResultSet rs, int row) throws SQLException {
    CompensationCurrency currency = CompensationCurrency.valueOf(rs.getString("currency"));
    String reason = rs.getString("reason_code");
    return new ChangeRow(
        rs.getObject("id", UUID.class),
        rs.getInt("seq"),
        CompensationAction.valueOf(rs.getString("action")),
        rs.getTimestamp("occurred_at").toInstant(),
        CompensationMoney.ofMinor(currency, rs.getLong("amount_minor")),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("target_change_id", UUID.class),
        reason == null ? null : CompensationReason.valueOf(reason),
        rs.getString("explanation"),
        rs.getBoolean("retroactive_acknowledged"));
  }
}
