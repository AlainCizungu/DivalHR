package com.divalhr.core.platform.idempotency;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared idempotent command flow: validate, fingerprint the normalized command, then in one
 * transaction reserve the key, replay or run the business work (business rows, audit and outbox
 * written by {@code work}), and store the response. Operation-neutral: it serves creates (through
 * {@link IdempotentCreate}) and state changes such as a site's first region assignment.
 *
 * <p>Emits low-cardinality outcome metrics and safe structured logs whose event names come from the
 * operation's {@link Spec}; never logs names, keys, tokens, cursors or bodies.
 */
@Component
public class IdempotentOperation {

  private static final Logger LOG = LoggerFactory.getLogger(IdempotentOperation.class);

  /** Outcomes a successful, non-replayed execution may report. */
  private static final Set<Outcome> COMPLETED_OUTCOMES =
      EnumSet.of(Outcome.CREATED, Outcome.UPDATED, Outcome.UNCHANGED);

  private final IdempotencyService idempotency;
  private final OperationMetrics metrics;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  /**
   * Creates the helper.
   *
   * @param idempotency idempotency records
   * @param metrics operation metrics
   * @param transactions transaction template
   * @param json JSON mapper
   */
  public IdempotentOperation(
      IdempotencyService idempotency,
      OperationMetrics metrics,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.idempotency = idempotency;
    this.metrics = metrics;
    this.transactions = transactions;
    this.json = json;
  }

  /**
   * Identifies an operation for idempotency scope, metrics and log event names.
   *
   * <p>Log events are {@code <resource>_<pastTense>} on success, {@code
   * <resource>_<verb>_unchanged} when nothing changed, and {@code <resource>_<verb>_replayed} /
   * {@code <resource>_<verb>_rejected} otherwise; e.g. {@code site_created}, {@code
   * site_create_replayed}, {@code site_region_assigned}, {@code site_region_assign_unchanged}.
   *
   * @param operation stable operation name, e.g. {@code site.region.assign}
   * @param resource log event prefix, e.g. {@code site_region}
   * @param verb log event verb, e.g. {@code assign}
   * @param pastTense log event suffix on success, e.g. {@code assigned}
   * @param successStatus 2xx HTTP status stored for replay, e.g. 200
   */
  public record Spec(
      String operation, String resource, String verb, String pastTense, int successStatus) {

    /** Requires every component and a 2xx status. */
    public Spec {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(resource, "resource");
      Objects.requireNonNull(verb, "verb");
      Objects.requireNonNull(pastTense, "pastTense");
      if (successStatus < 200 || successStatus > 299) {
        throw new IllegalArgumentException("successStatus must be 2xx");
      }
    }

    private String rejectedEvent() {
      return resource + "_" + verb + "_rejected";
    }

    private String replayedEvent() {
      return resource + "_" + verb + "_replayed";
    }

    private String completedEvent(Outcome outcome) {
      return outcome == Outcome.UNCHANGED
          ? resource + "_" + verb + "_unchanged"
          : resource + "_" + pastTense;
    }
  }

  /**
   * The outcome of the business work inside the transaction.
   *
   * @param body response body to return and store for replay
   * @param resourceId affected resource
   * @param outcome {@code CREATED}, {@code UPDATED} or {@code UNCHANGED}
   * @param <R> body type
   */
  public record Completed<R>(R body, UUID resourceId, Outcome outcome) {

    /** Requires every component and a success outcome. */
    public Completed {
      Objects.requireNonNull(body, "body");
      Objects.requireNonNull(resourceId, "resourceId");
      Objects.requireNonNull(outcome, "outcome");
      if (!COMPLETED_OUTCOMES.contains(outcome)) {
        throw new IllegalArgumentException("not a completion outcome");
      }
    }
  }

  /**
   * The result returned to the caller.
   *
   * @param body completed or replayed body
   * @param replayed whether an earlier response for this key was replayed
   * @param <R> body type
   */
  public record Result<R>(R body, boolean replayed) {}

