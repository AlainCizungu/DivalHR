package com.divalhr.core.people.schedule.internal;

import com.divalhr.core.people.schedule.domain.Substitution.Decision;
import com.divalhr.core.people.schedule.domain.Substitution.HistoryItem;
import com.divalhr.core.people.schedule.domain.Substitution.InboxItem;
import com.divalhr.core.people.schedule.domain.Substitution.Person;
import com.divalhr.core.people.schedule.domain.Substitution.Request;
import com.divalhr.core.people.schedule.domain.Substitution.Shift;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Shift-substitution evidence, its pending projection and the coverage moves of an approval
 * (MVP-051, V27; ADR 0015), and the employment, placement, reporting-line, separation and
 * operational-assignment facts a request and a decision are checked against. Every query names the
 * verified tenant. Runs in the caller's transaction; the caller holds the manager-graph lock and
 * the tenant's scheduling lock for every write.
 */
@Repository
public class JdbcWorkScheduleSubstitutionRepository {

  /** The immutable shift with its schedule's site, time zone and operational state. */
  private static final String SHIFT_COLUMNS =
      " l.id AS slot_id, l.schedule_id, s.site_id, s.site_timezone, l.start_local, l.end_local,"
          + " l.start_at, l.end_at, l.label_en, l.label_fr, l.assigned_employee_id,"
          + " (NOT EXISTS (SELECT 1 FROM people.work_schedule_cancellation x"
          + " WHERE x.tenant_id = s.tenant_id AND x.schedule_id = s.id)"
          + " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_replacement x"
          + " WHERE x.tenant_id = s.tenant_id AND x.schedule_id = s.id)) AS operational";

  private static final String REQUEST_COLUMNS =
      " r.id AS request_id, r.schedule_id AS request_schedule_id, r.slot_id AS request_slot_id,"
          + " r.employee_id AS request_employee_id, r.employment_id AS request_employment_id,"
          + " r.reason_locale AS request_reason_locale, r.reason AS request_reason,"
          + " r.requested_at";

  private static final String DECISION_COLUMNS =
      " d.id AS decision_id, d.outcome, d.manager_employee_id, d.replacement_employee_id,"
          + " d.replacement_employment_id, d.reason_locale AS decision_reason_locale,"
          + " d.reason AS decision_reason, d.decided_at";

  /**
   * An employment {@code e} of the employee that covers every occupied date and is placed at the
   * site on the first date (the qualifying employment, D51-1).
   */
  private static final String QUALIFIES =
      " e.effective_from <= :first AND (e.effective_to IS NULL OR e.effective_to >= :last)"
          + " AND EXISTS (SELECT 1 FROM people.employment_assignment pa"
          + " WHERE pa.tenant_id = e.tenant_id AND pa.employment_id = e.id"
          + " AND pa.kind = 'PLACEMENT' AND pa.superseded_by_change_id IS NULL"
          + " AND pa.site_id = :site AND pa.effective_from <= :first"
          + " AND (pa.effective_to IS NULL OR pa.effective_to >= :first))";

  /** Employment {@code e} has an active MANAGER line to {@code :manager} on the first date. */
  private static final String REPORTS =
      " EXISTS (SELECT 1 FROM people.employment_assignment ma"
          + " WHERE ma.tenant_id = e.tenant_id AND ma.employment_id = e.id"
          + " AND ma.kind = 'MANAGER' AND ma.superseded_by_change_id IS NULL"
          + " AND ma.manager_employee_id = :manager AND ma.effective_from <= :first"
          + " AND (ma.effective_to IS NULL OR ma.effective_to >= :first))";

  private static final String PERSON_COLUMNS =
      "p.id, p.employee_number, p.given_names, p.family_name";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc named-parameter JDBC
   */
  public JdbcWorkScheduleSubstitutionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------
  // Shifts and coverage
  // ------------------------------------------------------------------------------------------

