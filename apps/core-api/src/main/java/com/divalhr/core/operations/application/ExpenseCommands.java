package com.divalhr.core.operations.application;

import com.divalhr.core.operations.api.CreateExpenseRequest;
import com.divalhr.core.operations.api.ExpenseDraftRequest;
import com.divalhr.core.operations.api.ExpenseVersionRequest;
import com.divalhr.core.operations.api.ReturnExpenseRequest;
import com.divalhr.core.operations.api.StrictRequest;
import com.divalhr.core.operations.api.UpdateExpenseDraftRequest;
import com.divalhr.core.operations.domain.ExpenseCategory;
import com.divalhr.core.operations.domain.ExpenseCurrency;
import com.divalhr.core.operations.internal.JdbcExpenseRepository.Fields;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The validated commands of the expense lifecycle (MVP-081). Validation is deterministic: it
 * depends on the request alone, so it runs before the idempotency decision; the business-date
 * window, ownership, routing, version and transition rules run inside the transaction. Problems are
 * {@code {field, constraint}} pairs only: a user-entered value is never echoed, and every {@code
 * toString} shows identifiers only.
 */
public final class ExpenseCommands {

  /** The oldest business date, in days before today (D81-4). */
  public static final int MAX_AGE_DAYS = 90;

  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

  private ExpenseCommands() {}

  /**
   * The validated values of a revision.
   *
   * @param fields the values (amount in minor units)
   */
  public record Draft(Fields fields) {

    @Override
    public String toString() {
      return "Draft[" + fields.currency() + "]";
    }

    /**
     * The business date must be today or at most 90 days earlier in the organization's time zone
     * (D81-4); checked for a new execution with the server date, never for a replay.
     *
     * @param today today in the organization's time zone
     */
    public void requireWindow(LocalDate today) {
      ExpenseCommands.requireWindow(fields.businessDate(), today);
    }

    Map<String, Object> canonical(Map<String, Object> into) {
      into.put("businessDate", fields.businessDate().toString());
      into.put("currency", fields.currency().name());
      into.put("amountMinor", fields.amountMinor());
      into.put("category", fields.category().name());
      into.put("merchant", fields.merchant());
      into.put("description", fields.description());
      return into;
    }
  }

  /**
   * A new draft.
   *
   * @param draft the values
   */
  public record Create(Draft draft) {

    @Override
    public String toString() {
      return "Create[" + draft + "]";
    }

    /**
     * Validates a new draft.
     *
     * @param idempotencyKey header
     * @param request body
     * @return the command
     */
    public static Create from(String idempotencyKey, CreateExpenseRequest request) {
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request);
      Draft draft = parseDraft(errors, request);
      errors.throwIfAny();
      return new Create(draft);
    }

    /**
     * The normalized command and its scope, fingerprinted for idempotency.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      return draft.canonical(canonical);
    }
  }

  /**
   * The replacement of the editable revision.
   *
   * @param expenseId expense
   * @param expectedVersion the version the employee saw
   * @param draft the values
   */
  public record Save(UUID expenseId, int expectedVersion, Draft draft) {

    @Override
    public String toString() {
      return "Save[" + expenseId + "]";
    }

    /**
     * Validates the replacement.
     *
     * @param idempotencyKey header
     * @param expenseId path value
     * @param request body
     * @return the command
     */
    public static Save from(
        String idempotencyKey, String expenseId, UpdateExpenseDraftRequest request) {
      UUID id = ExpenseCommands.expenseId(expenseId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request);
      Integer version = version(errors, request.getExpectedVersion());
      Draft draft = parseDraft(errors, request);
      errors.throwIfAny();
      return new Save(id, version, draft);
    }

    /**
     * The normalized command and its scope.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("expenseId", expenseId.toString());
      canonical.put("expectedVersion", expectedVersion);
      return draft.canonical(canonical);
    }
  }

  /**
   * A command that needs only the version: submit, withdraw or approve.
   *
   * @param expenseId expense
   * @param expectedVersion the version the caller saw
   */
  public record Decision(UUID expenseId, int expectedVersion) {

    @Override
    public String toString() {
      return "Decision[" + expenseId + "]";
    }

    /**
     * Validates the command.
     *
     * @param idempotencyKey header
     * @param expenseId path value
     * @param request body
     * @return the command
     */
    public static Decision from(
        String idempotencyKey, String expenseId, ExpenseVersionRequest request) {
      UUID id = ExpenseCommands.expenseId(expenseId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request);
      Integer version = version(errors, request.getExpectedVersion());
      errors.throwIfAny();
      return new Decision(id, version);
    }

