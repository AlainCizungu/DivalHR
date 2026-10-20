package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.PayrollInputResponses.Summary;
import com.divalhr.core.payroll.api.PayrollVarianceResponses.Candidates;
import com.divalhr.core.payroll.api.PayrollVarianceResponses.LineView;
import com.divalhr.core.payroll.api.PayrollVarianceResponses.Review;
import com.divalhr.core.payroll.api.PayrollVarianceResponses.TotalsView;
import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.EmployeeInput;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollVariance;
import com.divalhr.core.payroll.domain.PayrollVariance.Comparison;
import com.divalhr.core.payroll.domain.PayrollVariance.Line;
import com.divalhr.core.payroll.domain.PayrollVariance.Thresholds;
import com.divalhr.core.payroll.domain.PayrollVariance.View;
import com.divalhr.core.payroll.internal.JdbcPayrollInputRepository;
import com.divalhr.core.payroll.internal.JdbcPayrollLifecycleRepository;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitKind;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Review of payroll-input variance (MVP-091, Issue #143; ADR 0024). Authorization (role {@code
 * tenant-admin} with privileged MFA) has run before this; the tenant comes from the verified
 * context only. The review is read-only: two immutable MVP-090A runs of one legal entity are
 * compared employee by employee in one bounded read transaction, with exact integer arithmetic;
 * nothing is written but the disclosure audit, no event is emitted and the thresholds are never
 * stored.
 *
 * <p>The comparison run must belong to the same tenant and legal entity, end before the current run
 * begins and cover the same inclusive number of days; anything else (and any unknown, foreign or
 * malformed run) is one safe {@code 404 PAYROLL_VARIANCE_TARGET_NOT_FOUND}. Without an explicit
 * comparison run the newest eligible one is used. Lines are ordered material first, then by largest
 * absolute minute delta, then employee ID; the cursor is bound to the tenant, both runs, the
 * thresholds, the filter and the page size. Audit metadata, logs and metrics carry periods,
 * thresholds, bounded counts and minute bands, never an identifier, an exact total, a name or a
 * cursor.
 */
@Service
public class PayrollVarianceService {

  /** Eligible comparison runs (interceptor operation and disclosure audit action). */
  public static final String CANDIDATES = "payroll-variance.candidates";

