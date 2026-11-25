package com.divalhr.core.integrations.partner.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The employee's own partner-referral data for the access package, and the stored package itself
 * (LAUNCH-001, L8). Every read adds the tenant and employee predicates.
 */
@Repository
public class PrivacyExportStore {

  /** One referral with the catalogue facts the package needs. */
  public record ReferralData(
      UUID id,
      String reference,
      String state,
      Instant createdAt,
      String partnerName,
      String productName,
      String language,
      List<String> fieldNames,
      byte[] sealedValues,
      String disclosureOutcome,
      Instant consentedAt,
      String consentVersion,
      String consentLanguage,
      int attemptCount,
      boolean receiptConfirmed,
      String stampStatus,
      String policyCountry,
      String policyWording) {}

  /** One referral lifecycle event. */
  public record ReferralEvent(
      int sequence,
      String fromState,
      String toState,
      String actorRole,
      String outcome,
      Instant occurredAt) {}

  /** A stored package. */
  public record StoredPackage(
      byte[] sealed, String sha256, int generation, Instant generatedAt, Instant expiresAt) {}

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PrivacyExportStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The employee's referrals (one, or all when {@code referral} is null), oldest first. */
  public List<ReferralData> referrals(UUID tenant, UUID employee, UUID referral) {
    return jdbc.sql(
            """
            SELECT r.id, r.reference, r.state, r.created_at,
                   coalesce(p.trading_name, p.legal_name) AS partner_name,
                   CASE r.language WHEN 'fr' THEN v.name_fr ELSE v.name_en END AS product_name,
                   r.language, d.field_names, d.values_ciphertext,
                   (SELECT i.action FROM integrations.privacy_purge_item i
                     WHERE i.tenant_id = r.tenant_id AND i.referral_id = r.id
                       AND i.data_class = 'PARTNER_REFERRAL_DISCLOSURE') AS disclosure_outcome,
                   c.consented_at, c.consent_version, c.language AS consent_language,
                   (SELECT count(*) FROM integrations.partner_referral_attempt a
                     WHERE a.tenant_id = r.tenant_id AND a.referral_id = r.id) AS attempts,
                   EXISTS (SELECT 1 FROM integrations.partner_referral_ack k
                            WHERE k.tenant_id = r.tenant_id AND k.referral_id = r.id) AS receipt,
                   s.status, o.country_code AS policy_country, o.wording_version
              FROM integrations.partner_referral r
              JOIN integrations.partner p ON p.id = r.partner_id
              JOIN integrations.partner_product_version v ON v.id = r.version_id
              LEFT JOIN integrations.partner_referral_disclosure d
                ON d.tenant_id = r.tenant_id AND d.referral_id = r.id
              LEFT JOIN integrations.partner_referral_consent c
                ON c.tenant_id = r.tenant_id AND c.referral_id = r.id
              LEFT JOIN integrations.privacy_referral_stamp s
                ON s.tenant_id = r.tenant_id AND s.referral_id = r.id
              LEFT JOIN integrations.privacy_policy o ON o.id = s.policy_id
             WHERE r.tenant_id = :tenant AND r.employee_id = :employee
               AND (CAST(:referral AS uuid) IS NULL OR r.id = :referral)
             ORDER BY r.created_at, r.id
             LIMIT 200
            """)
        .param("tenant", tenant)
        .param("employee", employee)
        .param("referral", referral)
        .query(
            (rs, n) -> {
              java.sql.Array names = rs.getArray("field_names");
              List<String> fields =
                  names == null ? List.of() : List.of((String[]) names.getArray());
              Timestamp consented = rs.getTimestamp("consented_at");
              return new ReferralData(
                  rs.getObject("id", UUID.class),
                  rs.getString("reference"),
                  rs.getString("state"),
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getString("partner_name"),
                  rs.getString("product_name"),
                  rs.getString("language"),
                  fields,
                  rs.getBytes("values_ciphertext"),
                  rs.getString("disclosure_outcome"),
                  consented == null ? null : consented.toInstant(),
                  rs.getString("consent_version"),
                  rs.getString("consent_language"),
                  rs.getInt("attempts"),
                  rs.getBoolean("receipt"),
                  rs.getString("status"),
                  rs.getString("policy_country") == null
                      ? null
                      : rs.getString("policy_country").trim(),
                  rs.getString("wording_version"));
            })
        .list();
  }

