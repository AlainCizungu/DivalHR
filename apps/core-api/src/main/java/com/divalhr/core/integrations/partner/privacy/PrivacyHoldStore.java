package com.divalhr.core.integrations.partner.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Legal and operational holds on partner referrals (LAUNCH-001, L6). A hold is append-only: it is
 * placed and released, never edited or deleted. The referral row is locked first, as everywhere in
 * the referral model, so a hold and a purge item serialise on the same referral.
 */
@Repository
public class PrivacyHoldStore {

  /** A hold with the referral reference it protects. */
  public record HoldRow(
      UUID tenantId,
      UUID referralId,
      UUID id,
      String reference,
      String referralReference,
      String reason,
      LocalDate reviewDate,
      String externalReference,
      boolean noteRecorded,
      String state,
      Instant placedAt,
      Instant releasedAt) {}

  private static final String SELECT =
      "SELECT h.tenant_id, h.referral_id, h.id, h.reference, r.reference AS referral_reference,"
          + " h.reason, h.review_date, h.external_reference, (h.note_ciphertext IS NOT NULL OR"
          + " h.release_note_ciphertext IS NOT NULL) AS note_recorded, h.state, h.placed_at,"
          + " h.released_at FROM integrations.privacy_hold h JOIN integrations.partner_referral r"
          + " ON r.tenant_id = h.tenant_id AND r.id = h.referral_id";

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PrivacyHoldStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static HoldRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp released = rs.getTimestamp("released_at");
    return new HoldRow(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("referral_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getString("referral_reference"),
        rs.getString("reason"),
        rs.getObject("review_date", LocalDate.class),
        rs.getString("external_reference"),
        rs.getBoolean("note_recorded"),
        rs.getString("state"),
        rs.getTimestamp("placed_at").toInstant(),
        released == null ? null : released.toInstant());
  }

  /** A referral's id and tenant by reference, locked for update (platform scope). */
  public Optional<ReferralKey> lockReferral(String reference) {
    return jdbc.sql(
            "SELECT tenant_id, id FROM integrations.partner_referral WHERE reference ="
                + " :reference FOR UPDATE")
        .param("reference", reference)
        .query(
            (rs, n) ->
                new ReferralKey(
                    rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
        .optional();
  }

  /** The tenant and id of a referral. */
  public record ReferralKey(UUID tenantId, UUID id) {}

  /** Inserts an ACTIVE hold. */
  public void insert(
      ReferralKey referral,
      UUID id,
      String reference,
      String reason,
      byte[] sealedNote,
      String actor,
      LocalDate reviewDate,
      String externalReference,
      Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.privacy_hold (tenant_id, referral_id, id, reference, reason,"
                + " note_ciphertext, state, placed_by, placed_at, review_date, external_reference)"
                + " VALUES (:tenant, :referral, :id, :reference, :reason, :note, 'ACTIVE', :actor,"
                + " :now, :review, :external)")
        .param("tenant", referral.tenantId())
        .param("referral", referral.id())
        .param("id", id)
        .param("reference", reference)
        .param("reason", reason)
        .param("note", sealedNote)
        .param("actor", actor)
        .param("now", Timestamp.from(now))
        .param("review", reviewDate)
        .param("external", externalReference)
        .update();
  }

  /** Appends a hold event (sequence 1 PLACED, 2 RELEASED). */
  public void insertEvent(UUID hold, int sequence, String kind, Instant now) {
    jdbc.sql("INSERT INTO integrations.privacy_hold_event VALUES (:hold, :sequence, :kind, :now)")
        .param("hold", hold)
        .param("sequence", sequence)
        .param("kind", kind)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Releases an ACTIVE hold. */
  public void release(UUID hold, byte[] sealedNote, String actor, Instant now) {
    jdbc.sql(
            "UPDATE integrations.privacy_hold SET state = 'RELEASED', released_by = :actor,"
                + " released_at = :now, release_note_ciphertext = :note WHERE id = :hold")
        .param("actor", actor)
        .param("now", Timestamp.from(now))
        .param("note", sealedNote)
        .param("hold", hold)
        .update();
  }

  /** A hold of a referral by reference, locked for update. */
  public Optional<HoldRow> lockOf(UUID referralId, String reference) {
    jdbc.sql(
            "SELECT id FROM integrations.privacy_hold WHERE referral_id = :referral AND"
                + " reference = :reference FOR UPDATE")
        .param("referral", referralId)
        .param("reference", reference)
        .query(UUID.class)
        .optional();
    return jdbc.sql(SELECT + " WHERE h.referral_id = :referral AND h.reference = :reference")
        .param("referral", referralId)
        .param("reference", reference)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** A hold by id. */
  public Optional<HoldRow> byId(UUID id) {
    return jdbc.sql(SELECT + " WHERE h.id = :id")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** The holds protecting one referral, or all of an employee's referrals when null. */
  public List<HoldRow> holds(UUID tenant, UUID employee, UUID referral) {
    return jdbc.sql(
            SELECT
                + " WHERE h.tenant_id = :tenant AND r.employee_id = :employee AND"
                + " (CAST(:referral AS uuid) IS NULL OR h.referral_id = :referral)"
                + " ORDER BY h.placed_at DESC, h.id DESC LIMIT 20")
        .param("tenant", tenant)
        .param("employee", employee)
        .param("referral", referral)
        .query((rs, n) -> row(rs))
        .list();
  }

  /** Whether any hold is active on the referral. */
  public boolean activeOn(UUID tenant, UUID referral) {
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
}
