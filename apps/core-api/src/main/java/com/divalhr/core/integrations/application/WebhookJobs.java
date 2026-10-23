package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.domain.Delivery;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.PublicEvent;
import com.divalhr.core.integrations.domain.RetrySchedule;
import com.divalhr.core.integrations.domain.Webhook;
import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.domain.WebhookSigner;
import com.divalhr.core.integrations.domain.WebhookStatus;
import com.divalhr.core.integrations.domain.WebhookUrl;
import com.divalhr.core.integrations.internal.JdbcWebhookRepository;
import com.divalhr.core.integrations.internal.JdbcWebhookRepository.Claimed;
import com.divalhr.core.integrations.internal.JdbcWebhookRepository.FanoutPosition;
import com.divalhr.core.integrations.internal.JdbcWebhookRepository.StoredSecret;
import com.divalhr.core.platform.outbox.OutboxReader;
import com.divalhr.core.platform.outbox.OutboxReader.Entry;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The webhook background work (D110-6): turning committed business events into deliveries, sending
 * due attempts, and erasing expired secrets. The business transaction never waits on any of it.
 * Several instances are safe: fan-out serializes on an advisory lock and a ledger, attempts are
 * claimed with row locks and a lease. Logs and metrics carry operation, event type, outcome,
 * attempt number, status class, failure class and latency band only.
 */
