package com.divalhr.core.operations.application;

import com.divalhr.core.operations.internal.ExpenseConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared transaction, metric and failure helpers of the expense services (MVP-081). */
final class ExpenseCalls {

  /** Explicit database timeout of every read and command transaction (D81-13). */
  static final Duration TIMEOUT = Duration.ofSeconds(15);

  private ExpenseCalls() {}

  /**
   * A bounded read transaction; the result is returned only after it committed (so a disclosure is
   * audited before anything is returned).
   *
   * @param transactionManager transaction manager
   * @param work the read
   * @param <T> result type
   * @return the result
   */
  static <T> T read(PlatformTransactionManager transactionManager, Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    return result;
  }

  /**
   * An integrity failure of a command. The application checks the version, the transition and the
   * route under the row lock, so a guard that still fires is a defect, except the two open-expense
   * limits, which are business answers; no database text is exposed.
   *
   * @param violated the failure
   * @return the exception to throw
   */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    var constraint = ExpenseConstraintViolations.constraint(violated);
    if (constraint
        .filter(
            name ->
                name.equals(ExpenseConstraintViolations.TENANT_LIMIT)
                    || name.equals(ExpenseConstraintViolations.EMPLOYEE_LIMIT))
        .isPresent()) {
      return new ApiException(ErrorCode.EXPENSE_LIMIT_REACHED, java.util.Map.of());
    }
    if (constraint.filter(ExpenseConstraintViolations.CONSISTENCY::contains).isPresent()) {
      return new IllegalStateException("expense change inconsistent with its record");
    }
    return violated;
  }

  /**
   * Records a read's outcome, counting link denials and unknown expenses on the bounded
   * self-service counter.
   */
  static <T> T recorded(
      OperationMetrics metrics,
      MeterRegistry meters,
      Logger log,
      String operation,
      boolean selfService,
      Supplier<T> work) {
    try {
      T result = counted(meters, operation, selfService, work);
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case EXPENSE_NOT_FOUND -> Outcome.NOT_FOUND;
            default -> Outcome.VALIDATION_FAILED;
          };
      metrics.record(operation, outcome);
      log.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("expense_read_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      throw failure;
    }
  }

  /** Counts the self-service denials on the bounded counter only. */
  static <T> T counted(
      MeterRegistry meters, String operation, boolean selfService, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException denied) {
      if (selfService) {
        if (denied.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
          ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "link_required");
        } else if (denied.code() == ErrorCode.EXPENSE_NOT_FOUND) {
          ScopeAuthorizationInterceptor.selfServiceDenied(meters, operation, "not_found");
        }
      }
      throw denied;
    }
  }
}
