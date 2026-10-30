package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of payroll packages, their source bindings, void evidence and payslip snapshots
 * (MVP-094C; V46, ADR 0035). Every method takes the verified {@link TenantId} and every statement
 * filters on it; the employee queries also filter on the employee of the verified link. Rows are
 * only ever inserted; the one update is APPROVED to VOIDED, after its void evidence (the V46
 * triggers enforce it). The approver subject and the void explanation are never selected into a
 * package row. Restricted financial and HR data: no record here prints an amount, an employee
 * number, a snapshot or a digest.
 */
@Repository
public class JdbcPayrollPackageRepository {

  private static final String PACKAGE_COLUMNS =
      "p.id, p.legal_entity_id, p.period_start, p.currency, p.version, p.status, p.run_id,"
          + " p.run_version, p.run_digest, p.calculation_id, p.calculation_version,"
          + " p.calculation_digest, p.calculation_source_run_digest,"
          + " p.calculation_profile_digest, p.calculation_rule_pack_digest, p.pack_id,"
          + " p.pack_code, p.pack_version, p.rules_digest, p.synthetic, p.policy_id,"
          + " p.policy_sequence, p.payslip_count, p.gross_total_minor,"
          + " p.employee_deductions_total_minor, p.employer_contributions_total_minor,"
          + " p.net_total_minor, p.employer_cost_total_minor, p.reviewed_totals,"
          + " p.reviewed_exceptions, p.understands_no_payment, p.same_preparer,"
          + " p.approval_digest, p.approved_at, p.voided_at, v.reason_code AS void_reason";

  private static final String PACKAGE_FROM =
      " FROM payroll.payroll_package p LEFT JOIN payroll.payroll_package_void v"
          + " ON v.tenant_id = p.tenant_id AND v.package_id = p.id";

  private static final String SLIP_COLUMNS =
      "s.id, s.package_id, s.legal_entity_id, s.period_start, s.approved_at, s.employee_id,"
          + " s.employee_number, s.gross_minor, s.employee_deductions_minor,"
          + " s.employer_contributions_minor, s.net_minor, s.snapshot_text, s.snapshot_digest,"
          + " p.status AS package_status, p.synthetic, p.voided_at, v.reason_code AS void_reason";

  private static final String SLIP_FROM =
      " FROM payroll.payroll_payslip s JOIN payroll.payroll_package p"
          + " ON p.tenant_id = s.tenant_id AND p.id = s.package_id"
          + " LEFT JOIN payroll.payroll_package_void v"
          + " ON v.tenant_id = p.tenant_id AND v.package_id = p.id";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcPayrollPackageRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /**
   * A stored package (Restricted financial data). The approver subject is not part of it.
   *
   * @param id package
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency {@code CDF}
   * @param version version in the chain
   * @param status {@code APPROVED} or {@code VOIDED}
   * @param runId frozen run
   * @param runVersion its version
   * @param runDigest its input digest
   * @param calculationId statutory calculation
   * @param calculationVersion its version
   * @param calculationDigest its digest
   * @param calculationSourceRunDigest its source-run digest
   * @param calculationProfileDigest its profile digest
   * @param calculationRulePackDigest its rule-pack digest
   * @param packId rule pack
   * @param packCode its code
   * @param packVersion its version
   * @param rulesDigest its rules digest
   * @param synthetic whether the pack is a SYNTHETIC-TEST pack
   * @param policyId frozen policy
   * @param policySequence its sequence
   * @param payslipCount payslips
   * @param grossTotal total contractual gross
   * @param deductionsTotal total employee deductions
   * @param contributionsTotal total employer contributions
   * @param netTotal total net
   * @param costTotal total employer cost
   * @param reviewedTotals acknowledgement
   * @param reviewedExceptions acknowledgement
   * @param understandsNoPayment acknowledgement
   * @param samePreparer whether the approver also prepared a source
   * @param approvalDigest the approval digest
   * @param approvedAt approval instant
   * @param voidedAt void instant, or {@code null}
   * @param voidReasonCode closed void reason, or {@code null}
   */
  public record PackageRow(
      UUID id,
      UUID legalEntityId,
      LocalDate periodStart,
      String currency,
      int version,
      String status,
      UUID runId,
      int runVersion,
      String runDigest,
      UUID calculationId,
      int calculationVersion,
      String calculationDigest,
      String calculationSourceRunDigest,
      String calculationProfileDigest,
      String calculationRulePackDigest,
      UUID packId,
      String packCode,
      int packVersion,
      String rulesDigest,
      boolean synthetic,
      UUID policyId,
      int policySequence,
      int payslipCount,
      long grossTotal,
      long deductionsTotal,
      long contributionsTotal,
      long netTotal,
      long costTotal,
      boolean reviewedTotals,
      boolean reviewedExceptions,
      boolean understandsNoPayment,
      boolean samePreparer,
      String approvalDigest,
      Instant approvedAt,
      Instant voidedAt,
      String voidReasonCode) {

    @Override
    public String toString() {
      return "PayrollPackageRow[" + id + "]";
    }
  }

