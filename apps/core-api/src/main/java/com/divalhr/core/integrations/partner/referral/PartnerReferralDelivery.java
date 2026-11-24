package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.domain.RetrySchedule;
import com.divalhr.core.integrations.partner.PartnerCrypto;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDeliveryStore.Locked;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers consented referrals to the controlled synthetic provider (MVP-120F, D120F-6/7, ADR
 * 0045).
 *
 * <p>Each step is its own short transaction that locks the referral row first: the claim (a lease
 * and one more attempt), the send (outside any transaction) and the resolution (attempt, receipt,
 * state and history together). The signed payload is exactly the disclosure the employee previewed
 * and consented to. Delivery is at-least-once only while nothing reached the provider: once the
 * provider accepted, answered uncertainly, failed after receiving, or an attempt outlived its
 * lease, the referral is never sent again. A referral that was withdrawn is never claimed, and a
 * claimed attempt cannot be withdrawn.
 */
@Service
public class PartnerReferralDelivery {

  private static final Logger LOG = LoggerFactory.getLogger(PartnerReferralDelivery.class);
  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final int BATCH = 20;
  private static final String WORKER_CORRELATION = "partner-referral-worker";

  /** A claimed attempt. */
  public record Claimed(Locked row, int attempt, Instant startedAt) {}

  private final PartnerReferralDeliveryStore store;
  private final PartnerReferralRecorder recorder;
  private final ReferralGateway gateway;
  private final PartnerCrypto crypto;
  private final JsonMapper json;
  private final Clock clock;
  private final TransactionTemplate transactions;

  /**
   * Creates the service.
   *
   * @param store delivery storage
   * @param recorder audit and outbox
   * @param gateway the provider
   * @param crypto opens the sealed disclosure and signs the delivery
   * @param json JSON mapper
   * @param clock clock
   * @param manager transaction manager
   */
  public PartnerReferralDelivery(
      PartnerReferralDeliveryStore store,
      PartnerReferralRecorder recorder,
      ReferralGateway gateway,
      PartnerCrypto crypto,
      JsonMapper json,
      Clock clock,
      PlatformTransactionManager manager) {
    this.store = store;
    this.recorder = recorder;
    this.gateway = gateway;
    this.crypto = crypto;
    this.json = json;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
  }

  /**
   * One cycle: resolves attempts that outlived their lease as uncertain, then claims and delivers
   * every referral that is due (bounded).
   *
   * @return the number of referrals handled
   */
  public int runOnce() {
    if (!crypto.available()) {
      return 0;
    }
    int handled = 0;
    while (handled < BATCH && Boolean.TRUE.equals(transactions.execute(s -> expireOne()))) {
      handled++;
    }
    for (int i = 0; i < BATCH; i++) {
      Claimed claimed = transactions.execute(s -> claimOne());
      if (claimed == null) {
        break;
      }
      deliver(claimed);
      handled++;
    }
    return handled;
  }

  /**
   * Claims the next due referral, or returns {@code null}.
   *
   * @return the claim
   */
  public Claimed claim() {
    return transactions.execute(status -> claimOne());
  }

  /**
   * Resolves one claimed attempt: sends it and records the outcome.
   *
   * @param claimed the claim
   */
  public void deliver(Claimed claimed) {
    // an attempt that outlived its lease was already resolved as uncertain: nothing is sent for it
    boolean live =
        Boolean.TRUE.equals(
            transactions.execute(
                status ->
                    store
                        .lock(claimed.row().tenantId(), claimed.row().id())
                        .filter(
                            row ->
                                "DELIVERY_PENDING".equals(row.state())
                                    && row.leased()
                                    && row.attemptCount() == claimed.attempt())
                        .isPresent()));
    if (!live) {
      return;
    }
    ReferralGateway.Result result;
    try {
      result = gateway.send(delivery(claimed));
    } catch (RuntimeException failure) {
      // the referral may have reached the provider: never resend
      LOG.atWarn()
          .addKeyValue("operation", "partner-referral.delivery")
          .addKeyValue("error", failure.getClass().getSimpleName())
          .log("partner_referral_delivery_uncertain");
      result = new ReferralGateway.Result(ReferralGateway.Kind.UNCERTAIN, null);
    }
    ReferralGateway.Result answer = result;
    transactions.executeWithoutResult(status -> resolve(claimed, answer));
  }

