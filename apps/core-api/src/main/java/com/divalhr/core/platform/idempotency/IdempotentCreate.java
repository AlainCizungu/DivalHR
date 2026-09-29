package com.divalhr.core.platform.idempotency;

import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The idempotent-create flow introduced by MVP-001, kept as a compatibility facade over the
 * operation-neutral {@link IdempotentOperation}: creates store and replay {@code 201}, report the
 * {@code created} outcome, and keep their log event names ({@code <resource>_created}, {@code
 * <resource>_create_replayed}, {@code <resource>_create_rejected}).
 */
@Component
public class IdempotentCreate {

  private static final int CREATED_STATUS = 201;

  private final IdempotentOperation operations;

  /**
   * Creates the facade.
   *
   * @param operations the shared idempotent command flow
   */
  public IdempotentCreate(IdempotentOperation operations) {
    this.operations = operations;
  }

  /**
   * Identifies a create operation for idempotency scope, metrics and log messages.
   *
   * @param operation stable operation name, e.g. {@code organization.create}
   * @param resource log-message prefix, e.g. {@code organization}
   */
  public record Operation(String operation, String resource) {

    private IdempotentOperation.Spec spec() {
      return new IdempotentOperation.Spec(operation, resource, "create", "created", CREATED_STATUS);
    }
  }

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
    return operations.validated(operation.spec(), validation);
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
    IdempotentOperation.Result<R> result =
        operations.execute(
            operation.spec(),
            principal,
            key,
            canonical,
            responseType,
            () -> {
              Created<R> created = work.get();
              return new IdempotentOperation.Completed<>(
                  created.body(), created.resourceId(), Outcome.CREATED);
            });
    return new Result<>(result.body(), result.replayed());
  }
}