  /**
   * Runs validation, recording the {@code validation_failed} outcome on failure.
   *
   * @param spec operation
   * @param validation validation producing the normalized command
   * @param <C> command type
   * @return normalized command
   */
  public <C> C validated(Spec spec, Supplier<C> validation) {
    try {
      return validation.get();
    } catch (ApiException invalid) {
      metrics.record(spec.operation(), Outcome.VALIDATION_FAILED);
      LOG.atInfo()
          .addKeyValue("operation", spec.operation())
          .addKeyValue("outcome", "validation_failed")
          .addKeyValue("code", invalid.code().name())
          .log(spec.rejectedEvent());
      throw invalid;
    }
  }

  /**
   * Executes the idempotent command.
   *
   * @param spec operation
   * @param principal verified JWT subject
   * @param key idempotency key (already validated)
   * @param canonical key-sorted normalized command, including any scope it depends on
   * @param responseType body type for replay
   * @param work business writes, run inside the transaction after the key is reserved
   * @param <R> body type
   * @return completed or replayed result
   */
  public <R> Result<R> execute(
      Spec spec,
      String principal,
      String key,
      Map<String, Object> canonical,
      Class<R> responseType,
      Supplier<Completed<R>> work) {
    IdempotencyScope scope = new IdempotencyScope(spec.operation(), principal, key);
    String fingerprint = Fingerprints.sha256(json.writeValueAsString(canonical));
    try {
      Execution<R> execution =
          transactions.execute(
              status -> {
                IdempotencyDecision decision = idempotency.reserve(scope, fingerprint);
                if (decision instanceof IdempotencyDecision.Replay replay) {
                  return new Execution<>(
                      json.readValue(replay.response().body(), responseType), Outcome.REPLAYED);
                }
                Completed<R> completed = work.get();
                idempotency.complete(
                    scope,
                    new StoredResponse(
                        spec.successStatus(),
                        json.writeValueAsString(completed.body()),
                        completed.resourceId()));
                return new Execution<>(completed.body(), completed.outcome());
              });
      if (execution == null) {
        throw new IllegalStateException("transaction returned no result");
      }
      Outcome outcome = execution.outcome();
      metrics.record(spec.operation(), outcome);
      LOG.atInfo()
          .addKeyValue("operation", spec.operation())
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .log(outcome == Outcome.REPLAYED ? spec.replayedEvent() : spec.completedEvent(outcome));
      return new Result<>(execution.body(), outcome == Outcome.REPLAYED);
    } catch (ApiException rejected) {
      Outcome outcome = outcomeOf(rejected.code());
      metrics.record(spec.operation(), outcome);
      LOG.atInfo()
          .addKeyValue("operation", spec.operation())
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log(spec.rejectedEvent());
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(spec.operation(), Outcome.FAILURE);
      throw failure;
    }
  }

  private record Execution<R>(R body, Outcome outcome) {}

  private static Outcome outcomeOf(ErrorCode code) {
    return switch (code) {
      case IDEMPOTENCY_KEY_REUSED -> Outcome.IDEMPOTENCY_CONFLICT;
      case DUPLICATE_LEGAL_ENTITY_CODE,
          DUPLICATE_SITE_CODE,
          DUPLICATE_DEPARTMENT_CODE,
          DUPLICATE_COST_CENTER_CODE,
          DUPLICATE_REGION_CODE,
          DUPLICATE_TEAM_CODE,
          INVITATION_ALREADY_PENDING,
          INVITATION_RECIPIENT_ALREADY_MEMBER ->
          Outcome.DUPLICATE_CONFLICT;
      case SITE_REGION_ALREADY_ASSIGNED, INVITATION_NOT_PENDING -> Outcome.STATE_CONFLICT;
      case INVITATION_RATE_LIMITED, INVITATION_RESEND_LIMITED, RATE_LIMITED -> Outcome.RATE_LIMITED;
      case LEGAL_ENTITY_NOT_FOUND,
          SITE_NOT_FOUND,
          REGION_NOT_FOUND,
          DEPARTMENT_NOT_FOUND,
          COST_CENTER_NOT_FOUND,
          INVITATION_NOT_FOUND,
          NOT_FOUND ->
          Outcome.NOT_FOUND;
      case ACCESS_DENIED, TENANT_ACCESS_DENIED, TENANT_CONTEXT_MISSING -> Outcome.DENIED;
      case INTERNAL_ERROR -> Outcome.FAILURE;
      default -> Outcome.VALIDATION_FAILED;
    };
  }
}
