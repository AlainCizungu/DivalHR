package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.SalaryAdvanceHandoffBodies.PartnerCallback;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffCommands.Callback;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffReviewState;
import com.divalhr.core.payroll.domain.SalaryAdvanceHandoffSigner;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.HandoffRow;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.Next;
import com.divalhr.core.payroll.internal.JdbcSalaryAdvanceHandoffRepository.ReviewEventRow;
import com.divalhr.core.platform.error.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed partner status callback (MVP-120B, ADR 0042 section 7). The partner is authenticated
 * by a dedicated HMAC key, never by a user, and the tenant is derived from the stored application,
 * never from the body. Checks run in a fixed order before any state is read: timestamp window,
 * signature over the exact bytes in constant time, a hourly ceiling and the closed schema. One
 * transaction then records the replay evidence (a repeated callback id fails), the review event and
 * the new review state, with audit and outbox. Unknown or foreign applications, invalid signatures,
 * stale timestamps, replays and illegal transitions all end in the same generic outcome and leave
 * no state change. Nothing from the request is logged.
 */
@Service
public class SalaryAdvanceHandoffCallbackService {

  /** The result of one callback. */
  public enum Outcome {
    /** Verified and applied. */
    ACCEPTED,
    /** Anything wrong with the callback: one generic answer. */
    INVALID,
    /** The hourly ceiling was reached. */
    RATE_LIMITED,
    /** The callback key is not configured. */
    UNAVAILABLE
  }

  /** Callbacks accepted per application per hour. */
  static final int PER_APPLICATION_PER_HOUR = 600;

  /** Callbacks accepted per instance per hour. */
  static final int GLOBAL_PER_HOUR = 6000;

  private static final Logger LOG =
      LoggerFactory.getLogger("divalhr.salary-advance-handoff.callback");
  private static final Duration WINDOW = Duration.ofSeconds(300);
  private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{10}$");
  private static final Pattern SIGNATURE = Pattern.compile("^v1=[0-9a-f]{64}$");
  private static final Pattern CORRELATION = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int PRUNE_ABOVE = 10_000;

  private final SalaryAdvancePartnerSecrets secrets;
  private final JdbcSalaryAdvanceHandoffRepository handoffs;
  private final SalaryAdvanceHandoffRecorder recorder;
  private final PlatformTransactionManager transactions;
  private final Clock clock;
  private final MeterRegistry registry;
  private final ConcurrentHashMap<String, int[]> counters = new ConcurrentHashMap<>();

  /**
   * Creates the service.
   *
   * @param secrets the callback signer
   * @param handoffs handoff storage
   * @param recorder audit and outbox
   * @param transactions transaction manager
   * @param clock clock
   * @param registry metrics
   */
  public SalaryAdvanceHandoffCallbackService(
      SalaryAdvancePartnerSecrets secrets,
      JdbcSalaryAdvanceHandoffRepository handoffs,
      SalaryAdvanceHandoffRecorder recorder,
      PlatformTransactionManager transactions,
      Clock clock,
      MeterRegistry registry) {
    this.secrets = secrets;
    this.handoffs = handoffs;
    this.recorder = recorder;
    this.transactions = transactions;
    this.clock = clock;
    this.registry = registry;
  }

