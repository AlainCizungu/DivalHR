package com.divalhr.core.operations.internal;

import com.divalhr.core.operations.domain.Expense;
import com.divalhr.core.operations.domain.Expense.ReceiptInfo;
import com.divalhr.core.operations.domain.ExpenseAction;
import com.divalhr.core.operations.domain.ExpenseActionType;
import com.divalhr.core.operations.domain.ExpenseCategory;
import com.divalhr.core.operations.domain.ExpenseCurrency;
import com.divalhr.core.operations.domain.ExpenseRevision;
import com.divalhr.core.operations.domain.ExpenseState;
import com.divalhr.core.platform.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Expense storage (MVP-081, V42). Every query names the verified tenant; employee queries are also
 * bound to the employee resolved from the caller's own link. Runs in the caller's transaction.
 * Merchant, description, return reason, file name and receipt bytes are read and written here only;
 * nothing user-entered is ever logged.
 */
@Repository
public class JdbcExpenseRepository {

  /** Which revision a view shows. */
  public enum View {
    /** The current revision (the employee's and the routed manager's view). */
    CURRENT("r.revision = e.current_revision"),
    /** The newest submitted revision (the administrator's read-only view). */
    LAST_SUBMITTED(
        "r.revision = (SELECT max(x.revision) FROM operations.expense_revision x"
            + " WHERE x.tenant_id = e.tenant_id AND x.expense_id = e.id"
            + " AND x.submitted_at IS NOT NULL)");

    private final String join;

    View(String join) {
      this.join = join;
    }
  }

  /** The values of a revision. */
  public record Fields(
      LocalDate businessDate,
      ExpenseCurrency currency,
      long amountMinor,
      ExpenseCategory category,
      String merchant,
      String description) {

    @Override
    public String toString() {
      return "Fields[" + currency + "]";
    }
  }

  /** The projection change of one action. */
  public record Change(
      ExpenseActionType action,
      ExpenseState to,
      int revision,
      Fields fields,
      boolean receiptPresent,
      Instant submittedAt,
      Instant decidedAt,
      UUID routedManager,
      UUID actionManager,
      String returnReason) {

    @Override
    public String toString() {
      return "Change[" + action + "]";
    }
  }

  /** The stored receipt for a download. */
  public record ReceiptContent(String mediaType, byte[] content) {

    @Override
    public String toString() {
      return "ReceiptContent[" + mediaType + "]";
    }
  }

  /** A page query. */
  public record PageQuery(
      View view,
      UUID employeeId,
      List<UUID> employees,
      boolean submittedOnly,
      boolean queue,
      ExpenseState state,
      ExpenseCurrency currency,
      ExpenseCategory category,
      LocalDate from,
      LocalDate to,
      UUID after,
      int limit) {}

  /** A count of committed work in the window. */
  public record Usage(int count, Instant oldest) {}

  private static final String COLUMNS =
      "e.id, e.employee_id, e.state, e.cycle, r.revision, e.version, r.business_date, r.currency,"
          + " r.amount_minor, r.category, r.merchant, r.description, e.created_at, e.submitted_at,"
          + " e.decided_at, e.last_action_at, (r.submitted_at IS NOT NULL) AS revision_submitted,"
          + " e.routed_manager_employee_id,"
          + " rc.id AS receipt_id, rc.media_type, rc.size_bytes, encode(rc.sha256, 'hex') AS sha,"
          + " rc.file_name";

  private static final String FROM =
      " FROM operations.expense e JOIN operations.expense_revision r"
          + " ON r.tenant_id = e.tenant_id AND r.expense_id = e.id AND ";

  private static final String RECEIPT_JOIN =
      " LEFT JOIN operations.expense_receipt rc ON rc.tenant_id = r.tenant_id"
          + " AND rc.expense_id = r.expense_id AND rc.id = r.receipt_id";

