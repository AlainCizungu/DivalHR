package com.divalhr.core.payroll.internal;

import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage of the MVP-120C evidence-retention model (ADR 0042 amendment, V54): the versioned
 * organization policy, the previewed purge of delivery diagnostics and its confirmation record.
 * Every statement filters on the verified tenant. Only delivery-diagnostic attempt rows are ever
 * deleted, only through the confirmed purge and under the database guard; nothing else is touched.
 * No record here prints an identity, an amount or a digest of personal data.
 */
@Repository
public class JdbcSalaryAdvanceRetentionRepository {

  /** The most handoffs one preview may name. */
  public static final int MAX_PREVIEW_HANDOFFS = 500;

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcSalaryAdvanceRetentionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One policy version.
   *
   * @param version the version, starting at 1
   * @param retentionDays days, 30 to 3,650
   * @param effectiveAt when it took effect
   */
  public record PolicyRow(int version, int retentionDays, Instant effectiveAt) {}

  /**
   * One handoff whose delivery diagnostics are eligible.
   *
   * @param handoffId the handoff
   * @param attemptCount its attempt rows
   */
  public record Eligible(UUID handoffId, int attemptCount) {}

  /**
   * A stored preview.
   *
   * @param id the preview
   * @param policyVersion the policy version it was computed under
   * @param digest the server-generated digest of the exact eligible set
   * @param handoffCount handoffs named
   * @param attemptCount attempt rows named
   * @param createdAt creation instant
   * @param expiresAt expiry instant
   */
  public record PreviewRow(
      UUID id,
      int policyVersion,
      String digest,
      int handoffCount,
      int attemptCount,
      Instant createdAt,
      Instant expiresAt) {}

  /**
   * A confirmation record.
   *
   * @param handoffCount handoffs purged
   * @param attemptCount attempt rows deleted
   * @param confirmedAt confirmation instant
   */
  public record RecordRow(int handoffCount, int attemptCount, Instant confirmedAt) {}

  /**
   * The latest policy of the tenant, if one was ever configured (none means retention is disabled).
   *
   * @param tenant verified tenant
   * @return the latest version
   */
  public Optional<PolicyRow> latestPolicy(TenantId tenant) {
    return jdbc.sql(
            "SELECT version, retention_days, effective_at FROM"
                + " payroll.salary_advance_evidence_retention_policy WHERE tenant_id = :tenant"
                + " ORDER BY version DESC LIMIT 1")
        .param("tenant", tenant.value())
        .query(
            (rs, n) ->
                new PolicyRow(
                    rs.getInt("version"),
                    rs.getInt("retention_days"),
                    rs.getTimestamp("effective_at").toInstant()))
        .optional();
  }

  /**
   * Serialises policy changes of one tenant for the rest of the transaction.
   *
   * @param tenant verified tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockPolicies(TenantId tenant) {
    jdbc.sql(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:tenant AS text),"
                + " 120))) AS locked")
        .param("tenant", tenant.value().toString())
        .query(Integer.class)
        .single();
  }

  /**
   * Appends a new policy version.
   *
   * @param tenant verified tenant
   * @param version the next version
   * @param retentionDays days
   * @param actor the configuring administrator's subject
   * @param at server instant (effective immediately)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertPolicy(
      TenantId tenant, int version, int retentionDays, String actor, Instant at) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_evidence_retention_policy (tenant_id, version,"
                + " retention_days, effective_at, configured_by, created_at) VALUES (:tenant,"
                + " :version, :days, :at, :actor, :at)")
        .param("tenant", tenant.value())
        .param("version", version)
        .param("days", retentionDays)
        .param("at", Timestamp.from(at))
        .param("actor", actor)
        .update();
  }

  /**
   * The handoffs whose delivery diagnostics are eligible now: terminal, retention stamped and
   * elapsed, no DELIVERY_UNKNOWN history, attempts still present. Ordered by id so that the digest
   * is deterministic.
   *
   * @param tenant verified tenant
   * @param now the instant eligibility is evaluated at
   * @param limit the most rows
   * @return the eligible handoffs
   */
  public List<Eligible> eligible(TenantId tenant, Instant now, int limit) {
    return jdbc.sql(
            "SELECT k.handoff_id, count(a.attempt_number) AS attempts FROM"
                + " payroll.salary_advance_handoff_retention k JOIN"
                + " payroll.salary_advance_handoff h ON h.tenant_id = k.tenant_id AND h.id ="
                + " k.handoff_id JOIN payroll.salary_advance_handoff_attempt a ON a.tenant_id ="
                + " k.tenant_id AND a.handoff_id = k.handoff_id WHERE k.tenant_id = :tenant AND"
                + " k.retention_until <= :now AND (h.state = 'WITHDRAWN' OR h.review_state IN"
                + " ('DECLINED', 'CANCELLED_BY_PARTNER')) AND NOT EXISTS (SELECT 1 FROM"
                + " payroll.salary_advance_handoff_event e WHERE e.tenant_id = k.tenant_id AND"
                + " e.handoff_id = k.handoff_id AND 'DELIVERY_UNKNOWN' IN (e.from_state,"
                + " e.to_state)) GROUP BY k.handoff_id ORDER BY k.handoff_id LIMIT :limit")
        .param("tenant", tenant.value())
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(
            (rs, n) -> new Eligible(rs.getObject("handoff_id", UUID.class), rs.getInt("attempts")))
        .list();
  }

