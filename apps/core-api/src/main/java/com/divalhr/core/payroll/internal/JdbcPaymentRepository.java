package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * Storage of salary payment destinations and payment batches (MVP-095A; V47, ADR 0036). Every
 * method takes the verified {@link TenantId} and every statement filters on it; the employee
 * predicate is part of every employee statement. Rows are only ever inserted; the one update is
 * PREPARED to CANCELLED, after its cancellation evidence (the V47 triggers enforce it). Restricted
 * financial and HR data: the sealed envelope is carried as hex text, no record prints a value, and
 * no statement selects a sensitive value in the clear (none is stored in the clear).
 */
@Repository
public class JdbcPaymentRepository {

  private static final String DEST_COLUMNS =
      "d.id, d.employee_id, d.sequence, d.effective_from, d.method, d.country_code,"
          + " d.provider_code, d.provider_name, d.masked_value, d.fingerprint,"
          + " encode(d.sealed, 'hex') AS sealed, d.key_version, d.reason_code, d.recorded_at";

  private static final String BATCH_COLUMNS =
      "b.id, b.legal_entity_id, b.package_id, b.version, b.status, b.period_start, b.currency,"
          + " b.package_version, b.package_digest, b.line_count, b.zero_net_excluded_count,"
          + " b.bank_count, b.mobile_count, b.manual_count, b.total_net_minor,"
          + " b.destinations_digest, b.batch_digest, b.prepared_at, b.cancelled_at,"
          + " c.reason_code AS cancel_reason";

  private static final String BATCH_FROM =
      " FROM payroll.payment_batch b LEFT JOIN payroll.payment_batch_cancel c"
          + " ON c.tenant_id = b.tenant_id AND c.batch_id = b.id";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcPaymentRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  // -------------------------------------------------------------------------------------------
  // Rows
  // -------------------------------------------------------------------------------------------

  /**
   * A stored destination version. The sealed envelope is carried as hex text; nothing sensitive is
   * in the clear.
   *
   * @param id destination row
   * @param employeeId employee
   * @param sequence version
   * @param effectiveFrom first day
   * @param method method code
   * @param country ISO country, or null
   * @param providerCode provider code, or null
   * @param providerName provider name, or null
   * @param masked masked value, or null
   * @param fingerprint keyed fingerprint, or null
   * @param sealedHex the sealed envelope as hex, or null
   * @param keyVersion key version, or null
   * @param reasonCode closed reason
   * @param recordedAt instant
   */
  public record DestinationRow(
      UUID id,
      UUID employeeId,
      int sequence,
      LocalDate effectiveFrom,
      String method,
      String country,
      String providerCode,
      String providerName,
      String masked,
      String fingerprint,
      String sealedHex,
      Integer keyVersion,
      String reasonCode,
      Instant recordedAt) {

    /**
     * @return the sealed envelope bytes, or null
     */
    public byte[] sealed() {
      return sealedHex == null ? null : HexFormat.of().parseHex(sealedHex);
    }

    @Override
    public String toString() {
      return "PaymentDestinationRow[redacted]";
    }
  }

  /**
   * A destination to append.
   *
   * @param id new row
   * @param employeeId employee
   * @param sequence next version
   * @param effectiveFrom first day
   * @param method method code
   * @param country ISO country, or null
   * @param providerCode provider code, or null
   * @param providerName provider name, or null
   * @param masked masked value, or null
   * @param fingerprint keyed fingerprint, or null
   * @param sealedHex sealed envelope as hex, or null
   * @param keyVersion key version, or null
   * @param reasonCode closed reason
   * @param recordedAt instant
   * @param recordedBy verified subject
   */
  public record NewDestination(
      UUID id,
      UUID employeeId,
      int sequence,
      LocalDate effectiveFrom,
      String method,
      String country,
      String providerCode,
      String providerName,
      String masked,
      String fingerprint,
      String sealedHex,
      Integer keyVersion,
      String reasonCode,
      Instant recordedAt,
      String recordedBy) {

    @Override
    public String toString() {
      return "NewPaymentDestination[redacted]";
    }
  }

  /**
   * A payslip as the batch reads it.
   *
   * @param id payslip
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param grossMinor gross
   * @param deductionsMinor employee deductions
   * @param netMinor net
   * @param excluded whether excluded
   */
  public record SlipFacts(
      UUID id,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      long deductionsMinor,
      long netMinor,
      boolean excluded) {

    @Override
    public String toString() {
      return "PaymentSlipFacts[" + id + "]";
    }
  }

