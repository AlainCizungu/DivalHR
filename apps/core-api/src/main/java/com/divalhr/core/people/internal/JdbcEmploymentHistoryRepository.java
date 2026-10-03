package com.divalhr.core.people.internal;

import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.domain.history.AssignmentValue;
import com.divalhr.core.people.domain.history.ChangeReason;
import com.divalhr.core.people.domain.history.ChangeTiming;
import com.divalhr.core.people.domain.history.ChangeType;
import com.divalhr.core.people.domain.history.CompensationBasis;
import com.divalhr.core.people.domain.history.ContractClassification;
import com.divalhr.core.people.domain.history.NewAssignment;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employment history persistence (MVP-021). Every method takes the verified {@link TenantId} and
 * filters on it. Rows are only ever inserted, or superseded once; nothing is updated in place or
 * deleted (the V14 triggers enforce it).
 */
@Repository
public class JdbcEmploymentHistoryRepository {

  private static final String ASSIGNMENT_COLUMNS =
      "id, kind, effective_from, effective_to, legal_entity_id, site_id, department_id,"
          + " cost_center_id, team_id, manager_employee_id, contract_code,"
          + " compensation_basis_code, created_by_change_id, origin_change_id,"
          + " restores_assignment_id, superseded_by_change_id, superseded_at";

  private static final String CHANGE_COLUMNS =
      "id, employee_id, employment_id, type, effective_from, kinds, reason_code, timing,"
          + " cancels_change_id, state, recorded_at, version_after";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcEmploymentHistoryRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /**
   * An employee (Confidential).
   *
   * @param id employee ID
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  public record EmployeeRecord(
      UUID id, String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "EmployeeRecord[" + id + "]";
    }
  }

  /**
   * An employment (dates are Restricted HR).
   *
   * @param id employment ID
   * @param employeeId employee
   * @param start first day
   * @param end last day, or {@code null}
   * @param version timeline version
   */
  public record EmploymentRecord(
      UUID id, UUID employeeId, LocalDate start, LocalDate end, long version) {}

  /**
   * A recorded change (Restricted HR).
   *
   * @param id change ID
   * @param employeeId employee
   * @param employmentId employment
   * @param type type
   * @param effectiveFrom effective date
   * @param kinds kinds
   * @param reason reason, or {@code null}
   * @param timing timing, or {@code null} for the hire
   * @param cancelsChangeId cancelled change (cancellations), or {@code null}
   * @param cancelled whether a cancellation cancelled it
   * @param recordedAt when it was recorded
   * @param versionAfter employment version after it
   */
  public record ChangeRecord(
      UUID id,
      UUID employeeId,
      UUID employmentId,
      ChangeType type,
      LocalDate effectiveFrom,
      Set<AssignmentKind> kinds,
      ChangeReason reason,
      ChangeTiming timing,
      UUID cancelsChangeId,
      boolean cancelled,
      Instant recordedAt,
      long versionAfter) {

    /** Defensively copies the kinds. */
    public ChangeRecord {
      kinds = kinds.isEmpty() ? Set.of() : EnumSet.copyOf(kinds);
    }
  }

  /**
   * A manager edge used by the cycle walk.
   *
   * @param employeeId the employee whose manager it is
   * @param managerId the manager
   * @param from first day
   * @param to last day, or {@code null}
   */
  public record ManagerEdge(UUID employeeId, UUID managerId, LocalDate from, LocalDate to) {}

  // -------------------------------------------------------------------------------------------
  // Employees
  // -------------------------------------------------------------------------------------------