  /**
   * Stores a preview and its exact items.
   *
   * @param tenant verified tenant
   * @param preview the preview
   * @param actor the administrator's subject
   * @param items the exact eligible set
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertPreview(
      TenantId tenant, PreviewRow preview, String actor, List<Eligible> items) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_purge_preview (tenant_id, id, policy_version,"
                + " digest, handoff_count, attempt_count, created_by, created_at, expires_at)"
                + " VALUES (:tenant, :id, :policy, :digest, :handoffs, :attempts, :actor,"
                + " :created, :expires)")
        .param("tenant", tenant.value())
        .param("id", preview.id())
        .param("policy", preview.policyVersion())
        .param("digest", preview.digest())
        .param("handoffs", preview.handoffCount())
        .param("attempts", preview.attemptCount())
        .param("actor", actor)
        .param("created", Timestamp.from(preview.createdAt()))
        .param("expires", Timestamp.from(preview.expiresAt()))
        .update();
    for (Eligible item : items) {
      jdbc.sql(
              "INSERT INTO payroll.salary_advance_purge_preview_item (tenant_id, preview_id,"
                  + " handoff_id, attempt_count) VALUES (:tenant, :preview, :handoff, :attempts)")
          .param("tenant", tenant.value())
          .param("preview", preview.id())
          .param("handoff", item.handoffId())
          .param("attempts", (short) item.attemptCount())
          .update();
    }
  }

  /**
   * Locks a preview of the tenant (serialising concurrent confirmations).
   *
   * @param tenant verified tenant
   * @param id the preview
   * @return the preview, if it exists for this tenant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PreviewRow> lockPreview(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT id, policy_version, digest, handoff_count, attempt_count, created_at,"
                + " expires_at FROM payroll.salary_advance_purge_preview WHERE tenant_id ="
                + " :tenant AND id = :id FOR UPDATE")
        .param("tenant", tenant.value())
        .param("id", id)
        .query(
            (rs, n) ->
                new PreviewRow(
                    rs.getObject("id", UUID.class),
                    rs.getInt("policy_version"),
                    rs.getString("digest"),
                    rs.getInt("handoff_count"),
                    rs.getInt("attempt_count"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()))
        .optional();
  }

  /**
   * The exact items of a preview, ordered like the eligible set.
   *
   * @param tenant verified tenant
   * @param previewId the preview
   * @return the items
   */
  public List<Eligible> items(TenantId tenant, UUID previewId) {
    return jdbc.sql(
            "SELECT handoff_id, attempt_count FROM payroll.salary_advance_purge_preview_item"
                + " WHERE tenant_id = :tenant AND preview_id = :preview ORDER BY handoff_id")
        .param("tenant", tenant.value())
        .param("preview", previewId)
        .query(
            (rs, n) ->
                new Eligible(rs.getObject("handoff_id", UUID.class), rs.getInt("attempt_count")))
        .list();
  }

  /**
   * The confirmation record of a preview, if it was confirmed.
   *
   * @param tenant verified tenant
   * @param previewId the preview
   * @return the record
   */
  public Optional<RecordRow> record(TenantId tenant, UUID previewId) {
    return jdbc.sql(
            "SELECT handoff_count, attempt_count, confirmed_at FROM"
                + " payroll.salary_advance_purge_record WHERE tenant_id = :tenant AND preview_id"
                + " = :preview")
        .param("tenant", tenant.value())
        .param("preview", previewId)
        .query(
            (rs, n) ->
                new RecordRow(
                    rs.getInt("handoff_count"),
                    rs.getInt("attempt_count"),
                    rs.getTimestamp("confirmed_at").toInstant()))
        .optional();
  }

  /**
   * Records the confirmation (before any deletion: the database guard requires it).
   *
   * @param tenant verified tenant
   * @param previewId the preview
   * @param actor the confirming administrator's subject
   * @param handoffs handoffs named
   * @param attempts attempt rows named
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertRecord(
      TenantId tenant, UUID previewId, String actor, int handoffs, int attempts, Instant at) {
    jdbc.sql(
            "INSERT INTO payroll.salary_advance_purge_record (tenant_id, preview_id,"
                + " confirmed_by, confirmed_at, handoff_count, attempt_count) VALUES (:tenant,"
                + " :preview, :actor, :at, :handoffs, :attempts)")
        .param("tenant", tenant.value())
        .param("preview", previewId)
        .param("actor", actor)
        .param("at", Timestamp.from(at))
        .param("handoffs", handoffs)
        .param("attempts", attempts)
        .update();
  }

  /**
   * Deletes the delivery diagnostics of exactly the previewed handoffs, for this transaction only.
   * The database guard refuses any row not named by the confirmed record.
   *
   * @param tenant verified tenant
   * @param previewId the confirmed preview
   * @param handoffIds the previewed handoffs
   * @return the number of attempt rows deleted
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteAttempts(TenantId tenant, UUID previewId, List<UUID> handoffIds) {
    jdbc.sql("SELECT set_config('divalhr.salary_advance_purge_id', :id, true)")
        .param("id", previewId.toString())
        .query(String.class)
        .single();
    int deleted = 0;
    for (UUID handoff : handoffIds) {
      deleted +=
          jdbc.sql(
                  "DELETE FROM payroll.salary_advance_handoff_attempt WHERE tenant_id = :tenant"
                      + " AND handoff_id = :handoff")
              .param("tenant", tenant.value())
              .param("handoff", handoff)
              .update();
    }
    return deleted;
  }
}
