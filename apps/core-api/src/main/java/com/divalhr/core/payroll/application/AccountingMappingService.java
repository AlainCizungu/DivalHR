package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.AccountingResponses.Accounts;
import com.divalhr.core.payroll.api.AccountingResponses.MappingList;
import com.divalhr.core.payroll.api.AccountingResponses.MappingPreview;
import com.divalhr.core.payroll.api.AccountingResponses.MappingReceipt;
import com.divalhr.core.payroll.api.AccountingResponses.MappingView;
import com.divalhr.core.payroll.api.CommitAccountingMappingRequest;
import com.divalhr.core.payroll.api.PreviewAccountingMappingRequest;
import com.divalhr.core.payroll.application.AccountingCommands.MappingCommit;
import com.divalhr.core.payroll.application.AccountingCommands.MappingDraft;
import com.divalhr.core.payroll.domain.AccountingAccounts;
import com.divalhr.core.payroll.domain.AccountingDigests;
import com.divalhr.core.payroll.domain.AccountingMappingBlocker;
import com.divalhr.core.payroll.domain.AccountingMappingStatus;
import com.divalhr.core.payroll.internal.AccountingConstraintViolations;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.MappingRow;
import com.divalhr.core.payroll.internal.JdbcAccountingRepository.NewMapping;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.PayrollPackageConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Accounting mapping versions (MVP-096, ADR 0039 sections 3 and 5): the list, the preview and the
 * idempotent, append-only creation of one version per legal entity and currency, for tenant
 * administrators with privileged MFA. Authorization has run before this; the tenant and the actor
 * never come from the request. A version is never edited or deleted; its status is derived at read
 * time. Mapping values (the account codes) never enter an audit record, an event, a log or an
 * error.
 */
@Service
public class AccountingMappingService {

  /** Mapping previews (interceptor operation). */
  public static final String PREVIEW = AccountingRecorder.MAPPING_PREVIEW;

  /** Mapping creation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = AccountingRecorder.MAPPING_CREATE;

  /** Mapping reads (interceptor operation and audit action). */
  public static final String READ = AccountingRecorder.MAPPING_READ;

  /** Per-subject previews (mapping and journal previews share it). */
  public static final String PREVIEW_BUCKET = "accounting-preview";

  /** Per-subject burst and per-tenant hourly mapping creations. */
  public static final String WRITE_BUCKET = "accounting-mapping-write";

  /** Per-subject and per-tenant reads (shared with the journal reads and downloads). */
  public static final String READ_BUCKET = "accounting-read";