  /**
   * Handles one signed callback.
   *
   * @param raw the exact request bytes (already bounded by the controller)
   * @param timestamp the {@code X-Divalhr-Timestamp} header
   * @param callbackId the {@code X-Divalhr-Callback-Id} header
   * @param signature the {@code X-Divalhr-Signature} header
   * @param correlationId the request correlation id (stored only when it is a safe bounded token)
   * @return the outcome
   */
  public Outcome receive(
      byte[] raw, String timestamp, String callbackId, String signature, String correlationId) {
    if (!secrets.callbackAvailable()) {
      return count(Outcome.UNAVAILABLE);
    }
    Instant now = Instant.now(clock);
    UUID replayId;
    long seconds;
    try {
      if (timestamp == null
          || !TIMESTAMP.matcher(timestamp).matches()
          || signature == null
          || !SIGNATURE.matcher(signature).matches()) {
        return count(Outcome.INVALID);
      }
      replayId = UUID.fromString(callbackId);
      seconds = Long.parseLong(timestamp);
    } catch (RuntimeException malformed) {
      return count(Outcome.INVALID);
    }
    if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(WINDOW) > 0) {
      return count(Outcome.INVALID);
    }
    SalaryAdvanceHandoffSigner signer = secrets.callback();
    if (!SalaryAdvanceHandoffSigner.matches(
        signer.signCallback(seconds, replayId, raw), signature)) {
      return count(Outcome.INVALID);
    }
    Callback callback;
    try {
      callback = SalaryAdvanceHandoffCommands.callback(parse(raw));
    } catch (ApiException invalid) {
      return count(Outcome.INVALID);
    }
    if (Duration.between(callback.issuedAt(), now).abs().compareTo(WINDOW) > 0) {
      return count(Outcome.INVALID);
    }
    if (!withinCeiling("global", GLOBAL_PER_HOUR, now)
        || !withinCeiling("app:" + callback.applicationId(), PER_APPLICATION_PER_HOUR, now)) {
      return count(Outcome.RATE_LIMITED);
    }
    try {
      Applied applied = apply(callback, replayId, now, safeCorrelation(correlationId));
      return count(applied == Applied.REFUSED ? Outcome.INVALID : Outcome.ACCEPTED);
    } catch (DataIntegrityViolationException replayOrGuard) {
      // A repeated callback id, or a guard that fired: the same generic answer, nothing was
      // written.
      return count(Outcome.INVALID);
    }
  }

  /** What one verified callback did. */
  private enum Applied {
    /** A new, legal transition was recorded. */
    APPLIED,
    /** The same event (id and content) was already accepted: acknowledged, nothing changed. */
    REPLAYED,
    /** Unknown, conflicting, stale, invalid or terminal: nothing changed. */
    REFUSED
  }

  private static String safeCorrelation(String correlationId) {
    return correlationId != null && CORRELATION.matcher(correlationId).matches()
        ? correlationId
        : null;
  }

  private Applied apply(Callback callback, UUID replayId, Instant now, String correlationId) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout(15);
    Applied applied =
        template.execute(
            status -> {
              HandoffRow row = handoffs.lockByApplication(callback.applicationId()).orElse(null);
              if (row == null || !"RECEIVED".equals(row.state()) || row.reviewState() == null) {
                return Applied.REFUSED;
              }
              // The same partner event id: an exact repeat is acknowledged and changes nothing; the
              // same id with other content (or for another application) is refused.
              JdbcSalaryAdvanceHandoffRepository.CallbackRow seen =
                  handoffs.callback(replayId).orElse(null);
              if (seen != null) {
                boolean same =
                    seen.tenant().equals(row.tenantId())
                        && seen.handoffId().equals(row.id())
                        && seen.reviewState().equals(callback.reviewState().name())
                        && java.util.Objects.equals(seen.reasonCode(), callback.reasonCode())
                        && seen.issuedAt()
                            .equals(callback.issuedAt().truncatedTo(ChronoUnit.MICROS));
                if (!same) {
                  refused(row, callback, correlationId, replayId, now);
                }
                return same ? Applied.REPLAYED : Applied.REFUSED;
              }
              SalaryAdvanceHandoffReviewState from =
                  SalaryAdvanceHandoffReviewState.parse(row.reviewState()).orElse(null);
              if (from == null || !from.mayMoveTo(callback.reviewState())) {
                // Terminal, already reached, stale or illegal: no history changes; the refusal is
                // audited.
                refused(row, callback, correlationId, replayId, now);
                return Applied.REFUSED;
              }
              int sequence = handoffs.nextReviewSequence(row.tenantId(), row.id());
              handoffs.insertCallback(
                  row.tenantId(),
                  row.id(),
                  replayId,
                  callback.reviewState().name(),
                  callback.reasonCode(),
                  callback.issuedAt(),
                  now,
                  correlationId);
              handoffs.insertReviewEvent(
                  row.tenantId(),
                  row.id(),
                  new ReviewEventRow(
                      sequence,
                      from.name(),
                      callback.reviewState().name(),
                      callback.reasonCode(),
                      replayId,
                      now));
              if (handoffs.update(
                      row.tenantId(),
                      row.id(),
                      row.version(),
                      new Next(
                          "RECEIVED",
                          callback.reviewState().name(),
                          callback.reasonCode(),
                          row.attemptCount(),
                          row.reconcileCount(),
                          row.adminRetryCount(),
                          null,
                          null,
                          now))
                  != 1) {
                status.setRollbackOnly();
                return Applied.REFUSED;
              }
              handoffs.checkDeferred(row.tenantId());
              recorder.partnerStatus(
                  row.tenantId(),
                  correlationId == null ? "cb-" + replayId : correlationId,
                  change(row, callback, row.version() + 1, now));
              return Applied.APPLIED;
            });
    return applied == null ? Applied.REFUSED : applied;
  }

  private void refused(
      HandoffRow row, Callback callback, String correlationId, UUID replayId, Instant now) {
    recorder.partnerStatusRefused(
        row.tenantId(),
        correlationId == null ? "cb-" + replayId : correlationId,
        change(row, callback, row.version(), now));
  }

  private static SalaryAdvanceHandoffRecorder.Change change(
      HandoffRow row, Callback callback, int version, Instant now) {
    return new SalaryAdvanceHandoffRecorder.Change(
        row.id(),
        row.requestId(),
        row.legalEntityId(),
        row.currency(),
        "RECEIVED",
        callback.reviewState().name(),
        version,
        null,
        callback.reasonCode(),
        0,
        "PARTNER",
        now);
  }

  /** Binds the bytes to the closed body; any failure is the one generic answer. */
  private static PartnerCallback parse(byte[] raw) {
    try {
      PartnerCallback body = JSON.readValue(raw, PartnerCallback.class);
      if (body == null) {
        throw SalaryAdvanceHandoffCommands.invalid();
      }
      return body;
    } catch (ApiException invalid) {
      throw invalid;
    } catch (RuntimeException malformed) {
      throw SalaryAdvanceHandoffCommands.invalid();
    }
  }

  private boolean withinCeiling(String key, int limit, Instant now) {
    Instant window = now.truncatedTo(ChronoUnit.HOURS);
    long windowKey = window.getEpochSecond();
    if (counters.size() > PRUNE_ABOVE) {
      counters.clear();
    }
    boolean[] allowed = {true};
    counters.compute(
        key,
        (k, current) -> {
          int[] live =
              current == null || current[0] != (int) windowKey
                  ? new int[] {(int) windowKey, 0}
                  : current;
          if (live[1] >= limit) {
            allowed[0] = false;
            return live;
          }
          live[1]++;
          return live;
        });
    return allowed[0];
  }

  private Outcome count(Outcome outcome) {
    Counter.builder("divalhr.partner.callbacks")
        .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
        .register(registry)
        .increment();
    if (outcome != Outcome.ACCEPTED) {
      LOG.atInfo()
          .addKeyValue("operation", "salary-advance-handoff.callback")
          .addKeyValue("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
          .log("salary_advance_handoff_callback_refused");
    }
    return outcome;
  }
}
