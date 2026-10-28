package com.divalhr.core.operations.api;

import com.divalhr.core.operations.domain.Expense;
import com.divalhr.core.operations.domain.ExpenseAction;
import com.divalhr.core.operations.domain.ExpenseActionType;
import com.divalhr.core.operations.domain.ExpenseCategory;
import com.divalhr.core.operations.domain.ExpenseCurrency;
import com.divalhr.core.operations.domain.ExpenseRevision;
import com.divalhr.core.operations.domain.ExpenseState;
import com.divalhr.core.platform.access.ReportingLines.Person;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Expense responses (MVP-081). Merchant, description, return reason and file name appear only here,
 * in authorized responses. Amounts are decimal strings in the expense's own currency, with the
 * integer minor units beside them; nothing is converted and nothing is ever summed across
 * currencies. Approval is a decision about the claim: it is not reimbursement or payment.
 */
public final class ExpenseResponses {

  private ExpenseResponses() {}

  /**
   * Receipt metadata (never the bytes).
   *
   * @param id receipt
   * @param mediaType verified media type
   * @param sizeBytes size in bytes
   * @param sha256 server-computed digest, hexadecimal
   * @param fileName normalized display name
   */
  @Schema(name = "ExpenseReceiptFile")
  public record ReceiptView(
      UUID id, String mediaType, int sizeBytes, String sha256, String fileName) {

    @Override
    public String toString() {
      return "ExpenseReceipt[" + id + "]";
    }

    static ReceiptView of(Expense.ReceiptInfo receipt) {
      return receipt == null
          ? null
          : new ReceiptView(
              receipt.id(),
              receipt.mediaType(),
              receipt.sizeBytes(),
              receipt.sha256(),
              receipt.fileName());
    }
  }

  /**
   * The employee a reader tells apart in a manager or administrator view.
   *
   * @param id employee
   * @param employeeNumber employee number
   * @param givenNames given names
   * @param familyName family name
   */
  @Schema(name = "ExpenseEmployee")
  public record EmployeeRef(UUID id, String employeeNumber, String givenNames, String familyName) {

    @Override
    public String toString() {
      return "ExpenseEmployee[" + id + "]";
    }

    /**
     * The reference of an employee.
     *
     * @param id employee
     * @param person identity found, or {@code null}
     * @return the reference
     */
    public static EmployeeRef of(UUID id, Person person) {
      return person == null
          ? new EmployeeRef(id, null, null, null)
          : new EmployeeRef(id, person.employeeNumber(), person.givenNames(), person.familyName());
    }
  }

  /**
   * An expense as the caller may see it.
   *
   * @param id expense
   * @param employee the owner, shown to the manager and the administrator only
   * @param state state
   * @param cycle submission cycle
   * @param revision shown revision
   * @param version optimistic version
   * @param businessDate business date
   * @param currency currency
   * @param amount decimal string in the currency's own scale
   * @param amountMinorUnits integer minor units
   * @param category category
   * @param merchant merchant
   * @param description description
   * @param receipt receipt metadata or {@code null}
   * @param createdAt creation instant
   * @param submittedAt last submission or {@code null}
   * @param decidedAt last decision or {@code null}
   * @param lastActionAt last action
   * @param editable whether the employee may change the expense (only the employee's own view)
   */
  @Schema(name = "Expense")
  public record ExpenseView(
      UUID id,
      EmployeeRef employee,
      ExpenseState state,
      int cycle,
      int revision,
      int version,
      LocalDate businessDate,
      ExpenseCurrency currency,
      String amount,
      long amountMinorUnits,
      ExpenseCategory category,
      String merchant,
      String description,
      ReceiptView receipt,
      Instant createdAt,
      Instant submittedAt,
      Instant decidedAt,
      Instant lastActionAt,
      boolean editable) {

    @Override
    public String toString() {
      return "Expense[" + id + "]";
    }

    /**
     * The view of an expense.
     *
     * @param expense expense
     * @param employee owner reference or {@code null} (the employee's own view)
     * @return the view
     */
    public static ExpenseView of(Expense expense, EmployeeRef employee) {
      return new ExpenseView(
          expense.id(),
          employee,
          expense.state(),
          expense.cycle(),
          expense.revision(),
          expense.version(),
          expense.businessDate(),
          expense.currency(),
          expense.currency().format(expense.amountMinor()),
          expense.amountMinor(),
          expense.category(),
          expense.merchant(),
          expense.description(),
          ReceiptView.of(expense.receipt()),
          expense.createdAt(),
          expense.submittedAt(),
          expense.decidedAt(),
          expense.lastActionAt(),
          employee == null && expense.state().editable());
    }
  }

