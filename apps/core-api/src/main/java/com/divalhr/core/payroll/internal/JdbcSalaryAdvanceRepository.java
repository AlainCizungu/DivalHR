package com.divalhr.core.payroll.internal;

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
 * Storage of salary-advance policies, requests, consents, employer decisions and history (MVP-120A;
 * V52). Every method takes the verified {@link TenantId} and every statement filters on it. Rows
 * are only ever inserted; a request header moves along its closed lifecycle, and a policy version
 * that has not taken effect is cancelled once (the V52 triggers enforce both). Restricted financial
 * and HR data: no record here prints an amount, an employee number, a date of employment or a note.
 */
@Repository
public class JdbcSalaryAdvanceRepository {

  private static final String POLICY_COLUMNS =
      "id, legal_entity_id, currency, sequence, code, effective_from, effective_to, enabled,"
          + " min_tenure_months, max_percent, absolute_max_minor, min_request_minor,"
          + " max_open_requests, waiting_days, consent_version, partner_code, created_at,"
          + " cancelled_at";

  private static final String REQUEST_COLUMNS =
      "r.id, r.employee_id, s.employee_number, r.legal_entity_id, r.currency, r.status,"
          + " r.row_version, r.policy_id, r.policy_sequence, r.partner_code, r.package_id,"
          + " r.payslip_id, r.reference_period_start, r.reference_net_minor, r.ceiling_minor,"
          + " r.requested_minor, r.employment_start, r.tenure_months, r.requested_on,"
          + " r.submitted_at, r.submitted_by";

  private static final String REQUEST_FROM =
      " FROM payroll.salary_advance_request r JOIN payroll.payroll_payslip s"
          + " ON s.tenant_id = r.tenant_id AND s.package_id = r.package_id AND s.id = r.payslip_id";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcSalaryAdvanceRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // -------------------------------------------------------------------------------------------
  // Records
  // -------------------------------------------------------------------------------------------

  /**
   * A stored policy version (amounts are Restricted financial data).
   *
   * @param id policy
   * @param legalEntityId legal entity
   * @param currency {@code CDF} or {@code USD}
   * @param sequence version in its chain
   * @param code stable policy code
   * @param effectiveFrom first day in force
   * @param effectiveTo last day in force, or {@code null}
   * @param enabled whether employees may request
   * @param minTenureMonths minimum completed employment months
   * @param maxPercent whole percent of the reference net
   * @param absoluteMaxMinor optional absolute maximum, or {@code null}
   * @param minRequestMinor minimum request
   * @param maxOpenRequests open request limit, 1 to 3
   * @param waitingDays waiting days after a withdrawal or refusal
   * @param consentVersion the consent document version
   * @param partnerCode the synthetic partner code
   * @param createdAt creation instant
   * @param cancelledAt cancellation instant, or {@code null}
   */
  public record PolicyRow(
      UUID id,
      UUID legalEntityId,
      String currency,
      int sequence,
      String code,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      boolean enabled,
      int minTenureMonths,
      int maxPercent,
      Long absoluteMaxMinor,
      long minRequestMinor,
      int maxOpenRequests,
      int waitingDays,
      String consentVersion,
      String partnerCode,
      Instant createdAt,
      Instant cancelledAt) {

    @Override
    public String toString() {
      return "SalaryAdvancePolicyRow[" + id + "]";
    }
  }

  /**
   * The latest approved payroll reference of an employee in a legal entity and currency.
   *
   * @param packageId approved package
   * @param payslipId payslip
   * @param periodStart reference month (first day)
   * @param netMinor the employee's own net, never recalculated
   * @param operational whether the package was approved under an operational governed pack
   */
  public record ReferenceRow(
      UUID packageId, UUID payslipId, LocalDate periodStart, long netMinor, boolean operational) {

    @Override
    public String toString() {
      return "SalaryAdvanceReference[" + payslipId + "]";
    }
  }