  /**
   * A package to insert, APPROVED, as the next version of its chain.
   *
   * @param id new package
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param version next version of the chain
   * @param runId frozen run
   * @param runVersion its version
   * @param runDigest its input digest
   * @param calculationId statutory calculation
   * @param calculationVersion its version
   * @param calculationDigest its digest
   * @param calculationSourceRunDigest its source-run digest
   * @param calculationProfileDigest its profile digest
   * @param calculationRulePackDigest its rule-pack digest
   * @param packId rule pack
   * @param packCode its code
   * @param packVersion its version
   * @param rulesDigest its rules digest
   * @param policyId frozen policy
   * @param policySequence its sequence
   * @param payslipCount payslips
   * @param grossTotal total contractual gross
   * @param deductionsTotal total employee deductions
   * @param contributionsTotal total employer contributions
   * @param netTotal total net
   * @param costTotal total employer cost
   * @param samePreparer whether the approver also prepared a source
   * @param approvalDigest the approval digest the database recomputes
   * @param approvedAt approval instant
   * @param approvedBySubject verified approver subject (restricted, never read back)
   */
  public record NewPackage(
      UUID id,
      UUID legalEntityId,
      LocalDate periodStart,
      int version,
      UUID runId,
      int runVersion,
      String runDigest,
      UUID calculationId,
      int calculationVersion,
      String calculationDigest,
      String calculationSourceRunDigest,
      String calculationProfileDigest,
      String calculationRulePackDigest,
      UUID packId,
      String packCode,
      int packVersion,
      String rulesDigest,
      UUID policyId,
      int policySequence,
      int payslipCount,
      long grossTotal,
      long deductionsTotal,
      long contributionsTotal,
      long netTotal,
      long costTotal,
      boolean samePreparer,
      String approvalDigest,
      Instant approvedAt,
      String approvedBySubject) {

    @Override
    public String toString() {
      return "NewPayrollPackage[" + id + "]";
    }
  }

  /**
   * A payslip to insert.
   *
   * @param id new payslip
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param calculationLineId the statutory calculation line it copies
   * @param excluded whether the line is excluded
   * @param grossMinor gross
   * @param deductionsMinor employee deductions
   * @param contributionsMinor employer contributions
   * @param netMinor net
   * @param costMinor employer cost
   * @param snapshotText the canonical snapshot text
   */
  public record NewSlip(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      UUID calculationLineId,
      boolean excluded,
      long grossMinor,
      long deductionsMinor,
      long contributionsMinor,
      long netMinor,
      long costMinor,
      String snapshotText) {

    @Override
    public String toString() {
      return "NewPayslip[" + id + "]";
    }
  }

