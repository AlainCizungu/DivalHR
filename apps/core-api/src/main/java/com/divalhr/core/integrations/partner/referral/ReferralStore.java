package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The consented referral's own rows (MVP-120F, ADR 0045). Every statement is parameterized and
 * takes the verified tenant; employee reads add the employee predicate, so another employee's
 * referral is simply absent. Locks follow the documented order: the referral row first, then its
 * children.
 */
@Repository
public class ReferralStore {

  /** A referral with the catalogue facts a view needs. */
  public record ReferralRow(
      UUID id,
      String reference,
      UUID employeeId,
      UUID partnerId,
      String language,
      String state,
      int version,
      int attemptCount,
      boolean leased,
      Instant createdAt,
      Instant updatedAt,
      String productReference,
      String productType,
      String partnerName,
      String nameEn,
      String nameFr,
      String primaryCategory) {}

  /** A history event. */
  public record EventRow(
      int sequence,
      String fromState,
      String toState,
      String actorRole,
      String outcome,
      Instant occurredAt) {}

  /** The keyset position of a list. */
  public record Position(Instant createdAt, UUID id) {}

  /** A new referral. */
  public record NewReferral(
      UUID id,
      String reference,
      UUID employeeId,
      UUID partnerId,
      UUID productId,
      UUID versionId,
      String language,
      String previewDigest,
      String submittedBy,
      Instant at) {}

  private static final String SELECT =
      """
      SELECT r.id, r.reference, r.employee_id, r.partner_id, r.language, r.state, r.row_version,
             r.attempt_count, (r.lease_until IS NOT NULL) AS leased, r.created_at, r.updated_at,
             x.public_reference, x.product_type, coalesce(p.trading_name, p.legal_name) AS display,
             v.name_en, v.name_fr, p.primary_category
        FROM integrations.partner_referral r
        JOIN integrations.partner_product_version v ON v.id = r.version_id
        JOIN integrations.partner_product x ON x.id = r.product_id
        JOIN integrations.partner p ON p.id = r.partner_id
       WHERE r.tenant_id = :tenant
      """;

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public ReferralStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static ReferralRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ReferralRow(
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getObject("employee_id", UUID.class),
        rs.getObject("partner_id", UUID.class),
        rs.getString("language"),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getInt("attempt_count"),
        rs.getBoolean("leased"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("public_reference"),
        rs.getString("product_type"),
        rs.getString("display"),
        rs.getString("name_en"),
        rs.getString("name_fr"),
        rs.getString("primary_category"));
  }

