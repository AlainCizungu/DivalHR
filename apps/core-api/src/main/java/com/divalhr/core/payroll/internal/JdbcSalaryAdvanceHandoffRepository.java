package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of controlled partner handoffs, consent, delivery and review evidence (MVP-120B; V53).
 * Every method takes the verified {@link TenantId} and every statement filters on it; the only
 * cross-tenant statements are the delivery worker's claims. Evidence rows are only ever inserted;
 * the header moves along its closed lifecycle (the V53 triggers enforce both). Restricted financial
 * and HR data: no record here prints an amount, a date of employment, an employee number, an
 * application id, a digest or a partner reference.
 */
@Repository
public class JdbcSalaryAdvanceHandoffRepository {

  private static final String COLUMNS =
      "h.tenant_id, h.id, h.request_id, h.employee_id, s.employee_number, h.legal_entity_id,"
          + " h.partner_code, h.application_id, h.state, h.review_state, h.review_reason,"
          + " h.row_version, h.attempt_count, h.reconcile_count, h.admin_retry_count,"
          + " h.payload_sha256, h.key_version, h.authorized_at, h.authorized_by,"
          + " h.next_attempt_at, h.lease_until, h.updated_at, r.currency, r.requested_minor,"
          + " r.ceiling_minor, r.reference_period_start, r.employment_start, r.row_version AS"
          + " request_version, r.status AS request_status";

  private static final String FROM =
      " FROM payroll.salary_advance_handoff h JOIN payroll.salary_advance_request r"
          + " ON r.tenant_id = h.tenant_id AND r.id = h.request_id JOIN payroll.payroll_payslip s"
          + " ON s.tenant_id = r.tenant_id AND s.package_id = r.package_id AND s.id = r.payslip_id";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcSalaryAdvanceHandoffRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A stored handoff joined with the frozen facts of its request.
   *
   * @param tenant the tenant
   * @param id handoff
   * @param requestId request
   * @param employeeId employee
   * @param employeeNumber employee number (administrators only)
   * @param legalEntityId legal entity
   * @param partnerCode the synthetic partner code
   * @param applicationId the opaque application id
   * @param state delivery state
   * @param reviewState partner review state, or {@code null}
   * @param reviewReason closed review reason, or {@code null}
   * @param version row version
   * @param attemptCount attempts started
   * @param reconcileCount reconciles performed
   * @param adminRetryCount administrator retries performed
   * @param payloadSha256 digest of the canonical payload
   * @param keyVersion the delivery key version
   * @param authorizedAt authorization instant
   * @param authorizedBy authorizing subject
   * @param nextAttemptAt next due attempt, or {@code null}
   * @param leaseUntil lease end, or {@code null}
   * @param updatedAt last change
   * @param currency currency
   * @param requestedMinor requested amount (Restricted)
   * @param ceilingMinor employer ceiling (Restricted)
   * @param referencePeriodStart payroll reference month
   * @param employmentStart employment start
   * @param requestVersion the request row version
   * @param requestStatus the request status
   */
  public record HandoffRow(
      UUID tenant,
      UUID id,
      UUID requestId,
      UUID employeeId,
      String employeeNumber,
      UUID legalEntityId,
      String partnerCode,
      UUID applicationId,
      String state,
      String reviewState,
      String reviewReason,
      int version,
      int attemptCount,
      int reconcileCount,
      int adminRetryCount,
      String payloadSha256,
      String keyVersion,
      Instant authorizedAt,
      String authorizedBy,
      Instant nextAttemptAt,
      Instant leaseUntil,
      Instant updatedAt,
      String currency,
      long requestedMinor,
      long ceilingMinor,
      LocalDate referencePeriodStart,
      LocalDate employmentStart,
      int requestVersion,
      String requestStatus) {

    @Override
    public String toString() {
      return "SalaryAdvanceHandoffRow[" + id + "]";
    }

    /**
     * @return the tenant as a verified identifier
     */
    public TenantId tenantId() {
      return new TenantId(tenant);
    }
  }

