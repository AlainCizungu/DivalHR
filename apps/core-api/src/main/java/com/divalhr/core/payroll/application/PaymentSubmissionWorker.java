package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentSubmissionOutcome;
import com.divalhr.core.payroll.domain.PaymentSubmissionPayload;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import com.divalhr.core.payroll.domain.PaymentSubmissionRetry;
import com.divalhr.core.payroll.domain.PaymentSubmissionState;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.Next;
import com.divalhr.core.payroll.internal.JdbcPaymentSubmissionRepository.SubmissionRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The delivery worker of provider submissions (MVP-095B, ADR 0037 section 6). A cycle recovers
 * expired leases, claims a bounded number of due submissions, and sends each one outside any
 * database transaction.
 *
 * <p>Delivery rules: the stable request ID is reused by every attempt; a failure before any byte
 * was written, or a 429 or 5xx response, is retried on the existing schedule; a rejection is final
 * and never retried; a timeout or lost connection after the request was written, a response that
 * cannot be authenticated, and a crashed worker (expired lease) end in {@code DELIVERY_UNKNOWN} and
 * are never resent blindly. Destination plaintext exists only in the byte buffer built for the
 * attempt, which is wiped afterwards. Logs, metrics and audit records carry the provider code,
 * state, outcome class and counts only.
 */
@Component
public class PaymentSubmissionWorker {

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payment-submission.worker");
  private static final String METRIC = "divalhr.payment.submission.transitions";

  private final JdbcPaymentSubmissionRepository submissions;
  private final PaymentSubmissionPayloads payloads;
  private final PaymentSubmissionProfiles profiles;
  private final PaymentSubmissionSecrets keys;
  private final PaymentProviderGateway gateway;
  private final PaymentSubmissionRecorder recorder;
  private final PaymentSubmissionProperties properties;
  private final Clock clock;
  private final PlatformTransactionManager transactions;
  private final MeterRegistry registry;
  private final String owner = "worker-" + UUID.randomUUID().toString().substring(0, 8);
  private final AtomicInteger depth = new AtomicInteger();

  /**
   * Creates the worker.
   *
   * @param submissions submission storage
   * @param payloads the outbound payload builder
   * @param profiles operator-controlled provider profiles
   * @param keys the signing key holder
   * @param gateway the provider connector port
   * @param recorder audit and outbox
   * @param properties worker settings
   * @param clock clock
   * @param transactions transaction manager
   * @param registry metrics
   */
  public PaymentSubmissionWorker(
      JdbcPaymentSubmissionRepository submissions,
      PaymentSubmissionPayloads payloads,
      PaymentSubmissionProfiles profiles,
      PaymentSubmissionSecrets keys,
      PaymentProviderGateway gateway,
      PaymentSubmissionRecorder recorder,
      PaymentSubmissionProperties properties,
      Clock clock,
      PlatformTransactionManager transactions,
      MeterRegistry registry) {
    this.submissions = submissions;
    this.payloads = payloads;
    this.profiles = profiles;
    this.keys = keys;
    this.gateway = gateway;
    this.recorder = recorder;
    this.properties = properties;
    this.clock = clock;
    this.transactions = transactions;
    this.registry = registry;
    Gauge.builder("divalhr.payment.submission.open", depth, AtomicInteger::get)
        .description("Submissions queued or being sent")
        .register(registry);
  }

  /**
   * One cycle: recover, claim, deliver.
   *
   * @return the number of submissions delivered this cycle
   */
  public int runOnce() {
    if (!keys.available()) {
      return 0;
    }
    recoverExpired();
    List<SubmissionRow> claimed = claimDue();
    int sent = 0;
    for (SubmissionRow claim : claimed) {
      try {
        deliver(claim);
        sent++;
      } catch (RuntimeException failure) {
        LOG.atWarn()
            .addKeyValue("operation", "payment-submission.deliver")
            .addKeyValue("outcome", "internal_failure")
            .addKeyValue("error", failure.getClass().getSimpleName())
            .log("payment_submission_attempt_failed");
      }
    }
    depth.set(transaction(submissions::openCount));
    return sent;
  }

  /**
   * Moves submissions whose worker lease expired to {@code DELIVERY_UNKNOWN}: the request may have
   * been written, so it is never resent automatically.
   *
   * @return how many were recovered
   */
  int recoverExpired() {
    String correlation = correlation();
    Integer recovered =
        transaction(
            () -> {
              Instant now = Instant.now(clock);
              int count = 0;
              for (SubmissionRow row :
                  submissions.lockExpired(now, properties.worker().claimSize())) {
                Next next =
                    new Next(
                        PaymentSubmissionState.DELIVERY_UNKNOWN.name(),
                        row.attemptCount(),
                        PaymentSubmissionOutcome.UNKNOWN_LEASE_EXPIRED.name(),
                        null,
                        null,
                        null,
                        null,
                        now);
                if (apply(row, next, correlation, now)) {
                  count++;
                }
              }
              return count;
            });
    return recovered == null ? 0 : recovered;
  }

  private List<SubmissionRow> claimDue() {
    List<SubmissionRow> claimed =
        transaction(
            () -> {
              Instant now = Instant.now(clock);
              List<SubmissionRow> result = new ArrayList<>();
              for (SubmissionRow row : submissions.lockDue(now, properties.worker().claimSize())) {
                Next next =
                    new Next(
                        PaymentSubmissionState.SENDING.name(),
                        row.attemptCount() + 1,
                        null,
                        null,
                        null,
                        owner,
                        now.plus(properties.worker().lease()),
                        now);
                if (submissions.transition(row.tenant(), row, next)) {
                  result.add(claimedRow(row, next));
                }
              }
              return result;
            });
    return claimed == null ? List.of() : claimed;
  }