  /** Most versions of one chain. */
  static final int MAX_VERSIONS = 10;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.accounting-mapping");
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration COMMIT_LIMIT = Duration.ofSeconds(25);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "accounting_mapping", "create", "created", 201);

  private final IdempotentOperation operations;
  private final JdbcAccountingRepository accounting;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final CompensationCalendar calendar;
  private final AccountingQuotas quotas;
  private final AccountingRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param accounting accounting storage
   * @param runs payroll storage (transaction limits)
   * @param entities legal entity resolver
   * @param calendar clock, business date and preview window
   * @param quotas the hourly preview quota
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public AccountingMappingService(
      IdempotentOperation operations,
      JdbcAccountingRepository accounting,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      CompensationCalendar calendar,
      AccountingQuotas quotas,
      AccountingRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.accounting = accounting;
    this.runs = runs;
    this.entities = entities;
    this.calendar = calendar;
    this.quotas = quotas;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  // ------------------------------------------------------------------------------------------
  // List
  // ------------------------------------------------------------------------------------------

  /**
   * The mapping versions of a legal entity, newest first.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param currency raw {@code currency} query value, or {@code null}
   * @return the versions, after their disclosure audit committed
   */
  public MappingList list(PayrollCaller caller, String legalEntityId, String currency) {
    return plumbing.observed(
        LOG,
        "accounting_mapping_read",
        READ,
        () -> {
          UUID entityId = AccountingCommands.mappingEntityId(legalEntityId);
          FieldErrors errors = new FieldErrors();
          String filter = AccountingCommands.currencyFilter(currency, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                LocalDate today = calendar.today(tenant);
                List<MappingRow> rows = accounting.mappings(tenant, entity, filter);
                List<MappingView> items = new ArrayList<>();
                for (MappingRow row : rows) {
                  List<LocalDate> chain =
                      rows.stream()
                          .filter(other -> other.currency().equals(row.currency()))
                          .map(MappingRow::effectiveFrom)
                          .toList();
                  LocalDate active = AccountingMappingStatus.activeDate(chain, today);
                  items.add(
                      new MappingView(
                          row.id(),
                          row.legalEntityId(),
                          row.currency(),
                          row.version(),
                          row.effectiveFrom(),
                          accounts(row.accounts()),
                          row.mappingDigest(),
                          AccountingMappingStatus.of(row.effectiveFrom(), today, active).name(),
                          row.createdAt()));
                }
                recorder.disclosed(
                    caller,
                    AccountingRecorder.MAPPING_READ,
                    entity,
                    Map.of("legalEntityId", entity.toString()),
                    "mappings",
                    items.size(),
                    now);
                return new MappingList(items);
              });
        });
  }

  private static Accounts accounts(AccountingAccounts accounts) {
    return new Accounts(
        accounts.salaryExpense(),
        accounts.employeeDeductionLiability(),
        accounts.employerContributionExpense(),
        accounts.employerContributionLiability(),
        accounts.netSalaryPayable());
  }

  // ------------------------------------------------------------------------------------------
  // Preview
  // ------------------------------------------------------------------------------------------

  /** What the chain says about a draft; reads only. */
  private record Evaluation(
      Optional<MappingRow> latest, int nextVersion, List<AccountingMappingBlocker> blockers) {
    boolean ready() {
      return blockers.isEmpty();
    }

    LocalDate previousEffectiveFrom() {
      return latest.map(MappingRow::effectiveFrom).orElse(null);
    }
  }

  private Evaluation evaluate(TenantId tenant, UUID entity, MappingDraft draft) {
    Optional<MappingRow> latest =
        accounting.latestMapping(tenant, entity, draft.currency().name());
    List<AccountingMappingBlocker> blockers = new ArrayList<>();
    if (latest.isPresent() && latest.get().version() >= MAX_VERSIONS) {
      blockers.add(AccountingMappingBlocker.LIMIT_REACHED);
    }
    if (latest.isPresent() && !draft.effectiveFrom().isAfter(latest.get().effectiveFrom())) {
      blockers.add(AccountingMappingBlocker.OVERLAP);
    }
    int next = latest.map(row -> row.version() + 1).orElse(1);
    return new Evaluation(latest, next, blockers);
  }

  private static String previewDigest(
      TenantId tenant, UUID entity, MappingDraft draft, Evaluation evaluation, Instant expiresAt) {
    String mapping =
        AccountingDigests.mapping(
            tenant.value(),
            entity,
            draft.currency(),
            evaluation.nextVersion(),
            draft.effectiveFrom(),
            draft.accounts());
    return AccountingDigests.mappingPreview(mapping, evaluation.previousEffectiveFrom(), expiresAt);
  }

  /**
   * Reviews appending a mapping version, without writing anything but the disclosure audit. A
   * preview never fails for a blocker: its status is {@code READY} or {@code BLOCKED}.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param body the currency, effective date and five codes
   * @return the preview, after its disclosure audit committed
   */
  public MappingPreview preview(
      PayrollCaller caller, String legalEntityId, PreviewAccountingMappingRequest body) {
    return plumbing.observed(
        LOG,
        "accounting_mapping_preview",
        PREVIEW,
        () -> {
          UUID entityId = AccountingCommands.mappingEntityId(legalEntityId);
          MappingDraft draft = AccountingCommands.mappingDraft(body);
          quotas.requirePreview(caller.tenant(), caller.subject(), calendar.now());
          return plumbing.inReadTransaction(
              caller.tenant(),
              READ_TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                Instant now = calendar.now();
                Evaluation evaluation = evaluate(tenant, entity, draft);
                boolean ready = evaluation.ready();
                Instant expiresAt = ready ? CompensationCalendar.expiry(now) : null;
                MappingPreview preview =
                    new MappingPreview(
                        entity,
                        draft.currency().name(),
                        evaluation.nextVersion(),
                        draft.effectiveFrom(),
                        accounts(draft.accounts()),
                        evaluation.previousEffectiveFrom(),
                        ready ? "READY" : "BLOCKED",
                        evaluation.blockers().stream().map(AccountingMappingBlocker::name).toList(),
                        ready ? previewDigest(tenant, entity, draft, evaluation, expiresAt) : null,
                        expiresAt);
                recorder.disclosed(
                    caller,
                    AccountingRecorder.MAPPING_PREVIEW,
                    entity,
                    Map.of("legalEntityId", entity.toString(), "currency", draft.currency().name()),
                    "preview",
                    1,
                    now);
                return preview;
              });
        },
        preview -> "BLOCKED".equals(preview.status()) ? Outcome.STATE_CONFLICT : Outcome.LISTED);
  }

  // ------------------------------------------------------------------------------------------
  // Create
  // ------------------------------------------------------------------------------------------

  /**
   * Appends a previewed mapping version, or replays an identical command.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the previewed version, the evidence the preview returned and the confirmation
   * @return the receipt
   */
  public IdempotentOperation.Result<MappingReceipt> create(
      PayrollCaller caller,
      String legalEntityId,
      String idempotencyKey,
      CommitAccountingMappingRequest body) {
    return plumbing.command(
        CREATE,
        () -> {
          record Parsed(UUID entity, MappingCommit command) {}
          Parsed parsed =
              operations.validated(
                  CREATE_SPEC,
                  () ->
                      new Parsed(
                          AccountingCommands.mappingEntityId(legalEntityId),
                          AccountingCommands.mappingCommit(idempotencyKey, body)));
          return operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant().value(), parsed.entity()),
              MappingReceipt.class,
              WRITE_TIMEOUT,
              () -> created(caller, parsed.entity(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<MappingReceipt> created(
      PayrollCaller caller, UUID entityId, MappingCommit command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, COMMIT_LIMIT);
    UUID entity = entities.require(tenant, entityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    MappingDraft draft = command.draft();
    // Lock order: the chain's lock first, the same key the database guard takes.
    accounting.lockMappingChain(tenant, entity, draft.currency().name());
    Evaluation evaluation = evaluate(tenant, entity, draft);
    requireAppendable(tenant, entity, draft, evaluation, command, now);
    UUID id = UUID.randomUUID();
    int version = evaluation.nextVersion();
    String digest =
        AccountingDigests.mapping(
            tenant.value(),
            entity,
            draft.currency(),
            version,
            draft.effectiveFrom(),
            draft.accounts());
    try {
      accounting.insertMapping(
          tenant,
          new NewMapping(
              id,
              entity,
              draft.currency().name(),
              version,
              draft.effectiveFrom(),
              draft.accounts(),
              digest,
              now,
              caller.subject()));
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.mappingCreated(
        caller,
        new AccountingRecorder.MappingChange(entity, id, draft.currency().name(), version, now));
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("currency", draft.currency().name())
        .addKeyValue("version", version)
        .log("accounting_mapping_created");
    return new IdempotentOperation.Completed<>(
        new MappingReceipt(
            id, entity, draft.currency().name(), version, draft.effectiveFrom(), now),
        id,
        Outcome.CREATED);
  }

  /** The contract's check order; nothing is written before every check passed. */
  private static void requireAppendable(
      TenantId tenant,
      UUID entity,
      MappingDraft draft,
      Evaluation evaluation,
      MappingCommit command,
      Instant now) {
    // 1. The chain is full.
    if (evaluation.blockers().contains(AccountingMappingBlocker.LIMIT_REACHED)) {
      throw new ApiException(ErrorCode.ACCOUNTING_MAPPING_LIMIT_REACHED, Map.of());
    }
    // 2. The effective date is not strictly later than the latest version's.
    if (evaluation.blockers().contains(AccountingMappingBlocker.OVERLAP)) {
      throw new ApiException(ErrorCode.ACCOUNTING_MAPPING_OVERLAP, Map.of());
    }
    // 3. The chain moved since the preview, expiry, and any difference of the digest.
    Instant expiresAt = command.expiresAt();
    String digest = previewDigest(tenant, entity, draft, evaluation, expiresAt);
    if (evaluation.nextVersion() != command.expectedVersion()
        || !expiresAt.isAfter(now)
        || expiresAt.isAfter(CompensationCalendar.expiry(now))
        || !CompensationCalendar.onGrid(expiresAt)
        || !digest.equals(command.previewDigest())) {
      throw new ApiException(ErrorCode.ACCOUNTING_MAPPING_PREVIEW_STALE, Map.of());
    }
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        PayrollPackageConstraintViolations.constraint(violated)
            .flatMap(AccountingConstraintViolations::mappingAnswer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