  /**
   * A stored payslip with the status of its package (Restricted financial and HR data).
   *
   * @param id payslip
   * @param packageId package
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param approvedAt approval instant
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param grossMinor gross
   * @param deductionsMinor employee deductions
   * @param contributionsMinor employer contributions
   * @param netMinor net
   * @param snapshotText the canonical snapshot text
   * @param snapshotDigest its SHA-256
   * @param packageStatus {@code APPROVED} or {@code VOIDED}
   * @param synthetic whether the pack is a SYNTHETIC-TEST pack
   * @param voidedAt void instant, or {@code null}
   * @param voidReasonCode closed void reason, or {@code null}
   */
  public record SlipRow(
      UUID id,
      UUID packageId,
      UUID legalEntityId,
      LocalDate periodStart,
      Instant approvedAt,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      long deductionsMinor,
      long contributionsMinor,
      long netMinor,
      String snapshotText,
      String snapshotDigest,
      String packageStatus,
      boolean synthetic,
      Instant voidedAt,
      String voidReasonCode) {

    @Override
    public String toString() {
      return "PayslipRow[" + id + "]";
    }
  }

  /**
   * The void evidence of a package, restricted.
   *
   * @param reasonCode closed reason
   * @param explanation the explanation of OTHER_CONTROLLED, or {@code null}
   * @param voidedAt void instant
   */
  public record VoidRow(String reasonCode, String explanation, Instant voidedAt) {

    @Override
    public String toString() {
      return "PayrollVoidRow[" + reasonCode + "]";
    }
  }

  /**
   * The void evidence to insert.
   *
   * @param id new evidence row
   * @param reasonCode closed reason
   * @param explanation the explanation of OTHER_CONTROLLED, or {@code null}
   * @param approvalDigest the package's approval digest
   * @param voidedAt void instant
   * @param voidedBySubject verified subject
   */
  public record NewVoid(
      UUID id,
      String reasonCode,
      String explanation,
      String approvalDigest,
      Instant voidedAt,
      String voidedBySubject) {

    @Override
    public String toString() {
      return "NewPayrollVoid[" + id + "]";
    }
  }

  /**
   * The month and currency of a locked run.
   *
   * @param periodStart first day of the month
   * @param currency run currency
   */
  public record SourceKey(LocalDate periodStart, String currency) {}

  /**
   * The creators of the two sources (restricted subjects, compared only).
   *
   * @param runCreatedBy creator of the payroll run
   * @param calculationCreatedBy creator of the statutory calculation
   */
  public record Preparers(String runCreatedBy, String calculationCreatedBy) {

    @Override
    public String toString() {
      return "PayrollPreparers[redacted]";
    }
  }

  /**
   * Where a package page continues after.
   *
   * @param periodStart the last row's month
   * @param version the last row's version
   * @param id the last row
   */
  public record PackagePosition(LocalDate periodStart, int version, UUID id) {}

  /**
   * Where an employee's payslip page continues after.
   *
   * @param approvedAt the last row's approval instant
   * @param id the last row
   */
  public record EmployeePosition(Instant approvedAt, UUID id) {}

