package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.ExpenseResponses.DetailView;
import com.divalhr.core.operations.api.ExpenseResponses.EmployeeRef;
import com.divalhr.core.operations.api.ExpenseResponses.ExpenseView;
import com.divalhr.core.operations.api.ExpenseResponses.Page;
import com.divalhr.core.operations.domain.Expense;
import com.divalhr.core.operations.internal.JdbcExpenseRepository;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.PageQuery;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.View;
import com.divalhr.core.platform.access.ReportingLines;
import com.divalhr.core.platform.access.ReportingLines.Person;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The tenant administrator's read-only expense views (MVP-081, Issue #169): list, detail with
 * history, and receipt download of submitted evidence. Authorization (role {@code tenant-admin},
 * exact MFA, verified tenant, active membership) has run before any of this. The administrator
 * cannot create, change, submit, withdraw, approve or return anything on another person's behalf:
 * no such operation exists.
 *
 * <p>An employee's private working copy stays private: an expense that was never submitted is not
 * listed and reads as the safe {@code 404 EXPENSE_NOT_FOUND}, and a returned expense shows its last
 * submitted revision, never the correction in progress. Every list, read and download is audited
 * before the data is returned.
 */
@Service
public class ExpenseService {

  /** List operation. */
  public static final String LIST = "expense.list";

  /** Read operation. */
  public static final String READ = "expense.read";

  /** Receipt download operation. */
  public static final String RECEIPT_READ = "expense.receipt-read";

  /** Per-subject read bucket. */
  public static final String SUBJECT_READ_BUCKET = "expense-read";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.operations");

  private final JdbcExpenseRepository expenses;
  private final ReportingLines lines;
  private final OperationsCalendar calendar;
  private final CursorCodec cursors;
  private final ExpenseRecorder recorder;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;

  ExpenseService(
      JdbcExpenseRepository expenses,
      ReportingLines lines,
      OperationsCalendar calendar,
      CursorCodec cursors,
      ExpenseRecorder recorder,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.expenses = expenses;
    this.lines = lines;
    this.calendar = calendar;
    this.cursors = cursors;
    this.recorder = recorder;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  /**
   * One page of the tenant's submitted expenses, newest first.
   *
   * @param caller verified administrator
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @param employee employee filter or {@code null}
   * @param state state filter or {@code null}
   * @param currency currency filter or {@code null}
   * @param category category filter or {@code null}
   * @param from earliest business date or {@code null}
   * @param to latest business date or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public Page list(
      OperationsCaller caller,
      String cursor,
      String limit,
      String employee,
      String state,
      String currency,
      String category,
      String from,
      String to) {
    return ExpenseCalls.recorded(
        metrics,
        meters,
        LOG,
        LIST,
        false,
        () -> {
          TenantId tenant = caller.tenant();
          int size = TaskPaging.limit(limit);
          ExpensePaging.Filter filter =
              ExpensePaging.filter(employee, state, currency, category, from, to);
          CursorScope scope = ExpensePaging.scope(LIST, caller, "admin", filter, size);
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          if (after != null && !ExpensePaging.CURSOR_CODE.equals(after.code())) {
            throw ExpensePaging.invalidCursor();
          }
          String page = after == null ? "first" : "next";
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                if (after != null
                    && expenses.find(tenant, View.LAST_SUBMITTED, after.id()).isEmpty()) {
                  throw ExpensePaging.invalidCursor();
                }
                Instant now = calendar.now();
                List<Expense> rows =
                    expenses.page(
                        tenant,
                        new PageQuery(
                            View.LAST_SUBMITTED,
                            filter.employeeId(),
                            null,
                            true,
                            false,
                            filter.state(),
                            filter.currency(),
                            filter.category(),
                            filter.from(),
                            filter.to(),
                            after == null ? null : after.id(),
                            size + 1));
                List<Expense> shown = rows.subList(0, Math.min(rows.size(), size));
                String next =
                    rows.size() > size
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                ExpensePaging.CURSOR_CODE, shown.get(shown.size() - 1).id()))
                        : null;
                Map<UUID, EmployeeRef> people = people(tenant, shown);
                recorder.listed(
                    caller, "admin", "tenant", tenant.value(), page, shown, filter.any(), now);
                LOG.atInfo()
                    .addKeyValue("operation", LIST)
                    .addKeyValue("page", page)
                    .addKeyValue("resultCount", shown.size())
                    .addKeyValue("outcome", "listed")
                    .log("expense_listed");
                return new Page(
                    shown.stream()
                        .map(e -> ExpenseView.of(e, people.get(e.employeeId())))
                        .toList(),
                    next,
                    now);
              });
        });
  }

  /**
   * One submitted expense with its submitted revisions and history.
   *
   * @param caller verified administrator
   * @param expenseId path value
   * @return the detail, after its disclosure audit committed
   */
  public DetailView read(OperationsCaller caller, String expenseId) {
    return ExpenseCalls.recorded(
        metrics,
        meters,
        LOG,
        READ,
        false,
        () -> {
          UUID id = ExpenseCommands.expenseId(expenseId);
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                TenantId tenant = caller.tenant();
                Expense expense = submitted(tenant, id);
                recorder.read(caller, "expense.read", "admin", expense, 0, calendar.now());
                LOG.atInfo()
                    .addKeyValue("operation", READ)
                    .addKeyValue("outcome", "read")
                    .log("expense_read");
                return DetailView.of(
                    ExpenseView.of(expense, people(tenant, List.of(expense)).get(expense.employeeId())),
                    expenses.revisions(tenant, id, true),
                    expenses.history(tenant, id, true));
              });
        });
  }

  /**
   * The receipt bound to one submitted revision.
   *
   * @param caller verified administrator
   * @param expenseId path value
   * @param revision path value
   * @return the receipt, after its disclosure audit committed
   */
  public ExpenseReceiptDownload receipt(
      OperationsCaller caller, String expenseId, String revision) {
    return ExpenseCalls.recorded(
        metrics,
        meters,
        LOG,
        RECEIPT_READ,
        false,
        () -> {
          UUID id = ExpenseCommands.expenseId(expenseId);
          int number = ExpenseCommands.revision(revision);
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                TenantId tenant = caller.tenant();
                Expense expense = submitted(tenant, id);
                var content =
                    expenses.receipt(tenant, id, number, true).orElseThrow(ExpenseCommands::notFound);
                recorder.read(
                    caller, "expense.receipt-download", "admin", expense, number, calendar.now());
                LOG.atInfo()
                    .addKeyValue("operation", RECEIPT_READ)
                    .addKeyValue("outcome", "receipt_read")
                    .log("expense_receipt_read");
                return new ExpenseReceiptDownload(content.mediaType(), content.content());
              });
        });
  }

  private Expense submitted(TenantId tenant, UUID id) {
    Expense expense =
        expenses.find(tenant, View.LAST_SUBMITTED, id).orElseThrow(ExpenseCommands::notFound);
    if (expense.submittedAt() == null) {
      throw ExpenseCommands.notFound();
    }
    return expense;
  }

  private Map<UUID, EmployeeRef> people(TenantId tenant, List<Expense> shown) {
    List<UUID> ids = shown.stream().map(Expense::employeeId).distinct().toList();
    Map<UUID, Person> found =
        lines.describe(tenant, ids).stream()
            .collect(Collectors.toMap(Person::id, Function.identity()));
    Map<UUID, EmployeeRef> refs = new HashMap<>();
    for (UUID id : ids) {
      refs.put(id, EmployeeRef.of(id, found.get(id)));
    }
    return refs;
  }
}