    /**
     * The normalized command and its scope.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("expenseId", expenseId.toString());
      canonical.put("expectedVersion", expectedVersion);
      return canonical;
    }
  }

  /**
   * The return of a submitted expense.
   *
   * @param expenseId expense
   * @param expectedVersion the version the manager saw
   * @param reason the reason (restricted text, 10 to 500 characters)
   */
  public record Return(UUID expenseId, int expectedVersion, String reason) {

    @Override
    public String toString() {
      return "Return[" + expenseId + "]";
    }

    /**
     * Validates the return.
     *
     * @param idempotencyKey header
     * @param expenseId path value
     * @param request body
     * @return the command
     */
    public static Return from(String idempotencyKey, String expenseId, ReturnExpenseRequest request) {
      UUID id = ExpenseCommands.expenseId(expenseId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      body(errors, request);
      Integer version = version(errors, request.getExpectedVersion());
      String reason = TaskText.parse(errors, "reason", request.getReason(), 10, 500, true);
      errors.throwIfAny();
      return new Return(id, version, reason);
    }

    /**
     * The normalized command and its scope.
     *
     * @param tenant verified tenant
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("expenseId", expenseId.toString());
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("reason", reason);
      return canonical;
    }
  }

  /**
   * The metadata of a receipt mutation, validated before the body is read.
   *
   * @param expenseId expense
   * @param expectedVersion the version the employee saw
   * @param mediaType declared media type (set) or {@code null} (remove)
   * @param declaredName percent-decoded display name or {@code null}
   */
  public record ReceiptMutation(
      UUID expenseId, int expectedVersion, String mediaType, String declaredName) {

    @Override
    public String toString() {
      return "ReceiptMutation[" + expenseId + "]";
    }

    /**
     * Validates the headers of an upload before any body byte is read.
     *
     * @param idempotencyKey header
     * @param expenseId path value
     * @param expectedVersion {@code X-Expected-Version} header
     * @param contentType declared {@code Content-Type}
     * @param encodedName {@code X-Receipt-File-Name} header (percent-encoded UTF-8) or null
     * @return the command
     */
    public static ReceiptMutation set(
        String idempotencyKey,
        String expenseId,
        String expectedVersion,
        String contentType,
        String encodedName) {
      UUID id = ExpenseCommands.expenseId(expenseId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      Integer version = headerVersion(errors, expectedVersion);
      errors.throwIfAny();
      String media = ReceiptVerifier.declared(contentType);
      String name = null;
      if (encodedName != null && !encodedName.isBlank()) {
        if (encodedName.length() > 1024) {
          errors.add("X-Receipt-File-Name", Constraint.LENGTH);
        } else {
          try {
            name = java.net.URLDecoder.decode(encodedName.replace("+", "%2B"),
                java.nio.charset.StandardCharsets.UTF_8);
          } catch (IllegalArgumentException invalid) {
            errors.add("X-Receipt-File-Name", Constraint.FORMAT);
          }
        }
      }
      errors.throwIfAny();
      return new ReceiptMutation(id, version, media, name);
    }

    /**
     * Validates the removal of the receipt.
     *
     * @param idempotencyKey header
     * @param expenseId path value
     * @param expectedVersion {@code X-Expected-Version} header
     * @return the command
     */
    public static ReceiptMutation remove(
        String idempotencyKey, String expenseId, String expectedVersion) {
      UUID id = ExpenseCommands.expenseId(expenseId);
      FieldErrors errors = new FieldErrors();
      key(errors, idempotencyKey);
      Integer version = headerVersion(errors, expectedVersion);
      errors.throwIfAny();
      return new ReceiptMutation(id, version, null, null);
    }

    /**
     * The normalized command and its scope; an upload also binds the digest of the bytes.
     *
     * @param tenant verified tenant
     * @param verified the verified receipt, or {@code null} for a removal
     * @return key-sorted canonical form
     */
    public Map<String, Object> canonical(TenantId tenant, ReceiptVerifier.Verified verified) {
      Map<String, Object> canonical = new TreeMap<>();
      canonical.put("tenantId", tenant.toString());
      canonical.put("expenseId", expenseId.toString());
      canonical.put("expectedVersion", expectedVersion);
      if (verified != null) {
        canonical.put("mediaType", verified.mediaType());
        canonical.put("sha256", verified.sha256Hex());
        canonical.put("size", verified.bytes().length);
        canonical.put("fileName", verified.fileName());
      }
      return canonical;
    }
  }

  /**
   * The business date must be today or at most 90 days earlier in the organization's time zone
   * (D81-4): the exact 90-day boundary is accepted, 91 days is refused, the future is refused.
   *
   * @param date the business date
   * @param today today in the organization's time zone
   */
  public static void requireWindow(LocalDate date, LocalDate today) {
    if (date.isAfter(today) || date.isBefore(today.minusDays(MAX_AGE_DAYS))) {
      FieldErrors errors = new FieldErrors();
      errors.add("businessDate", Constraint.RANGE);
      errors.throwIfAny();
    }
  }

  /**
   * The expense of a path, or the safe {@code 404 EXPENSE_NOT_FOUND} for anything else.
   *
   * @param expenseId path value
   * @return the expense id
   */
  public static UUID expenseId(String expenseId) {
    if (expenseId == null || !UUID_SHAPE.matcher(expenseId).matches()) {
      throw notFound();
    }
    return UUID.fromString(expenseId.toLowerCase(Locale.ROOT));
  }

  /**
   * The revision of a path, or the safe {@code 404 EXPENSE_NOT_FOUND} for anything else.
   *
   * @param revision path value
   * @return the revision number
   */
  public static int revision(String revision) {
    if (revision == null || !revision.matches("[1-9][0-9]{0,5}")) {
      throw notFound();
    }
    return Integer.parseInt(revision);
  }

  /**
   * The one answer for an expense the caller may not see or change.
   *
   * @return {@code 404 EXPENSE_NOT_FOUND} with empty params
   */
  public static ApiException notFound() {
    return new ApiException(ErrorCode.EXPENSE_NOT_FOUND, Map.of());
  }

  private static void key(FieldErrors errors, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  private static void body(FieldErrors errors, StrictRequest body) {
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("a missing body is always a validation failure");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
  }

  private static Integer version(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return 0;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add("expectedVersion", Constraint.FORMAT);
      return 0;
    }
    long value = ((Number) raw).longValue();
    if (value < 1 || value > Integer.MAX_VALUE) {
      errors.add("expectedVersion", Constraint.RANGE);
      return 0;
    }
    return (int) value;
  }

  private static Integer headerVersion(FieldErrors errors, String raw) {
    if (raw == null || raw.isBlank()) {
      errors.add("X-Expected-Version", Constraint.REQUIRED);
      return 0;
    }
    if (!raw.matches("[0-9]{1,9}")) {
      errors.add("X-Expected-Version", Constraint.FORMAT);
      return 0;
    }
    long value = Long.parseLong(raw);
    if (value < 1 || value > Integer.MAX_VALUE) {
      errors.add("X-Expected-Version", Constraint.RANGE);
      return 0;
    }
    return (int) value;
  }

  private static Draft parseDraft(FieldErrors errors, ExpenseDraftRequest request) {
    LocalDate date = date(errors, request.getBusinessDate());
    ExpenseCurrency currency =
        enumeration(errors, "currency", request.getCurrency(), ExpenseCurrency.class);
    ExpenseCategory category =
        enumeration(errors, "category", request.getCategory(), ExpenseCategory.class);
    Long minor = amount(errors, currency, request.getAmount());
    String merchant = TaskText.parse(errors, "merchant", request.getMerchant(), 1, 120, true);
    String description =
        TaskText.parse(errors, "description", request.getDescription(), 10, 1000, true);
    if (date == null
        || currency == null
        || category == null
        || minor == null
        || merchant == null
        || description == null) {
      return null;
    }
    return new Draft(new Fields(date, currency, minor, category, merchant, description));
  }

  private static LocalDate date(FieldErrors errors, Object raw) {
    if (raw == null) {
      errors.add("businessDate", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DATE_SHAPE.matcher(text).matches()) {
      errors.add("businessDate", Constraint.FORMAT);
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException invalid) {
      errors.add("businessDate", Constraint.FORMAT);
      return null;
    }
  }

  /** The amount: a decimal string only, exact in the currency's own scale. */
  private static Long amount(FieldErrors errors, ExpenseCurrency currency, Object raw) {
    if (raw == null) {
      errors.add("amount", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("amount", Constraint.FORMAT);
      return null;
    }
    if (currency == null) {
      return null;
    }
    return currency
        .parse(text)
        .orElseGet(
            () -> {
              errors.add("amount", Constraint.FORMAT);
              return null;
            });
  }

  private static <E extends Enum<E>> E enumeration(
      FieldErrors errors, String field, Object raw, Class<E> type) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text) {
      for (E candidate : type.getEnumConstants()) {
        if (candidate.name().equals(text)) {
          return candidate;
        }
      }
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }
}