  /**
   * One employee.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the employee, if in the tenant
   */
  public Optional<EmployeeRecord> employee(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT id, employee_number, given_names, family_name FROM people.employee"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", employeeId)
        .query(JdbcEmploymentHistoryRepository::employee)
        .optional();
  }

  /**
   * Employees by ID (manager display).
   *
   * @param tenant verified tenant
   * @param ids employee IDs
   * @return employees found, by ID
   */
  public Map<UUID, EmployeeRecord> employees(TenantId tenant, Set<UUID> ids) {
    Map<UUID, EmployeeRecord> found = new HashMap<>();
    if (ids.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT id, employee_number, given_names, family_name FROM people.employee"
                + " WHERE tenant_id = :tenant AND id = ANY(:ids)")
        .param("tenant", tenant.value())
        .param("ids", ids.toArray(UUID[]::new))
        .query(JdbcEmploymentHistoryRepository::employee)
        .list()
        .forEach(e -> found.put(e.id(), e));
    return found;
  }

  /**
   * A page of employees ordered by employee number then ID, optionally filtered by a search.
   *
   * @param tenant verified tenant
   * @param numberPrefix upper-case employee-number prefix, or {@code null}
   * @param words normalized name words (each a word prefix), or empty
   * @param afterNumber keyset employee number, or {@code null}
   * @param afterId keyset ID, or {@code null}
   * @param limit rows to fetch
   * @return employees with their current employment
   */
  public List<EmployeeRecord> employeePage(
      TenantId tenant,
      String numberPrefix,
      List<String> words,
      String afterNumber,
      UUID afterId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, employee_number, given_names, family_name FROM people.employee"
                + " WHERE tenant_id = :tenant");
    MapSqlParameterSource params =
        new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("limit", limit);
    if (numberPrefix != null || !words.isEmpty()) {
      List<String> alternatives = new ArrayList<>();
      if (numberPrefix != null) {
        alternatives.add("employee_number LIKE :number ESCAPE '\\'");
        params.addValue("number", like(numberPrefix) + "%");
      }
      if (!words.isEmpty()) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
          all.add("search_key LIKE :w" + i + " ESCAPE '\\'");
          params.addValue("w" + i, "% " + like(words.get(i)) + "%");
        }
        alternatives.add("(" + String.join(" AND ", all) + ")");
      }
      sql.append(" AND (").append(String.join(" OR ", alternatives)).append(')');
    }
    if (afterNumber != null) {
      sql.append(" AND (employee_number, id) > (:afterNumber, :afterId)");
      params.addValue("afterNumber", afterNumber).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY employee_number, id LIMIT :limit");
    return batch.query(sql.toString(), params, (rs, row) -> employee(rs, row));
  }

  private static String like(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static EmployeeRecord employee(ResultSet rs, int row) throws SQLException {
    return new EmployeeRecord(
        rs.getObject("id", UUID.class),
        rs.getString("employee_number"),
        rs.getString("given_names"),
        rs.getString("family_name"));
  }

  // -------------------------------------------------------------------------------------------
  // Employments
  // -------------------------------------------------------------------------------------------

  /**
   * The employee's employment: the one covering the day, else the latest that started before it,
   * else the first future one.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param day business date
   * @return the employment, if any
   */
  public Optional<EmploymentRecord> employment(TenantId tenant, UUID employeeId, LocalDate day) {
    return jdbc.sql(
            "SELECT id, employee_id, effective_from, effective_to, version FROM people.employment"
                + " WHERE tenant_id = :tenant AND employee_id = :employee"
                + " ORDER BY (effective_from <= :day) DESC,"
                + " CASE WHEN effective_from <= :day THEN effective_from END DESC NULLS LAST,"
                + " effective_from LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("day", day)
        .query(JdbcEmploymentHistoryRepository::employment)
        .optional();
  }

  /**
   * Current employments of many employees (directory status).
   *
   * @param tenant verified tenant
   * @param employeeIds employees
   * @return their employments, by employee
   */
  public Map<UUID, List<EmploymentRecord>> employments(TenantId tenant, Set<UUID> employeeIds) {
    Map<UUID, List<EmploymentRecord>> found = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT id, employee_id, effective_from, effective_to, version FROM people.employment"
                + " WHERE tenant_id = :tenant AND employee_id = ANY(:ids)"
                + " ORDER BY effective_from")
        .param("tenant", tenant.value())
        .param("ids", employeeIds.toArray(UUID[]::new))
        .query(JdbcEmploymentHistoryRepository::employment)
        .list()
        .forEach(e -> found.computeIfAbsent(e.employeeId(), k -> new ArrayList<>()).add(e));
    return found;
  }

  /**
   * One employment, without locking it (previews).
   *
   * @param tenant verified tenant
   * @param employmentId employment
   * @return the employment, if in the tenant
   */
  public Optional<EmploymentRecord> employmentById(TenantId tenant, UUID employmentId) {
    return jdbc.sql(
            "SELECT id, employee_id, effective_from, effective_to, version FROM people.employment"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", employmentId)
        .query(JdbcEmploymentHistoryRepository::employment)
        .optional();
  }

  /**
   * Locks one employment for a change (row lock; the serialization point for its timeline).
   *
   * @param tenant verified tenant
   * @param employmentId employment
   * @return the locked employment
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<EmploymentRecord> lockEmployment(TenantId tenant, UUID employmentId) {
    return jdbc.sql(
            "SELECT id, employee_id, effective_from, effective_to, version FROM people.employment"
                + " WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("id", employmentId)
        .query(JdbcEmploymentHistoryRepository::employment)
        .optional();
  }

  /**
   * Takes the tenant's manager-graph transaction lock (the same key the V14 trigger takes).
   *
   * @param tenant verified tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockManagerGraph(TenantId tenant) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('people.manager-graph:'"
                + " || CAST(:tenant AS uuid), 0))")
        .param("tenant", tenant.value())
        .query()
        .singleRow();
  }

  /**
   * Increments an employment's timeline version.
   *
   * @param tenant verified tenant
   * @param employmentId employment
   * @param version the new version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void setVersion(TenantId tenant, UUID employmentId, long version) {
    jdbc.sql(
            "UPDATE people.employment SET version = :version WHERE tenant_id = :tenant AND id ="
                + " :id")
        .param("version", version)
        .param("tenant", tenant.value())
        .param("id", employmentId)
        .update();
  }

  private static EmploymentRecord employment(ResultSet rs, int row) throws SQLException {
    return new EmploymentRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class),
        rs.getLong("version"));
  }

  // -------------------------------------------------------------------------------------------
  // Assignments
  // -------------------------------------------------------------------------------------------

  /**
   * Every row of an employment, active and superseded.
   *
   * @param tenant verified tenant
   * @param employmentId employment
   * @return rows
   */
  public List<Assignment> assignments(TenantId tenant, UUID employmentId) {
    return jdbc.sql(
            "SELECT "
                + ASSIGNMENT_COLUMNS
                + " FROM people.employment_assignment WHERE tenant_id = :tenant"
                + " AND employment_id = :employment ORDER BY kind, effective_from, id")
        .param("tenant", tenant.value())
        .param("employment", employmentId)
        .query(JdbcEmploymentHistoryRepository::assignment)
        .list();
  }

  /**
   * A page of an employee's rows, ordered by kind, start date and ID.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param kind kind filter, or {@code null}
   * @param includeSuperseded whether superseded rows are listed
   * @param after keyset (kind ordinal, start, id), or {@code null}
   * @param limit rows to fetch
   * @return rows
   */
  public List<Assignment> timelinePage(
      TenantId tenant,
      UUID employeeId,
      AssignmentKind kind,
      boolean includeSuperseded,
      TimelineKey after,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + ASSIGNMENT_COLUMNS
                + ", array_position(ARRAY['PLACEMENT','MANAGER','CONTRACT','COMPENSATION'],"
                + " kind) AS kind_order FROM people.employment_assignment"
                + " WHERE tenant_id = :tenant AND employee_id = :employee");
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("employee", employeeId)
            .addValue("limit", limit);
    if (kind != null) {
      sql.append(" AND kind = :kind");
      params.addValue("kind", kind.name());
    }
    if (!includeSuperseded) {
      sql.append(" AND superseded_by_change_id IS NULL");
    }
    if (after != null) {
      sql.append(
          " AND (array_position(ARRAY['PLACEMENT','MANAGER','CONTRACT','COMPENSATION'], kind),"
              + " effective_from, id) > (:afterKind, :afterFrom, :afterId)");
      params
          .addValue("afterKind", after.kindOrder())
          .addValue("afterFrom", after.from())
          .addValue("afterId", after.id());
    }
    sql.append(" ORDER BY kind_order, effective_from, id LIMIT :limit");
    return batch.query(sql.toString(), params, (rs, row) -> assignment(rs, row));
  }

  /**
   * Keyset of the timeline order.
   *
   * @param kindOrder 1-based kind order
   * @param from start date
   * @param id row ID
   */
  public record TimelineKey(int kindOrder, LocalDate from, UUID id) {}

  /**
   * Active MANAGER rows of a set of employees whose periods intersect a range (cycle walk).
   *
   * @param tenant verified tenant
   * @param employeeIds employees
   * @return their manager edges
   */
  public List<ManagerEdge> managerEdges(TenantId tenant, Collection<UUID> employeeIds) {
    if (employeeIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT employee_id, manager_employee_id, effective_from, effective_to"
                + " FROM people.employment_assignment WHERE tenant_id = :tenant"
                + " AND kind = 'MANAGER' AND superseded_by_change_id IS NULL"
                + " AND employee_id = ANY(:ids)")
        .param("tenant", tenant.value())
        .param("ids", employeeIds.toArray(UUID[]::new))
        .query(
            (rs, row) ->
                new ManagerEdge(
                    rs.getObject("employee_id", UUID.class),
                    rs.getObject("manager_employee_id", UUID.class),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class)))
        .list();
  }

  /**
   * Inserts rows a plan creates.
   *
   * @param tenant verified tenant
   * @param employment the employment
   * @param changeId the change writing them
   * @param rows rows
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertAssignments(
      TenantId tenant, EmploymentRecord employment, UUID changeId, List<NewAssignment> rows) {
    if (rows.isEmpty()) {
      return;
    }
    MapSqlParameterSource[] params = new MapSqlParameterSource[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      NewAssignment row = rows.get(i);
      MapSqlParameterSource p =
          new MapSqlParameterSource()
              .addValue("id", row.id())
              .addValue("tenant", tenant.value())
              .addValue("employee", employment.employeeId())
              .addValue("employment", employment.id())
              .addValue("kind", row.kind().name())
              .addValue("from", row.from())
              .addValue("to", row.to())
              .addValue("createdBy", changeId)
              .addValue("origin", row.origin())
              .addValue("restores", row.restores())
              .addValue("le", null)
              .addValue("site", null)
              .addValue("dept", null)
              .addValue("cc", null)
              .addValue("team", null)
              .addValue("manager", null)
              .addValue("contract", null)
              .addValue("compensation", null);
      switch (row.value()) {
        case AssignmentValue.Placement v ->
            p.addValue("le", v.legalEntityId())
                .addValue("site", v.siteId())
                .addValue("dept", v.departmentId())
                .addValue("cc", v.costCenterId())
                .addValue("team", v.teamId());
        case AssignmentValue.Manager v -> p.addValue("manager", v.employeeId());
        case AssignmentValue.Contract v -> p.addValue("contract", v.classification().name());
        case AssignmentValue.Compensation v -> p.addValue("compensation", v.basis().name());
      }
      params[i] = p;
    }
    batch.batchUpdate(
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, effective_to, legal_entity_id, site_id, department_id,"
            + " cost_center_id, team_id, manager_employee_id, contract_code,"
            + " compensation_basis_code, created_by_change_id, origin_change_id,"
            + " restores_assignment_id) VALUES (:id, :tenant, :employee, :employment, :kind,"
            + " :from, :to, :le, :site, :dept, :cc, :team, :manager, :contract, :compensation,"
            + " :createdBy, :origin, :restores)",
        params);
  }

  /**
   * Sets the supersession pair of active rows (the only update an assignment ever receives).
   *
   * @param tenant verified tenant
   * @param ids rows
   * @param changeId the superseding change
   * @param at when
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int supersede(TenantId tenant, Collection<UUID> ids, UUID changeId, Instant at) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            "UPDATE people.employment_assignment SET superseded_by_change_id = :change,"
                + " superseded_at = :at WHERE tenant_id = :tenant AND id = ANY(:ids)"
                + " AND superseded_by_change_id IS NULL")
        .param("change", changeId)
        .param("at", Timestamp.from(at))
        .param("tenant", tenant.value())
        .param("ids", ids.toArray(UUID[]::new))
        .update();
  }

  private static Assignment assignment(ResultSet rs, int row) throws SQLException {
    AssignmentKind kind = AssignmentKind.valueOf(rs.getString("kind"));
    AssignmentValue value =
        switch (kind) {
          case PLACEMENT ->
              new AssignmentValue.Placement(
                  rs.getObject("legal_entity_id", UUID.class),
                  rs.getObject("site_id", UUID.class),
                  rs.getObject("department_id", UUID.class),
                  rs.getObject("cost_center_id", UUID.class),
                  rs.getObject("team_id", UUID.class));
          case MANAGER ->
              new AssignmentValue.Manager(rs.getObject("manager_employee_id", UUID.class));
          case CONTRACT ->
              new AssignmentValue.Contract(
                  ContractClassification.valueOf(rs.getString("contract_code")));
          case COMPENSATION ->
              new AssignmentValue.Compensation(
                  CompensationBasis.valueOf(rs.getString("compensation_basis_code")));
        };
    Timestamp superseded = rs.getTimestamp("superseded_at");
    return new Assignment(
        rs.getObject("id", UUID.class),
        kind,
        rs.getObject("effective_from", LocalDate.class),
        rs.getObject("effective_to", LocalDate.class),
        value,
        rs.getObject("created_by_change_id", UUID.class),
        rs.getObject("origin_change_id", UUID.class),
        rs.getObject("restores_assignment_id", UUID.class),
        rs.getObject("superseded_by_change_id", UUID.class),
        superseded == null ? null : superseded.toInstant());
  }

  // -------------------------------------------------------------------------------------------
  // Changes
  // -------------------------------------------------------------------------------------------

  /**
   * One change of an employee.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param changeId change
   * @return the change, if it belongs to that employee in the tenant
   */
  public Optional<ChangeRecord> change(TenantId tenant, UUID employeeId, UUID changeId) {
    return jdbc.sql(
            "SELECT "
                + CHANGE_COLUMNS
                + " FROM people.employment_change WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", changeId)
        .query(JdbcEmploymentHistoryRepository::change)
        .optional();
  }

  /**
   * A page of an employee's changes, newest first.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param afterAt keyset recorded time, or {@code null}
   * @param afterId keyset ID, or {@code null}
   * @param limit rows to fetch
   * @return changes
   */
  public List<ChangeRecord> changePage(
      TenantId tenant, UUID employeeId, Instant afterAt, UUID afterId, int limit) {
    String keyset = afterAt == null ? "" : " AND (recorded_at, id) < (:afterAt, :afterId)";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT "
                    + CHANGE_COLUMNS
                    + " FROM people.employment_change WHERE tenant_id = :tenant"
                    + " AND employee_id = :employee"
                    + keyset
                    + " ORDER BY recorded_at DESC, id DESC LIMIT :limit")
            .param("tenant", tenant.value())
            .param("employee", employeeId)
            .param("limit", limit);
    if (afterAt != null) {
      spec = spec.param("afterAt", Timestamp.from(afterAt)).param("afterId", afterId);
    }
    return spec.query(JdbcEmploymentHistoryRepository::change).list();
  }

  /**
   * Inserts a change.
   *
   * @param tenant verified tenant
   * @param change the change
   * @param recordedBy verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertChange(TenantId tenant, ChangeRecord change, String recordedBy) {
    jdbc.sql(
            "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id,"
                + " type, effective_from, kinds, reason_code, timing, cancels_change_id, state,"
                + " recorded_at, recorded_by, version_after) VALUES (:id, :tenant, :employee,"
                + " :employment, :type, :from, :kinds, :reason, :timing, :cancels, 'ACTIVE', :at,"
                + " :by, :version)")
        .param("id", change.id())
        .param("tenant", tenant.value())
        .param("employee", change.employeeId())
        .param("employment", change.employmentId())
        .param("type", change.type().name())
        .param("from", change.effectiveFrom())
        .param(
            "kinds",
            change.kinds().stream().sorted().map(AssignmentKind::name).toArray(String[]::new))
        .param("reason", change.reason() == null ? null : change.reason().name())
        .param("timing", change.timing() == null ? null : change.timing().name())
        .param("cancels", change.cancelsChangeId())
        .param("at", Timestamp.from(change.recordedAt()))
        .param("by", recordedBy)
        .param("version", change.versionAfter())
        .update();
  }

  /**
   * Runs the deferred V14 checks now (placement coverage, manager graph, change shape), so that a
   * violation surfaces inside the business work, where it is mapped by constraint name, rather than
   * at commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    // Only the V14 checks: other deferred checks (the idempotency record's) stay at commit.
    jdbc.sql(
            "SET CONSTRAINTS people.employment_placement_coverage,"
                + " people.employment_placement_coverage_on_employment,"
                + " people.employment_manager_acyclic, people.employment_change_shape IMMEDIATE")
        .update();
  }

  /**
   * Marks a change cancelled (its cancellation must already be recorded).
   *
   * @param tenant verified tenant
   * @param changeId cancelled change
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void markCancelled(TenantId tenant, UUID changeId) {
    jdbc.sql(
            "UPDATE people.employment_change SET state = 'CANCELLED'"
                + " WHERE tenant_id = :tenant AND id = :id AND state = 'ACTIVE'")
        .param("tenant", tenant.value())
        .param("id", changeId)
        .update();
  }

  private static ChangeRecord change(ResultSet rs, int row) throws SQLException {
    Set<AssignmentKind> kinds = EnumSet.noneOf(AssignmentKind.class);
    Array array = rs.getArray("kinds");
    for (Object kind : (Object[]) array.getArray()) {
      kinds.add(AssignmentKind.valueOf((String) kind));
    }
    String reason = rs.getString("reason_code");
    String timing = rs.getString("timing");
    return new ChangeRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        ChangeType.valueOf(rs.getString("type")),
        rs.getObject("effective_from", LocalDate.class),
        kinds,
        reason == null ? null : ChangeReason.valueOf(reason),
        timing == null ? null : ChangeTiming.valueOf(timing),
        rs.getObject("cancels_change_id", UUID.class),
        "CANCELLED".equals(rs.getString("state")),
        rs.getTimestamp("recorded_at").toInstant(),
        rs.getLong("version_after"));
  }
}