  /**
   * A stored batch.
   *
   * @param id batch
   * @param legalEntityId legal entity
   * @param packageId package
   * @param version chain version
   * @param status PREPARED or CANCELLED
   * @param periodStart first day of the month
   * @param currency currency
   * @param packageVersion package version
   * @param packageDigest package approval digest
   * @param lineCount lines
   * @param zeroNetExcluded zero-net payslips left out
   * @param bank BANK_ACCOUNT lines
   * @param mobile MOBILE_MONEY lines
   * @param manual MANUAL lines
   * @param totalNet total in minor units
   * @param destinationsDigest destinations digest
   * @param batchDigest batch digest
   * @param preparedAt instant
   * @param cancelledAt instant, or null
   * @param cancelReason closed cancellation reason, or null
   */
  public record BatchRow(
      UUID id,
      UUID legalEntityId,
      UUID packageId,
      int version,
      String status,
      LocalDate periodStart,
      String currency,
      int packageVersion,
      String packageDigest,
      int lineCount,
      int zeroNetExcluded,
      int bank,
      int mobile,
      int manual,
      long totalNet,
      String destinationsDigest,
      String batchDigest,
      Instant preparedAt,
      Instant cancelledAt,
      String cancelReason) {

    @Override
    public String toString() {
      return "PaymentBatchRow[" + id + "]";
    }
  }

  /**
   * A batch to insert.
   *
   * @param id new batch
   * @param legalEntityId legal entity
   * @param packageId package
   * @param version next chain version
   * @param periodStart month
   * @param currency currency
   * @param packageVersion package version
   * @param packageDigest package approval digest
   * @param lineCount lines
   * @param zeroNetExcluded zero-net payslips left out
   * @param bank BANK_ACCOUNT lines
   * @param mobile MOBILE_MONEY lines
   * @param manual MANUAL lines
   * @param totalNet total in minor units
   * @param destinationsDigest destinations digest
   * @param batchDigest batch digest
   * @param preparedAt instant
   * @param preparedBy verified subject
   */
  public record NewBatch(
      UUID id,
      UUID legalEntityId,
      UUID packageId,
      int version,
      LocalDate periodStart,
      String currency,
      int packageVersion,
      String packageDigest,
      int lineCount,
      int zeroNetExcluded,
      int bank,
      int mobile,
      int manual,
      long totalNet,
      String destinationsDigest,
      String batchDigest,
      Instant preparedAt,
      String preparedBy) {

    @Override
    public String toString() {
      return "NewPaymentBatch[" + id + "]";
    }
  }

  /**
   * A stored line.
   *
   * @param id line
   * @param payslipId payslip
   * @param employeeId employee
   * @param employeeNumber employee number
   * @param method method code
   * @param country ISO country, or null
   * @param providerCode provider code, or null
   * @param providerName provider name, or null
   * @param masked masked destination, or null
   * @param destinationId destination row, or null
   * @param destinationSequence destination version, or null
   * @param net net in minor units
   */
  public record LineRow(
      UUID id,
      UUID payslipId,
      UUID employeeId,
      String employeeNumber,
      String method,
      String country,
      String providerCode,
      String providerName,
      String masked,
      UUID destinationId,
      Integer destinationSequence,
      long net) {

    @Override
    public String toString() {
      return "PaymentBatchLineRow[" + id + "]";
    }
  }

  /**
   * Cancellation evidence to insert.
   *
   * @param id new row
   * @param reasonCode closed reason
   * @param batchDigest the batch digest
   * @param cancelledAt instant
   * @param cancelledBy verified subject
   */
  public record NewCancel(
      UUID id, String reasonCode, String batchDigest, Instant cancelledAt, String cancelledBy) {}

  /**
   * Keyset position in a batch list (newest first).
   *
   * @param preparedAt instant
   * @param id batch
   */
  public record BatchPosition(Instant preparedAt, UUID id) {}

  // -------------------------------------------------------------------------------------------
  // Locks and limits
  // -------------------------------------------------------------------------------------------

