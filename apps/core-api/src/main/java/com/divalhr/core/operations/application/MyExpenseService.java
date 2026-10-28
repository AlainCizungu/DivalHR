package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.CreateExpenseRequest;
import com.divalhr.core.operations.api.ExpenseResponses.DetailView;
import com.divalhr.core.operations.api.ExpenseResponses.ExpenseView;
import com.divalhr.core.operations.api.ExpenseResponses.Page;
import com.divalhr.core.operations.api.ExpenseVersionRequest;
import com.divalhr.core.operations.api.UpdateExpenseDraftRequest;
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
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The employee's own expenses (MVP-081, Issue #169): create a draft, change the editable revision,
 * attach, replace or remove its receipt, submit, withdraw while submitted, list, read and download
 * the receipt of a revision.
 *
 * <p>Authorization (role {@code employee}, verified tenant, active membership) has run before any
 * of this. Every operation then resolves the caller's own employee through the identity port's
 * active link ({@code FOR SHARE}, lock order step 4, ADR 0008): loss of the link or the membership
 * fails closed ({@code 403 EMPLOYEE_LINK_REQUIRED}). No request names an employee: an expense that
 * is unknown, foreign or somebody else's is the same safe {@code 404 EXPENSE_NOT_FOUND}. A command
 * locks the expense row (step 5) before validating the version and the transition; the projection,
 * revision, receipt, action, audit and outbox event commit atomically. A submission first takes the
 * tenant's manager-graph lock (step 1), so it never races a change of the route.
 */
@Service
public class MyExpenseService {

  /** Create operation. */
  public static final String CREATE = "expense.self-create";

  /** Update-draft operation. */
  public static final String UPDATE = "expense.self-update";

  /** Receipt attach/replace operation. */
  public static final String RECEIPT_SET = "expense.self-receipt-set";

  /** Receipt removal operation. */
  public static final String RECEIPT_REMOVE = "expense.self-receipt-remove";

  /** Submit operation. */
  public static final String SUBMIT = "expense.self-submit";

  /** Withdraw operation. */
  public static final String WITHDRAW = "expense.self-withdraw";

  /** List operation. */
  public static final String LIST = "expense.self-list";

  /** Read operation. */
  public static final String READ = "expense.self-read";

  /** Receipt download operation. */
  public static final String RECEIPT_READ = "expense.self-receipt-read";

  /** Event of a submission. */
  public static final String SUBMITTED_EVENT = "operations.expense.submitted.v1";

  /** Event of a withdrawal. */
  public static final String WITHDRAWN_EVENT = "operations.expense.withdrawn.v1";

  /** Per-subject read bucket. */
  public static final String SUBJECT_READ_BUCKET = "expense-self-read";

  /** Per-subject write bucket. */
  public static final String SUBJECT_WRITE_BUCKET = "expense-write";

  /** Per-tenant write bucket. */
  public static final String TENANT_WRITE_BUCKET = "expense-write";

  /** Per-subject receipt bucket. */
  public static final String SUBJECT_RECEIPT_BUCKET = "expense-receipt";

  /** Per-tenant receipt bucket. */
  public static final String TENANT_RECEIPT_BUCKET = "expense-receipt";

