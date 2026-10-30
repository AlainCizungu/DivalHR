package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shared plumbing of the payroll policy and run services (MVP-094A): bounded read transactions,
 * the outcome metrics and the safe logs of reads and commands. A log line carries the operation,
 * the outcome and a stable error code only, never an identifier of a person, an amount or a digest.
 */
@Component
public class PayrollOperations {

  private final OperationMetrics metrics;
  private final JdbcPayrollRunRepository runs;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the helper.
   *
   * @param metrics operation metrics
   * @param runs payroll storage (transaction limits)
   * @param transactionManager transaction manager
   */
  public PayrollOperations(
      OperationMetrics metrics,
      JdbcPayrollRunRepository runs,
      PlatformTransactionManager transactionManager) {
    this.metrics = metrics;
    this.runs = runs;
    this.transactionManager = transactionManager;
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  <T> T inReadTransaction(TenantId tenant, Duration timeout, Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) timeout.toSeconds());
    T result =
        template.execute(
            status -> {
              runs.limits(tenant, timeout);
              return work.get();
            });
    return Objects.requireNonNull(result, "read transaction returned nothing");
  }

  /** Records a read or preview outcome; rejections log only their code. */
  <T> T observed(Logger log, String event, String operation, Supplier<T> work) {
    return observed(log, event, operation, work, result -> Outcome.LISTED);
  }

  /**
   * Records a read or preview outcome that the result itself decides (a statutory preview that is
   * blocked or unchanged is not a plain success); rejections log only their code.
   */
  <T> T observed(
      Logger log,
      String event,
      String operation,
      Supplier<T> work,
      Function<T, Outcome> outcomeOfResult) {
    try {
      T result = work.get();
      Outcome success = outcomeOfResult.apply(result);
      metrics.record(operation, success);
      log.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", success.name().toLowerCase(Locale.ROOT))
          .log(event);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome = outcomeOf(rejected.code());
      metrics.record(operation, outcome);
      log.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log(event + "_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      if (DatabaseTimeouts.isTimeout(failure)) {
        throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_TIMEOUT, Map.of());
      }
      log.atWarn()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "failure")
          .log(event + "_failed");
      throw failure;
    }
  }

  /** A command's work; a database time limit is the stable 503 (the transaction rolled back). */
  <T> T command(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException refused) {
      throw refused;
    } catch (RuntimeException failure) {
      if (DatabaseTimeouts.isTimeout(failure)) {
        metrics.record(operation, Outcome.FAILURE);
        throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_TIMEOUT, Map.of());
      }
      throw failure;
    }
  }

  static Outcome outcomeOf(ErrorCode code) {
    return switch (code) {
      case LEGAL_ENTITY_NOT_FOUND,
          PAYROLL_RUN_NOT_FOUND,
          STATUTORY_RULE_PACK_NOT_FOUND,
          STATUTORY_CALCULATION_NOT_FOUND,
          COMPENSATION_NOT_FOUND ->
          Outcome.NOT_FOUND;
      case VALIDATION_FAILED, CURSOR_INVALID, PAYROLL_PERIOD_INVALID -> Outcome.VALIDATION_FAILED;
      case RATE_LIMITED -> Outcome.RATE_LIMITED;
      default -> Outcome.STATE_CONFLICT;
    };
  }
}
