package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.ExpenseResponses.CommandReceipt;
import com.divalhr.core.operations.api.ExpenseResponses.DetailView;
import com.divalhr.core.operations.api.ExpenseResponses.EmployeeRef;
import com.divalhr.core.operations.api.ExpenseResponses.ExpenseView;
import com.divalhr.core.operations.api.ExpenseResponses.Page;
import com.divalhr.core.operations.api.ExpenseVersionRequest;
import com.divalhr.core.operations.api.ReturnExpenseRequest;
import com.divalhr.core.operations.domain.Expense;
import com.divalhr.core.operations.domain.ExpenseActionType;
import com.divalhr.core.operations.domain.ExpenseState;
import com.divalhr.core.operations.internal.JdbcExpenseRepository;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.Change;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.Fields;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.PageQuery;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.View;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.access.ReportingLines;
import com.divalhr.core.platform.access.ReportingLines.Person;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The effective manager's expense queue (MVP-081, Issue #169): list and read the {@code SUBMITTED}
 * expenses that currently route to the caller, download their receipts, approve or return them.
 *
 * <p>Authorization (role {@code employee}, verified tenant, active membership) has run before any
 * of this. The route is resolved again on every list, read, download and decision from the current
 * employment history, so a manager who loses the line loses access at once; an expense that is
 * unknown, foreign, not submitted or no longer routed to the caller is the same safe {@code 404
 * EXPENSE_NOT_FOUND}. A decision takes the tenant's manager-graph lock (step 1), the caller's link
 * (step 4) and the expense row (step 5) in that order, then re-checks the route, the version and
 * the state under the lock. Approval is a decision about the claim: it is not reimbursement or
 * payment, and nothing here moves money.
 */
@Service
public class ExpenseApprovalService {

  /** Approve operation. */
  public static final String APPROVE = "expense.approval-approve";

  /** Return operation. */
  public static final String RETURN = "expense.approval-return";

  /** List operation. */
  public static final String LIST = "expense.approval-list";

  /** Read operation. */
  public static final String READ = "expense.approval-read";

  /** Receipt download operation. */
  public static final String RECEIPT_READ = "expense.approval-receipt-read";

  /** Event of an approval. */
  public static final String APPROVED_EVENT = "operations.expense.approved.v1";

  /** Event of a return. */
  public static final String RETURNED_EVENT = "operations.expense.returned.v1";

  /** Per-subject read bucket. */
  public static final String SUBJECT_READ_BUCKET = "expense-approval-read";

  /** Per-subject decision bucket. */
  public static final String SUBJECT_DECISION_BUCKET = "expense-decision";

  /** Per-tenant decision bucket. */
  public static final String TENANT_DECISION_BUCKET = "expense-decision";

  private static final int REPORT_CANDIDATES = 1000;
  private static final int SCAN_BATCH = 100;
  private static final int SCAN_BATCHES = 5;
  private static final String ROLE = "manager";
  private static final Logger LOG = LoggerFactory.getLogger("divalhr.operations");
  private static final IdempotentOperation.Spec APPROVE_SPEC =
      new IdempotentOperation.Spec(APPROVE, "expense", "approve", "approved", 200);
  private static final IdempotentOperation.Spec RETURN_SPEC =
      new IdempotentOperation.Spec(RETURN, "expense", "return", "returned", 200);

  private final IdempotentOperation operations;
  private final JdbcExpenseRepository expenses;
  private final EmployeeAccessLinks links;
  private final ReportingLines lines;
  private final ExpenseRouting routing;
  private final OperationsCalendar calendar;
  private final CursorCodec cursors;
  private final ExpenseRecorder recorder;
  private final ExpenseQuotas quotas;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final PlatformTransactionManager transactionManager;

  ExpenseApprovalService(
      IdempotentOperation operations,
      JdbcExpenseRepository expenses,
      EmployeeAccessLinks links,
      ReportingLines lines,
      ExpenseRouting routing,
      OperationsCalendar calendar,
      CursorCodec cursors,
      ExpenseRecorder recorder,
      ExpenseQuotas quotas,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.operations = operations;
    this.expenses = expenses;
    this.links = links;
    this.lines = lines;
    this.routing = routing;
    this.calendar = calendar;
    this.cursors = cursors;
    this.recorder = recorder;
    this.quotas = quotas;
    this.metrics = metrics;
    this.meters = meters;
    this.transactionManager = transactionManager;
  }

  // ------------------------------------------------------------------------------------------
  // Decisions
  // ------------------------------------------------------------------------------------------