  private final NamedParameterJdbcTemplate jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC template
   */
  public JdbcExpenseRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * An expense of the tenant, without locking.
   *
   * @param tenant verified tenant
   * @param view revision shown
   * @param id expense
   * @return the expense, or empty
   */
  public Optional<Expense> find(TenantId tenant, View view, UUID id) {
    return one(select(view) + " AND e.id = :id", params(tenant, id));
  }

  /**
   * An expense of the tenant owned by the employee, without locking.
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param id expense
   * @return the expense, or empty
   */
  public Optional<Expense> findOwn(TenantId tenant, UUID employeeId, UUID id) {
    return one(
        select(View.CURRENT) + " AND e.id = :id AND e.employee_id = :employee",
        params(tenant, id).addValue("employee", employeeId));
  }

  /**
   * An expense owned by the employee, locked {@code FOR UPDATE} (lock order step 5).
   *
   * @param tenant verified tenant
   * @param employeeId the caller's own employee
   * @param id expense
   * @return the expense, or empty
   */
  public Optional<Expense> lockOwn(TenantId tenant, UUID employeeId, UUID id) {
    return one(
        select(View.CURRENT) + " AND e.id = :id AND e.employee_id = :employee FOR UPDATE OF e",
        params(tenant, id).addValue("employee", employeeId));
  }

  /**
   * An expense of the tenant, locked {@code FOR UPDATE} (lock order step 5).
   *
   * @param tenant verified tenant
   * @param id expense
   * @return the expense, or empty
   */
  public Optional<Expense> lock(TenantId tenant, UUID id) {
    return one(
        select(View.CURRENT) + " AND e.id = :id FOR UPDATE OF e", params(tenant, id));
  }

  /**
   * The revisions of an expense, oldest first.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param submittedOnly whether to leave out an editable (unsubmitted) revision
   * @return the revisions
   */
  public List<ExpenseRevision> revisions(TenantId tenant, UUID id, boolean submittedOnly) {
    return jdbc.query(
        "SELECT r.revision, r.business_date, r.currency, r.amount_minor, r.category, r.merchant,"
            + " r.description, r.created_at, r.submitted_at, rc.id AS receipt_id, rc.media_type,"
            + " rc.size_bytes, encode(rc.sha256, 'hex') AS sha, rc.file_name"
            + " FROM operations.expense_revision r"
            + RECEIPT_JOIN
            + " WHERE r.tenant_id = :tenant AND r.expense_id = :id"
            + (submittedOnly ? " AND r.submitted_at IS NOT NULL" : "")
            + " ORDER BY r.revision",
        params(tenant, id),
        (rs, n) ->
            new ExpenseRevision(
                rs.getInt("revision"),
                rs.getObject("business_date", LocalDate.class),
                ExpenseCurrency.valueOf(rs.getString("currency")),
                rs.getLong("amount_minor"),
                ExpenseCategory.valueOf(rs.getString("category")),
                rs.getString("merchant"),
                rs.getString("description"),
                receipt(rs),
                instant(rs, "created_at"),
                instant(rs, "submitted_at")));
  }

  /**
   * The history of an expense, in sequence order.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param submittedOnly whether to leave out the actions on an editable revision
   * @return the actions
   */
  public List<ExpenseAction> history(TenantId tenant, UUID id, boolean submittedOnly) {
    return jdbc.query(
        "SELECT a.seq, a.action, a.occurred_at, a.actor_role, a.from_state, a.to_state, a.revision,"
            + " a.receipt_present, a.manager_employee_id, a.return_reason"
            + " FROM operations.expense_action a"
            + " WHERE a.tenant_id = :tenant AND a.expense_id = :id"
            + (submittedOnly
                ? " AND a.action NOT IN ('CREATED', 'DRAFT_UPDATED', 'RECEIPT_SET',"
                    + " 'RECEIPT_REMOVED')"
                : "")
            + " ORDER BY a.seq",
        params(tenant, id),
        (rs, n) ->
            new ExpenseAction(
                rs.getInt("seq"),
                ExpenseActionType.valueOf(rs.getString("action")),
                instant(rs, "occurred_at"),
                rs.getString("actor_role"),
                state(rs.getString("from_state")),
                ExpenseState.valueOf(rs.getString("to_state")),
                rs.getInt("revision"),
                rs.getBoolean("receipt_present"),
                rs.getObject("manager_employee_id", UUID.class),
                rs.getString("return_reason")));
  }

