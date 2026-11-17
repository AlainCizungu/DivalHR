package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffOutcome;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffPayload;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffRetry;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.AttemptRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.ConsentRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.EventRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.HandoffRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.Next;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The controlled partner delivery worker (MVP-120B, ADR 0042). It claims due QUEUED handoffs with a
 * short lease, rebuilds the exact authorized payload from stored facts, signs it with the separate
 * partner signing key, sends it to the controlled synthetic receiver outside any database
 * transaction and records the outcome atomically with its audit and outbox event.
 *
 * <p>A delivery that may have reached the partner is never resent: an ambiguous outcome (a timeout
 * after the write, an unauthenticated or malformed answer, an expired lease) moves the handoff to
 * DELIVERY_UNKNOWN and only an administrator's signed inquiry can release it. Only a failure that
 * proves nothing was sent (a refused connection, a blocked address, an unavailable receiver) is
 * retried automatically, on a bounded schedule. Nothing here logs a header, a signature, a payload
 * or a partner response.
 */
@Component
public class SalaryAdvanceHandoffWorker {

  private static final Logger LOG =
      LoggerFactory.getLogger("divalhr.salary-advance-handoff.worker");
  private static final String METRIC = "divalhr.payroll.handoff.transitions";
  private static final String SYSTEM = "SYSTEM";

  private final JdbcSalaryAdvanceHandoffRepository handoffs;
  private final SalaryAdvancePartnerProfiles profiles;
  private final SalaryAdvancePartnerSecrets secrets;
  private final SalaryAdvancePartnerGateway gateway;
  private final SalaryAdvanceHandoffRecorder recorder;
  private final SalaryAdvancePartnerProperties properties;
  private final Clock clock;
  private final PlatformTransactionManager transactions;
  private final MeterRegistry registry;
  private final AtomicInteger depth = new AtomicInteger();

  /**
   * Creates the worker.
   *
   * @param handoffs handoff storage
   * @param profiles operator-controlled partner profile
   * @param secrets partner signing keys
   * @param gateway the partner port
   * @param recorder audit and outbox
   * @param properties partner configuration
   * @param clock the clock
   * @param transactions transaction manager
   * @param registry metrics
   */
  public SalaryAdvanceHandoffWorker(
      JdbcSalaryAdvanceHandoffRepository handoffs,
      SalaryAdvancePartnerProfiles profiles,
      SalaryAdvancePartnerSecrets secrets,
      SalaryAdvancePartnerGateway gateway,
      SalaryAdvanceHandoffRecorder recorder,
      SalaryAdvancePartnerProperties properties,
      Clock clock,
      PlatformTransactionManager transactions,
      MeterRegistry registry) {
    this.handoffs = handoffs;
    this.profiles = profiles;
    this.secrets = secrets;
    this.gateway = gateway;
    this.recorder = recorder;
    this.properties = properties;
    this.clock = clock;
    this.transactions = transactions;
    this.registry = registry;
    Gauge.builder("divalhr.payroll.handoff.open", depth, AtomicInteger::get)
        .description("Handoffs queued or being sent")
        .register(registry);
  }

  /**
   * One cycle: recover expired leases, claim due handoffs and deliver them.
   *
   * @return the number of handoffs delivered or recorded
   */
  public int runOnce() {
    if (!secrets.deliveryAvailable()) {
      return 0;
    }
    recoverExpired();
    int done = 0;
    for (HandoffRow claim : claimDue()) {
      try {
        deliver(claim);
        done++;
      } catch (RuntimeException failure) {
        LOG.atWarn()
            .addKeyValue("operation", "salary-advance-handoff.deliver")
            .addKeyValue("outcome", "internal_failure")
            .addKeyValue("error", failure.getClass().getSimpleName())
            .log("salary_advance_handoff_attempt_failed");
      }
    }
    depth.set(transaction(handoffs::openCount));
    return done;
  }

  /** A crashed worker's SENDING handoff may have reached the partner: it becomes unknown. */
  int recoverExpired() {
    String correlation = correlation();
    Integer recovered =
        transaction(
            () -> {
              Instant now = now();
              int count = 0;
              for (HandoffRow row : handoffs.lockExpired(now, properties.worker().claimSize())) {
                Next next =
                    new Next(
                        "DELIVERY_UNKNOWN",
                        null,
                        null,
                        row.attemptCount(),
                        row.reconcileCount(),
                        row.adminRetryCount(),
                        null,
                        null,
                        now);
                if (leaveSending(
                    row,
                    next,
                    SalaryAdvanceHandoffOutcome.UNKNOWN_LEASE_EXPIRED,
                    null,
                    correlation,
                    now)) {
                  count++;
                }
              }
              return count;
            });
    return recovered == null ? 0 : recovered;
  }