@Component
public class WebhookJobs {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookJobs.class);
  private static final int FANOUT_BATCH = 200;
  private static final int LATE_RESCAN = 1000;
  private static final Duration LATE_WINDOW = Duration.ofMinutes(2);
  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final int DELIVERY_BATCH = 20;
  private static final int CLEANUP_BATCH = 100;

  private final JdbcWebhookRepository webhooks;
  private final OutboxReader outbox;
  private final DeliveryTransport transport;
  private final WebhookSecrets secrets;
  private final IntegrationProperties properties;
  private final Clock clock;
  private final PlatformTransactionManager transactions;

  /**
   * Creates the jobs.
   *
   * @param webhooks storage
   * @param outbox the platform outbox reader port
   * @param transport delivery transport
   * @param secrets secret sealing
   * @param properties settings
   * @param clock the platform clock
   * @param transactions transaction manager
   */
  public WebhookJobs(
      JdbcWebhookRepository webhooks,
      OutboxReader outbox,
      DeliveryTransport transport,
      WebhookSecrets secrets,
      IntegrationProperties properties,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.webhooks = webhooks;
    this.outbox = outbox;
    this.transport = transport;
    this.secrets = secrets;
    this.properties = properties;
    this.clock = clock;
    this.transactions = transactions;
  }

  /** Runs one tick of every job. */
  public void runOnce() {
    fanOut();
    deliverDue();
    eraseExpiredSecrets();
  }

  /**
   * Turns newly committed public business events into one delivery per subscribed webhook.
   *
   * @return deliveries created
   */
  public int fanOut() {
    Integer created =
        inTransaction(
            () -> {
              Instant now = Instant.now(clock);
              FanoutPosition position = webhooks.lockFanout();
              List<Entry> fresh =
                  outbox.after(
                      position.watermark(),
                      position.eventId(),
                      WebhookEventType.INTERNAL_TYPES,
                      FANOUT_BATCH);
              List<Entry> late =
                  outbox.between(
                      position.watermark().minus(LATE_WINDOW),
                      position.watermark(),
                      WebhookEventType.INTERNAL_TYPES,
                      LATE_RESCAN);
              Map<UUID, Entry> candidates = new LinkedHashMap<>();
              late.forEach(e -> candidates.put(e.eventId(), e));
              fresh.forEach(e -> candidates.put(e.eventId(), e));
              Set<UUID> done = webhooks.alreadyFannedOut(new ArrayList<>(candidates.keySet()));
              int deliveries = 0;
              for (Entry entry : candidates.values()) {
                if (done.contains(entry.eventId())) {
                  continue;
                }
                deliveries += fanOut(entry, now);
                webhooks.markFannedOut(entry.eventId(), new TenantId(entry.tenantId()), now);
              }
              if (!fresh.isEmpty()) {
                Entry last = fresh.get(fresh.size() - 1);
                webhooks.saveFanout(new FanoutPosition(last.createdAt(), last.eventId()), now);
              }
              return deliveries;
            });
    return created == null ? 0 : created;
  }

  private int fanOut(Entry entry, Instant now) {
    Optional<WebhookEventType> type = WebhookEventType.fromInternal(entry.eventType());
    Optional<UUID> resource = uuid(entry.subject());
    if (type.isEmpty() || resource.isEmpty()) {
      LOG.atWarn()
          .addKeyValue("operation", "webhook.fan-out")
          .addKeyValue("outcome", "skipped")
          .log("webhook_fanout_skipped");
      return 0;
    }
    TenantId tenant = new TenantId(entry.tenantId());
    byte[] payload =
        new PublicEvent(
                entry.eventId(), type.get(), entry.eventTime(), entry.tenantId(), resource.get(), 1)
            .canonicalBytes();
    int created = 0;
    for (UUID webhook : webhooks.subscribers(tenant, type.get().code(), entry.createdAt())) {
      webhooks.insertDelivery(
          tenant,
          UUID.randomUUID(),
          webhook,
          entry.eventId(),
          type.get().code(),
          "EVENT",
          payload,
          now);
      created++;
    }
    return created;
  }

  private static Optional<UUID> uuid(String value) {
    try {
      return Optional.ofNullable(value).map(UUID::fromString);
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  /**
   * Sends every due attempt. The network call happens outside any transaction.
   *
   * @return attempts made
   */
  public int deliverDue() {
    if (!secrets.available()) {
      return 0;
    }
    List<Claimed> claimed = webhooks.claimDue(Instant.now(clock), LEASE, DELIVERY_BATCH);
    for (Claimed claim : claimed) {
      try {
        attempt(claim);
      } catch (RuntimeException failure) {
        LOG.atWarn()
            .addKeyValue("operation", "webhook.deliver")
            .addKeyValue("outcome", "internal_failure")
            .addKeyValue("error", failure.getClass().getSimpleName())
            .log("webhook_attempt_failed");
      }
    }
    return claimed.size();
  }

  private void attempt(Claimed claim) {
    Instant started = Instant.now(clock);
    Optional<Webhook> webhook = webhooks.find(claim.tenant(), claim.webhookId(), false);
    if (webhook.isEmpty() || webhook.get().status() != WebhookStatus.ACTIVE) {
      inTransaction(
          () -> {
            webhooks.cancel(claim, Instant.now(clock));
            return null;
          });
      return;
    }
    Optional<WebhookUrl> url = properties.parseUrl(webhook.get().url());
    List<byte[]> keys = new ArrayList<>();
    FailureClass failure = null;
    Integer status = null;
    long latency = 0;
    try {
      for (StoredSecret secret :
          webhooks.signingSecrets(claim.tenant(), claim.webhookId(), started)) {
        keys.add(
            secrets.open(
                secret.ciphertext(), claim.tenant().value(), claim.webhookId(), secret.version()));
      }
    } catch (IllegalStateException unreadable) {
      failure = FailureClass.INTERNAL_FAILURE;
    }
    if (failure == null && (url.isEmpty() || keys.isEmpty())) {
      failure = FailureClass.INTERNAL_FAILURE;
    }
    if (failure == null) {
      int number = claim.attemptCount() + 1;
      long timestamp = started.getEpochSecond();
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Content-Type", "application/json");
      headers.put("User-Agent", "DivalHR-Webhooks/1");
      headers.put("X-DivalHR-Event-Id", claim.eventId().toString());
      headers.put("X-DivalHR-Delivery-Id", claim.id().toString());
      headers.put("X-DivalHR-Attempt", Integer.toString(number));
      headers.put("X-DivalHR-Timestamp", Long.toString(timestamp));
      headers.put("X-DivalHR-Signature", WebhookSigner.header(keys, timestamp, claim.payload()));
      DeliveryTransport.Outcome outcome = transport.post(url.get(), headers, claim.payload());
      failure = outcome.failure();
      status = outcome.httpStatus();
      latency = outcome.latencyMillis();
    }
    FailureClass finalFailure = failure;
    Integer finalStatus = status;
    long finalLatency = latency;
    Instant finished = Instant.now(clock);
    int number = claim.attemptCount() + 1;
    boolean terminal = finalFailure != null && number >= RetrySchedule.MAX_ATTEMPTS;
    Instant next =
        finalFailure != null && !terminal
            ? finished.plus(RetrySchedule.delayAfter(number, claim.id(), properties.jitter()))
            : null;
    inTransaction(
        () -> {
          boolean recorded =
              webhooks.recordAttempt(
                  claim,
                  started,
                  finished,
                  finalStatus,
                  finalFailure,
                  (int) Math.min(finalLatency, Integer.MAX_VALUE),
                  next,
                  terminal);
          if (recorded && (finalFailure == null || terminal)) {
            webhooks.applyOutcome(
                claim.tenant(), claim.webhookId(), finalFailure == null, finished);
          }
          return null;
        });
    LOG.atInfo()
        .addKeyValue("operation", "webhook.deliver")
        .addKeyValue("eventType", claim.eventType())
        .addKeyValue(
            "outcome", finalFailure == null ? "succeeded" : terminal ? "failed" : "retrying")
        .addKeyValue("attempt", number)
        .addKeyValue("statusClass", finalStatus == null ? "none" : finalStatus / 100 + "xx")
        .addKeyValue("failureClass", finalFailure == null ? "none" : finalFailure.name())
        .addKeyValue("latencyBand", band(finalLatency))
        .log("webhook_attempt");
  }

  private static String band(long millis) {
    if (millis < 100) {
      return "lt100ms";
    }
    if (millis < 1000) {
      return "lt1s";
    }
    if (millis < 5000) {
      return "lt5s";
    }
    return "ge5s";
  }

  /**
   * Erases the ciphertext of previous secrets whose overlap has ended (bounded cleanup).
   *
   * @return secrets erased
   */
  public int eraseExpiredSecrets() {
    Integer erased =
        inTransaction(() -> webhooks.eraseExpiredSecrets(CLEANUP_BATCH, Instant.now(clock)));
    return erased == null ? 0 : erased;
  }

  private <T> T inTransaction(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(30);
    return template.execute(status -> work.get());
  }

  /**
   * Helper for tests and diagnostics: whether a delivery is still open.
   *
   * @param delivery a delivery
   * @return true when more attempts may happen
   */
  static boolean open(Delivery delivery) {
    return !delivery.state().isFinal();
  }
}