  /** The lifecycle events of one referral. */
  public List<ReferralEvent> events(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT sequence, from_state, to_state, actor_role, outcome, occurred_at FROM"
                + " integrations.partner_referral_event WHERE tenant_id = :tenant AND referral_id"
                + " = :referral ORDER BY sequence")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(
            (rs, n) ->
                new ReferralEvent(
                    rs.getInt("sequence"),
                    rs.getString("from_state"),
                    rs.getString("to_state"),
                    rs.getString("actor_role"),
                    rs.getString("outcome"),
                    rs.getTimestamp("occurred_at").toInstant()))
        .list();
  }

  /** Whether a hold is active on the referral. */
  public boolean holdActive(UUID tenant, UUID referral) {
    Boolean active =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM integrations.privacy_hold WHERE tenant_id ="
                    + " :tenant AND referral_id = :referral AND state = 'ACTIVE')")
            .param("tenant", tenant)
            .param("referral", referral)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(active);
  }

  /** The stored package of a request, or empty. */
  public Optional<StoredPackage> find(UUID tenant, UUID request) {
    return jdbc.sql(
            "SELECT package_ciphertext, manifest_sha256, generation_count, generated_at,"
                + " expires_at FROM integrations.privacy_export WHERE tenant_id = :tenant AND"
                + " request_id = :request")
        .param("tenant", tenant)
        .param("request", request)
        .query(
            (rs, n) ->
                new StoredPackage(
                    rs.getBytes("package_ciphertext"),
                    rs.getString("manifest_sha256").trim(),
                    rs.getInt("generation_count"),
                    rs.getTimestamp("generated_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()))
        .optional();
  }

  /** Stores the first package. */
  public void insert(
      UUID tenant, UUID request, byte[] sealed, String sha256, Instant now, Instant expires) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_export VALUES (:tenant, :request, :sealed, :sha,"
                + " 'v1', 1, :now, :expires)")
        .param("tenant", tenant)
        .param("request", request)
        .param("sealed", sealed)
        .param("sha", sha256)
        .param("now", Timestamp.from(now))
        .param("expires", Timestamp.from(expires))
        .update();
  }

  /** Replaces an expired package with a new generation. */
  public void replace(
      UUID tenant,
      UUID request,
      byte[] sealed,
      String sha256,
      int generation,
      Instant now,
      Instant expires) {
    jdbc.sql(
            "UPDATE integrations.privacy_export SET package_ciphertext = :sealed,"
                + " manifest_sha256 = :sha, generation_count = :generation, generated_at = :now,"
                + " expires_at = :expires WHERE tenant_id = :tenant AND request_id = :request")
        .param("sealed", sealed)
        .param("sha", sha256)
        .param("generation", generation)
        .param("now", Timestamp.from(now))
        .param("expires", Timestamp.from(expires))
        .param("tenant", tenant)
        .param("request", request)
        .update();
  }

  /** Appends a GENERATED or DOWNLOADED event. */
  public void insertEvent(UUID tenant, UUID request, String kind, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_export_event SELECT :tenant, :request,"
                + " coalesce(max(sequence), 0) + 1, :kind, :now FROM"
                + " integrations.privacy_export_event WHERE tenant_id = :tenant AND request_id ="
                + " :request")
        .param("tenant", tenant)
        .param("request", request)
        .param("kind", kind)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** The number of export events of a request. */
  public int eventCount(UUID tenant, UUID request) {
    return jdbc.sql(
            "SELECT count(*) FROM integrations.privacy_export_event WHERE tenant_id = :tenant"
                + " AND request_id = :request")
        .param("tenant", tenant)
        .param("request", request)
        .query(Integer.class)
        .single();
  }
}
