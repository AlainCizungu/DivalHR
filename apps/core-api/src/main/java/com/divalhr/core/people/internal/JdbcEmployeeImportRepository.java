package com.divalhr.core.people.internal;

import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.ImportRecord;
import com.divalhr.core.people.domain.ImportRow;
import com.divalhr.core.people.domain.ImportStatus;
import com.divalhr.core.people.domain.RowError;
import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowStatus;
import com.divalhr.core.people.domain.RowValues;
import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee import persistence (MVP-020). Every tenant operation takes the verified {@link TenantId}
 * and filters on it; the two background job operations are marked {@link CrossTenantAccess} and
 * return no personal data. Staged values exist only for valid rows of open imports and are erased
 * when the import closes.
 */
@Repository
public class JdbcEmployeeImportRepository {

  private static final String IMPORT_COLUMNS =
      "id, tenant_id, status, created_at, created_by, expires_at, file_sha256, preview_digest,"
          + " delimiter, header_language, total_rows, valid_rows, invalid_rows, created_count,"
          + " closed_at";

  private static final String ROW_COLUMNS =
      "row_number, status, error_columns, error_codes, employee_number, given_names, family_name,"
          + " start_date, legal_entity_code, site_code, department_code, cost_center_code,"
          + " team_code";

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   * @param batch batch template
   */
  public JdbcEmployeeImportRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /**
   * Serializes the tenant's imports for the rest of the transaction (open-import cap).
   *
   * @param tenant verified tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockTenant(TenantId tenant) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('people.employee_import:' || :t, 0))")
        .param("t", tenant.toString())
        .query((rs, row) -> 1)
        .single();
  }

  /**
   * Counts the tenant's open imports.
   *
   * @param tenant verified tenant
   * @param now current time
   * @return open imports
   */
  public int countOpen(TenantId tenant, Instant now) {
    return jdbc.sql(
            "SELECT count(*) FROM people.employee_import WHERE tenant_id = :tenant"
                + " AND status = 'VALIDATED' AND expires_at > :now")
        .param("tenant", tenant.value())
        .param("now", Timestamp.from(now))
        .query(Integer.class)
        .single();
  }

