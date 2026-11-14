package com.divalhr.core.payroll.internal;

import com.divalhr.core.payroll.domain.AccountingAccounts;
import com.divalhr.core.payroll.domain.AccountingDirection;
import com.divalhr.core.payroll.domain.AccountingJournalBuilder;
import com.divalhr.core.payroll.domain.AccountingLine;
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
 * Storage of accounting mapping versions and accounting journals (MVP-096; V50, ADR 0039). Every
 * method takes the verified {@link TenantId} and every statement filters on it. Mapping versions,
 * journal headers and journal lines are only ever inserted; the database refuses every update,
 * delete and truncate. The payslip statement selects only the four frozen amount columns of a
 * package (never an employee identifier, number or snapshot), and nothing here writes a payroll,
 * payslip, payment or reconciliation row. No amount, account code or digest is ever logged.
 */
@Repository
public class JdbcAccountingRepository {

  private static final String MAPPING_COLUMNS =
      "m.id, m.legal_entity_id, m.currency, m.version, m.effective_from, m.salary_expense,"
          + " m.employee_deduction_liability, m.employer_contribution_expense,"
          + " m.employer_contribution_liability, m.net_salary_payable, m.mapping_digest,"
          + " m.created_at";

  private static final String PACKAGE_COLUMNS =
      "p.id, p.legal_entity_id, p.status, p.voided_at, p.period_start, p.currency, p.version,"
          + " p.approval_digest, p.calculation_id, p.calculation_version, p.calculation_digest,"
          + " p.payslip_count, p.gross_total_minor, p.employee_deductions_total_minor,"
          + " p.employer_contributions_total_minor, p.net_total_minor";

  private static final String JOURNAL_COLUMNS =
      "j.id, j.legal_entity_id, j.package_id, j.currency, j.version, j.period_start,"
          + " j.legal_entity_code, j.package_version, j.package_approval_digest,"
          + " j.calculation_id, j.calculation_version, j.calculation_digest, j.mapping_id,"
          + " j.mapping_version, j.mapping_digest, m.effective_from AS mapping_effective_from,"
          + " j.payslip_count, j.source_entry_count, j.line_count, j.total_debits_minor,"
          + " j.total_credits_minor, j.csv_sha256, j.csv_bytes, j.journal_digest,"
          + " j.schema_version, j.generated_at, (p.voided_at IS NOT NULL) AS source_voided";

  private static final String JOURNAL_FROM =
      " FROM payroll.accounting_journal j JOIN payroll.accounting_mapping m"
          + " ON m.tenant_id = j.tenant_id AND m.legal_entity_id = j.legal_entity_id"
          + " AND m.id = j.mapping_id JOIN payroll.payroll_package p"
          + " ON p.tenant_id = j.tenant_id AND p.legal_entity_id = j.legal_entity_id"
          + " AND p.id = j.package_id";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcAccountingRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  // -------------------------------------------------------------------------------------------
  // Rows
  // -------------------------------------------------------------------------------------------

  /**
   * A stored mapping version.
   *
   * @param id mapping row
   * @param legalEntityId legal entity
   * @param currency CDF or USD
   * @param version 1 to 10
   * @param effectiveFrom first day it applies
   * @param accounts the five codes
   * @param mappingDigest stored digest
   * @param createdAt creation instant
   */
  public record MappingRow(
      UUID id,
      UUID legalEntityId,
      String currency,
      int version,
      LocalDate effectiveFrom,
      AccountingAccounts accounts,
      String mappingDigest,
      Instant createdAt) {
    @Override
    public String toString() {
      return "AccountingMappingRow[" + id + "]";
    }
  }

  /**
   * A mapping version to insert.
   *
   * @param id new row
   * @param legalEntityId legal entity
   * @param currency CDF or USD
   * @param version the next version
   * @param effectiveFrom first day it applies
   * @param accounts the five codes
   * @param mappingDigest digest computed by the application
   * @param createdAt creation instant
   * @param createdBy verified administrator
   */
  public record NewMapping(
      UUID id,
      UUID legalEntityId,
      String currency,
      int version,
      LocalDate effectiveFrom,
      AccountingAccounts accounts,
      String mappingDigest,
      Instant createdAt,
      String createdBy) {
    @Override
    public String toString() {
      return "NewAccountingMapping[" + id + "]";
    }
  }

