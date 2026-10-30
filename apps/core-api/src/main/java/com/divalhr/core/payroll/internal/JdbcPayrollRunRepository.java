package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollCalculator.Fact;
import com.divalhr.core.payroll.domain.PayrollCalculator.SourceKind;
import com.divalhr.core.payroll.domain.PayrollExplanation;
import com.divalhr.core.payroll.domain.PayrollPayDay;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.payroll.domain.PayrollProration;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of monthly payroll policies and runs (MVP-094A; V44). Every method takes the verified
 * {@link TenantId} and every statement filters on it. Rows are only ever inserted, or a DRAFT run
 * is superseded once; nothing is deleted or updated in place (the V44 triggers enforce it).
 * Restricted financial data: no record here prints an amount, an employee number or a digest.
 */
@Repository
public class JdbcPayrollRunRepository {

  private static final String RUN_COLUMNS =
      "id, legal_entity_id, period_start, currency, version, status, policy_id, policy_sequence,"
          + " pay_day_kind, pay_day, proration_method, employee_count, total_gross_minor,"
          + " input_digest, created_at, superseded_at, superseded_by_run_id";

  private static final String LINE_COLUMNS =
      "id, employee_id, employee_number, eligible_days, denominator, full_month, monthly_minor,"
          + " gross_minor, array_to_string(explanation_codes, ',') AS codes";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcPayrollRunRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /**
   * A stored run version (amounts are Restricted financial data).
   *
   * @param id run
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency currency
   * @param version version in its chain
   * @param status {@code DRAFT} or {@code SUPERSEDED}
   * @param policyId frozen policy
   * @param policySequence frozen policy sequence
   * @param payDay frozen pay day
   * @param method frozen proration method
   * @param employeeCount lines
   * @param totalMinor total contractual gross in minor units
   * @param inputDigest digest of the inputs
   * @param createdAt creation instant
   * @param supersededAt supersession instant, or {@code null}
   * @param supersededByRunId successor, or {@code null}
   */
  public record RunRow(
      UUID id,
      UUID legalEntityId,
      LocalDate periodStart,
      CompensationCurrency currency,
      int version,
      String status,
      UUID policyId,
      int policySequence,
      PayrollPayDay payDay,
      PayrollProration method,
      int employeeCount,
      long totalMinor,
      String inputDigest,
      Instant createdAt,
      Instant supersededAt,
      UUID supersededByRunId) {

    @Override
    public String toString() {
      return "PayrollRunRow[" + id + "]";
    }
  }

  /**
   * A run version to insert.
   *
   * @param id new run
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency currency
   * @param version next version of the chain
   * @param policy the frozen policy
   * @param employeeCount lines
   * @param totalMinor total in minor units
   * @param inputDigest digest of the inputs
   * @param createdAt server instant
   * @param actorSubject verified subject
   */
  public record NewRun(
      UUID id,
      UUID legalEntityId,
      LocalDate periodStart,
      CompensationCurrency currency,
      int version,
      PayrollPolicy policy,
      int employeeCount,
      long totalMinor,
      String inputDigest,
      Instant createdAt,
      String actorSubject) {

    @Override
    public String toString() {
      return "NewPayrollRun[" + id + "]";
    }
  }

  /**
   * A stored line (Restricted financial and HR data).
   *
   * @param id line
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param eligibleDays eligible days
   * @param denominator divisor, or {@code null} for a full month
   * @param fullMonth whether the month is full
   * @param monthlyMinor monthly amount in minor units
   * @param grossMinor contractual gross in minor units
   * @param explanations explanation codes
   */
  public record LineRow(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      int eligibleDays,
      Integer denominator,
      boolean fullMonth,
      long monthlyMinor,
      long grossMinor,
      List<PayrollExplanation> explanations) {

    /** Copies the list. */
    public LineRow {
      explanations = List.copyOf(explanations);
    }

    @Override
    public String toString() {
      return "PayrollLineRow[" + id + "]";
    }
  }

  /**
   * A line to insert with its ranges and sources.
   *
   * @param line the line
   * @param ranges maximal eligible ranges, in order
   * @param sources source records, in order
   */
  public record NewLine(LineRow line, List<RangeRow> ranges, List<SourceRow> sources) {

