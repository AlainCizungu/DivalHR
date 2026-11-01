package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.CommitPaymentDestinationRequest;
import com.divalhr.core.payroll.api.PaymentDestinationCommandRequest;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationPage;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationPreview;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationReceipt;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationRecord;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationView;
import com.divalhr.core.payroll.api.PaymentResponses.MyDestination;
import com.divalhr.core.payroll.application.PaymentCommands.DestinationCommit;
import com.divalhr.core.payroll.domain.PaymentCipher;
import com.divalhr.core.payroll.domain.PaymentDestinationBlocker;
import com.divalhr.core.payroll.domain.PaymentDestinationDraft;
import com.divalhr.core.payroll.domain.PaymentDestinationWarning;
import com.divalhr.core.payroll.domain.PaymentDigests;
import com.divalhr.core.payroll.domain.PaymentSealException;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.DestinationRow;
import com.divalhr.core.payroll.internal.JdbcPaymentRepository.NewDestination;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.PaymentConstraintViolations;
import com.divalhr.core.payroll.internal.PayrollPackageConstraintViolations;
import com.divalhr.core.platform.access.EmployeeAccessLinks;
import com.divalhr.core.platform.access.EmployeeAccessLinks.SelfLink;
import com.divalhr.core.platform.access.EmployeeRecords;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.security.ScopeAuthorizationInterceptor;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Salary payment destinations of employees (MVP-095A, ADR 0036): the masked current read, the
 * history, the preview and the idempotent commit for tenant administrators with privileged MFA, and
 * the employee's own masked read. Authorization has run before this; the tenant, the actor and the
 * employee scope never come from the request. A destination is appended, never changed: it is
 * RECORDED, never verified with a bank or provider.
 *
 * <p>Restricted financial and HR data: the account, phone, holder and branch are sealed with
 * AES-256-GCM before they are stored and are never returned, logged, audited, published, put in a
 * cursor, a receipt or an error; only the masked value is shown, and only after the commit. A
 * duplicate check uses a keyed fingerprint. When the key is unavailable, or an envelope does not
 * authenticate, the operation fails closed with a stable 503.
 *
 * <p>A commit runs in one idempotent transaction in the lock order of ADR 0036: the idempotency
 * reservation, the tenant payment lock shared, then the employee lock; everything is re-read under
 * the locks and decided in the contract's order before anything is written.
 */
@Service
public class PaymentDestinationService {

  /** Admin reads (interceptor operation and bucket). */
  public static final String READ = "payment-destination.read";

  /** Previews (interceptor operation). */
  public static final String PREVIEW = "payment-destination.preview";

  /** Commands (idempotency operation, interceptor operation and audit action). */
  public static final String COMMIT = PaymentRecorder.DESTINATION_RECORD;

  /** The employee's own read (interceptor operation). */
  public static final String SELF_READ = "payment-destination.self-read";

  /** Per-subject reads. */
  public static final String SUBJECT_READ_BUCKET = "payment-read";

  /** Per-subject previews. */
  public static final String SUBJECT_PREVIEW_BUCKET = "payment-destination-preview";

  /** Per-subject commands. */
  public static final String SUBJECT_COMMIT_BUCKET = "payment-destination-write";

  /** Per-tenant previews. */
  public static final String TENANT_PREVIEW_BUCKET = "payment-destination-preview";

  /** Per-tenant commands. */
  public static final String TENANT_COMMIT_BUCKET = "payment-destination-write";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.payment-destination");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(15);
  private static final String CURSOR_CODE = "PAYDEST";
  private static final IdempotentOperation.Spec COMMIT_SPEC =
      new IdempotentOperation.Spec(COMMIT, "payment_destination", "record", "recorded", 201);