  /**
   * The bytes of the receipt bound to one revision of an expense.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param revision revision number
   * @param submittedOnly whether an editable revision is hidden
   * @return the receipt, or empty
   */
  public Optional<ReceiptContent> receipt(
      TenantId tenant, UUID id, int revision, boolean submittedOnly) {
    return jdbc
        .query(
            "SELECT rc.media_type, rc.content FROM operations.expense_revision r"
                + " JOIN operations.expense_receipt rc ON rc.tenant_id = r.tenant_id"
                + " AND rc.expense_id = r.expense_id AND rc.id = r.receipt_id"
                + " WHERE r.tenant_id = :tenant AND r.expense_id = :id AND r.revision = :revision"
                + (submittedOnly ? " AND r.submitted_at IS NOT NULL" : ""),
            params(tenant, id).addValue("revision", revision),
            (rs, n) -> new ReceiptContent(rs.getString("media_type"), rs.getBytes("content")))
        .stream()
        .findFirst();
  }

  /**
   * One page, in the stable order of the view: newest creation first, or (queue) oldest submission
   * first.
   *
   * @param tenant verified tenant
   * @param query the query
   * @return up to {@code limit} expenses
   */
  public List<Expense> page(TenantId tenant, PageQuery query) {
    StringBuilder sql = new StringBuilder(select(query.view()));
    MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenant.value());
    if (query.employeeId() != null) {
      sql.append(" AND e.employee_id = :employee");
      params.addValue("employee", query.employeeId());
    }
    if (query.employees() != null) {
      sql.append(" AND e.employee_id IN (:employees)");
      params.addValue("employees", query.employees());
    }
    if (query.submittedOnly()) {
      sql.append(" AND e.submitted_at IS NOT NULL");
    }
    if (query.queue()) {
      sql.append(" AND e.state = 'SUBMITTED'");
    }
    if (query.state() != null) {
      sql.append(" AND e.state = :state");
      params.addValue("state", query.state().name());
    }
    if (query.currency() != null) {
      sql.append(" AND r.currency = :currency");
      params.addValue("currency", query.currency().name());
    }
    if (query.category() != null) {
      sql.append(" AND r.category = :category");
      params.addValue("category", query.category().name());
    }
    if (query.from() != null) {
      sql.append(" AND r.business_date >= :from");
      params.addValue("from", query.from());
    }
    if (query.to() != null) {
      sql.append(" AND r.business_date <= :to");
      params.addValue("to", query.to());
    }
    if (query.after() != null) {
      params.addValue("after", query.after());
      if (query.queue()) {
        sql.append(
            " AND (e.submitted_at, e.id) > ((SELECT a.submitted_at FROM operations.expense a"
                + " WHERE a.tenant_id = :tenant AND a.id = :after), :after)");
      } else {
        sql.append(
            " AND (e.created_at, e.id) < ((SELECT a.created_at FROM operations.expense a"
                + " WHERE a.tenant_id = :tenant AND a.id = :after), :after)");
      }
    }
    sql.append(
        query.queue()
            ? " ORDER BY e.submitted_at ASC, e.id ASC"
            : " ORDER BY e.created_at DESC, e.id DESC");
    sql.append(" LIMIT :limit");
    params.addValue("limit", query.limit());
    return jdbc.query(sql.toString(), params, (rs, n) -> expense(rs));
  }

  /**
   * The open expenses a subject committed actions on, counted for a quota window.
   *
   * @param tenant verified tenant
   * @param subject verified subject
   * @param actions action names counted
   * @param since window start
   * @return the count and the oldest instant
   */
  public Usage actionsSince(
      TenantId tenant, String subject, List<ExpenseActionType> actions, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) AS n, min(occurred_at) AS oldest FROM operations.expense_action"
            + " WHERE tenant_id = :tenant AND actor_subject = :subject AND action IN (:actions)"
            + " AND occurred_at > :since",
        new MapSqlParameterSource()
            .addValue("tenant", tenant.value())
            .addValue("subject", subject)
            .addValue("actions", actions.stream().map(Enum::name).toList())
            .addValue("since", Timestamp.from(since)),
        (rs, n) -> new Usage(rs.getInt("n"), instant(rs, "oldest")));
  }

  // ------------------------------------------------------------------------------------------
  // Writes (each inside the caller's transaction; the database re-checks every rule)
  // ------------------------------------------------------------------------------------------

  /**
   * Inserts a new draft: the projection, revision 1 and the {@code CREATED} action.
   *
   * @param tenant verified tenant
   * @param id new expense
   * @param employeeId owner
   * @param fields revision values
   * @param now server instant
   * @param subject creating subject
   */
  public void insert(
      TenantId tenant, UUID id, UUID employeeId, Fields fields, Instant now, String subject) {
    MapSqlParameterSource p = values(tenant, id, fields).addValue("employee", employeeId);
    p.addValue("now", Timestamp.from(now)).addValue("subject", subject);
    jdbc.update(
        "INSERT INTO operations.expense (tenant_id, id, employee_id, state, cycle, current_revision,"
            + " version, business_date, currency, amount_minor, category, receipt_present,"
            + " created_at, created_by, last_action_at)"
            + " VALUES (:tenant, :id, :employee, 'DRAFT', 1, 1, 1, :date, :currency, :minor,"
            + " :category, false, :now, :subject, :now)",
        p);
    jdbc.update(
        "INSERT INTO operations.expense_revision (tenant_id, expense_id, revision, business_date,"
            + " currency, exponent, amount_minor, category, merchant, description, created_at)"
            + " VALUES (:tenant, :id, 1, :date, :currency, :exponent, :minor, :category,"
            + " :merchant, :description, :now)",
        p);
    jdbc.update(
        "INSERT INTO operations.expense_action (tenant_id, expense_id, seq, action, occurred_at,"
            + " actor_subject, actor_role, actor_employee_id, from_state, to_state, revision, cycle,"
            + " receipt_present) VALUES (:tenant, :id, 1, 'CREATED', :now, :subject, 'employee',"
            + " :employee, NULL, 'DRAFT', 1, 1, false)",
        p);
  }

  /**
   * Updates the values of the editable current revision in place.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param revision the editable revision
   * @param fields new values
   */
  public void updateRevision(TenantId tenant, UUID id, int revision, Fields fields) {
    jdbc.update(
        "UPDATE operations.expense_revision SET business_date = :date, currency = :currency,"
            + " exponent = :exponent, amount_minor = :minor, category = :category,"
            + " merchant = :merchant, description = :description"
            + " WHERE tenant_id = :tenant AND expense_id = :id AND revision = :revision",
        values(tenant, id, fields).addValue("revision", revision));
  }

  /**
   * Appends the next revision that corrects a returned expense.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param revision the new revision number
   * @param fields its values
   * @param receiptId the receipt carried over or {@code null}
   * @param now server instant
   */
  public void appendRevision(
      TenantId tenant, UUID id, int revision, Fields fields, UUID receiptId, Instant now) {
    jdbc.update(
        "INSERT INTO operations.expense_revision (tenant_id, expense_id, revision, business_date,"
            + " currency, exponent, amount_minor, category, merchant, description, receipt_id,"
            + " created_at) VALUES (:tenant, :id, :revision, :date, :currency, :exponent, :minor,"
            + " :category, :merchant, :description, :receipt, :now)",
        values(tenant, id, fields)
            .addValue("revision", revision)
            .addValue("receipt", receiptId)
            .addValue("now", Timestamp.from(now)));
  }

  /**
   * Stores a verified receipt of the expense.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param receiptId server-generated receipt ID
   * @param mediaType verified media type
   * @param bytes content
   * @param sha256 server-computed digest
   * @param fileName normalized display name
   * @param now server instant
   * @param subject uploading subject
   */
  public void insertReceipt(
      TenantId tenant,
      UUID id,
      UUID receiptId,
      String mediaType,
      byte[] bytes,
      byte[] sha256,
      String fileName,
      Instant now,
      String subject) {
    jdbc.update(
        "INSERT INTO operations.expense_receipt (tenant_id, expense_id, id, media_type, size_bytes,"
            + " sha256, file_name, content, created_at, created_by)"
            + " VALUES (:tenant, :id, :receipt, :media, :size, :sha, :name, :content, :now,"
            + " :subject)",
        params(tenant, id)
            .addValue("receipt", receiptId)
            .addValue("media", mediaType)
            .addValue("size", bytes.length)
            .addValue("sha", sha256)
            .addValue("name", fileName)
            .addValue("content", bytes)
            .addValue("now", Timestamp.from(now))
            .addValue("subject", subject));
  }

  /**
   * Binds a receipt (or none) to the editable revision.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param revision the editable revision
   * @param receiptId the receipt or {@code null}
   */
  public void bindReceipt(TenantId tenant, UUID id, int revision, UUID receiptId) {
    jdbc.update(
        "UPDATE operations.expense_revision SET receipt_id = :receipt"
            + " WHERE tenant_id = :tenant AND expense_id = :id AND revision = :revision",
        params(tenant, id).addValue("revision", revision).addValue("receipt", receiptId));
  }

  /**
   * Deletes a replaced or removed receipt that no revision uses any more.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param receiptId the receipt
   */
  public void deleteReceiptIfUnused(TenantId tenant, UUID id, UUID receiptId) {
    jdbc.update(
        "DELETE FROM operations.expense_receipt rc WHERE rc.tenant_id = :tenant"
            + " AND rc.expense_id = :id AND rc.id = :receipt AND NOT EXISTS (SELECT 1 FROM"
            + " operations.expense_revision r WHERE r.tenant_id = rc.tenant_id"
            + " AND r.expense_id = rc.expense_id AND r.receipt_id = rc.id)",
        params(tenant, id).addValue("receipt", receiptId));
  }

  /**
   * Marks the current revision submitted.
   *
   * @param tenant verified tenant
   * @param id expense
   * @param revision the revision
   * @param at submission instant
   */
  public void submitRevision(TenantId tenant, UUID id, int revision, Instant at) {
    jdbc.update(
        "UPDATE operations.expense_revision SET submitted_at = :at"
            + " WHERE tenant_id = :tenant AND expense_id = :id AND revision = :revision",
        params(tenant, id).addValue("revision", revision).addValue("at", Timestamp.from(at)));
  }

  /**
   * Moves the projection to its next version and appends the action (the database re-checks the
   * transition, the sequence and the exact match at commit).
   *
   * @param tenant verified tenant
   * @param before the locked expense
   * @param change the change
   * @param now server instant
   * @param subject acting subject
   * @param role {@code employee} or {@code manager}
   * @param actorEmployeeId acting employee
   */
  public void commit(
      TenantId tenant,
      Expense before,
      Change change,
      Instant now,
      String subject,
      String role,
      UUID actorEmployeeId) {
    Fields f = change.fields();
    MapSqlParameterSource p =
        values(tenant, before.id(), f)
            .addValue("expected", before.version())
            .addValue("next", before.version() + 1)
            .addValue("state", change.to().name())
            .addValue("revision", change.revision())
            .addValue("receiptPresent", change.receiptPresent())
            .addValue("submittedAt", timestamp(change.submittedAt()))
            .addValue("decidedAt", timestamp(change.decidedAt()))
            .addValue("manager", change.routedManager())
            .addValue("now", Timestamp.from(now));
    int updated =
        jdbc.update(
            "UPDATE operations.expense SET state = :state, cycle = :revision,"
                + " current_revision = :revision, version = :next, business_date = :date,"
                + " currency = :currency, amount_minor = :minor, category = :category,"
                + " receipt_present = :receiptPresent, submitted_at = :submittedAt,"
                + " decided_at = :decidedAt, last_action_at = :now,"
                + " routed_manager_employee_id = :manager"
                + " WHERE tenant_id = :tenant AND id = :id AND version = :expected",
            p);
    if (updated != 1) {
      throw new IllegalStateException("expense changed under its own lock");
    }
    p.addValue("action", change.action().name())
        .addValue("subject", subject)
        .addValue("role", role)
        .addValue("actorEmployee", actorEmployeeId)
        .addValue("from", before.state().name())
        .addValue("actionManager", change.actionManager())
        .addValue("reason", change.returnReason());
    jdbc.update(
        "INSERT INTO operations.expense_action (tenant_id, expense_id, seq, action, occurred_at,"
            + " actor_subject, actor_role, actor_employee_id, from_state, to_state, revision, cycle,"
            + " manager_employee_id, receipt_present, return_reason) VALUES (:tenant, :id, :next,"
            + " :action, :now, :subject, :role, :actorEmployee, :from, :state, :revision,"
            + " :revision, :actionManager, :receiptPresent, :reason)",
        p);
  }

  /**
   * Runs the deferred V42 checks now, so a violation surfaces inside the command's transaction.
   *
   * @param tenant verified tenant whose transaction it is
   */
  public void checkConsistency(TenantId tenant) {
    java.util.Objects.requireNonNull(tenant, "tenant");
    jdbc.getJdbcOperations()
        .execute(
            "SET CONSTRAINTS operations.operations_expense_exact,"
                + " operations.operations_expense_action_exact,"
                + " operations.operations_expense_revision_exact IMMEDIATE");
  }

  // ------------------------------------------------------------------------------------------

  private static String select(View view) {
    return "SELECT "
        + COLUMNS
        + FROM
        + view.join
        + RECEIPT_JOIN
        + " WHERE e.tenant_id = :tenant";
  }

  private Optional<Expense> one(String sql, MapSqlParameterSource params) {
    return jdbc.query(sql, params, (rs, n) -> expense(rs)).stream().findFirst();
  }

  private static MapSqlParameterSource params(TenantId tenant, UUID id) {
    return new MapSqlParameterSource().addValue("tenant", tenant.value()).addValue("id", id);
  }

  private static MapSqlParameterSource values(TenantId tenant, UUID id, Fields fields) {
    return params(tenant, id)
        .addValue("date", fields.businessDate())
        .addValue("currency", fields.currency().name())
        .addValue("exponent", fields.currency().exponent())
        .addValue("minor", fields.amountMinor())
        .addValue("category", fields.category().name())
        .addValue("merchant", fields.merchant())
        .addValue("description", fields.description());
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static ExpenseState state(String name) {
    return name == null ? null : ExpenseState.valueOf(name);
  }

  private static ReceiptInfo receipt(ResultSet rs) throws SQLException {
    UUID id = rs.getObject("receipt_id", UUID.class);
    return id == null
        ? null
        : new ReceiptInfo(
            id,
            rs.getString("media_type"),
            rs.getInt("size_bytes"),
            rs.getString("sha"),
            rs.getString("file_name"));
  }

  private static Expense expense(ResultSet rs) throws SQLException {
    return new Expense(
        rs.getObject("id", UUID.class),
        rs.getObject("employee_id", UUID.class),
        ExpenseState.valueOf(rs.getString("state")),
        rs.getInt("cycle"),
        rs.getInt("revision"),
        rs.getInt("version"),
        rs.getObject("business_date", LocalDate.class),
        ExpenseCurrency.valueOf(rs.getString("currency")),
        rs.getLong("amount_minor"),
        ExpenseCategory.valueOf(rs.getString("category")),
        rs.getString("merchant"),
        rs.getString("description"),
        receipt(rs),
        instant(rs, "created_at"),
        instant(rs, "submitted_at"),
        instant(rs, "decided_at"),
        instant(rs, "last_action_at"),
        rs.getBoolean("revision_submitted"),
        rs.getObject("routed_manager_employee_id", UUID.class));
  }
}