  /**
   * The facts of a payroll package a journal depends on (no employee data).
   *
   * @param id package
   * @param legalEntityId legal entity
   * @param status APPROVED or VOIDED
   * @param voided whether the package was voided
   * @param periodStart first day of the payroll month
   * @param currency package currency
   * @param version package version
   * @param approvalDigest frozen approval digest
   * @param calculationId frozen calculation
   * @param calculationVersion calculation version
   * @param calculationDigest calculation digest
   * @param payslipCount payslips
   * @param totals the four frozen header totals
   */
  public record PackageRow(
      UUID id,
      UUID legalEntityId,
      String status,
      boolean voided,
      LocalDate periodStart,
      String currency,
      int version,
      String approvalDigest,
      UUID calculationId,
      int calculationVersion,
      String calculationDigest,
      int payslipCount,
      AccountingJournalBuilder.Totals totals) {
    @Override
    public String toString() {
      return "AccountingPackageRow[" + id + "]";
    }
  }

  /**
   * A stored journal header with its derived source state.
   *
   * @param id journal
   * @param legalEntityId legal entity
   * @param packageId package
   * @param currency CDF or USD
   * @param version journal version
   * @param periodStart first day of the payroll month
   * @param legalEntityCode stored legal entity code
   * @param packageVersion package version
   * @param packageApprovalDigest package approval digest
   * @param calculationId calculation
   * @param calculationVersion calculation version
   * @param calculationDigest calculation digest
   * @param mappingId mapping
   * @param mappingVersion mapping version
   * @param mappingDigest mapping digest
   * @param mappingEffectiveFrom the mapping's effective date
   * @param payslipCount payslips
   * @param sourceEntryCount entries before aggregation
   * @param lineCount lines
   * @param totalDebitsMinor total debits
   * @param totalCreditsMinor total credits
   * @param csvSha256 SHA-256 of the CSV generated with the journal
   * @param csvBytes size of that CSV
   * @param journalDigest journal digest
   * @param schemaVersion schema version
   * @param generatedAt generation instant
   * @param sourceVoided whether the package was voided after generation (derived at read time)
   */
  public record JournalRow(
      UUID id,
      UUID legalEntityId,
      UUID packageId,
      String currency,
      int version,
      LocalDate periodStart,
      String legalEntityCode,
      int packageVersion,
      String packageApprovalDigest,
      UUID calculationId,
      int calculationVersion,
      String calculationDigest,
      UUID mappingId,
      int mappingVersion,
      String mappingDigest,
      LocalDate mappingEffectiveFrom,
      int payslipCount,
      int sourceEntryCount,
      int lineCount,
      long totalDebitsMinor,
      long totalCreditsMinor,
      String csvSha256,
      int csvBytes,
      String journalDigest,
      int schemaVersion,
      Instant generatedAt,
      boolean sourceVoided) {
    @Override
    public String toString() {
      return "AccountingJournalRow[" + id + "]";
    }
  }

