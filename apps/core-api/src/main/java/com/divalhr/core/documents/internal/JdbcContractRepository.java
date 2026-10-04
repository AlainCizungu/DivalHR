package com.divalhr.core.documents.internal;

import com.divalhr.core.platform.tenancy.CrossTenantAccess;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issued contracts and their acknowledgement evidence (MVP-030), always bound to the verified
 * tenant and, for every read, to the employee. Snapshots are Restricted HR data: never logged. The
 * V16 guards keep a contract immutable apart from {@code ISSUED -> ACKNOWLEDGED|VOID}.
 */
@Repository
public class JdbcContractRepository {

  private static final String SUMMARY_COLUMNS =
      "id, employee_id, employment_id, template_id, template_version_id, contract_type, locale,"
          + " start_date, end_date, snapshot_sha256, state, issued_at, acknowledged_at,"
          + " void_reason, voided_at, version";

  private static final String FULL_COLUMNS = SUMMARY_COLUMNS + ", snapshot_canonical";

  private static final String ACK_COLUMNS =
      "contract_id, statement_code, statement_version, statement_locale, statement_sha256,"
          + " evidence_sha256, acknowledged_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcContractRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * An issued contract.
   *
   * @param id contract
   * @param employeeId employee
   * @param employmentId employment
   * @param templateId template
   * @param templateVersionId template version
   * @param contractType closed type code
   * @param locale language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param snapshotSha256 snapshot digest
   * @param state ISSUED, ACKNOWLEDGED or VOID
   * @param issuedAt issue time
   * @param acknowledgedAt acknowledgement time, or {@code null}
   * @param voidReason void reason, or {@code null}
   * @param voidedAt void time, or {@code null}
   * @param version row version
   * @param canonical canonical snapshot text, or {@code null} in summaries
   */
  public record ContractRow(
      UUID id,
      UUID employeeId,
      UUID employmentId,
      UUID templateId,
      UUID templateVersionId,
      String contractType,
      String locale,
      LocalDate startDate,
      LocalDate endDate,
      String snapshotSha256,
      String state,
      Instant issuedAt,
      Instant acknowledgedAt,
      String voidReason,
      Instant voidedAt,
      long version,
      String canonical) {

    @Override
    public String toString() {
      return "ContractRow[" + id + "]";
    }
  }

  /**
   * A contract to issue.
   *
   * @param id contract
   * @param employeeId employee
   * @param employmentId employment
   * @param templateId template
   * @param templateVersionId approved version
   * @param contractType the template's type
   * @param locale the version's language
   * @param startDate start
   * @param endDate end, or {@code null}
   * @param canonical canonical snapshot JSON
   * @param snapshotSha256 its digest
   * @param issuedAt issue time
   */
  public record NewContract(
      UUID id,
      UUID employeeId,
      UUID employmentId,
      UUID templateId,
      UUID templateVersionId,
      String contractType,
      String locale,
      LocalDate startDate,
      LocalDate endDate,
      String canonical,
      String snapshotSha256,
      Instant issuedAt) {

    @Override
    public String toString() {
      return "NewContract[" + id + "]";
    }
  }

  /**
   * Acknowledgement evidence as shown (identity columns stay in the database).
   *
   * @param contractId contract
   * @param statementCode statement code
   * @param statementVersion statement version
   * @param statementLocale statement language
   * @param statementSha256 statement digest
   * @param evidenceSha256 evidence digest
   * @param acknowledgedAt server time
   */
  public record AcknowledgementRow(
      UUID contractId,
      String statementCode,
      int statementVersion,
      String statementLocale,
      String statementSha256,
      String evidenceSha256,
      Instant acknowledgedAt) {}

  /**
   * Evidence to insert.
   *
   * @param id evidence row
   * @param contractId contract
   * @param employeeId employee
   * @param membershipId the caller's membership
   * @param linkId the caller's active link
   * @param snapshotSha256 the contract's snapshot digest
   * @param statementLocale statement language
   * @param statementSha256 statement digest
   * @param evidenceSha256 evidence digest
   * @param acknowledgedAt server time
   * @param correlationId correlation ID
   */
  public record NewAcknowledgement(
      UUID id,
      UUID contractId,
      UUID employeeId,
      UUID membershipId,
      UUID linkId,
      String snapshotSha256,
      String statementLocale,
      String statementSha256,
      String evidenceSha256,
      Instant acknowledgedAt,
      String correlationId) {

    @Override
    public String toString() {
      return "NewAcknowledgement[" + contractId + "]";
    }
  }