  /**
   * Takes the tenant's payment-destination lock: exclusive for batch preparation, shared for every
   * destination command.
   *
   * @param tenant verified tenant
   * @param shared whether the lock is shared
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockPayments(TenantId tenant, boolean shared) {
    String function = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
    jdbc.sql(
            "SELECT "
                + function
                + "(hashtextextended('payroll.payment-destinations:'"
                + " || CAST(CAST(:tenant AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .query()
        .singleRow();
  }

  /**
   * Takes the employee's destination lock (the key the V47 trigger takes).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockEmployee(TenantId tenant, UUID employeeId) {
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtextextended('payroll.payment-destination:'"
                + " || CAST(CAST(:tenant AS uuid) AS text) || ':'"
                + " || CAST(CAST(:employee AS uuid) AS text), 0))")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query()
        .singleRow();
  }

  /**
   * Runs the deferred V47 checks now (inside the caller's transaction), so a violation surfaces
   * inside the business work, where it is mapped by constraint name, rather than at commit.
   *
   * @param tenant verified tenant whose transaction it is
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void checkDeferred(TenantId tenant) {
    Objects.requireNonNull(tenant, "tenant");
    jdbc.sql(
            "SET CONSTRAINTS payroll.payment_batch_exact, payroll.payment_batch_line_exact"
                + " IMMEDIATE")
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Destinations
  // -------------------------------------------------------------------------------------------

  private static DestinationRow destination(ResultSet rs, int row) throws SQLException {
    int keyVersion = rs.getInt("key_version");
    Integer key = rs.wasNull() ? null : keyVersion;
    return new DestinationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getInt("sequence"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("method"),
        rs.getString("country_code"),
        rs.getString("provider_code"),
        rs.getString("provider_name"),
        rs.getString("masked_value"),
        rs.getString("fingerprint"),
        rs.getString("sealed"),
        key,
        rs.getString("reason_code"),
        rs.getTimestamp("recorded_at").toInstant());
  }

  /**
   * The latest recorded version of an employee, whatever its effective date.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @return the version, if any
   */
  public Optional<DestinationRow> latest(TenantId tenant, UUID employeeId) {
    return jdbc.sql(
            "SELECT "
                + DEST_COLUMNS
                + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                + " AND d.employee_id = :employee ORDER BY d.sequence DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .query(JdbcPaymentRepository::destination)
        .optional();
  }

  /**
   * One version by ID (cursor positions).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param id the destination version ID
   * @return the version, if it belongs to that employee in the tenant
   */
  public Optional<DestinationRow> byId(TenantId tenant, UUID employeeId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + DEST_COLUMNS
                + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                + " AND d.employee_id = :employee AND d.id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", id)
        .query(JdbcPaymentRepository::destination)
        .optional();
  }

