package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.ReferralStore.EventRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cross-tenant, read-only access for platform operators (MVP-120F, D120F-5). Reads only what the
 * masked operational view needs; there is no write here. The sealed disclosure is returned solely
 * so the service can mask the contact fields: it never leaves the service.
 */
@Repository
public class PlatformReferralStore {

  /** One referral with what the operational view needs. */
  public record Row(
      UUID id,
      UUID tenantId,
      String reference,
      UUID partnerId,
      String state,
      int version,
      int attemptCount,
      Instant createdAt,
      Instant updatedAt,
      String productType,
      String partnerName,
      String primaryCategory,
      String lastOutcome,
      byte[] sealed) {}

  private static final String SELECT =
      """
      SELECT r.id, r.tenant_id, r.reference, r.partner_id, r.state, r.row_version, r.attempt_count,
             r.created_at, r.updated_at, x.product_type,
             coalesce(p.trading_name, p.legal_name) AS display, p.primary_category,
             (SELECT e.outcome FROM integrations.partner_referral_event e
               WHERE e.tenant_id = r.tenant_id AND e.referral_id = r.id
               ORDER BY e.sequence DESC LIMIT 1) AS last_outcome,
             d.values_ciphertext
        FROM integrations.partner_referral r
        JOIN integrations.partner_product x ON x.id = r.product_id
        JOIN integrations.partner p ON p.id = r.partner_id
        JOIN integrations.partner_referral_disclosure d
          ON d.tenant_id = r.tenant_id AND d.referral_id = r.id
       WHERE true
      """;

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PlatformReferralStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("reference"),
        rs.getObject("partner_id", UUID.class),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getInt("attempt_count"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("product_type"),
        rs.getString("display"),
        rs.getString("primary_category"),
        rs.getString("last_outcome"),
        rs.getBytes("values_ciphertext"));
  }

  /**
   * One referral by its reference.
   *
   * @param reference opaque referral reference
   * @return the row
   */
  public Optional<Row> byReference(String reference) {
    return jdbc.sql(SELECT + " AND r.reference = :reference")
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /**
   * One referral by id (cursor resolution).
   *
   * @param id referral id
   * @return the row
   */
  public Optional<Row> byId(UUID id) {
    return jdbc.sql(SELECT + " AND r.id = :id")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /**
   * A page of the queue, newest first.
   *
   * @param state state filter or {@code null}
   * @param category category filter or {@code null}
   * @param after keyset or {@code null}
   * @param limit rows
   * @return the rows
   */
  public List<Row> page(String state, String category, ReferralStore.Position after, int limit) {
    return jdbc.sql(
            SELECT
                + " AND (CAST(:state AS text) IS NULL OR r.state = :state)"
                + " AND (CAST(:category AS text) IS NULL OR p.primary_category = :category"
                + "      OR EXISTS (SELECT 1 FROM integrations.partner_category c"
                + "                  WHERE c.partner_id = p.id AND c.category = :category))"
                + " AND (CAST(:afterAt AS timestamptz) IS NULL"
                + "      OR (r.created_at, r.id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))"
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit")
        .param("state", state)
        .param("category", category)
        .param("afterAt", after == null ? null : Timestamp.from(after.createdAt()))
        .param("afterId", after == null ? null : after.id())
        .param("limit", limit)
        .query((rs, n) -> row(rs))
        .list();
  }

  /**
   * The history of a referral, in order.
   *
   * @param tenant the referral's tenant
   * @param referralId referral
   * @return the events
   */
  public List<EventRow> events(UUID tenant, UUID referralId) {
    return jdbc.sql(
            "SELECT sequence, from_state, to_state, actor_role, outcome, occurred_at"
                + " FROM integrations.partner_referral_event"
                + " WHERE tenant_id = :tenant AND referral_id = :id ORDER BY sequence")
        .param("tenant", tenant)
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
}
