package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.domain.StatutoryClassification;
import com.divalhr.core.payroll.domain.StatutoryEvidence;
import com.divalhr.core.payroll.domain.StatutoryProfile;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 * Storage of the tenant-owned statutory preview data (MVP-094B; V45, ADR 0034): rule-pack
 * activations, employee statutory profiles and statutory calculations with their lines and
 * components. Every method takes the verified {@link TenantId} and every statement filters on it.
 * Rows are only ever inserted, or a DRAFT calculation is superseded once; nothing is deleted or
 * updated in place (the V45 triggers enforce it). The platform-owned rule packs are read through
 * {@link JdbcStatutoryRulePackRepository}. Restricted financial and HR data: no record here prints
 * an amount, an employee number, a flag, a classification or a digest.
 */
@Repository
public class JdbcStatutoryRepository {

  private static final String CALCULATION_COLUMNS =
      "c.id, c.legal_entity_id, c.run_id, c.run_version, c.period_start, c.currency, c.pack_id,"
          + " p.code AS pack_code, p.version AS pack_version, c.activation_id, c.version,"
          + " c.status, c.supported_count, c.excluded_count, c.gross_total_minor,"
          + " c.employee_deductions_total_minor, c.employer_contributions_total_minor,"
          + " c.preview_net_total_minor, c.employer_cost_total_minor, c.source_run_digest,"
          + " c.profile_digest, c.rule_pack_digest, c.calculation_digest, c.created_at,"
          + " c.superseded_at, c.superseded_by_id";

  private static final String CALCULATION_FROM =
      " FROM payroll.statutory_calculation c JOIN payroll.statutory_rule_pack p"
          + " ON p.id = c.pack_id";

  private static final String LINE_COLUMNS =
      "id, employee_id, employee_number, gross_minor, employee_deductions_minor,"
          + " employer_contributions_minor, preview_net_minor, employer_cost_minor, excluded";

  private static final String ACTIVATION_COLUMNS =
      "a.id, a.legal_entity_id, a.sequence, a.pack_id, p.code AS pack_code,"
          + " p.version AS pack_version, a.from_month, a.activated_at";

  private static final String ACTIVATION_FROM =
      " FROM payroll.statutory_activation a JOIN payroll.statutory_rule_pack p"
          + " ON p.id = a.pack_id";

  private static final String PROFILE_COLUMNS =
      "id, sequence, effective_from, classification, cnss_subject, irpp_subject, evidence_code,"
          + " created_at";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcStatutoryRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /**
   * A stored activation with the identity of its pack.
   *
   * @param id activation
   * @param legalEntityId legal entity
   * @param sequence sequence within the legal entity, 1 to 60
   * @param packId activated pack
   * @param packCode its code
   * @param packVersion its version
   * @param fromMonth first day of the first month it applies to
   * @param activatedAt activation instant
   */
  public record ActivationRow(
      UUID id,
      UUID legalEntityId,
      int sequence,
      UUID packId,
      String packCode,
      int packVersion,
      LocalDate fromMonth,
      Instant activatedAt) {}

  /**
   * An activation to insert.
   *
   * @param id new activation
   * @param legalEntityId legal entity
   * @param sequence next sequence of the legal entity
   * @param packId activated pack
   * @param fromMonth first day of the first month
   * @param createdAt server instant
   * @param actorSubject verified subject
   */
  public record NewActivation(
      UUID id,
      UUID legalEntityId,
      int sequence,
      UUID packId,
      LocalDate fromMonth,
      Instant createdAt,
      String actorSubject) {}

  /**
   * A stored profile row (restricted HR data).
   *
   * @param profile the profile
   * @param createdAt creation instant
   */
  public record ProfileRow(StatutoryProfile profile, Instant createdAt) {

    @Override
    public String toString() {
      return "StatutoryProfileRow[" + profile.id() + "]";
    }
  }

  /**
   * One frozen line of a source run, with the first and last day of its eligible ranges.
   *
   * @param id source line
   * @param employeeId employee
   * @param employeeNumber employee number (Confidential)
   * @param grossMinor frozen contractual gross
   * @param firstDay first eligible day
   * @param lastDay last eligible day
   */
  public record SourceLineRow(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      LocalDate firstDay,
      LocalDate lastDay) {

    @Override
    public String toString() {
      return "StatutorySourceLine[" + id + "]";
    }
  }

