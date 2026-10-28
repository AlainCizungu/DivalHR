package com.divalhr.core.operations.application;

import com.divalhr.core.operations.domain.ExpenseCategory;
import com.divalhr.core.operations.domain.ExpenseCurrency;
import com.divalhr.core.operations.domain.ExpenseState;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.pagination.CursorScope;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The list parameters of expenses (MVP-081, D81-10): page size, filters and the cursor's binding.
 * The cursor is bound to the operation, tenant, the verified subject and role scope (a digest), every
 * filter and the page size, so it cannot be replayed across tenants, actors, roles, filters or
 * sizes. It carries only an expense identifier: never an amount, merchant, description or name.
 */
final class ExpensePaging {

  /** The constant code of an expense cursor; the row is looked up by id on decode. */
  static final String CURSOR_CODE = "EX";

  private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private ExpensePaging() {}

  /**
   * The filters of a list.
   *
   * @param employeeId owner filter (administrator) or {@code null}
   * @param state state or {@code null}
   * @param currency currency or {@code null}
   * @param category category or {@code null}
   * @param from earliest business date or {@code null}
   * @param to latest business date or {@code null}
   */
  record Filter(
      UUID employeeId,
      ExpenseState state,
      ExpenseCurrency currency,
      ExpenseCategory category,
      LocalDate from,
      LocalDate to) {

    boolean any() {
      return employeeId != null
          || state != null
          || currency != null
          || category != null
          || from != null
          || to != null;
    }
  }

  /**
   * Parses the filters; any defect is a field error with no echoed value.
   *
   * @param employee {@code employeeId} value or {@code null}
   * @param state {@code state} value or {@code null}
   * @param currency {@code currency} value or {@code null}
   * @param category {@code category} value or {@code null}
   * @param from {@code from} value or {@code null}
   * @param to {@code to} value or {@code null}
   * @return the filter
   */
  static Filter filter(
      String employee, String state, String currency, String category, String from, String to) {
    FieldErrors errors = new FieldErrors();
    UUID employeeId = null;
    if (employee != null) {
      if (UUID_SHAPE.matcher(employee).matches()) {
        employeeId = UUID.fromString(employee.toLowerCase(Locale.ROOT));
      } else {
        errors.add("employeeId", Constraint.FORMAT);
      }
    }
    ExpenseState expenseState = value(errors, "state", state, ExpenseState.class);
    ExpenseCurrency expenseCurrency = value(errors, "currency", currency, ExpenseCurrency.class);
    ExpenseCategory expenseCategory = value(errors, "category", category, ExpenseCategory.class);
    LocalDate start = date(errors, "from", from);
    LocalDate end = date(errors, "to", to);
    if (start != null && end != null && start.isAfter(end)) {
      errors.add("to", Constraint.RANGE);
    }
    errors.throwIfAny();
    return new Filter(employeeId, expenseState, expenseCurrency, expenseCategory, start, end);
  }

  private static <E extends Enum<E>> E value(
      FieldErrors errors, String field, String raw, Class<E> type) {
    if (raw == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }

  private static LocalDate date(FieldErrors errors, String field, String raw) {
    if (raw == null) {
      return null;
    }
    if (!DATE_SHAPE.matcher(raw).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException invalid) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
  }

  /**
   * The cursor's binding.
   *
   * @param operation operation name
   * @param caller verified caller
   * @param actorScope {@code self}, {@code manager} or {@code admin}
   * @param filter filters
   * @param size page size
   * @return the scope
   */
  static CursorScope scope(
      String operation, OperationsCaller caller, String actorScope, Filter filter, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put(
        "subject", Fingerprints.sha256("DIVALHR-EXPENSE-ACTOR\nsubject=" + caller.subject()));
    filters.put("scope", actorScope);
    filters.put("employee", filter.employeeId() == null ? "" : filter.employeeId().toString());
    filters.put("state", filter.state() == null ? "" : filter.state().name());
    filters.put("currency", filter.currency() == null ? "" : filter.currency().name());
    filters.put("category", filter.category() == null ? "" : filter.category().name());
    filters.put("from", filter.from() == null ? "" : filter.from().toString());
    filters.put("to", filter.to() == null ? "" : filter.to().toString());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(operation, caller.tenant(), filters);
  }

  /**
   * The one answer for a cursor that is not valid for this caller, scope or page.
   *
   * @return {@code 400 CURSOR_INVALID}
   */
  static ApiException invalidCursor() {
    return new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
  }
}
