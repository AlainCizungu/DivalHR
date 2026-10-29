package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CompensationResponses.Mine;
import com.divalhr.core.payroll.application.CompensationReader.Loaded;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An employee's own monthly compensation (MVP-093A, D93-2): read only, bound on every request to
 * the caller's own active employee-access link. The employee id never comes from the path or the
 * body, the explanation is never returned and nothing is cancellable. A caller without an active
 * link is {@code 403 EMPLOYEE_LINK_REQUIRED}. Managers, substitutes and platform administrators
 * have no operation. Restricted financial data: the disclosure audit counts rows only.
 */
@Service
public class MyCompensationService {

  /** Own read (interceptor operation and cursor binding). */
  public static final String READ = "compensation.self-read";

  /** Per-subject own reads. */
  public static final String SUBJECT_READ_BUCKET = "compensation-self-read";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.compensation");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  private final EmployeeAccessLinks links;
  private final JdbcCompensationRepository compensation;
  private final CompensationReader reader;
  private final CompensationCalendar calendar;
  private final CompensationRecorder recorder;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param links the identity port (joins this module's transactions)
   * @param compensation compensation storage
   * @param reader read model
   * @param calendar business date and zone
   * @param recorder disclosure audit
   * @param metrics operation metrics
   * @param meters bounded self-service denial counter
   * @param transactionManager transaction manager
   */
  public MyCompensationService(
      EmployeeAccessLinks links,
      JdbcCompensationRepository compensation,
      CompensationReader reader,
      CompensationCalendar calendar,
      CompensationRecorder recorder,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.links = links;
    this.compensation = compensation;
    this.reader = reader;
    this.calendar = calendar;
    this.recorder = recorder;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  /**
   * The caller's own compensation and a page of its history.
   *
   * @param caller verified employee
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the record, after its disclosure audit committed
   */
  public Mine read(PayrollCaller caller, String cursor, String limit) {
    try {
      FieldErrors errors = new FieldErrors();
      int size = CompensationReader.limit(limit, errors);
      errors.throwIfAny();
      Mine result = inReadTransaction(caller, cursor, size);
      metrics.record(READ, Outcome.LISTED);
      LOG.atInfo()
          .addKeyValue("operation", READ)
          .addKeyValue("outcome", "listed")
          .log("compensation_read");
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case EMPLOYEE_LINK_REQUIRED -> Outcome.DENIED;
            case VALIDATION_FAILED, CURSOR_INVALID -> Outcome.VALIDATION_FAILED;
            default -> Outcome.STATE_CONFLICT;
          };
      if (rejected.code() == ErrorCode.EMPLOYEE_LINK_REQUIRED) {
        ScopeAuthorizationInterceptor.selfServiceDenied(meters, READ, "link_required");
      }
      metrics.record(READ, outcome);
      LOG.atInfo()
          .addKeyValue("operation", READ)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("compensation_read_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(READ, Outcome.FAILURE);
      if (DatabaseTimeouts.isTimeout(failure)) {
        throw new ApiException(ErrorCode.EMPLOYMENT_CHANGE_TIMEOUT, Map.of());
      }
      throw failure;
    }
  }

  private Mine inReadTransaction(PayrollCaller caller, String cursor, int size) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TIMEOUT.toSeconds());
    Supplier<Mine> work =
        () -> {
          compensation.limits(caller.tenant(), TIMEOUT);
          SelfLink link =
              links
                  .linkedEmployee(caller.tenant(), caller.subject())
                  .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
          Loaded loaded =
              reader.load(
                  caller.tenant(), link.employeeId(), caller.subject(), false, READ, cursor, size);
          recorder.disclosed(
              caller,
              cursor == null ? CompensationService.READ : CompensationService.HISTORY_READ,
              link.employeeId(),
              "own-record",
              cursor == null,
              loaded.changes().size(),
              calendar.now());
          return new Mine(
              loaded.businessDate(),
              loaded.current(),
              loaded.scheduled(),
              loaded.changes(),
              loaded.nextCursor());
        };
    Mine result = template.execute(status -> work.get());
    return Objects.requireNonNull(result, "read transaction returned nothing");
  }
}