  /**
   * A stored calculation version with the identity of its pack (restricted financial data).
   *
   * @param id calculation
   * @param legalEntityId legal entity
   * @param runId source run
   * @param runVersion source run version
   * @param periodStart first day of the month
   * @param currency {@code CDF}
   * @param packId pack
   * @param packCode its code
   * @param packVersion its version
   * @param activationId activation in effect when it was made
   * @param version version in its chain
   * @param status {@code DRAFT} or {@code SUPERSEDED}
   * @param supportedCount lines with at least one applicable component
   * @param excludedCount lines subject to nothing
   * @param grossTotal total contractual gross
   * @param deductionsTotal total employee deductions
   * @param contributionsTotal total employer contributions
   * @param netTotal total preview net
   * @param costTotal total employer cost
   * @param sourceRunDigest digest of the source run
   * @param profileDigest digest of the profiles
   * @param rulePackDigest digest of the activation and pack
   * @param calculationDigest digest of the previewed command
   * @param createdAt creation instant
   * @param supersededAt supersession instant, or {@code null}
   * @param supersededById successor, or {@code null}
   */
  public record CalculationRow(
      UUID id,
      UUID legalEntityId,
      UUID runId,
      int runVersion,
      LocalDate periodStart,
      String currency,
      UUID packId,
      String packCode,
      int packVersion,
      UUID activationId,
      int version,
      String status,
      int supportedCount,
      int excludedCount,
      long grossTotal,
      long deductionsTotal,
      long contributionsTotal,
      long netTotal,
      long costTotal,
      String sourceRunDigest,
      String profileDigest,
      String rulePackDigest,
      String calculationDigest,
      Instant createdAt,
      Instant supersededAt,
      UUID supersededById) {

    @Override
    public String toString() {
      return "StatutoryCalculationRow[" + id + "]";
    }
  }

  /**
   * A calculation to insert.
   *
   * @param id new calculation
   * @param legalEntityId legal entity
   * @param runId source run
   * @param runVersion source run version
   * @param periodStart first day of the month
   * @param packId pack
   * @param activationId activation in effect
   * @param version next version of the chain
   * @param supportedCount supported lines
   * @param excludedCount excluded lines
   * @param grossTotal total gross
   * @param deductionsTotal total employee deductions
   * @param contributionsTotal total employer contributions
   * @param netTotal total preview net
   * @param costTotal total employer cost
   * @param sourceRunDigest digest of the source run
   * @param profileDigest digest of the profiles
   * @param rulePackDigest digest of the activation and pack
   * @param calculationDigest digest of the previewed command
   * @param createdAt server instant
   * @param actorSubject verified subject
   */
  public record NewCalculation(
      UUID id,
      UUID legalEntityId,
      UUID runId,
      int runVersion,
      LocalDate periodStart,
      UUID packId,
      UUID activationId,
      int version,
      int supportedCount,
      int excludedCount,
      long grossTotal,
      long deductionsTotal,
      long contributionsTotal,
      long netTotal,
      long costTotal,
      String sourceRunDigest,
      String profileDigest,
      String rulePackDigest,
      String calculationDigest,
      Instant createdAt,
      String actorSubject) {

    @Override
    public String toString() {
      return "NewStatutoryCalculation[" + id + "]";
    }
  }

  /**
   * One component of a stored or new line.
   *
   * @param seq 1-based position of the rule in the pack
   * @param componentId rule id
   * @param party {@code EMPLOYEE} or {@code EMPLOYER}
   * @param category {@code SOCIAL_CONTRIBUTION} or {@code INCOME_TAX}
   * @param baseMinor the base
   * @param amountMinor the amount
   * @param explanationCodes stable explanation codes
   * @param traceJson the trace as JSON text, or {@code null}
   */
  public record ComponentRow(
      int seq,
      String componentId,
      String party,
      String category,
      long baseMinor,
      long amountMinor,
      List<String> explanationCodes,
      String traceJson) {

    /** Copies the list. */
    public ComponentRow {
      explanationCodes = List.copyOf(explanationCodes);
    }

    @Override
    public String toString() {
      return "StatutoryComponentRow[" + componentId + "]";
    }
  }

