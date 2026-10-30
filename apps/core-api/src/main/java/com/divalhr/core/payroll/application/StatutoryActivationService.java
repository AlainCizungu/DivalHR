package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.ActivateStatutoryRulePackRequest;
import com.divalhr.core.payroll.api.StatutoryResponses.ActivationEntry;
import com.divalhr.core.payroll.api.StatutoryResponses.ActivationReceipt;
import com.divalhr.core.payroll.api.StatutoryResponses.ActivationView;
import com.divalhr.core.payroll.api.StatutoryResponses.InEffect;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.internal.JdbcPayrollRunRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.ActivationRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryRepository.NewActivation;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryRulePackRepository.PackRow;
import com.divalhr.core.payroll.internal.StatutoryConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Activation of a VERIFIED statutory rule pack for a legal entity, for tenant administrators with
 * privileged MFA (MVP-094B, Issue #179, ADR 0034). Authorization has run before this; tenant, actor
 * and scope never come from the request. An activation is the employer's acknowledged choice of a
 * pack from a month: it is appended, never edited or deleted, and never changes a frozen
 * calculation. A pack that is not VERIFIED can never be activated, and no operation verifies one.
 *
 * <p>A commit runs in one idempotent transaction in the lock order of ADR 0034 section 7: the
 * idempotency reservation, the tenant statutory lock shared, the legal entity's activation lock
 * exclusive, the checks, then the deferred checks, the audit record and the outbox event.
 */
@Service
public class StatutoryActivationService {

  /** Reads (interceptor operation and disclosure audit action). */
  public static final String READ = StatutoryRecorder.ACTIVATION_READ;

  /** Activation (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = StatutoryRecorder.ACTIVATION_CREATE;

  /** Per-subject activations. */
  public static final String SUBJECT_WRITE_BUCKET = "statutory-activation-write";

  /** The most activations of one legal entity. */
  public static final int MAX_ACTIVATIONS = 60;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.statutory-activation");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "statutory_activation", "create", "created", 201);

  private final IdempotentOperation operations;
  private final JdbcStatutoryRepository statutory;
  private final JdbcStatutoryRulePackRepository packs;
  private final JdbcPayrollRunRepository runs;
  private final PayrollEntities entities;
  private final StatutoryEvaluator evaluator;
  private final CompensationCalendar calendar;
  private final StatutoryRecorder recorder;
  private final PayrollOperations plumbing;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param statutory tenant-owned statutory storage
   * @param packs platform-owned rule packs
   * @param runs payroll storage (transaction limits)
   * @param entities legal entity resolver
   * @param evaluator rule pack resolution
   * @param calendar server clock
   * @param recorder audit and outbox
   * @param plumbing transactions, metrics and logs
   */
  public StatutoryActivationService(
      IdempotentOperation operations,
      JdbcStatutoryRepository statutory,
      JdbcStatutoryRulePackRepository packs,
      JdbcPayrollRunRepository runs,
      PayrollEntities entities,
      StatutoryEvaluator evaluator,
      CompensationCalendar calendar,
      StatutoryRecorder recorder,
      PayrollOperations plumbing) {
    this.operations = operations;
    this.statutory = statutory;
    this.packs = packs;
    this.runs = runs;
    this.entities = entities;
    this.evaluator = evaluator;
    this.calendar = calendar;
    this.recorder = recorder;
    this.plumbing = plumbing;
  }

  // ------------------------------------------------------------------------------------------
  // Read
  // ------------------------------------------------------------------------------------------

  /**
   * The activations of a legal entity and the pack in effect for a month.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param month raw {@code YYYY-MM} query value
   * @return the view, after its disclosure audit committed
   */
  public ActivationView read(PayrollCaller caller, String legalEntityId, String month) {
    return plumbing.observed(
        LOG,
        "statutory_activation_read",
        READ,
        () -> {
          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
          FieldErrors errors = new FieldErrors();
          PayrollPeriod period = StatutoryCommands.month(month, errors);
          errors.throwIfAny();
          return plumbing.inReadTransaction(
              caller.tenant(),
              TIMEOUT,
              () -> {
                TenantId tenant = caller.tenant();
                UUID entity =
                    entities.require(tenant, legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
                List<ActivationRow> activations = statutory.activations(tenant, entity);
                int version = statutory.activationVersion(tenant, entity);
                InEffect inEffect =
                    evaluator
                        .resolve(tenant, entity, period.first())
                        .filter(found -> StatutoryEvaluator.usable(found, period.first()))
                        .map(
                            found ->
                                new InEffect(
                                    found.activation().id(),
                                    found.activation().sequence(),
                                    StatutoryViews.month(found.activation().fromMonth()),
                                    StatutoryViews.summary(found.pack())))
                        .orElse(null);
                recorder.disclosed(
                    caller,
                    READ,
                    "statutory-activation",
                    entity,
                    Map.of("legalEntityId", entity.toString()),
                    "activation",
                    true,
                    activations.size(),
                    calendar.now());
                List<ActivationEntry> entries =
                    activations.stream().map(StatutoryViews::entry).toList();
                return new ActivationView(
                    entity, period.text(), version, inEffect == null, inEffect, entries);
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Activate
  // ------------------------------------------------------------------------------------------

  /**
   * Activates a verified pack for a legal entity from a month, or replays an identical activation.
   *
   * @param caller verified tenant administrator
   * @param legalEntityId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the activation
   * @return the receipt
   */
  public IdempotentOperation.Result<ActivationReceipt> activate(
      PayrollCaller caller,
      String legalEntityId,
      String idempotencyKey,
      ActivateStatutoryRulePackRequest body) {
    return plumbing.command(
        CREATE,
        () -> {
          record Parsed(UUID entity, StatutoryCommands.Activation command) {}
          Parsed parsed =
              operations.validated(
                  CREATE_SPEC,
                  () ->
                      new Parsed(
                          PayrollCommands.id(legalEntityId, ErrorCode.LEGAL_ENTITY_NOT_FOUND),
                          StatutoryCommands.activation(idempotencyKey, body)));
          return operations.execute(
              CREATE_SPEC,
              caller.subject(),
              idempotencyKey,
              parsed.command().canonical(caller.tenant(), parsed.entity()),
              ActivationReceipt.class,
              TIMEOUT,
              () -> activated(caller, parsed.entity(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<ActivationReceipt> activated(
      PayrollCaller caller, UUID entity, StatutoryCommands.Activation command) {
    TenantId tenant = caller.tenant();
    runs.limits(tenant, TIMEOUT);
    entities.require(tenant, entity, ErrorCode.LEGAL_ENTITY_NOT_FOUND);
    Instant now = calendar.now();
    // Lock order of ADR 0034 section 7: the tenant lock shared, then the entity's activations.
    statutory.lockStatutory(tenant, true);
    statutory.lockActivation(tenant, entity);

    PackRow pack =
        packs
            .pack(command.packId())
            .orElseThrow(() -> new ApiException(ErrorCode.STATUTORY_RULE_PACK_NOT_FOUND, Map.of()));
    if (!"VERIFIED".equals(pack.status())) {
      throw new ApiException(ErrorCode.STATUTORY_RULE_PACK_NOT_VERIFIED, Map.of());
    }
    if (!"CD".equals(pack.country())
        || !"CDF".equals(pack.currency())
        || !StatutoryEvaluator.covers(pack, command.fromMonth().first())) {
      throw new ApiException(ErrorCode.STATUTORY_RULE_PACK_NOT_APPLICABLE, Map.of());
    }
    List<ActivationRow> existing = statutory.activations(tenant, entity);
    int version = statutory.activationVersion(tenant, entity);
    boolean monthTaken =
        existing.stream().anyMatch(row -> row.fromMonth().equals(command.fromMonth().first()));
    if (version != command.expectedVersion() || monthTaken) {
      throw new ApiException(ErrorCode.STATUTORY_VERSION_CONFLICT, Map.of());
    }
    if (version >= MAX_ACTIVATIONS) {
      throw new ApiException(ErrorCode.STATUTORY_LIMIT_REACHED, Map.of());
    }
    UUID id = UUID.randomUUID();
    int sequence = version + 1;
    try {
      statutory.insertActivation(
          tenant,
          new NewActivation(
              id, entity, sequence, pack.id(), command.fromMonth().first(), now, caller.subject()));
      statutory.checkDeferred(tenant);
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    recorder.activated(
        caller,
        new StatutoryRecorder.ActivationRecorded(
            entity, id, pack.id(), pack.version(), command.fromMonth().text(), sequence, now));
    LOG.atInfo()
        .addKeyValue("operation", CREATE)
        .addKeyValue("outcome", "recorded")
        .log("statutory_activation_recorded");
    return new IdempotentOperation.Completed<>(
        new ActivationReceipt(
            id,
            entity,
            pack.id(),
            pack.code(),
            pack.version(),
            command.fromMonth().text(),
            sequence),
        id,
        Outcome.CREATED);
  }

  /** A guard that still fires is a lost race (or a defect); no database text is exposed. */
  static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        StatutoryConstraintViolations.constraint(violated)
            .flatMap(StatutoryConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }
}
