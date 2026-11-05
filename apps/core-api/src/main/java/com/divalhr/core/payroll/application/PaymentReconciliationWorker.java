package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.PaymentInquiryBody;
import com.divalhr.core.payroll.domain.PaymentInquiryOutcome;
import com.divalhr.core.payroll.domain.PaymentReconciliationRetry;
import com.divalhr.core.payroll.domain.PaymentReconciliationState;
import com.divalhr.core.payroll.domain.PaymentSubmissionProfile;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository.Next;
import com.divalhr.core.payroll.internal.JdbcPaymentReconciliationRepository.ReconciliationRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The inquiry worker of payment reconciliations (MVP-095C, ADR 0038 section 7). A cycle recovers
 * expired leases, claims a bounded number of due reconciliations and asks the provider for the
 * status of each existing payment request, outside any database transaction.
 *
 * <p>No-resend rule: this class never calls {@link PaymentProviderGateway#send}, never builds a
 * payment payload and never reads destinations or amounts. The only thing it sends is the signed,
 * canonical inquiry that carries the stable request ID and the stable inquiry ID. An answer that
 * cannot be authenticated is {@code BAD_RESPONSE} and never concludes anything. The original
 * submission and its attempts are not read or written here.
 */
@Component
public class PaymentReconciliationWorker {

  private static final Logger LOG =
      LoggerFactory.getLogger("divalhr.payment-reconciliation.worker");
  private static final String METRIC = "divalhr.payment.reconciliation.transitions";

  private final JdbcPaymentReconciliationRepository reconciliations;
  private final PaymentSubmissionProfiles profiles;
  private final PaymentSubmissionSecrets keys;
  private final PaymentProviderGateway gateway;
  private final PaymentReconciliationRecorder recorder;
  private final PaymentSubmissionProperties properties;
  private final Clock clock;
  private final PlatformTransactionManager transactions;
  private final MeterRegistry registry;
  private final String owner = "recon-" + UUID.randomUUID().toString().substring(0, 8);
  private final AtomicInteger depth = new AtomicInteger();

  /**
   * Creates the worker.
   *
   * @param reconciliations reconciliation storage
   * @param profiles operator-controlled provider profiles
   * @param keys the signing key holder
   * @param gateway the provider connector port
   * @param recorder audit and outbox
   * @param properties worker settings (shared with the submission worker)
   * @param clock clock
   * @param transactions transaction manager
   * @param registry metrics
   */
  public PaymentReconciliationWorker(
      JdbcPaymentReconciliationRepository reconciliations,
      PaymentSubmissionProfiles profiles,
      PaymentSubmissionSecrets keys,
      PaymentProviderGateway gateway,
      PaymentReconciliationRecorder recorder,
      PaymentSubmissionProperties properties,
      Clock clock,
      PlatformTransactionManager transactions,
      MeterRegistry registry) {
    this.reconciliations = reconciliations;
    this.profiles = profiles;
    this.keys = keys;
    this.gateway = gateway;
    this.recorder = recorder;
    this.properties = properties;
    this.clock = clock;
    this.transactions = transactions;
    this.registry = registry;
    Gauge.builder("divalhr.payment.reconciliation.open", depth, AtomicInteger::get)
        .description("Reconciliations open or being inquired")
        .register(registry);
  }

  /**
   * One cycle: recover, claim, inquire.
   *
   * @return the number of inquiries made this cycle
   */
  public int runOnce() {
    if (!keys.available()) {
      return 0;
    }
    recoverExpired();
    List<ReconciliationRow> claimed = claimDue();
    int asked = 0;
    for (ReconciliationRow claim : claimed) {
      try {
        inquire(claim);
        asked++;
      } catch (RuntimeException failure) {
        LOG.atWarn()
            .addKeyValue("operation", "payment-reconciliation.inquire")
            .addKeyValue("outcome", "internal_failure")
            .addKeyValue("error", failure.getClass().getSimpleName())
            .log("payment_reconciliation_attempt_failed");
      }
    }
    depth.set(transaction(reconciliations::openCount));
    return asked;
  }

  /**
   * Returns reconciliations whose worker lease expired to {@code OPEN} (the same inquiry ID is
   * asked again, which is safe because an inquiry is read-only), or to {@code
   * MANUAL_REVIEW_REQUIRED} once the attempts are used up.
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
              for (ReconciliationRow row :
                  reconciliations.lockExpired(now, properties.worker().claimSize())) {
                if (apply(
                    row,
                    afterTransient(row, PaymentInquiryOutcome.TRANSIENT_TIMEOUT, now),
                    correlation,
                    now)) {
                  count++;
                }
              }
              return count;
            });
    return recovered == null ? 0 : recovered;
  }

  private List<ReconciliationRow> claimDue() {
    String correlation = correlation();
    List<ReconciliationRow> claimed =
        transaction(
            () -> {
              Instant now = Instant.now(clock);
              List<ReconciliationRow> result = new ArrayList<>();
              for (ReconciliationRow row :
                  reconciliations.lockDue(now, properties.worker().claimSize())) {
                if (row.attemptCount() >= PaymentReconciliationRetry.MAX_ATTEMPTS) {
                  apply(row, exhausted(row, now), correlation, now);
                  continue;
                }
                Next next =
                    new Next(
                        PaymentReconciliationState.INQUIRY_IN_PROGRESS.name(),
                        row.attemptCount() + 1,
                        null,
                        false,
                        null,
                        owner,
                        now.plus(properties.worker().lease()),
                        now);
                if (apply(row, next, correlation, now)) {
                  result.add(claimedRow(row, next));
                }
              }
              return result;
            });
    return claimed == null ? List.of() : claimed;
  }

  private static ReconciliationRow claimedRow(ReconciliationRow row, Next next) {
    return new ReconciliationRow(
        row.tenant(),
        row.legalEntityId(),
        row.id(),
        row.submissionId(),
        row.batchId(),
        row.requestId(),
        row.providerCode(),
        row.keyVersion(),
        row.inquiryId(),
        row.openedFromState(),
        row.openedFromVersion(),
        next.state(),
        row.version() + 1,
        next.attemptCount(),
        next.outcomeCode(),
        next.nextAttemptAt(),
        next.leaseOwner(),
        next.leaseExpiresAt(),
        row.createdAt(),
        row.createdBy(),
        next.at());
  }

  /** Signs and sends one claimed inquiry, then records exactly one outcome. */
  void inquire(ReconciliationRow claim) {
    PaymentSubmissionProfile profile = profiles.byCode(claim.providerCode()).orElse(null);
    PaymentInquiryOutcome outcome;
    if (profile == null) {
      outcome = PaymentInquiryOutcome.TRANSIENT_CONNECT;
    } else {
      byte[] body = PaymentInquiryBody.canonical(claim.requestId(), claim.inquiryId());
      try {
        String signature = keys.signer().signInquiry(claim.requestId(), claim.inquiryId(), body);
        outcome =
            gateway
                .inquire(
                    profile,
                    claim.requestId(),
                    claim.inquiryId(),
                    claim.keyVersion(),
                    signature,
                    body)
                .outcome();
      } catch (RuntimeException unexpected) {
        // An inquiry is read-only, so any local failure is retried on the schedule.
        outcome = PaymentInquiryOutcome.TRANSIENT_CONNECT;
      } finally {
        Arrays.fill(body, (byte) 0);
      }
    }
    record(claim, outcome);
  }

  private void record(ReconciliationRow claim, PaymentInquiryOutcome outcome) {
    String correlation = correlation();
    transaction(
        () -> {
          Instant now = Instant.now(clock);
          ReconciliationRow current =
              reconciliations.lockById(claim.tenant(), claim.id()).orElse(null);
          if (current == null
              || current.version() != claim.version()
              || !PaymentReconciliationState.INQUIRY_IN_PROGRESS.name().equals(current.state())
              || !owner.equals(current.leaseOwner())) {
            // The lease was lost: a concluded or recovered reconciliation is never overwritten.
            LOG.atWarn()
                .addKeyValue("operation", "payment-reconciliation.inquire")
                .addKeyValue("outcome", "lease_lost")
                .log("payment_reconciliation_lease_lost");
            return null;
          }
          Next next;
          if (outcome.kind() == PaymentInquiryOutcome.Kind.TRANSIENT) {
            next = afterTransient(current, outcome, now);
          } else {
            PaymentReconciliationState state = outcome.nextState(false);
            next =
                new Next(
                    state.name(),
                    current.attemptCount(),
                    outcome.name(),
                    outcome.authenticated(),
                    null,
                    null,
                    null,
                    now);
          }
          apply(current, next, correlation, now);
          return null;
        });
  }

  /** The state after a failed attempt: retry on the schedule, or hand over to manual review. */
  private static Next afterTransient(
      ReconciliationRow row, PaymentInquiryOutcome outcome, Instant now) {
    int attempts = row.attemptCount();
    if (attempts >= PaymentReconciliationRetry.MAX_ATTEMPTS) {
      return exhausted(row, now);
    }
    return new Next(
        PaymentReconciliationState.OPEN.name(),
        attempts,
        outcome.name(),
        false,
        now.plus(PaymentReconciliationRetry.delayAfter(attempts)),
        null,
        null,
        now);
  }

  private static Next exhausted(ReconciliationRow row, Instant now) {
    return new Next(
        PaymentReconciliationState.MANUAL_REVIEW_REQUIRED.name(),
        row.attemptCount(),
        PaymentInquiryOutcome.RETRIES_EXHAUSTED.name(),
        false,
        null,
        null,
        null,
        now);
  }

  /** Applies one transition with its evidence, audit record and event; false if it moved. */
  private boolean apply(ReconciliationRow row, Next next, String correlation, Instant now) {
    if (!reconciliations.transition(row.tenant(), row, next)) {
      return false;
    }
    reconciliations.checkDeferred(row.tenant());
    recorder.inquired(
        row.tenant(),
        correlation,
        new PaymentReconciliationRecorder.Change(
            row.legalEntityId(),
            row.batchId(),
            row.submissionId(),
            row.id(),
            row.providerCode(),
            next.state(),
            row.version() + 1,
            next.attemptCount(),
            next.outcomeCode(),
            now));
    Counter.builder(METRIC)
        .tag("provider", row.providerCode())
        .tag("state", next.state())
        .tag("outcome", next.outcomeCode() == null ? "none" : next.outcomeCode())
        .register(registry)
        .increment();
    LOG.atInfo()
        .addKeyValue("operation", "payment-reconciliation.transition")
        .addKeyValue("provider", row.providerCode())
        .addKeyValue("state", next.state())
        .addKeyValue("outcome", next.outcomeCode() == null ? "none" : next.outcomeCode())
        .addKeyValue("attempt", next.attemptCount())
        .addKeyValue("correlationId", correlation)
        .log("payment_reconciliation_transition");
    return true;
  }

  private static String correlation() {
    return "prw-" + UUID.randomUUID();
  }

  private <T> T transaction(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(30);
    return template.execute(status -> work.get());
  }
}
