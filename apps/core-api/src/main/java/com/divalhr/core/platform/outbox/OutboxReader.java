package com.divalhr.core.platform.outbox;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The platform port through which a consuming module reads committed business events (MVP-110, ADR
 * 0028). It returns only the routing facts a consumer needs to build its own, separate public
 * payload (event id, type, tenant, subject, event time, commit-ordered position) and never the
 * internal {@code data}. It does not mark anything published: consumers keep their own progress.
 */
@Component
public class OutboxReader {

  private final JdbcClient jdbc;

  /**
   * Creates the reader.
   *
   * @param jdbc JDBC client
   */
  public OutboxReader(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One committed event's routing facts.
   *
   * @param eventId unique event id
   * @param eventType internal event type
   * @param tenantId owning tenant
   * @param subject the envelope subject (the affected entity's identifier)
   * @param eventTime the event time recorded in the envelope
   * @param createdAt when the row was written
   */
  public record Entry(
      UUID eventId,
      String eventType,
      UUID tenantId,
      String subject,
      Instant eventTime,
      Instant createdAt) {}

  /**
   * Events strictly after a position, oldest first.
   *
   * @param createdAt position instant
   * @param eventId position tie-breaker
   * @param types internal event types wanted
   * @param limit maximum rows
   * @return matching events
   */
  public List<Entry> after(Instant createdAt, UUID eventId, Collection<String> types, int limit) {
    return jdbc.sql(
            """
            SELECT event_id, event_type, tenant_id, envelope ->> 'subject' AS subject,
                   envelope ->> 'eventTime' AS event_time, created_at
              FROM platform.outbox_event
             WHERE event_type IN (:types)
               AND (created_at, event_id) > (:createdAt, :eventId)
             ORDER BY created_at, event_id
             LIMIT :limit
            """)
        .param("types", types)
        .param("createdAt", java.sql.Timestamp.from(createdAt))
        .param("eventId", eventId)
        .param("limit", limit)
        .query(OutboxReader::map)
        .list();
  }

  /**
   * Events written in a window, for a consumer that re-checks recent history for rows that
   * committed late.
   *
   * @param from window start (inclusive)
   * @param to window end (inclusive)
   * @param types internal event types wanted
   * @param limit maximum rows
   * @return matching events, oldest first
   */
  public List<Entry> between(Instant from, Instant to, Collection<String> types, int limit) {
    return jdbc.sql(
            """
            SELECT event_id, event_type, tenant_id, envelope ->> 'subject' AS subject,
                   envelope ->> 'eventTime' AS event_time, created_at
              FROM platform.outbox_event
             WHERE event_type IN (:types)
               AND created_at BETWEEN :from AND :to
             ORDER BY created_at, event_id
             LIMIT :limit
            """)
        .param("types", types)
        .param("from", java.sql.Timestamp.from(from))
        .param("to", java.sql.Timestamp.from(to))
        .param("limit", limit)
        .query(OutboxReader::map)
        .list();
  }

  private static Entry map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Entry(
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("subject"),
        Instant.parse(rs.getString("event_time")),
        rs.getTimestamp("created_at").toInstant());
  }
}