  /**
   * A journal header to insert; the four source totals are those of the package.
   *
   * @param id new journal
   * @param legalEntityId legal entity
   * @param packageId package
   * @param currency CDF or USD
   * @param version journal version
   * @param periodStart first day of the payroll month
   * @param legalEntityCode stored legal entity code
   * @param packageVersion package version
   * @param packageApprovalDigest package approval digest
   * @param calculationId calculation
   * @param calculationVersion calculation version
   * @param calculationDigest calculation digest
   * @param mappingId mapping
   * @param mappingVersion mapping version
   * @param mappingDigest mapping digest
   * @param payslipCount payslips
   * @param sourceEntryCount entries before aggregation
   * @param lineCount lines
   * @param totals the four frozen package totals
   * @param totalDebitsMinor total debits
   * @param totalCreditsMinor total credits
   * @param csvSha256 SHA-256 of the CSV
   * @param csvBytes size of the CSV
   * @param journalDigest journal digest
   * @param generatedAt generation instant
   * @param generatedBy verified administrator
   */
  public record NewJournal(
      UUID id,
      UUID legalEntityId,
      UUID packageId,
      String currency,
      int version,
      LocalDate periodStart,
      String legalEntityCode,
      int packageVersion,
      String packageApprovalDigest,
      UUID calculationId,
      int calculationVersion,
      String calculationDigest,
      UUID mappingId,
      int mappingVersion,
      String mappingDigest,
      int payslipCount,
      int sourceEntryCount,
      int lineCount,
      AccountingJournalBuilder.Totals totals,
      long totalDebitsMinor,
      long totalCreditsMinor,
      String csvSha256,
      int csvBytes,
      String journalDigest,
      Instant generatedAt,
      String generatedBy) {
    @Override
    public String toString() {
      return "NewAccountingJournal[" + id + "]";
    }
  }