  /**
   * A stored line of a calculation (restricted financial and HR data).
   *
   * @param id line
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param grossMinor gross
   * @param deductionsMinor employee deductions
   * @param contributionsMinor employer contributions
   * @param netMinor preview net
   * @param costMinor employer cost
   * @param excluded whether the line is subject to nothing
   */
  public record LineRow(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      long deductionsMinor,
      long contributionsMinor,
      long netMinor,
      long costMinor,
      boolean excluded) {

    @Override
    public String toString() {
      return "StatutoryLineRow[" + id + "]";
    }
  }

  /**
   * A line to insert with its components.
   *
   * @param line the line (its identity is created by the caller)
   * @param sourceLineId the frozen source line
   * @param components the components in rule order
   */
  public record NewLine(LineRow line, UUID sourceLineId, List<ComponentRow> components) {

    /** Copies the list. */
    public NewLine {
      components = List.copyOf(components);
    }

    @Override
    public String toString() {
      return "NewStatutoryLine[" + line.id() + "]";
    }
  }

  /**
   * Where a calculation page continues after.
   *
   * @param version the last row's version
   * @param id the last row
   */
  public record CalculationPosition(int version, UUID id) {}

  // -------------------------------------------------------------------------------------------
  // Locks and deferred checks (ADR 0034 section 7)
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the tenant's statutory advisory lock for the rest of the transaction: shared for profile
   * and activation commits, exclusive for a calculation commit.
   *
   * @param tenant verified tenant
   * @param shared whether the lock is shared
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockStatutory(TenantId tenant, boolean shared) {
    if (shared) {
      jdbc.sql(
              "SELECT pg_advisory_xact_lock_shared(hashtextextended('payroll.statutory:'"
                  + " || CAST(CAST(:tenant AS uuid) AS text), 0))")
          .param("tenant", tenant.value())
          .query()
          .singleRow();
    } else {
      jdbc.sql(
              "SELECT pg_advisory_xact_lock(hashtextextended('payroll.statutory:'"
                  + " || CAST(CAST(:tenant AS uuid) AS text), 0))")
          .param("tenant", tenant.value())
          .query()
          .singleRow();
    }
  }

  /**
   * Takes the legal entity's activation lock (the key the V45 trigger takes).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockActivation(TenantId tenant, UUID legalEntityId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.statutory-activation:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:entity AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query()
        .singleRow();
  }

  /**
   * Takes the legal entity's calculation lock (the key the V45 trigger takes).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockCalculation(TenantId tenant, UUID legalEntityId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.statutory-calculation:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:entity AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query()
        .singleRow();
  }

  /**
   * Takes the employee's profile chain lock (the key the V45 trigger takes).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockProfile(TenantId tenant, UUID employeeId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.statutory-profile:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:employee AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query()
        .singleRow();
  }

  /**
   * Locks the source run's row {@code FOR SHARE} until the transaction ends, so that a payroll run
   * creation cannot supersede it between the read and the insert of the calculation.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId source run
   * @return whether the run exists in that legal entity of the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockSourceRun(TenantId tenant, UUID legalEntityId, UUID runId) {
    return jdbc.sql(
            "SELECT id FROM payroll.pay_run WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND id = :id FOR SHARE")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", runId)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /**
   * Runs the deferred V45 checks now (inside the caller's transaction), so a violation surfaces
   * inside the business work, where it is mapped by constraint name, rather than at commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payroll_statutory_calculation_exact,"
                + " payroll.payroll_statutory_calculation_line_exact,"
                + " payroll.payroll_statutory_calculation_successor IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Activations
  // -------------------------------------------------------------------------------------------

  /**
   * The legal entity's activations, newest first.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @return at most 60 activations
   */
  public List<ActivationRow> activations(TenantId tenant, UUID legalEntityId) {
    return jdbc.sql(
            "SELECT "
                + ACTIVATION_COLUMNS
                + ACTIVATION_FROM
                + " WHERE a.tenant_id = :tenant AND a.legal_entity_id = :entity"
                + " ORDER BY a.sequence DESC LIMIT 60")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query(JdbcStatutoryRepository::activation)
        .list();
  }