  private final IdempotentOperation operations;
  private final JdbcPaymentRepository payments;
  private final JdbcPayrollRunRepository runs;
  private final EmployeeRecords employees;
  private final EmployeeAccessLinks links;
  private final CompensationCalendar calendar;
  private final PaymentSecrets secrets;
  private final PaymentRecorder recorder;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;
  private final MeterRegistry meters;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param payments payment storage
   * @param runs payroll storage (transaction limits)
   * @param employees the people module's employee port
   * @param links employee access links
   * @param calendar business date and preview window
   * @param secrets payment cipher
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   * @param cursors signed cursors
   * @param meters meter registry (self-service denials)
   */
  public PaymentDestinationService(
      IdempotentOperation operations,
      JdbcPaymentRepository payments,
      JdbcPayrollRunRepository runs,
      EmployeeRecords employees,
      EmployeeAccessLinks links,
      CompensationCalendar calendar,
      PaymentSecrets secrets,
      PaymentRecorder recorder,
      PayrollOperations plumbing,
      CursorCodec cursors,
      MeterRegistry meters) {
    this.operations = operations;
    this.payments = payments;
    this.runs = runs;
    this.employees = employees;
    this.links = links;
    this.calendar = calendar;
    this.secrets = secrets;
    this.recorder = recorder;
    this.plumbing = plumbing;
    this.cursors = cursors;
    this.meters = meters;
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * The employee's masked destination: current today, latest version and a scheduled one.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @return the masked record, after its disclosure audit committed
   */
  public DestinationRecord current(PayrollCaller caller, String employeeId) {
    return plumbing.observed(
        LOG,
        "payment_destination_read",
        READ,
        () -> {
          UUID employee = PaymentCommands.employeeId(employeeId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                requireEmployee(tenant, employee);
                Instant now = calendar.now();
                LocalDate today = calendar.today(tenant);
                Optional<DestinationRow> current = payments.effective(tenant, employee, today);
                Optional<DestinationRow> scheduled = payments.scheduled(tenant, employee, today);
                int latest =
                    payments.latest(tenant, employee).map(DestinationRow::sequence).orElse(0);
                recorder.disclosed(
                    caller,
                    PaymentRecorder.DESTINATION_READ,
                    "payment-destination",
                    employee,
                    Map.of("employeeId", employee.toString()),
                    "current",
                    true,
                    current.isPresent() ? 1 : 0,
                    now);
                return new DestinationRecord(
                    employee,
                    latest,
                    current.map(PaymentViews::destination).orElse(null),
                    scheduled.map(PaymentViews::destination).orElse(null));
              });
        });
  }

