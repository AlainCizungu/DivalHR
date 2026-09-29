package com.divalhr.core.platform.records;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Database constraints are an independent line of defence behind application validation. */
@IntegrationTest
class PlatformRecordConstraintsIntegrationTest {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  private UUID insertOrganization() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO tenant.organization
          (id, name, country_code, default_locale, timezone, created_at, created_by)
        VALUES (?, 'Constraint Test Org', 'CD', 'fr', 'Africa/Kinshasa', now(), 'sub-db')
        """,
        id);
    return id;
  }

  @Test
  void organizationRejectsInvalidRows() {
    for (String[] row :
        new String[][] {
          {" padded", "CD", "fr"},
          {"x", "CD", "fr"},
          {"Name", "cd", "fr"},
          {"Name", "CD", "de"},
          {"Bad\nName", "CD", "fr"}
        }) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
                      INSERT INTO tenant.organization
                        (id, name, country_code, default_locale, timezone, created_at, created_by)
                      VALUES (?, ?, ?, ?, 'Africa/Kinshasa', now(), 'sub-db')
                      """,
                      UUID.randomUUID(),
                      row[0],
                      row[1],
                      row[2]))
          .isInstanceOf(DataAccessException.class);
    }
    UUID id = insertOrganization();
    assertThatThrownBy(
            () -> jdbc.update("INSERT INTO tenant.organization_currency VALUES (?, 'usd')", id))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void idempotencyRecordCannotCommitWithoutItsResponse() {
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        jdbc.update(
                            """
                            INSERT INTO platform.idempotency_record
                              (operation, principal, idempotency_key, request_fingerprint, state,
                               created_at, expires_at)
                            VALUES ('organization.create', 'sub-db', ?, ?, 'PENDING', now(),
                                    now() + interval '7 days')
                            """,
                            "pending-" + UUID.randomUUID(),
                            "a".repeat(64))))
        .isInstanceOfAny(
            DataAccessException.class, org.springframework.transaction.TransactionException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO platform.idempotency_record
                      (operation, principal, idempotency_key, request_fingerprint, state,
                       response_status, created_at, expires_at)
                    VALUES ('organization.create', 'sub-db', ?, ?, 'COMPLETED', 201, now(),
                            now() + interval '7 days')
                    """,
                    "incomplete-" + UUID.randomUUID(),
                    "b".repeat(64)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void auditEventsAreAppendOnly() {
    UUID org = insertOrganization();
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO platform.audit_event VALUES
          (?, now(), 'sub-db', 'organization.create', 'organization', ?, ?, 'SUCCESS',
           'constraint-corr-01', '{"countryCode":"CD"}', ?)
        """,
        id,
        org,
        org,
        "c".repeat(64));
    assertThatThrownBy(
            () ->
                jdbc.update("UPDATE platform.audit_event SET result = 'FAILURE' WHERE id = ?", id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM platform.audit_event WHERE id = ?", id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.execute("TRUNCATE platform.audit_event"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO platform.audit_event VALUES
                      (?, now(), 'sub-db', 'organization.create', 'organization', ?, ?, 'MAYBE',
                       'constraint-corr-01', '{}', ?)
                    """,
                    UUID.randomUUID(),
                    org,
                    org,
                    "c".repeat(64)))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void outboxEnvelopeMustMatchColumnsAndCarryEveryField() {
    UUID org = insertOrganization();
    UUID event = UUID.randomUUID();
    String valid =
        """
        {"eventId":"%s","eventType":"tenant.organization-created.v1","schemaVersion":1,
         "tenantId":"%s","source":"core-api/tenant","subject":"%s",
         "eventTime":"2026-09-29T00:00:00Z","correlationId":"constraint-corr-01",
         "causationId":null,"data":{}}
        """
            .formatted(event, org, org);
    assertThatCode(
            () ->
                jdbc.update(
                    """
                    INSERT INTO platform.outbox_event (event_id, event_type, tenant_id, envelope, created_at)
                    VALUES (?, 'tenant.organization-created.v1', ?, CAST(? AS jsonb), now())
                    """,
                    event,
                    org,
                    valid))
        .doesNotThrowAnyException();
    String withoutCausation = valid.replace("\"causationId\":null,", "");
    UUID other = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO platform.outbox_event (event_id, event_type, tenant_id, envelope, created_at)
                    VALUES (?, 'tenant.organization-created.v1', ?, CAST(? AS jsonb), now())
                    """,
                    other,
                    org,
                    withoutCausation.replace(event.toString(), other.toString())))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO platform.outbox_event (event_id, event_type, tenant_id, envelope, created_at)
                    VALUES (?, 'OrganizationCreated', ?, CAST(? AS jsonb), now())
                    """,
                    UUID.randomUUID(),
                    org,
                    valid))
        .isInstanceOf(DataAccessException.class);
  }
}
