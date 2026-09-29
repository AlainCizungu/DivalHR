package com.divalhr.core.platform.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends events to {@code platform.outbox_event} in the caller's transaction. The stored {@code
 * envelope} is the exact document a future publisher will emit; nothing is published yet.
 */
@Component
public class OutboxWriter {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * Creates the writer.
   *
   * @param jdbc JDBC client
   * @param json JSON mapper
   */
  public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Appends an event.
   *
   * @param envelope event envelope
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(EventEnvelope envelope) {
    jdbc.sql(
            """
            INSERT INTO platform.outbox_event
              (event_id, event_type, tenant_id, envelope, created_at)
            VALUES (:eventId, :eventType, :tenantId, CAST(:envelope AS jsonb), now())
            """)
        .param("eventId", UUID.fromString(envelope.eventId()))
        .param("eventType", envelope.eventType())
        .param("tenantId", envelope.tenantUuid())
        .param("envelope", json.writeValueAsString(envelope))
        .update();
  }
}