    /** Copies the lists. */
    public NewLine {
      ranges = List.copyOf(ranges);
      sources = List.copyOf(sources);
    }

    @Override
    public String toString() {
      return "NewPayrollLine[" + line.id() + "]";
    }
  }

  /**
   * One eligible range of a line.
   *
   * @param lineId line
   * @param seq 1-based position
   * @param from first day
   * @param to last day
   */
  public record RangeRow(UUID lineId, int seq, LocalDate from, LocalDate to) {}

  /**
   * One source record of a line.
   *
   * @param lineId line
   * @param seq 1-based position
   * @param kind kind
   * @param sourceId the record
   * @param sourceVersion its version
   * @param from first day
   * @param to last day, or {@code null}
   */
  public record SourceRow(
      UUID lineId,
      int seq,
      SourceKind kind,
      UUID sourceId,
      long sourceVersion,
      LocalDate from,
      LocalDate to) {}

  /**
   * Where a run page continues after.
   *
   * @param periodStart the last row's month
   * @param currency the last row's currency
   * @param version the last row's version
   * @param id the last row
   */
  public record RunPosition(LocalDate periodStart, String currency, int version, UUID id) {}

  // -------------------------------------------------------------------------------------------
  // Transaction controls and locks
  // -------------------------------------------------------------------------------------------

  /**
   * Bounds every following statement and lock wait of the current transaction.
   *
   * @param tenant verified tenant whose transaction it is
   * @param statementTimeout per-statement limit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void limits(TenantId tenant, Duration statementTimeout) {
    Objects.requireNonNull(tenant, "tenant");
    long millis = Math.max(1, statementTimeout.toMillis());
    // Values are server-owned integers; SET does not accept bind parameters.
    jdbc.sql("SET LOCAL statement_timeout = " + millis).update();
    jdbc.sql("SET LOCAL lock_timeout = " + millis).update();
  }

  /**
   * Takes the tenant's payroll-population advisory lock for the rest of the transaction (lock order
   * step 5c, ADR 0033): exclusive for run creation, shared for every compensation command.
   *
   * @param tenant verified tenant
   * @param shared whether the lock is shared
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockPopulation(TenantId tenant, boolean shared) {
    String function = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
    jdbc.sql(
            "SELECT "
                + function
                + "(hashtextextended('payroll.population:' || CAST(CAST(:tenant AS uuid) AS text),"
                + " 0))")
        .param("tenant", tenant.value())
        .query()
        .singleRow();
  }

  /**
   * Takes the legal entity's run lock for the rest of the transaction (lock order step 5e, the same
   * key the V44 triggers take): policy commits and run creations of one entity serialize.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockRun(TenantId tenant, UUID legalEntityId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.run:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:entity AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query()
        .singleRow();
  }

  /**
   * Runs the deferred V44 checks now (inside the caller's transaction), so a violation surfaces
   * inside the business work, where it is mapped by constraint name, rather than at commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payroll_pay_run_exact, payroll.payroll_pay_run_line_exact,"
                + " payroll.payroll_pay_run_successor IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Policies
  // -------------------------------------------------------------------------------------------

  /**
   * The legal entity's policies by sequence.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @return the policies
   */
  public List<PayrollPolicy> policies(TenantId tenant, UUID legalEntityId) {
    return jdbc.sql(
            "SELECT id, sequence, effective_from, pay_day_kind, pay_day, proration_method"
                + " FROM payroll.run_policy WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity ORDER BY sequence")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query(JdbcPayrollRunRepository::policy)
        .list();
  }