  /**
   * The employee's own referral.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param reference opaque referral reference
   * @return the referral
   */
  public Optional<ReferralRow> mine(TenantId tenant, UUID employeeId, String reference) {
    return jdbc.sql(SELECT + " AND r.employee_id = :employee AND r.reference = :reference")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /**
   * The employee's own referral by id.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param id referral id
   * @return the referral
   */
  public Optional<ReferralRow> mineById(TenantId tenant, UUID employeeId, UUID id) {
    return jdbc.sql(SELECT + " AND r.employee_id = :employee AND r.id = :id")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /**
   * Locks the employee's own referral row for the rest of the transaction (lock order: the referral
   * row first).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param reference opaque referral reference
   * @return the referral
   */
  public Optional<ReferralRow> lockMine(TenantId tenant, UUID employeeId, String reference) {
    return jdbc.sql(
            SELECT + " AND r.employee_id = :employee AND r.reference = :reference FOR UPDATE OF r")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /**
   * A page of the employee's referrals, newest first.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param state state filter or {@code null}
   * @param after keyset or {@code null}
   * @param limit rows
   * @return the rows
   */
  public List<ReferralRow> page(
      TenantId tenant, UUID employeeId, String state, Position after, int limit) {
    return jdbc.sql(
            SELECT
                + " AND r.employee_id = :employee"
                + " AND (CAST(:state AS text) IS NULL OR r.state = :state)"
                + " AND (CAST(:afterAt AS timestamptz) IS NULL"
                + "      OR (r.created_at, r.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))"
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("state", state)
        .param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
        .param("afterId", after == null ? null : after.id())
        .param("limit", limit)
        .query((rs, n) -> row(rs))
        .list();
  }

  /**
   * The history of a referral, in order.
   *
   * @param tenant verified tenant
   * @param referralId referral
   * @return the events
   */
  public List<EventRow> events(TenantId tenant, UUID referralId) {
    return jdbc.sql(
            "SELECT sequence, from_state, to_state, actor_role, outcome, occurred_at"
                + " FROM integrations.partner_referral_event"
                + " WHERE tenant_id = :tenant AND referral_id = :id ORDER BY sequence")
        .param("tenant", tenant.value())
        .param("id", referralId)
        .query(
            (rs, n) ->
                new EventRow(
                    rs.getInt("sequence"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("actor_role"),
                    rs.getString("outcome"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /**
   * The names of the fields a referral disclosed.
   *
   * @param tenant verified tenant
   * @param referralId referral
   * @return the field names in allow-list order
   */
  public List<String> disclosedFields(TenantId tenant, UUID referralId) {
    String joined =
        jdbc.sql(
                "SELECT array_to_string(field_names, ',') FROM integrations.partner_referral_disclosure"
                    + " WHERE tenant_id = :tenant AND referral_id = :id")
            .param("tenant", tenant.value())
            .param("id", referralId)
            .query(String.class)
            .optional()
            .orElse("");
    List<String> fields = new java.util.ArrayList<>();
    for (PartnerReferralDomain.Field field : PartnerReferralDomain.Field.values()) {
      if (("," + joined + ",").contains("," + field.name() + ",")) {
        fields.add(field.name());
      }
    }
    return List.copyOf(fields);
  }

  /**
   * Whether the employee already used a preview digest.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param digest the preview digest
   * @return true when a referral exists for it
   */
  public boolean previewUsed(TenantId tenant, UUID employeeId, String digest) {
    return jdbc.sql(
                "SELECT count(*) FROM integrations.partner_referral"
                    + " WHERE tenant_id = :tenant AND employee_id = :employee AND preview_sha256 = :digest")
            .param("tenant", tenant.value())
            .param("employee", employeeId)
            .param("digest", digest)
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * The employee's own referrals committed since an instant.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param since window start
   * @return the count
   */
  public long commitsSince(TenantId tenant, UUID employeeId, Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM integrations.partner_referral"
                + " WHERE tenant_id = :tenant AND employee_id = :employee AND created_at >= :since")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("since", Timestamp.from(since))
        .query(Long.class)
        .single();
  }

  /**
   * The employee's own withdrawals since an instant.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's employee
   * @param since window start
   * @return the count
   */
  public long withdrawalsSince(TenantId tenant, UUID employeeId, Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM integrations.partner_referral_event e"
                + " JOIN integrations.partner_referral r ON r.tenant_id = e.tenant_id AND r.id = e.referral_id"
                + " WHERE e.tenant_id = :tenant AND r.employee_id = :employee"
                + " AND e.to_state = 'WITHDRAWN' AND e.occurred_at >= :since")
        .param("tenant", tenant.value())
        .param("employee", employeeId)
        .param("since", Timestamp.from(since))
        .query(Long.class)
        .single();
  }

  /**
   * Inserts a referral in state SUBMITTED, due for delivery at once.
   *
   * @param tenant verified tenant
   * @param referral the new referral
   */
  public void insert(TenantId tenant, NewReferral referral) {
    jdbc.sql(
            """
            INSERT INTO integrations.partner_referral
              (tenant_id, id, reference, employee_id, partner_id, product_id, version_id, partner_code,
               language, state, row_version, attempt_count, preview_sha256, key_version, submitted_by,
               next_attempt_at, created_at, updated_at)
            VALUES (:tenant, :id, :reference, :employee, :partner, :product, :version, 'SYNTHETIC-TEST',
                    :language, 'SUBMITTED', 1, 0, :digest, 'v1', :by, :at, :at, :at)
            """)
        .param("tenant", tenant.value())
        .param("id", referral.id())
        .param("reference", referral.reference())
        .param("employee", referral.employeeId())
        .param("partner", referral.partnerId())
        .param("product", referral.productId())
        .param("version", referral.versionId())
        .param("language", referral.language())
        .param("digest", referral.previewDigest())
        .param("by", referral.submittedBy())
        .param("at", Timestamp.from(referral.at()))
        .update();
  }

  /**
   * Inserts the immutable disclosure manifest.
   *
   * @param tenant verified tenant
   * @param referralId referral
   * @param fieldNames the disclosed field names
   * @param ciphertext the sealed values
   * @param digest the canonical disclosure digest
   * @param at server instant
   */
  public void insertDisclosure(
      TenantId tenant,
      UUID referralId,
      List<String> fieldNames,
      byte[] ciphertext,
      String digest,
      Instant at) {
    jdbc.sql(
            "INSERT INTO integrations.partner_referral_disclosure"
                + " (tenant_id, referral_id, field_names, values_ciphertext, disclosure_sha256, created_at)"
                + " VALUES (:tenant, :id, CAST(:names AS text[]), :cipher, :digest, :at)")
        .param("tenant", tenant.value())
        .param("id", referralId)
        .param("names", "{" + String.join(",", fieldNames) + "}")
        .param("cipher", ciphertext)
        .param("digest", digest)
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Inserts the consent evidence.
   *
   * @param tenant verified tenant
   * @param referralId referral
   * @param language the consent language
   * @param subject the consenting subject
   * @param disclosureDigest the canonical disclosure digest
   * @param previewDigest the preview digest
   * @param at server instant
   */
  public void insertConsent(
      TenantId tenant,
      UUID referralId,
      String language,
      String subject,
      String disclosureDigest,
      String previewDigest,
      Instant at) {
    jdbc.sql(
            "INSERT INTO integrations.partner_referral_consent"
                + " (tenant_id, referral_id, consent_version, language, consented_at, consented_by,"
                + "  disclosure_sha256, preview_sha256, affirmed)"
                + " VALUES (:tenant, :id, :version, :language, :at, :by, :disclosure, :preview, true)")
        .param("tenant", tenant.value())
        .param("id", referralId)
        .param("version", PartnerReferralDomain.CONSENT_VERSION)
        .param("language", language)
        .param("at", Timestamp.from(at))
        .param("by", subject)
        .param("disclosure", disclosureDigest)
        .param("preview", previewDigest)
        .update();
  }

  /**
   * Appends a history event.
   *
   * @param tenant verified tenant
   * @param referralId referral
   * @param sequence next sequence
   * @param from previous state or {@code null}
   * @param to new state
   * @param actor {@code EMPLOYEE} or {@code SYSTEM}
   * @param outcome closed outcome or {@code null}
   * @param at server instant
   */
  public void insertEvent(
      TenantId tenant,
      UUID referralId,
      int sequence,
      String from,
      String to,
      String actor,
      String outcome,
      Instant at) {
    jdbc.sql(
            "INSERT INTO integrations.partner_referral_event"
                + " (tenant_id, referral_id, sequence, from_state, to_state, actor_role, outcome, occurred_at)"
                + " VALUES (:tenant, :id, :sequence, :from, :to, :actor, :outcome, :at)")
        .param("tenant", tenant.value())
        .param("id", referralId)
        .param("sequence", sequence)
        .param("from", from)
        .param("to", to)
        .param("actor", actor)
        .param("outcome", outcome)
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Moves a locked referral to WITHDRAWN (the database guard refuses any illegal move).
   *
   * @param tenant verified tenant
   * @param id referral
   * @param newVersion the next row version
   * @param at server instant
   */
  public void markWithdrawn(TenantId tenant, UUID id, int newVersion, Instant at) {
    jdbc.sql(
            "UPDATE integrations.partner_referral SET state = 'WITHDRAWN', row_version = :version,"
                + " next_attempt_at = NULL, lease_until = NULL, updated_at = :at"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", newVersion)
        .param("at", Timestamp.from(at))
        .update();
  }
}