  /**
   * A stored request header with its frozen evidence.
   *
   * @param id request
   * @param employeeId employee
   * @param employeeNumber employee number (administrators only; never logged)
   * @param legalEntityId legal entity
   * @param currency currency
   * @param status status
   * @param rowVersion version 1 to 3
   * @param policyId frozen policy
   * @param policySequence frozen policy sequence
   * @param partnerCode synthetic partner code
   * @param packageId frozen package
   * @param payslipId frozen payslip
   * @param referencePeriodStart frozen reference month
   * @param referenceNetMinor frozen reference net
   * @param ceilingMinor frozen ceiling
   * @param requestedMinor requested amount
   * @param employmentStart frozen employment start
   * @param tenureMonths frozen tenure
   * @param requestedOn business date of the request
   * @param submittedAt submission instant
   * @param submittedBy submitting subject
   */
  public record RequestRow(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      UUID legalEntityId,
      String currency,
      String status,
      int rowVersion,
      UUID policyId,
      int policySequence,
      String partnerCode,
      UUID packageId,
      UUID payslipId,
      LocalDate referencePeriodStart,
      long referenceNetMinor,
      long ceilingMinor,
      long requestedMinor,
      LocalDate employmentStart,
      int tenureMonths,
      LocalDate requestedOn,
      Instant submittedAt,
      String submittedBy) {

    @Override
    public String toString() {
      return "SalaryAdvanceRequestRow[" + id + "]";
    }
  }

  /**
   * A request to insert (frozen evidence the V52 guard re-proves).
   *
   * @param row the header at version 1, SUBMITTED
   */
  public record NewRequest(RequestRow row) {}

  /**
   * One lifecycle event.
   *
   * @param sequence 1 to 3
   * @param fromStatus previous status, or {@code null} for the first
   * @param toStatus new status
   * @param actorRole {@code EMPLOYEE} or {@code TENANT_ADMIN}
   * @param actorSubject acting subject
   * @param occurredAt instant
   * @param occurredOn business date
   * @param reasonCode bounded reason, or {@code null}
   */
  public record EventRow(
      int sequence,
      String fromStatus,
      String toStatus,
      String actorRole,
      String actorSubject,
      Instant occurredAt,
      LocalDate occurredOn,
      String reasonCode) {

    @Override
    public String toString() {
      return "SalaryAdvanceEvent[" + sequence + "]";
    }
  }

  /**
   * The immutable consent evidence of a request.
   *
   * @param version consent document version
   * @param language {@code en} or {@code fr}
   * @param consentedAt instant
   * @param disclosureSha256 digest of the canonical disclosure
   */
  public record ConsentRow(
      String version, String language, Instant consentedAt, String disclosureSha256) {}

  /**
   * The employer decision of a request.
   *
   * @param decision {@code CONFIRMED} or {@code REFUSED}
   * @param reasonCode refusal reason, or {@code null}
   * @param internalNote administrators-only note, or {@code null}
   * @param decidedBy deciding subject
   * @param decidedAt instant
   */
  public record DecisionRow(
      String decision,
      String reasonCode,
      String internalNote,
      String decidedBy,
      Instant decidedAt) {

    @Override
    public String toString() {
      return "SalaryAdvanceDecision[" + decision + "]";
    }
  }

  /**
   * A keyset position of the request list.
   *
   * @param submittedAt submission instant
   * @param id request
   */
  public record RequestPosition(Instant submittedAt, UUID id) {}

  /**
   * The subject's committed salary-advance work in a window (for the hourly command quota).
   *
   * @param count commands in the window
   * @param oldest the oldest, or {@code null}
   */
  public record Usage(int count, Instant oldest) {}

  // -------------------------------------------------------------------------------------------
  // Policy
  // -------------------------------------------------------------------------------------------

  /**
   * Every policy version of a legal entity, by currency then sequence.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @return the versions
   */
  public List<PolicyRow> policies(TenantId tenant, UUID legalEntityId) {
    return jdbc.sql(
            "SELECT "
                + POLICY_COLUMNS
                + " FROM payroll.salary_advance_policy WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity ORDER BY currency, sequence")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query(JdbcSalaryAdvanceRepository::policyRow)
        .list();
  }

  /**
   * One policy version of a legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param policyId policy
   * @return the version, if it exists in this tenant and entity
   */
  public Optional<PolicyRow> policy(TenantId tenant, UUID legalEntityId, UUID policyId) {
    return jdbc.sql(
            "SELECT "
                + POLICY_COLUMNS
                + " FROM payroll.salary_advance_policy WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", policyId)
        .query(JdbcSalaryAdvanceRepository::policyRow)
        .optional();
  }