  /**
   * The employee's destination history, newest first (masked).
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public DestinationPage history(
      PayrollCaller caller, String employeeId, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "payment_destination_read",
        READ,
        () -> {
          UUID employee = PaymentCommands.employeeId(employeeId);
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  PaymentCommands.DEFAULT_HISTORY_LIMIT,
                  PaymentCommands.MAX_HISTORY_LIMIT,
                  errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                requireEmployee(tenant, employee);
                CursorScope scope = scope(caller, employee, size);
                Integer before = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  before =
                      payments
                          .byId(tenant, employee, position.id())
                          .filter(row -> CURSOR_CODE.equals(position.code()))
                          .map(DestinationRow::sequence)
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                }
                List<DestinationRow> rows = payments.history(tenant, employee, before, size + 1);
                List<DestinationRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  next =
                      cursors.encode(
                          scope, new KeysetPosition(CURSOR_CODE, shown.get(shown.size() - 1).id()));
                }
                recorder.disclosed(
                    caller,
                    PaymentRecorder.DESTINATION_READ,
                    "payment-destination",
                    employee,
                    Map.of("employeeId", employee.toString()),
                    "history",
                    cursor == null,
                    shown.size(),
                    calendar.now());
                List<DestinationView> items = new ArrayList<>();
                for (DestinationRow row : shown) {
                  items.add(PaymentViews.destination(row));
                }
                return new DestinationPage(items, next);
              });
        });
  }

  /**
   * The caller's own current destination, masked and read-only.
   *
   * @param caller verified employee
   * @return the own masked destination, after its disclosure audit committed
   */
  public MyDestination mine(PayrollCaller caller) {
    return plumbing.observed(
        LOG,
        "payment_destination_self_read",
        SELF_READ,
        () ->
            plumbing.inReadTransaction(
                caller.tenant(),
                READ_TIMEOUT,
                () -> {
                  TenantId tenant = caller.tenant();
                  SelfLink link =
                      links
                          .linkedEmployee(tenant, caller.subject())
                          .orElseThrow(
                              () -> {
                                ScopeAuthorizationInterceptor.selfServiceDenied(
                                    meters, SELF_READ, "link_required");
                                return new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of());
                              });
                  Optional<DestinationRow> current =
                      payments.effective(tenant, link.employeeId(), calendar.today(tenant));
                  recorder.disclosed(
                      caller,
                      PaymentRecorder.DESTINATION_READ,
                      "payment-destination",
                      link.employeeId(),
                      Map.of("employeeId", link.employeeId().toString()),
                      "own",
                      true,
                      current.isPresent() ? 1 : 0,
                      calendar.now());
                  if (current.isEmpty()) {
                    return new MyDestination("NOT_RECORDED", null, null, null, null, null);
                  }
                  DestinationRow row = current.get();
                  return new MyDestination(
                      "RECORDED",
                      row.method(),
                      row.country(),
                      PaymentViews.provider(row.providerCode(), row.providerName()),
                      row.masked(),
                      row.effectiveFrom());
                }));
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /**
   * Reviews what recording a destination would do, without writing anything but the disclosure
   * audit. The values are validated and sealed in memory only; nothing sensitive is returned.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param body the destination
   * @return the preview, after its disclosure audit committed
   */
  public DestinationPreview preview(
      PayrollCaller caller, String employeeId, PaymentDestinationCommandRequest body) {
    return plumbing.observed(
        LOG,
        "payment_destination_preview",
        PREVIEW,
        () -> {
          UUID employee = PaymentCommands.employeeId(employeeId);
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                requireEmployee(tenant, employee);
                Instant now = calendar.now();
                LocalDate today = calendar.today(tenant);
                PaymentDestinationDraft draft = PaymentCommands.destinationDraft(body, today);
                requireKey();
                Optional<DestinationRow> latest = payments.latest(tenant, employee);
                int expected = latest.map(DestinationRow::sequence).orElse(0);
                if (latest.isPresent()
                    && !draft.effectiveFrom().isAfter(latest.get().effectiveFrom())) {
                  throw new ApiException(ErrorCode.PAYMENT_DESTINATION_INVALID, Map.of());
                }
                List<PaymentDestinationBlocker> blockers = new ArrayList<>();
                List<PaymentDestinationWarning> warnings = new ArrayList<>();
                if (latest.isPresent() && sameContent(tenant, latest.get(), draft)) {
                  blockers.add(PaymentDestinationBlocker.NO_CHANGE);
                }
                String identifier = identifierFingerprint(tenant, draft);
                if (identifier != null && payments.otherHolders(tenant, identifier, employee) > 0) {
                  warnings.add(PaymentDestinationWarning.DUPLICATE_DESTINATION);
                }
                if (draft.effectiveFrom().isBefore(today)) {
                  warnings.add(PaymentDestinationWarning.BACKDATED);
                }
                boolean ready = blockers.isEmpty();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                String digest =
                    ready
                        ? PaymentDigests.destinationPreview(
                            tenant.value(),
                            employee,
                            expected,
                            contentFingerprint(tenant, draft),
                            draft.effectiveFrom(),
                            draft.reason(),
                            expiresAt)
                        : null;
                recorder.disclosed(
                    caller,
                    PaymentRecorder.DESTINATION_READ,
                    "payment-destination",
                    employee,
                    Map.of("employeeId", employee.toString()),
                    "preview",
                    true,
                    0,
                    now);
                return new DestinationPreview(
                    employee,
                    expected,
                    ready ? "READY" : "BLOCKED",
                    blockers.stream().map(PaymentDestinationBlocker::name).toList(),
                    warnings.stream().map(PaymentDestinationWarning::name).toList(),
                    draft.method().name(),
                    draft.country(),
                    draft.provider() == null
                        ? null
                        : PaymentViews.provider(draft.provider().code(), draft.provider().name()),
                    draft.maskedValue(),
                    draft.effectiveFrom(),
                    draft.reason().name(),
                    digest,
                    expiresAt);
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  // ------------------------------------------------------------------------------------------
  // Commit
  // ------------------------------------------------------------------------------------------

  /**
   * Records a previewed destination, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the destination, the evidence its preview returned and the confirmation
   * @return the masked receipt
   */
  public IdempotentOperation.Result<DestinationReceipt> recordDestination(
      PayrollCaller caller,
      String employeeId,
      String idempotencyKey,
      CommitPaymentDestinationRequest body) {
    return plumbing.command(
        COMMIT,
        () -> {
          record Parsed(UUID employee, DestinationCommit command) {}
          Parsed parsed =
              operations.validated(
                  COMMIT_SPEC,
                  () -> {
                    UUID employee = PaymentCommands.employeeId(employeeId);
                    return new Parsed(
                        employee,
                        PaymentCommands.destinationCommit(
                            idempotencyKey, body, calendar.today(caller.tenant())));
                  });
          requireKey();
          TenantId tenant = caller.tenant();
          PaymentDestinationDraft draft = parsed.command().draft();
          String explanation =
              draft.explanation() == null
                  ? ""
                  : secrets.fingerprint(tenant.value(), "explanation", draft.explanation());
          return operations.execute(
              COMMIT_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed
                  .command()
                  .canonical(
                      tenant.value(),
                      parsed.employee(),
                      contentFingerprint(tenant, draft),
                      explanation),
              DestinationReceipt.class,
              WRITE_TIMEOUT,
              () -> recorded(caller, parsed.employee(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<DestinationReceipt> recorded(
      PayrollCaller caller, UUID employee, DestinationCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    requireEmployee(tenant, employee);
    Instant now = calendar.now();
    // Lock order of ADR 0036 section 8: the tenant payment lock shared, then the employee lock.
    payments.lockPayments(tenant, true);
    payments.lockEmployee(tenant, employee);
    PaymentDestinationDraft draft = command.draft();
    Optional<DestinationRow> latest = payments.latest(tenant, employee);
    int expected = latest.map(DestinationRow::sequence).orElse(0);
    // 1. Another command moved the employee's chain.
    if (expected != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_VERSION_CONFLICT, Map.of());
    }
    // 2. The effective date does not follow the current version, or nothing changes.
    if (latest.isPresent()
        && (!draft.effectiveFrom().isAfter(latest.get().effectiveFrom())
            || sameContent(tenant, latest.get(), draft))) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_INVALID, Map.of());
    }
    // 3. Expiry and any difference of the digest.
    Instant expiresAt = command.expiresAt();
    String digest =
        PaymentDigests.destinationPreview(
            tenant.value(),
            employee,
            expected,
            contentFingerprint(tenant, draft),
            draft.effectiveFrom(),
            draft.reason(),
            expiresAt);
    if (!expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_PREVIEW_STALE, Map.of());
    }
    if (expected >= PaymentCommands.MAX_DESTINATION_VERSION) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_INVALID, Map.of());
    }
    UUID id = UUID.randomUUID();
    int sequence = expected + 1;
    String sealedHex = null;
    String fingerprint = null;
    Integer keyVersion = null;
    if (draft.method().hasAccount()) {
      byte[] sealed = sealed(tenant, employee, id, sequence, draft);
      sealedHex = HexFormat.of().formatHex(sealed);
      fingerprint = identifierFingerprint(tenant, draft);
      keyVersion = PaymentCipher.KEY_VERSION;
    }
    NewDestination row =
        new NewDestination(
            id,
            employee,
            sequence,
            draft.effectiveFrom(),
            draft.method().name(),
            draft.country(),
            draft.provider() == null ? null : draft.provider().code(),
            draft.provider() == null ? null : draft.provider().name(),
            draft.maskedValue(),
            fingerprint,
            sealedHex,
            keyVersion,
            draft.reason().name(),
            now,
            caller.subject());
    try {
      payments.insertDestination(tenant, row);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.destinationRecorded(
        caller,
        new PaymentRecorder.DestinationRecorded(employee, sequence, draft.method().name(), now));
    LOG.atInfo()
        .addKeyValue("operation", COMMIT)
        .addKeyValue("outcome", "recorded")
        .log("payment_destination_recorded");
    return new IdempotentOperation.Completed<>(
        new DestinationReceipt(
            employee,
            sequence,
            draft.method().name(),
            draft.country(),
            draft.provider() == null
                ? null
                : PaymentViews.provider(draft.provider().code(), draft.provider().name()),
            draft.maskedValue(),
            draft.effectiveFrom(),
            "RECORDED",
            draft.reason().name(),
            now),
        id,
        Outcome.CREATED);
  }

  // ------------------------------------------------------------------------------------------
  // Sealing and comparison
  // ------------------------------------------------------------------------------------------

  private byte[] sealed(
      TenantId tenant, UUID employee, UUID id, int sequence, PaymentDestinationDraft draft) {
    try {
      return secrets.seal(draft.sealedText(), tenant.value(), employee, id, sequence);
    } catch (PaymentSealException unavailable) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_KEY_UNAVAILABLE, Map.of());
    }
  }

  /** The fields that decide "the same destination": no date, reason or explanation. */
  private static String comparable(
      String method,
      String country,
      String provider,
      String account,
      String holder,
      String branch,
      String phone) {
    Map<String, String> fields = new TreeMap<>();
    fields.put("method", method);
    fields.put("country", country == null ? "" : country);
    fields.put("provider", provider == null ? "" : provider);
    fields.put("account", account == null ? "" : account);
    fields.put("holder", holder == null ? "" : holder);
    fields.put("branch", branch == null ? "" : branch);
    fields.put("phone", phone == null ? "" : phone);
    StringBuilder text = new StringBuilder();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      text.append(field.getKey()).append('=').append(field.getValue()).append('\n');
    }
    return text.toString();
  }

  private static String comparable(PaymentDestinationDraft draft) {
    return comparable(
        draft.method().name(),
        draft.country(),
        draft.provider() == null ? null : draft.provider().code(),
        draft.account(),
        draft.holder(),
        draft.branch(),
        draft.phone());
  }

  private String contentFingerprint(TenantId tenant, PaymentDestinationDraft draft) {
    return secrets.fingerprint(tenant.value(), "content", comparable(draft));
  }

  private String identifierFingerprint(TenantId tenant, PaymentDestinationDraft draft) {
    String identifier = draft.identifier();
    if (identifier == null) {
      return null;
    }
    return secrets.fingerprint(
        tenant.value(),
        "identifier",
        draft.method().name() + ":" + draft.provider().code() + ":" + identifier);
  }

  /** Decrypts the stored version and compares its content; an envelope that fails is a 503. */
  private boolean sameContent(TenantId tenant, DestinationRow row, PaymentDestinationDraft draft) {
    if (!row.method().equals(draft.method().name())) {
      return false;
    }
    if (row.sealedHex() == null) {
      return !draft.method().hasAccount();
    }
    String text;
    try {
      text = secrets.open(row.sealed(), tenant.value(), row.employeeId(), row.id(), row.sequence());
    } catch (PaymentSealException unavailable) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_KEY_UNAVAILABLE, Map.of());
    }
    Map<String, String> fields =
        PaymentDestinationDraft.parseSealed(text)
            .orElseThrow(
                () -> new ApiException(ErrorCode.PAYMENT_DESTINATION_KEY_UNAVAILABLE, Map.of()));
    String stored =
        comparable(
            row.method(),
            row.country(),
            row.providerCode(),
            fields.get("account"),
            fields.get("holder"),
            fields.get("branch"),
            fields.get("phone"));
    return stored.equals(comparable(draft));
  }

  private void requireKey() {
    if (!secrets.available()) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_KEY_UNAVAILABLE, Map.of());
    }
  }

  private void requireEmployee(TenantId tenant, UUID employee) {
    if (!employees.exists(tenant, employee)) {
      throw new ApiException(ErrorCode.PAYMENT_DESTINATION_NOT_FOUND, Map.of());
    }
  }

  private static CursorScope scope(PayrollCaller caller, UUID employee, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "history");
    filters.put("employee", employee.toString());
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, caller.tenant(), filters);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollPackageConstraintViolations.constraint(violated)
            .flatMap(PaymentConstraintViolations::destinationAnswer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