  /**
   * Approves an expense routed to the caller.
   *
   * @param caller verified employee acting as the manager
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<CommandReceipt> approve(
      OperationsCaller caller,
      String expenseId,
      String idempotencyKey,
      ExpenseVersionRequest body) {
    ExpenseCommands.Decision command =
        operations.validated(
            APPROVE_SPEC, () -> ExpenseCommands.Decision.from(idempotencyKey, expenseId, body));
    return ExpenseCalls.counted(
        meters,
        APPROVE,
        true,
        () ->
            operations.execute(
                APPROVE_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                CommandReceipt.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () -> decide(caller, command.expenseId(), command.expectedVersion(), null)));
  }

  /**
   * Returns an expense routed to the caller for correction, with a reason.
   *
   * @param caller verified employee acting as the manager
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version and reason
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<CommandReceipt> returnExpense(
      OperationsCaller caller, String expenseId, String idempotencyKey, ReturnExpenseRequest body) {
    ExpenseCommands.Return command =
        operations.validated(
            RETURN_SPEC, () -> ExpenseCommands.Return.from(idempotencyKey, expenseId, body));
    return ExpenseCalls.counted(
        meters,
        RETURN,
        true,
        () ->
            operations.execute(
                RETURN_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                CommandReceipt.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () ->
                    decide(
                        caller, command.expenseId(), command.expectedVersion(), command.reason())));
  }

  private IdempotentOperation.Completed<CommandReceipt> decide(
      OperationsCaller caller, UUID id, int expectedVersion, String reason) {
    TenantId tenant = caller.tenant();
    boolean approve = reason == null;
    // Lock order: manager graph (step 1), the manager's link (step 4), the expense row (step 5).
    lines.lockManagerGraph(tenant);
    SelfLink link = link(caller);
    Expense before = expenses.lock(tenant, id).orElseThrow(ExpenseCommands::notFound);
    // Visible only while submitted and routed to this manager right now.
    if (before.state() != ExpenseState.SUBMITTED
        || !routing.routedTo(tenant, before, link.employeeId())) {
      throw ExpenseCommands.notFound();
    }
    if (before.version() != expectedVersion) {
      throw new ApiException(ErrorCode.EXPENSE_VERSION_CONFLICT, Map.of());
    }
    Instant now = calendar.now();
    quotas.requireDecision(tenant, caller.subject(), now);
    Expense after;
    try {
      expenses.commit(
          tenant,
          before,
          new Change(
              approve ? ExpenseActionType.APPROVED : ExpenseActionType.RETURNED,
              approve ? ExpenseState.APPROVED : ExpenseState.RETURNED,
              before.revision(),
              new Fields(
                  before.businessDate(),
                  before.currency(),
                  before.amountMinor(),
                  before.category(),
                  before.merchant(),
                  before.description()),
              before.receipt() != null,
              before.submittedAt(),
              now,
              before.routedManagerEmployeeId(),
              link.employeeId(),
              reason),
          now,
          caller.subject(),
          ROLE,
          link.employeeId());
      expenses.checkConsistency(tenant);
      after = expenses.find(tenant, View.CURRENT, id).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw ExpenseCalls.integrity(violated);
    }
    recorder.command(
        caller,
        approve ? "expense.approve" : "expense.return",
        approve ? APPROVED_EVENT : RETURNED_EVENT,
        before,
        after,
        ROLE,
        !approve,
        now);
    LOG.atInfo()
        .addKeyValue("operation", approve ? APPROVE : RETURN)
        .addKeyValue("outcome", approve ? "approved" : "returned")
        .log(approve ? "expense_approved" : "expense_returned");
    return new IdempotentOperation.Completed<>(CommandReceipt.of(after, now), id, Outcome.UPDATED);
  }

  /** A stored decision is returned only to the manager the expense still routes to. */
  private void replayable(OperationsCaller caller, UUID expenseId) {
    SelfLink link = link(caller);
    Expense expense =
        expenseId == null
            ? null
            : expenses.find(caller.tenant(), View.CURRENT, expenseId).orElse(null);
    if (expense == null || !routing.routedTo(caller.tenant(), expense, link.employeeId())) {
      throw ExpenseCommands.notFound();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the caller's queue: submitted expenses currently routed to the caller, oldest
   * submission first.
   *
   * @param caller verified employee acting as the manager
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @return the page, after its disclosure audit committed
   */
  public Page list(OperationsCaller caller, String cursor, String limit) {
    return ExpenseCalls.recorded(
        metrics,
        meters,
        LOG,
        LIST,
        true,
        () -> {
          TenantId tenant = caller.tenant();
          int size = TaskPaging.limit(limit);
          ExpensePaging.Filter none = ExpensePaging.filter(null, null, null, null, null, null);
          CursorScope scope = ExpensePaging.scope(LIST, caller, "manager", none, size);
          KeysetPosition position = cursor == null ? null : cursors.decode(cursor, scope);
          if (position != null && !ExpensePaging.CURSOR_CODE.equals(position.code())) {
            throw ExpensePaging.invalidCursor();
          }
          String page = position == null ? "first" : "next";
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                SelfLink link = link(caller);
                List<UUID> reports = lines.reportsOf(tenant, link.employeeId(), REPORT_CANDIDATES);
                UUID after = position == null ? null : position.id();
                if (after != null) {
                  Expense anchor = expenses.find(tenant, View.CURRENT, after).orElse(null);
                  if (anchor == null || !reports.contains(anchor.employeeId())) {
                    throw ExpensePaging.invalidCursor();
                  }
                }
                Instant now = calendar.now();
                List<Expense> routed = new ArrayList<>();
                UUID lastScanned = after;
                boolean exhausted = reports.isEmpty();
                Map<String, Boolean> verdicts = new HashMap<>();
                for (int batch = 0;
                    !exhausted && batch < SCAN_BATCHES && routed.size() <= size;
                    batch++) {
                  List<Expense> rows =
                      expenses.page(
                          tenant,
                          new PageQuery(
                              View.CURRENT,
                              null,
                              reports,
                              false,
                              true,
                              null,
                              null,
                              null,
                              null,
                              null,
                              lastScanned,
                              SCAN_BATCH));
                  for (Expense row : rows) {
                    lastScanned = row.id();
                    boolean mine =
                        verdicts.computeIfAbsent(
                            row.employeeId() + "|" + row.businessDate(),
                            key -> routing.routedTo(tenant, row, link.employeeId()));
                    if (mine) {
                      routed.add(row);
                      if (routed.size() > size) {
                        break;
                      }
                    }
                  }
                  exhausted = rows.size() < SCAN_BATCH;
                }
                List<Expense> shown = routed.subList(0, Math.min(routed.size(), size));
                String next = null;
                if (routed.size() > size) {
                  next =
                      cursors.encode(
                          scope,
                          new KeysetPosition(
                              ExpensePaging.CURSOR_CODE, shown.get(shown.size() - 1).id()));
                } else if (!exhausted && lastScanned != null) {
                  // The bounded scan ended before the queue did: continue after the last row read.
                  next =
                      cursors.encode(
                          scope, new KeysetPosition(ExpensePaging.CURSOR_CODE, lastScanned));
                }
                Map<UUID, EmployeeRef> people = people(tenant, shown);
                recorder.listed(
                    caller, "manager", "employee", link.employeeId(), page, shown, false, now);
                LOG.atInfo()
                    .addKeyValue("operation", LIST)
                    .addKeyValue("page", page)
                    .addKeyValue("resultCount", shown.size())
                    .addKeyValue("outcome", "listed")
                    .log("expense_approval_listed");
                return new Page(
                    shown.stream().map(e -> ExpenseView.of(e, people.get(e.employeeId()))).toList(),
                    next,
                    now);
              });
        });
  }