  /**
   * Creates the employment's guard row once, then locks it {@code FOR UPDATE} (lock order step 5c):
   * issues for one employment are serialized.
   *
   * @param tenant verified tenant
   * @param employmentId employment
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockGuard(TenantId tenant, UUID employmentId) {
    jdbc.sql(
            "INSERT INTO documents.contract_employment_guard (tenant_id, employment_id)"
                + " VALUES (:tenant, :employment) ON CONFLICT DO NOTHING")
        .param("tenant", tenant.value())
        .param("employment", employmentId)
        .update();
    jdbc.sql(
            "SELECT employment_id FROM documents.contract_employment_guard"
                + " WHERE tenant_id = :tenant AND employment_id = :employment FOR UPDATE")
        .param("tenant", tenant.value())
        .param("employment", employmentId)
        .query(UUID.class)
        .single();
  }

  /**
   * Inserts an issued contract.
   *
   * @param tenant verified tenant
   * @param contract the contract
   * @param by verified subject
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(TenantId tenant, NewContract contract, String by) {
    jdbc.sql(
            "INSERT INTO documents.contract (id, tenant_id, employee_id, employment_id,"
                + " template_id, template_version_id, contract_type, locale, start_date, end_date,"
                + " snapshot, snapshot_canonical, snapshot_sha256, digest_version,"
                + " grammar_version, renderer_version, state, issued_at, issued_by) VALUES (:id,"
                + " :tenant, :employee, :employment, :template, :version, :type, :locale, :start,"
                + " :end, CAST(:canonical AS jsonb), :canonical, :sha, 1, 1, 1, 'ISSUED', :at,"
                + " :by)")
        .param("id", contract.id())
        .param("tenant", tenant.value())
        .param("employee", contract.employeeId())
        .param("employment", contract.employmentId())
        .param("template", contract.templateId())
        .param("version", contract.templateVersionId())
        .param("type", contract.contractType())
        .param("locale", contract.locale())
        .param("start", Date.valueOf(contract.startDate()))
        .param("end", contract.endDate() == null ? null : Date.valueOf(contract.endDate()))
        .param("canonical", contract.canonical())
        .param("sha", contract.snapshotSha256())
        .param("at", Timestamp.from(contract.issuedAt()))
        .param("by", by)
        .update();
  }

  /**
   * One contract of an employee of the tenant, with its snapshot.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param id contract
   * @param lock whether to lock it {@code FOR UPDATE}
   * @return the contract, if it is that employee's in the tenant
   */
  public Optional<ContractRow> find(TenantId tenant, UUID employeeId, UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + FULL_COLUMNS
                + " FROM documents.contract WHERE tenant_id = :tenant AND employee_id = :employee"
                + " AND id = :id"
                + (lock ? " FOR UPDATE" : ""))
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", id)
        .query((rs, n) -> contract(rs, true))
        .optional();
  }

  /**
   * A page of an employee's contracts, newest first (keyset on issue time and ID), without
   * snapshots.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param afterIssuedAt keyset issue time, or {@code null}
   * @param afterId keyset ID, or {@code null}
   * @param limit rows to fetch
   * @return contracts
   */
  public List<ContractRow> page(
      TenantId tenant, UUID employeeId, Instant afterIssuedAt, UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT "
                + SUMMARY_COLUMNS
                + " FROM documents.contract WHERE tenant_id = :tenant AND employee_id = :employee"
                + " AND (CAST(:after AS timestamptz) IS NULL"
                + " OR (issued_at, id) < (CAST(:after AS timestamptz), CAST(:afterId AS uuid)))"
                + " ORDER BY issued_at DESC, id DESC LIMIT :limit")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("after", afterIssuedAt == null ? null : Timestamp.from(afterIssuedAt))
        .param("afterId", afterId)
        .param("limit", limit)
        .query((rs, n) -> contract(rs, false))
        .list();
  }

  /**
   * Voids an issued contract.
   *
   * @param tenant verified tenant
   * @param id contract
   * @param version expected row version
   * @param reason closed reason code
   * @param at void time
   * @param by verified subject
   * @return rows updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int voidContract(
      TenantId tenant, UUID id, long version, String reason, Instant at, String by) {
    return jdbc.sql(
            "UPDATE documents.contract SET state = 'VOID', void_reason = :reason,"
                + " voided_at = :at, voided_by = :by, version = version + 1"
                + " WHERE tenant_id = :tenant AND id = :id AND version = :version"
                + " AND state = 'ISSUED'")
        .param("reason", reason)
        .param("at", Timestamp.from(at))
        .param("by", by)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", version)
        .update();
  }

  /**
   * The database's statement time, truncated to microseconds by PostgreSQL: the acknowledgement
   * time (server clock, never the client's).
   *
   * @param tenant verified tenant (the call is part of a tenant transaction)
   * @return now
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Instant statementTime(TenantId tenant) {
    return jdbc.sql("SELECT statement_timestamp()")
        .query((rs, n) -> rs.getTimestamp(1).toInstant())
        .single();
  }

  /**
   * Inserts acknowledgement evidence, then marks the contract acknowledged at the same time (the
   * V16 guard requires both).
   *
   * @param tenant verified tenant
   * @param evidence the evidence
   * @param contractVersion the contract's expected row version
   * @return rows of the contract updated
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int acknowledge(TenantId tenant, NewAcknowledgement evidence, long contractVersion) {
    jdbc.sql(
            "INSERT INTO documents.contract_acknowledgement (id, tenant_id, contract_id,"
                + " employee_id, membership_id, link_id, snapshot_sha256, snapshot_digest_version,"
                + " grammar_version, renderer_version, statement_code, statement_version,"
                + " statement_locale, statement_sha256, evidence_sha256, acknowledged_at,"
                + " correlation_id) VALUES (:id, :tenant, :contract, :employee, :membership,"
                + " :link, :snapshot, 1, 1, 1, 'RECEIVED_AND_REVIEWED', 1, :locale, :statement,"
                + " :evidence, :at, :correlation)")
        .param("id", evidence.id())
        .param("tenant", tenant.value())
        .param("contract", evidence.contractId())
        .param("employee", evidence.employeeId())
        .param("membership", evidence.membershipId())
        .param("link", evidence.linkId())
        .param("snapshot", evidence.snapshotSha256())
        .param("locale", evidence.statementLocale())
        .param("statement", evidence.statementSha256())
        .param("evidence", evidence.evidenceSha256())
        .param("at", Timestamp.from(evidence.acknowledgedAt()))
        .param("correlation", evidence.correlationId())
        .update();
    return jdbc.sql(
            "UPDATE documents.contract SET state = 'ACKNOWLEDGED', acknowledged_at = :at,"
                + " version = version + 1 WHERE tenant_id = :tenant AND id = :id"
                + " AND version = :version AND state = 'ISSUED'")
        .param("at", Timestamp.from(evidence.acknowledgedAt()))
        .param("tenant", tenant.value())
        .param("id", evidence.contractId())
        .param("version", contractVersion)
        .update();
  }

  /**
   * The evidence of contracts of the tenant.
   *
   * @param tenant verified tenant
   * @param contractIds contracts
   * @return evidence per contract
   */
  public Map<UUID, AcknowledgementRow> acknowledgements(
      TenantId tenant, Collection<UUID> contractIds) {
    Map<UUID, AcknowledgementRow> found = new LinkedHashMap<>();
    if (contractIds.isEmpty()) {
      return found;
    }
    jdbc.sql(
            "SELECT "
                + ACK_COLUMNS
                + " FROM documents.contract_acknowledgement WHERE tenant_id = :tenant"
                + " AND contract_id = ANY(:ids)")
        .param("tenant", tenant.value())
        .param("ids", contractIds.toArray(UUID[]::new))
        .query(JdbcContractRepository::acknowledgement)
        .list()
        .forEach(row -> found.put(row.contractId(), row));
    return found;
  }

  /**
   * One contract's stored digest and canonical text, for the integrity job.
   *
   * @param tenantId tenant of the row
   * @param id contract
   * @param canonical canonical text
   * @param snapshotSha256 stored digest
   * @param structuredMatches whether the stored {@code jsonb} equals the parsed canonical text
   */
  public record IntegrityRow(
      UUID tenantId, UUID id, String canonical, String snapshotSha256, boolean structuredMatches) {

    @Override
    public String toString() {
      return "IntegrityRow[" + id + "]";
    }
  }

  /**
   * The next batch of contracts by ID, across tenants, for the read-only integrity job.
   *
   * @param afterId keyset ID, or {@code null}
   * @param limit rows
   * @return rows
   */
  @CrossTenantAccess("read-only integrity job: recomputes stored digests across tenants")
  public List<IntegrityRow> integrityBatch(UUID afterId, int limit) {
    return jdbc.sql(
            "SELECT tenant_id, id, snapshot_canonical, snapshot_sha256,"
                + " (snapshot = snapshot_canonical::jsonb) AS structured"
                + " FROM documents.contract WHERE (CAST(:after AS uuid) IS NULL"
                + " OR id > CAST(:after AS uuid)) ORDER BY id LIMIT :limit")
        .param("after", afterId)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new IntegrityRow(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("id", UUID.class),
                    rs.getString("snapshot_canonical"),
                    rs.getString("snapshot_sha256"),
                    rs.getBoolean("structured")))
        .list();
  }

  private static ContractRow contract(ResultSet rs, boolean full) throws SQLException {
    Date end = rs.getDate("end_date");
    return new ContractRow(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("employment_id", UUID.class),
        rs.getObject("template_id", UUID.class),
        rs.getObject("template_version_id", UUID.class),
        rs.getString("contract_type"),
        rs.getString("locale"),
        rs.getDate("start_date").toLocalDate(),
        end == null ? null : end.toLocalDate(),
        rs.getString("snapshot_sha256"),
        rs.getString("state"),
        rs.getTimestamp("issued_at").toInstant(),
        JdbcContractTemplateRepository.instant(rs, "acknowledged_at"),
        rs.getString("void_reason"),
        JdbcContractTemplateRepository.instant(rs, "voided_at"),
        rs.getLong("version"),
        full ? rs.getString("snapshot_canonical") : null);
  }

  private static AcknowledgementRow acknowledgement(ResultSet rs, int n) throws SQLException {
    return new AcknowledgementRow(
        rs.getObject("contract_id", UUID.class),
        rs.getString("statement_code"),
        rs.getInt("statement_version"),
        rs.getString("statement_locale"),
        rs.getString("statement_sha256"),
        rs.getString("evidence_sha256"),
        rs.getTimestamp("acknowledged_at").toInstant());
  }
}
