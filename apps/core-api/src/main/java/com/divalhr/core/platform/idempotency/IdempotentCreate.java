package com.divalhr.core.platform.idempotency;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared idempotent-create flow introduced by MVP-001: validate, fingerprint the normalized
 * command, then in one transaction reserve the key, replay or create (business rows, audit and
 * outbox written by {@code work}), and store the response. Emits low-cardinality outcome metrics
 * and safe structured logs; never logs names, keys, tokens or bodies.
 */
@Component
public class IdempotentCreate {

  private static final Logger LOG = LoggerFactory.getLogger(IdempotentCreate.class);

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
  public IdempotentCreate(
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
   * Identifies an operation for idempotency scope, metrics and log messages.
   *
   * @param operation stable operation name, e.g. {@code organization.create}
   * @param resource log-message prefix, e.g. {@code organization}
   */
  public record Operation(String operation, String resource) {}

  /**
   * The outcome of the business work inside the transaction.
   *
   * @param body response body to return and store for replay
   * @param resourceId created resource
   * @param <R> body type
   */
  public record Created<R>(R body, UUID resourceId) {}

  /**
   * The result returned to the caller.
   *
   * @param body created or replayed body
   * @param replayed whether an earlier response was replayed
   * @param <R> body type
   */
  public record Result<R>(R body, boolean replayed) {}

  /**
   * Runs validation, recording the {@code validation_failed} outcome on failure.
   *
   * @param operation operation
   * @param validation validation producing the normalized command
   * @param <C> command type
   * @return normalized command
   */
  public <C> C validated(Operation operation, Supplier<C> validation) {
    try {
      return validation.get();
    } catch (ApiException invalid) {
      metrics.record(operation.operation(), Outcome.VALIDATION_FAILED);
      LOG.atInfo()
          .addKeyValue("operation", operation.operation())
          .addKeyValue("outcome", "validation_failed")
          .addKeyValue("code", invalid.code().name())
          .log(operation.resource() + "_create_rejected");
      throw invalid;
    }
  }

  /**
   * Executes the idempotent create.
   *
   * @param operation operation
   * @param principal verified JWT subject
   * @param key idempotency key (already validated)
   * @param canonical key-sorted normalized command, including any scope it depends on
   * @param responseType body type for replay
   * @param work business writes, run inside the transaction after the key is reserved
   * @param <R> body type
   * @return created or replayed result
   */
  public <R> Result<R> execute(
      Operation operation,
      String principal,
      String key,
      Map<String, Object> canonical,
      Class<R> responseType,
      Supplier<Created<R>> work) {
    IdempotencyScope scope = new IdempotencyScope(operation.operation(), principal, key);
    String fingerprint = Fingerprints.sha256(json.writeValueAsString(canonical));
    try {
      Result<R> result =
          transactions.execute(
              status -> {
                IdempotencyDecision decision = idempotency.reserve(scope, fingerprint);
                if (decision instanceof IdempotencyDecision.Replay replay) {
                  return new Result<>(json.readValue(replay.response().body(), responseType), true);
                }
                Created<R> created = work.get();
                idempotency.complete(
                    scope,
                    new StoredResponse(
                        201, json.writeValueAsString(created.body()), created.resourceId()));
                return new Result<>(created.body(), false);
              });
      if (result == null) {
        throw new IllegalStateException("transaction returned no result");
      }
      Outcome outcome = result.replayed() ? Outcome.REPLAYED : Outcome.CREATED;
      metrics.record(operation.operation(), outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation.operation())
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .log(
              result.replayed()
                  ? operation.resource() + "_create_replayed"
                  : operation.resource() + "_created");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome = outcomeOf(rejected.code());
      metrics.record(operation.operation(), outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation.operation())
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log(operation.resource() + "_create_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation.operation(), Outcome.FAILURE);
      throw failure;
    }
  }

  private static Outcome outcomeOf(ErrorCode code) {
    return switch (code) {
      case IDEMPOTENCY_KEY_REUSED -> Outcome.IDEMPOTENCY_CONFLICT;
      case DUPLICATE_LEGAL_ENTITY_CODE, DUPLICATE_SITE_CODE -> Outcome.DUPLICATE_CONFLICT;
      case LEGAL_ENTITY_NOT_FOUND, NOT_FOUND -> Outcome.NOT_FOUND;
      case ACCESS_DENIED, TENANT_ACCESS_DENIED, TENANT_CONTEXT_MISSING -> Outcome.DENIED;
      case INTERNAL_ERROR -> Outcome.FAILURE;
      default -> Outcome.VALIDATION_FAILED;
    };
  }
}