  /**
   * A handoff to insert, QUEUED at version 1 (the V53 guard re-proves the source request and
   * actors).
   *
   * @param id handoff
   * @param requestId request
   * @param employeeId employee
   * @param legalEntityId legal entity
   * @param partnerCode partner
   * @param applicationId the opaque application id
   * @param payloadSha256 payload digest
   * @param sharedCategories the closed shared categories
   * @param keyVersion key version
   * @param authorizedAt instant
   * @param authorizedBy authorizing subject
   * @param nextAttemptAt first due instant
   */
  public record NewHandoff(
      UUID id,
      UUID requestId,
      UUID employeeId,
      UUID legalEntityId,
      String partnerCode,
      UUID applicationId,
      String payloadSha256,
      List<String> sharedCategories,
      String keyVersion,
      Instant authorizedAt,
      String authorizedBy,
      Instant nextAttemptAt) {}

  /**
   * The mutable columns after a transition.
   *
   * @param state new delivery state
   * @param reviewState new review state, or {@code null}
   * @param reviewReason new review reason, or {@code null}
   * @param attemptCount attempts started
   * @param reconcileCount reconciles performed
   * @param adminRetryCount administrator retries performed
   * @param nextAttemptAt next due instant (QUEUED only)
   * @param leaseUntil lease end (SENDING only)
   * @param at update instant
   */
  public record Next(
      String state,
      String reviewState,
      String reviewReason,
      int attemptCount,
      int reconcileCount,
      int adminRetryCount,
      Instant nextAttemptAt,
      Instant leaseUntil,
      Instant at) {}

  /**
   * Consent evidence.
   *
   * @param version consent version
   * @param language {@code en} or {@code fr}
   * @param consentedAt instant
   * @param disclosureSha256 disclosure digest
   * @param payloadSha256 bound payload digest
   * @param consentedBy subject
   */
  public record ConsentRow(
      String version,
      String language,
      Instant consentedAt,
      String disclosureSha256,
      String payloadSha256,
      String consentedBy) {}

  /**
   * One delivery event.
   *
   * @param sequence 1-based
   * @param fromState previous state, or {@code null}
   * @param toState new state
   * @param actorRole role
   * @param actorSubject subject
   * @param occurredAt instant
   * @param outcome allow-listed outcome, or {@code null}
   */
  public record EventRow(
      int sequence,
      String fromState,
      String toState,
      String actorRole,
      String actorSubject,
      Instant occurredAt,
      String outcome) {}

  /**
   * One review event.
   *
   * @param sequence 1-based
   * @param fromReviewState previous review state
   * @param toReviewState new review state
   * @param reasonCode closed reason, or {@code null}
   * @param callbackId the signed callback that caused it
   * @param occurredAt instant
   */
  public record ReviewEventRow(
      int sequence,
      String fromReviewState,
      String toReviewState,
      String reasonCode,
      UUID callbackId,
      Instant occurredAt) {}

  /**
   * One finished attempt.
   *
   * @param attemptNumber 1-based
   * @param startedAt start
   * @param finishedAt end
   * @param outcome allow-listed outcome
   * @param httpStatus status code, or {@code null}
   */
  public record AttemptRow(
      int attemptNumber,
      Instant startedAt,
      Instant finishedAt,
      String outcome,
      Integer httpStatus) {}

  /**
   * The position of a page cursor.
   *
   * @param authorizedAt instant
   * @param id handoff
   */
  public record Position(Instant authorizedAt, UUID id) {}

  /**
   * The subject's committed handoff work in a window (hourly quotas).
   *
   * @param count commands in the window
   * @param oldest the oldest, or {@code null}
   */
  public record Usage(int count, Instant oldest) {}