  /**
   * The enabled, non-cancelled policy in force for a legal entity and currency on a date.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param currency currency
   * @param on business date
   * @return the policy, if one is in force
   */
  public Optional<PolicyRow> applicablePolicy(
      TenantId tenant, UUID legalEntityId, String currency, LocalDate on) {
    return jdbc.sql(
            "SELECT "
                + POLICY_COLUMNS
                + " FROM payroll.salary_advance_policy WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND currency = :currency AND enabled"
                + " AND cancelled_at IS NULL AND period @> CAST(:on AS date)")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("currency", currency)
        .param("on", on)
        .query(JdbcSalaryAdvanceRepository::policyRow)
        .optional();
  }

  /**
   * Takes the chain lock of a legal entity and currency for the rest of the transaction (the same
   * lock the V52 trigger takes, so a policy command and an insert never interleave).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param currency currency
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockChain(TenantId tenant, UUID legalEntityId, String currency) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('salary-advance-policy:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:entity AS uuid) AS text) || ':' || :currency, 0))")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("currency", currency)
        .query()
        .singleRow();
  }

  /**
   * Appends a policy version.
   *
   * @param tenant verified tenant
   * @param policy the version (its {@code cancelledAt} must be {@code null})
   * @param createdBy acting subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertPolicy(TenantId tenant, PolicyRow policy, String createdBy) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_policy (tenant_id, legal_entity_id, id, currency,"
                + " sequence, code, effective_from, effective_to, enabled, min_tenure_months,"
                + " max_percent, absolute_max_minor, min_request_minor, max_open_requests,"
                + " waiting_days, consent_version, partner_code, created_at, created_by)"
                + " VALUES (:tenant, :entity, :id, :currency, :sequence, :code, :from,"
                + " CAST(:to AS date), :enabled, :tenure, :percent, CAST(:absMax AS bigint),"
                + " :minRequest, :open, :waiting, :consent, :partner, :at, :by)")
        .param("tenant", tenant.value())
        .param("entity", policy.legalEntityId())
        .param("id", policy.id())
        .param("currency", policy.currency())
        .param("sequence", policy.sequence())
        .param("code", policy.code())
        .param("from", policy.effectiveFrom())
        .param("to", policy.effectiveTo())
        .param("enabled", policy.enabled())
        .param("tenure", (short) policy.minTenureMonths())
        .param("percent", (short) policy.maxPercent())
        .param("absMax", policy.absoluteMaxMinor())
        .param("minRequest", policy.minRequestMinor())
        .param("open", (short) policy.maxOpenRequests())
        .param("waiting", (short) policy.waitingDays())
        .param("consent", policy.consentVersion())
        .param("partner", policy.partnerCode())
        .param("at", Timestamp.from(policy.createdAt()))
        .param("by", createdBy)
        .update();
  }

  /**
   * Records the one-time cancellation of a version that has not taken effect.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param policyId policy
   * @param at cancellation instant
   * @param by acting subject
   * @return rows changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int cancelPolicy(
      TenantId tenant, UUID legalEntityId, UUID policyId, Instant at, String by) {
    return jdbc.sql(
            "UPDATE payroll.salary_advance_policy SET cancelled_at = :at, cancelled_by = :by"
                + " WHERE tenant_id = :tenant AND legal_entity_id = :entity AND id = :id"
                + " AND cancelled_at IS NULL")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", policyId)
        .param("at", Timestamp.from(at))
        .param("by", by)
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Eligibility facts (read only)
  // -------------------------------------------------------------------------------------------

  /**
   * The start date of the employment covering a date.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on business date
   * @return the employment start, if the employee is employed on the date
   */
  public Optional<LocalDate> employmentStart(TenantId tenant, UUID employeeId, LocalDate on) {
    return jdbc.sql(
            "SELECT e.effective_from FROM people.employment e WHERE e.tenant_id = :tenant"
                + " AND e.employee_id = :employee AND e.effective_from <= CAST(:on AS date)"
                + " AND (e.effective_to IS NULL OR e.effective_to >= CAST(:on AS date))"
                + " ORDER BY e.effective_from DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("on", on)
        .query((rs, row) -> rs.getObject("effective_from", LocalDate.class))
        .optional();
  }

  /**
   * The legal entity of the employee's current placement.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param on business date
   * @return the legal entity (the lowest identifier when several), if the employee is placed
   */
  public Optional<UUID> placementEntity(TenantId tenant, UUID employeeId, LocalDate on) {
    return jdbc.sql(
            "SELECT a.legal_entity_id FROM people.employment_assignment a"
                + " WHERE a.tenant_id = :tenant AND a.employee_id = :employee"
                + " AND a.kind = 'PLACEMENT' AND a.superseded_by_change_id IS NULL"
                + " AND a.legal_entity_id IS NOT NULL AND a.period @> CAST(:on AS date)"
                + " ORDER BY a.legal_entity_id LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("on", on)
        .query((rs, row) -> rs.getObject("legal_entity_id", UUID.class))
        .optional();
  }

  /**
   * The employee's latest approved, non-voided payroll reference in a legal entity and currency.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param legalEntityId legal entity
   * @param currency currency
   * @return the reference, if any approved package carries a payslip of the employee
   */
  public Optional<ReferenceRow> reference(
      TenantId tenant, UUID employeeId, UUID legalEntityId, String currency) {
    return jdbc.sql(
            "SELECT s.package_id, s.id, s.period_start, s.net_minor, EXISTS (SELECT 1 FROM"
                + " payroll.statutory_pack_governance g WHERE g.pack_id = k.pack_id"
                + " AND g.state IN ('ACTIVE', 'RETIRED')) AS operational"
                + " FROM payroll.payroll_payslip s JOIN payroll.payroll_package k"
                + " ON k.tenant_id = s.tenant_id AND k.legal_entity_id = s.legal_entity_id"
                + " AND k.id = s.package_id WHERE s.tenant_id = :tenant"
                + " AND s.employee_id = :employee AND s.legal_entity_id = :entity"
                + " AND k.currency = :currency AND k.status = 'APPROVED' AND k.voided_at IS NULL"
                + " AND NOT s.excluded AND s.net_minor >= 1"
                + " ORDER BY s.period_start DESC, s.approved_at DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("entity", legalEntityId)
        .param("currency", currency)
        .query(
            (rs, row) ->
                new ReferenceRow(
                    rs.getObject("package_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    rs.getObject("period_start", LocalDate.class),
                    rs.getLong("net_minor"),
                    rs.getBoolean("operational")))
        .optional();
  }

  /**
   * Requests of the employee that are open (SUBMITTED or EMPLOYER_CONFIRMED).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the count
   */
  public int openCount(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT count(*) FROM payroll.salary_advance_request WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND status IN ('SUBMITTED', 'EMPLOYER_CONFIRMED')")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(Integer.class)
        .single();
  }

  /**
   * Requests of the employee submitted after an instant (the rolling twelve months).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param since the lower bound (exclusive)
   * @return the count
   */
  public int submittedSince(TenantId tenant, UUID employeeId, Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM payroll.salary_advance_request WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND submitted_at > :since")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("since", Timestamp.from(since))
        .query(Integer.class)
        .single();
  }

  /**
   * The business date of the employee's latest withdrawal or refusal in a legal entity and
   * currency.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param legalEntityId legal entity
   * @param currency currency
   * @return the date, if any
   */
  public Optional<LocalDate> lastBlockedOn(
      TenantId tenant, UUID employeeId, UUID legalEntityId, String currency) {
    return jdbc.sql(
            "SELECT max(ev.occurred_on) AS last_on FROM payroll.salary_advance_request_event ev"
                + " JOIN payroll.salary_advance_request r ON r.tenant_id = ev.tenant_id"
                + " AND r.id = ev.request_id WHERE r.tenant_id = :tenant"
                + " AND r.employee_id = :employee AND r.legal_entity_id = :entity"
                + " AND r.currency = :currency AND ev.to_status IN ('WITHDRAWN', 'EMPLOYER_REFUSED')")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("entity", legalEntityId)
        .param("currency", currency)
        .query((rs, row) -> rs.getObject("last_on", LocalDate.class))
        .optional();
  }

  // -------------------------------------------------------------------------------------------
  // Requests
  // -------------------------------------------------------------------------------------------

  /**
   * One request of the tenant.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the header, if it exists in this tenant
   */
  public Optional<RequestRow> request(TenantId tenant, UUID requestId) {
    return jdbc.sql(
            "SELECT "
                + REQUEST_COLUMNS
                + REQUEST_FROM
                + " WHERE r.tenant_id = :tenant AND r.id = :id")
        .param("tenant", tenant.value())
        .param("id", requestId)
        .query(JdbcSalaryAdvanceRepository::requestRow)
        .optional();
  }

  /**
   * One request of the employee: the employee predicate is part of the statement.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param requestId request
   * @return the header, if it belongs to this employee
   */
  public Optional<RequestRow> myRequest(TenantId tenant, UUID employeeId, UUID requestId) {
    return jdbc.sql(
            "SELECT "
                + REQUEST_COLUMNS
                + REQUEST_FROM
                + " WHERE r.tenant_id = :tenant AND r.employee_id = :employee AND r.id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", requestId)
        .query(JdbcSalaryAdvanceRepository::requestRow)
        .optional();
  }

  /**
   * Locks a request header row for the rest of the transaction and returns it as it now stands.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the locked header, if it exists in this tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RequestRow> lockRequest(TenantId tenant, UUID requestId) {
    Optional<UUID> locked =
        jdbc.sql(
                "SELECT id FROM payroll.salary_advance_request WHERE tenant_id = :tenant"
                    + " AND id = :id FOR UPDATE")
            .param("tenant", tenant.value())
            .param("id", requestId)
            .query((rs, row) -> rs.getObject("id", UUID.class))
            .optional();
    return locked.flatMap(id -> request(tenant, id));
  }

  /**
   * A page of requests, newest first. A {@code null} employee lists the whole tenant
   * (administrators); a {@code null} status lists every status.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee, or {@code null} for administrators
   * @param status status filter, or {@code null}
   * @param after the last row of the previous page, or {@code null}
   * @param limit rows to read (page size plus one)
   * @return the rows
   */
  public List<RequestRow> requestPage(
      TenantId tenant, UUID employeeId, String status, RequestPosition after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(REQUEST_COLUMNS)
            .append(REQUEST_FROM)
            .append(" WHERE r.tenant_id = :tenant");
    if (employeeId != null) {
      sql.append(" AND r.employee_id = :employee");
    }
    if (status != null) {
      sql.append(" AND r.status = :status");
    }
    if (after != null) {
      sql.append(" AND (r.submitted_at, r.id) < (:afterAt, CAST(:afterId AS uuid))");
    }
    sql.append(" ORDER BY r.submitted_at DESC, r.id DESC LIMIT :limit");
    JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", tenant.value());
    if (employeeId != null) {
      spec = spec.param("employee", employeeId);
    }
    if (status != null) {
      spec = spec.param("status", status);
    }
    if (after != null) {
      spec =
          spec.param("afterAt", Timestamp.from(after.submittedAt())).param("afterId", after.id());
    }
    return spec.param("limit", limit).query(JdbcSalaryAdvanceRepository::requestRow).list();
  }

  /**
   * Inserts a request header at version 1, SUBMITTED. The deferred completeness check runs at
   * commit, so the consent and first event follow in the same transaction.
   *
   * @param tenant verified tenant
   * @param request the header
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertRequest(TenantId tenant, NewRequest request) {
    RequestRow row = request.row();
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_request (tenant_id, id, employee_id,"
                + " legal_entity_id, currency, status, row_version, policy_id, policy_sequence,"
                + " partner_code, package_id, payslip_id, reference_period_start,"
                + " reference_net_minor, ceiling_minor, requested_minor, employment_start,"
                + " tenure_months, requested_on, submitted_at, submitted_by) VALUES (:tenant,"
                + " :id, :employee, :entity, :currency, 'SUBMITTED', 1, :policy, :sequence,"
                + " :partner, :package, :payslip, :period, :net, :ceiling, :requested, :start,"
                + " :tenure, :on, :at, :by)")
        .param("tenant", tenant.value())
        .param("id", row.id())
        .param("employee", row.employeeId())
        .param("entity", row.legalEntityId())
        .param("currency", row.currency())
        .param("policy", row.policyId())
        .param("sequence", row.policySequence())
        .param("partner", row.partnerCode())
        .param("package", row.packageId())
        .param("payslip", row.payslipId())
        .param("period", row.referencePeriodStart())
        .param("net", row.referenceNetMinor())
        .param("ceiling", row.ceilingMinor())
        .param("requested", row.requestedMinor())
        .param("start", row.employmentStart())
        .param("tenure", (short) row.tenureMonths())
        .param("on", row.requestedOn())
        .param("at", Timestamp.from(row.submittedAt()))
        .param("by", row.submittedBy())
        .update();
  }

  /**
   * Moves a request along the closed lifecycle.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @param fromVersion the version the caller read under the lock
   * @param toStatus the new status
   * @return rows changed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int advanceRequest(TenantId tenant, UUID requestId, int fromVersion, String toStatus) {
    return jdbc.sql(
            "UPDATE payroll.salary_advance_request SET status = :status,"
                + " row_version = row_version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND row_version = :version")
        .param("tenant", tenant.value())
        .param("id", requestId)
        .param("version", fromVersion)
        .param("status", toStatus)
        .update();
  }

  /**
   * Records the consent of a request.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @param consent the evidence
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertConsent(TenantId tenant, UUID requestId, ConsentRow consent) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_consent (tenant_id, request_id, consent_version,"
                + " language, consented_at, disclosure_sha256, affirmed) VALUES (:tenant,"
                + " :request, :version, :language, :at, :digest, true)")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .param("version", consent.version())
        .param("language", consent.language())
        .param("at", Timestamp.from(consent.consentedAt()))
        .param("digest", consent.disclosureSha256())
        .update();
  }

  /**
   * Records the employer decision of a request.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @param decision the decision (its decider holds privileged MFA and confirmed)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertDecision(TenantId tenant, UUID requestId, DecisionRow decision) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_employer_decision (tenant_id, request_id,"
                + " decision, reason_code, internal_note, decided_by, decided_at, mfa_verified,"
                + " confirmed) VALUES (:tenant, :request, :decision, CAST(:reason AS text),"
                + " CAST(:note AS text), :by, :at, true, true)")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .param("decision", decision.decision())
        .param("reason", decision.reasonCode())
        .param("note", decision.internalNote())
        .param("by", decision.decidedBy())
        .param("at", Timestamp.from(decision.decidedAt()))
        .update();
  }

  /**
   * Appends a lifecycle event.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @param event the event
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertEvent(TenantId tenant, UUID requestId, EventRow event) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_request_event (tenant_id, request_id, sequence,"
                + " from_status, to_status, actor_role, actor_subject, occurred_at, occurred_on,"
                + " reason_code) VALUES (:tenant, :request, :sequence, CAST(:from AS text),"
                + " :to, :role, :actor, :at, :on, CAST(:reason AS text))")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .param("sequence", event.sequence())
        .param("from", event.fromStatus())
        .param("to", event.toStatus())
        .param("role", event.actorRole())
        .param("actor", event.actorSubject())
        .param("at", Timestamp.from(event.occurredAt()))
        .param("on", event.occurredOn())
        .param("reason", event.reasonCode())
        .update();
  }

  /**
   * The history of a request, in order.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the events
   */
  public List<EventRow> events(TenantId tenant, UUID requestId) {
    return jdbc.sql(
            "SELECT sequence, from_status, to_status, actor_role, actor_subject, occurred_at,"
                + " occurred_on, reason_code FROM payroll.salary_advance_request_event"
                + " WHERE tenant_id = :tenant AND request_id = :request ORDER BY sequence")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .query(
            (rs, row) ->
                new EventRow(
                    rs.getInt("sequence"),
                    rs.getString("from_status"),
                    rs.getString("to_status"),
                    rs.getString("actor_role"),
                    rs.getString("actor_subject"),
                    instant(rs, "occurred_at"),
                    rs.getObject("occurred_on", LocalDate.class),
                    rs.getString("reason_code")))
        .list();
  }

  /**
   * The consent evidence of a request.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the evidence, if recorded
   */
  public Optional<ConsentRow> consent(TenantId tenant, UUID requestId) {
    return jdbc.sql(
            "SELECT consent_version, language, consented_at, disclosure_sha256"
                + " FROM payroll.salary_advance_consent WHERE tenant_id = :tenant"
                + " AND request_id = :request")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .query(
            (rs, row) ->
                new ConsentRow(
                    rs.getString("consent_version"),
                    rs.getString("language"),
                    instant(rs, "consented_at"),
                    rs.getString("disclosure_sha256")))
        .optional();
  }

  /**
   * The employer decision of a request.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the decision, if one was recorded
   */
  public Optional<DecisionRow> decision(TenantId tenant, UUID requestId) {
    return jdbc.sql(
            "SELECT decision, reason_code, internal_note, decided_by, decided_at"
                + " FROM payroll.salary_advance_employer_decision WHERE tenant_id = :tenant"
                + " AND request_id = :request")
        .param("tenant", tenant.value())
        .param("request", requestId)
        .query(
            (rs, row) ->
                new DecisionRow(
                    rs.getString("decision"),
                    rs.getString("reason_code"),
                    rs.getString("internal_note"),
                    rs.getString("decided_by"),
                    instant(rs, "decided_at")))
        .optional();
  }

  // -------------------------------------------------------------------------------------------
  // Quota counts
  // -------------------------------------------------------------------------------------------

  /**
   * The subject's committed employee commands (submissions and withdrawals) since an instant.
   *
   * @param tenant verified tenant
   * @param subject acting subject
   * @param role {@code EMPLOYEE} or {@code TENANT_ADMIN}
   * @param since lower bound (inclusive)
   * @return the count and the oldest instant
   */
  public Usage eventsSince(TenantId tenant, String subject, String role, Instant since) {
    return jdbc.sql(
            "SELECT count(*) AS total, min(occurred_at) AS oldest"
                + " FROM payroll.salary_advance_request_event WHERE tenant_id = :tenant"
                + " AND actor_subject = :subject AND actor_role = :role AND occurred_at >= :since")
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("role", role)
        .param("since", Timestamp.from(since))
        .query((rs, row) -> new Usage(rs.getInt("total"), instantOrNull(rs, "oldest")))
        .single();
  }

  /**
   * The subject's committed policy changes (versions and cancellations) since an instant.
   *
   * @param tenant verified tenant
   * @param subject acting subject
   * @param since lower bound (inclusive)
   * @return the count and the oldest instant
   */
  public Usage policyChangesSince(TenantId tenant, String subject, Instant since) {
    return jdbc.sql(
            "SELECT count(*) AS total, min(at) AS oldest FROM ("
                + " SELECT created_at AS at FROM payroll.salary_advance_policy"
                + " WHERE tenant_id = :tenant AND created_by = :subject AND created_at >= :since"
                + " UNION ALL SELECT cancelled_at AS at FROM payroll.salary_advance_policy"
                + " WHERE tenant_id = :tenant AND cancelled_by = :subject"
                + " AND cancelled_at >= :since) changes")
        .param("tenant", tenant.value())
        .param("subject", subject)
        .param("since", Timestamp.from(since))
        .query((rs, row) -> new Usage(rs.getInt("total"), instantOrNull(rs, "oldest")))
        .single();
  }

  // -------------------------------------------------------------------------------------------
  // Mapping
  // -------------------------------------------------------------------------------------------

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static PolicyRow policyRow(ResultSet rs, int row) throws SQLException {
    long absolute = rs.getLong("absolute_max_minor");
    Long absoluteMax = rs.wasNull() ? null : absolute;
    return new PolicyRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("currency"),
        rs.getInt("sequence"),
        rs.getString("code"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class),
        rs.getBoolean("enabled"),
        rs.getInt("min_tenure_months"),
        rs.getInt("max_percent"),
        absoluteMax,
        rs.getLong("min_request_minor"),
        rs.getInt("max_open_requests"),
        rs.getInt("waiting_days"),
        rs.getString("consent_version"),
        rs.getString("partner_code"),
        instant(rs, "created_at"),
        instantOrNull(rs, "cancelled_at"));
  }

  private static RequestRow requestRow(ResultSet rs, int row) throws SQLException {
    return new RequestRow(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getInt("row_version"),
        rs.getObject("policy_id", UUID.class),
        rs.getInt("policy_sequence"),
        rs.getString("partner_code"),
        rs.getObject("package_id", UUID.class),
        rs.getObject("payslip_id", UUID.class),
        rs.getObject("reference_period_start", LocalDate.class),
        rs.getLong("reference_net_minor"),
        rs.getLong("ceiling_minor"),
        rs.getLong("requested_minor"),
        rs.getObject("employment_start", LocalDate.class),
        rs.getInt("tenure_months"),
        rs.getObject("requested_on", LocalDate.class),
        instant(rs, "submitted_at"),
        rs.getString("submitted_by"));
  }
}
