package com.divalhr.core.integrations.partner.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Previews, policy applications and purges (LAUNCH-001, L5 and L9). Every statement is
 * parameterized. The database authorizes each deletion through the purge context and a matching
 * purge item; this store only issues the statements the service has decided on, referral row first.
 */
@Repository
public class PrivacyGovernanceStore {

  /** A preview. */
  public record PreviewRow(
      UUID id,
      String reference,
      String kind,
      String actor,
      String fingerprint,
      String state,
      Instant createdAt,
      Instant expiresAt,
      String action,
      String country) {}

  /** A referral key with what eligibility needs. */
  public record ReferralRow(
      UUID tenantId, UUID id, String reference, String state, Instant createdAt) {}

  /** An ACTIVE policy of a country. */
  public record ActivePolicy(String country, UUID id, String reference) {}

  /** A stamped rule. */
  public record StampRule(String action, String trigger, Integer days) {}

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PrivacyGovernanceStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------
  // Previews
  // ------------------------------------------------------------------------------------------

  /** Inserts an OPEN preview. */
  public void insertPreview(
      UUID id,
      String reference,
      String kind,
      String actor,
      String fingerprint,
      Instant now,
      String action,
      String country) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_preview (id, reference, kind, actor, fingerprint,"
                + " state, created_at, expires_at, used_at, action, country_code) VALUES (:id,"
                + " :reference, :kind, :actor, :fingerprint, 'OPEN', :now, :expires, NULL, :action,"
                + " :country)")
        .param("id", id)
        .param("reference", reference)
        .param("kind", kind)
        .param("actor", actor)
        .param("fingerprint", fingerprint)
        .param("now", Timestamp.from(now))
        .param("expires", Timestamp.from(now.plusSeconds(900)))
        .param("action", action)
        .param("country", country)
        .update();
  }

  /** Records one referral of a purge preview. */
  public void insertPreviewItem(UUID preview, UUID tenant, UUID referral) {
    jdbc.sql("INSERT INTO integrations.privacy_preview_item VALUES (:preview, :tenant, :referral)")
        .param("preview", preview)
        .param("tenant", tenant)
        .param("referral", referral)
        .update();
  }

  /** A preview by reference, locked for update. */
  public Optional<PreviewRow> lockPreview(String reference) {
    return jdbc.sql(
            "SELECT id, reference, kind, actor, fingerprint, state, created_at, expires_at,"
                + " action, country_code FROM integrations.privacy_preview WHERE reference ="
                + " :reference FOR UPDATE")
        .param("reference", reference)
        .query(
            (rs, n) ->
                new PreviewRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("reference"),
                    rs.getString("kind"),
                    rs.getString("actor"),
                    rs.getString("fingerprint").trim(),
                    rs.getString("state"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getString("action"),
                    rs.getString("country_code") == null
                        ? null
                        : rs.getString("country_code").trim()))
        .optional();
  }

  /** The referrals a purge preview covers, ordered by id (the lock order). */
  public List<ReferralRow> previewReferrals(UUID preview) {
    return jdbc.sql(
            "SELECT r.tenant_id, r.id, r.reference, r.state, r.created_at FROM"
                + " integrations.privacy_preview_item i JOIN integrations.partner_referral r ON"
                + " r.tenant_id = i.tenant_id AND r.id = i.referral_id WHERE i.preview_id ="
                + " :preview ORDER BY r.id")
        .param("preview", preview)
        .query((rs, n) -> referral(rs))
        .list();
  }

  /** Marks a preview used. */
  public void usePreview(UUID id, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_preview SET state = 'USED', used_at = :now WHERE id ="
                + " :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private static ReferralRow referral(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ReferralRow(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getString("state"),
        rs.getTimestamp("created_at").toInstant());
  }

  // ------------------------------------------------------------------------------------------
  // Policy application
  // ------------------------------------------------------------------------------------------

  /** The ACTIVE policies covering a day, one per country, optionally for one country. */
  public List<ActivePolicy> activePolicies(String country, LocalDate today) {
    return jdbc.sql(
            "SELECT country_code, id, reference FROM integrations.privacy_policy WHERE state ="
                + " 'ACTIVE' AND effective_from <= :today AND (effective_to IS NULL OR"
                + " effective_to >= :today) AND (CAST(:country AS text) IS NULL OR country_code ="
                + " :country) ORDER BY country_code")
        .param("today", today)
        .param("country", country)
        .query(
            (rs, n) ->
                new ActivePolicy(
                    rs.getString("country_code").trim(),
                    rs.getObject("id", UUID.class),
                    rs.getString("reference")))
        .list();
  }

  /** The POLICY_PENDING referrals of a country, ordered by id, optionally locked. */
  public List<ReferralRow> pending(String country, boolean lock) {
    return jdbc.sql(
            "SELECT r.tenant_id, r.id, r.reference, r.state, r.created_at FROM"
                + " integrations.privacy_referral_stamp s JOIN integrations.partner_referral r ON"
                + " r.tenant_id = s.tenant_id AND r.id = s.referral_id WHERE s.status ="
                + " 'POLICY_PENDING' AND s.country_code = :country ORDER BY r.id"
                + (lock ? " FOR UPDATE OF r" : ""))
        .param("country", country)
        .query((rs, n) -> referral(rs))
        .list();
  }

  /** Records an application of one policy. */
  public void insertApplication(
      UUID id, String reference, UUID policy, UUID preview, String actor, int count, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_policy_application VALUES (:id, :reference,"
                + " :policy, :preview, :actor, :count, :now)")
        .param("id", id)
        .param("reference", reference)
        .param("policy", policy)
        .param("preview", preview)
        .param("actor", actor)
        .param("count", count)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Records one referral of an application. */
  public void insertApplicationItem(UUID application, UUID tenant, UUID referral) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_policy_application_item VALUES (:application,"
                + " :tenant, :referral)")
        .param("application", application)
        .param("tenant", tenant)
        .param("referral", referral)
        .update();
  }

  /** Authorizes the following stamp updates for one application (transaction-local). */
  public void applicationContext(UUID application) {
    jdbc.sql("SELECT set_config('divalhr.privacy_application', :id, true)")
        .param("id", application.toString())
        .query(String.class)
        .single();
  }

  /** Stamps a pending referral with the active policy and copies its rules. */
  public void stamp(UUID tenant, UUID referral, UUID policy, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_referral_stamp SET status = 'STAMPED', policy_id ="
                + " :policy, rules_digest = integrations.privacy_rules_digest(:policy), source ="
                + " 'APPLICATION', stamped_at = :now WHERE tenant_id = :tenant AND referral_id ="
                + " :referral AND status = 'POLICY_PENDING'")
        .param("policy", policy)
        .param("now", Timestamp.from(now))
        .param("tenant", tenant)
        .param("referral", referral)
        .update();
    jdbc.sql("SELECT integrations.privacy_copy_rules(:tenant, :referral, :policy)")
        .param("tenant", tenant)
        .param("referral", referral)
        .param("policy", policy)
        .query(Object.class)
        .optional();
  }

  // ------------------------------------------------------------------------------------------
  // Purge eligibility
  // ------------------------------------------------------------------------------------------

  /** A referral by reference. */
  public Optional<ReferralRow> referralByReference(String reference) {
    return jdbc.sql(
            "SELECT tenant_id, id, reference, state, created_at FROM"
                + " integrations.partner_referral WHERE reference = :reference")
        .param("reference", reference)
        .query((rs, n) -> referral(rs))
        .optional();
  }

  /** Locks a referral row (first, as everywhere in the referral model). */
  public void lockReferral(UUID tenant, UUID referral) {
    jdbc.sql(
            "SELECT id FROM integrations.partner_referral WHERE tenant_id = :tenant AND id ="
                + " :referral FOR UPDATE")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(UUID.class)
        .optional();
  }

  /** The stamp status, or {@code null} when none exists. */
  public String stampStatus(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT status FROM integrations.privacy_referral_stamp WHERE tenant_id = :tenant AND"
                + " referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** The stamped rules by data class. */
  public Map<String, StampRule> stampRules(UUID tenant, UUID referral) {
    Map<String, StampRule> rules = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT data_class, action, trigger_event, duration_days FROM"
                + " integrations.privacy_referral_stamp_rule WHERE tenant_id = :tenant AND"
                + " referral_id = :referral ORDER BY data_class")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(
            (rs, n) -> {
              rules.put(
                  rs.getString("data_class"),
                  new StampRule(
                      rs.getString("action"),
                      rs.getString("trigger_event"),
                      (Integer) rs.getObject("duration_days")));
              return 0;
            })
        .list();
    return rules;
  }

  /** When the referral closed, or {@code null} while open. */
  public Instant closedAt(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT max(occurred_at) FROM integrations.partner_referral_event WHERE tenant_id ="
                + " :tenant AND referral_id = :referral AND to_state IN ('DELIVERED',"
                + " 'DELIVERY_UNKNOWN', 'WITHDRAWN')")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant)
        .orElse(null);
  }

  /** The references of the active holds on a referral. */
  public List<String> activeHolds(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT reference FROM integrations.privacy_hold WHERE tenant_id = :tenant AND"
                + " referral_id = :referral AND state = 'ACTIVE' ORDER BY reference")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(String.class)
        .list();
  }

  /** The data classes already purged for a referral. */
  public Set<String> purgedClasses(UUID tenant, UUID referral) {
    return jdbc
        .sql(
            "SELECT data_class FROM integrations.privacy_purge_item WHERE tenant_id = :tenant AND"
                + " referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(String.class)
        .list()
        .stream()
        .collect(Collectors.toSet());
  }

  // ------------------------------------------------------------------------------------------
  // Purge execution
  // ------------------------------------------------------------------------------------------

  /** Records an EXECUTING purge. */
  public void insertPurge(UUID id, String reference, UUID preview, String actor, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_purge VALUES (:id, :reference, :preview, :actor,"
                + " 'EXECUTING', NULL, :now, NULL)")
        .param("id", id)
        .param("reference", reference)
        .param("preview", preview)
        .param("actor", actor)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Authorizes the following deletions for one purge (transaction-local). */
  public void purgeContext(UUID purge) {
    jdbc.sql("SELECT set_config('divalhr.privacy_purge', :id, true)")
        .param("id", purge.toString())
        .query(String.class)
        .single();
  }

  /** Records one authorized purge item. */
  public void insertPurgeItem(
      UUID purge, UUID tenant, UUID referral, String dataClass, String action, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_purge_item VALUES (:purge, :tenant, :referral,"
                + " :class, :action, :now)")
        .param("purge", purge)
        .param("tenant", tenant)
        .param("referral", referral)
        .param("class", dataClass)
        .param("action", action)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Records the value-free residue of a minimized class. */
  public void insertResidue(
      UUID purge, UUID tenant, UUID referral, String dataClass, String detailsJson, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_referral_residue VALUES (:purge, :tenant, :referral,"
                + " :class, CAST(:details AS jsonb), :now)")
        .param("purge", purge)
        .param("tenant", tenant)
        .param("referral", referral)
        .param("class", dataClass)
        .param("details", detailsJson)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** The number of disclosed field names (the only fact kept about a disclosure). */
  public int disclosureFieldCount(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT coalesce(max(cardinality(field_names)), 0) FROM"
                + " integrations.partner_referral_disclosure WHERE tenant_id = :tenant AND"
                + " referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(Integer.class)
        .single();
  }

  /** Deletes the disclosure; returns the rows removed. */
  public int deleteDisclosure(UUID tenant, UUID referral) {
    return jdbc.sql(
            "DELETE FROM integrations.partner_referral_disclosure WHERE tenant_id = :tenant AND"
                + " referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .update();
  }

  /** The attempt count and whether a receipt exists. */
  public int attemptCount(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT count(*) FROM integrations.partner_referral_attempt WHERE tenant_id = :tenant"
                + " AND referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(Integer.class)
        .single();
  }

  /** Whether a receipt exists. */
  public boolean receiptExists(UUID tenant, UUID referral) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM integrations.partner_referral_ack WHERE tenant_id ="
                    + " :tenant AND referral_id = :referral)")
            .param("tenant", tenant)
            .param("referral", referral)
            .query(Boolean.class)
            .single());
  }

  /** Deletes the receipt and the attempts; returns the rows removed. */
  public int deleteDiagnostics(UUID tenant, UUID referral) {
    int acks =
        jdbc.sql(
                "DELETE FROM integrations.partner_referral_ack WHERE tenant_id = :tenant AND"
                    + " referral_id = :referral")
            .param("tenant", tenant)
            .param("referral", referral)
            .update();
    int attempts =
        jdbc.sql(
                "DELETE FROM integrations.partner_referral_attempt WHERE tenant_id = :tenant AND"
                    + " referral_id = :referral")
            .param("tenant", tenant)
            .param("referral", referral)
            .update();
    return acks + attempts;
  }

  /** The consent version and language, or {@code null} when none remains. */
  public Map<String, String> consentFacts(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT consent_version, language FROM integrations.partner_referral_consent WHERE"
                + " tenant_id = :tenant AND referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(
            (rs, n) ->
                Map.of("consent_version", rs.getString(1), "language", rs.getString(2).trim()))
        .optional()
        .orElse(null);
  }

  /** Deletes the consent; returns the rows removed. */
  public int deleteConsent(UUID tenant, UUID referral) {
    return jdbc.sql(
            "DELETE FROM integrations.partner_referral_consent WHERE tenant_id = :tenant AND"
                + " referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .update();
  }

  /** The number of lifecycle events (kept; only counted in the residue). */
  public int eventCount(UUID tenant, UUID referral) {
    return jdbc.sql(
            "SELECT count(*) FROM integrations.partner_referral_event WHERE tenant_id = :tenant"
                + " AND referral_id = :referral")
        .param("tenant", tenant)
        .param("referral", referral)
        .query(Integer.class)
        .single();
  }

  /** Completes a purge. */
  public void completePurge(UUID purge, int items, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_purge SET state = 'COMPLETED', item_count = :items,"
                + " completed_at = :now WHERE id = :purge")
        .param("items", items)
        .param("now", Timestamp.from(now))
        .param("purge", purge)
        .update();
  }
}
