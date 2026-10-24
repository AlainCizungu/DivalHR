package com.divalhr.core.integrations.messaging.application;

import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.messaging.domain.Connector;
import com.divalhr.core.integrations.messaging.domain.ConnectorState;
import com.divalhr.core.integrations.messaging.domain.MessagingRetrySchedule;
import com.divalhr.core.integrations.messaging.domain.NotificationType;
import com.divalhr.core.integrations.messaging.domain.PhoneNumber;
import com.divalhr.core.integrations.messaging.domain.Recipient;
import com.divalhr.core.integrations.messaging.domain.RecipientStatus;
import com.divalhr.core.integrations.messaging.domain.SendFailure;
import com.divalhr.core.integrations.messaging.domain.TemplateConfig;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.Claimed;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.Decision;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.FanoutPosition;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.StoredCredential;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.StoredNumber;
import com.divalhr.core.integrations.messaging.internal.JdbcMessagingRepository.Subscriber;
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
 * The WhatsApp background work (D111-9): turning committed business events into one delivery per
 * subscribed recipient and sending due attempts. The business transaction never waits on it.
 * Several instances are safe: fan-out serializes on an advisory lock and a ledger, attempts are
 * claimed with row locks and a lease. Logs carry operation, notification type, outcome, attempt
 * number, status class, failure class and latency band only: never a number, token, template,
 * identifier of the provider or link.
 */
@Component
public class MessagingJobs {

  private static final Logger LOG = LoggerFactory.getLogger(MessagingJobs.class);
  private static final int FANOUT_BATCH = 200;
  private static final int LATE_RESCAN = 1000;
  private static final Duration LATE_WINDOW = Duration.ofMinutes(2);
  private static final Duration LEASE = Duration.ofSeconds(60);
  private static final int DELIVERY_BATCH = 20;

  private final JdbcMessagingRepository repository;
  private final OutboxReader outbox;
  private final MessagingProvider provider;
  private final MessagingSecrets secrets;
  private final MessagingProperties properties;
  private final Clock clock;
  private final PlatformTransactionManager transactions;

  /**
   * Creates the jobs.
   *
   * @param repository storage
   * @param outbox the platform outbox reader port
   * @param provider the WhatsApp provider
   * @param secrets token and number sealing
   * @param properties settings
   * @param clock the platform clock
   * @param transactions transaction manager
   */
  public MessagingJobs(
      JdbcMessagingRepository repository,
      OutboxReader outbox,
      MessagingProvider provider,
      MessagingSecrets secrets,
      MessagingProperties properties,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.repository = repository;
    this.outbox = outbox;
    this.provider = provider;
    this.secrets = secrets;
    this.properties = properties;
    this.clock = clock;
    this.transactions = transactions;
  }

  /** Runs one tick of every job. */
  public void runOnce() {
    fanOut();
    deliverDue();
  }

  /**
   * Turns newly committed business events into one delivery per subscribed recipient. Only events
   * recorded after the connector was activated and the recipient registered are notified; old
   * events are never replayed.
   *
   * @return deliveries created
   */
  public int fanOut() {
    Integer created =
        inTransaction(
            () -> {
              Instant now = Instant.now(clock);
              FanoutPosition position = repository.lockFanout();
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
              Set<UUID> done = repository.alreadyFannedOut(new ArrayList<>(candidates.keySet()));
              int deliveries = 0;
              for (Entry entry : candidates.values()) {
                if (done.contains(entry.eventId())) {
                  continue;
                }
                deliveries += fanOut(entry, now);
                repository.markFannedOut(entry.eventId(), new TenantId(entry.tenantId()), now);
              }
              if (!fresh.isEmpty()) {
                Entry last = fresh.get(fresh.size() - 1);
                repository.saveFanout(new FanoutPosition(last.createdAt(), last.eventId()), now);
              }
              return deliveries;
            });
    return created == null ? 0 : created;
  }