  // -------------------------------------------------------------------------------------------
  // Locks
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the lock of a mapping chain (the key the V50 trigger takes); call it first.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param currency chain currency
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockMappingChain(TenantId tenant, UUID legalEntityId, String currency) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.accounting-mapping:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:entity AS uuid) AS text) || ':' || CAST(:currency AS text), 0))")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("currency", currency)
        .query()
        .singleRow();
  }

  /**
   * Takes the lock of a package's journal (the key the V50 trigger takes).
   *
   * @param tenant verified tenant
   * @param packageId package
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockJournal(TenantId tenant, UUID packageId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.accounting-journal:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:package AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("package", packageId)
        .query()
        .singleRow();
  }

  // -------------------------------------------------------------------------------------------
  // Mappings
  // -------------------------------------------------------------------------------------------

  /**
   * The mapping versions of a legal entity, newest effective date first, at most 20.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param currency one currency, or {@code null} for both
   * @return the versions
   */
  public List<MappingRow> mappings(TenantId tenant, UUID legalEntityId, String currency) {
    return jdbc.sql(
            "SELECT "
                + MAPPING_COLUMNS
                + " FROM payroll.accounting_mapping m WHERE m.tenant_id = :tenant"
                + " AND m.legal_entity_id = :entity"
                + " AND (CAST(:currency AS text) IS NULL OR m.currency = CAST(:currency AS text))"
                + " ORDER BY m.effective_from DESC, m.currency, m.version DESC LIMIT 20")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("currency", currency)
        .query(JdbcAccountingRepository::mapping)
        .list();
  }

  /**
   * The latest version of a chain.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param currency chain currency
   * @return the highest version, if any
   */
  public Optional<MappingRow> latestMapping(TenantId tenant, UUID legalEntityId, String currency) {
    return jdbc.sql(
            "SELECT "
                + MAPPING_COLUMNS
                + " FROM payroll.accounting_mapping m WHERE m.tenant_id = :tenant"
                + " AND m.legal_entity_id = :entity AND m.currency = :currency"
                + " ORDER BY m.version DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("currency", currency)
        .query(JdbcAccountingRepository::mapping)
        .optional();
  }

  /**
   * The mapping that applies to a package month: the version of the currency with the greatest
   * effective date on or before the first day of the month.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param currency package currency
   * @param periodStart first day of the payroll month
   * @return the applicable version, if any
   */
  public Optional<MappingRow> applicableMapping(
      TenantId tenant, UUID legalEntityId, String currency, LocalDate periodStart) {
    return jdbc.sql(
            "SELECT "
                + MAPPING_COLUMNS
                + " FROM payroll.accounting_mapping m WHERE m.tenant_id = :tenant"
                + " AND m.legal_entity_id = :entity AND m.currency = :currency"
                + " AND m.effective_from <= :period ORDER BY m.effective_from DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("currency", currency)
        .param("period", periodStart)
        .query(JdbcAccountingRepository::mapping)
        .optional();
  }

  /**
   * Appends one mapping version.
   *
   * @param tenant verified tenant
   * @param row the version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertMapping(TenantId tenant, NewMapping row) {
    jdbc.sql(
            "INSERT INTO payroll.accounting_mapping (tenant_id, legal_entity_id, id, currency,"
                + " version, effective_from, salary_expense, employee_deduction_liability,"
                + " employer_contribution_expense, employer_contribution_liability,"
                + " net_salary_payable, mapping_digest, created_at, created_by_subject)"
                + " VALUES (:tenant, :entity, :id, :currency, :version, :effective, :salary,"
                + " :deduction, :expense, :liability, :net, :digest, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", row.legalEntityId())
        .param("id", row.id())
        .param("currency", row.currency())
        .param("version", row.version())
        .param("effective", row.effectiveFrom())
        .param("salary", row.accounts().salaryExpense())
        .param("deduction", row.accounts().employeeDeductionLiability())
        .param("expense", row.accounts().employerContributionExpense())
        .param("liability", row.accounts().employerContributionLiability())
        .param("net", row.accounts().netSalaryPayable())
        .param("digest", row.mappingDigest())
        .param("at", Timestamp.from(row.createdAt()))
        .param("actor", row.createdBy())
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Package evidence
  // -------------------------------------------------------------------------------------------

  /**
   * The payroll package a journal depends on.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @return the package, if it exists in the entity
   */
  public Optional<PackageRow> payrollPackage(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT "
                + PACKAGE_COLUMNS
                + " FROM payroll.payroll_package p WHERE p.tenant_id = :tenant"
                + " AND p.legal_entity_id = :entity AND p.id = :package")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .query(JdbcAccountingRepository::packageRow)
        .optional();
  }

  /**
   * The frozen amounts of the payslips of a package: the four amount columns only, never an
   * employee identifier, number or snapshot. Bounded to {@code limit} rows.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param limit most rows
   * @return the amounts, in payslip order
   */
  public List<AccountingJournalBuilder.Row> payslipAmounts(
      TenantId tenant, UUID legalEntityId, UUID packageId, int limit) {
    return jdbc.sql(
            "SELECT s.gross_minor, s.employee_deductions_minor, s.employer_contributions_minor,"
                + " s.net_minor FROM payroll.payroll_payslip s WHERE s.tenant_id = :tenant"
                + " AND s.legal_entity_id = :entity AND s.package_id = :package"
                + " ORDER BY s.id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .param("limit", limit)
        .query(JdbcAccountingRepository::amounts)
        .list();
  }

  // -------------------------------------------------------------------------------------------
  // Journals
  // -------------------------------------------------------------------------------------------

  /**
   * The journals of a package (at most one in this story), newest version first.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @return the journals
   */
  public List<JournalRow> journalsOfPackage(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT "
                + JOURNAL_COLUMNS
                + JOURNAL_FROM
                + " WHERE j.tenant_id = :tenant AND j.legal_entity_id = :entity"
                + " AND j.package_id = :package ORDER BY j.version DESC LIMIT 25")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .query(JdbcAccountingRepository::journal)
        .list();
  }

  /**
   * One journal.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param journalId journal
   * @return the journal, if it exists in the entity
   */
  public Optional<JournalRow> journal(TenantId tenant, UUID legalEntityId, UUID journalId) {
    return jdbc.sql(
            "SELECT "
                + JOURNAL_COLUMNS
                + JOURNAL_FROM
                + " WHERE j.tenant_id = :tenant AND j.legal_entity_id = :entity"
                + " AND j.id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", journalId)
        .query(JdbcAccountingRepository::journal)
        .optional();
  }

  /**
   * Whether a package already has a journal (any version).
   *
   * @param tenant verified tenant
   * @param packageId package
   * @return {@code true} when one exists
   */
  public boolean journalExists(TenantId tenant, UUID packageId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM payroll.accounting_journal j"
                + " WHERE j.tenant_id = :tenant AND j.package_id = :package)")
        .param("tenant", tenant.value())
        .param("package", packageId)
        .query(Boolean.class)
        .single();
  }

  /**
   * The lines of a journal in journal order.
   *
   * @param tenant verified tenant
   * @param journalId journal
   * @return at most 2,000 lines
   */
  public List<AccountingLine> lines(TenantId tenant, UUID journalId) {
    return jdbc.sql(
            "SELECT l.line_number, l.account_code, l.direction, l.amount_minor,"
                + " l.cost_center_code, l.site_code FROM payroll.accounting_journal_line l"
                + " WHERE l.tenant_id = :tenant AND l.journal_id = :journal"
                + " ORDER BY l.line_number LIMIT 2000")
        .param("tenant", tenant.value())
        .param("journal", journalId)
        .query(JdbcAccountingRepository::line)
        .list();
  }

  /**
   * Inserts a journal header.
   *
   * @param tenant verified tenant
   * @param row the header
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertJournal(TenantId tenant, NewJournal row) {
    jdbc.sql(
            "INSERT INTO payroll.accounting_journal (tenant_id, legal_entity_id, id, package_id,"
                + " currency, version, period_start, legal_entity_code, package_version,"
                + " package_approval_digest, calculation_id, calculation_version,"
                + " calculation_digest, mapping_id, mapping_version, mapping_digest,"
                + " payslip_count, source_entry_count, line_count, gross_total_minor,"
                + " employee_deductions_total_minor, employer_contributions_total_minor,"
                + " net_total_minor, total_debits_minor, total_credits_minor, csv_sha256,"
                + " csv_bytes, journal_digest, schema_version, generated_at,"
                + " generated_by_subject) VALUES (:tenant, :entity, :id, :package, :currency,"
                + " :version, :period, :code, :packageVersion, :approval, :calculation,"
                + " :calculationVersion, :calculationDigest, :mapping, :mappingVersion,"
                + " :mappingDigest, :payslips, :entries, :lines, :gross, :deductions,"
                + " :contributions, :net, :debits, :credits, :csv, :csvBytes, :digest, 1, :at,"
                + " :actor)")
        .param("tenant", tenant.value())
        .param("entity", row.legalEntityId())
        .param("id", row.id())
        .param("package", row.packageId())
        .param("currency", row.currency())
        .param("version", row.version())
        .param("period", row.periodStart())
        .param("code", row.legalEntityCode())
        .param("packageVersion", row.packageVersion())
        .param("approval", row.packageApprovalDigest())
        .param("calculation", row.calculationId())
        .param("calculationVersion", row.calculationVersion())
        .param("calculationDigest", row.calculationDigest())
        .param("mapping", row.mappingId())
        .param("mappingVersion", row.mappingVersion())
        .param("mappingDigest", row.mappingDigest())
        .param("payslips", row.payslipCount())
        .param("entries", row.sourceEntryCount())
        .param("lines", row.lineCount())
        .param("gross", row.totals().grossMinor())
        .param("deductions", row.totals().employeeDeductionsMinor())
        .param("contributions", row.totals().employerContributionsMinor())
        .param("net", row.totals().netMinor())
        .param("debits", row.totalDebitsMinor())
        .param("credits", row.totalCreditsMinor())
        .param("csv", row.csvSha256())
        .param("csvBytes", row.csvBytes())
        .param("digest", row.journalDigest())
        .param("at", Timestamp.from(row.generatedAt()))
        .param("actor", row.generatedBy())
        .update();
  }

  /**
   * Inserts the lines of a journal in one statement batch.
   *
   * @param tenant verified tenant
   * @param journalId journal
   * @param lines the lines in journal order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertLines(TenantId tenant, UUID journalId, List<AccountingLine> lines) {
    List<SqlParameterSource> params = new ArrayList<>();
    for (AccountingLine line : lines) {
      params.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("journal", journalId)
              .addValue("number", line.lineNumber())
              .addValue("account", line.accountCode())
              .addValue("direction", line.direction().name())
              .addValue("amount", line.amountMinor())
              .addValue("center", line.costCenterCode())
              .addValue("site", line.siteCode()));
    }
    batch.batchUpdate(
        "INSERT INTO payroll.accounting_journal_line (tenant_id, journal_id, line_number,"
            + " account_code, direction, amount_minor, cost_center_code, site_code)"
            + " VALUES (:tenant, :journal, :number, :account, :direction, :amount, :center,"
            + " :site)",
        params.toArray(SqlParameterSource[]::new));
  }

  /**
   * Runs the deferred V50 exactness checks now, inside the caller's transaction, so a violation
   * surfaces where it is mapped by constraint name rather than at commit. Call it last.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.accounting_journal_exact,"
                + " payroll.accounting_journal_line_exact IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Row mappers
  // -------------------------------------------------------------------------------------------

  private static MappingRow mapping(ResultSet rs, int rowNumber) throws SQLException {
    return new MappingRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("currency"),
        rs.getInt("version"),
        rs.getObject("effective_from", LocalDate.class),
        new AccountingAccounts(
            rs.getString("salary_expense"),
            rs.getString("employee_deduction_liability"),
            rs.getString("employer_contribution_expense"),
            rs.getString("employer_contribution_liability"),
            rs.getString("net_salary_payable")),
        rs.getString("mapping_digest"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static PackageRow packageRow(ResultSet rs, int rowNumber) throws SQLException {
    return new PackageRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getString("status"),
        rs.getTimestamp("voided_at") != null,
        rs.getObject("period_start", LocalDate.class),
        rs.getString("currency"),
        rs.getInt("version"),
        rs.getString("approval_digest"),
        rs.getObject("calculation_id", UUID.class),
        rs.getInt("calculation_version"),
        rs.getString("calculation_digest"),
        rs.getInt("payslip_count"),
        new AccountingJournalBuilder.Totals(
            rs.getLong("gross_total_minor"),
            rs.getLong("employee_deductions_total_minor"),
            rs.getLong("employer_contributions_total_minor"),
            rs.getLong("net_total_minor")));
  }

  private static AccountingJournalBuilder.Row amounts(ResultSet rs, int rowNumber)
      throws SQLException {
    return new AccountingJournalBuilder.Row(
        rs.getLong("gross_minor"),
        rs.getLong("employee_deductions_minor"),
        rs.getLong("employer_contributions_minor"),
        rs.getLong("net_minor"),
        null,
        null);
  }

  private static JournalRow journal(ResultSet rs, int rowNumber) throws SQLException {
    return new JournalRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("package_id", UUID.class),
        rs.getString("currency"),
        rs.getInt("version"),
        rs.getObject("period_start", LocalDate.class),
        rs.getString("legal_entity_code"),
        rs.getInt("package_version"),
        rs.getString("package_approval_digest"),
        rs.getObject("calculation_id", UUID.class),
        rs.getInt("calculation_version"),
        rs.getString("calculation_digest"),
        rs.getObject("mapping_id", UUID.class),
        rs.getInt("mapping_version"),
        rs.getString("mapping_digest"),
        rs.getObject("mapping_effective_from", LocalDate.class),
        rs.getInt("payslip_count"),
        rs.getInt("source_entry_count"),
        rs.getInt("line_count"),
        rs.getLong("total_debits_minor"),
        rs.getLong("total_credits_minor"),
        rs.getString("csv_sha256"),
        rs.getInt("csv_bytes"),
        rs.getString("journal_digest"),
        rs.getInt("schema_version"),
        rs.getTimestamp("generated_at").toInstant(),
        rs.getBoolean("source_voided"));
  }

  private static AccountingLine line(ResultSet rs, int rowNumber) throws SQLException {
    return new AccountingLine(
        rs.getInt("line_number"),
        rs.getString("account_code"),
        AccountingDirection.valueOf(rs.getString("direction")),
        rs.getLong("amount_minor"),
        rs.getString("cost_center_code"),
        rs.getString("site_code"));
  }
}