  /**
   * The version in force on a date: the greatest effective date not after it.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param date the business date
   * @return the version, if any
   */
  public Optional<DestinationRow> effective(TenantId tenant, UUID employeeId, LocalDate date) {
    return jdbc.sql(
            "SELECT "
                + DEST_COLUMNS
                + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                + " AND d.employee_id = :employee AND d.effective_from <= :date"
                + " ORDER BY d.sequence DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("date", date)
        .query(JdbcPaymentRepository::destination)
        .optional();
  }

  /**
   * The first version that takes effect after a date (a scheduled destination).
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param date the business date
   * @return the version, if any
   */
  public Optional<DestinationRow> scheduled(TenantId tenant, UUID employeeId, LocalDate date) {
    return jdbc.sql(
            "SELECT "
                + DEST_COLUMNS
                + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                + " AND d.employee_id = :employee AND d.effective_from > :date"
                + " ORDER BY d.sequence DESC LIMIT 1")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("date", date)
        .query(JdbcPaymentRepository::destination)
        .optional();
  }

  /**
   * One version of an employee.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param sequence version
   * @return the version, if any
   */
  public Optional<DestinationRow> version(TenantId tenant, UUID employeeId, int sequence) {
    return jdbc.sql(
            "SELECT "
                + DEST_COLUMNS
                + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                + " AND d.employee_id = :employee AND d.sequence = :sequence")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("sequence", sequence)
        .query(JdbcPaymentRepository::destination)
        .optional();
  }

  /**
   * A page of an employee's history, newest version first.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param beforeSequence the last version of the previous page, or null for the first page
   * @param limit rows to read
   * @return the versions
   */
  public List<DestinationRow> history(
      TenantId tenant, UUID employeeId, Integer beforeSequence, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(DEST_COLUMNS)
            .append(
                " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                    + " AND d.employee_id = :employee");
    if (beforeSequence != null) {
      sql.append(" AND d.sequence < :before");
    }
    sql.append(" ORDER BY d.sequence DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenant.value())
            .param("employee", employeeId)
            .param("limit", limit);
    if (beforeSequence != null) {
      spec = spec.param("before", beforeSequence);
    }
    return spec.query(JdbcPaymentRepository::destination).list();
  }

  /**
   * The destination in force on a date for every employee of a package.
   *
   * @param tenant verified tenant
   * @param packageId package
   * @param date the business date
   * @return the versions by employee
   */
  public Map<UUID, DestinationRow> effectiveForPackage(
      TenantId tenant, UUID packageId, LocalDate date) {
    List<DestinationRow> rows =
        jdbc.sql(
                "SELECT DISTINCT ON (d.employee_id) "
                    + DEST_COLUMNS
                    + " FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                    + " AND d.effective_from <= :date AND d.employee_id IN"
                    + " (SELECT s.employee_id FROM payroll.payroll_payslip s"
                    + " WHERE s.tenant_id = :tenant AND s.package_id = :package)"
                    + " ORDER BY d.employee_id, d.sequence DESC")
            .param("tenant", tenant.value())
            .param("package", packageId)
            .param("date", date)
            .query(JdbcPaymentRepository::destination)
            .list();
    Map<UUID, DestinationRow> byEmployee = new HashMap<>();
    for (DestinationRow row : rows) {
      byEmployee.put(row.employeeId(), row);
    }
    return byEmployee;
  }

  /**
   * How many other employees currently have the same fingerprint as their latest destination.
   *
   * @param tenant verified tenant
   * @param fingerprint keyed fingerprint
   * @param exceptEmployee the employee being recorded
   * @return the count of other employees
   */
  public int otherHolders(TenantId tenant, String fingerprint, UUID exceptEmployee) {
    int count =
        jdbc.sql(
                "SELECT count(*) FROM payroll.payment_destination d WHERE d.tenant_id = :tenant"
                    + " AND d.fingerprint = :fingerprint AND d.employee_id <> :employee"
                    + " AND d.sequence = (SELECT max(l.sequence) FROM payroll.payment_destination l"
                    + " WHERE l.tenant_id = d.tenant_id AND l.employee_id = d.employee_id)")
            .param("tenant", tenant.value())
            .param("fingerprint", fingerprint)
            .param("employee", exceptEmployee)
            .query(Integer.class)
            .single();
    return count;
  }

  /**
   * Appends a destination version.
   *
   * @param tenant verified tenant
   * @param row the version
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertDestination(TenantId tenant, NewDestination row) {
    jdbc.sql(
            "INSERT INTO payroll.payment_destination (tenant_id, employee_id, id, sequence,"
                + " effective_from, method, country_code, provider_code, provider_name,"
                + " masked_value, fingerprint, sealed, key_version, status, reason_code,"
                + " recorded_at, recorded_by_subject) VALUES (:tenant, :employee, :id, :sequence,"
                + " :from, :method, :country, :provider, :providerName, :masked, :fingerprint,"
                + " decode(CAST(:sealed AS text), 'hex'), :keyVersion, 'RECORDED', :reason, :at,"
                + " :actor)")
        .param("tenant", tenant.value())
        .param("employee", row.employeeId())
        .param("id", row.id())
        .param("sequence", row.sequence())
        .param("from", row.effectiveFrom())
        .param("method", row.method())
        .param("country", row.country())
        .param("provider", row.providerCode())
        .param("providerName", row.providerName())
        .param("masked", row.masked())
        .param("fingerprint", row.fingerprint())
        .param("sealed", row.sealedHex())
        .param("keyVersion", row.keyVersion())
        .param("reason", row.reasonCode())
        .param("at", Timestamp.from(row.recordedAt()))
        .param("actor", row.recordedBy())
        .update();
  }

  // -------------------------------------------------------------------------------------------
  // Package facts for a batch
  // -------------------------------------------------------------------------------------------

  /**
   * The payslips of a package, by employee ID.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @return the payslips
   */
  public List<SlipFacts> slips(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT s.id, s.employee_id, s.employee_number, s.gross_minor,"
                + " s.employee_deductions_minor, s.net_minor, s.excluded"
                + " FROM payroll.payroll_payslip s WHERE s.tenant_id = :tenant"
                + " AND s.legal_entity_id = :entity AND s.package_id = :package"
                + " ORDER BY s.employee_id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .query(
            (rs, row) ->
                new SlipFacts(
                    rs.getObject("id", UUID.class),
                    rs.getObject("employee_id", UUID.class),
                    rs.getString("employee_number"),
                    rs.getLong("gross_minor"),
                    rs.getLong("employee_deductions_minor"),
                    rs.getLong("net_minor"),
                    rs.getBoolean("excluded")))
        .list();
  }

  /**
   * The highest package version of an entity, month and currency.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param periodStart month
   * @param currency currency
   * @return the version, 0 when none
   */
  public int latestPackageVersion(
      TenantId tenant, UUID legalEntityId, LocalDate periodStart, String currency) {
    int version =
        jdbc.sql(
                "SELECT coalesce(max(p.version), 0) FROM payroll.payroll_package p"
                    + " WHERE p.tenant_id = :tenant AND p.legal_entity_id = :entity"
                    + " AND p.period_start = :period AND p.currency = :currency")
            .param("tenant", tenant.value())
            .param("entity", legalEntityId)
            .param("period", periodStart)
            .param("currency", currency)
            .query(Integer.class)
            .single();
    return version;
  }

  /**
   * The highest batch version of a package.
   *
   * @param tenant verified tenant
   * @param packageId package
   * @return the version, 0 when none
   */
  public int chainVersion(TenantId tenant, UUID packageId) {
    int version =
        jdbc.sql(
                "SELECT coalesce(max(b.version), 0) FROM payroll.payment_batch b"
                    + " WHERE b.tenant_id = :tenant AND b.package_id = :package")
            .param("tenant", tenant.value())
            .param("package", packageId)
            .query(Integer.class)
            .single();
    return version;
  }

  /**
   * Whether a PREPARED batch exists for a package.
   *
   * @param tenant verified tenant
   * @param packageId package
   * @return true when one does
   */
  public boolean preparedExists(TenantId tenant, UUID packageId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM payroll.payment_batch b WHERE b.tenant_id = :tenant"
                + " AND b.package_id = :package AND b.status = 'PREPARED')")
        .param("tenant", tenant.value())
        .param("package", packageId)
        .query(Boolean.class)
        .single();
  }

  /**
   * The payroll payment guard's question: whether a PREPARED batch exists.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity of the package
   * @param packageId package
   * @return true when a PREPARED batch exists for it
   */
  public boolean preparedExists(TenantId tenant, UUID legalEntityId, UUID packageId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM payroll.payment_batch b WHERE b.tenant_id = :tenant"
                + " AND b.legal_entity_id = :entity AND b.package_id = :package"
                + " AND b.status = 'PREPARED')")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("package", packageId)
        .query(Boolean.class)
        .single();
  }