  private List<HandoffRow> claimDue() {
    List<HandoffRow> claimed =
        transaction(
            () -> {
              Instant now = now();
              List<HandoffRow> result = new ArrayList<>();
              for (HandoffRow row : handoffs.lockDue(now, properties.worker().claimSize())) {
                Next next =
                    new Next(
                        "SENDING",
                        null,
                        null,
                        row.attemptCount() + 1,
                        row.reconcileCount(),
                        row.adminRetryCount(),
                        null,
                        now.plus(properties.worker().lease()),
                        now);
                handoffs.insertEvent(
                    row.tenantId(),
                    row.id(),
                    new EventRow(
                        handoffs.nextEventSequence(row.tenantId(), row.id()),
                        "QUEUED",
                        "SENDING",
                        SYSTEM,
                        SalaryAdvanceHandoffRecorder.WORKER_ACTOR,
                        now,
                        null));
                if (handoffs.update(row.tenantId(), row.id(), row.version(), next) == 1) {
                  handoffs.checkDeferred(row.tenantId());
                  result.add(claimedRow(row, next));
                }
              }
              return result;
            });
    return claimed == null ? List.of() : claimed;
  }

  /** The row as the claim left it: SENDING, one attempt more, one version on. */
  private static HandoffRow claimedRow(HandoffRow row, Next next) {
    return new HandoffRow(
        row.tenant(),
        row.id(),
        row.requestId(),
        row.employeeId(),
        row.employeeNumber(),
        row.legalEntityId(),
        row.partnerCode(),
        row.applicationId(),
        next.state(),
        null,
        null,
        row.version() + 1,
        next.attemptCount(),
        row.reconcileCount(),
        row.adminRetryCount(),
        row.payloadSha256(),
        row.keyVersion(),
        row.authorizedAt(),
        row.authorizedBy(),
        null,
        next.leaseUntil(),
        next.at(),
        row.currency(),
        row.requestedMinor(),
        row.ceilingMinor(),
        row.referencePeriodStart(),
        row.employmentStart(),
        row.requestVersion(),
        row.requestStatus());
  }

  void deliver(HandoffRow claim) {
    SalaryAdvancePartnerProfile profile = profiles.byCode(claim.partnerCode()).orElse(null);
    SalaryAdvancePartnerGateway.Result result;
    byte[] body = null;
    try {
      body = profile == null ? null : transaction(() -> rebuild(claim));
      if (body == null) {
        // Nothing was sent: the stored facts no longer reproduce the authorized payload, or the
        // partner is not enabled. The delivery is stopped, never sent in another shape.
        result =
            new SalaryAdvancePartnerGateway.Result(
                SalaryAdvanceHandoffOutcome.TRANSIENT_NOT_SENT, null);
      } else {
        long timestamp = now().getEpochSecond();
        String signature = secrets.delivery().signDelivery(timestamp, claim.applicationId(), body);
        try {
          result =
              gateway.send(
                  profile, claim.applicationId(), claim.keyVersion(), timestamp, signature, body);
        } catch (RuntimeException unexpected) {
          // Whatever happened, a request may have been written: never resend blindly.
          result =
              new SalaryAdvancePartnerGateway.Result(
                  SalaryAdvanceHandoffOutcome.UNKNOWN_TIMEOUT, null);
        }
      }
    } finally {
      if (body != null) {
        Arrays.fill(body, (byte) 0);
      }
    }
    record(claim, result);
  }

  /** The exact authorized bytes, or {@code null} when they cannot be reproduced. */
  private byte[] rebuild(HandoffRow claim) {
    ConsentRow consent = handoffs.consent(claim.tenantId(), claim.id()).orElse(null);
    if (consent == null) {
      return null;
    }
    SalaryAdvanceHandoffPayload payload =
        new SalaryAdvanceHandoffPayload(
            claim.applicationId(),
            claim.requestId(),
            SalaryAdvanceHandoffService.legalEntityReference(
                claim.legalEntityId(), claim.applicationId()),
            true,
            claim.employmentStart(),
            claim.referencePeriodStart(),
            claim.currency(),
            claim.requestedMinor(),
            claim.ceilingMinor(),
            consent.version(),
            consent.disclosureSha256(),
            claim.authorizedAt(),
            SalaryAdvanceHandoffService.correlationFor(claim.applicationId()));
    if (!payload.sha256().equals(claim.payloadSha256())) {
      return null;
    }
    return payload.canonicalBytes();
  }