  /**
   * A slot of the tenant with its schedule (unlocked).
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return the shift, or empty for an unknown or foreign slot
   */
  public Optional<Shift> shift(TenantId tenant, UUID slotId) {
    return jdbc
        .query(
            "SELECT"
                + SHIFT_COLUMNS
                + " FROM people.work_schedule_slot l JOIN people.work_schedule s"
                + " ON s.tenant_id = l.tenant_id AND s.id = l.schedule_id"
                + " WHERE l.tenant_id = :tenant AND l.id = :slot",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId),
            (rs, n) -> shift(rs))
        .stream()
        .findFirst();
  }

  /**
   * The slot's operational coverage: its employee and, for a substitute, the approving decision.
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return the coverage, or empty when the slot has none
   */
  public Optional<Coverage> coverage(TenantId tenant, UUID slotId) {
    return jdbc
        .query(
            "SELECT employee_id, decision_id FROM people.work_schedule_slot_coverage"
                + " WHERE tenant_id = :tenant AND slot_id = :slot",
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId),
            (rs, n) ->
                new Coverage(
                    rs.getObject("employee_id", UUID.class),
                    rs.getObject("decision_id", UUID.class)))
        .stream()
        .findFirst();
  }

  /**
   * A slot's operational coverage.
   *
   * @param employeeId the covering employee
   * @param decisionId the approving decision, or {@code null} for the original employee
   */
  public record Coverage(UUID employeeId, UUID decisionId) {}

  /**
   * Whether the slot has an approved substitution.
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return true when approved
   */
  public boolean substituted(TenantId tenant, UUID slotId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM people.work_schedule_substitution_decision"
            + " WHERE tenant_id = :tenant AND slot_id = :slot AND outcome = 'APPROVED')",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId));
  }

  /**
   * Whether the slot has a pending request.
   *
   * @param tenant verified tenant
   * @param slotId slot
   * @return true when pending
   */
  public boolean slotPending(TenantId tenant, UUID slotId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending"
            + " WHERE tenant_id = :tenant AND slot_id = :slot)",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("slot", slotId));
  }

  // ------------------------------------------------------------------------------------------
  // Employments and reporting lines
  // ------------------------------------------------------------------------------------------

  /**
   * Locks {@code FOR SHARE}, in UUID order, the listed employments and every employment of the
   * employee overlapping the dates (lock order step 4): employment changes, corrections and
   * separations, which lock an employment {@code FOR UPDATE}, wait until this commits, and this,
   * waiting on one, reads the changed facts.
   *
   * @param tenant verified tenant
   * @param employmentIds employments to lock (may be empty)
   * @param employeeId an employee whose overlapping employments are locked too, or {@code null}
   * @param from first date
   * @param to last date
   */
  public void shareEmployments(
      TenantId tenant,
      Collection<UUID> employmentIds,
      UUID employeeId,
      LocalDate from,
      LocalDate to) {
    List<UUID> ids =
        employmentIds.isEmpty() ? List.of(new UUID(0L, 0L)) : List.copyOf(employmentIds);
    jdbc.query(
        "SELECT id FROM people.employment WHERE tenant_id = :tenant AND (id IN (:ids)"
            + " OR (employee_id = CAST(:employee AS uuid) AND effective_from <= :to"
            + " AND (effective_to IS NULL OR effective_to >= :from))) ORDER BY id FOR SHARE",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("ids", ids)
            .addValue("employee", employeeId)
            .addValue("from", from)
            .addValue("to", to),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  /**
   * The employee's employments that cover every date the shift occupies and are placed at its site
   * on its first date: exactly one is the qualifying employment (D51-1).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param shift the shift
   * @return the qualifying employments, by ID
   */
  public List<UUID> qualifyingEmployments(TenantId tenant, UUID employeeId, Shift shift) {
    return jdbc.query(
        "SELECT e.id FROM people.employment e WHERE e.tenant_id = :tenant"
            + " AND e.employee_id = :employee AND"
            + QUALIFIES
            + " ORDER BY e.id",
        shiftParams(tenant, shift).addValue("employee", employeeId),
        (rs, n) -> rs.getObject("id", UUID.class));
  }

  /**
   * Whether the employment has an active MANAGER line on the shift's first date.
   *
   * @param tenant verified tenant
   * @param employmentId employment
   * @param date the shift's site-local start date
   * @return true when managed
   */
  public boolean managed(TenantId tenant, UUID employmentId, LocalDate date) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM people.employment_assignment a"
            + " WHERE a.tenant_id = :tenant AND a.employment_id = :employment"
            + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL"
            + " AND a.manager_employee_id IS NOT NULL AND a.effective_from <= :first"
            + " AND (a.effective_to IS NULL OR a.effective_to >= :first))",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employment", employmentId)
            .addValue("first", date));
  }

  /**
   * Whether the employment reports to the manager on the date (the dynamic authority, D51-1).
   *
   * @param tenant verified tenant
   * @param employmentId the request's stored employment
   * @param date the shift's site-local start date
   * @param managerId the caller's linked employee
   * @return true when the manager is the effective manager
   */
  public boolean reportsTo(TenantId tenant, UUID employmentId, LocalDate date, UUID managerId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM people.employment e WHERE e.tenant_id = :tenant"
            + " AND e.id = :employment AND"
            + REPORTS
            + ")",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employment", employmentId)
            .addValue("first", date)
            .addValue("manager", managerId));
  }

  // ------------------------------------------------------------------------------------------
  // Substitutes
  // ------------------------------------------------------------------------------------------

  /**
   * One page of eligible substitutes for the shift (D51-2, D51-5): direct reports of the manager on
   * the shift's first date, never the original employee or the manager, with exactly one qualifying
   * employment, that one reporting to the manager, no scheduled or effective separation before the
   * shift ends and no overlapping operational assignment at any site (half-open instants), whose
   * number starts with the prefix or whose name words match, in employee-number order.
   *
   * @param tenant verified tenant
   * @param shift the shift
   * @param managerId the manager
   * @param originalId the original employee
   * @param numberPrefix upper-case number prefix, or {@code null}
   * @param words normalized name words (each a word prefix), or empty
   * @param afterNumber keyset number, or {@code null}
   * @param afterId keyset ID, or {@code null}
   * @param limit rows to read
   * @return the candidates
   */
  public List<Person> candidates(
      TenantId tenant,
      Shift shift,
      UUID managerId,
      UUID originalId,
      String numberPrefix,
      List<String> words,
      String afterNumber,
      UUID afterId,
      int limit) {
    MapSqlParameterSource params = substituteParams(tenant, shift, managerId, originalId);
    StringBuilder sql = new StringBuilder(substituteQuery(PERSON_COLUMNS, true));
    List<String> alternatives = new ArrayList<>();
    if (numberPrefix != null) {
      alternatives.add("p.employee_number LIKE :number ESCAPE '\\'");
      params.addValue("number", like(numberPrefix) + "%");
    }
    if (!words.isEmpty()) {
      List<String> all = new ArrayList<>();
      for (int i = 0; i < words.size(); i++) {
        all.add("p.search_key LIKE :w" + i + " ESCAPE '\\'");
        params.addValue("w" + i, "% " + like(words.get(i)) + "%");
      }
      alternatives.add("(" + String.join(" AND ", all) + ")");
    }
    if (alternatives.isEmpty()) {
      return List.of();
    }
    sql.append(" AND (").append(String.join(" OR ", alternatives)).append(')');
    if (afterId != null) {
      sql.append(" AND (p.employee_number, p.id) > (:afterNumber, :afterId)");
      params.addValue("afterNumber", afterNumber).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY p.employee_number, p.id LIMIT :limit");
    params.addValue("limit", limit);
    return jdbc.query(sql.toString(), params, (rs, n) -> person(rs, ""));
  }

  /**
   * The substitute's qualifying employment when every rule but the overlap holds (decision-time
   * re-evaluation with the candidate list's exact rules).
   *
   * @param tenant verified tenant
   * @param shift the shift
   * @param managerId the deciding manager
   * @param originalId the original employee
   * @param substituteId the chosen employee
   * @return the employment, or empty when not eligible
   */
  public Optional<UUID> eligibleEmployment(
      TenantId tenant, Shift shift, UUID managerId, UUID originalId, UUID substituteId) {
    MapSqlParameterSource params =
        substituteParams(tenant, shift, managerId, originalId).addValue("candidate", substituteId);
    List<UUID> employments =
        jdbc.query(
            "SELECT e.id FROM people.employment e WHERE e.tenant_id = :tenant"
                + " AND e.employee_id IN ("
                + substituteQuery("p.id", false)
                + " AND p.id = :candidate) AND"
                + QUALIFIES
                + " AND"
                + REPORTS,
            params,
            (rs, n) -> rs.getObject("id", UUID.class));
    return employments.size() == 1 ? Optional.of(employments.get(0)) : Optional.empty();
  }

  /**
   * Whether the employee has an operational assignment overlapping the instants (half-open).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param startAt start
   * @param endAt end
   * @return true on overlap
   */
  public boolean overlaps(TenantId tenant, UUID employeeId, Instant startAt, Instant endAt) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM people.work_schedule_slot_coverage c"
            + " WHERE c.tenant_id = :tenant AND c.employee_id = :employee"
            + " AND tstzrange(c.start_at, c.end_at, '[)') && tstzrange(:startAt, :endAt, '[)'))",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("startAt", Timestamp.from(startAt))
            .addValue("endAt", Timestamp.from(endAt)));
  }

  /**
   * An employee's number, for a candidate cursor's keyset.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the number
   */
  public Optional<String> employeeNumber(TenantId tenant, UUID employeeId) {
    return jdbc
        .query(
            "SELECT employee_number FROM people.employee WHERE tenant_id = :tenant AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", employeeId),
            (rs, n) -> rs.getString("employee_number"))
        .stream()
        .findFirst();
  }

  /**
   * An employee's current minimal identity.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the identity
   */
  public Optional<Person> person(TenantId tenant, UUID employeeId) {
    return jdbc
        .query(
            "SELECT p.id, p.employee_number, p.given_names, p.family_name FROM people.employee p"
                + " WHERE p.tenant_id = :tenant AND p.id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", employeeId),
            (rs, n) -> person(rs, ""))
        .stream()
        .findFirst();
  }

  /**
   * The eligibility query (without its name filter): {@code p} is a direct report of the manager on
   * the first date, neither the original employee nor the manager, has exactly one qualifying
   * employment and that one reports to the manager, and has no separation before the shift ends;
   * with {@code overlap}, also no overlapping operational assignment.
   */
  private static String substituteQuery(String columns, boolean overlap) {
    String sql =
        "SELECT "
            + columns
            + " FROM people.employee p"
            + " WHERE p.tenant_id = :tenant AND p.id <> :original AND p.id <> :manager"
            + " AND p.id IN (SELECT ra.employee_id FROM people.employment_assignment ra"
            + " WHERE ra.tenant_id = :tenant AND ra.kind = 'MANAGER'"
            + " AND ra.superseded_by_change_id IS NULL AND ra.manager_employee_id = :manager"
            + " AND ra.effective_from <= :first"
            + " AND (ra.effective_to IS NULL OR ra.effective_to >= :first))"
            + " AND (SELECT count(*) FROM people.employment e WHERE e.tenant_id = p.tenant_id"
            + " AND e.employee_id = p.id AND"
            + QUALIFIES
            + ") = 1"
            + " AND EXISTS (SELECT 1 FROM people.employment e WHERE e.tenant_id = p.tenant_id"
            + " AND e.employee_id = p.id AND"
            + QUALIFIES
            + " AND"
            + REPORTS
            + ")"
            + " AND NOT EXISTS (SELECT 1 FROM people.employment_separation x"
            + " WHERE x.tenant_id = p.tenant_id AND x.employee_id = p.id"
            + " AND x.state IN ('SCHEDULED', 'EFFECTIVE') AND x.effective_at < :endAt)";
    if (overlap) {
      sql +=
          " AND NOT EXISTS (SELECT 1 FROM people.work_schedule_slot_coverage c"
              + " WHERE c.tenant_id = p.tenant_id AND c.employee_id = p.id"
              + " AND tstzrange(c.start_at, c.end_at, '[)')"
              + " && tstzrange(:startAt, :endAt, '[)'))";
    }
    return sql;
  }

  private static MapSqlParameterSource substituteParams(
      TenantId tenant, Shift shift, UUID managerId, UUID originalId) {
    return shiftParams(tenant, shift)
        .addValue("manager", managerId)
        .addValue("original", originalId)
        .addValue("startAt", Timestamp.from(shift.startAt()))
        .addValue("endAt", Timestamp.from(shift.endAt()));
  }

  private static MapSqlParameterSource shiftParams(TenantId tenant, Shift shift) {
    return new MapSqlParameterSource()
        .addValue("tenant", tenant.value())
        .addValue("site", shift.siteId())
        .addValue("first", shift.firstDate())
        .addValue("last", shift.lastDate());
  }

  // ------------------------------------------------------------------------------------------
  // Requests and decisions
  // ------------------------------------------------------------------------------------------

  /**
   * A request of the tenant (unlocked).
   *
   * @param tenant verified tenant
   * @param id request
   * @return the request
   */
  public Optional<Request> request(TenantId tenant, UUID id) {
    return requestRow(tenant, id, "");
  }

  /**
   * Locks a request {@code FOR UPDATE} (lock order step 6, after its schedule): two decisions of
   * one request serialize on it.
   *
   * @param tenant verified tenant
   * @param id request
   * @return the request
   */
  public Optional<Request> lockRequest(TenantId tenant, UUID id) {
    return requestRow(tenant, id, " FOR UPDATE");
  }

  private Optional<Request> requestRow(TenantId tenant, UUID id, String lock) {
    return jdbc
        .query(
            "SELECT"
                + REQUEST_COLUMNS
                + " FROM people.work_schedule_substitution_request r"
                + " WHERE r.tenant_id = :tenant AND r.id = :id"
                + lock,
            new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id),
            (rs, n) -> request(rs))
        .stream()
        .findFirst();
  }

  /**
   * A request's decision.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return the decision
   */
  public Optional<Decision> decision(TenantId tenant, UUID requestId) {
    return jdbc
        .query(
            "SELECT d.request_id,"
                + DECISION_COLUMNS
                + " FROM people.work_schedule_substitution_decision d"
                + " WHERE d.tenant_id = :tenant AND d.request_id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", requestId),
            (rs, n) -> decision(rs, rs.getObject("request_id", UUID.class)))
        .stream()
        .findFirst();
  }

  /**
   * Whether the request is pending.
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return true when pending
   */
  public boolean pending(TenantId tenant, UUID requestId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM people.work_schedule_substitution_pending"
            + " WHERE tenant_id = :tenant AND request_id = :id)",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", requestId));
  }

  /**
   * Records a request and its pending projection.
   *
   * @param tenant verified tenant
   * @param request the request
   * @param by verified subject
   */
  public void insertRequest(TenantId tenant, Request request, String by) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", request.id())
            .addValue("schedule", request.scheduleId())
            .addValue("slot", request.slotId())
            .addValue("employee", request.employeeId())
            .addValue("employment", request.employmentId())
            .addValue("locale", request.reasonLocale())
            .addValue("reason", request.reason())
            .addValue("at", Timestamp.from(request.requestedAt()))
            .addValue("by", by);
    jdbc.update(
        "INSERT INTO people.work_schedule_substitution_request (tenant_id, id, schedule_id,"
            + " slot_id, employee_id, employment_id, reason_locale, reason, requested_at,"
            + " requested_by) VALUES (:tenant, :id, :schedule, :slot, :employee, :employment,"
            + " :locale, :reason, :at, :by)",
        params);
    jdbc.update(
        "INSERT INTO people.work_schedule_substitution_pending (tenant_id, request_id, slot_id,"
            + " schedule_id) VALUES (:tenant, :id, :slot, :schedule)",
        params);
  }

  /**
   * Records a decision (the evidence first: the coverage guard and the pending guard require it).
   *
   * @param tenant verified tenant
   * @param request the request
   * @param decision the decision
   * @param by verified subject
   */
  public void insertDecision(TenantId tenant, Request request, Decision decision, String by) {
    jdbc.update(
        "INSERT INTO people.work_schedule_substitution_decision (tenant_id, id, request_id,"
            + " schedule_id, slot_id, original_employee_id, outcome, manager_employee_id,"
            + " replacement_employee_id, replacement_employment_id, reason_locale, reason,"
            + " decided_at, decided_by) VALUES (:tenant, :id, :request, :schedule, :slot,"
            + " :original, :outcome, :manager, :substitute, :substituteEmployment, :locale,"
            + " :reason, :at, :by)",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("id", decision.id())
            .addValue("request", request.id())
            .addValue("schedule", request.scheduleId())
            .addValue("slot", request.slotId())
            .addValue("original", request.employeeId())
            .addValue("outcome", decision.outcome())
            .addValue("manager", decision.managerEmployeeId())
            .addValue("substitute", decision.substituteEmployeeId())
            .addValue("substituteEmployment", decision.substituteEmploymentId())
            .addValue("locale", decision.reasonLocale())
            .addValue("reason", decision.reason())
            .addValue("at", Timestamp.from(decision.decidedAt()))
            .addValue("by", by));
  }

  /**
   * Moves an approved slot's operational coverage from the original employee to the substitute: the
   * original row is deleted, then the substitute's row inserted on the same slot, schedule and
   * instants (the employee exclusion checks it immediately).
   *
   * @param tenant verified tenant
   * @param shift the shift
   * @param decision the approval
   * @return the number of original rows removed (1)
   */
  public int moveCoverage(TenantId tenant, Shift shift, Decision decision) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("slot", shift.slotId())
            .addValue("schedule", shift.scheduleId())
            .addValue("employee", decision.substituteEmployeeId())
            .addValue("decision", decision.id())
            .addValue("startAt", Timestamp.from(shift.startAt()))
            .addValue("endAt", Timestamp.from(shift.endAt()));
    int removed =
        jdbc.update(
            "DELETE FROM people.work_schedule_slot_coverage WHERE tenant_id = :tenant"
                + " AND slot_id = :slot AND decision_id IS NULL",
            params);
    jdbc.update(
        "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
            + " employee_id, start_at, end_at, decision_id) VALUES (:tenant, :slot, :schedule,"
            + " :employee, :startAt, :endAt, :decision)",
        params);
    return removed;
  }

  /**
   * Removes a decided request's pending projection.
   *
   * @param tenant verified tenant
   * @param requestId request
   */
  public void removePending(TenantId tenant, UUID requestId) {
    jdbc.update(
        "DELETE FROM people.work_schedule_substitution_pending"
            + " WHERE tenant_id = :tenant AND request_id = :id",
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", requestId));
  }

  /**
   * Runs the deferred substitution and coverage checks now, so a violation fails the operation
   * before its audit record and outbox event are written.
   *
   * @param tenant verified tenant (the check is transaction-wide)
   */
  public void checkConsistent(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS people.work_schedule_substitution_request_exact,"
                + " people.work_schedule_substitution_decision_exact,"
                + " people.work_schedule_substitution_pending_exact,"
                + " people.work_schedule_slot_coverage_bound,"
                + " people.work_schedule_slot_coverage_exact IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * One keyset page of the employee's own requests, newest first.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param afterAt the previous page's last request instant, or {@code null}
   * @param afterId its ID, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<HistoryItem> history(
      TenantId tenant, UUID employeeId, Instant afterAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT"
                + REQUEST_COLUMNS
                + ","
                + SHIFT_COLUMNS
                + ", (q.request_id IS NOT NULL) AS pending,"
                + DECISION_COLUMNS
                + ", sp.id AS sub_id, sp.employee_number AS sub_employee_number,"
                + " sp.given_names AS sub_given_names, sp.family_name AS sub_family_name"
                + " FROM people.work_schedule_substitution_request r"
                + " JOIN people.work_schedule_slot l ON l.tenant_id = r.tenant_id"
                + " AND l.id = r.slot_id"
                + " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id"
                + " AND s.id = l.schedule_id"
                + " LEFT JOIN people.work_schedule_substitution_pending q"
                + " ON q.tenant_id = r.tenant_id AND q.request_id = r.id"
                + " LEFT JOIN people.work_schedule_substitution_decision d"
                + " ON d.tenant_id = r.tenant_id AND d.request_id = r.id"
                + " LEFT JOIN people.employee sp ON sp.tenant_id = d.tenant_id"
                + " AND sp.id = d.replacement_employee_id"
                + " WHERE r.tenant_id = :tenant AND r.employee_id = :employee");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("limit", limit);
    if (afterId != null) {
      sql.append(" AND (r.requested_at, r.id) < (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY r.requested_at DESC, r.id DESC LIMIT :limit");
    return jdbc.query(
        sql.toString(),
        params,
        (rs, n) -> {
          Request request = request(rs);
          UUID decisionId = rs.getObject("decision_id", UUID.class);
          UUID substituteId = rs.getObject("sub_id", UUID.class);
          return new HistoryItem(
              request,
              shift(rs),
              rs.getBoolean("pending"),
              decisionId == null ? null : decision(rs, request.id()),
              substituteId == null ? null : person(rs, "sub_"));
        });
  }

  /**
   * One of the employee's own requests' instant (a cursor position).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's linked employee
   * @param requestId request
   * @return its instant
   */
  public Optional<Instant> ownRequestedAt(TenantId tenant, UUID employeeId, UUID requestId) {
    return jdbc
        .query(
            "SELECT requested_at FROM people.work_schedule_substitution_request"
                + " WHERE tenant_id = :tenant AND employee_id = :employee AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("employee", employeeId)
                .addValue("id", requestId),
            (rs, n) -> rs.getTimestamp("requested_at").toInstant())
        .stream()
        .findFirst();
  }

  /**
   * One keyset page of the pending, not yet started requests routed to the manager, oldest first.
   *
   * @param tenant verified tenant
   * @param managerId the caller's linked employee
   * @param asOf the server instant shifts must start after
   * @param afterAt the previous page's last request instant, or {@code null}
   * @param afterId its ID, or {@code null}
   * @param limit rows to read
   * @return the rows
   */
  public List<InboxItem> inbox(
      TenantId tenant, UUID managerId, Instant asOf, Instant afterAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT"
                + REQUEST_COLUMNS
                + ","
                + SHIFT_COLUMNS
                + ", p.id, p.employee_number, p.given_names, p.family_name"
                + " FROM people.work_schedule_substitution_pending q"
                + " JOIN people.work_schedule_substitution_request r"
                + " ON r.tenant_id = q.tenant_id AND r.id = q.request_id"
                + " JOIN people.work_schedule_slot l ON l.tenant_id = r.tenant_id"
                + " AND l.id = r.slot_id"
                + " JOIN people.work_schedule s ON s.tenant_id = l.tenant_id"
                + " AND s.id = l.schedule_id"
                + " JOIN people.employee p ON p.tenant_id = r.tenant_id AND p.id = r.employee_id"
                + " WHERE q.tenant_id = :tenant AND l.start_at > :asOf"
                + " AND r.employee_id <> :manager"
                + " AND r.employment_id IN (SELECT ma.employment_id"
                + " FROM people.employment_assignment ma WHERE ma.tenant_id = :tenant"
                + " AND ma.kind = 'MANAGER' AND ma.superseded_by_change_id IS NULL"
                + " AND ma.manager_employee_id = :manager"
                + " AND ma.effective_from <= l.start_local::date"
                + " AND (ma.effective_to IS NULL OR ma.effective_to >= l.start_local::date))");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("manager", managerId)
            .addValue("asOf", Timestamp.from(asOf))
            .addValue("limit", limit);
    if (afterId != null) {
      sql.append(" AND (r.requested_at, r.id) > (:afterAt, :afterId)");
      params.addValue("afterAt", Timestamp.from(afterAt)).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY r.requested_at, r.id LIMIT :limit");
    return jdbc.query(
        sql.toString(), params, (rs, n) -> new InboxItem(request(rs), shift(rs), person(rs, "")));
  }

  /**
   * A request's instant (a manager cursor position).
   *
   * @param tenant verified tenant
   * @param requestId request
   * @return its instant
   */
  public Optional<Instant> requestedAt(TenantId tenant, UUID requestId) {
    return jdbc
        .query(
            "SELECT requested_at FROM people.work_schedule_substitution_request"
                + " WHERE tenant_id = :tenant AND id = :id",
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("id", requestId),
            (rs, n) -> rs.getTimestamp("requested_at").toInstant())
        .stream()
        .findFirst();
  }

  // ------------------------------------------------------------------------------------------
  // Mapping
  // ------------------------------------------------------------------------------------------

  private boolean exists(String sql, MapSqlParameterSource params) {
    return Boolean.TRUE.equals(jdbc.queryForObject(sql, params, Boolean.class));
  }

  private static String like(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Shift shift(ResultSet rs) throws SQLException {
    return new Shift(
        rs.getObject("slot_id", UUID.class),
        rs.getObject("schedule_id", UUID.class),
        rs.getObject("site_id", UUID.class),
        rs.getString("site_timezone"),
        rs.getObject("start_local", LocalDateTime.class),
        rs.getObject("end_local", LocalDateTime.class),
        rs.getTimestamp("start_at").toInstant(),
        rs.getTimestamp("end_at").toInstant(),
        rs.getString("label_en"),
        rs.getString("label_fr"),
        rs.getObject("assigned_employee_id", UUID.class),
        rs.getBoolean("operational"));
  }

  private static Request request(ResultSet rs) throws SQLException {
    return new Request(
        rs.getObject("request_id", UUID.class),
        rs.getObject("request_schedule_id", UUID.class),
        rs.getObject("request_slot_id", UUID.class),
        rs.getObject("request_employee_id", UUID.class),
        rs.getObject("request_employment_id", UUID.class),
        rs.getString("request_reason_locale"),
        rs.getString("request_reason"),
        rs.getTimestamp("requested_at").toInstant());
  }

  private static Decision decision(ResultSet rs, UUID requestId) throws SQLException {
    return new Decision(
        rs.getObject("decision_id", UUID.class),
        requestId,
        rs.getString("outcome"),
        rs.getObject("manager_employee_id", UUID.class),
        rs.getObject("replacement_employee_id", UUID.class),
        rs.getObject("replacement_employment_id", UUID.class),
        rs.getString("decision_reason_locale"),
        rs.getString("decision_reason"),
        rs.getTimestamp("decided_at").toInstant());
  }

  private static Person person(ResultSet rs, String prefix) throws SQLException {
    return new Person(
        rs.getObject(prefix + "id", UUID.class),
        rs.getString(prefix + "employee_number"),
        rs.getString(prefix + "given_names"),
        rs.getString(prefix + "family_name"));
  }
}