  /** A variance review (interceptor operation and disclosure audit action). */
  public static final String REVIEW = "payroll-variance.review";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payroll-variance");
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern INTEGER = Pattern.compile("^[0-9]{1,6}$");
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern POSITION = Pattern.compile("^M[01]D[0-9]{1,10}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final int DEFAULT_LIMIT = 25;
  private static final int MAX_LIMIT = 100;

  private final JdbcPayrollInputRepository runs;
  private final JdbcPayrollLifecycleRepository lifecycle;
  private final OrganizationPlacementDirectory units;
  private final CursorCodec cursors;
  private final Clock clock;
  private final AuditRecorder audit;
  private final OperationMetrics metrics;
  private final PlatformTransactionManager transactionManager;

  /**
   * Creates the service.
   *
   * @param runs run storage
   * @param lifecycle lifecycle storage (the state shown with every run)
   * @param units the tenant module's unit port (legal entity)
   * @param cursors signed cursors
   * @param clock the platform clock
   * @param audit audit recorder
   * @param metrics operation metrics
   * @param transactionManager transaction manager
   */
  public PayrollVarianceService(
      JdbcPayrollInputRepository runs,
      JdbcPayrollLifecycleRepository lifecycle,
      OrganizationPlacementDirectory units,
      CursorCodec cursors,
      Clock clock,
      AuditRecorder audit,
      OperationMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.lifecycle = lifecycle;
    this.units = units;
    this.cursors = cursors;
    this.clock = clock;
    this.audit = audit;
    this.metrics = metrics;
    this.transactionManager = transactionManager;
  }

  /**
   * The eligible comparison runs of a current run, newest period first.
   *
   * @param caller verified tenant administrator
   * @param runId the current run (path value)
   * @return the candidates, after the disclosure audit committed
   */
  public Candidates candidates(PayrollCaller caller, String runId) {
    return observed(
        CANDIDATES,
        () -> {
          UUID id = id(runId);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Run current = runs.run(tenant, id).orElseThrow(PayrollVarianceService::notFound);
                List<Run> eligible = eligible(tenant, current);
                UnitView entity = legalEntity(tenant, current);
                List<Summary> items =
                    eligible.stream().map(r -> summary(tenant, r, entity)).toList();
                Map<String, Object> metadata = base("candidates", "first");
                metadata.put("currentPeriodStart", current.scope().periodStart().toString());
                metadata.put("currentPeriodEnd", current.scope().periodEnd().toString());
                metadata.put("resultCount", items.size());
                disclose(
                    caller,
                    CANDIDATES,
                    metadata,
                    "DIVALHR-PAYROLL-VARIANCE-CANDIDATES\nversion=1\nrun="
                        + id
                        + "\ncount="
                        + items.size()
                        + "\n");
                return new Candidates(
                    summary(tenant, current, entity),
                    items,
                    eligible.isEmpty() ? null : eligible.get(0).id());
              });
        });
  }

  /**
   * One page of the variance between a current run and a comparison run.
   *
   * @param caller verified tenant administrator
   * @param runId the current run (path value)
   * @param comparisonRunId the comparison run, or {@code null} for the newest eligible one
   * @param absolute absolute minute threshold, or {@code null} for the default
   * @param percentage percentage threshold, or {@code null} for the default
   * @param viewName filter (ALL by default)
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @return the page, after the disclosure audit committed
   */
  public Review review(
      PayrollCaller caller,
      String runId,
      String comparisonRunId,
      String absolute,
      String percentage,
      String viewName,
      String cursor,
      String limit) {
    return observed(
        REVIEW,
        () -> {
          FieldErrors errors = new FieldErrors();
          int abs =
              threshold(
                  absolute,
                  PayrollVariance.DEFAULT_ABSOLUTE_MINUTES,
                  PayrollVariance.MAX_ABSOLUTE_MINUTES,
                  "absoluteMinuteThreshold",
                  errors);
          int pct =
              threshold(
                  percentage,
                  PayrollVariance.DEFAULT_PERCENTAGE,
                  PayrollVariance.MAX_PERCENTAGE,
                  "percentageThreshold",
                  errors);
          View view = view(viewName, errors);
          int size = limit(limit, errors);
          errors.throwIfAny();
          UUID currentId = id(runId);
          UUID explicit = comparisonRunId == null ? null : id(comparisonRunId);
          Thresholds thresholds = new Thresholds(abs, pct);
          return read(
              () -> {
                TenantId tenant = caller.tenant();
                Run current =
                    runs.run(tenant, currentId).orElseThrow(PayrollVarianceService::notFound);
                Run comparison = comparison(tenant, current, explicit);
                Map<String, String> filters = new TreeMap<>();
                filters.put("current", current.id().toString());
                filters.put("comparison", comparison.id().toString());
                filters.put("absolute", Integer.toString(abs));
                filters.put("percentage", Integer.toString(pct));
                filters.put("view", view.name());
                filters.put("limit", Integer.toString(size));
                CursorScope scope = new CursorScope(REVIEW, tenant, filters);
                KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
                List<EmployeeInput> now = runs.employees(tenant, current.id());
                List<EmployeeInput> before = runs.employees(tenant, comparison.id());
                Comparison compared = PayrollVariance.compare(now, before, thresholds);
                List<Line> matching = PayrollVariance.filtered(compared, view);
                List<Line> rest = after == null ? matching : following(matching, after);
                List<Line> shown = rest.subList(0, Math.min(rest.size(), size));
                String next =
                    rest.size() > size
                        ? cursors.encode(scope, position(shown.get(shown.size() - 1)))
                        : null;
                UnitView entity = legalEntity(tenant, current);
                Review review =
                    new Review(
                        summary(tenant, current, entity),
                        summary(tenant, comparison, entity),
                        explicit == null,
                        Review.thresholds(thresholds),
                        view.name(),
                        TotalsView.of(compared.totals()),
                        matching.size(),
                        shown.stream().map(LineView::of).toList(),
                        next);
                Map<String, Object> metadata = base("review", after == null ? "first" : "next");
                metadata.put("currentPeriodStart", current.scope().periodStart().toString());
                metadata.put("currentPeriodEnd", current.scope().periodEnd().toString());
                metadata.put("comparisonPeriodStart", comparison.scope().periodStart().toString());
                metadata.put("comparisonPeriodEnd", comparison.scope().periodEnd().toString());
                metadata.put("absoluteMinuteThreshold", abs);
                metadata.put("percentageThreshold", pct);
                metadata.put("filter", view.name());
                metadata.put("resultCount", shown.size());
                metadata.put(
                    "employeeCountBand",
                    PayrollInputs.countBand(
                        Math.max(
                            compared.totals().currentEmployees(),
                            compared.totals().comparisonEmployees())));
                metadata.put(
                    "materialCountBand",
                    PayrollInputs.countBand(compared.totals().materialCount()));
                metadata.put(
                    "minuteDeltaBand",
                    PayrollInputs.minuteBand(
                        (int)
                            Math.min(
                                Math.abs(compared.totals().minuteDelta()), Integer.MAX_VALUE)));
                disclose(
                    caller,
                    REVIEW,
                    metadata,
                    "DIVALHR-PAYROLL-VARIANCE-REVIEW\nversion=1\ncurrent="
                        + current.id()
                        + "\ncomparison="
                        + comparison.id()
                        + "\nabsolute="
                        + abs
                        + "\npercentage="
                        + pct
                        + "\nview="
                        + view
                        + "\nshown="
                        + shown.size()
                        + "\nmaterial="
                        + compared.totals().materialCount()
                        + "\nminuteDelta="
                        + compared.totals().minuteDelta()
                        + "\n");
                logBands(compared);
                return review;
              });
        });
  }

  // ------------------------------------------------------------------------------------------

  private Run comparison(TenantId tenant, Run current, UUID explicit) {
    if (explicit == null) {
      List<Run> eligible = eligible(tenant, current);
      if (eligible.isEmpty()) {
        throw notFound();
      }
      return eligible.get(0);
    }
    Run candidate = runs.run(tenant, explicit).orElseThrow(PayrollVarianceService::notFound);
    if (!PayrollVariance.eligible(current, candidate)) {
      throw notFound();
    }
    return candidate;
  }

  private List<Run> eligible(TenantId tenant, Run current) {
    return runs.comparable(tenant, current, PayrollVariance.MAX_CANDIDATES).stream()
        .filter(candidate -> PayrollVariance.eligible(current, candidate))
        .toList();
  }

  private UnitView legalEntity(TenantId tenant, Run run) {
    UUID id = run.scope().legalEntityId();
    UnitView view = units.resolveIds(tenant, Set.of(id)).get(id);
    return view != null && view.kind() == UnitKind.LEGAL_ENTITY ? view : null;
  }

  /** The lines after a cursor position, in review order. */
  private static List<Line> following(List<Line> lines, KeysetPosition position) {
    String code = position.code();
    if (code == null || !POSITION.matcher(code).matches() || position.id() == null) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    boolean material = code.charAt(1) == '1';
    int absolute;
    try {
      absolute = Integer.parseInt(code.substring(3));
    } catch (NumberFormatException invalid) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    return lines.stream()
        .filter(l -> PayrollVariance.after(l, material, absolute, position.id()))
        .toList();
  }

  private static KeysetPosition position(Line line) {
    return new KeysetPosition(
        "M" + (line.material() ? 1 : 0) + "D" + Math.abs(line.minuteDelta()), line.employeeId());
  }

  private static int threshold(
      String raw, int fallback, int max, String field, FieldErrors errors) {
    if (raw == null) {
      return fallback;
    }
    if (!INTEGER.matcher(raw).matches()) {
      errors.add(field, Constraint.FORMAT);
      return fallback;
    }
    int value = Integer.parseInt(raw);
    if (value > max) {
      errors.add(field, Constraint.RANGE);
      return fallback;
    }
    return value;
  }

  private static View view(String raw, FieldErrors errors) {
    if (raw == null) {
      return View.ALL;
    }
    try {
      return View.valueOf(raw);
    } catch (IllegalArgumentException invalid) {
      errors.add("view", Constraint.FORMAT);
      return View.ALL;
    }
  }

  private static int limit(String raw, FieldErrors errors) {
    if (raw == null) {
      return DEFAULT_LIMIT;
    }
    if (LIMIT.matcher(raw).matches()) {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= MAX_LIMIT) {
        return value;
      }
    }
    errors.add("limit", Constraint.RANGE);
    return DEFAULT_LIMIT;
  }

  /** A path or query identifier: malformed is the same 404 as unknown. */
  private static UUID id(String raw) {
    if (raw == null || !UUID_SHAPE.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw.toLowerCase(Locale.ROOT));
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.PAYROLL_VARIANCE_TARGET_NOT_FOUND, Map.of());
  }

  private static Map<String, Object> base(String view, String page) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", PayrollVariance.SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", page);
    return metadata;
  }

  /** The fail-closed disclosure audit: bounded metadata; identifiers only in the digest. */
  private void disclose(
      PayrollCaller caller, String action, Map<String, Object> metadata, String text) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            clock.instant().truncatedTo(ChronoUnit.MICROS),
            caller.subject(),
            action,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(text + "by=" + caller.subject() + "\n")));
  }

  private static void logBands(Comparison compared) {
    LOG.atInfo()
        .addKeyValue("operation", REVIEW)
        .addKeyValue("employeeCountBand", PayrollInputs.countBand(compared.lines().size()))
        .addKeyValue(
            "materialCountBand", PayrollInputs.countBand(compared.totals().materialCount()))
        .log("payroll_variance_reviewed");
  }

  /** Records an outcome; rejections log only their code. */
  private <T> T observed(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      metrics.record(operation, Outcome.LISTED);
      return result;
    } catch (ApiException rejected) {
      Outcome outcome =
          switch (rejected.code()) {
            case PAYROLL_VARIANCE_TARGET_NOT_FOUND -> Outcome.NOT_FOUND;
            case VALIDATION_FAILED, CURSOR_INVALID -> Outcome.VALIDATION_FAILED;
            default -> Outcome.STATE_CONFLICT;
          };
      metrics.record(operation, outcome);
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .addKeyValue("code", rejected.code().name())
          .log("payroll_variance_rejected");
      throw rejected;
    } catch (RuntimeException failure) {
      metrics.record(operation, Outcome.FAILURE);
      LOG.atWarn()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "failure")
          .log("payroll_variance_failed");
      throw failure;
    }
  }

  /** A bounded read transaction; the result is returned only after it committed. */
  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    return Objects.requireNonNull(result, "read transaction returned nothing");
  }

  private Summary summary(TenantId tenant, Run run, UnitView entity) {
    return Summary.of(
        run,
        entity,
        lifecycle.state(tenant, run.id()).orElseThrow(PayrollVarianceService::notFound));
  }
}
