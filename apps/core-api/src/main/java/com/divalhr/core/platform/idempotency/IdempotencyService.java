package com.divalhr.core.platform.idempotency;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional idempotency backed by {@code platform.idempotency_record}.
 *
 * <p>{@link #reserve} inserts the key with {@code ON CONFLICT DO NOTHING} in the caller's
 * transaction. PostgreSQL makes a concurrent insert of the same key wait until the first
 * transaction ends: after a commit the stored record is replayed or rejected; after a rollback the
 * waiting insert succeeds. A deferred constraint trigger guarantees that no record commits without
 * its response.
 */
@Service
public class IdempotencyService {

  private final JdbcClient jdbc;
  private final IdempotencyProperties properties;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param jdbc JDBC client
   * @param properties retention settings
   */
  public IdempotencyService(JdbcClient jdbc, IdempotencyProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = Clock.systemUTC();
  }

  /**
   * Reserves the key or resolves an earlier request.
   *
   * @param scope operation, principal and key
   * @param fingerprint SHA-256 of the normalized command
   * @return proceed or replay
   * @throws ApiException {@link ErrorCode#IDEMPOTENCY_KEY_REUSED} for a different payload
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public IdempotencyDecision reserve(IdempotencyScope scope, String fingerprint) {
    Instant now = Instant.now(clock);
    int inserted =
        jdbc.sql(
                """
                INSERT INTO platform.idempotency_record
                  (operation, principal, idempotency_key, request_fingerprint, state,
                   created_at, expires_at)
                VALUES (:operation, :principal, :key, :fingerprint, 'PENDING', :now, :expiresAt)
                ON CONFLICT (operation, principal, idempotency_key) DO NOTHING
                """)
            .param("operation", scope.operation())
            .param("principal", scope.principal())
            .param("key", scope.key())
            .param("fingerprint", fingerprint)
            .param("now", java.sql.Timestamp.from(now))
            .param("expiresAt", java.sql.Timestamp.from(now.plus(properties.retention())))
            .update();
    if (inserted == 1) {
      return new IdempotencyDecision.Proceed();
    }
    Existing existing =
        find(scope)
            .orElseThrow(
                () -> new IllegalStateException("Idempotency record vanished after conflict"));
    if (!existing.fingerprint().equals(fingerprint)) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, Map.of());
    }
    return new IdempotencyDecision.Replay(existing.response());
  }

  /**
   * Stores the successful response in the same transaction as the business writes.
   *
   * @param scope operation, principal and key
   * @param response response to replay later
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(IdempotencyScope scope, StoredResponse response) {
    int updated =
        jdbc.sql(
                """
                UPDATE platform.idempotency_record
                   SET state = 'COMPLETED', response_status = :status,
                       response_body = CAST(:body AS jsonb), resource_id = :resourceId
                 WHERE operation = :operation AND principal = :principal
                   AND idempotency_key = :key AND state = 'PENDING'
                """)
            .param("status", response.status())
            .param("body", response.body())
            .param("resourceId", response.resourceId())
            .param("operation", scope.operation())
            .param("principal", scope.principal())
            .param("key", scope.key())
            .update();
    if (updated != 1) {
      throw new IllegalStateException("Idempotency record was not pending");
    }
  }

  private Optional<Existing> find(IdempotencyScope scope) {
    return jdbc.sql(
            """
            SELECT request_fingerprint, response_status, response_body::text AS body, resource_id
              FROM platform.idempotency_record
             WHERE operation = :operation AND principal = :principal AND idempotency_key = :key
            """)
        .param("operation", scope.operation())
        .param("principal", scope.principal())
        .param("key", scope.key())
        .query(
            (rs, rowNum) ->
                new Existing(
                    rs.getString("request_fingerprint"),
                    new StoredResponse(
                        rs.getInt("response_status"),
                        rs.getString("body"),
                        rs.getObject("resource_id", UUID.class))))
        .optional();
  }

  private record Existing(String fingerprint, StoredResponse response) {}
}
