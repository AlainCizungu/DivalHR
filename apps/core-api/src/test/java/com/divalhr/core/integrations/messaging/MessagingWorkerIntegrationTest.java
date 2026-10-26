package com.divalhr.core.integrations.messaging;

import static com.divalhr.core.integrations.messaging.MessagingFixture.PATH;
import static com.divalhr.core.integrations.messaging.MessagingFixture.event;
import static com.divalhr.core.integrations.messaging.MessagingFixture.json;
import static com.divalhr.core.integrations.messaging.MessagingFixture.key;
import static com.divalhr.core.integrations.messaging.MessagingFixture.newNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.integrations.messaging.MessagingTestConfig.ScriptedProvider;
import com.divalhr.core.integrations.messaging.application.MessagingJobs;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-111 (D111-8, D111-9): fan-out from committed business events, the retry schedule, the
 * never-resend rule, suspension after ten terminal failures, cancellation, and STATUS_UNKNOWN. The
 * provider is a scripted bean: no test touches the network, Meta or a real account.
 */
@IntegrationTest
@Import({MessagingTestConfig.class, SettableClock.Config.class})
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.messaging-write=600",
      "divalhr.request-limits.subject.messaging-read=600",
      "divalhr.messaging.jitter=false"
    })
class MessagingWorkerIntegrationTest {

  private static final String TIMESHEET = "people.timesheet.approved.v1";
  private static final String LEAVE = "people.leave-request.approved.v1";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ScriptedProvider provider;
  @Autowired private MessagingJobs jobs;
  @Autowired private SettableClock clock;

  private MessagingFixture fixture;
  private UUID tenant;
  private String admin;

  @BeforeEach
  void organization() throws Exception {
    provider.reset();
    clock.reset();
    fixture = new MessagingFixture(mvc);
    tenant = Hierarchy.newTenant(mvc);
    admin = Hierarchy.bearer(tenant, "sub-wrk-" + UUID.randomUUID(), "tenant-admin");
  }

  @AfterEach
  void restore() {
    clock.reset();
  }

  private Map<String, Object> delivery(UUID id) {
    return jdbc.queryForMap("SELECT * FROM integrations.messaging_delivery WHERE id = ?", id);
  }

  private List<UUID> deliveries(UUID forTenant) {
    return jdbc.queryForList(
        "SELECT id FROM integrations.messaging_delivery WHERE tenant_id = ? AND kind <> 'TEST'"
            + " ORDER BY created_at, id",
        UUID.class,
        forTenant);
  }

  private UUID onlyDelivery() {
    List<UUID> all = deliveries(tenant);
    assertThat(all).hasSize(1);
    return all.get(0);
  }

