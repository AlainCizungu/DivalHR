package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.integrations.application.WebhookJobs;
import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.application.MessagingJobs;
import com.divalhr.core.payroll.application.AccountingRecorder;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-096 (Issue #194, ADR 0039 section 10): the two accounting events cannot reach webhook or
 * WhatsApp delivery. A mapping version and a journal are created through the API; both internal
 * events are in the outbox; the two real fan-out jobs run; the fan-out gate does not return them;
 * neither job marks them fanned out; and no webhook or WhatsApp delivery exists for them. A control
 * proves the outbox reader does see the events when asked for them by name, so the empty answers
 * are the gate's doing. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.accounting-preview=600",
      "divalhr.request-limits.subject.accounting-journal-write=600",
      "divalhr.request-limits.subject.accounting-mapping-write=600",
      "divalhr.request-limits.subject.accounting-read=600"
    })
class AccountingEventsNotDeliveredIntegrationTest {

  private static final List<String> TYPES =
      List.of(AccountingRecorder.MAPPING_CREATED_EVENT, AccountingRecorder.JOURNAL_GENERATED_EVENT);

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  @Autowired private OutboxReader outbox;
  @Autowired private WebhookJobs webhookJobs;
  @Autowired private MessagingJobs messagingJobs;

  private PayrollPackageWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
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
  void theEventsAreNotInAnyPublicMapping() {
    for (String type : TYPES) {
      assertThat(WebhookEventType.INTERNAL_TYPES).doesNotContain(type);
    }
    assertThat(WebhookEventType.subscribableCodes()).noneMatch(code -> code.contains("accounting"));
    assertThat(WebhookEventType.of("payroll.accounting-mapping.created.v1")).isEmpty();
    assertThat(WebhookEventType.of("payroll.accounting-journal.generated.v1")).isEmpty();
  }

  @Test
  void accountingEventsAreInTheOutboxButNeitherFanOutJobNorDeliveryTableEverHoldsThem()
      throws Exception {
    AccountingWorld a = new AccountingWorld(w, w.approve(w.ready()).get("packageId").asText());
    a.createMapping("2000-01-01");
    a.generated();

    List<UUID> events =
        jdbc.queryForList(
            "SELECT event_id FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.accounting-%'",
            UUID.class, w.tenant);
    assertThat(events).hasSize(2);
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT event_type FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type LIKE 'payroll.accounting-%'",
                String.class, w.tenant))
        .containsExactlyInAnyOrderElementsOf(TYPES);

    // Control: asked for by name, the reader returns both, so it can see them.
    Instant from = Instant.EPOCH;
    assertThat(
            outbox.after(from, new UUID(0, 0), Set.copyOf(TYPES), 10_000).stream()
                .map(OutboxReader.Entry::eventId)
                .filter(events::contains))
        .containsExactlyInAnyOrderElementsOf(events);

    // The gate both jobs read the outbox with returns none of them, new or late.
    assertThat(outbox.after(from, new UUID(0, 0), WebhookEventType.INTERNAL_TYPES, 10_000))
        .extracting(OutboxReader.Entry::eventType)
        .noneMatch(type -> type.startsWith("payroll.accounting-"));
    assertThat(
            outbox.between(
                from, Instant.now().plusSeconds(3600), WebhookEventType.INTERNAL_TYPES, 10_000))
        .extracting(OutboxReader.Entry::eventType)
        .noneMatch(type -> type.startsWith("payroll.accounting-"));

    // The real jobs run; nothing is fanned out or delivered for any accounting event.
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
              count(
                  "SELECT count(*) FROM integrations.messaging_delivery WHERE event_id = ?", event))
          .as("WhatsApp delivery")
          .isZero();
    }
    assertThat(
            count(
                "SELECT count(*) FROM integrations.delivery WHERE event_type LIKE '%accounting%'"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_delivery"
                    + " WHERE notification_type ILIKE '%accounting%'"))
        .isZero();
  }
}