  /**
   * The result of a command: identifiers, state and numbers only. It is what an idempotency record
   * stores for a replay, so it never carries a merchant, description, return reason, file name or
   * name; the caller reads the detail for those.
   *
   * @param id expense
   * @param state resulting state
   * @param cycle submission cycle
   * @param revision current revision
   * @param version resulting optimistic version
   * @param currency currency
   * @param amount decimal string in the currency's own scale
   * @param amountMinorUnits integer minor units
   * @param category category
   * @param receiptPresent whether the current revision has a receipt
   * @param occurredAt server instant of the command
   */
  @Schema(name = "ExpenseReceipt")
  public record CommandReceipt(
      UUID id,
      ExpenseState state,
      int cycle,
      int revision,
      int version,
      ExpenseCurrency currency,
      String amount,
      long amountMinorUnits,
      ExpenseCategory category,
      boolean receiptPresent,
      Instant occurredAt) {

    @Override
    public String toString() {
      return "ExpenseCommandReceipt[" + id + "]";
    }

    /**
     * The receipt of a command.
     *
     * @param expense the expense after the command
     * @param at server instant
     * @return the receipt
     */
    public static CommandReceipt of(Expense expense, Instant at) {
      return new CommandReceipt(
          expense.id(),
          expense.state(),
          expense.cycle(),
          expense.revision(),
          expense.version(),
          expense.currency(),
          expense.currency().format(expense.amountMinor()),
          expense.amountMinor(),
          expense.category(),
          expense.receipt() != null,
          at);
    }
  }

  /**
   * One immutable (or still editable) revision.
   *
   * @param revision number
   * @param businessDate business date
   * @param currency currency
   * @param amount decimal string
   * @param amountMinorUnits minor units
   * @param category category
   * @param merchant merchant
   * @param description description
   * @param receipt receipt metadata or {@code null}
   * @param createdAt creation instant
   * @param submittedAt submission instant or {@code null} while editable
   */
  @Schema(name = "ExpenseRevision")
  public record RevisionView(
      int revision,
      LocalDate businessDate,
      ExpenseCurrency currency,
      String amount,
      long amountMinorUnits,
      ExpenseCategory category,
      String merchant,
      String description,
      ReceiptView receipt,
      Instant createdAt,
      Instant submittedAt) {

    @Override
    public String toString() {
      return "ExpenseRevision[" + revision + "]";
    }

    static RevisionView of(ExpenseRevision r) {
      return new RevisionView(
          r.revision(),
          r.businessDate(),
          r.currency(),
          r.currency().format(r.amountMinor()),
          r.amountMinor(),
          r.category(),
          r.merchant(),
          r.description(),
          ReceiptView.of(r.receipt()),
          r.createdAt(),
          r.submittedAt());
    }
  }

  /**
   * One action of the history.
   *
   * @param sequence order
   * @param action action
   * @param occurredAt instant
   * @param actorRole {@code employee} or {@code manager}
   * @param fromState prior state or {@code null}
   * @param toState resulting state
   * @param revision revision acted on
   * @param receiptPresent whether the revision had a receipt
   * @param returnReason return reason or {@code null}
   */
  @Schema(name = "ExpenseAction")
  public record ActionView(
      int sequence,
      ExpenseActionType action,
      Instant occurredAt,
      String actorRole,
      ExpenseState fromState,
      ExpenseState toState,
      int revision,
      boolean receiptPresent,
      String returnReason) {

    @Override
    public String toString() {
      return "ExpenseAction[" + sequence + "]";
    }

    static ActionView of(ExpenseAction a) {
      return new ActionView(
          a.seq(),
          a.action(),
          a.occurredAt(),
          a.actorRole(),
          a.fromState(),
          a.toState(),
          a.revision(),
          a.receiptPresent(),
          a.returnReason());
    }
  }

  /**
   * An expense with its revisions and history.
   *
   * @param expense the expense
   * @param revisions revisions, oldest first
   * @param history actions, in order
   */
  @Schema(name = "ExpenseDetail")
  public record DetailView(
      ExpenseView expense, List<RevisionView> revisions, List<ActionView> history) {

    @Override
    public String toString() {
      return "ExpenseDetail[" + expense + "]";
    }

    /**
     * The detail.
     *
     * @param expense expense view
     * @param revisions revisions
     * @param history actions
     * @return the detail
     */
    public static DetailView of(
        ExpenseView expense, List<ExpenseRevision> revisions, List<ExpenseAction> history) {
      return new DetailView(
          expense,
          revisions.stream().map(RevisionView::of).toList(),
          history.stream().map(ActionView::of).toList());
    }
  }

  /**
   * One page of expenses.
   *
   * @param items expenses
   * @param nextCursor opaque cursor or {@code null}
   * @param asOf server instant
   */
  @Schema(name = "ExpensePage")
  public record Page(List<ExpenseView> items, String nextCursor, Instant asOf) {}
}
