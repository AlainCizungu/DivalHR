package com.divalhr.core.platform.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Writes audit events in the caller's transaction. The table rejects UPDATE and DELETE. */
@Component
public class AuditRecorder {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * Creates the recorder.
   *
   * @param jdbc JDBC client
   * @param json JSON mapper
   */
  public AuditRecorder(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Records an event.
   *
   * @param event audit event
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(AuditEvent event) {
    jdbc.sql(
            """
            INSERT INTO platform.audit_event
              (id, occurred_at, actor_subject, action, resource_type, resource_id, tenant_id,
               result, correlation_id, metadata, after_state_sha256)
            VALUES (:id, :occurredAt, :actor, :action, :resourceType, :resourceId, :tenantId,
                    :result, :correlationId, CAST(:metadata AS jsonb), :afterState)
            """)
        .param("id", event.id())
        .param("occurredAt", java.sql.Timestamp.from(event.occurredAt()))
        .param("actor", event.actorSubject())
        .param("action", event.action())
        .param("resourceType", event.resourceType())
        .param("resourceId", event.resourceId())
        .param("tenantId", event.tenantId())
        .param("result", event.result())
        .param("correlationId", event.correlationId())
        .param("metadata", json.writeValueAsString(event.metadata()))
        .param("afterState", event.afterStateSha256())
        .update();
  }
}