  private void record(HandoffRow claim, SalaryAdvancePartnerGateway.Result result) {
    String correlation = correlation();
    transaction(
        () -> {
          Instant now = now();
          HandoffRow current = handoffs.lock(claim.tenantId(), claim.id()).orElse(null);
          if (current == null
              || current.version() != claim.version()
              || !"SENDING".equals(current.state())) {
            // The lease was lost (recovered as unknown by another instance): nothing is
            // overwritten.
            LOG.atWarn()
                .addKeyValue("operation", "salary-advance-handoff.deliver")
                .addKeyValue("outcome", "lease_lost")
                .log("salary_advance_handoff_lease_lost");
            return null;
          }
          SalaryAdvanceHandoffOutcome outcome = result.outcome();
          int attempts = current.attemptCount();
          int roundBase = SalaryAdvanceHandoffRetry.MAX_ATTEMPTS * current.adminRetryCount();
          boolean exhausted =
              attempts >= SalaryAdvanceHandoffRetry.MAX_ATTEMPTS * (current.adminRetryCount() + 1);
          String state;
          SalaryAdvanceHandoffOutcome code = outcome;
          switch (outcome) {
            case RECEIVED_ACKNOWLEDGED -> state = "RECEIVED";
            case REJECTED_BY_PARTNER -> state = "FAILED";
            case TRANSIENT_NOT_SENT -> {
              state = exhausted ? "FAILED" : "QUEUED";
              if (exhausted) {
                code = SalaryAdvanceHandoffOutcome.RETRIES_EXHAUSTED;
              }
            }
            default -> state = "DELIVERY_UNKNOWN";
          }
          Instant nextAt =
              "QUEUED".equals(state)
                  ? now.plus(SalaryAdvanceHandoffRetry.delayAfter(attempts - roundBase))
                  : null;
          Next next =
              new Next(
                  state,
                  "RECEIVED".equals(state) ? "RECEIVED" : null,
                  null,
                  attempts,
                  current.reconcileCount(),
                  current.adminRetryCount(),
                  nextAt,
                  null,
                  now);
          String reference = "RECEIVED".equals(state) ? boundedReference(result.reference()) : null;
          leaveSending(current, next, code, reference, correlation, now);
          return null;
        });
  }

  /**
   * Leaves SENDING: the delivery event, the finished attempt, the acknowledgement when received,
   * the state, the audit record and the outbox event, all in the caller's transaction.
   */
  private boolean leaveSending(
      HandoffRow row,
      Next next,
      SalaryAdvanceHandoffOutcome outcome,
      String reference,
      String correlation,
      Instant now) {
    handoffs.insertEvent(
        row.tenantId(),
        row.id(),
        new EventRow(
            handoffs.nextEventSequence(row.tenantId(), row.id()),
            "SENDING",
            next.state(),
            SYSTEM,
            SalaryAdvanceHandoffRecorder.WORKER_ACTOR,
            now,
            outcome.name()));
    handoffs.insertAttempt(
        row.tenantId(),
        row.id(),
        new AttemptRow(row.attemptCount(), startedAt(row, now), now, outcome.name(), null));
    if (reference != null) {
      handoffs.insertAck(row.tenantId(), row.id(), reference, now, "DELIVERY");
    }
    if (handoffs.update(row.tenantId(), row.id(), row.version(), next) != 1) {
      return false;
    }
    handoffs.checkDeferred(row.tenantId());
    recorder.delivered(
        row.tenantId(),
        correlation,
        new SalaryAdvanceHandoffRecorder.Change(
            row.id(),
            row.requestId(),
            row.legalEntityId(),
            row.currency(),
            next.state(),
            next.reviewState(),
            row.version() + 1,
            outcome.name(),
            null,
            row.attemptCount(),
            SYSTEM,
            now));
    Counter.builder(METRIC)
        .tag("state", next.state())
        .tag("outcome", outcome.name())
        .register(registry)
        .increment();
    LOG.atInfo()
        .addKeyValue("operation", "salary-advance-handoff.transition")
        .addKeyValue("state", next.state())
        .addKeyValue("outcome", outcome.name())
        .addKeyValue("attempt", row.attemptCount())
        .addKeyValue("correlationId", correlation)
        .log("salary_advance_handoff_transition");
    return true;
  }

  /** The attempt started when the claim last updated the row (never later than it finished). */
  private static Instant startedAt(HandoffRow row, Instant finished) {
    Instant started = row.updatedAt();
    return started.isAfter(finished) ? finished : started;
  }

  private static String boundedReference(String reference) {
    if (reference == null || reference.isBlank() || reference.length() > 80) {
      return "SYNTHETIC-RECEIVED";
    }
    return reference;
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  private static String correlation() {
    return "hfw-" + UUID.randomUUID();
  }

  private <T> T transaction(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(30);
    return template.execute(status -> work.get());
  }
}
