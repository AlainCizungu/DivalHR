package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CompensationResponses.ChangeView;
import com.divalhr.core.payroll.api.CompensationResponses.IntervalView;
import com.divalhr.core.payroll.api.CompensationResponses.MoneyView;
import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository.ActiveFact;
import com.divalhr.core.payroll.internal.JdbcCompensationRepository.ChangeRow;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds the read model of an employee's compensation (MVP-093A, D93-9): the interval in force on
 * the business date, the scheduled intervals and a keyset page of the immutable change history,
 * newest first. It runs inside the caller's read transaction, so the disclosure audit commits with
 * it. The cursor holds only a sequence and a change identifier and is bound to the operation,
 * tenant, subject, employee, role and page size. Restricted financial data: nothing here logs an
 * amount.
 */
@Component
public class CompensationReader {

  /** Default page size. */
  public static final int DEFAULT_LIMIT = 25;

  /** Largest page size. */
  public static final int MAX_LIMIT = 100;

  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern POSITION = Pattern.compile("^S[0-9]{1,9}$");

  private final JdbcCompensationRepository compensation;
  private final CompensationCalendar calendar;
  private final CursorCodec cursors;

  /**
   * Creates the reader.
   *
   * @param compensation compensation storage
   * @param calendar business date and zone
   * @param cursors signed cursors
   */
  public CompensationReader(
      JdbcCompensationRepository compensation, CompensationCalendar calendar, CursorCodec cursors) {
    this.compensation = compensation;
    this.calendar = calendar;
    this.cursors = cursors;
  }

  /**
   * An employee's compensation as one caller may see it.
   *
   * @param version compensation version
   * @param businessDate business date
   * @param current interval in force, or {@code null}
   * @param scheduled intervals starting after the business date
   * @param changes a page of changes, newest first
   * @param nextCursor next cursor, or {@code null}
   */
  record Loaded(
      int version,
      LocalDate businessDate,
      IntervalView current,
      List<IntervalView> scheduled,
      List<ChangeView> changes,
      String nextCursor) {

    @Override
    public String toString() {
      return "Loaded[" + version + "]";
    }
  }

  /**
   * Validates a page size.
   *
   * @param raw the raw value, or {@code null}
   * @param errors collector
   * @return the size
   */
  static int limit(String raw, FieldErrors errors) {
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

  /**
   * Loads one page. Call inside a read transaction.
   *
   * @param tenant verified tenant
   * @param employeeId employee
   * @param subject verified subject (bound into the cursor)
   * @param admin whether the caller is the tenant administrator (explanations, cancellable)
   * @param operation the operation the cursor is bound to
   * @param cursor the cursor, or {@code null} for the first page
   * @param size page size
   * @return the loaded page
   */
  Loaded load(
      TenantId tenant,
      UUID employeeId,
      String subject,
      boolean admin,
      String operation,
      String cursor,
      int size) {
    ZoneId zone = calendar.zone(tenant);
    LocalDate today = calendar.today(tenant);
    Map<String, String> filters = new TreeMap<>();
    filters.put("employee", employeeId.toString());
    filters.put("role", admin ? "tenant-admin" : "employee");
    filters.put("subject", subject);
    filters.put("limit", Integer.toString(size));
    CursorScope scope = new CursorScope(operation, tenant, filters);
    Integer beforeSeq = null;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      beforeSeq = sequence(tenant, employeeId, position);
    }
    int version = compensation.version(tenant, employeeId);
    List<ActiveFact> active = compensation.active(tenant, employeeId);
    List<ChangeRow> rows = compensation.page(tenant, employeeId, beforeSeq, size + 1);
    List<ChangeRow> shown = rows.subList(0, Math.min(rows.size(), size));
    String next = null;
    if (rows.size() > size) {
      ChangeRow last = shown.get(shown.size() - 1);
      next = cursors.encode(scope, new KeysetPosition("S" + last.seq(), last.id()));
    }
    IntervalView current = null;
    List<IntervalView> scheduled = new ArrayList<>();
    for (ActiveFact entry : active) {
      Interval interval = entry.fact().interval();
      boolean started = !interval.from().isAfter(today);
      boolean cancellable =
          admin && !started && entry.originAction() == CompensationAction.CHANGED;
      if (started && (interval.to() == null || !interval.to().isBefore(today))) {
        current = interval(interval, cancellable);
      } else if (!started) {
        scheduled.add(interval(interval, cancellable));
      }
    }
    List<ChangeView> changes = new ArrayList<>();
    for (ChangeRow row : shown) {
      changes.add(change(row, admin, zone));
    }
    return new Loaded(version, today, current, scheduled, changes, next);
  }

  private int sequence(TenantId tenant, UUID employeeId, KeysetPosition position) {
    if (!POSITION.matcher(position.code()).matches()) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    int seq = Integer.parseInt(position.code().substring(1));
    boolean known =
        compensation
            .change(tenant, employeeId, position.id())
            .filter(row -> row.seq() == seq)
            .isPresent();
    if (!known) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    return seq;
  }

  // ------------------------------------------------------------------------------------------
  // Views
  // ------------------------------------------------------------------------------------------

  static MoneyView money(CompensationMoney money) {
    return new MoneyView(money.currency().name(), money.amount());
  }

  static IntervalView interval(Interval interval, boolean cancellable) {
    return new IntervalView(
        interval.origin(),
        money(interval.money()),
        "MONTHLY",
        interval.from(),
        interval.to(),
        cancellable);
  }

  static List<IntervalView> intervals(List<Interval> intervals) {
    List<IntervalView> views = new ArrayList<>();
    for (Interval interval : intervals) {
      views.add(interval(interval, false));
    }
    return views;
  }

  static ChangeView change(ChangeRow row, boolean admin, ZoneId zone) {
    LocalDate recordedOn = LocalDate.ofInstant(row.occurredAt(), zone);
    return new ChangeView(
        row.id(),
        row.seq(),
        row.action().name(),
        money(row.money()),
        row.effectiveFrom(),
        row.reason() == null ? null : row.reason().name(),
        admin ? row.explanation() : null,
        row.targetChangeId(),
        row.effectiveFrom().isBefore(recordedOn),
        row.occurredAt());
  }
}