  @Test
  void aCommittedEventBecomesOneDeliveryPerSubscribedRecipientAndOnlyOnce() throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "fr", "TIMESHEET_APPROVED", "LEAVE_DECISION");
    fixture.recipient(admin, newNumber(), "en", "TIMESHEET_APPROVED");
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, TIMESHEET);

    assertThat(jobs.fanOut()).isEqualTo(2);
    assertThat(jobs.fanOut()).isZero();
    assertThat(deliveries(tenant)).hasSize(2);
    assertThat(jobs.deliverDue()).isEqualTo(2);
    assertThat(provider.sent()).hasSize(2);
    assertThat(provider.sent())
        .extracting(MessagingTestConfig.Sent::templateName)
        .containsExactlyInAnyOrder(
            "divalhr_timesheet_approved_fr", "divalhr_timesheet_approved_en");
    assertThat(provider.sent())
        .allSatisfy(s -> assertThat(s.buttonSuffix()).isEqualTo("admin/timesheets"));
    assertThat(jobs.deliverDue()).isZero();
    assertThat(provider.sent()).hasSize(2);
    List<String> states =
        jdbc.queryForList(
            "SELECT state FROM integrations.messaging_delivery WHERE tenant_id = ?",
            String.class,
            tenant);
    assertThat(states).containsOnly("ACCEPTED");
  }

  @Test
  void eventsFromBeforeTheConnectorOrTheRecipientExistedAreNeverReplayed() throws Exception {
    event(jdbc, tenant, LEAVE);
    fixture.activate(admin);
    event(jdbc, tenant, TIMESHEET);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION", "TIMESHEET_APPROVED");

    assertThat(jobs.fanOut()).isZero();
    assertThat(deliveries(tenant)).isEmpty();
    assertThat(jobs.deliverDue()).isZero();
    assertThat(provider.sent()).isEmpty();
  }

  @Test
  void anEventOfAnotherTenantNeverReachesThisTenantsRecipients() throws Exception {
    UUID other = Hierarchy.newTenant(mvc);
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "TIMESHEET_APPROVED");
    event(jdbc, other, TIMESHEET);
    assertThat(jobs.fanOut()).isZero();
    assertThat(deliveries(tenant)).isEmpty();
  }

  @Test
  void aDisabledOrUnsubscribedRecipientIsSkipped() throws Exception {
    fixture.activate(admin);
    String id =
        fixture.recipient(admin, newNumber(), "en", "TIMESHEET_APPROVED").get("id").asText();
    mvc.perform(
            json(
                post(PATH + "/recipients/" + id + "/disable"),
                admin,
                key(),
                "{\"version\":1,\"confirm\":true}"))
        .andExpect(status().isOk());
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, TIMESHEET);
    assertThat(jobs.fanOut()).isZero();
  }

  @Test
  void retryableFailuresFollowTheScheduleAndSucceedWithoutResendingAfterAcceptance()
      throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    UUID id = onlyDelivery();
    Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    clock.set(start);

    provider.fail(SendFailure.RATE_LIMITED);
    assertThat(jobs.deliverDue()).isEqualTo(1);
    assertThat(delivery(id)).containsEntry("state", "RETRYING").containsEntry("attempt_count", 1);
    // Not due before the one-minute delay, due after it.
    clock.set(start.plus(Duration.ofSeconds(59)));
    assertThat(jobs.deliverDue()).isZero();
    clock.set(start.plus(Duration.ofSeconds(61)));
    provider.accept();
    assertThat(jobs.deliverDue()).isEqualTo(1);
    Map<String, Object> accepted = delivery(id);
    assertThat(accepted).containsEntry("state", "ACCEPTED").containsEntry("attempt_count", 2);
    assertThat((String) accepted.get("provider_message_id")).startsWith("wamid.");
    assertThat(provider.sent()).hasSize(2);

    clock.set(start.plus(Duration.ofDays(1)));
    assertThat(jobs.deliverDue()).isZero();
    assertThat(provider.sent()).hasSize(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.messaging_delivery_attempt WHERE delivery_id ="
                    + " ?",
                Integer.class,
                id))
        .isEqualTo(2);
  }

  @Test
  void theDelaysAreOneFiveThirtyMinutesAndTwoHoursThenTheOutcomeIsStatusUnknown() throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    UUID id = onlyDelivery();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    clock.set(now);
    provider.fail(SendFailure.REQUEST_TIMEOUT);

    Duration[] delays = {
      Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)
    };
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThat(jobs.deliverDue()).isEqualTo(1);
      Map<String, Object> row = delivery(id);
      assertThat(row).containsEntry("state", "RETRYING").containsEntry("attempt_count", attempt);
      Instant next = ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant();
      assertThat(Duration.between(now, next)).isEqualTo(delays[attempt - 1]);
      clock.set(next.minusSeconds(1));
      assertThat(jobs.deliverDue()).isZero();
      now = next;
      clock.set(now);
    }
    assertThat(jobs.deliverDue()).isEqualTo(1);
    Map<String, Object> last = delivery(id);
    assertThat(last).containsEntry("state", "STATUS_UNKNOWN").containsEntry("attempt_count", 5);
    assertThat(last.get("provider_message_id")).isNull();
    assertThat(last.get("next_attempt_at")).isNull();
    assertThat(provider.sent()).hasSize(5);
    clock.set(now.plus(Duration.ofDays(2)));
    assertThat(jobs.deliverDue()).isZero();
    assertThat(provider.sent()).hasSize(5);
    // An unknown outcome is not a counted failure.
    assertThat(
            jdbc.queryForObject(
                "SELECT consecutive_failures FROM integrations.messaging_connector WHERE tenant_id"
                    + " = ?",
                Integer.class,
                tenant))
        .isZero();
  }

  @Test
  void aProviderRetryAfterHintLengthensTheDelayAndIsCapped() throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    UUID id = onlyDelivery();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    clock.set(now);
    provider.fail(SendFailure.RATE_LIMITED, Duration.ofDays(3));
    jobs.deliverDue();
    Instant next = ((java.sql.Timestamp) delivery(id).get("next_attempt_at")).toInstant();
    assertThat(Duration.between(now, next)).isEqualTo(Duration.ofHours(2));
    // Finish the delivery so no due work outlives this test for the others sharing the database.
    clock.set(next.plusSeconds(1));
    provider.accept();
    jobs.deliverDue();
    assertThat(delivery(id)).containsEntry("state", "ACCEPTED");
  }

  @Test
  void aNonRetryableFailureIsFinalAtOnce() throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    UUID id = onlyDelivery();
    provider.fail(SendFailure.RECIPIENT_REJECTED);
    jobs.deliverDue();
    assertThat(delivery(id))
        .containsEntry("state", "FAILED")
        .containsEntry("last_failure_class", "RECIPIENT_REJECTED")
        .containsEntry("attempt_count", 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT consecutive_failures FROM integrations.messaging_connector WHERE tenant_id"
                    + " = ?",
                Integer.class,
                tenant))
        .isEqualTo(1);
  }

  @Test
  void tenTerminalFailuresSuspendTheConnectorAndCancelTheRest() throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    for (int i = 0; i < 11; i++) {
      event(jdbc, tenant, LEAVE);
    }
    assertThat(jobs.fanOut()).isEqualTo(11);
    provider.fail(SendFailure.CREDENTIAL_INVALID);
    jobs.deliverDue();

    List<String> states =
        jdbc.queryForList(
            "SELECT state FROM integrations.messaging_delivery WHERE tenant_id = ?",
            String.class,
            tenant);
    assertThat(states.stream().filter("FAILED"::equals).count()).isEqualTo(10);
    assertThat(states.stream().filter("CANCELLED"::equals).count()).isEqualTo(1);
    assertThat(provider.sent()).hasSize(10);
    JsonNode connector = fixture.connector(admin);
    assertThat(connector.get("state").asText()).isEqualTo("SUSPENDED");

    // A suspended connector sends nothing more, and events no longer create deliveries.
    event(jdbc, tenant, LEAVE);
    assertThat(jobs.fanOut()).isZero();
    assertThat(jobs.deliverDue()).isZero();
  }

  @Test
  void everyTerminalFailureAddsOneToTheConsecutiveCounter() throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    for (int i = 0; i < 3; i++) {
      event(jdbc, tenant, LEAVE);
    }
    jobs.fanOut();
    provider.fail(SendFailure.TEMPLATE_INVALID);
    jobs.deliverDue();
    assertThat(
            jdbc.queryForObject(
                "SELECT consecutive_failures FROM integrations.messaging_connector WHERE tenant_id"
                    + " = ?",
                Integer.class,
                tenant))
        .isEqualTo(3);
  }

  @Test
  void disablingTheConnectorOrTheRecipientCancelsQueuedWorkWithoutSending() throws Exception {
    JsonNode active = fixture.activate(admin);
    String first = fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    mvc.perform(
            json(
                post(PATH + "/recipients/" + first + "/disable"),
                admin,
                key(),
                "{\"version\":1,\"confirm\":true}"))
        .andExpect(status().isOk());
    assertThat(jobs.deliverDue()).isZero();
    assertThat(provider.sent()).isEmpty();
    assertThat(delivery(onlyDelivery())).containsEntry("state", "CANCELLED");

    fixture.recipient(admin, newNumber(), "fr", "LEAVE_DECISION");
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    mvc.perform(
            json(
                post(PATH + "/disable"),
                admin,
                key(),
                "{\"configVersion\":" + active.get("configVersion").asInt() + ",\"confirm\":true}"))
        .andExpect(status().isOk());
    jobs.deliverDue();
    assertThat(provider.sent()).isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT state FROM integrations.messaging_delivery WHERE tenant_id = ?",
                String.class,
                tenant))
        .containsOnly("CANCELLED");
  }

  @Test
  void aDeliveryWhoseTemplateIsNoLongerVerifiedFailsClosedWithoutCallingTheProvider()
      throws Exception {
    fixture.activate(admin);
    fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    event(jdbc, tenant, LEAVE);
    jobs.fanOut();
    // A configuration change (here a rotation) is not required; an unverified template is enough.
    jdbc.update(
        "UPDATE integrations.messaging_template SET verified_at = NULL WHERE tenant_id = ? AND"
            + " notification_type = 'LEAVE_DECISION' AND language = 'en'",
        tenant);
    jobs.deliverDue();
    assertThat(provider.sent()).isEmpty();
    assertThat(delivery(onlyDelivery()))
        .containsEntry("state", "FAILED")
        .containsEntry("last_failure_class", "TEMPLATE_INVALID");
  }
}
