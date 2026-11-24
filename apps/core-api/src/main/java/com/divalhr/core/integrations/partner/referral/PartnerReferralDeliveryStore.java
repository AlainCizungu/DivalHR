package com.divalhr.core.integrations.partner.referral;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Delivery-side storage of a referral (MVP-120F, D120F-6/7). Every method runs inside the caller's
 * transaction and locks the referral row first; the database guards enforce the closed lifecycle,
 * so a claim, a resolution and a withdrawal serialise on that one lock and can never both win.
 */
@Repository
public class PartnerReferralDeliveryStore {

  /** A locked referral. */
  public record Locked(
      UUID tenantId,
      UUID id,
      String reference,
      String state,
      int version,
      int attemptCount,
      boolean leased,
      Instant updatedAt) {}

  private static final String LOCK =
      "SELECT tenant_id, id, reference, state, row_version, attempt_count,"
          + " (lease_until IS NOT NULL) AS leased, updated_at"
          + " FROM integrations.partner_referral r WHERE partner_code = 'SYNTHETIC-TEST'";

  private final JdbcClient jdbc;

  /**
   * Creates the store.
   *
   * @param jdbc JDBC client
   */
  public PartnerReferralDeliveryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Locked locked(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Locked(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getString("reference"),
        rs.getString("state"),
        rs.getInt("row_version"),
        rs.getInt("attempt_count"),
        rs.getBoolean("leased"),
        rs.getTimestamp("updated_at").toInstant());
  }

  /**
   * Locks the next referral that is due (skipping rows another worker holds).
   *
   * @param now current instant
   * @return the locked referral
   */
  public Optional<Locked> lockNextDue(Instant now) {
    return jdbc.sql(
            LOCK
                + " AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now"
                + " AND (state = 'SUBMITTED' OR (state = 'DELIVERY_PENDING' AND lease_until IS NULL))"
                + " ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .query((rs, n) -> locked(rs))
        .optional();
  }

  /**
   * Locks the next referral whose claimed attempt outlived its lease.
   *
   * @param now current instant
   * @return the locked referral
   */
  public Optional<Locked> lockNextExpired(Instant now) {
    return jdbc.sql(
            LOCK
                + " AND state = 'DELIVERY_PENDING' AND lease_until IS NOT NULL AND lease_until < :now"
                + " ORDER BY lease_until, id LIMIT 1 FOR UPDATE SKIP LOCKED")
        .param("now", Timestamp.from(now))
        .query((rs, n) -> locked(rs))
        .optional();
  }

  /**
   * Locks one referral.
   *
   * @param tenant the referral's tenant
   * @param id referral
   * @return the locked referral
   */
  public Optional<Locked> lock(UUID tenant, UUID id) {
    return jdbc.sql(LOCK + " AND tenant_id = :tenant AND id = :id FOR UPDATE")
        .param("tenant", tenant)
        .param("id", id)
        .query((rs, n) -> locked(rs))
        .optional();
  }

  /**
   * The sealed disclosure.
   *
   * @param tenant the referral's tenant
   * @param id referral
   * @return the ciphertext
   */
  public byte[] sealed(UUID tenant, UUID id) {
    return jdbc.sql(
            "SELECT values_ciphertext FROM integrations.partner_referral_disclosure"
                + " WHERE tenant_id = :tenant AND referral_id = :id")
        .param("tenant", tenant)
        .param("id", id)
        .query(byte[].class)
        .single();
  }

  /**
   * Claims a delivery attempt: a lease, one more attempt, no due time.
   *
   * @param row the locked referral
   * @param lease lease end
   * @param at server instant
   */
  public void claim(Locked row, Instant lease, Instant at) {
    jdbc.sql(
            "UPDATE integrations.partner_referral SET state = 'DELIVERY_PENDING',"
                + " lease_until = :lease, next_attempt_at = NULL, attempt_count = attempt_count + 1,"
                + " row_version = row_version + 1, updated_at = :at"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("lease", Timestamp.from(lease))
        .param("at", Timestamp.from(at))
        .param("tenant", row.tenantId())
        .param("id", row.id())
        .update();
  }

  /**
   * Moves a claimed referral on: the lease is released, the attempt count is unchanged.
   *
   * @param row the locked referral
   * @param state the new state
   * @param nextAttempt the retry due time, or {@code null}
   * @param at server instant
   */
  public void move(Locked row, String state, Instant nextAttempt, Instant at) {
    jdbc.sql(
            "UPDATE integrations.partner_referral SET state = :state, lease_until = NULL,"
                + " next_attempt_at = :next, row_version = row_version + 1, updated_at = :at"
                + " WHERE tenant_id = :tenant AND id = :id")
        .param("state", state)
        .param("next", nextAttempt == null ? null : Timestamp.from(nextAttempt))
        .param("at", Timestamp.from(at))
        .param("tenant", row.tenantId())
        .param("id", row.id())
        .update();
  }

  /**
   * Appends a finished attempt.
   *
   * @param row the locked referral
   * @param started claim instant
   * @param finished finish instant
   * @param outcome closed outcome
   */
  public void insertAttempt(Locked row, Instant started, Instant finished, String outcome) {
    jdbc.sql(
            "INSERT INTO integrations.partner_referral_attempt"
                + " (tenant_id, referral_id, attempt_number, started_at, finished_at, outcome)"
                + " VALUES (:tenant, :id, :number, :started, :finished, :outcome)")
        .param("tenant", row.tenantId())
        .param("id", row.id())
        .param("number", row.attemptCount())
        .param("started", Timestamp.from(started))
        .param("finished", Timestamp.from(finished))
        .param("outcome", outcome)
        .update();
  }

  /**
   * Records the provider receipt.
   *
   * @param row the locked referral
   * @param providerReference the provider's receipt reference
   * @param at server instant
   */
  public void insertAck(Locked row, String providerReference, Instant at) {
    jdbc.sql(
            "INSERT INTO integrations.partner_referral_ack"
                + " (tenant_id, referral_id, provider_reference, acknowledged_at)"
                + " VALUES (:tenant, :id, :reference, :at)")
        .param("tenant", row.tenantId())
        .param("id", row.id())
        .param("reference", providerReference)
        .param("at", Timestamp.from(at))
        .update();
  }

  /**
   * Appends a system history event.
   *
   * @param row the locked referral
   * @param sequence the new sequence (the new row version)
   * @param from previous state
   * @param to new state
   * @param outcome closed outcome or {@code null}
   * @param at server instant
   */
  public void insertEvent(
      Locked row, int sequence, String from, String to, String outcome, Instant at) {
    jdbc.sql(
            "INSERT INTO integrations.partner_referral_event"
                + " (tenant_id, referral_id, sequence, from_state, to_state, actor_role, outcome, occurred_at)"
                + " VALUES (:tenant, :id, :sequence, :from, :to, 'SYSTEM', :outcome, :at)")
        .param("tenant", row.tenantId())
        .param("id", row.id())
        .param("sequence", sequence)
        .param("from", from)
        .param("to", to)
        .param("outcome", outcome)
        .param("at", Timestamp.from(at))
        .update();
  }
}