  // -------------------------------------------------------------------------------------------
  // Batches
  // -------------------------------------------------------------------------------------------

  private static BatchRow batchRow(ResultSet rs, int row) throws SQLException {
    Timestamp cancelled = rs.getTimestamp("cancelled_at");
    return new BatchRow(
        rs.getObject("id", UUID.class),
        rs.getObject("legal_entity_id", UUID.class),
        rs.getObject("package_id", UUID.class),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getObject("period_start", LocalDate.class),
        rs.getString("currency"),
        rs.getInt("package_version"),
        rs.getString("package_digest"),
        rs.getInt("line_count"),
        rs.getInt("zero_net_excluded_count"),
        rs.getInt("bank_count"),
        rs.getInt("mobile_count"),
        rs.getInt("manual_count"),
        rs.getLong("total_net_minor"),
        rs.getString("destinations_digest"),
        rs.getString("batch_digest"),
        rs.getTimestamp("prepared_at").toInstant(),
        cancelled == null ? null : cancelled.toInstant(),
        rs.getString("cancel_reason"));
  }

  private static LineRow lineRow(ResultSet rs, int row) throws SQLException {
    int sequence = rs.getInt("destination_sequence");
    Integer destinationSequence = rs.wasNull() ? null : sequence;
    return new LineRow(
        rs.getObject("id", UUID.class),
        rs.getObject("payslip_id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getString("employee_number"),
        rs.getString("method"),
        rs.getString("country_code"),
        rs.getString("provider_code"),
        rs.getString("provider_name"),
        rs.getString("masked_value"),
        rs.getObject("destination_id", UUID.class),
        destinationSequence,
        rs.getLong("net_minor"));
  }

  private static final String LINE_COLUMNS =
      "l.id, l.payslip_id, l.employee_id, l.employee_number, l.method, l.country_code,"
          + " l.provider_code, l.provider_name, l.masked_value, l.destination_id,"
          + " l.destination_sequence, l.net_minor";

  /**
   * Locks the batch row {@code FOR UPDATE} until the transaction ends.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @return whether the batch exists in that legal entity of the tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockBatch(TenantId tenant, UUID legalEntityId, UUID batchId) {
    return jdbc.sql(
            "SELECT id FROM payroll.payment_batch WHERE tenant_id = :tenant"
                + " AND legal_entity_id = :entity AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", batchId)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /**
   * One batch of a legal entity.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @return the batch, if it belongs to that legal entity in the tenant
   */
  public Optional<BatchRow> batch(TenantId tenant, UUID legalEntityId, UUID batchId) {
    return jdbc.sql(
            "SELECT "
                + BATCH_COLUMNS
                + BATCH_FROM
                + " WHERE b.tenant_id = :tenant AND b.legal_entity_id = :entity AND b.id = :id")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", batchId)
        .query(JdbcPaymentRepository::batchRow)
        .optional();
  }

  /**
   * A page of the batches of a legal entity, newest first.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId optional package filter, or null
   * @param status optional status filter, or null
   * @param after the last row of the previous page, or null for the first page
   * @param limit rows to read
   * @return the batches
   */
  public List<BatchRow> batchPage(
      TenantId tenant,
      UUID legalEntityId,
      UUID packageId,
      String status,
      BatchPosition after,
      int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(BATCH_COLUMNS)
            .append(BATCH_FROM)
            .append(" WHERE b.tenant_id = :tenant AND b.legal_entity_id = :entity");
    if (packageId != null) {
      sql.append(" AND b.package_id = :package");
    }
    if (status != null) {
      sql.append(" AND b.status = :status");
    }
    if (after != null) {
      sql.append(
          " AND (b.prepared_at < :preparedAt OR (b.prepared_at = :preparedAt AND b.id < :after))");
    }
    sql.append(" ORDER BY b.prepared_at DESC, b.id DESC LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenant.value())
            .param("entity", legalEntityId)
            .param("limit", limit);
    if (packageId != null) {
      spec = spec.param("package", packageId);
    }
    if (status != null) {
      spec = spec.param("status", status);
    }
    if (after != null) {
      spec =
          spec.param("preparedAt", Timestamp.from(after.preparedAt())).param("after", after.id());
    }
    return spec.query(JdbcPaymentRepository::batchRow).list();
  }

  /**
   * Inserts a batch (PREPARED).
   *
   * @param tenant verified tenant
   * @param row the batch
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertBatch(TenantId tenant, NewBatch row) {
    jdbc.sql(
            "INSERT INTO payroll.payment_batch (tenant_id, legal_entity_id, id, package_id,"
                + " version, status, period_start, currency, package_version, package_digest,"
                + " line_count, zero_net_excluded_count, bank_count, mobile_count, manual_count,"
                + " total_net_minor, destinations_digest, batch_digest, reviewed_totals,"
                + " understands_no_money, prepared_at, prepared_by_subject) VALUES (:tenant,"
                + " :entity, :id, :package, :version, 'PREPARED', :period, :currency,"
                + " :packageVersion, :packageDigest, :lines, :zero, :bank, :mobile, :manual,"
                + " :total, :destinations, :digest, true, true, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", row.legalEntityId())
        .param("id", row.id())
        .param("package", row.packageId())
        .param("version", row.version())
        .param("period", row.periodStart())
        .param("currency", row.currency())
        .param("packageVersion", row.packageVersion())
        .param("packageDigest", row.packageDigest())
        .param("lines", row.lineCount())
        .param("zero", row.zeroNetExcluded())
        .param("bank", row.bank())
        .param("mobile", row.mobile())
        .param("manual", row.manual())
        .param("total", row.totalNet())
        .param("destinations", row.destinationsDigest())
        .param("digest", row.batchDigest())
        .param("at", Timestamp.from(row.preparedAt()))
        .param("actor", row.preparedBy())
        .update();
  }

  /**
   * Inserts the lines of a batch in one statement batch.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param packageId package
   * @param batchId batch
   * @param lines the lines
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertLines(
      TenantId tenant, UUID legalEntityId, UUID packageId, UUID batchId, List<LineRow> lines) {
    List<SqlParameterSource> params = new ArrayList<>();
    for (LineRow line : lines) {
      params.add(
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("entity", legalEntityId)
              .addValue("batch", batchId)
              .addValue("package", packageId)
              .addValue("id", line.id())
              .addValue("payslip", line.payslipId())
              .addValue("employee", line.employeeId())
              .addValue("number", line.employeeNumber())
              .addValue("method", line.method())
              .addValue("country", line.country())
              .addValue("provider", line.providerCode())
              .addValue("providerName", line.providerName())
              .addValue("masked", line.masked())
              .addValue("destination", line.destinationId())
              .addValue("sequence", line.destinationSequence())
              .addValue("net", line.net()));
    }
    batch.batchUpdate(
        "INSERT INTO payroll.payment_batch_line (tenant_id, legal_entity_id, batch_id,"
            + " package_id, id, payslip_id, employee_id, employee_number, method, country_code,"
            + " provider_code, provider_name, masked_value, destination_id, destination_sequence,"
            + " net_minor) VALUES (:tenant, :entity, :batch, :package, :id, :payslip, :employee,"
            + " :number, :method, :country, :provider, :providerName, :masked, :destination,"
            + " :sequence, :net)",
        params.toArray(SqlParameterSource[]::new));
  }

  /**
   * A page of a batch's lines by employee ID.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param afterEmployee the employee of the previous page's last line, or null
   * @param limit rows to read
   * @return the lines
   */
  public List<LineRow> linePage(
      TenantId tenant, UUID legalEntityId, UUID batchId, UUID afterEmployee, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(LINE_COLUMNS)
            .append(
                " FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
                    + " AND l.legal_entity_id = :entity AND l.batch_id = :batch");
    if (afterEmployee != null) {
      sql.append(" AND l.employee_id > :after");
    }
    sql.append(" ORDER BY l.employee_id LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString())
            .param("tenant", tenant.value())
            .param("entity", legalEntityId)
            .param("batch", batchId)
            .param("limit", limit);
    if (afterEmployee != null) {
      spec = spec.param("after", afterEmployee);
    }
    return spec.query(JdbcPaymentRepository::lineRow).list();
  }

  /**
   * The line of one employee in a batch (the keyset anchor of a cursor).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param employeeId employee
   * @return the line, if any
   */
  public Optional<LineRow> lineOf(
      TenantId tenant, UUID legalEntityId, UUID batchId, UUID employeeId) {
    return jdbc.sql(
            "SELECT "
                + LINE_COLUMNS
                + " FROM payroll.payment_batch_line l WHERE l.tenant_id = :tenant"
                + " AND l.legal_entity_id = :entity AND l.batch_id = :batch"
                + " AND l.employee_id = :employee")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("batch", batchId)
        .param("employee", employeeId)
        .query(JdbcPaymentRepository::lineRow)
        .optional();
  }

  /**
   * Inserts the cancellation evidence (before the batch row is updated).
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param evidence the evidence
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertCancel(TenantId tenant, UUID legalEntityId, UUID batchId, NewCancel evidence) {
    jdbc.sql(
            "INSERT INTO payroll.payment_batch_cancel (tenant_id, legal_entity_id, batch_id, id,"
                + " reason_code, no_money_acknowledged, batch_digest, cancelled_at,"
                + " cancelled_by_subject) VALUES (:tenant, :entity, :batch, :id, :reason, true,"
                + " :digest, :at, :actor)")
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("batch", batchId)
        .param("id", evidence.id())
        .param("reason", evidence.reasonCode())
        .param("digest", evidence.batchDigest())
        .param("at", Timestamp.from(evidence.cancelledAt()))
        .param("actor", evidence.cancelledBy())
        .update();
  }

  /**
   * The one legal update: PREPARED to CANCELLED, with the instant of the cancellation evidence.
   *
   * @param tenant verified tenant
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param cancelledAt the instant
   * @return rows updated (1 when the batch was PREPARED)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int markCancelled(TenantId tenant, UUID legalEntityId, UUID batchId, Instant cancelledAt) {
    return jdbc.sql(
            "UPDATE payroll.payment_batch SET status = 'CANCELLED', cancelled_at = :at"
                + " WHERE tenant_id = :tenant AND legal_entity_id = :entity AND id = :id"
                + " AND status = 'PREPARED'")
        .param("at", Timestamp.from(cancelledAt))
        .param("tenant", tenant.value())
        .param("entity", legalEntityId)
        .param("id", batchId)
        .update();
  }
}