  /**
   * The number of activations of the legal entity (its version).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @return the highest sequence, 0 when none
   */
  public int activationVersion(TenantId tenant, UUID legalEntityId) {
    return jdbc.sql(
            "SELECT COALESCE(max(sequence), 0) FROM payroll.statutory_activation"
                + " WHERE tenant_id = :tenant AND legal_entity_id = :entity")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query(Integer.class)
        .single();
  }

  /**
   * The activation in effect for a month: the one with the greatest first month not after it.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param month first day of the month
   * @return the activation, if any
   */
  public Optional<ActivationRow> activationInEffect(
      TenantId tenant, UUID legalEntityId, LocalDate month) {
    return jdbc.sql(
            "SELECT "
                + ACTIVATION_COLUMNS
                + ACTIVATION_FROM
                + " WHERE a.tenant_id = :tenant AND a.legal_entity_id = :entity"
                + " AND a.from_month <= :month"
                + " ORDER BY a.from_month DESC, a.sequence DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("month", month)
        .query(JdbcStatutoryRepository::activation)
        .optional();
  }

  /**
   * Inserts an activation.
   *
   * @param tenant verified tenant
   * @param activation the activation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertActivation(TenantId tenant, NewActivation activation) {
    jdbc.sql(
            "INSERT INTO payroll.statutory_activation (tenant_id, legal_entity_id, id, sequence,"
                + " pack_id, from_month, acknowledged, activated_at, activated_by)"
                + " VALUES (:tenant, :entity, :id, :sequence, :pack, :month, true, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", activation.legalEntityId())
        .param("id", activation.id())
        .param("sequence", activation.sequence())
        .param("pack", activation.packId())
        .param("month", activation.fromMonth())
        .param("at", Timestamp.from(activation.createdAt()))
        .param("actor", activation.actorSubject())
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Profiles
  // -------------------------------------------------------------------------------------------

  /**
   * The employee's profile rows, newest effective date first, then newest sequence.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return at most 100 rows
   */
  public List<ProfileRow> profiles(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT "
                + PROFILE_COLUMNS
                + " FROM payroll.statutory_profile WHERE tenant_id = :tenant"
                + " AND employee_id = :employee"
                + " ORDER BY effective_from DESC, sequence DESC LIMIT 100")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(JdbcStatutoryRepository::profile)
        .list();
  }

  /**
   * The profile rows of many employees.
   *
   * @param tenant verified tenant
   * @param employeeIds employees
   * @return the rows by employee, oldest effective date first, then by sequence
   */
  public Map<UUID, List<StatutoryProfile>> profilesOf(
      TenantId tenant, Collection<UUID> employeeIds) {
    Map<UUID, List<StatutoryProfile>> found = new HashMap<>();
    if (employeeIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT employee_id, "
                + PROFILE_COLUMNS
                + " FROM payroll.statutory_profile WHERE tenant_id = :tenant"
                + " AND employee_id = ANY(:ids)"
                + " ORDER BY employee_id, effective_from, sequence")
        .param("tenant", tenant.value())
        .param("ids", employeeIds.toArray(UUID[]::new))
        .query(
            (rs, row) ->
                Map.entry(rs.getObject("employee_id", UUID.class), profile(rs, row).profile()))
        .list()
        .forEach(
            entry ->
                found
                    .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(entry.getValue()));
    return found;
  }