  private int fanOut(Entry entry, Instant now) {
    Optional<NotificationType> type = NotificationType.fromInternal(entry.eventType());
    Optional<UUID> resource = uuid(entry.subject());
    if (type.isEmpty() || resource.isEmpty()) {
      return 0;
    }
    TenantId tenant = new TenantId(entry.tenantId());
    int created = 0;
    for (Subscriber subscriber : repository.subscribers(tenant, type.get(), entry.createdAt())) {
      repository.insertDelivery(
          tenant,
          UUID.randomUUID(),
          subscriber.connectorId(),
          subscriber.recipientId(),
          entry.eventId(),
          type.get(),
          resource.get(),
          subscriber.language(),
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
   * @return attempts claimed
   */
  public int deliverDue() {
    if (!secrets.available()) {
      return 0;
    }
    List<Claimed> claimed = repository.claimDue(Instant.now(clock), LEASE, DELIVERY_BATCH);
    for (Claimed claim : claimed) {
      try {
        attempt(claim);
      } catch (RuntimeException failure) {
        LOG.atWarn()
            .addKeyValue("operation", "messaging.deliver")
            .addKeyValue("outcome", "internal_failure")
            .addKeyValue("error", failure.getClass().getSimpleName())
            .log("messaging_attempt_failed");
      }
    }
    return claimed.size();
  }

  private void attempt(Claimed claim) {
    Instant started = Instant.now(clock);
    Optional<Connector> connector = repository.findConnector(claim.tenant(), false);
    Optional<Recipient> recipient =
        repository.findRecipient(claim.tenant(), claim.recipientId(), false);
    if (connector.isEmpty()
        || !connector.get().id().equals(claim.connectorId())
        || connector.get().state() != ConnectorState.ACTIVE
        || recipient.isEmpty()
        || recipient.get().status() != RecipientStatus.ACTIVE) {
      inTransaction(
          () -> {
            repository.cancel(claim, Instant.now(clock));
            return null;
          });
      return;
    }
    SendFailure failure = null;
    String providerMessageId = null;
    Integer status = null;
    Duration retryAfter = null;
    long latency = 0;
    Optional<TemplateConfig> template =
        repository.templates(claim.tenant(), claim.connectorId()).stream()
            .filter(t -> t.type() == claim.type() && t.language() == claim.language())
            .filter(t -> t.verifiedAt() != null)
            .findFirst();
    if (template.isEmpty()) {
      failure = SendFailure.TEMPLATE_INVALID;
    } else {
      try {
        Optional<StoredCredential> credential =
            repository.credential(claim.tenant(), claim.connectorId());
        Optional<StoredNumber> number = repository.number(claim.tenant(), claim.recipientId());
        if (credential.isEmpty() || credential.get().ciphertext() == null || number.isEmpty()) {
          failure = SendFailure.INTERNAL_FAILURE;
        } else {
          String token =
              secrets.openToken(
                  credential.get().ciphertext(),
                  claim.tenant().value(),
                  claim.connectorId(),
                  credential.get().version());
          PhoneNumber to =
              secrets.openNumber(
                  number.get().ciphertext(),
                  claim.tenant().value(),
                  claim.connectorId(),
                  claim.recipientId());
          MessagingProvider.SendOutcome outcome =
              provider.send(
                  new MessagingProvider.SendRequest(
                      token,
                      connector.get().phoneNumberId(),
                      to,
                      template.get().name(),
                      claim.language(),
                      template.get().bodyParameters() == 1
                          ? claim.type().label(claim.language())
                          : null,
                      com.divalhr.core.integrations.messaging.domain.DeepLink.buttonSuffix(
                          claim.type())));
          failure = outcome.failure();
          providerMessageId = outcome.providerMessageId();
          status = outcome.httpStatus();
          retryAfter = outcome.retryAfter();
          latency = outcome.latencyMillis();
          if (failure == null && providerMessageId == null) {
            failure = SendFailure.PROVIDER_FAILED;
          }
        }
      } catch (IllegalStateException unreadable) {
        failure = SendFailure.INTERNAL_FAILURE;
      }
    }
    Instant finished = Instant.now(clock);
    int number = claim.attemptCount() + 1;
    Decision decision;
    Instant next = null;
    if (failure == null) {
      decision = Decision.ACCEPTED;
    } else if (failure.retryable() && number < MessagingRetrySchedule.MAX_ATTEMPTS) {
      decision = Decision.RETRY;
      next =
          finished.plus(
              MessagingRetrySchedule.delayAfter(
                  number, claim.id(), properties.jitter(), retryAfter));
    } else if (failure.retryable() && failure.acceptanceUnknown()) {
      decision = Decision.UNKNOWN;
    } else {
      decision = Decision.FAILED;
    }
    SendFailure finalFailure = failure;
    Integer finalStatus = status;
    String finalProvider = failure == null ? providerMessageId : null;
    long finalLatency = latency;
    Instant finalNext = next;
    Decision finalDecision = decision;
    inTransaction(
        () -> {
          boolean recorded =
              repository.recordAttempt(
                  claim,
                  started,
                  finished,
                  finalStatus,
                  finalFailure,
                  (int) Math.min(finalLatency, Integer.MAX_VALUE),
                  finalDecision,
                  finalProvider,
                  finalNext);
          if (recorded && finalDecision == Decision.FAILED) {
            repository.applyOutcome(claim.tenant(), claim.connectorId(), false, finished);
          }
          return null;
        });
    LOG.atInfo()
        .addKeyValue("operation", "messaging.deliver")
        .addKeyValue("notificationType", claim.type().name())
        .addKeyValue("outcome", decision.name().toLowerCase(java.util.Locale.ROOT))
        .addKeyValue("attempt", number)
        .addKeyValue("statusClass", status == null ? "none" : status / 100 + "xx")
        .addKeyValue("failureClass", failure == null ? "none" : failure.name())
        .addKeyValue("latencyBand", band(latency))
        .log("messaging_attempt");
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

  private <T> T inTransaction(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(30);
    return template.execute(status -> work.get());
  }
}