  // -------------------------------------------------------------------------------------------
  // Locks and deferred checks (ADR 0035 section 8)
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the legal entity's package lock for the rest of the transaction (the key the V46 trigger
   * takes): last of the advisory locks in the global order.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockPackage(TenantId tenant, UUID legalEntityId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.package:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:entity AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .query()
        .singleRow();
  }

  /**
   * Locks the run row and then the calculation row {@code FOR UPDATE} (lock order: run, then
   * calculation), until the transaction ends.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param calculationId statutory calculation of that run
   * @return the month and currency of the run, or empty when the run or the calculation of that
   *     run does not exist in that legal entity of the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<SourceKey> lockSources(
      TenantId tenant, UUID legalEntityId, UUID runId, UUID calculationId) {
    Optional<SourceKey> key =
        jdbc.sql(
                "SELECT period_start, currency FROM payroll.pay_run WHERE tenant_id = :tenant"
                    + " AND legal_entity_id = :entity AND id = :run FOR UPDATE")
            .param("tenant", tenant.value())
            .param("entity", legalEntityId)
            .param("run", runId)
            .query(
                (rs, row) ->
                    new SourceKey(
                        rs.getObject("period_start", LocalDate.class), rs.getString("currency")))
            .optional();
    if (key.isEmpty()) {
      return Optional.empty();
    }
    boolean calculation =
        jdbc.sql(
                "SELECT id FROM payroll.statutory_calculation WHERE tenant_id = :tenant"
                    + " AND legal_entity_id = :entity AND run_id = :run AND id = :id FOR UPDATE")
            .param("tenant", tenant.value())
            .param("entity", legalEntityId)
            .param("run", runId)
            .param("id", calculationId)
            .query(UUID.class)
            .optional()
            .isPresent();
    return calculation ? key : Optional.empty();
  }

  /**
   * Locks the package row {@code FOR UPDATE} until the transaction ends.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @return whether the package exists in that legal entity of the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockPackageRow(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT id FROM payroll.payroll_package WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", packageId)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /**
   * Runs the deferred V46 checks now (inside the caller's transaction), so a violation surfaces
   * inside the business work, where it is mapped by constraint name, rather than at commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payroll_package_exact, payroll.payroll_payslip_exact,"
                + " payroll.payroll_package_source_exact, payroll.payroll_package_void_exact"
                + " IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Reads of the sources and the chain
  // -------------------------------------------------------------------------------------------

  /**
   * The creators of the run and of the calculation (for the same-preparer fact).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param calculationId statutory calculation
   * @return the creators, or empty when either does not exist
   */
  public Optional<Preparers> preparers(
      TenantId tenant, UUID legalEntityId, UUID runId, UUID calculationId) {
    return jdbc.sql(
            "SELECT r.created_by AS run_by, c.created_by AS calculation_by"
                + " FROM payroll.pay_run r JOIN payroll.statutory_calculation c"
                + " ON c.tenant_id = r.tenant_id AND c.legal_entity_id = r.legal_entity_id"
                + " AND c.run_id = r.id"
                + " WHERE r.tenant_id = :tenant AND r.legal_entity_id = :entity AND r.id = :run"
                + " AND c.tenant_id = :tenant AND c.id = :calculation")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("run", runId)
        .param("calculation", calculationId)
        .query(
            (rs, row) -> new Preparers(rs.getString("run_by"), rs.getString("calculation_by")))
        .optional();
  }

  /**
   * The packages of a chain, version ascending.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency currency
   * @return the packages
   */
  public List<PackageRow> chain(
      TenantId tenant, UUID legalEntityId, LocalDate periodStart, String currency) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + PACKAGE_FROM
                + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
                + " AND p.period_start = :period AND p.currency = :currency ORDER BY p.version")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("period", periodStart)
        .param("currency", currency)
        .query(JdbcPayrollPackageRepository::packageRow)
        .list();
  }

  /**
   * The packages of a chain locked {@code FOR UPDATE} in identifier order (lock order: after the
   * run and the calculation rows).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param periodStart first day of the month
   * @param currency currency
   * @return the packages, in identifier order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<PackageRow> chainForUpdate(
      TenantId tenant, UUID legalEntityId, LocalDate periodStart, String currency) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + PACKAGE_FROM
                + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
                + " AND p.period_start = :period AND p.currency = :currency"
                + " ORDER BY p.id FOR UPDATE OF p")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("period", periodStart)
        .param("currency", currency)
        .query(JdbcPayrollPackageRepository::packageRow)
        .list();
  }

  /**
   * The packages that bound exactly this run and calculation.
   *
   * @param tenant verified tenant
   * @param runId payroll run
   * @param calculationId statutory calculation
   * @return the packages (at most one per outcome in practice)
   */
  public List<PackageRow> packagesOfPair(TenantId tenant, UUID runId, UUID calculationId) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + PACKAGE_FROM
                + " WHERE p.tenant_id = :tenant AND p.run_id = :run"
                + " AND p.calculation_id = :calculation ORDER BY p.version")
        .param("tenant", tenant.value())
        .param("run", runId)
        .param("calculation", calculationId)
        .query(JdbcPayrollPackageRepository::packageRow)
        .list();
  }

  // -------------------------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------------------------

  /**
   * Inserts the APPROVED package.
   *
   * @param tenant verified tenant
   * @param row the package
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertPackage(TenantId tenant, NewPackage row) {
    jdbc.sql(
            "INSERT INTO payroll.payroll_package (tenant_id, legal_entity_id, id, period_start,"
                + " currency, version, status, run_id, run_version, run_digest, calculation_id,"
                + " calculation_version, calculation_digest, calculation_source_run_digest,"
                + " calculation_profile_digest, calculation_rule_pack_digest, pack_id, pack_code,"
                + " pack_version, rules_digest, policy_id, policy_sequence, payslip_count,"
                + " gross_total_minor, employee_deductions_total_minor,"
                + " employer_contributions_total_minor, net_total_minor, employer_cost_total_minor,"
                + " reviewed_totals, reviewed_exceptions, understands_no_payment, same_preparer,"
                + " approval_digest, approved_at, approved_by_subject)"
                + " VALUES (:tenant, :entity, :id, :period, 'CDF', :version, 'APPROVED', :run,"
                + " :runVersion, :runDigest, :calculation, :calculationVersion,"
                + " :calculationDigest, :sourceDigest, :profileDigest, :packDigest, :pack,"
                + " :packCode, :packVersion, :rulesDigest, :policy, :policySequence, :count,"
                + " :gross, :deductions, :contributions, :net, :cost, true, true, true,"
                + " :same, :approvalDigest, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", row.legalEntityId())
        .param("id", row.id())
        .param("period", row.periodStart())
        .param("version", row.version())
        .param("run", row.runId())
        .param("runVersion", row.runVersion())
        .param("runDigest", row.runDigest())
        .param("calculation", row.calculationId())
        .param("calculationVersion", row.calculationVersion())
        .param("calculationDigest", row.calculationDigest())
        .param("sourceDigest", row.calculationSourceRunDigest())
        .param("profileDigest", row.calculationProfileDigest())
        .param("packDigest", row.calculationRulePackDigest())
        .param("pack", row.packId())
        .param("packCode", row.packCode())
        .param("packVersion", row.packVersion())
        .param("rulesDigest", row.rulesDigest())
        .param("policy", row.policyId())
        .param("policySequence", row.policySequence())
        .param("count", row.payslipCount())
        .param("gross", row.grossTotal())
        .param("deductions", row.deductionsTotal())
        .param("contributions", row.contributionsTotal())
        .param("net", row.netTotal())
        .param("cost", row.costTotal())
        .param("same", row.samePreparer())
        .param("approvalDigest", row.approvalDigest())
        .param("at", Timestamp.from(row.approvedAt()))
        .param("actor", row.approvedBySubject())
        .update();
  }

  /**
   * Inserts the two source bindings of a package (the run and the calculation).
   *
   * @param tenant verified tenant
   * @param row the package they bind
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertSources(TenantId tenant, NewPackage row) {
    insertSource(tenant, row.id(), "RUN", row.runId(), row.runVersion(), row.runDigest());
    insertSource(
        tenant,
        row.id(),
        "CALCULATION",
        row.calculationId(),
        row.calculationVersion(),
        row.calculationDigest());
  }

  private void insertSource(
      TenantId tenant, UUID packageId, String kind, UUID sourceId, int version, String digest) {
    jdbc.sql(
            "INSERT INTO payroll.payroll_package_source (tenant_id, package_id, kind, source_id,"
                + " source_version, source_digest) VALUES (:tenant, :package, :kind, :source,"
                + " :version, :digest)")
        .param("tenant", tenant.value())
        .param("package", packageId)
        .param("kind", kind)
        .param("source", sourceId)
        .param("version", version)
        .param("digest", digest)
        .update();
  }

  /**
   * Inserts the payslip snapshots of a package.
   *
   * @param tenant verified tenant
   * @param row the package they belong to
   * @param slips the payslips
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertSlips(TenantId tenant, NewPackage row, List<NewSlip> slips) {
    List<SqlParameterSource> params = new ArrayList<>();
    for (NewSlip slip : slips) {
      params.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("entity", row.legalEntityId())
              .addValue("package", row.id())
              .addValue("id", slip.id())
              .addValue("period", row.periodStart())
              .addValue("at", Timestamp.from(row.approvedAt()))
              .addValue("employee", slip.employeeId())
              .addValue("number", slip.employeeNumber())
              .addValue("calculation", row.calculationId())
              .addValue("line", slip.calculationLineId())
              .addValue("excluded", slip.excluded())
              .addValue("gross", slip.grossMinor())
              .addValue("deductions", slip.deductionsMinor())
              .addValue("contributions", slip.contributionsMinor())
              .addValue("net", slip.netMinor())
              .addValue("cost", slip.costMinor())
              .addValue("text", slip.snapshotText()));
    }
    batch.batchUpdate(
        "INSERT INTO payroll.payroll_payslip (tenant_id, legal_entity_id, package_id, id,"
            + " period_start, approved_at, employee_id, employee_number, calculation_id,"
            + " calculation_line_id, excluded, gross_minor, employee_deductions_minor,"
            + " employer_contributions_minor, net_minor, employer_cost_minor, snapshot_version,"
            + " snapshot_text) VALUES (:tenant, :entity, :package, :id, :period, :at, :employee,"
            + " :number, :calculation, :line, :excluded, :gross, :deductions, :contributions,"
            + " :net, :cost, 1, :text)",
        params.toArray(SqlParameterSource[]::new));
  }

  /**
   * Inserts the void evidence (before the package row is updated).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param evidence the evidence
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertVoid(TenantId tenant, UUID legalEntityId, UUID packageId, NewVoid evidence) {
    jdbc.sql(
            "INSERT INTO payroll.payroll_package_void (tenant_id, legal_entity_id, package_id,"
                + " id, reason_code, explanation, no_payment_acknowledged, approval_digest,"
                + " voided_at, voided_by_subject) VALUES (:tenant, :entity, :package, :id,"
                + " :reason, :explanation, true, :digest, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .param("id", evidence.id())
        .param("reason", evidence.reasonCode())
        .param("explanation", evidence.explanation())
        .param("digest", evidence.approvalDigest())
        .param("at", Timestamp.from(evidence.voidedAt()))
        .param("actor", evidence.voidedBySubject())
        .update();
  }

  /**
   * The one legal update: APPROVED to VOIDED, with the instant of the void evidence.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param voidedAt the instant of the void evidence row
   * @return the rows changed (1)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int markVoided(TenantId tenant, UUID legalEntityId, UUID packageId, Instant voidedAt) {
    return jdbc.sql(
            "UPDATE payroll.payroll_package SET status = 'VOIDED', voided_at = :at"
                + " WHERE tenant_id = :tenant AND legal_entity_id = :entity AND id = :id"
                + " AND status = 'APPROVED'")
        .param("at", Timestamp.from(voidedAt))
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", packageId)
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Package reads
  // -------------------------------------------------------------------------------------------

  /**
   * One package of a legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @return the package, if it belongs to that legal entity in the tenant
   */
  public Optional<PackageRow> pack(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + PACKAGE_FROM
                + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity AND p.id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", packageId)
        .query(JdbcPayrollPackageRepository::packageRow)
        .optional();
  }

  /**
   * The void evidence of a package.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @return the evidence, if the package is voided
   */
  public Optional<VoidRow> voidEvidence(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT reason_code, explanation, voided_at FROM payroll.payroll_package_void"
                + " WHERE tenant_id = :tenant AND legal_entity_id = :entity"
                + " AND package_id = :package")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .query(
            (rs, row) ->
                new VoidRow(
                    rs.getString("reason_code"),
                    rs.getString("explanation"),
                    rs.getTimestamp("voided_at").toInstant()))
        .optional();
  }

  /**
   * A page of a legal entity's packages: month descending, version descending, then identifier.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param status {@code APPROVED}, {@code VOIDED} or {@code null} for both
   * @param after the last row of the previous page, or {@code null} for the first page
   * @param limit rows to read
   * @return the packages
   */
  public List<PackageRow> packagePage(
      TenantId tenant, UUID legalEntityId, String status, PackagePosition after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(PACKAGE_COLUMNS)
            .append(PACKAGE_FROM)
            .append(" WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity");
    if (status != null) {
      sql.append(" AND p.status = :status");
    }
    if (after != null) {
      sql.append(
          " AND (p.period_start < :period OR (p.period_start = :period AND (p.version < :version"
              + " OR (p.version = :version AND p.id > :after))))");
    }
    sql.append(" ORDER BY p.period_start DESC, p.version DESC, p.id LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenant.value())
            .param("entity", legalEntityId)
            .param("limit", limit);
    if (status != null) {
      spec = spec.param("status", status);
    }
    if (after != null) {
      spec =
          spec.param("period", after.periodStart())
              .param("version", after.version())
              .param("after", after.id());
    }
    return spec.query(JdbcPayrollPackageRepository::packageRow).list();
  }

  // -------------------------------------------------------------------------------------------
  // Payslip reads
  // -------------------------------------------------------------------------------------------

  /**
   * One payslip of a package of a legal entity (administrator).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param payslipId payslip
   * @return the payslip, if it belongs to that package and legal entity in the tenant
   */
  public Optional<SlipRow> slip(
      TenantId tenant, UUID legalEntityId, UUID packageId, UUID payslipId) {
    return jdbc.sql(
            "SELECT "
                + SLIP_COLUMNS
                + SLIP_FROM
                + " WHERE s.tenant_id = :tenant AND s.legal_entity_id = :entity"
                + " AND s.package_id = :package AND s.id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .param("id", payslipId)
        .query(JdbcPayrollPackageRepository::slipRow)
        .optional();
  }

  /**
   * A page of a package's payslips by employee number in byte order, then identifier.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param afterNumber employee number of the last row of the previous page, or {@code null}
   * @param afterId identifier of that row, or {@code null}
   * @param limit rows to read
   * @return the payslips
   */
  public List<SlipRow> slipPage(
      TenantId tenant,
      UUID legalEntityId,
      UUID packageId,
      String afterNumber,
      UUID afterId,
      int limit) {
    if (afterNumber == null || afterId == null) {
      return jdbc.sql(
              "SELECT "
                  + SLIP_COLUMNS
                  + SLIP_FROM
                  + " WHERE s.tenant_id = :tenant AND s.legal_entity_id = :entity"
                  + " AND s.package_id = :package"
                  + " ORDER BY (s.employee_number COLLATE \"C\"), s.id LIMIT :limit")
          .param("tenant", tenant.value())
          .param("entity", legalEntityId)
          .param("package", packageId)
          .param("limit", limit)
          .query(JdbcPayrollPackageRepository::slipRow)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + SLIP_COLUMNS
                + SLIP_FROM
                + " WHERE s.tenant_id = :tenant AND s.legal_entity_id = :entity"
                + " AND s.package_id = :package"
                + " AND ((s.employee_number COLLATE \"C\"), s.id) >"
                + " (CAST(:number AS text) COLLATE \"C\", CAST(:after AS uuid))"
                + " ORDER BY (s.employee_number COLLATE \"C\"), s.id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .param("number", afterNumber)
        .param("after", afterId)
        .param("limit", limit)
        .query(JdbcPayrollPackageRepository::slipRow)
        .list();
  }

  /**
   * One payslip of the employee of the verified link (self-service). The employee predicate is
   * part of the statement: another employee's payslip is simply not found.
   *
   * @param tenant verified tenant
   * @param employeeId the employee of the caller's active link
   * @param payslipId payslip
   * @return the payslip, if it is the employee's own in the tenant
   */
  public Optional<SlipRow> mySlip(TenantId tenant, UUID employeeId, UUID payslipId) {
    return jdbc.sql(
            "SELECT "
                + SLIP_COLUMNS
                + SLIP_FROM
                + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee AND s.id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", payslipId)
        .query(JdbcPayrollPackageRepository::slipRow)
        .optional();
  }

  /**
   * A page of the employee's own payslips, newest first (approval instant, then identifier).
   *
   * @param tenant verified tenant
   * @param employeeId the employee of the caller's active link
   * @param after the last row of the previous page, or {@code null} for the first page
   * @param limit rows to read
   * @return the payslips
   */
  public List<SlipRow> mySlipPage(
      TenantId tenant, UUID employeeId, EmployeePosition after, int limit) {
    if (after == null) {
      return jdbc.sql(
              "SELECT "
                  + SLIP_COLUMNS
                  + SLIP_FROM
                  + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee"
                  + " ORDER BY s.approved_at DESC, s.id DESC LIMIT :limit")
          .param("tenant", tenant.value())
          .param("employee", employeeId)
          .param("limit", limit)
          .query(JdbcPayrollPackageRepository::slipRow)
          .list();
    }
    return jdbc.sql(
            "SELECT "
                + SLIP_COLUMNS
                + SLIP_FROM
                + " WHERE s.tenant_id = :tenant AND s.employee_id = :employee"
                + " AND (s.approved_at < :at OR (s.approved_at = :at AND s.id < :after))"
                + " ORDER BY s.approved_at DESC, s.id DESC LIMIT :limit")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("at", Timestamp.from(after.approvedAt()))
        .param("after", after.id())
        .param("limit", limit)
        .query(JdbcPayrollPackageRepository::slipRow)
        .list();
  }

  // -------------------------------------------------------------------------------------------
  // Row mapping
  // -------------------------------------------------------------------------------------------

  private static PackageRow packageRow(ResultSet rs, int row) throws SQLException {
    Timestamp voided = rs.getTimestamp("voided_at");
    return new PackageRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("period_start", LocalDate.class),
        rs.getString("currency"),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getObject("run_id", UUID.class),
        rs.getInt("run_version"),
        rs.getString("run_digest").trim(),
        rs.getObject("calculation_id", UUID.class),
        rs.getInt("calculation_version"),
        rs.getString("calculation_digest").trim(),
        rs.getString("calculation_source_run_digest").trim(),
        rs.getString("calculation_profile_digest").trim(),
        rs.getString("calculation_rule_pack_digest").trim(),
        rs.getObject("pack_id", UUID.class),
        rs.getString("pack_code"),
        rs.getInt("pack_version"),
        rs.getString("rules_digest").trim(),
        rs.getBoolean("synthetic"),
        rs.getObject("policy_id", UUID.class),
        rs.getInt("policy_sequence"),
        rs.getInt("payslip_count"),
        rs.getLong("gross_total_minor"),
        rs.getLong("employee_deductions_total_minor"),
        rs.getLong("employer_contributions_total_minor"),
        rs.getLong("net_total_minor"),
        rs.getLong("employer_cost_total_minor"),
        rs.getBoolean("reviewed_totals"),
        rs.getBoolean("reviewed_exceptions"),
        rs.getBoolean("understands_no_payment"),
        rs.getBoolean("same_preparer"),
        rs.getString("approval_digest").trim(),
        rs.getTimestamp("approved_at").toInstant(),
        voided == null ? null : voided.toInstant(),
        rs.getString("void_reason"));
  }

  private static SlipRow slipRow(ResultSet rs, int row) throws SQLException {
    Timestamp voided = rs.getTimestamp("voided_at");
    return new SlipRow(
        rs.getObject("id", UUID.class),
        rs.getObject("package_id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("period_start", LocalDate.class),
        rs.getTimestamp("approved_at").toInstant(),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getLong("gross_minor"),
        rs.getLong("employee_deductions_minor"),
        rs.getLong("employer_contributions_minor"),
        rs.getLong("net_minor"),
        rs.getString("snapshot_text"),
        rs.getString("snapshot_digest").trim(),
        rs.getString("package_status"),
        rs.getBoolean("synthetic"),
        voided == null ? null : voided.toInstant(),
        rs.getString("void_reason"));
  }
}