  /**
   * Appends a profile row.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param profile the row (its sequence is the next one)
   * @param at server instant
   * @param actorSubject verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertProfile(
      TenantId tenant, UUID employeeId, StatutoryProfile profile, Instant at, String actorSubject) {
    jdbc.sql(
            "INSERT INTO payroll.statutory_profile (tenant_id, employee_id, id, sequence,"
                + " effective_from, country, classification, cnss_subject, irpp_subject,"
                + " evidence_code, created_at, created_by) VALUES (:tenant, :employee, :id,"
                + " :sequence, :from, 'CD', :classification, :cnss, :irpp, :evidence, :at, :actor)")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", profile.id())
        .param("sequence", profile.sequence())
        .param("from", profile.effectiveFrom())
        .param("classification", profile.classification().name())
        .param("cnss", profile.cnssSubject())
        .param("irpp", profile.irppSubject())
        .param("evidence", profile.evidence().name())
        .param("at", Timestamp.from(at))
        .param("actor", actorSubject)
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // The frozen source run (payroll's own tables)
  // -------------------------------------------------------------------------------------------

  /**
   * The frozen lines of a source run with the first and last day of their eligible ranges, ordered
   * by employee number in byte order, then identifier.
   *
   * @param tenant verified tenant
   * @param runId source run
   * @param limit the most lines to read (callers pass one more than they accept)
   * @return the lines
   */
  public List<SourceLineRow> sourceLines(TenantId tenant, UUID runId, int limit) {
    return jdbc.sql(
            "SELECT l.id, l.employee_id, l.employee_number, l.gross_minor,"
                + " (SELECT min(r.range_from) FROM payroll.pay_run_line_range r"
                + " WHERE r.tenant_id = l.tenant_id AND r.run_id = l.run_id AND r.line_id = l.id)"
                + " AS first_day,"
                + " (SELECT max(r.range_to) FROM payroll.pay_run_line_range r"
                + " WHERE r.tenant_id = l.tenant_id AND r.run_id = l.run_id AND r.line_id = l.id)"
                + " AS last_day"
                + " FROM payroll.pay_run_line l WHERE l.tenant_id = :tenant AND l.run_id = :run"
                + " ORDER BY (l.employee_number COLLATE \"C\"), l.id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new SourceLineRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("employee_id", UUID.class),
                    rs.getString("employee_number"),
                    rs.getLong("gross_minor"),
                    rs.getObject("first_day", LocalDate.class),
                    rs.getObject("last_day", LocalDate.class)))
        .list();
  }

  // -------------------------------------------------------------------------------------------
  // Calculations
  // -------------------------------------------------------------------------------------------

  /**
   * The highest version of the chain of a source run and pack, 0 when none.
   *
   * @param tenant verified tenant
   * @param runId source run
   * @param packId pack
   * @return the version
   */
  public int chainVersion(TenantId tenant, UUID runId, UUID packId) {
    return jdbc.sql(
            "SELECT COALESCE(max(version), 0) FROM payroll.statutory_calculation"
                + " WHERE tenant_id = :tenant AND run_id = :run AND pack_id = :pack")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("pack", packId)
        .query(Integer.class)
        .single();
  }

  /**
   * The current DRAFT of the chain of a source run and pack.
   *
   * @param tenant verified tenant
   * @param runId source run
   * @param packId pack
   * @return the draft, if any
   */
  public Optional<CalculationRow> draft(TenantId tenant, UUID runId, UUID packId) {
    return jdbc.sql(
            "SELECT "
                + CALCULATION_COLUMNS
                + CALCULATION_FROM
                + " WHERE c.tenant_id = :tenant AND c.run_id = :run AND c.pack_id = :pack"
                + " AND c.status = 'DRAFT'")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("pack", packId)
        .query(JdbcStatutoryRepository::calculation)
        .optional();
  }

  /**
   * One calculation of a run in a legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId source run
   * @param calculationId calculation
   * @return the calculation, if it belongs to that run and legal entity in the tenant
   */
  public Optional<CalculationRow> calculation(
      TenantId tenant, UUID legalEntityId, UUID runId, UUID calculationId) {
    return jdbc.sql(
            "SELECT "
                + CALCULATION_COLUMNS
                + CALCULATION_FROM
                + " WHERE c.tenant_id = :tenant AND c.legal_entity_id = :entity"
                + " AND c.run_id = :run AND c.id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("run", runId)
        .param("id", calculationId)
        .query(JdbcStatutoryRepository::calculation)
        .optional();
  }

  /**
   * A page of the calculations of a run: version descending, then identifier.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId source run
   * @param after the last row of the previous page, or {@code null} for the first page
   * @param limit rows to read
   * @return the calculations
   */
  public List<CalculationRow> calculationPage(
      TenantId tenant, UUID legalEntityId, UUID runId, CalculationPosition after, int limit) {
    if (after == null) {
      return jdbc.sql(
              "SELECT "
                  + CALCULATION_COLUMNS
                  + CALCULATION_FROM
                  + " WHERE c.tenant_id = :tenant AND c.legal_entity_id = :entity"
                  + " AND c.run_id = :run ORDER BY c.version DESC, c.id LIMIT :limit")
          .param("tenant", tenant.value())
          .param("entity", legalEntityId)
          .param("run", runId)
          .param("limit", limit)
          .query(JdbcStatutoryRepository::calculation)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + CALCULATION_COLUMNS
                + CALCULATION_FROM
                + " WHERE c.tenant_id = :tenant AND c.legal_entity_id = :entity"
                + " AND c.run_id = :run AND (c.version < :version"
                + " OR (c.version = :version AND c.id > :after))"
                + " ORDER BY c.version DESC, c.id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("run", runId)
        .param("version", after.version())
        .param("after", after.id())
        .param("limit", limit)
        .query(JdbcStatutoryRepository::calculation)
        .list();
  }

  /**
   * Marks the current DRAFT superseded by its successor, which is inserted next (the successor's
   * key is checked at commit).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param calculationId the DRAFT to supersede
   * @param successorId the calculation that will be inserted
   * @param at server instant
   * @return the rows changed (1)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int supersede(
      TenantId tenant, UUID legalEntityId, UUID calculationId, UUID successorId, Instant at) {
    return jdbc.sql(
            "UPDATE payroll.statutory_calculation SET status = 'SUPERSEDED', superseded_at = :at,"
                + " superseded_by_id = :successor WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND id = :id AND status = 'DRAFT'")
        .param("at", Timestamp.from(at))
        .param("successor", successorId)
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", calculationId)
        .update();
  }

  /**
   * Inserts a calculation version.
   *
   * @param tenant verified tenant
   * @param calculation the calculation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCalculation(TenantId tenant, NewCalculation calculation) {
    jdbc.sql(
            "INSERT INTO payroll.statutory_calculation (tenant_id, legal_entity_id, id, run_id,"
                + " run_version, period_start, currency, pack_id, activation_id, version, status,"
                + " supported_count, excluded_count, blocked_count, gross_total_minor,"
                + " employee_deductions_total_minor, employer_contributions_total_minor,"
                + " preview_net_total_minor, employer_cost_total_minor, source_run_digest,"
                + " profile_digest, rule_pack_digest, calculation_digest, created_at, created_by)"
                + " VALUES (:tenant, :entity, :id, :run, :runVersion, :period, 'CDF', :pack,"
                + " :activation, :version, 'DRAFT', :supported, :excluded, 0, :gross, :deductions,"
                + " :contributions, :net, :cost, :sourceDigest, :profileDigest, :packDigest,"
                + " :calculationDigest, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", calculation.legalEntityId())
        .param("id", calculation.id())
        .param("run", calculation.runId())
        .param("runVersion", calculation.runVersion())
        .param("period", calculation.periodStart())
        .param("pack", calculation.packId())
        .param("activation", calculation.activationId())
        .param("version", calculation.version())
        .param("supported", calculation.supportedCount())
        .param("excluded", calculation.excludedCount())
        .param("gross", calculation.grossTotal())
        .param("deductions", calculation.deductionsTotal())
        .param("contributions", calculation.contributionsTotal())
        .param("net", calculation.netTotal())
        .param("cost", calculation.costTotal())
        .param("sourceDigest", calculation.sourceRunDigest())
        .param("profileDigest", calculation.profileDigest())
        .param("packDigest", calculation.rulePackDigest())
        .param("calculationDigest", calculation.calculationDigest())
        .param("at", Timestamp.from(calculation.createdAt()))
        .param("actor", calculation.actorSubject())
        .update();
  }

  /**
   * Inserts the lines of a calculation with their components.
   *
   * @param tenant verified tenant
   * @param calculationId the calculation being created
   * @param runId its source run
   * @param lines the lines
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertLines(TenantId tenant, UUID calculationId, UUID runId, List<NewLine> lines) {
    List<SqlParameterSource> lineParams = new ArrayList<>();
    List<SqlParameterSource> componentParams = new ArrayList<>();
    for (NewLine newLine : lines) {
      LineRow line = newLine.line();
      lineParams.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("calculation", calculationId)
              .addValue("id", line.id())
              .addValue("run", runId)
              .addValue("source", newLine.sourceLineId())
              .addValue("employee", line.employeeId())
              .addValue("number", line.employeeNumber())
              .addValue("gross", line.grossMinor())
              .addValue("deductions", line.deductionsMinor())
              .addValue("contributions", line.contributionsMinor())
              .addValue("net", line.netMinor())
              .addValue("cost", line.costMinor())
              .addValue("excluded", line.excluded()));
      for (ComponentRow component : newLine.components()) {
        componentParams.add(
            new MapSqlParameterSource()
                .addValue("tenant", tenant.value())
                .addValue("calculation", calculationId)
                .addValue("line", line.id())
                .addValue("seq", component.seq())
                .addValue("component", component.componentId())
                .addValue("party", component.party())
                .addValue("category", component.category())
                .addValue("base", component.baseMinor())
                .addValue("amount", component.amountMinor())
                .addValue("codes", String.join(",", component.explanationCodes()))
                .addValue("trace", component.traceJson()));
      }
    }
    batch.batchUpdate(
        "INSERT INTO payroll.statutory_calculation_line (tenant_id, calculation_id, id, run_id,"
            + " source_line_id, employee_id, employee_number, gross_minor,"
            + " employee_deductions_minor, employer_contributions_minor, preview_net_minor,"
            + " employer_cost_minor, excluded) VALUES (:tenant, :calculation, :id, :run, :source,"
            + " :employee, :number, :gross, :deductions, :contributions, :net, :cost, :excluded)",
        lineParams.toArray(SqlParameterSource[]::new));
    if (!componentParams.isEmpty()) {
      batch.batchUpdate(
          "INSERT INTO payroll.statutory_calculation_component (tenant_id, calculation_id,"
              + " line_id, seq, component_id, party, category, base_minor, amount_minor,"
              + " explanation_codes, trace) VALUES (:tenant, :calculation, :line, :seq,"
              + " :component, :party, :category, :base, :amount, string_to_array(:codes, ','),"
              + " CAST(:trace AS jsonb))",
          componentParams.toArray(SqlParameterSource[]::new));
    }
  }

  // -------------------------------------------------------------------------------------------
  // Lines and components
  // -------------------------------------------------------------------------------------------

  /**
   * One line of a calculation (the position a cursor names).
   *
   * @param tenant verified tenant
   * @param calculationId calculation
   * @param lineId line
   * @return the line, if it belongs to the calculation in the tenant
   */
  public Optional<LineRow> line(TenantId tenant, UUID calculationId, UUID lineId) {
    return jdbc.sql(
            "SELECT "
                + LINE_COLUMNS
                + " FROM payroll.statutory_calculation_line WHERE tenant_id = :tenant"
                + " AND calculation_id = :calculation AND id = :id")
        .param("tenant", tenant.value())
        .param("calculation", calculationId)
        .param("id", lineId)
        .query(JdbcStatutoryRepository::line)
        .optional();
  }

  /**
   * A page of a calculation's lines by employee number in byte order, then identifier.
   *
   * @param tenant verified tenant
   * @param calculationId calculation
   * @param afterNumber employee number of the last line of the previous page, or {@code null}
   * @param afterId identifier of that line, or {@code null}
   * @param limit rows to read
   * @return the lines
   */
  public List<LineRow> linePage(
      TenantId tenant, UUID calculationId, String afterNumber, UUID afterId, int limit) {
    if (afterNumber == null || afterId == null) {
      return jdbc.sql(
              "SELECT "
                  + LINE_COLUMNS
                  + " FROM payroll.statutory_calculation_line WHERE tenant_id = :tenant"
                  + " AND calculation_id = :calculation"
                  + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit")
          .param("tenant", tenant.value())
          .param("calculation", calculationId)
          .param("limit", limit)
          .query(JdbcStatutoryRepository::line)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + LINE_COLUMNS
                + " FROM payroll.statutory_calculation_line WHERE tenant_id = :tenant"
                + " AND calculation_id = :calculation"
                + " AND ((employee_number COLLATE \"C\"), id) > (CAST(:number AS text) COLLATE"
                + " \"C\", CAST(:after AS uuid))"
                + " ORDER BY (employee_number COLLATE \"C\"), id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("calculation", calculationId)
        .param("number", afterNumber)
        .param("after", afterId)
        .param("limit", limit)
        .query(JdbcStatutoryRepository::line)
        .list();
  }

  /**
   * The components of some lines of a calculation.
   *
   * @param tenant verified tenant
   * @param calculationId calculation
   * @param lineIds lines
   * @return the components by line, in rule order
   */
  public Map<UUID, List<ComponentRow>> components(
      TenantId tenant, UUID calculationId, Collection<UUID> lineIds) {
    Map<UUID, List<ComponentRow>> found = new HashMap<>();
    if (lineIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT line_id, seq, component_id, party, category, base_minor, amount_minor,"
                + " array_to_string(explanation_codes, ',') AS codes, trace::text AS trace"
                + " FROM payroll.statutory_calculation_component WHERE tenant_id = :tenant"
                + " AND calculation_id = :calculation AND line_id = ANY(:ids)"
                + " ORDER BY line_id, seq")
        .param("tenant", tenant.value())
        .param("calculation", calculationId)
        .param("ids", lineIds.toArray(UUID[]::new))
        .query(
            (rs, row) -> {
              String codes = rs.getString("codes");
              return Map.entry(
                  rs.getObject("line_id", UUID.class),
                  new ComponentRow(
                      rs.getInt("seq"),
                      rs.getString("component_id"),
                      rs.getString("party"),
                      rs.getString("category"),
                      rs.getLong("base_minor"),
                      rs.getLong("amount_minor"),
                      codes == null || codes.isEmpty()
                          ? List.of()
                          : Arrays.stream(codes.split(",")).collect(Collectors.toList()),
                      rs.getString("trace")));
            })
        .list()
        .forEach(
            entry ->
                found
                    .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(entry.getValue()));
    return found;
  }

  // -------------------------------------------------------------------------------------------
  // Row mapping
  // -------------------------------------------------------------------------------------------

  private static ActivationRow activation(ResultSet rs, int row) throws SQLException {
    return new ActivationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getInt("sequence"),
        rs.getObject("pack_id", UUID.class),
        rs.getString("pack_code"),
        rs.getInt("pack_version"),
        rs.getObject("from_month", LocalDate.class),
        rs.getTimestamp("activated_at").toInstant());
  }

  private static ProfileRow profile(ResultSet rs, int row) throws SQLException {
    return new ProfileRow(
        new StatutoryProfile(
            rs.getObject("id", UUID.class),
            rs.getInt("sequence"),
            rs.getObject("effective_from", LocalDate.class),
            StatutoryClassification.valueOf(rs.getString("classification")),
            rs.getBoolean("cnss_subject"),
            rs.getBoolean("irpp_subject"),
            StatutoryEvidence.valueOf(rs.getString("evidence_code"))),
        rs.getTimestamp("created_at").toInstant());
  }

  private static CalculationRow calculation(ResultSet rs, int row) throws SQLException {
    Timestamp superseded = rs.getTimestamp("superseded_at");
    return new CalculationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getInt("run_version"),
        rs.getObject("period_start", LocalDate.class),
        rs.getString("currency"),
        rs.getObject("pack_id", UUID.class),
        rs.getString("pack_code"),
        rs.getInt("pack_version"),
        rs.getObject("activation_id", UUID.class),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getInt("supported_count"),
        rs.getInt("excluded_count"),
        rs.getLong("gross_total_minor"),
        rs.getLong("employee_deductions_total_minor"),
        rs.getLong("employer_contributions_total_minor"),
        rs.getLong("preview_net_total_minor"),
        rs.getLong("employer_cost_total_minor"),
        rs.getString("source_run_digest").trim(),
        rs.getString("profile_digest").trim(),
        rs.getString("rule_pack_digest").trim(),
        rs.getString("calculation_digest").trim(),
        rs.getTimestamp("created_at").toInstant(),
        superseded == null ? null : superseded.toInstant(),
        rs.getObject("superseded_by_id", UUID.class));
  }

  private static LineRow line(ResultSet rs, int row) throws SQLException {
    return new LineRow(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getLong("gross_minor"),
        rs.getLong("employee_deductions_minor"),
        rs.getLong("employer_contributions_minor"),
        rs.getLong("preview_net_minor"),
        rs.getLong("employer_cost_minor"),
        rs.getBoolean("excluded"));
  }
}