  /**
   * One submitted expense routed to the caller with its submitted revisions and decisions.
   *
   * @param caller verified employee acting as the manager
   * @param expenseId path value
   * @return the detail, after its disclosure audit committed
   */
  public DetailView read(OperationsCaller caller, String expenseId) {
    return ExpenseCalls.recorded(
        metrics,
        meters,
        LOG,
        READ,
        true,
        () -> {
          UUID id = ExpenseCommands.expenseId(expenseId);
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Expense expense = routed(tenant, link, id);
                recorder.read(caller, "expense.read", "manager", expense, 0, calendar.now());
                LOG.atInfo()
                    .addKeyValue("operation", READ)
                    .addKeyValue("outcome", "read")
                    .log("expense_approval_read");
                return DetailView.of(
                    ExpenseView.of(expense, reference(tenant, expense)),
                    expenses.revisions(tenant, id, true),
                    expenses.history(tenant, id, true));
              });
        });
  }

  /**
   * The receipt bound to one submitted revision of an expense routed to the caller.
   *
   * @param caller verified employee acting as the manager
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
        true,
        () -> {
          UUID id = ExpenseCommands.expenseId(expenseId);
          int number = ExpenseCommands.revision(revision);
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                TenantId tenant = caller.tenant();
                SelfLink link = link(caller);
                Expense expense = routed(tenant, link, id);
                var content =
                    expenses
                        .receipt(tenant, id, number, true)
                        .orElseThrow(ExpenseCommands::notFound);
                recorder.read(
                    caller, "expense.receipt-download", "manager", expense, number, calendar.now());
                LOG.atInfo()
                    .addKeyValue("operation", RECEIPT_READ)
                    .addKeyValue("outcome", "receipt_read")
                    .log("expense_approval_receipt_read");
                return new ExpenseReceiptDownload(content.mediaType(), content.content());
              });
        });
  }

  // ------------------------------------------------------------------------------------------

  /** A submitted expense that routes to the caller now, or the safe 404. */
  private Expense routed(TenantId tenant, SelfLink link, UUID id) {
    Expense expense =
        expenses.find(tenant, View.CURRENT, id).orElseThrow(ExpenseCommands::notFound);
    if (expense.state() != ExpenseState.SUBMITTED
        || !routing.routedTo(tenant, expense, link.employeeId())) {
      throw ExpenseCommands.notFound();
    }
    return expense;
  }

  private SelfLink link(OperationsCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  private EmployeeRef reference(TenantId tenant, Expense expense) {
    return people(tenant, List.of(expense)).get(expense.employeeId());
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