  private Claimed claimOne() {
    Instant now = now();
    Locked row = store.lockNextDue(now).orElse(null);
    if (row == null) {
      return null;
    }
    int version = row.version() + 1;
    store.claim(row, now.plus(LEASE), now);
    store.insertEvent(row, version, row.state(), "DELIVERY_PENDING", null, now);
    recorder.delivered(
        new TenantId(row.tenantId()),
        WORKER_CORRELATION,
        change(row, "DELIVERY_PENDING", version, null, row.attemptCount() + 1, now));
    return new Claimed(row, row.attemptCount() + 1, now);
  }

  private boolean expireOne() {
    Instant now = now();
    Locked row = store.lockNextExpired(now).orElse(null);
    if (row == null) {
      return false;
    }
    finish(row, row.updatedAt(), "DELIVERY_UNKNOWN", "UNCERTAIN_NOT_RESENT", null, null, now);
    return true;
  }

  private void resolve(Claimed claimed, ReferralGateway.Result result) {
    Instant now = now();
    Locked row = store.lock(claimed.row().tenantId(), claimed.row().id()).orElse(null);
    if (row == null
        || !"DELIVERY_PENDING".equals(row.state())
        || !row.leased()
        || row.attemptCount() != claimed.attempt()) {
      return; // resolved meanwhile (lease expiry); the outcome is already recorded
    }
    switch (result.kind()) {
      case ACCEPTED ->
          finish(
              row,
              claimed.startedAt(),
              "DELIVERED",
              "PROVIDER_ACKNOWLEDGED",
              result.providerReference(),
              null,
              now);
      case REJECTED ->
          finish(
              row, claimed.startedAt(), "DELIVERY_FAILED", "REJECTED_BY_PROVIDER", null, null, now);
      case UNCERTAIN ->
          finish(
              row,
              claimed.startedAt(),
              "DELIVERY_UNKNOWN",
              "UNCERTAIN_NOT_RESENT",
              null,
              null,
              now);
      case NOT_SENT -> {
        if (row.attemptCount() >= RetrySchedule.MAX_ATTEMPTS) {
          finish(row, claimed.startedAt(), "DELIVERY_FAILED", "RETRIES_EXHAUSTED", null, null, now);
        } else {
          Instant due =
              now.plus(RetrySchedule.delayAfter(row.attemptCount(), row.id(), false))
                  .truncatedTo(ChronoUnit.MICROS);
          finish(row, claimed.startedAt(), "DELIVERY_PENDING", "NOT_SENT_RETRYING", null, due, now);
        }
      }
    }
  }

  /** The attempt, the receipt, the new state and its history event: one transaction. */
  private void finish(
      Locked row,
      Instant started,
      String state,
      String outcome,
      String providerReference,
      Instant nextAttempt,
      Instant now) {
    int version = row.version() + 1;
    String attemptOutcome = outcome;
    store.insertAttempt(row, started, now, attemptOutcome);
    if (providerReference != null) {
      store.insertAck(row, providerReference, now);
    }
    store.move(row, state, nextAttempt, now);
    store.insertEvent(row, version, row.state(), state, outcome, now);
    recorder.delivered(
        new TenantId(row.tenantId()),
        WORKER_CORRELATION,
        change(row, state, version, outcome, row.attemptCount(), now));
  }

  private ReferralGateway.Delivery delivery(Claimed claimed) {
    Locked row = claimed.row();
    JsonNode values =
        transactions.execute(
            status ->
                json.readTree(
                    crypto.open(
                        store.sealed(row.tenantId(), row.id()),
                        "partner-referral:" + new TenantId(row.tenantId()) + ":" + row.id())));
    String payload = json.writeValueAsString(values);
    Instant timestamp = claimed.startedAt().truncatedTo(ChronoUnit.SECONDS);
    return new ReferralGateway.Delivery(
        row.reference(),
        timestamp,
        payload,
        SyntheticReferralProvider.sign(crypto, timestamp, payload));
  }

  private static PartnerReferralRecorder.Change change(
      Locked row, String state, int version, String outcome, int attempt, Instant at) {
    UUID id = row.id();
    return new PartnerReferralRecorder.Change(
        id, state, version, outcome, attempt, "SYSTEM", null, null, at);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
