package com.divalhr.core.integrations.internal;

import com.divalhr.core.integrations.domain.Delivery;
import com.divalhr.core.integrations.domain.DeliveryState;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.Webhook;
import com.divalhr.core.integrations.domain.WebhookStatus;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Storage of webhooks, their secret versions and their delivery history (V39). Every operation
 * takes the verified tenant; the schema's guard triggers refuse everything the service must not do.
 */
@Repository
public class JdbcWebhookRepository {

  private static final String COLUMNS =
      "id, name, url, event_types, status, config_version, secret_version, consecutive_failures,"
          + " last_success_at, last_failure_at, created_at, updated_at";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client
   */
  public JdbcWebhookRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Webhook webhook(ResultSet rs, int row) throws SQLException {
    return new Webhook(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("url"),
        List.of((String[]) rs.getArray("event_types").getArray()),
        WebhookStatus.valueOf(rs.getString("status")),
        rs.getInt("config_version"),
        rs.getInt("secret_version"),
        rs.getInt("consecutive_failures"),
        instant(rs, "last_success_at"),
        instant(rs, "last_failure_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Delivery delivery(ResultSet rs, int row) throws SQLException {
    String failure = rs.getString("last_failure_class");
    int status = rs.getInt("last_http_status");
    return new Delivery(
        rs.getObject("id", UUID.class),
        rs.getObject("webhook_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        DeliveryState.valueOf(rs.getString("state")),
        rs.getInt("attempt_count"),
        instant(rs, "created_at"),
        instant(rs, "last_attempt_at"),
        instant(rs, "next_attempt_at"),
        rs.wasNull() ? null : status,
        failure == null ? null : FailureClass.valueOf(failure));
  }

  /**
   * Inserts a webhook (the schema enforces the 20 limit) and its first secret.
   *
   * @param tenant verified tenant
   * @param id new id
   * @param name display name
   * @param url normalized URL
   * @param events subscribed events
   * @param actor creating subject
   * @param now instant
   * @param cipher first secret ciphertext
   */
  public void insert(
      TenantId tenant,
      UUID id,
      String name,
      String url,
      List<String> events,
      String actor,
      Instant now,
      byte[] cipher) {
    jdbc.sql(
            """
            INSERT INTO integrations.webhook
              (tenant_id, id, name, url, event_types, status, config_version, secret_version,
               consecutive_failures, created_at, created_by, updated_at, updated_by)
            VALUES (:tenant, :id, :name, :url, CAST(:events AS text[]), 'ACTIVE', 1, 1, 0,
                    :now, :actor, :now, :actor)
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("name", name)
        .param("url", url)
        .param("events", "{" + String.join(",", events) + "}")
        .param("now", Timestamp.from(now))
        .param("actor", actor)
        .update();
    insertSecret(tenant, id, 1, cipher, now);
  }

  private void insertSecret(
      TenantId tenant, UUID webhook, int version, byte[] cipher, Instant now) {
    jdbc.sql(
            """
            INSERT INTO integrations.webhook_secret
              (tenant_id, webhook_id, version, ciphertext, created_at)
            VALUES (:tenant, :webhook, :version, :cipher, :now)
            """)
        .param("tenant", tenant.value())
        .param("webhook", webhook)
        .param("version", version)
        .param("cipher", cipher)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Finds a webhook.
   *
   * @param tenant verified tenant
   * @param id webhook id
   * @param lock whether to lock the row for the transaction
   * @return the webhook, or empty when missing or foreign
   */
  public Optional<Webhook> find(TenantId tenant, UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM integrations.webhook WHERE tenant_id = :tenant AND id = :id"
                + (lock ? " FOR UPDATE" : ""))
        .param("tenant", tenant.value())
        .param("id", id)
        .query(JdbcWebhookRepository::webhook)
        .optional();
  }

  /**
   * One page, newest first.
   *
   * @param tenant verified tenant
   * @param afterCreated keyset position instant, or null for the first page
   * @param afterId keyset position id
   * @param limit maximum rows
   * @return the rows
   */
  public List<Webhook> page(TenantId tenant, Instant afterCreated, UUID afterId, int limit) {
    String where = afterCreated == null ? "" : " AND (created_at, id) < (:afterCreated, :afterId)";
    var spec =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM integrations.webhook WHERE tenant_id = :tenant"
                    + where
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("tenant", tenant.value())
            .param("limit", limit);
    if (afterCreated != null) {
      spec = spec.param("afterCreated", Timestamp.from(afterCreated)).param("afterId", afterId);
    }
    return spec.query(JdbcWebhookRepository::webhook).list();
  }

  /**
   * Updates the configuration when the version matches.
   *
   * @param tenant verified tenant
   * @param id webhook id
   * @param expectedVersion version the caller saw
   * @param name new name
   * @param url new URL
   * @param events new events
   * @param actor subject
   * @param now instant
   * @return whether a row was updated
   */
  public boolean updateConfiguration(
      TenantId tenant,
      UUID id,
      int expectedVersion,
      String name,
      String url,
      List<String> events,
      String actor,
      Instant now) {
    return jdbc.sql(
                """
                UPDATE integrations.webhook
                   SET name = :name, url = :url, event_types = CAST(:events AS text[]),
                       config_version = config_version + 1, updated_at = :now, updated_by = :actor
                 WHERE tenant_id = :tenant AND id = :id AND config_version = :expected
                """)
            .param("tenant", tenant.value())
            .param("id", id)
            .param("expected", expectedVersion)
            .param("name", name)
            .param("url", url)
            .param("events", "{" + String.join(",", events) + "}")
            .param("now", Timestamp.from(now))
            .param("actor", actor)
            .update()
        == 1;
  }

  /**
   * Changes the status; re-enabling resets the failure counter (older failures are not replayed).
   *
   * @param tenant verified tenant
   * @param id webhook id
   * @param status the new status
   * @param actor subject
   * @param now instant
   */
  public void setStatus(TenantId tenant, UUID id, WebhookStatus status, String actor, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.webhook
               SET status = :status, config_version = config_version + 1,
                   consecutive_failures = CASE WHEN :status = 'ACTIVE' THEN 0
                                               ELSE consecutive_failures END,
                   updated_at = :now, updated_by = :actor
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("status", status.name())
        .param("now", Timestamp.from(now))
        .param("actor", actor)
        .update();
  }

  /**
   * Rotates the secret: the current version expires at the given instant and the new one becomes
   * current.
   *
   * @param tenant verified tenant
   * @param id webhook id
   * @param newVersion the new version number
   * @param cipher the new ciphertext
   * @param previousExpiry when the previous version stops being valid
   * @param actor subject
   * @param now instant
   */
  public void rotateSecret(
      TenantId tenant,
      UUID id,
      int newVersion,
      byte[] cipher,
      Instant previousExpiry,
      String actor,
      Instant now) {
    // Any still-unerased previous version must be gone before another rotation.
    jdbc.sql(
            """
            UPDATE integrations.webhook_secret
               SET ciphertext = NULL, erased_at = GREATEST(:now, expires_at)
             WHERE tenant_id = :tenant AND webhook_id = :id
               AND expires_at IS NOT NULL AND ciphertext IS NOT NULL
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            UPDATE integrations.webhook_secret SET expires_at = :expiry
             WHERE tenant_id = :tenant AND webhook_id = :id AND expires_at IS NULL
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("expiry", Timestamp.from(previousExpiry))
        .update();
    insertSecret(tenant, id, newVersion, cipher, now);
    jdbc.sql(
            """
            UPDATE integrations.webhook
               SET secret_version = :version, config_version = config_version + 1,
                   updated_at = :now, updated_by = :actor
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("version", newVersion)
        .param("now", Timestamp.from(now))
        .param("actor", actor)
        .update();
  }

  /**
   * A stored secret version.
   *
   * @param version version number
   * @param ciphertext the sealed secret
   * @param expiresAt when it stops being valid, or null for the current one
   */
  public record StoredSecret(int version, byte[] ciphertext, Instant expiresAt) {}

  /**
   * The secret versions that may sign at an instant: the current one first, then a previous one
   * that has not expired.
   *
   * @param tenant verified tenant
   * @param id webhook id
   * @param at the instant
   * @return one or two versions
   */
  public List<StoredSecret> signingSecrets(TenantId tenant, UUID id, Instant at) {
    return jdbc.sql(
            """
            SELECT version, ciphertext, expires_at FROM integrations.webhook_secret
             WHERE tenant_id = :tenant AND webhook_id = :id AND ciphertext IS NOT NULL
               AND (expires_at IS NULL OR expires_at > :at)
             ORDER BY version DESC
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("at", Timestamp.from(at))
        .query(
            (rs, n) ->
                new StoredSecret(
                    rs.getInt("version"), rs.getBytes("ciphertext"), instant(rs, "expires_at")))
        .list();
  }

  /**
   * Erases the ciphertext of expired previous versions (bounded cleanup).
   *
   * @param limit maximum rows
   * @param now instant
   * @return rows erased
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background secret cleanup")
  public int eraseExpiredSecrets(int limit, Instant now) {
    return jdbc.sql(
            """
            UPDATE integrations.webhook_secret s
               SET ciphertext = NULL, erased_at = :now
              FROM (SELECT tenant_id, webhook_id, version FROM integrations.webhook_secret
                     WHERE expires_at IS NOT NULL AND expires_at <= :now AND ciphertext IS NOT NULL
                     ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED) due
             WHERE s.tenant_id = due.tenant_id AND s.webhook_id = due.webhook_id
               AND s.version = due.version
            """)
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .update();
  }

  /**
   * Counts a webhook's test deliveries created within the hour up to an instant (it locks the
   * webhook row first, so simultaneous commands cannot both pass).
   *
   * @param tenant verified tenant
   * @param webhook webhook id
   * @param now instant
   * @return the count
   */
  public int countTestDeliveriesLastHour(TenantId tenant, UUID webhook, Instant now) {
    return jdbc.sql(
            """
            SELECT count(*) FROM integrations.delivery
             WHERE tenant_id = :tenant AND webhook_id = :webhook AND kind = 'TEST'
               AND created_at > :since AND created_at <= :now
            """)
        .param("tenant", tenant.value())
        .param("webhook", webhook)
        .param("since", Timestamp.from(now.minusSeconds(3600)))
        .param("now", Timestamp.from(now))
        .query(Integer.class)
        .single();
  }

  /**
   * Inserts a pending delivery.
   *
   * @param tenant verified tenant
   * @param id delivery id
   * @param webhook webhook id
   * @param eventId event id
   * @param eventType public event type
   * @param kind EVENT or TEST
   * @param payload canonical payload bytes
   * @param now instant
   */
  public void insertDelivery(
      TenantId tenant,
      UUID id,
      UUID webhook,
      UUID eventId,
      String eventType,
      String kind,
      byte[] payload,
      Instant now) {
    jdbc.sql(
            """
            INSERT INTO integrations.delivery
              (tenant_id, id, webhook_id, event_id, event_type, kind, payload, state,
               attempt_count, created_at, next_attempt_at)
            VALUES (:tenant, :id, :webhook, :eventId, :eventType, :kind, :payload, 'PENDING', 0,
                    :now, :now)
            """)
        .param("tenant", tenant.value())
        .param("id", id)
        .param("webhook", webhook)
        .param("eventId", eventId)
        .param("eventType", eventType)
        .param("kind", kind)
        .param("payload", payload)
        .param("now", Timestamp.from(now))
        .update();
  }

  private static final String DELIVERY_COLUMNS =
      "id, webhook_id, event_id, event_type, state, attempt_count, created_at, last_attempt_at,"
          + " next_attempt_at, last_http_status, last_failure_class";

  /**
   * One page of history, newest first.
   *
   * @param tenant verified tenant
   * @param webhook webhook id
   * @param afterCreated keyset position instant, or null
   * @param afterId keyset position id
   * @param limit maximum rows
   * @return the rows
   */
  public List<Delivery> deliveries(
      TenantId tenant, UUID webhook, Instant afterCreated, UUID afterId, int limit) {
    String where = afterCreated == null ? "" : " AND (created_at, id) < (:afterCreated, :afterId)";
    var spec =
        jdbc.sql(
                "SELECT "
                    + DELIVERY_COLUMNS
                    + " FROM integrations.delivery WHERE tenant_id = :tenant AND webhook_id ="
                    + " :webhook"
                    + where
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("tenant", tenant.value())
            .param("webhook", webhook)
            .param("limit", limit);
    if (afterCreated != null) {
      spec = spec.param("afterCreated", Timestamp.from(afterCreated)).param("afterId", afterId);
    }
    return spec.query(JdbcWebhookRepository::delivery).list();
  }

  /**
   * One delivery of a webhook.
   *
   * @param tenant verified tenant
   * @param webhook webhook id
   * @param id delivery id
   * @return the delivery, or empty
   */
  public Optional<Delivery> delivery(TenantId tenant, UUID webhook, UUID id) {
    return jdbc.sql(
            "SELECT "
                + DELIVERY_COLUMNS
                + " FROM integrations.delivery WHERE tenant_id = :tenant AND webhook_id = :webhook"
                + " AND id = :id")
        .param("tenant", tenant.value())
        .param("webhook", webhook)
        .param("id", id)
        .query(JdbcWebhookRepository::delivery)
        .optional();
  }

  /**
   * One attempt of a delivery as shown in detail.
   *
   * @param number attempt number
   * @param startedAt start
   * @param httpStatus exact status when a response arrived
   * @param failureClass closed class, null on success
   * @param latencyMillis wall time
   */
  public record AttemptView(
      int number,
      Instant startedAt,
      Integer httpStatus,
      FailureClass failureClass,
      int latencyMillis) {}

  /**
   * The attempts of a delivery.
   *
   * @param tenant verified tenant
   * @param delivery delivery id
   * @return attempts in order
   */
  public List<AttemptView> attempts(TenantId tenant, UUID delivery) {
    return jdbc.sql(
            """
            SELECT number, started_at, http_status, failure_class, latency_ms
              FROM integrations.delivery_attempt
             WHERE tenant_id = :tenant AND delivery_id = :delivery ORDER BY number
            """)
        .param("tenant", tenant.value())
        .param("delivery", delivery)
        .query(
            (rs, n) -> {
              String failure = rs.getString("failure_class");
              int status = rs.getInt("http_status");
              return new AttemptView(
                  rs.getInt("number"),
                  instant(rs, "started_at"),
                  rs.wasNull() ? null : status,
                  failure == null ? null : FailureClass.valueOf(failure),
                  rs.getInt("latency_ms"));
            })
        .list();
  }

  /**
   * Counts a webhook's deliveries by state (bounded audit metadata).
   *
   * @param tenant verified tenant
   * @param webhook webhook id
   * @return counts for every state
   */
  public Map<DeliveryState, Integer> stateCounts(TenantId tenant, UUID webhook) {
    Map<DeliveryState, Integer> counts = new EnumMap<>(DeliveryState.class);
    for (DeliveryState state : DeliveryState.values()) {
      counts.put(state, 0);
    }
    jdbc.sql(
            """
            SELECT state, count(*) AS n FROM integrations.delivery
             WHERE tenant_id = :tenant AND webhook_id = :webhook GROUP BY state
            """)
        .param("tenant", tenant.value())
        .param("webhook", webhook)
        .query((rs, n) -> counts.put(DeliveryState.valueOf(rs.getString("state")), rs.getInt("n")))
        .list();
    return counts;
  }

  /**
   * Cancels the open deliveries of a webhook that was stopped.
   *
   * @param tenant verified tenant
   * @param webhook webhook id
   * @param now instant
   * @return deliveries cancelled
   */
  public int cancelOpenDeliveries(TenantId tenant, UUID webhook, Instant now) {
    return jdbc.sql(
            """
            UPDATE integrations.delivery
               SET state = 'CANCELLED', next_attempt_at = NULL, completed_at = :now,
                   claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND webhook_id = :webhook
               AND state IN ('PENDING', 'RETRYING')
            """)
        .param("tenant", tenant.value())
        .param("webhook", webhook)
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Ids of ACTIVE webhooks of a tenant subscribed to a public event that existed when it was
   * recorded.
   *
   * @param tenant the event's tenant (taken from the committed event, not a request)
   * @param eventType public event type
   * @param recordedAt when the event was recorded
   * @return webhook ids
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public List<UUID> subscribers(TenantId tenant, String eventType, Instant recordedAt) {
    return jdbc.sql(
            """
            SELECT id FROM integrations.webhook
             WHERE tenant_id = :tenant AND status = 'ACTIVE' AND :type = ANY (event_types)
               AND created_at <= :recordedAt
            """)
        .param("tenant", tenant.value())
        .param("type", eventType)
        .param("recordedAt", Timestamp.from(recordedAt))
        .query(UUID.class)
        .list();
  }

  /**
   * Reads and locks the fan-out position.
   *
   * @return the watermark and the last event id
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public FanoutPosition lockFanout() {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('integrations.fanout', 0))")
        .query()
        .listOfRows();
    return jdbc.sql(
            "SELECT watermark, watermark_event_id FROM integrations.fanout_state WHERE id = 1")
        .query(
            (rs, n) ->
                new FanoutPosition(
                    instant(rs, "watermark"), rs.getObject("watermark_event_id", UUID.class)))
        .single();
  }

  /**
   * The fan-out read position.
   *
   * @param watermark created_at of the last processed event
   * @param eventId id of the last processed event
   */
  public record FanoutPosition(Instant watermark, UUID eventId) {}

  /**
   * Moves the fan-out position.
   *
   * @param position the new position
   * @param now instant
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public void saveFanout(FanoutPosition position, Instant now) {
    jdbc.sql(
            "UPDATE integrations.fanout_state SET watermark = :w, watermark_event_id = :e,"
                + " updated_at = :now WHERE id = 1")
        .param("w", Timestamp.from(position.watermark()))
        .param("e", position.eventId())
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * Which of the given events were already fanned out.
   *
   * @param ids event ids
   * @return those already recorded
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public java.util.Set<UUID> alreadyFannedOut(List<UUID> ids) {
    if (ids.isEmpty()) {
      return java.util.Set.of();
    }
    return new java.util.HashSet<>(
        jdbc.sql("SELECT event_id FROM integrations.fanout_event WHERE event_id IN (:ids)")
            .param("ids", ids)
            .query(UUID.class)
            .list());
  }

  /**
   * Records an event as fanned out.
   *
   * @param eventId event id
   * @param tenant tenant
   * @param now instant
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background event fan-out")
  public void markFannedOut(UUID eventId, TenantId tenant, Instant now) {
    jdbc.sql(
            "INSERT INTO integrations.fanout_event (event_id, tenant_id, fanned_at) VALUES (:e, :t,"
                + " :now) ON CONFLICT DO NOTHING")
        .param("e", eventId)
        .param("t", tenant.value())
        .param("now", Timestamp.from(now))
        .update();
  }

  /**
   * A delivery claimed by a worker.
   *
   * @param tenant owning tenant
   * @param id delivery id
   * @param webhookId webhook
   * @param eventType public event type
   * @param eventId event id
   * @param payload exact body bytes
   * @param attemptCount attempts already made
   * @param claimToken the lease token
   */
  public record Claimed(
      TenantId tenant,
      UUID id,
      UUID webhookId,
      String eventType,
      UUID eventId,
      byte[] payload,
      int attemptCount,
      UUID claimToken) {}

  /**
   * Claims due deliveries for this worker (row locks with SKIP LOCKED, so instances never share
   * one; a crashed worker's lease simply expires).
   *
   * @param now instant
   * @param lease how long the claim lasts
   * @param limit maximum rows
   * @return the claimed deliveries
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public List<Claimed> claimDue(Instant now, java.time.Duration lease, int limit) {
    UUID token = UUID.randomUUID();
    return jdbc.sql(
            """
            WITH due AS (
              SELECT tenant_id, id FROM integrations.delivery
               WHERE state IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now
                 AND (claimed_until IS NULL OR claimed_until < :now)
               ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
            UPDATE integrations.delivery d
               SET claim_token = :token, claimed_until = :until
              FROM due
             WHERE d.tenant_id = due.tenant_id AND d.id = due.id
            RETURNING d.tenant_id, d.id, d.webhook_id, d.event_type, d.event_id, d.payload,
                      d.attempt_count
            """)
        .param("now", Timestamp.from(now))
        .param("until", Timestamp.from(now.plus(lease)))
        .param("limit", limit)
        .param("token", token)
        .query(
            (rs, n) ->
                new Claimed(
                    new TenantId(rs.getObject("tenant_id", UUID.class)),
                    rs.getObject("id", UUID.class),
                    rs.getObject("webhook_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getObject("event_id", UUID.class),
                    rs.getBytes("payload"),
                    rs.getInt("attempt_count"),
                    token))
        .list();
  }

  /**
   * Records one attempt's result under the claim; returns false when the claim was lost.
   *
   * @param claim the claimed delivery
   * @param startedAt attempt start
   * @param finishedAt attempt end
   * @param httpStatus exact status when a response arrived
   * @param failure closed class, null on success
   * @param latencyMillis wall time
   * @param nextAttemptAt when to retry, or null when the delivery is finished
   * @param terminalFailure whether this failure ends the delivery
   * @return whether the result was recorded
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public boolean recordAttempt(
      Claimed claim,
      Instant startedAt,
      Instant finishedAt,
      Integer httpStatus,
      FailureClass failure,
      int latencyMillis,
      Instant nextAttemptAt,
      boolean terminalFailure) {
    int number = claim.attemptCount() + 1;
    List<UUID> locked =
        jdbc.sql(
                """
                SELECT id FROM integrations.delivery
                 WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                   AND state IN ('PENDING', 'RETRYING') FOR UPDATE
                """)
            .param("tenant", claim.tenant().value())
            .param("id", claim.id())
            .param("token", claim.claimToken())
            .query(UUID.class)
            .list();
    if (locked.size() != 1) {
      return false;
    }
    jdbc.sql(
            """
            INSERT INTO integrations.delivery_attempt
              (tenant_id, delivery_id, number, started_at, finished_at, http_status, failure_class,
               latency_ms)
            VALUES (:tenant, :id, :number, :started, :finished, :status, :failure, :latency)
            """)
        .param("tenant", claim.tenant().value())
        .param("id", claim.id())
        .param("number", number)
        .param("started", Timestamp.from(startedAt))
        .param("finished", Timestamp.from(finishedAt))
        .param("status", httpStatus)
        .param("failure", failure == null ? null : failure.name())
        .param("latency", latencyMillis)
        .update();
    String state = failure == null ? "SUCCEEDED" : terminalFailure ? "FAILED" : "RETRYING";
    jdbc.sql(
            """
            UPDATE integrations.delivery
               SET state = :state, attempt_count = :number, last_attempt_at = :finished,
                   last_http_status = :status, last_failure_class = :failure,
                   next_attempt_at = :next,
                   completed_at = CASE WHEN :state = 'RETRYING' THEN NULL ELSE :finished END,
                   claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", claim.tenant().value())
        .param("id", claim.id())
        .param("state", state)
        .param("number", number)
        .param("finished", Timestamp.from(finishedAt))
        .param("status", httpStatus)
        .param("failure", failure == null ? null : failure.name())
        .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
        .update();
    return true;
  }

  /**
   * Applies a finished delivery to its webhook's projection: a success clears the counter; a
   * terminal failure moves it by one and suspends the webhook at ten, cancelling its open
   * deliveries.
   *
   * @param tenant owning tenant
   * @param webhook webhook id
   * @param success whether the delivery succeeded
   * @param now instant
   * @return true when this call suspended the webhook
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public boolean applyOutcome(TenantId tenant, UUID webhook, boolean success, Instant now) {
    if (success) {
      jdbc.sql(
              """
              UPDATE integrations.webhook SET consecutive_failures = 0, last_success_at = :now
               WHERE tenant_id = :tenant AND id = :id
              """)
          .param("tenant", tenant.value())
          .param("id", webhook)
          .param("now", Timestamp.from(now))
          .update();
      return false;
    }
    Optional<Webhook> locked = find(tenant, webhook, true);
    if (locked.isEmpty() || locked.get().status() != WebhookStatus.ACTIVE) {
      return false;
    }
    boolean suspend = locked.get().consecutiveFailures() + 1 >= 10;
    jdbc.sql(
            """
            UPDATE integrations.webhook
               SET consecutive_failures = consecutive_failures + 1, last_failure_at = :now,
                   status = CASE WHEN :suspend THEN 'SUSPENDED' ELSE status END,
                   config_version = CASE WHEN :suspend THEN config_version + 1 ELSE config_version END,
                   updated_at = :now, updated_by = 'system'
             WHERE tenant_id = :tenant AND id = :id
            """)
        .param("tenant", tenant.value())
        .param("id", webhook)
        .param("now", Timestamp.from(now))
        .param("suspend", suspend)
        .update();
    if (suspend) {
      cancelOpenDeliveries(tenant, webhook, now);
    }
    return suspend;
  }

  /**
   * Cancels a claimed delivery whose webhook is no longer active.
   *
   * @param claim the claimed delivery
   * @param now instant
   */
  @com.divalhr.core.platform.tenancy.CrossTenantAccess("background delivery worker")
  public void cancel(Claimed claim, Instant now) {
    jdbc.sql(
            """
            UPDATE integrations.delivery
               SET state = 'CANCELLED', next_attempt_at = NULL, completed_at = :now,
                   claim_token = NULL, claimed_until = NULL
             WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
               AND state IN ('PENDING', 'RETRYING')
            """)
        .param("tenant", claim.tenant().value())
        .param("id", claim.id())
        .param("token", claim.claimToken())
        .param("now", Timestamp.from(now))
        .update();
  }
}