  private static final String ROLE = "employee";
  private static final Logger LOG = LoggerFactory.getLogger("divalhr.operations");
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "expense", "create", "created", 201);
  private static final IdempotentOperation.Spec UPDATE_SPEC =
      new IdempotentOperation.Spec(UPDATE, "expense", "update", "updated", 200);
  private static final IdempotentOperation.Spec RECEIPT_SET_SPEC =
      new IdempotentOperation.Spec(RECEIPT_SET, "expense_receipt", "set", "set", 200);
  private static final IdempotentOperation.Spec RECEIPT_REMOVE_SPEC =
      new IdempotentOperation.Spec(RECEIPT_REMOVE, "expense_receipt", "remove", "removed", 200);
  private static final IdempotentOperation.Spec SUBMIT_SPEC =
      new IdempotentOperation.Spec(SUBMIT, "expense", "submit", "submitted", 200);
  private static final IdempotentOperation.Spec WITHDRAW_SPEC =
      new IdempotentOperation.Spec(WITHDRAW, "expense", "withdraw", "withdrawn", 200);

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
  private final Clock clock;

  MyExpenseService(
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
      PlatformTransactionManager transactionManager,
      Clock clock) {
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
    this.clock = clock;
  }

  // ------------------------------------------------------------------------------------------
  // Commands
  // ------------------------------------------------------------------------------------------

  /**
   * Creates a draft for the caller's own employee.
   *
   * @param caller verified employee
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the values
   * @return the draft, or the replayed original
   */
  public IdempotentOperation.Result<ExpenseView> create(
      OperationsCaller caller, String idempotencyKey, CreateExpenseRequest body) {
    ExpenseCommands.Create command =
        operations.validated(
            CREATE_SPEC, () -> ExpenseCommands.Create.from(idempotencyKey, body));
    return ExpenseCalls.counted(
        meters,
        CREATE,
        true,
        () ->
            operations.execute(
                CREATE_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                ExpenseView.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () -> doCreate(caller, command)));
  }

  private IdempotentOperation.Completed<ExpenseView> doCreate(
      OperationsCaller caller, ExpenseCommands.Create command) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    Instant now = calendar.now();
    command.draft().requireWindow(calendar.today(tenant));
    quotas.requireWrite(tenant, caller.subject(), now);
    UUID id = UUID.randomUUID();
    Expense after;
    try {
      expenses.insert(tenant, id, link.employeeId(), command.draft().fields(), now, caller.subject());
      expenses.checkConsistency(tenant);
      after = expenses.findOwn(tenant, link.employeeId(), id).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw ExpenseCalls.integrity(violated);
    }
    recorder.command(caller, "expense.create", null, null, after, ROLE, false, now);
    log(CREATE, "created");
    return new IdempotentOperation.Completed<>(
        ExpenseView.of(after, null), id, Outcome.CREATED);
  }

  /**
   * Replaces the values of the editable revision (a returned expense gets its next revision).
   *
   * @param caller verified employee
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the expected version and values
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<ExpenseView> update(
      OperationsCaller caller,
      String expenseId,
      String idempotencyKey,
      UpdateExpenseDraftRequest body) {
    ExpenseCommands.Save command =
        operations.validated(
            UPDATE_SPEC, () -> ExpenseCommands.Save.from(idempotencyKey, expenseId, body));
    return ExpenseCalls.counted(
        meters,
        UPDATE,
        true,
        () ->
            operations.execute(
                UPDATE_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                ExpenseView.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () -> doUpdate(caller, command)));
  }

  private IdempotentOperation.Completed<ExpenseView> doUpdate(
      OperationsCaller caller, ExpenseCommands.Save command) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    Expense before = locked(tenant, link, command.expenseId(), command.expectedVersion());
    requireEditable(before);
    Instant now = calendar.now();
    command.draft().requireWindow(calendar.today(tenant));
    quotas.requireWrite(tenant, caller.subject(), now);
    Fields fields = command.draft().fields();
    Expense after;
    try {
      int revision = before.revision();
      UUID receiptId = before.receipt() == null ? null : before.receipt().id();
      if (before.revisionSubmitted()) {
        revision++;
        expenses.appendRevision(tenant, before.id(), revision, fields, receiptId, now);
      } else {
        expenses.updateRevision(tenant, before.id(), revision, fields);
      }
      expenses.commit(
          tenant,
          before,
          edit(ExpenseActionType.DRAFT_UPDATED, before, revision, fields, receiptId != null),
          now,
          caller.subject(),
          ROLE,
          link.employeeId());
      expenses.checkConsistency(tenant);
      after = expenses.findOwn(tenant, link.employeeId(), before.id()).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw ExpenseCalls.integrity(violated);
    }
    recorder.command(caller, "expense.update-draft", null, before, after, ROLE, false, now);
    log(UPDATE, "updated");
    return new IdempotentOperation.Completed<>(
        ExpenseView.of(after, null), after.id(), Outcome.UPDATED);
  }

  /**
   * Attaches or replaces the receipt of the editable revision. The headers are validated and the
   * caller's right to the expense is established before the first body byte is read; the body is
   * then read within 2 MiB and verified from its bytes.
   *
   * @param caller verified employee
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param expectedVersion {@code X-Expected-Version} header
   * @param contentType declared {@code Content-Type}
   * @param encodedName {@code X-Receipt-File-Name} header or {@code null}
   * @param contentLength declared length or -1
   * @param body the request body stream
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<ExpenseView> setReceipt(
      OperationsCaller caller,
      String expenseId,
      String idempotencyKey,
      String expectedVersion,
      String contentType,
      String encodedName,
      long contentLength,
      InputStream body) {
    ExpenseCommands.ReceiptMutation command =
        operations.validated(
            RECEIPT_SET_SPEC,
            () ->
                ExpenseCommands.ReceiptMutation.set(
                    idempotencyKey, expenseId, expectedVersion, contentType, encodedName));
    return ExpenseCalls.counted(
        meters,
        RECEIPT_SET,
        true,
        () -> {
          // Own, editable expense before one byte of the body is read.
          ExpenseCalls.read(
              transactionManager,
              () -> {
                SelfLink link = link(caller);
                Expense own =
                    expenses
                        .findOwn(caller.tenant(), link.employeeId(), command.expenseId())
                        .orElseThrow(ExpenseCommands::notFound);
                if (own.version() != command.expectedVersion()) {
                  throw new ApiException(ErrorCode.EXPENSE_VERSION_CONFLICT, Map.of());
                }
                requireEditable(own);
                return own.id();
              });
          ReceiptBody.requireDeclaredLength(contentLength);
          byte[] bytes = ReceiptBody.read(body, clock);
          ReceiptVerifier.Verified verified =
              operations.validated(
                  RECEIPT_SET_SPEC,
                  () -> ReceiptVerifier.verify(command.mediaType(), bytes, command.declaredName()));
          return operations.execute(
              RECEIPT_SET_SPEC,
              caller.subject(),
              idempotencyKey,
              command.canonical(caller.tenant(), verified),
              ExpenseView.class,
              ExpenseCalls.TIMEOUT,
              resource -> replayable(caller, resource),
              () -> doReceipt(caller, command, verified));
        });
  }

  /**
   * Removes the receipt of the editable revision.
   *
   * @param caller verified employee
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param expectedVersion {@code X-Expected-Version} header
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<ExpenseView> removeReceipt(
      OperationsCaller caller, String expenseId, String idempotencyKey, String expectedVersion) {
    ExpenseCommands.ReceiptMutation command =
        operations.validated(
            RECEIPT_REMOVE_SPEC,
            () -> ExpenseCommands.ReceiptMutation.remove(idempotencyKey, expenseId, expectedVersion));
    return ExpenseCalls.counted(
        meters,
        RECEIPT_REMOVE,
        true,
        () ->
            operations.execute(
                RECEIPT_REMOVE_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant(), null),
                ExpenseView.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () -> doReceipt(caller, command, null)));
  }

  private IdempotentOperation.Completed<ExpenseView> doReceipt(
      OperationsCaller caller,
      ExpenseCommands.ReceiptMutation command,
      ReceiptVerifier.Verified verified) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    Expense before = locked(tenant, link, command.expenseId(), command.expectedVersion());
    requireEditable(before);
    boolean set = verified != null;
    if (!set && before.receipt() == null) {
      throw new ApiException(ErrorCode.EXPENSE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    Instant now = calendar.now();
    quotas.requireReceipt(tenant, caller.subject(), now);
    Fields fields =
        new Fields(
            before.businessDate(),
            before.currency(),
            before.amountMinor(),
            before.category(),
            before.merchant(),
            before.description());
    Expense after;
    try {
      UUID oldReceipt = before.receipt() == null ? null : before.receipt().id();
      UUID newReceipt = null;
      if (set) {
        newReceipt = UUID.randomUUID();
        expenses.insertReceipt(
            tenant,
            before.id(),
            newReceipt,
            verified.mediaType(),
            verified.bytes(),
            verified.sha256(),
            verified.fileName(),
            now,
            caller.subject());
      }
      int revision = before.revision();
      if (before.revisionSubmitted()) {
        revision++;
        expenses.appendRevision(tenant, before.id(), revision, fields, newReceipt, now);
      } else {
        expenses.bindReceipt(tenant, before.id(), revision, newReceipt);
        if (oldReceipt != null) {
          expenses.deleteReceiptIfUnused(tenant, before.id(), oldReceipt);
        }
      }
      expenses.commit(
          tenant,
          before,
          edit(
              set ? ExpenseActionType.RECEIPT_SET : ExpenseActionType.RECEIPT_REMOVED,
              before,
              revision,
              fields,
              set),
          now,
          caller.subject(),
          ROLE,
          link.employeeId());
      expenses.checkConsistency(tenant);
      after = expenses.findOwn(tenant, link.employeeId(), before.id()).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw ExpenseCalls.integrity(violated);
    }
    recorder.command(
        caller,
        set ? "expense.receipt-set" : "expense.receipt-remove",
        null,
        before,
        after,
        ROLE,
        false,
        now);
    log(set ? RECEIPT_SET : RECEIPT_REMOVE, set ? "receipt_set" : "receipt_removed");
    return new IdempotentOperation.Completed<>(
        ExpenseView.of(after, null), after.id(), Outcome.UPDATED);
  }

  /**
   * Submits the editable revision to the one manager it routes to on its business date.
   *
   * @param caller verified employee
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<ExpenseView> submit(
      OperationsCaller caller,
      String expenseId,
      String idempotencyKey,
      ExpenseVersionRequest body) {
    ExpenseCommands.Decision command =
        operations.validated(
            SUBMIT_SPEC, () -> ExpenseCommands.Decision.from(idempotencyKey, expenseId, body));
    return ExpenseCalls.counted(
        meters,
        SUBMIT,
        true,
        () ->
            operations.execute(
                SUBMIT_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                ExpenseView.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () -> doSubmit(caller, command)));
  }

  private IdempotentOperation.Completed<ExpenseView> doSubmit(
      OperationsCaller caller, ExpenseCommands.Decision command) {
    TenantId tenant = caller.tenant();
    // Lock order: manager graph (step 1), employee link (step 4), expense row (step 5).
    lines.lockManagerGraph(tenant);
    SelfLink link = link(caller);
    Expense before = locked(tenant, link, command.expenseId(), command.expectedVersion());
    // A returned expense is corrected by a new revision first; a submitted revision never changes.
    if (!before.state().editable() || before.revisionSubmitted()) {
      throw new ApiException(ErrorCode.EXPENSE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    Instant now = calendar.now();
    ExpenseCommands.requireWindow(before.businessDate(), calendar.today(tenant));
    UUID manager =
        routing
            .manager(tenant, before)
            .orElseThrow(
                () -> new ApiException(ErrorCode.EXPENSE_APPROVAL_ROUTE_UNAVAILABLE, Map.of()));
    quotas.requireWrite(tenant, caller.subject(), now);
    Fields fields = fields(before);
    Expense after;
    try {
      expenses.submitRevision(tenant, before.id(), before.revision(), now);
      expenses.commit(
          tenant,
          before,
          new Change(
              ExpenseActionType.SUBMITTED,
              ExpenseState.SUBMITTED,
              before.revision(),
              fields,
              before.receipt() != null,
              now,
              null,
              manager,
              manager,
              null),
          now,
          caller.subject(),
          ROLE,
          link.employeeId());
      expenses.checkConsistency(tenant);
      after = expenses.findOwn(tenant, link.employeeId(), before.id()).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw ExpenseCalls.integrity(violated);
    }
    recorder.command(caller, "expense.submit", SUBMITTED_EVENT, before, after, ROLE, false, now);
    log(SUBMIT, "submitted");
    return new IdempotentOperation.Completed<>(
        ExpenseView.of(after, null), after.id(), Outcome.UPDATED);
  }

  /**
   * Withdraws a submitted expense before a decision.
   *
   * @param caller verified employee
   * @param expenseId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body expected version
   * @return the expense, or the replayed original
   */
  public IdempotentOperation.Result<ExpenseView> withdraw(
      OperationsCaller caller,
      String expenseId,
      String idempotencyKey,
      ExpenseVersionRequest body) {
    ExpenseCommands.Decision command =
        operations.validated(
            WITHDRAW_SPEC, () -> ExpenseCommands.Decision.from(idempotencyKey, expenseId, body));
    return ExpenseCalls.counted(
        meters,
        WITHDRAW,
        true,
        () ->
            operations.execute(
                WITHDRAW_SPEC,
                caller.subject(),
                idempotencyKey,
                command.canonical(caller.tenant()),
                ExpenseView.class,
                ExpenseCalls.TIMEOUT,
                resource -> replayable(caller, resource),
                () -> doWithdraw(caller, command)));
  }

  private IdempotentOperation.Completed<ExpenseView> doWithdraw(
      OperationsCaller caller, ExpenseCommands.Decision command) {
    TenantId tenant = caller.tenant();
    SelfLink link = link(caller);
    Expense before = locked(tenant, link, command.expenseId(), command.expectedVersion());
    if (before.state() != ExpenseState.SUBMITTED) {
      throw new ApiException(ErrorCode.EXPENSE_TRANSITION_NOT_ALLOWED, Map.of());
    }
    Instant now = calendar.now();
    quotas.requireWrite(tenant, caller.subject(), now);
    Expense after;
    try {
      expenses.commit(
          tenant,
          before,
          new Change(
              ExpenseActionType.WITHDRAWN,
              ExpenseState.WITHDRAWN,
              before.revision(),
              fields(before),
              before.receipt() != null,
              before.submittedAt(),
              now,
              before.routedManagerEmployeeId(),
              null,
              null),
          now,
          caller.subject(),
          ROLE,
          link.employeeId());
      expenses.checkConsistency(tenant);
      after = expenses.findOwn(tenant, link.employeeId(), before.id()).orElseThrow();
    } catch (DataIntegrityViolationException violated) {
      throw ExpenseCalls.integrity(violated);
    }
    recorder.command(caller, "expense.withdraw", WITHDRAWN_EVENT, before, after, ROLE, false, now);
    log(WITHDRAW, "withdrawn");
    return new IdempotentOperation.Completed<>(
        ExpenseView.of(after, null), after.id(), Outcome.UPDATED);
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the caller's own expenses, newest first.
   *
   * @param caller verified employee
   * @param cursor opaque cursor or {@code null}
   * @param limit page size (1 to 100, default 25)
   * @param state state filter or {@code null}
   * @param currency currency filter or {@code null}
   * @param from earliest business date or {@code null}
   * @param to latest business date or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public Page list(
      OperationsCaller caller,
      String cursor,
      String limit,
      String state,
      String currency,
      String from,
      String to) {
    return ExpenseCalls.recorded(
        metrics,
        meters,
        LOG,
        LIST,
        true,
        () -> {
          TenantId tenant = caller.tenant();
          int size = TaskPaging.limit(limit);
          ExpensePaging.Filter filter = ExpensePaging.filter(null, state, currency, null, from, to);
          CursorScope scope = ExpensePaging.scope(LIST, caller, "self", filter, size);
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          if (after != null && !ExpensePaging.CURSOR_CODE.equals(after.code())) {
            throw ExpensePaging.invalidCursor();
          }
          String page = after == null ? "first" : "next";
          return ExpenseCalls.read(
              transactionManager,
              () -> {
                SelfLink link = link(caller);
                if (after != null
                    && expenses.findOwn(tenant, link.employeeId(), after.id()).isEmpty()) {
                  throw ExpensePaging.invalidCursor();
                }
                Instant now = calendar.now();
                List<Expense> rows =
                    expenses.page(
                        tenant,
                        new PageQuery(
                            View.CURRENT,
                            link.employeeId(),
                            null,
                            false,
                            false,
                            filter.state(),
                            filter.currency(),
                            null,
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
                recorder.listed(
                    caller, "self", "employee", link.employeeId(), page, shown, filter.any(), now);
                log(LIST, "listed");
                return new Page(
                    shown.stream().map(expense -> ExpenseView.of(expense, null)).toList(),
                    next,
                    now);
              });
        });
  }

  /**
   * One of the caller's own expenses with its revisions and history.
   *
   * @param caller verified employee
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
                SelfLink link = link(caller);
                Expense expense =
                    expenses
                        .findOwn(caller.tenant(), link.employeeId(), id)
                        .orElseThrow(ExpenseCommands::notFound);
                Instant now = calendar.now();
                recorder.read(caller, "expense.read", "self", expense, 0, now);
                log(READ, "read");
                return DetailView.of(
                    ExpenseView.of(expense, null),
                    expenses.revisions(caller.tenant(), id, false),
                    expenses.history(caller.tenant(), id, false));
              });
        });
  }

  /**
   * The receipt bound to one revision of the caller's own expense.
   *
   * @param caller verified employee
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
                SelfLink link = link(caller);
                Expense expense =
                    expenses
                        .findOwn(caller.tenant(), link.employeeId(), id)
                        .orElseThrow(ExpenseCommands::notFound);
                var content =
                    expenses
                        .receipt(caller.tenant(), id, number, false)
                        .orElseThrow(ExpenseCommands::notFound);
                recorder.read(
                    caller, "expense.receipt-download", "self", expense, number, calendar.now());
                log(RECEIPT_READ, "receipt_read");
                return new ExpenseReceiptDownload(content.mediaType(), content.content());
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** The caller's own active link ({@code FOR SHARE}), or {@code 403 EMPLOYEE_LINK_REQUIRED}. */
  private SelfLink link(OperationsCaller caller) {
    return links
        .linkedEmployee(caller.tenant(), caller.subject())
        .orElseThrow(() -> new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of()));
  }

  /** A stored response is returned only to the owner, through the caller's current active link. */
  private void replayable(OperationsCaller caller, UUID expenseId) {
    SelfLink link = link(caller);
    if (expenseId == null
        || expenses.findOwn(caller.tenant(), link.employeeId(), expenseId).isEmpty()) {
      throw ExpenseCommands.notFound();
    }
  }

  private Expense locked(TenantId tenant, SelfLink link, UUID id, int expectedVersion) {
    Expense before =
        expenses.lockOwn(tenant, link.employeeId(), id).orElseThrow(ExpenseCommands::notFound);
    if (before.version() != expectedVersion) {
      throw new ApiException(ErrorCode.EXPENSE_VERSION_CONFLICT, Map.of());
    }
    return before;
  }

  private static void requireEditable(Expense before) {
    if (!before.state().editable()) {
      throw new ApiException(ErrorCode.EXPENSE_TRANSITION_NOT_ALLOWED, Map.of());
    }
  }

  private static Fields fields(Expense e) {
    return new Fields(
        e.businessDate(), e.currency(), e.amountMinor(), e.category(), e.merchant(), e.description());
  }

  private static Change edit(
      ExpenseActionType action, Expense before, int revision, Fields fields, boolean receipt) {
    return new Change(
        action,
        before.state(),
        revision,
        fields,
        receipt,
        before.submittedAt(),
        before.decidedAt(),
        before.routedManagerEmployeeId(),
        null,
        null);
  }

  private static void log(String operation, String outcome) {
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("outcome", outcome)
        .log("expense_" + outcome);
  }
}
