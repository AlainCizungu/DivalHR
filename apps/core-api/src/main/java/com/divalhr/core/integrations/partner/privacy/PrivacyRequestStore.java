package com.divalhr.core.integrations.partner.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data-rights requests, their history and the access package (LAUNCH-001). Every employee read adds
 * the tenant and employee predicates, so another employee's request is simply absent. Platform
 * reads span tenants and are reachable only through platform-scoped, exact-MFA operations.
 */
@Repository
public class PrivacyRequestStore {

  /** A request header with the referral reference it concerns. */
  public record RequestRow(
      UUID tenantId,
      UUID id,
      String reference,
      UUID employeeId,
      String requestType,
      UUID referralId,
      String referralReference,
      String state,
      int version,
      String decisionReason,
      Instant createdAt,
      Instant updatedAt,
      boolean exportAvailable) {}

  /** A history event. */
  public record EventRow(
      int sequence, String fromState, String toState, String reason, Instant occurredAt) {}

  /** The keyset position of a list. */
  public record Position(Instant createdAt, UUID id) {}

  private static final String SELECT =
      "SELECT q.tenant_id, q.id, q.reference, q.employee_id, q.request_type, q.referral_id,"
          + " r.reference AS referral_reference, q.state, q.row_version, q.decision_reason,"
          + " q.created_at, q.updated_at, EXISTS (SELECT 1 FROM integrations.privacy_export x"
          + " WHERE x.tenant_id = q.tenant_id AND x.request_id = q.id AND x.expires_at > now())"
          + " AS export_available FROM integrations.privacy_request q"
          + " LEFT JOIN integrations.partner_referral r ON r.tenant_id = q.tenant_id"
          + " AND r.id = q.referral_id";

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PrivacyRequestStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static RequestRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new RequestRow(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getObject("employee_id", UUID.class),
        rs.getString("request_type"),
        rs.getObject("referral_id", UUID.class),
        rs.getString("referral_reference"),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getString("decision_reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getBoolean("export_available"));
  }

  /** The employee's own referral id by reference, or empty. */
  public Optional<UUID> ownReferral(UUID tenant, UUID employee, String reference) {
    return jdbc.sql(
            "SELECT id FROM integrations.partner_referral WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND reference = :reference")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("reference", reference)
        .query(UUID.class)
        .optional();
  }

  /** Whether the employee already has an open request of the type and scope. */
  public boolean openExists(UUID tenant, UUID employee, String type, UUID referral) {
    Boolean exists =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM integrations.privacy_request WHERE tenant_id ="
                    + " :tenant AND employee_id = :employee AND request_type = :type AND"
                    + " coalesce(referral_id, '00000000-0000-0000-0000-000000000000'::uuid) ="
                    + " coalesce(CAST(:referral AS uuid), '00000000-0000-0000-0000-000000000000'"
                    + "::uuid) AND state IN ('SUBMITTED', 'UNDER_REVIEW',"
                    + " 'MORE_INFORMATION_REQUIRED', 'APPROVED', 'PARTIALLY_APPROVED'))")
            .param("tenant", tenant)
            .param("employee", employee)
            .param("type", type)
            .param("referral", referral)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  /** Inserts a SUBMITTED request at version 1. */
  public void insert(
      UUID tenant,
      UUID id,
      String reference,
      UUID employee,
      String type,
      UUID referral,
      Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_request (tenant_id, id, reference, employee_id,"
                + " request_type, referral_id, state, row_version, decision_reason, created_at,"
                + " updated_at) VALUES (:tenant, :id, :reference, :employee, :type, :referral,"
                + " 'SUBMITTED', 1, NULL, :now, :now)")
        .param("tenant", tenant)
        .param("id", id)
        .param("reference", reference)
        .param("employee", employee)
        .param("type", type)
        .param("referral", referral)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Appends a history event. */
  public void insertEvent(
      UUID tenant,
      UUID request,
      int sequence,
      String from,
      String to,
      String actorRole,
      String reason,
      Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_request_event VALUES (:tenant, :request, :sequence,"
                + " :from, :to, :actor, :reason, :now)")
        .param("tenant", tenant)
        .param("request", request)
        .param("sequence", sequence)
        .param("from", from)
        .param("to", to)
        .param("actor", actorRole)
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Moves a request one version. */
  public void move(UUID tenant, UUID id, String state, int version, String reason, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_request SET state = :state, row_version = :version,"
                + " decision_reason = :reason, updated_at = :now WHERE tenant_id = :tenant"
                + " AND id = :id")
        .param("state", state)
        .param("version", version)
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("tenant", tenant)
        .param("id", id)
        .update();
  }

  /** The employee's own request by reference. */
  public Optional<RequestRow> mine(UUID tenant, UUID employee, String reference) {
    return jdbc.sql(
            SELECT
                + " WHERE q.tenant_id = :tenant AND q.employee_id = :employee"
                + " AND q.reference = :reference")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** The employee's own request, locked for update. */
  public Optional<RequestRow> lockMine(UUID tenant, UUID employee, String reference) {
    lockHeader(tenant, employee, reference);
    return mine(tenant, employee, reference);
  }

  private void lockHeader(UUID tenant, UUID employee, String reference) {
    jdbc.sql(
            "SELECT id FROM integrations.privacy_request WHERE tenant_id = :tenant"
                + " AND employee_id = :employee AND reference = :reference FOR UPDATE")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("reference", reference)
        .query(UUID.class)
        .optional();
  }

  /** A request by id inside a tenant (cursor resolution). */
  public Optional<RequestRow> byId(UUID tenant, UUID id) {
    return jdbc.sql(SELECT + " WHERE q.tenant_id = :tenant AND q.id = :id")
        .param("tenant", tenant)
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** The employee's own requests, newest first. */
  public List<RequestRow> pageMine(
      UUID tenant, UUID employee, String state, Position after, int limit) {
    return jdbc.sql(
            SELECT
                + " WHERE q.tenant_id = :tenant AND q.employee_id = :employee"
                + " AND (CAST(:state AS text) IS NULL OR q.state = :state)"
                + " AND (CAST(:at AS timestamptz) IS NULL OR (q.created_at, q.id) <"
                + " (CAST(:at AS timestamptz), CAST(:id AS uuid)))"
                + " ORDER BY q.created_at DESC, q.id DESC LIMIT :limit")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("state", state)
        .param("at", after == null ? null : Timestamp.from(after.createdAt()))
        .param("id", after == null ? null : after.id())
        .param("limit", limit)
        .query((rs, n) -> row(rs))
        .list();
  }

  /** The history of a request, oldest first. */
  public List<EventRow> events(UUID tenant, UUID request) {
    return jdbc.sql(
            "SELECT sequence, from_state, to_state, decision_reason, occurred_at"
                + " FROM integrations.privacy_request_event WHERE tenant_id = :tenant"
                + " AND request_id = :request ORDER BY sequence")
        .param("tenant", tenant)
        .param("request", request)
        .query(
            (rs, n) ->
                new EventRow(
                    rs.getInt("sequence"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("decision_reason"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /** A request by reference across tenants (platform privacy operations only). */
  public Optional<RequestRow> byReference(String reference) {
    return jdbc.sql(SELECT + " WHERE q.reference = :reference")
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** A request by reference across tenants, locked for update. */
  public Optional<RequestRow> lockByReference(String reference) {
    jdbc.sql("SELECT id FROM integrations.privacy_request WHERE reference = :reference FOR UPDATE")
        .param("reference", reference)
        .query(UUID.class)
        .optional();
    return byReference(reference);
  }

  /** A request by id across tenants (cursor resolution). */
  public Optional<RequestRow> byIdAnyTenant(UUID id) {
    return jdbc.sql(SELECT + " WHERE q.id = :id")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** The platform queue, newest first. */
  public List<RequestRow> page(String state, String type, Position after, int limit) {
    return jdbc.sql(
            SELECT
                + " WHERE (CAST(:state AS text) IS NULL OR q.state = :state)"
                + " AND (CAST(:type AS text) IS NULL OR q.request_type = :type)"
                + " AND (CAST(:at AS timestamptz) IS NULL OR (q.created_at, q.id) <"
                + " (CAST(:at AS timestamptz), CAST(:id AS uuid)))"
                + " ORDER BY q.created_at DESC, q.id DESC LIMIT :limit")
        .param("state", state)
        .param("type", type)
        .param("at", after == null ? null : Timestamp.from(after.createdAt()))
        .param("id", after == null ? null : after.id())
        .param("limit", limit)
        .query((rs, n) -> row(rs))
        .list();
  }

  /** Appends a platform decision event carrying the employee message and the sealed note. */
  public void insertDecisionEvent(
      UUID tenant,
      UUID request,
      int sequence,
      String from,
      String to,
      String reason,
      String employeeMessage,
      byte[] sealedNote,
      Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_request_event (tenant_id, request_id, sequence,"
                + " from_state, to_state, actor_role, decision_reason, occurred_at,"
                + " employee_message, note_ciphertext) VALUES (:tenant, :request, :sequence,"
                + " :from, :to, 'PLATFORM', :reason, :now, :message, :note)")
        .param("tenant", tenant)
        .param("request", request)
        .param("sequence", sequence)
        .param("from", from)
        .param("to", to)
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("message", employeeMessage)
        .param("note", sealedNote)
        .update();
  }

  /** The latest employee message of a request, or {@code null}. */
  public String latestEmployeeMessage(UUID tenant, UUID request) {
    return jdbc.sql(
            "SELECT employee_message FROM integrations.privacy_request_event WHERE tenant_id ="
                + " :tenant AND request_id = :request AND employee_message IS NOT NULL"
                + " ORDER BY sequence DESC LIMIT 1")
        .param("tenant", tenant)
        .param("request", request)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** Whether any event of the request carries an operational note. */
  public boolean notePresent(UUID tenant, UUID request) {
    Boolean present =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM integrations.privacy_request_event WHERE tenant_id ="
                    + " :tenant AND request_id = :request AND note_ciphertext IS NOT NULL)")
            .param("tenant", tenant)
            .param("request", request)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(present);
  }

  /** The sealed disclosure of the employee's latest available referral (for masking only). */
  public Optional<SealedDisclosure> latestDisclosure(UUID tenant, UUID employee, UUID referral) {
    return jdbc.sql(
            "SELECT d.referral_id, d.values_ciphertext FROM integrations.partner_referral r"
                + " JOIN integrations.partner_referral_disclosure d ON d.tenant_id = r.tenant_id"
                + " AND d.referral_id = r.id WHERE r.tenant_id = :tenant AND r.employee_id ="
                + " :employee AND (CAST(:referral AS uuid) IS NULL OR r.id = :referral)"
                + " ORDER BY r.created_at DESC LIMIT 1")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("referral", referral)
        .query(
            (rs, n) ->
                new SealedDisclosure(
                    rs.getObject("referral_id", UUID.class), rs.getBytes("values_ciphertext")))
        .optional();
  }

  /** A sealed disclosure with the referral it belongs to. */
  public record SealedDisclosure(UUID referralId, byte[] sealed) {}
}
