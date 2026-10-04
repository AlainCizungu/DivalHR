package com.divalhr.core.documents.application;

import com.divalhr.core.documents.internal.ContractConstraintViolations;
import com.divalhr.core.documents.internal.ContractTransactionLimits;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared flow of MVP-030 operations: validation outcomes, fail-closed disclosures (the read and its
 * audit record commit together, or nothing is returned), bounded write transactions, timeouts and
 * the narrow, by-name mapping of V16 constraints. Logs carry the operation, a closed outcome and
 * counts only.
 */
@Component
public class ContractOperations {

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.contracts");

  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;
  private final ContractEvents events;
  private final ContractCalendar calendar;
  private final ContractTransactionLimits limits;
  private final ContractProperties properties;

  /**
   * Creates the helper.
   *
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   * @param events audit and outbox
   * @param calendar clock
   * @param limits per-transaction database limits
   * @param properties settings
   */
  public ContractOperations(
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager,
      ContractEvents events,
      ContractCalendar calendar,
      ContractTransactionLimits limits,
      ContractProperties properties) {
    this.metrics = metrics;
    this.transactionManager = transactionManager;
    this.events = events;
    this.calendar = calendar;
    this.limits = limits;
    this.properties = properties;
  }

  /**
   * What a disclosure returns and records.
   *
   * @param body response body
   * @param resourceType audited resource type
   * @param resourceId audited resource
   * @param ids disclosed identifiers
   * @param <T> body type
   */
  record Disclosure<T>(T body, String resourceType, UUID resourceId, List<UUID> ids) {}

  /**
   * Runs validation, recording a rejected outcome on failure.
   *
   * @param operation operation name
   * @param validation validation
   * @param <T> result type
   * @return the validated value
   */
  <T> T validated(String operation, Supplier<T> validation) {
    try {
      return validation.get();
    } catch (ApiException invalid) {
      rejected(operation, invalid);
      throw invalid;
    }
  }

  /**
   * Runs a read and its disclosure audit in one bounded transaction; the body is returned only
   * after both committed.
   *
   * @param caller verified caller
   * @param operation operation name
   * @param action audit action
   * @param view view name
   * @param page {@code first} or {@code next}
   * @param query the read
   * @param <T> body type
   * @return the body
   */
  <T> T disclose(
      DocumentsCaller caller,
      String operation,
      String action,
      String view,
      String page,
      Supplier<Disclosure<T>> query) {
    Disclosure<T> disclosure;
    try {
      disclosure =
          bounded(
              () ->
                  transactions()
                      .execute(
                          status -> {
                            Disclosure<T> built = query.get();
                            events.disclosed(
                                caller,
                                action,
                                built.resourceType(),
                                built.resourceId(),
                                view,
                                page,
                                built.ids(),
                                calendar.now());
                            return built;
                          }));
    } catch (ApiException rejected) {
      rejected(operation, rejected);
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
    if (disclosure == null) {
      throw new IllegalStateException("disclosure transaction returned nothing");
    }
    metrics.record(operation, Outcome.LISTED);
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("view", view)
        .addKeyValue("page", page)
        .addKeyValue("resultCount", disclosure.ids().size())
        .addKeyValue("outcome", "listed")
        .log("contract_disclosure");
    return disclosure.body();
  }

  /**
   * Runs a non-idempotent write (draft edit or delete) in one bounded transaction.
   *
   * @param operation operation name
   * @param work the write
   * @param <T> result type
   * @return the result
   */
  <T> T write(String operation, Supplier<T> work) {
    try {
      T result =
          bounded(
              () ->
                  transactions()
                      .execute(
                          status -> {
                            limit();
                            return work.get();
                          }));
      metrics.record(operation, Outcome.UPDATED);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "updated")
          .log("contract_written");
      return result;
    } catch (ApiException rejected) {
      rejected(operation, rejected);
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /**
   * Records a successful read that is not a disclosure (template reads, D14).
   *
   * @param operation operation name
   */
  void listed(String operation) {
    metrics.record(operation, Outcome.LISTED);
  }

  /** Applies the statement and lock limits to the current transaction. */
  void limit() {
    limits.apply(properties.statementTimeout());
  }

  /**
   * The write transaction timeout.
   *
   * @return timeout
   */
  java.time.Duration transactionTimeout() {
    return properties.transactionTimeout();
  }

  /**
   * Now (microseconds).
   *
   * @return now
   */
  Instant now() {
    return calendar.now();
  }

  /**
   * Turns a statement, lock or transaction timeout into {@code 503 CONTRACT_TIMEOUT}: the whole
   * transaction was rolled back.
   *
   * @param work the work
   * @param <T> result type
   * @return its result
   */
  static <T> T bounded(Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException expected) {
      throw expected;
    } catch (RuntimeException failure) {
      if (DatabaseTimeouts.isTimeout(failure)) {
        throw new ApiException(ErrorCode.CONTRACT_TIMEOUT, Map.of());
      }
      throw failure;
    }
  }

  /**
   * Maps only the named V16 constraints; anything else stays an internal error.
   *
   * @param violated database failure
   * @return the exception to throw
   */
  static RuntimeException mapped(DataAccessException violated) {
    Optional<String> constraint = ContractConstraintViolations.constraint(violated);
    if (constraint.isEmpty()) {
      return violated;
    }
    return switch (constraint.get()) {
      case "contract_template_code_unique" ->
          new ApiException(ErrorCode.CONTRACT_TEMPLATE_CODE_TAKEN, Map.of());
      case "contract_template_version_one_draft",
          "contract_template_version_one_approved",
          "contract_template_version_number_unique" ->
          new ApiException(ErrorCode.CONTRACT_TEMPLATE_VERSION_CONFLICT, Map.of());
      case "contract_template_version_immutable" ->
          new ApiException(ErrorCode.CONTRACT_TEMPLATE_NOT_DRAFT, Map.of());
      case "contract_no_overlap" -> new ApiException(ErrorCode.CONTRACT_PERIOD_OVERLAP, Map.of());
      case "contract_immutable" -> new ApiException(ErrorCode.CONTRACT_PREVIEW_CHANGED, Map.of());
      case "contract_acknowledgement_once", "contract_acknowledgement_immutable" ->
          new ApiException(ErrorCode.CONTRACT_NOT_ACKNOWLEDGEABLE, Map.of());
      default -> violated;
    };
  }

  private TransactionTemplate transactions() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) Math.max(1, properties.transactionTimeout().toSeconds()));
    return template;
  }

  private void rejected(String operation, ApiException rejected) {
    Outcome outcome =
        switch (rejected.code().status().value()) {
          case 400 -> Outcome.VALIDATION_FAILED;
          case 403 -> Outcome.DENIED;
          case 404 -> Outcome.NOT_FOUND;
          case 409 -> Outcome.STATE_CONFLICT;
          case 422 -> Outcome.VALIDATION_FAILED;
          default -> Outcome.FAILURE;
        };
    metrics.record(operation, outcome);
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
        .addKeyValue("code", rejected.code().name())
        .log("contract_request_rejected");
  }
}