  /**
   * Appends a policy.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param policy the policy (its sequence is the next one)
   * @param at server instant
   * @param actorSubject verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertPolicy(
      TenantId tenant, UUID legalEntityId, PayrollPolicy policy, Instant at, String actorSubject) {
    jdbc.sql(
            "INSERT INTO payroll.run_policy (tenant_id, legal_entity_id, id, sequence,"
                + " effective_from, pay_day_kind, pay_day, proration_method, created_at,"
                + " created_by) VALUES (:tenant, :entity, :id, :sequence, :from, :kind, :day,"
                + " :method, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", policy.id())
        .param("sequence", policy.sequence())
        .param("from", policy.effectiveFrom())
        .param("kind", policy.payDay().kind().name())
        .param("day", policy.payDay().day() == null ? null : policy.payDay().day().shortValue())
        .param("method", policy.method().name())
        .param("at", Timestamp.from(at))
        .param("actor", actorSubject)
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Compensation facts (payroll's own tables)
  // -------------------------------------------------------------------------------------------

  /**
   * The active monthly compensation facts of employees overlapping a period.
   *
   * @param tenant verified tenant
   * @param employeeIds employees
   * @param from first day of the period
   * @param to last day of the period
   * @return the facts by employee, by start date
   */
  public Map<UUID, List<Fact>> facts(
      TenantId tenant, Set<UUID> employeeIds, LocalDate from, LocalDate to) {
    Map<UUID, List<Fact>> found = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT employee_id, id, origin_change_id, created_seq, currency, amount_minor,"
                + " effective_from, effective_to FROM payroll.compensation"
                + " WHERE tenant_id = :tenant AND employee_id = ANY(:ids)"
                + " AND superseded_seq IS NULL AND effective_from <= :to"
                + " AND (effective_to IS NULL OR effective_to >= :from)"
                + " ORDER BY employee_id, effective_from")
        .param("tenant", tenant.value())
        .param("ids", employeeIds.toArray(UUID[]::new))
        .param("from", from)
        .param("to", to)
        .query(
            (rs, row) ->
                Map.entry(
                    rs.getObject("employee_id", UUID.class),
                    new Fact(
                        rs.getObject("id", UUID.class),
                        rs.getObject("origin_change_id", UUID.class),
                        rs.getLong("created_seq"),
                        CompensationCurrency.valueOf(rs.getString("currency")),
                        rs.getLong("amount_minor"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class))))
        .list()
        .forEach(
            entry ->
                found
                    .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(entry.getValue()));
    return found;
  }

  // -------------------------------------------------------------------------------------------
  // Runs
  // -------------------------------------------------------------------------------------------

  /**
   * The current DRAFT of a chain.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency currency
   * @return the draft, if any
   */
  public Optional<RunRow> draft(
      TenantId tenant, UUID legalEntityId, LocalDate periodStart, CompensationCurrency currency) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
                + " AND period_start = :period AND currency = :currency AND status = 'DRAFT'")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("period", periodStart)
        .param("currency", currency.name())
        .query(JdbcPayrollRunRepository::run)
        .optional();
  }

  /**
   * One run of a legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId run
   * @return the run, if it belongs to that legal entity in the tenant
   */
  public Optional<RunRow> run(TenantId tenant, UUID legalEntityId, UUID runId) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
                + " AND id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", runId)
        .query(JdbcPayrollRunRepository::run)
        .optional();
  }

  /**
   * A page of a legal entity's run versions: month descending, currency, version descending.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param after the last row of the previous page, or {@code null} for the first page
   * @param limit rows to read
   * @return the runs
   */
  public List<RunRow> runPage(TenantId tenant, UUID legalEntityId, RunPosition after, int limit) {
    if (after == null) {
      return jdbc.sql(
              "SELECT "
                  + RUN_COLUMNS
                  + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
                  + " ORDER BY period_start DESC, currency, version DESC, id LIMIT :limit")
          .param("tenant", tenant.value())
          .param("entity", legalEntityId)
          .param("limit", limit)
          .query(JdbcPayrollRunRepository::run)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM payroll.pay_run WHERE tenant_id = :tenant AND legal_entity_id = :entity"
                + " AND (period_start < :period"
                + " OR (period_start = :period AND currency > :currency)"
                + " OR (period_start = :period AND currency = :currency AND version < :version)"
                + " OR (period_start = :period AND currency = :currency AND version = :version"
                + " AND id > :id))"
                + " ORDER BY period_start DESC, currency, version DESC, id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("period", after.periodStart())
        .param("currency", after.currency())
        .param("version", after.version())
        .param("id", after.id())
        .param("limit", limit)
        .query(JdbcPayrollRunRepository::run)
        .list();
  }

  /**
   * The highest version of a chain, 0 when none.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency currency
   * @return the version
   */
  public int chainVersion(
      TenantId tenant, UUID legalEntityId, LocalDate periodStart, CompensationCurrency currency) {
    return jdbc.sql(
            "SELECT COALESCE(max(version), 0) FROM payroll.pay_run WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND period_start = :period"
                + " AND currency = :currency")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("period", periodStart)
        .param("currency", currency.name())
        .query(Integer.class)
        .single();
  }

  /**
   * Marks the current DRAFT superseded by its successor, which is inserted next (the successor's
   * key is checked at commit).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId the DRAFT to supersede
   * @param successorId the run that will be inserted
   * @param at server instant
   * @return the rows changed (1)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int supersede(
      TenantId tenant, UUID legalEntityId, UUID runId, UUID successorId, Instant at) {
    return jdbc.sql(
            "UPDATE payroll.pay_run SET status = 'SUPERSEDED', superseded_at = :at,"
                + " superseded_by_run_id = :successor WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND id = :id AND status = 'DRAFT'")
        .param("at", Timestamp.from(at))
        .param("successor", successorId)
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", runId)
        .update();
  }

  /**
   * Inserts a run version.
   *
   * @param tenant verified tenant
   * @param run the run
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertRun(TenantId tenant, NewRun run) {
    PayrollPolicy policy = run.policy();
    jdbc.sql(
            "INSERT INTO payroll.pay_run (tenant_id, legal_entity_id, id, period_start, currency,"
                + " version, status, policy_id, policy_sequence, pay_day_kind, pay_day,"
                + " proration_method, employee_count, total_gross_minor, input_digest, created_at,"
                + " created_by) VALUES (:tenant, :entity, :id, :period, :currency, :version,"
                + " 'DRAFT', :policy, :sequence, :kind, :day, :method, :count, :total, :digest,"
                + " :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", run.legalEntityId())
        .param("id", run.id())
        .param("period", run.periodStart())
        .param("currency", run.currency().name())
        .param("version", run.version())
        .param("policy", policy.id())
        .param("sequence", policy.sequence())
        .param("kind", policy.payDay().kind().name())
        .param("day", policy.payDay().day() == null ? null : policy.payDay().day().shortValue())
        .param("method", policy.method().name())
        .param("count", run.employeeCount())
        .param("total", run.totalMinor())
        .param("digest", run.inputDigest())
        .param("at", Timestamp.from(run.createdAt()))
        .param("actor", run.actorSubject())
        .update();
  }

  /**
   * Inserts the lines of a run with their ranges and sources.
   *
   * @param tenant verified tenant
   * @param runId the run being created
   * @param lines the lines
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertLines(TenantId tenant, UUID runId, List<NewLine> lines) {
    List<SqlParameterSource> lineParams = new ArrayList<>();
    List<SqlParameterSource> rangeParams = new ArrayList<>();
    List<SqlParameterSource> sourceParams = new ArrayList<>();
    for (NewLine newLine : lines) {
      LineRow line = newLine.line();
      lineParams.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("run", runId)
              .addValue("id", line.id())
              .addValue("employee", line.employeeId())
              .addValue("number", line.employeeNumber())
              .addValue("days", line.eligibleDays())
              .addValue("denominator", line.denominator())
              .addValue("full", line.fullMonth())
              .addValue("monthly", line.monthlyMinor())
              .addValue("gross", line.grossMinor())
              .addValue(
                  "codes",
                  line.explanations().stream()
                      .map(PayrollExplanation::name)
                      .collect(Collectors.joining(","))));
      for (RangeRow range : newLine.ranges()) {
        rangeParams.add(
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("run", runId)
                .addValue("line", range.lineId())
                .addValue("seq", range.seq())
                .addValue("from", range.from())
                .addValue("to", range.to()));
      }
      for (SourceRow source : newLine.sources()) {
        sourceParams.add(
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("run", runId)
                .addValue("line", source.lineId())
                .addValue("seq", source.seq())
                .addValue("kind", source.kind().name())
                .addValue("source", source.sourceId())
                .addValue("version", source.sourceVersion())
                .addValue("from", source.from())
                .addValue("to", source.to()));
      }
    }
    batch.batchUpdate(
        "INSERT INTO payroll.pay_run_line (tenant_id, run_id, id, employee_id, employee_number,"
            + " eligible_days, denominator, full_month, monthly_minor, gross_minor,"
            + " explanation_codes) VALUES (:tenant, :run, :id, :employee, :number, :days,"
            + " :denominator, :full, :monthly, :gross, string_to_array(:codes, ','))",
        lineParams.toArray(SqlParameterSource[]::new));
    batch.batchUpdate(
        "INSERT INTO payroll.pay_run_line_range (tenant_id, run_id, line_id, seq, range_from,"
            + " range_to) VALUES (:tenant, :run, :line, :seq, :from, :to)",
        rangeParams.toArray(SqlParameterSource[]::new));
    batch.batchUpdate(
        "INSERT INTO payroll.pay_run_line_source (tenant_id, run_id, line_id, seq, kind,"
            + " source_id, source_version, valid_from, valid_to) VALUES (:tenant, :run, :line,"
            + " :seq, :kind, :source, :version, :from, :to)",
        sourceParams.toArray(SqlParameterSource[]::new));
  }

  // -------------------------------------------------------------------------------------------
  // Lines
  // -------------------------------------------------------------------------------------------

  /**
   * One line of a run (the position a cursor names).
   *
   * @param tenant verified tenant
   * @param runId run
   * @param lineId line
   * @return the line, if it belongs to the run in the tenant
   */
  public Optional<LineRow> line(TenantId tenant, UUID runId, UUID lineId) {
    return jdbc.sql(
            "SELECT "
                + LINE_COLUMNS
                + " FROM payroll.pay_run_line WHERE tenant_id = :tenant AND run_id = :run"
                + " AND id = :id")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("id", lineId)
        .query(JdbcPayrollRunRepository::line)
        .optional();
  }

  /**
   * A page of a run's lines by employee number in byte order, then ID.
   *
   * @param tenant verified tenant
   * @param runId run
   * @param afterNumber employee number of the last line of the previous page, or {@code null}
   * @param afterId ID of that line, or {@code null}
   * @param limit rows to read
   * @return the lines
   */
  public List<LineRow> linePage(
      TenantId tenant, UUID runId, String afterNumber, UUID afterId, int limit) {
    if (afterNumber == null || afterId == null) {
      return jdbc.sql(
              "SELECT "
                  + LINE_COLUMNS
                  + " FROM payroll.pay_run_line WHERE tenant_id = :tenant AND run_id = :run"
                  + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit")
          .param("tenant", tenant.value())
          .param("run", runId)
          .param("limit", limit)
          .query(JdbcPayrollRunRepository::line)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + LINE_COLUMNS
                + " FROM payroll.pay_run_line WHERE tenant_id = :tenant AND run_id = :run"
                + " AND ((employee_number COLLATE \"C\"), id) > (CAST(:number AS text) COLLATE"
                + " \"C\", CAST(:after AS uuid))"
                + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("number", afterNumber)
        .param("after", afterId)
        .param("limit", limit)
        .query(JdbcPayrollRunRepository::line)
        .list();
  }

  /**
   * The eligible ranges of some lines of a run.
   *
   * @param tenant verified tenant
   * @param runId run
   * @param lineIds lines
   * @return the ranges by line, in order
   */
  public Map<UUID, List<RangeRow>> ranges(TenantId tenant, UUID runId, Collection<UUID> lineIds) {
    Map<UUID, List<RangeRow>> found = new HashMap<>();
    if (lineIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT line_id, seq, range_from, range_to FROM payroll.pay_run_line_range"
                + " WHERE tenant_id = :tenant AND run_id = :run AND line_id = ANY(:ids)"
                + " ORDER BY line_id, seq")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("ids", lineIds.toArray(UUID[]::new))
        .query(
            (rs, row) ->
                new RangeRow(
                    rs.getObject("line_id", UUID.class),
                    rs.getInt("seq"),
                    rs.getObject("range_from", LocalDate.class),
                    rs.getObject("range_to", LocalDate.class)))
        .list()
        .forEach(range -> found.computeIfAbsent(range.lineId(), k -> new ArrayList<>()).add(range));
    return found;
  }

  /**
   * The source records of some lines of a run.
   *
   * @param tenant verified tenant
   * @param runId run
   * @param lineIds lines
   * @return the sources by line, in order
   */
  public Map<UUID, List<SourceRow>> sources(TenantId tenant, UUID runId, Collection<UUID> lineIds) {
    Map<UUID, List<SourceRow>> found = new HashMap<>();
    if (lineIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT line_id, seq, kind, source_id, source_version, valid_from, valid_to"
                + " FROM payroll.pay_run_line_source WHERE tenant_id = :tenant AND run_id = :run"
                + " AND line_id = ANY(:ids) ORDER BY line_id, seq")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("ids", lineIds.toArray(UUID[]::new))
        .query(
            (rs, row) ->
                new SourceRow(
                    rs.getObject("line_id", UUID.class),
                    rs.getInt("seq"),
                    SourceKind.valueOf(rs.getString("kind")),
                    rs.getObject("source_id", UUID.class),
                    rs.getLong("source_version"),
                    rs.getObject("valid_from", LocalDate.class),
                    rs.getObject("valid_to", LocalDate.class)))
        .list()
        .forEach(
            source -> found.computeIfAbsent(source.lineId(), k -> new ArrayList<>()).add(source));
    return found;
  }

  // -------------------------------------------------------------------------------------------
  // Row mapping
  // -------------------------------------------------------------------------------------------

  private static PayrollPolicy policy(ResultSet rs, int row) throws SQLException {
    int day = rs.getInt("pay_day");
    boolean noDay = rs.wasNull();
    PayrollPayDay payDay =
        PayrollPayDay.Kind.valueOf(rs.getString("pay_day_kind")) == PayrollPayDay.Kind.DAY
            ? PayrollPayDay.ofDay(noDay ? 1 : day)
            : PayrollPayDay.lastDay();
    return new PayrollPolicy(
        rs.getObject("id", UUID.class),
        rs.getInt("sequence"),
        rs.getObject("effective_from", LocalDate.class),
        payDay,
        PayrollProration.valueOf(rs.getString("proration_method")));
  }

  private static RunRow run(ResultSet rs, int row) throws SQLException {
    int day = rs.getInt("pay_day");
    boolean noDay = rs.wasNull();
    PayrollPayDay payDay =
        PayrollPayDay.Kind.valueOf(rs.getString("pay_day_kind")) == PayrollPayDay.Kind.DAY
            ? PayrollPayDay.ofDay(noDay ? 1 : day)
            : PayrollPayDay.lastDay();
    Timestamp superseded = rs.getTimestamp("superseded_at");
    return new RunRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("period_start", LocalDate.class),
        CompensationCurrency.valueOf(rs.getString("currency")),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getObject("policy_id", UUID.class),
        rs.getInt("policy_sequence"),
        payDay,
        PayrollProration.valueOf(rs.getString("proration_method")),
        rs.getInt("employee_count"),
        rs.getLong("total_gross_minor"),
        rs.getString("input_digest"),
        rs.getTimestamp("created_at").toInstant(),
        superseded == null ? null : superseded.toInstant(),
        rs.getObject("superseded_by_run_id", UUID.class));
  }

  private static LineRow line(ResultSet rs, int row) throws SQLException {
    int denominator = rs.getInt("denominator");
    boolean noDenominator = rs.wasNull();
    String codes = rs.getString("codes");
    return new LineRow(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getInt("eligible_days"),
        noDenominator ? null : denominator,
        rs.getBoolean("full_month"),
        rs.getLong("monthly_minor"),
        rs.getLong("gross_minor"),
        codes == null || codes.isEmpty()
            ? List.of()
            : Arrays.stream(codes.split(",")).map(PayrollExplanation::valueOf).toList());
  }
}