  private static SubmissionRow claimedRow(SubmissionRow row, Next next) {
    return new SubmissionRow(
        row.tenant(),
        row.legalEntityId(),
        row.id(),
        row.batchId(),
        row.packageId(),
        row.requestId(),
        row.providerCode(),
        row.keyVersion(),
        next.state(),
        row.version() + 1,
        row.batchVersion(),
        row.batchDigest(),
        row.currency(),
        row.lineCount(),
        row.bank(),
        row.mobile(),
        row.manual(),
        row.totalNetMinor(),
        next.attemptCount(),
        next.outcomeCode(),
        next.reference(),
        next.nextAttemptAt(),
        next.leaseOwner(),
        next.leaseExpiresAt(),
        row.createdAt(),
        row.createdBy(),
        next.at());
  }

  /** Builds, signs and sends one claimed submission, then records exactly one outcome. */
  void deliver(SubmissionRow claim) {
    PaymentSubmissionProfile profile = profiles.byCode(claim.providerCode()).orElse(null);
    PaymentProviderGateway.Result result;
    byte[] body = null;
    try {
      if (profile == null) {
        result =
            new PaymentProviderGateway.Result(PaymentSubmissionOutcome.NOT_SENT_UNREADABLE, null);
      } else {
        try {
          body =
              transaction(
                  () ->
                      payloads.build(
                          claim.tenant(),
                          claim.legalEntityId(),
                          claim.batchId(),
                          claim.requestId(),
                          claim.batchDigest(),
                          claim.currency(),
                          claim.totalNetMinor()));
        } catch (PaymentSubmissionPayloads.Unreadable unreadable) {
          body = null;
        }
        if (body == null) {
          result =
              new PaymentProviderGateway.Result(PaymentSubmissionOutcome.NOT_SENT_UNREADABLE, null);
        } else {
          String signature = keys.signer().sign(claim.requestId(), body);
          try {
            result = gateway.send(profile, claim.requestId(), claim.keyVersion(), signature, body);
          } catch (RuntimeException unexpected) {
            // Whatever happened, a request may have been written: never resend blindly.
            result =
                new PaymentProviderGateway.Result(
                    PaymentSubmissionOutcome.UNKNOWN_CONNECTION_LOST, null);
          }
        }
      }
    } finally {
      PaymentSubmissionPayload.wipe(body);
    }
    record(claim, result);
  }

  private void record(SubmissionRow claim, PaymentProviderGateway.Result result) {
    String correlation = correlation();
    transaction(
        () -> {
          Instant now = Instant.now(clock);
          SubmissionRow current = submissions.lockById(claim.tenant(), claim.id()).orElse(null);
          if (current == null
              || current.version() != claim.version()
              || !PaymentSubmissionState.SENDING.name().equals(current.state())
              || !owner.equals(current.leaseOwner())) {
            // The lease was lost (recovered as unknown by another instance): nothing is
            // overwritten.
            LOG.atWarn()
                .addKeyValue("operation", "payment-submission.deliver")
                .addKeyValue("outcome", "lease_lost")
                .log("payment_submission_lease_lost");
            return null;
          }
          PaymentSubmissionOutcome outcome = result.outcome();
          int attempts = current.attemptCount();
          boolean exhausted = attempts >= PaymentSubmissionRetry.MAX_ATTEMPTS;
          PaymentSubmissionState state = outcome.nextState(exhausted);
          String code =
              outcome.kind() == PaymentSubmissionOutcome.Kind.TRANSIENT && exhausted
                  ? PaymentSubmissionOutcome.RETRIES_EXHAUSTED.name()
                  : outcome.name();
          Instant nextAt =
              state == PaymentSubmissionState.QUEUED
                  ? now.plus(PaymentSubmissionRetry.delayAfter(attempts))
                  : null;
          String reference =
              state == PaymentSubmissionState.ACCEPTED_FOR_PROCESSING ? result.reference() : null;
          Next next = new Next(state.name(), attempts, code, reference, nextAt, null, null, now);
          apply(current, next, correlation, now);
          return null;
        });
  }

  /** Applies one transition with its audit record and event; returns whether it was written. */
  private boolean apply(SubmissionRow row, Next next, String correlation, Instant now) {
    if (!submissions.transition(row.tenant(), row, next)) {
      return false;
    }
    submissions.checkDeferred(row.tenant());
    recorder.attempted(
        row.tenant(),
        correlation,
        new PaymentSubmissionRecorder.Change(
            row.legalEntityId(),
            row.batchId(),
            row.id(),
            row.providerCode(),
            next.state(),
            row.version() + 1,
            row.lineCount(),
            next.attemptCount(),
            next.outcomeCode(),
            row.batchDigest(),
            now));
    Counter.builder(METRIC)
        .tag("provider", row.providerCode())
        .tag("state", next.state())
        .tag("outcome", next.outcomeCode() == null ? "none" : next.outcomeCode())
        .register(registry)
        .increment();
    LOG.atInfo()
        .addKeyValue("operation", "payment-submission.transition")
        .addKeyValue("provider", row.providerCode())
        .addKeyValue("state", next.state())
        .addKeyValue("outcome", next.outcomeCode() == null ? "none" : next.outcomeCode())
        .addKeyValue("attempt", next.attemptCount())
        .addKeyValue("correlationId", correlation)
        .log("payment_submission_transition");
    return true;
  }

  private static String correlation() {
    return "psw-" + UUID.randomUUID();
  }

  private <T> T transaction(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(30);
    return template.execute(status -> work.get());
  }
}