  /**
   * Bounds the current transaction's statements and lock waits.
   *
   * @param tenant verified tenant whose transaction it is
   * @param statementTimeout the bound
   */
  public void limits(TenantId tenant, java.time.Duration statementTimeout) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    long millis = Math.max(1, statementTimeout.toMillis());
    jdbc.sql("SET LOCAL statement_timeout = " + millis).update();
    jdbc.sql("SET LOCAL lock_timeout = " + millis).update();
  }

  // -------------------------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------------------------

  /**
   * The handoff of a request (one per request, forever).
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the handoff, if any
   */
  public Optional<HandoffRow> byRequest(TenantId tenant, UUID requestId) {
    return jdbc.sql(
            "SELECT " + COLUMNS + FROM + " WHERE h.tenant_id = :tenant AND h.request_id = :request")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .query(JdbcSalaryAdvanceHandoffRepository::row)
        .optional();
  }

  /**
   * The handoff of the employee's own request: the employee predicate is part of the statement.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param requestId request
   * @return the handoff, if it belongs to this employee
   */
  public Optional<HandoffRow> myByRequest(TenantId tenant, UUID employeeId, UUID requestId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE h.tenant_id = :tenant AND h.employee_id = :employee"
                + " AND h.request_id = :request")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("request", requestId)
        .query(JdbcSalaryAdvanceHandoffRepository::row)
        .optional();
  }

  /**
   * One handoff of the tenant.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the handoff, if it exists in this tenant
   */
  public Optional<HandoffRow> handoff(TenantId tenant, UUID handoffId) {
    return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE h.tenant_id = :tenant AND h.id = :id")
        .param("tenant", tenant.value())
        .param("id", handoffId)
        .query(JdbcSalaryAdvanceHandoffRepository::row)
        .optional();
  }

  /**
   * Locks a handoff row for the rest of the transaction and returns it as it now stands.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the locked handoff, if it exists in this tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<HandoffRow> lock(TenantId tenant, UUID handoffId) {
    Optional<UUID> locked =
        jdbc.sql(
                "SELECT id FROM payroll.salary_advance_handoff WHERE tenant_id = :tenant"
                    + " AND id = :id FOR UPDATE")
            .param("tenant", tenant.value())
            .param("id", handoffId)
            .query((rs, n) -> rs.getObject("id", UUID.class))
            .optional();
    return locked.flatMap(id -> handoff(tenant, id));
  }

  /**
   * Locks a handoff by its application id (the signed callback names only that id).
   *
   * @param applicationId the opaque application id
   * @return the locked handoff with its tenant, if it exists
   */
  @CrossTenantAccess("a signed partner callback names only the opaque application id")
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<HandoffRow> lockByApplication(UUID applicationId) {
    Optional<UUID[]> keys =
        jdbc.sql(
                "SELECT tenant_id, id FROM payroll.salary_advance_handoff WHERE application_id ="
                    + " :application FOR UPDATE")
            .param("application", applicationId)
            .query(
                (rs, n) ->
                    new UUID[] {
                      rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)
                    })
            .optional();
    return keys.flatMap(pair -> handoff(new TenantId(pair[0]), pair[1]));
  }

  /**
   * A page of handoffs, newest first. A {@code null} employee lists the whole tenant
   * (administrators); a {@code null} state lists every state.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee, or {@code null} for administrators
   * @param state state filter, or {@code null}
   * @param after the last row of the previous page, or {@code null}
   * @param limit rows to read (page size plus one)
   * @return the rows
   */
  public List<HandoffRow> page(
      TenantId tenant, UUID employeeId, String state, Position after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(FROM)
            .append(" WHERE h.tenant_id = :tenant");
    if (employeeId != null) {
      sql.append(" AND h.employee_id = :employee");
    }
    if (state != null) {
      sql.append(" AND h.state = :state");
    }
    if (after != null) {
      sql.append(" AND (h.authorized_at, h.id) < (:afterAt, CAST(:afterId AS uuid))");
    }
    sql.append(" ORDER BY h.authorized_at DESC, h.id DESC LIMIT :limit");
    JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", tenant.value());
    if (employeeId != null) {
      spec = spec.param("employee", employeeId);
    }
    if (state != null) {
      spec = spec.param("state", state);
    }
    if (after != null) {
      spec =
          spec.param("afterAt", Timestamp.from(after.authorizedAt())).param("afterId", after.id());
    }
    return spec.param("limit", limit).query(JdbcSalaryAdvanceHandoffRepository::row).list();
  }

  /**
   * The consent evidence of a handoff.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the consent, if any
   */
  public Optional<ConsentRow> consent(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT consent_version, language, consented_at, disclosure_sha256, payload_sha256,"
                + " consented_by FROM payroll.salary_advance_handoff_consent WHERE tenant_id ="
                + " :tenant AND handoff_id = :handoff")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query(
            (rs, n) ->
                new ConsentRow(
                    rs.getString("consent_version"),
                    rs.getString("language"),
                    rs.getTimestamp("consented_at").toInstant(),
                    rs.getString("disclosure_sha256"),
                    rs.getString("payload_sha256"),
                    rs.getString("consented_by")))
        .optional();
  }

  /**
   * The delivery history of a handoff, in order.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the events
   */
  public List<EventRow> events(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT sequence, from_state, to_state, actor_role, actor_subject, occurred_at, outcome"
                + " FROM payroll.salary_advance_handoff_event WHERE tenant_id = :tenant AND"
                + " handoff_id = :handoff ORDER BY sequence")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query(
            (rs, n) ->
                new EventRow(
                    rs.getInt("sequence"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("actor_role"),
                    rs.getString("actor_subject"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("outcome")))
        .list();
  }

  /**
   * The partner-review history of a handoff, in order.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the review events
   */
  public List<ReviewEventRow> reviewEvents(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT sequence, from_review_state, to_review_state, reason_code, callback_id,"
                + " occurred_at FROM payroll.salary_advance_handoff_review_event WHERE tenant_id ="
                + " :tenant AND handoff_id = :handoff ORDER BY sequence")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query(
            (rs, n) ->
                new ReviewEventRow(
                    rs.getInt("sequence"),
                    rs.getString("from_review_state"),
                    rs.getString("to_review_state"),
                    rs.getString("reason_code"),
                    rs.getObject("callback_id", UUID.class),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /**
   * The attempt history of a handoff, in order.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the attempts
   */
  public List<AttemptRow> attempts(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT attempt_number, started_at, finished_at, outcome, http_status FROM"
                + " payroll.salary_advance_handoff_attempt WHERE tenant_id = :tenant AND"
                + " handoff_id = :handoff ORDER BY attempt_number")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query(
            (rs, n) ->
                new AttemptRow(
                    rs.getInt("attempt_number"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("finished_at").toInstant(),
                    rs.getString("outcome"),
                    rs.getObject("http_status") == null ? null : rs.getInt("http_status")))
        .list();
  }

  /**
   * The instant the partner receipt was acknowledged.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the acknowledgement instant, if received
   */
  public Optional<Instant> acknowledgedAt(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT acknowledged_at FROM payroll.salary_advance_handoff_ack WHERE tenant_id ="
                + " :tenant AND handoff_id = :handoff")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query((rs, n) -> rs.getTimestamp("acknowledged_at").toInstant())
        .optional();
  }

  /**
   * The subject's committed handoff authorizations in a window (hourly quota).
   *
   * @param tenant verified tenant
   * @param subject acting subject
   * @param role {@code EMPLOYEE} or {@code TENANT_ADMIN}
   * @param since lower bound (inclusive)
   * @return the count and the oldest instant
   */
  public Usage eventsSince(TenantId tenant, String subject, String role, Instant since) {
    return jdbc.sql(
            "SELECT count(*) AS total, min(occurred_at) AS oldest FROM"
                + " payroll.salary_advance_handoff_event WHERE tenant_id = :tenant AND"
                + " actor_subject = :subject AND actor_role = :role AND occurred_at >= :since AND"
                + " (to_state = 'AUTHORIZED' OR outcome IN ('ADMIN_RETRY', 'WITHDRAWN_BEFORE_RECEIPT', 'RECONCILED_RECEIVED',"
                + " 'RECONCILED_NOT_RECEIVED', 'RECONCILE_INCONCLUSIVE'))")
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("role", role)
        .param("since", Timestamp.from(since))
        .query(
            (rs, n) ->
                new Usage(
                    rs.getInt("total"),
                    rs.getTimestamp("oldest") == null
                        ? null
                        : rs.getTimestamp("oldest").toInstant()))
        .single();
  }

  // -------------------------------------------------------------------------------------------
  // Writes (inside the caller's transaction)
  // -------------------------------------------------------------------------------------------

  /**
   * Inserts a handoff QUEUED at version 1. The deferred completeness check runs at commit, so the
   * consent and the two authorization events follow in the same transaction.
   *
   * @param tenant verified tenant
   * @param handoff the handoff
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, NewHandoff handoff) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff (tenant_id, id, request_id, employee_id,"
                + " legal_entity_id, partner_code, application_id, state, review_state,"
                + " review_reason, row_version, attempt_count, reconcile_count, admin_retry_count,"
                + " payload_schema_version, payload_sha256, shared_categories, key_version,"
                + " authorized_at, authorized_by, next_attempt_at, lease_until, updated_at) VALUES"
                + " (:tenant, :id, :request, :employee, :entity, :partner, :application, 'QUEUED',"
                + " NULL, NULL, 1, 0, 0, 0, 1, :digest, CAST(:categories AS text[]), :key, :at,"
                + " :by, :next, NULL, :at)")
        .param("tenant", tenant.value())
        .param("id", handoff.id())
        .param("request", handoff.requestId())
        .param("employee", handoff.employeeId())
        .param("entity", handoff.legalEntityId())
        .param("partner", handoff.partnerCode())
        .param("application", handoff.applicationId())
        .param("digest", handoff.payloadSha256())
        .param("categories", "{" + String.join(",", handoff.sharedCategories()) + "}")
        .param("key", handoff.keyVersion())
        .param("at", Timestamp.from(handoff.authorizedAt()))
        .param("by", handoff.authorizedBy())
        .param("next", Timestamp.from(handoff.nextAttemptAt()))
        .update();
  }

  /**
   * Moves a handoff along its closed lifecycle (optimistic on the version the caller read under the
   * lock).
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param fromVersion the version read under the lock
   * @param next the mutable columns after the transition
   * @return rows changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int update(TenantId tenant, UUID handoffId, int fromVersion, Next next) {
    return jdbc.sql(
            "UPDATE payroll.salary_advance_handoff SET state = :state, review_state ="
                + " CAST(:review AS text), review_reason = CAST(:reason AS text), attempt_count ="
                + " :attempts, reconcile_count = :reconciles, admin_retry_count = :retries,"
                + " next_attempt_at = :nextAt, lease_until = :lease, updated_at = :at,"
                + " row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id AND"
                + " row_version = :version")
        .param("tenant", tenant.value())
        .param("id", handoffId)
        .param("version", fromVersion)
        .param("state", next.state())
        .param("review", next.reviewState())
        .param("reason", next.reviewReason())
        .param("attempts", (short) next.attemptCount())
        .param("reconciles", (short) next.reconcileCount())
        .param("retries", (short) next.adminRetryCount())
        .param("nextAt", next.nextAttemptAt() == null ? null : Timestamp.from(next.nextAttemptAt()))
        .param("lease", next.leaseUntil() == null ? null : Timestamp.from(next.leaseUntil()))
        .param("at", Timestamp.from(next.at()))
        .update();
  }

  /**
   * Records the consent of a handoff.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param requestId request
   * @param consent the consent evidence
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertConsent(TenantId tenant, UUID handoffId, UUID requestId, ConsentRow consent) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff_consent (tenant_id, handoff_id, request_id,"
                + " consent_version, language, consented_at, disclosure_sha256, payload_sha256,"
                + " consented_by, affirmed, synthetic_acknowledged) VALUES (:tenant, :handoff,"
                + " :request, :version, :language, :at, :disclosure, :payload, :by, true, true)")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .param("request", requestId)
        .param("version", consent.version())
        .param("language", consent.language())
        .param("at", Timestamp.from(consent.consentedAt()))
        .param("disclosure", consent.disclosureSha256())
        .param("payload", consent.payloadSha256())
        .param("by", consent.consentedBy())
        .update();
  }

  /**
   * Appends a delivery event.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param event the event
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertEvent(TenantId tenant, UUID handoffId, EventRow event) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff_event (tenant_id, handoff_id, sequence,"
                + " from_state, to_state, actor_role, actor_subject, occurred_at, outcome) VALUES"
                + " (:tenant, :handoff, :sequence, CAST(:from AS text), :to, :role, :actor, :at,"
                + " CAST(:outcome AS text))")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .param("sequence", (short) event.sequence())
        .param("from", event.fromState())
        .param("to", event.toState())
        .param("role", event.actorRole())
        .param("actor", event.actorSubject())
        .param("at", Timestamp.from(event.occurredAt()))
        .param("outcome", event.outcome())
        .update();
  }

  /**
   * The next delivery event sequence of a handoff (computed under the handoff lock).
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the next sequence
   */
  public int nextEventSequence(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT coalesce(max(sequence), 0) + 1 FROM payroll.salary_advance_handoff_event WHERE"
                + " tenant_id = :tenant AND handoff_id = :handoff")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query(Integer.class)
        .single();
  }

  /**
   * The next review event sequence of a handoff (computed under the handoff lock).
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @return the next sequence
   */
  public int nextReviewSequence(TenantId tenant, UUID handoffId) {
    return jdbc.sql(
            "SELECT coalesce(max(sequence), 0) + 1 FROM"
                + " payroll.salary_advance_handoff_review_event WHERE tenant_id = :tenant AND"
                + " handoff_id = :handoff")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .query(Integer.class)
        .single();
  }

  /**
   * Records a finished attempt.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param attempt the attempt
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAttempt(TenantId tenant, UUID handoffId, AttemptRow attempt) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff_attempt (tenant_id, handoff_id,"
                + " attempt_number, started_at, finished_at, outcome, http_status) VALUES (:tenant,"
                + " :handoff, :number, :started, :finished, :outcome, CAST(:status AS smallint))")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .param("number", (short) attempt.attemptNumber())
        .param("started", Timestamp.from(attempt.startedAt()))
        .param("finished", Timestamp.from(attempt.finishedAt()))
        .param("outcome", attempt.outcome())
        .param("status", attempt.httpStatus())
        .update();
  }

  /**
   * Records the partner's acknowledgement (immutable, at most one).
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param reference the bounded partner reference
   * @param at acknowledgement instant
   * @param source {@code DELIVERY} or {@code RECONCILE}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAck(
      TenantId tenant, UUID handoffId, String reference, Instant at, String source) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff_ack (tenant_id, handoff_id,"
                + " partner_reference, acknowledged_at, source) VALUES (:tenant, :handoff,"
                + " :reference, :at, :source)")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .param("reference", reference)
        .param("at", Timestamp.from(at))
        .param("source", source)
        .update();
  }

  /**
   * Records signed-callback replay evidence; the callback id is unique across the platform.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param callbackId the replay id
   * @param reviewState reported state
   * @param reasonCode closed reason, or {@code null}
   * @param issuedAt the partner's issue instant
   * @param receivedAt server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCallback(
      TenantId tenant,
      UUID handoffId,
      UUID callbackId,
      String reviewState,
      String reasonCode,
      Instant issuedAt,
      Instant receivedAt) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff_callback (callback_id, tenant_id,"
                + " handoff_id, review_state, reason_code, issued_at, received_at) VALUES"
                + " (:callback, :tenant, :handoff, :state, CAST(:reason AS text), :issued,"
                + " :received)")
        .param("callback", callbackId)
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .param("state", reviewState)
        .param("reason", reasonCode)
        .param("issued", Timestamp.from(issuedAt))
        .param("received", Timestamp.from(receivedAt))
        .update();
  }

  /**
   * Appends a review event.
   *
   * @param tenant verified tenant
   * @param handoffId handoff
   * @param event the event
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertReviewEvent(TenantId tenant, UUID handoffId, ReviewEventRow event) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_handoff_review_event (tenant_id, handoff_id,"
                + " sequence, from_review_state, to_review_state, reason_code, callback_id,"
                + " occurred_at) VALUES (:tenant, :handoff, :sequence, :from, :to, CAST(:reason AS"
                + " text), :callback, :at)")
        .param("tenant", tenant.value())
        .param("handoff", handoffId)
        .param("sequence", (short) event.sequence())
        .param("from", event.fromReviewState())
        .param("to", event.toReviewState())
        .param("reason", event.reasonCode())
        .param("callback", event.callbackId())
        .param("at", Timestamp.from(event.occurredAt()))
        .update();
  }

  /**
   * Runs the deferred V53 completeness checks now (inside the caller's transaction), so a violation
   * surfaces inside the business work, where it is mapped by constraint name, rather than at
   * commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payroll_salary_advance_handoff_complete,"
                + " payroll.payroll_salary_advance_handoff_consent_complete,"
                + " payroll.payroll_salary_advance_handoff_event_complete,"
                + " payroll.payroll_salary_advance_handoff_attempt_complete,"
                + " payroll.payroll_salary_advance_handoff_ack_complete,"
                + " payroll.payroll_salary_advance_handoff_review_complete,"
                + " payroll.payroll_salary_advance_handoff_callback_complete IMMEDIATE")
        .update();
    // Back to deferred: the immediate check is a one-shot, so later writes of the same
    // transaction (a worker claim of several handoffs) are checked at commit like any other.
    jdbc.sql(
            "SET CONSTRAINTS payroll.payroll_salary_advance_handoff_complete,"
                + " payroll.payroll_salary_advance_handoff_consent_complete,"
                + " payroll.payroll_salary_advance_handoff_event_complete,"
                + " payroll.payroll_salary_advance_handoff_attempt_complete,"
                + " payroll.payroll_salary_advance_handoff_ack_complete,"
                + " payroll.payroll_salary_advance_handoff_review_complete,"
                + " payroll.payroll_salary_advance_handoff_callback_complete DEFERRED")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // The delivery worker (the only cross-tenant statements)
  // -------------------------------------------------------------------------------------------

  /**
   * Locks QUEUED handoffs that are due, skipping rows held by another instance.
   *
   * @param now current instant
   * @param limit most rows
   * @return the locked rows, oldest due first
   */
  @CrossTenantAccess("the delivery worker claims due handoffs of every tenant")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<HandoffRow> lockDue(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE h.state = 'QUEUED' AND h.next_attempt_at <= :now"
                + " ORDER BY h.next_attempt_at, h.tenant_id, h.id LIMIT :limit"
                + " FOR UPDATE OF h SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcSalaryAdvanceHandoffRepository::row)
        .list();
  }

  /**
   * Locks SENDING handoffs whose lease expired (a crashed worker), skipping held rows.
   *
   * @param now current instant
   * @param limit most rows
   * @return the locked rows, oldest lease first
   */
  @CrossTenantAccess("the delivery worker recovers expired leases of every tenant")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<HandoffRow> lockExpired(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + FROM
                + " WHERE h.state = 'SENDING' AND h.lease_until < :now"
                + " ORDER BY h.lease_until, h.tenant_id, h.id LIMIT :limit"
                + " FOR UPDATE OF h SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcSalaryAdvanceHandoffRepository::row)
        .list();
  }

  /**
   * The number of handoffs queued or being sent (a gauge).
   *
   * @return the open count
   */
  @CrossTenantAccess("the open-handoff gauge counts every tenant")
  public int openCount() {
    return jdbc.sql(
            "SELECT count(*) FROM payroll.salary_advance_handoff WHERE state IN ('QUEUED',"
                + " 'SENDING')")
        .query(Integer.class)
        .single();
  }

  // -------------------------------------------------------------------------------------------
  // Mapping
  // -------------------------------------------------------------------------------------------

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static HandoffRow row(ResultSet rs, int n) throws SQLException {
    return new HandoffRow(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getObject("request_id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("partner_code"),
        rs.getObject("application_id", UUID.class),
        rs.getString("state"),
        rs.getString("review_state"),
        rs.getString("review_reason"),
        rs.getInt("row_version"),
        rs.getInt("attempt_count"),
        rs.getInt("reconcile_count"),
        rs.getInt("admin_retry_count"),
        rs.getString("payload_sha256"),
        rs.getString("key_version"),
        rs.getTimestamp("authorized_at").toInstant(),
        rs.getString("authorized_by"),
        instantOrNull(rs, "next_attempt_at"),
        instantOrNull(rs, "lease_until"),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("currency"),
        rs.getLong("requested_minor"),
        rs.getLong("ceiling_minor"),
        rs.getObject("reference_period_start", LocalDate.class),
        rs.getObject("employment_start", LocalDate.class),
        rs.getInt("request_version"),
        rs.getString("request_status"));
  }
}
