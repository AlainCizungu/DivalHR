package com.divalhr.core.integrations.messaging;

import static com.divalhr.core.integrations.messaging.MessagingFixture.newNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.integrations.messaging.MessagingTestConfig.ScriptedProvider;
import com.divalhr.core.integrations.messaging.application.MessagingJobs;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-111 (D111-3, D111-4, D111-8, D111-9, D111-12): the V40 guards hold even for code that
 * bypasses the application: forward-only delivery state, no resend after a provider id, the
 * database-enforced test quota, append-only evidence, the recipient cap and uniqueness, erase-only
 * credentials, and the rollback script that refuses to destroy data.
 */
@IntegrationTest
@Import(MessagingTestConfig.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.messaging-write=600",
      "divalhr.request-limits.subject.messaging-read=600",
      "divalhr.messaging.jitter=false"
    })
class MessagingSchemaGuardsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ScriptedProvider provider;
  @Autowired private MessagingJobs jobs;

  private MessagingFixture fixture;
  private UUID tenant;
  private UUID connector;
  private UUID recipient;
  private UUID accepted;

  @BeforeEach
  void acceptedDelivery() throws Exception {
    provider.reset();
    fixture = new MessagingFixture(mvc);
    tenant = Hierarchy.newTenant(mvc);
    String admin = Hierarchy.bearer(tenant, "sub-sch-" + UUID.randomUUID(), "tenant-admin");
    connector = UUID.fromString(fixture.activate(admin).get("id").asText());
    recipient =
        UUID.fromString(
            fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText());
    accepted = fixture.test(admin, recipient.toString());
    jobs.deliverDue();
    assertThat(state(accepted)).isEqualTo("ACCEPTED");
  }

  private String state(UUID id) {
    return jdbc.queryForObject(
        "SELECT state FROM integrations.messaging_delivery WHERE id = ?", String.class, id);
  }

  /** Runs a statement the database must refuse and returns the database's message. */
  private String refused(String sql, Object... args) {
    Throwable[] thrown = new Throwable[1];
    assertThatThrownBy(() -> jdbc.update(sql, args))
        .isInstanceOf(DataAccessException.class)
        .satisfies(e -> thrown[0] = e);
    Throwable root = thrown[0];
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return String.valueOf(root.getMessage());
  }

  private void insertPending(UUID forRecipient, String kind, String type) {
    jdbc.update(
        "INSERT INTO integrations.messaging_delivery (tenant_id, id, connector_id, recipient_id,"
            + " event_id, notification_type, kind, resource_id, language, state, attempt_count,"
            + " created_at, next_attempt_at) VALUES (?, gen_random_uuid(), ?, ?, gen_random_uuid(),"
            + " ?, ?, NULL, 'en', 'PENDING', 0, now(), now())",
        tenant,
        connector,
        forRecipient,
        type,
        kind);
  }

  private void cloneRecipients(int count) {
    jdbc.update(
        "INSERT INTO integrations.messaging_recipient (tenant_id, id, connector_id, label,"
            + " number_ciphertext, number_digest, number_masked, language, notification_types,"
            + " status, consent_source, consent_confirmed_at, consent_attested_by, version,"
            + " created_at, created_by, updated_at, updated_by) SELECT tenant_id,"
            + " gen_random_uuid(), connector_id, label, number_ciphertext,"
            + " decode(md5(random()::text || g::text) || md5(clock_timestamp()::text || g::text),"
            + " 'hex'), number_masked, language, notification_types, status, consent_source,"
            + " consent_confirmed_at, consent_attested_by, 1, created_at, created_by, updated_at,"
            + " updated_by FROM integrations.messaging_recipient, generate_series(1, ?) g WHERE id"
            + " = ?",
        count,
        recipient);
  }

  @Test
  void anAcceptedMessageIsNeverSentAgainAndItsProviderIdNeverChanges() {
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET attempt_count = attempt_count + 1 WHERE"
                    + " id = ?",
                accepted))
        .contains("never sent again");
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET provider_message_id ="
                    + " 'wamid.replaced-1234' WHERE id = ?",
                accepted))
        .contains("provider message id never changes");
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET attempt_count = attempt_count + 2 WHERE"
                    + " id = ?",
                accepted))
        .contains("without gaps");
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET event_id = gen_random_uuid() WHERE id ="
                    + " ?",
                accepted))
        .contains("immutable");
    assertThat(state(accepted)).isEqualTo("ACCEPTED");
  }

  @Test
  void deliveryStateOnlyMovesForward() {
    jdbc.update(
        "UPDATE integrations.messaging_delivery SET state = 'DELIVERED', delivered_at = now() WHERE"
            + " id = ?",
        accepted);
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET state = 'ACCEPTED' WHERE id = ?",
                accepted))
        .contains("forward-only");
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET state = 'FAILED', completed_at = now(),"
                    + " last_failure_class = 'PROVIDER_FAILED' WHERE id = ?",
                accepted))
        .contains("forward-only");
    jdbc.update(
        "UPDATE integrations.messaging_delivery SET state = 'READ', read_at = now(), completed_at ="
            + " now() WHERE id = ?",
        accepted);
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET state = 'DELIVERED', completed_at ="
                    + " NULL WHERE id = ?",
                accepted))
        .contains("never changes");
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET language = 'fr' WHERE id = ?",
                accepted))
        .contains("never changes");
  }

  @Test
  void aMessageMustBeAcceptedBeforeItCanBeDelivered() {
    insertPending(recipient, "EVENT", "LEAVE_DECISION");
    UUID pending =
        jdbc.queryForObject(
            "SELECT id FROM integrations.messaging_delivery WHERE tenant_id = ? AND state ="
                + " 'PENDING' AND kind = 'EVENT'",
            UUID.class,
            tenant);
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET state = 'DELIVERED', delivered_at ="
                    + " now(), provider_message_id = 'wamid.skipped-1234', accepted_at = now(),"
                    + " next_attempt_at = NULL WHERE id = ?",
                pending))
        .contains("accepted before it is delivered");
  }

  @Test
  void aFinishedDeliveryNeverChangesAndAFailureNeverReopens() throws Exception {
    insertPending(recipient, "EVENT", "LEAVE_DECISION");
    provider.fail(SendFailure.RECIPIENT_REJECTED);
    jobs.deliverDue();
    UUID failed =
        jdbc.queryForObject(
            "SELECT id FROM integrations.messaging_delivery WHERE tenant_id = ? AND state ="
                + " 'FAILED'",
            UUID.class,
            tenant);
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery SET state = 'PENDING', completed_at = NULL,"
                    + " next_attempt_at = now(), last_failure_class = NULL WHERE id = ?",
                failed))
        .contains("never changes");
  }

  @Test
  void aDeliveryStartsPendingAndTestsAreLimitedInsideTheDatabase() {
    assertThat(
            refused(
                "INSERT INTO integrations.messaging_delivery (tenant_id, id, connector_id,"
                    + " recipient_id, event_id, notification_type, kind, language, state,"
                    + " attempt_count, created_at, completed_at) VALUES (?, gen_random_uuid(), ?,"
                    + " ?, gen_random_uuid(), 'LEAVE_DECISION', 'EVENT', 'en', 'CANCELLED', 0,"
                    + " now(), now())",
                tenant,
                connector,
                recipient))
        .contains("starts pending");
    // One test delivery already exists for this recipient from the fixture; two more fit, the next
    // does not.
    insertPending(recipient, "TEST", "CONNECTOR_TEST");
    insertPending(recipient, "TEST", "CONNECTOR_TEST");
    assertThat(
            refused(
                "INSERT INTO integrations.messaging_delivery (tenant_id, id, connector_id,"
                    + " recipient_id, event_id, notification_type, kind, language, state,"
                    + " attempt_count, created_at, next_attempt_at) VALUES (?, gen_random_uuid(),"
                    + " ?, ?, gen_random_uuid(), 'CONNECTOR_TEST', 'TEST', 'en', 'PENDING', 0,"
                    + " now(), now())",
                tenant,
                connector,
                recipient))
        .contains("quota");
    // Event deliveries are not limited by the test quota.
    insertPending(recipient, "EVENT", "LEAVE_DECISION");
  }

  @Test
  void tenTestDeliveriesPerTenantPerHourAreTheCeilingAcrossRecipients() {
    cloneRecipients(10);
    java.util.List<UUID> recipients =
        jdbc.queryForList(
            "SELECT id FROM integrations.messaging_recipient WHERE tenant_id = ? AND id <> ? ORDER"
                + " BY id",
            UUID.class,
            tenant,
            recipient);
    // The fixture already queued one test delivery for the first recipient; nine more fit.
    for (int i = 0; i < 9; i++) {
      insertPending(recipients.get(i), "TEST", "CONNECTOR_TEST");
    }
    assertThat(
            refused(
                "INSERT INTO integrations.messaging_delivery (tenant_id, id, connector_id,"
                    + " recipient_id, event_id, notification_type, kind, language, state,"
                    + " attempt_count, created_at, next_attempt_at) VALUES (?, gen_random_uuid(),"
                    + " ?, ?, gen_random_uuid(), 'CONNECTOR_TEST', 'TEST', 'en', 'PENDING', 0,"
                    + " now(), now())",
                tenant,
                connector,
                recipients.get(9)))
        .contains("quota");
  }

  @Test
  void aTenantNeverHasMoreThanTwentyFiveRecipientsAndNoNumberTwice() {
    cloneRecipients(24);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.messaging_recipient WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isEqualTo(25);
    assertThat(
            refused(
                "INSERT INTO integrations.messaging_recipient (tenant_id, id, connector_id, label,"
                    + " number_ciphertext, number_digest, number_masked, language,"
                    + " notification_types, status, consent_source, consent_confirmed_at,"
                    + " consent_attested_by, version, created_at, created_by, updated_at,"
                    + " updated_by) SELECT tenant_id, gen_random_uuid(), connector_id, label,"
                    + " number_ciphertext, decode(md5(random()::text) || md5(random()::text),"
                    + " 'hex'), number_masked, language, notification_types, status,"
                    + " consent_source, consent_confirmed_at, consent_attested_by, 1, created_at,"
                    + " created_by, updated_at, updated_by FROM integrations.messaging_recipient"
                    + " WHERE id = ?",
                recipient))
        .contains("recipient limit reached");
  }

  @Test
  void theSameNumberDigestCannotBeRegisteredTwiceForOneConnector() {
    String message =
        refused(
            "INSERT INTO integrations.messaging_recipient (tenant_id, id, connector_id, label,"
                + " number_ciphertext, number_digest, number_masked, language, notification_types,"
                + " status, consent_source, consent_confirmed_at, consent_attested_by, version,"
                + " created_at, created_by, updated_at, updated_by) SELECT tenant_id,"
                + " gen_random_uuid(), connector_id, label, number_ciphertext, number_digest,"
                + " number_masked, language, notification_types, status, consent_source,"
                + " consent_confirmed_at, consent_attested_by, 1, created_at, created_by,"
                + " updated_at, updated_by FROM integrations.messaging_recipient WHERE id = ?",
            recipient);
    assertThat(message).contains("integrations_messaging_recipient_number_unique");
  }

  @Test
  void aRecipientNumberAndIdentityAreImmutableAndNothingIsEverDeleted() {
    assertThat(
            refused(
                "UPDATE integrations.messaging_recipient SET number_digest = number_digest ||"
                    + " '\\x00'::bytea WHERE id = ?",
                recipient))
        .isNotBlank();
    assertThat(
            refused(
                "UPDATE integrations.messaging_recipient SET number_masked = '+1 **00' WHERE id ="
                    + " ?",
                recipient))
        .contains("immutable");
    assertThat(
            refused(
                "UPDATE integrations.messaging_recipient SET label = 'Renamed' WHERE id = ?",
                recipient))
        .contains("version");
    assertThat(refused("DELETE FROM integrations.messaging_recipient WHERE id = ?", recipient))
        .contains("never deleted");
    assertThat(refused("DELETE FROM integrations.messaging_connector WHERE id = ?", connector))
        .contains("never deleted");
    assertThat(refused("DELETE FROM integrations.messaging_delivery WHERE id = ?", accepted))
        .contains("never deleted");
    assertThat(
            refused(
                "DELETE FROM integrations.messaging_credential WHERE connector_id = ?", connector))
        .contains("never deleted");
  }

  @Test
  void attemptsCallbacksAndTheFanoutLedgerAreAppendOnly() {
    assertThat(
            refused(
                "UPDATE integrations.messaging_delivery_attempt SET latency_ms = 1 WHERE"
                    + " delivery_id = ?",
                accepted))
        .contains("never deleted, truncated or");
    assertThat(
            refused(
                "DELETE FROM integrations.messaging_delivery_attempt WHERE delivery_id = ?",
                accepted))
        .contains("never deleted, truncated or");
    assertThat(refused("TRUNCATE integrations.messaging_delivery_attempt"))
        .contains("never deleted, truncated or");
    jdbc.update(
        "INSERT INTO integrations.messaging_status_callback (tenant_id, delivery_id, status,"
            + " occurred_at, received_at) VALUES (?, ?, 'SENT', now(), now())",
        tenant,
        accepted);
    assertThat(
            refused(
                "UPDATE integrations.messaging_status_callback SET status = 'READ' WHERE"
                    + " delivery_id = ?",
                accepted))
        .contains("never deleted, truncated or");
    assertThat(
            refused(
                "DELETE FROM integrations.messaging_status_callback WHERE delivery_id = ?",
                accepted))
        .contains("never deleted, truncated or");
    assertThat(
            refused(
                "INSERT INTO integrations.messaging_status_callback (tenant_id, delivery_id,"
                    + " status, occurred_at, received_at, error_class) VALUES (?, ?, 'READ', now(),"
                    + " now(), 'PROVIDER_FAILED')",
                tenant,
                accepted))
        .isNotBlank();
    assertThat(refused("TRUNCATE integrations.messaging_fanout_event"))
        .contains("never deleted, truncated or");
  }

  @Test
  void aCredentialCanOnlyBeErasedNeverReplacedInPlaceAndExactlyOneIsStored() {
    assertThat(
            refused(
                "UPDATE integrations.messaging_credential SET ciphertext = ciphertext ||"
                    + " '\\x00'::bytea WHERE connector_id = ?",
                connector))
        .contains("can only be erased");
    // A second stored ciphertext without moving the connector's credential version is refused at
    // commit.
    assertThat(
            refused(
                "INSERT INTO integrations.messaging_credential (tenant_id, connector_id, version,"
                    + " ciphertext, created_at) SELECT tenant_id, connector_id, 2, ciphertext,"
                    + " now() FROM integrations.messaging_credential WHERE connector_id = ?",
                connector))
        .contains("exactly one stored credential");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.messaging_credential WHERE connector_id = ?",
                Integer.class,
                connector))
        .isEqualTo(1);
  }

  @Test
  void connectorStatesAndIdentityFollowTheGuardedTransitions() {
    assertThat(
            refused(
                "UPDATE integrations.messaging_connector SET state = 'DRAFT' WHERE id = ?",
                connector))
        .contains("illegal connector state transition");
    assertThat(
            refused(
                "UPDATE integrations.messaging_connector SET phone_number_id = '99999999' WHERE id"
                    + " = ?",
                connector))
        .isNotBlank();
    assertThat(
            refused(
                "UPDATE integrations.messaging_connector SET config_version = config_version + 5"
                    + " WHERE id = ?",
                connector))
        .isNotBlank();
    assertThat(
            refused(
                "UPDATE integrations.messaging_connector SET consecutive_failures = 7 WHERE id = ?",
                connector))
        .contains("failure counter");
    assertThat(
            refused(
                "UPDATE integrations.messaging_connector SET provider = 'OTHER' WHERE id = ?",
                connector))
        .isNotBlank();
  }

  @Test
  void theRollbackScriptRefusesWhileAnyMessagingDataExistsAndChangesNothing() throws Exception {
    Path script = Path.of("src/main/resources/db/rollback/V40__rollback.sql");
    if (!Files.exists(script)) {
      script = Path.of("apps/core-api").resolve(script);
    }
    String sql = Files.readString(script);
    assertThat(sql)
        .contains("V40 rollback refused")
        .doesNotContain("DELETE FROM")
        .doesNotContain("TRUNCATE");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute(sql))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("V40 rollback refused");
      try {
        statement.execute("ROLLBACK");
      } catch (SQLException alreadyEnded) {
        // The failed script already left no open transaction.
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.messaging_connector WHERE id = ?",
                Integer.class,
                connector))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.messaging_delivery WHERE id = ?",
                Integer.class,
                accepted))
        .isEqualTo(1);
  }
}
