package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.integrations.application.WebhookJobs;
import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.application.MessagingJobs;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.platform.outbox.OutboxReader;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-095A (Issue #183, ADR 0036, R184-1): payment events cannot reach webhook or WhatsApp
 * delivery. A destination is recorded, a batch prepared and cancelled through the API; the three
 * internal events are in the outbox; the two real fan-out jobs run; the fan-out gate (the
 * allow-list the jobs read the outbox with) does not return them; neither job marks them fanned
 * out; and no webhook or WhatsApp delivery exists for them. A control proves the outbox reader
 * does see the events when asked for them by name, so the empty answers are the gate's doing.
 * Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PaymentEventsNotDeliveredIntegrationTest {

  private static final List<String> TYPES =
      List.of(
          "payroll.payment-destination.recorded.v1",
          "payroll.payment-batch.prepared.v1",
          "payroll.payment-batch.cancelled.v1");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private OutboxReader outbox;
  @Autowired private WebhookJobs webhookJobs;
  @Autowired private MessagingJobs messagingJobs;

  private PayrollPackageWorld w;
  private PaymentWorld p;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  @Test
  void paymentEventsAreInTheOutboxButNeitherFanOutJobNorDeliveryTableEverHoldsThem()
      throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    CompensationWorld.Person ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    String pkg =
        w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    String batch = p.prepare(pkg).get("batchId").asText();
    p.cancel(batch, "PREPARED_IN_ERROR");

    List<UUID> events =
        jdbc.queryForList(
            "SELECT event_id FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payment-%'",
            UUID.class,
            w.tenant);
    assertThat(events).hasSize(3);
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT event_type FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type LIKE 'payroll.payment-%'",
                String.class,
                w.tenant))
        .containsExactlyInAnyOrderElementsOf(TYPES);

    // Control: asked for by name, the reader returns all three, so it can see them.
    Instant from = Instant.EPOCH;
    assertThat(
            outbox.after(from, new UUID(0, 0), Set.copyOf(TYPES), 1000).stream()
                .map(OutboxReader.Entry::eventId)
                .filter(events::contains))
        .containsExactlyInAnyOrderElementsOf(events);

    // The gate both jobs read the outbox with returns none of them, new or late.
    assertThat(outbox.after(from, new UUID(0, 0), WebhookEventType.INTERNAL_TYPES, 10_000))
        .extracting(OutboxReader.Entry::eventType)
        .noneMatch(type -> type.startsWith("payroll.payment-"));
    assertThat(
            outbox.between(
                from, Instant.now().plusSeconds(3600), WebhookEventType.INTERNAL_TYPES, 10_000))
        .extracting(OutboxReader.Entry::eventType)
        .noneMatch(type -> type.startsWith("payroll.payment-"));

    // The real jobs run; nothing is fanned out or delivered for any payment event.
    webhookJobs.runOnce();
    messagingJobs.runOnce();
    for (UUID event : events) {
      assertThat(count("SELECT count(*) FROM integrations.fanout_event WHERE event_id = ?", event))
          .as("webhook fan-out marker")
          .isZero();
      assertThat(
              count(
                  "SELECT count(*) FROM integrations.messaging_fanout_event WHERE event_id = ?",
                  event))
          .as("WhatsApp fan-out marker")
          .isZero();
      assertThat(count("SELECT count(*) FROM integrations.delivery WHERE event_id = ?", event))
          .as("webhook delivery")
          .isZero();
      assertThat(
              count("SELECT count(*) FROM integrations.messaging_delivery WHERE event_id = ?", event))
          .as("WhatsApp delivery")
          .isZero();
    }
    assertThat(
            count(
                "SELECT count(*) FROM integrations.delivery WHERE event_type LIKE '%payment%'"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_delivery"
                    + " WHERE notification_type ILIKE '%payment%'"))
        .isZero();
  }
}