  /**
   * Inserts an import and its rows.
   *
   * @param tenant verified tenant
   * @param record the import
   * @param rows its rows in row order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, ImportRecord record, List<ImportRow> rows) {
    jdbc.sql(
            "INSERT INTO people.employee_import ("
                + IMPORT_COLUMNS
                + ") VALUES (:id, :tenant,"
                + " :status, :createdAt, :createdBy, :expiresAt, :fileSha256, :previewDigest,"
                + " :delimiter, :headerLanguage, :total, :valid, :invalid, NULL, NULL)")
        .param("id", record.id())
        .param("tenant", tenant.value())
        .param("status", record.status().name())
        .param("createdAt", Timestamp.from(record.createdAt()))
        .param("createdBy", record.createdBy())
        .param("expiresAt", Timestamp.from(record.expiresAt()))
        .param("fileSha256", record.fileSha256())
        .param("previewDigest", record.previewDigest())
        .param("delimiter", record.delimiter())
        .param("headerLanguage", record.headerLanguage())
        .param("total", record.totalRows())
        .param("valid", record.validRows())
        .param("invalid", record.invalidRows())
        .update();
    MapSqlParameterSource[] params = new MapSqlParameterSource[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      ImportRow row = rows.get(i);
      RowValues v = row.values();
      params[i] =
          new MapSqlParameterSource()
              .addValue("tenant", tenant.value())
              .addValue("import", record.id())
              .addValue("row", row.rowNumber())
              .addValue("status", (row.valid() ? RowStatus.VALID : RowStatus.INVALID).name())
              .addValue(
                  "columns",
                  row.errors().stream().map(e -> e.column().key()).toArray(String[]::new))
              .addValue(
                  "codes", row.errors().stream().map(e -> e.code().name()).toArray(String[]::new))
              .addValue("number", v == null ? null : v.employeeNumber())
              .addValue("given", v == null ? null : v.givenNames())
              .addValue("family", v == null ? null : v.familyName())
              .addValue("start", v == null ? null : v.startDate())
              .addValue("le", v == null ? null : v.legalEntityCode())
              .addValue("site", v == null ? null : v.siteCode())
              .addValue("dept", v == null ? null : v.departmentCode())
              .addValue("cc", v == null ? null : v.costCenterCode())
              .addValue("team", v == null ? null : v.teamCode());
    }
    batch.batchUpdate(
        "INSERT INTO people.employee_import_row (tenant_id, import_id, "
            + ROW_COLUMNS
            + ")"
            + " VALUES (:tenant, :import, :row, :status, :columns, :codes, :number, :given,"
            + " :family, :start, :le, :site, :dept, :cc, :team)",
        params);
  }

  /**
   * Finds an import of the tenant.
   *
   * @param tenant verified tenant
   * @param id import ID
   * @return the import
   */
  public Optional<ImportRecord> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + IMPORT_COLUMNS
                + " FROM people.employee_import"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcEmployeeImportRepository::record)
        .optional();
  }

  /**
   * Locks an import of the tenant for the rest of the transaction.
   *
   * @param tenant verified tenant
   * @param id import ID
   * @return the import
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ImportRecord> lock(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT "
                + IMPORT_COLUMNS
                + " FROM people.employee_import"
                + " WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcEmployeeImportRepository::record)
        .optional();
  }

  /**
   * Error code counts of an import.
   *
   * @param tenant verified tenant
   * @param id import ID
   * @return count per code, in code order
   */
  public Map<RowErrorCode, Integer> errorCounts(TenantId tenant, UUID id) {
    Map<RowErrorCode, Integer> counts = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT code, count(*) AS n FROM people.employee_import_row r, unnest(r.error_codes)"
                + " AS code WHERE r.tenant_id = :tenant AND r.import_id = :id GROUP BY code"
                + " ORDER BY code")
        .param("tenant", tenant.value())
        .param("id", id)
        .query((rs, row) -> counts.put(RowErrorCode.valueOf(rs.getString("code")), rs.getInt("n")))
        .list();
    return counts;
  }

  /** Which rows a preview page shows. */
  public enum RowFilter {
    /** Every row. */
    ALL,
    /** Rows without errors. */
    VALID,
    /** Rows with errors. */
    INVALID
  }

  /**
   * A stored row: its outcome, errors and, for a valid row of an open import, its values.
   *
   * @param rowNumber row number
   * @param status status
   * @param errors errors in column order
   * @param values staged values of a valid row of an open import, or {@code null}
   */
  public record StoredRow(
      int rowNumber, RowStatus status, List<RowError> errors, RowValues values) {

    /** Copies the errors. */
    public StoredRow {
      errors = List.copyOf(errors);
    }
  }

  /**
   * A page of rows after a row number.
   *
   * @param tenant verified tenant
   * @param id import ID
   * @param filter row filter
   * @param afterRow last row number of the previous page, or 0
   * @param limit page size plus one
   * @return rows in row order
   */
  public List<StoredRow> rows(TenantId tenant, UUID id, RowFilter filter, int afterRow, int limit) {
    String condition =
        switch (filter) {
          case ALL -> "";
          case VALID -> " AND cardinality(error_codes) = 0";
          case INVALID -> " AND cardinality(error_codes) > 0";
        };
    return jdbc.sql(
            "SELECT "
                + ROW_COLUMNS
                + " FROM people.employee_import_row WHERE tenant_id = :tenant"
                + " AND import_id = :id AND row_number > :after"
                + condition
                + " ORDER BY row_number LIMIT :limit")
        .param("tenant", tenant.value())
        .param("id", id)
        .param("after", afterRow)
        .param("limit", limit)
        .query(JdbcEmployeeImportRepository::storedRow)
        .list();
  }

  /**
   * The staged valid rows of an import, in employee-number order (the commit's lock order).
   *
   * @param tenant verified tenant
   * @param id import ID
   * @return valid rows with their values
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ImportRow> validRows(TenantId tenant, UUID id) {
    return jdbc
        .sql(
            "SELECT "
                + ROW_COLUMNS
                + " FROM people.employee_import_row WHERE tenant_id = :tenant"
                + " AND import_id = :id AND status = 'VALID' ORDER BY employee_number")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcEmployeeImportRepository::storedRow)
        .list()
        .stream()
        .map(stored -> new ImportRow(stored.rowNumber(), List.of(), stored.values()))
        .toList();
  }

  /**
   * Marks a committed import: valid rows become CREATED and invalid rows NOT_IMPORTED, and every
   * staged value is erased (no link to the created employees is kept, A20-2).
   *
   * @param tenant verified tenant
   * @param id import ID
   * @param createdCount employees created
   * @param committedBy verified subject
   * @param now commit time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void markCommitted(
      TenantId tenant, UUID id, int createdCount, String committedBy, Instant now) {
    closeRows(tenant, id, RowStatus.CREATED);
    jdbc.sql(
            "UPDATE people.employee_import SET status = 'COMMITTED', created_count = :count,"
                + " committed_at = :now, committed_by = :by, closed_at = :now"
                + " WHERE tenant_id = :tenant AND id = :id AND status = 'VALIDATED'")
        .param("count", createdCount)
        .param("now", Timestamp.from(now))
        .param("by", committedBy)
        .param("tenant", tenant.value())
        .param("id", id)
        .update();
  }

  /**
   * Closes an open import without creating anything (discard): every row becomes NOT_IMPORTED and
   * every staged value is erased.
   *
   * @param tenant verified tenant
   * @param id import ID
   * @param status {@code DISCARDED} or {@code EXPIRED}
   * @param now closing time
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void markClosed(TenantId tenant, UUID id, ImportStatus status, Instant now) {
    if (status != ImportStatus.DISCARDED && status != ImportStatus.EXPIRED) {
      throw new IllegalArgumentException("closing status");
    }
    closeRows(tenant, id, RowStatus.NOT_IMPORTED);
    jdbc.sql(
            "UPDATE people.employee_import SET status = :status, closed_at = :now"
                + " WHERE tenant_id = :tenant AND id = :id AND status = 'VALIDATED'")
        .param("status", status.name())
        .param("now", Timestamp.from(now))
        .param("tenant", tenant.value())
        .param("id", id)
        .update();
  }

  private void closeRows(TenantId tenant, UUID id, RowStatus validBecomes) {
    jdbc.sql(
            "UPDATE people.employee_import_row SET status = CASE WHEN status = 'VALID'"
                + " THEN :valid ELSE 'NOT_IMPORTED' END, employee_number = NULL,"
                + " given_names = NULL, family_name = NULL, start_date = NULL,"
                + " legal_entity_code = NULL, site_code = NULL, department_code = NULL,"
                + " cost_center_code = NULL, team_code = NULL"
                + " WHERE tenant_id = :tenant AND import_id = :id")
        .param("valid", validBecomes.name())
        .param("tenant", tenant.value())
        .param("id", id)
        .update();
  }

  /**
   * Claims open imports past their expiry, across tenants (expiry job).
   *
   * @param now current time
   * @param limit batch size
   * @return tenant and import IDs, locked for the rest of the transaction
   */
  @CrossTenantAccess("expiry job: claims due imports across tenants; returns IDs only")
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Map.Entry<TenantId, UUID>> claimExpired(Instant now, int limit) {
    return jdbc.sql(
            "SELECT tenant_id, id FROM people.employee_import WHERE status = 'VALIDATED'"
                + " AND expires_at <= :now ORDER BY expires_at LIMIT :limit"
                + " FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(
            (rs, row) ->
                Map.entry(
                    new TenantId(rs.getObject("tenant_id", UUID.class)),
                    rs.getObject("id", UUID.class)))
        .list();
  }

  /**
   * Deletes closed imports (and their rows) whose details have reached the retention period, across
   * tenants (retention job). Employees, audit and outbox rows are never touched.
   *
   * @param closedBefore closing-time boundary
   * @param limit batch size
   * @return imports deleted
   */
  @CrossTenantAccess("retention job: deletes expired import details across tenants")
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteClosedBefore(Instant closedBefore, int limit) {
    return jdbc.sql(
            "DELETE FROM people.employee_import WHERE id IN (SELECT id FROM people.employee_import"
                + " WHERE closed_at IS NOT NULL AND closed_at < :before ORDER BY closed_at"
                + " LIMIT :limit FOR UPDATE SKIP LOCKED)")
        .param("before", Timestamp.from(closedBefore))
        .param("limit", limit)
        .update();
  }

  private static ImportRecord record(ResultSet rs, int row) throws SQLException {
    Timestamp closed = rs.getTimestamp("closed_at");
    return new ImportRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        ImportStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("created_by"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getString("file_sha256"),
        rs.getString("preview_digest"),
        rs.getString("delimiter"),
        rs.getString("header_language"),
        rs.getInt("total_rows"),
        rs.getInt("valid_rows"),
        rs.getInt("invalid_rows"),
        (Integer) rs.getObject("created_count"),
        closed == null ? null : closed.toInstant());
  }

  private static StoredRow storedRow(ResultSet rs, int ignored) throws SQLException {
    int rowNumber = rs.getInt("row_number");
    RowStatus status = RowStatus.valueOf(rs.getString("status"));
    String[] columns = strings(rs.getArray("error_columns"));
    String[] codes = strings(rs.getArray("error_codes"));
    List<RowError> errors = new ArrayList<>();
    for (int i = 0; i < codes.length; i++) {
      errors.add(new RowError(column(columns[i]), RowErrorCode.valueOf(codes[i])));
    }
    RowValues values = null;
    if (status == RowStatus.VALID) {
      values =
          new RowValues(
              rs.getString("employee_number"),
              rs.getString("given_names"),
              rs.getString("family_name"),
              rs.getObject("start_date", LocalDate.class),
              rs.getString("legal_entity_code"),
              rs.getString("site_code"),
              rs.getString("department_code"),
              rs.getString("cost_center_code"),
              rs.getString("team_code"));
    }
    return new StoredRow(rowNumber, status, errors, values);
  }

  private static String[] strings(Array array) throws SQLException {
    return array == null ? new String[0] : (String[]) array.getArray();
  }

  private static ImportColumn column(String key) {
    for (ImportColumn column : ImportColumn.values()) {
      if (column.key().equals(key)) {
        return column;
      }
    }
    throw new IllegalStateException("unknown column key");
  }
}
