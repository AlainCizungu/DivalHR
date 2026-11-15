package com.divalhr.core.payroll.application;

import com.divalhr.core.payroll.api.StatutoryPackCommandRequest;
import com.divalhr.core.payroll.api.StatutoryPackResponses.CommandReceipt;
import com.divalhr.core.payroll.api.StatutoryPackResponses.Digests;
import com.divalhr.core.payroll.api.StatutoryPackResponses.Evidence;
import com.divalhr.core.payroll.api.StatutoryPackResponses.Review;
import com.divalhr.core.payroll.api.StatutoryPackResponses.Transition;
import com.divalhr.core.payroll.api.StatutoryPackResponses.ValidationCheck;
import com.divalhr.core.payroll.api.StatutoryPackResponses.ValidationRun;
import com.divalhr.core.payroll.api.StatutoryPackResponses.VersionDetail;
import com.divalhr.core.payroll.api.StatutoryPackResponses.VersionPage;
import com.divalhr.core.payroll.api.StatutoryPackResponses.VersionSummary;
import com.divalhr.core.payroll.domain.StatutoryPackValidator;
import com.divalhr.core.payroll.domain.StatutoryPackValidator.ReferenceCase;
import com.divalhr.core.payroll.domain.StatutoryPackValidator.Report;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository.CaseRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository.Position;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository.RunRow;
import com.divalhr.core.payroll.internal.JdbcStatutoryPackGovernanceRepository.VersionRow;
import com.divalhr.core.payroll.internal.StatutoryPackConstraintViolations;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Governance of statutory payroll packs by platform administrators (MVP-097A, ADR 0040): the closed
 * lifecycle DRAFT, VALIDATED, REVIEWED, SCHEDULED, ACTIVE, RETIRED with independent four-eyes review.
 *
 * <p>The service never writes statutory content: it validates it offline, records evidence and
 * moves a version through states. Every command runs in one transaction under the version row lock
 * and checks, in this order and before any write: the version exists, the expected row version,
 * the transition, independence (the reviewer is never the submitter), the evidence and the digests,
 * the quota. Technical validation is not legal approval. Logs and metrics carry the operation and
 * outcome only; audit and events carry identifiers, state and digests only.
 */
@Service
public class StatutoryPackGovernanceService {

  /** Audit action and operation name of a read. */
  public static final String READ = StatutoryPackRecorder.READ;

  /** The lifecycle commands. */
  public enum Action {
    /** DRAFT to VALIDATED (or a repeated validation). */
    VALIDATE(StatutoryPackRecorder.VALIDATE, "validate", "validated"),
    /** Submission for independent review. */
    SUBMIT(StatutoryPackRecorder.SUBMIT_REVIEW, "submit-review", "submitted"),
    /** VALIDATED to REVIEWED. */
    APPROVE(StatutoryPackRecorder.APPROVE, "approve", "approved"),
    /** Rejection: the version retires and a new DRAFT version is created. */
    REJECT(StatutoryPackRecorder.REJECT, "reject", "rejected"),
    /** REVIEWED to SCHEDULED. */
    SCHEDULE(StatutoryPackRecorder.SCHEDULE, "schedule", "scheduled"),
    /** REVIEWED or SCHEDULED to ACTIVE. */
    ACTIVATE(StatutoryPackRecorder.ACTIVATE, "activate", "activated"),
    /** To RETIRED. */
    RETIRE(StatutoryPackRecorder.RETIRE, "retire", "retired");

    private final String operation;
    private final String verb;
    private final String pastTense;

    Action(String operation, String verb, String pastTense) {
      this.operation = operation;
      this.verb = verb;
      this.pastTense = pastTense;
    }

    /**
     * The audit action and operation name.
     *
     * @return the name
     */
    public String operation() {
      return operation;
    }

    IdempotentOperation.Spec spec() {
      return new IdempotentOperation.Spec(operation, "statutory_pack", verb, pastTense, 200);
    }

    boolean confirms() {
      return this != VALIDATE && this != SUBMIT;
    }
  }

  /**
   * The verified platform administrator.
   *
   * @param subject verified subject
   * @param correlationId request correlation ID
   */
  public record Caller(String subject, String correlationId) {}

  private static final Logger LOG = LoggerFactory.getLogger(StatutoryPackGovernanceService.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final Duration HOUR = Duration.ofHours(1);
  private static final int VALIDATIONS_PER_SUBJECT_PER_HOUR = 10;
  private static final int VALIDATIONS_PER_PLATFORM_PER_HOUR = 50;
  private static final int COMMANDS_PER_SUBJECT_PER_HOUR = 10;
  private static final int MAX_VERSIONS = 25;
  private static final String ROLE = "platform-admin";
  private static final String NO_DIGEST = "0".repeat(64);
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,63}$");
  private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Set<String> REJECT_REASONS =
      Set.of("SOURCE_MISMATCH", "VALUE_ERROR", "EVIDENCE_INSUFFICIENT", "SCOPE_ERROR", "OTHER");
  private static final Set<String> RETIRE_REASONS =
      Set.of("SUPERSEDED", "WITHDRAWN", "LEGAL_CHANGE", "ERROR");

  private final IdempotentOperation operations;
  private final JdbcStatutoryPackGovernanceRepository governance;
  private final StatutoryPackRecorder recorder;
  private final CompensationCalendar calendar;
  private final PayrollOperations plumbing;
  private final CursorCodec cursors;
  private final PlatformTransactionManager transactions;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param governance governance storage
   * @param recorder audit and outbox
   * @param calendar server clock
   * @param plumbing metrics and logs
   * @param cursors signed cursors
   * @param transactions transaction manager
   */
  public StatutoryPackGovernanceService(
      IdempotentOperation operations,
      JdbcStatutoryPackGovernanceRepository governance,
      StatutoryPackRecorder recorder,
      CompensationCalendar calendar,
      PayrollOperations plumbing,
      CursorCodec cursors,
      PlatformTransactionManager transactions) {
    this.operations = operations;
    this.governance = governance;
    this.recorder = recorder;
    this.calendar = calendar;
    this.plumbing = plumbing;
    this.cursors = cursors;
    this.transactions = transactions;
  }

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * A page of pack versions.
   *
   * @param caller verified platform administrator
   * @param cursor opaque cursor, or {@code null}
   * @param limit raw page size, or {@code null}
   * @return the page, after its disclosure audit committed
   */
  public VersionPage list(Caller caller, String cursor, String limit) {
    return plumbing.observed(
        LOG,
        "statutory_pack_read",
        READ,
        () -> {
          FieldErrors errors = new FieldErrors();
          int size =
              PayrollCommands.limit(
                  limit,
                  StatutoryCommands.DEFAULT_LIST_LIMIT,
                  StatutoryCommands.MAX_LIST_LIMIT,
                  errors);
          errors.throwIfAny();
          return read(
              () -> {
                CursorScope scope = scope(caller, size);
                Position after = null;
                if (cursor != null) {
                  KeysetPosition position = cursors.decode(cursor, scope);
                  VersionRow at =
                      governance
                          .findById(position.id())
                          .filter(row -> row.code().equals(position.code()))
                          .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
                  after = new Position(at.code(), at.version());
                }
                List<VersionRow> rows = governance.page(after, size + 1);
                List<VersionRow> shown = rows.subList(0, Math.min(rows.size(), size));
                String next = null;
                if (rows.size() > size) {
                  VersionRow last = shown.get(shown.size() - 1);
                  next = cursors.encode(scope, new KeysetPosition(last.code(), last.id()));
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("view", "list");
                metadata.put("page", cursor == null ? "first" : "cursor");
                metadata.put("resultCount", shown.size());
                recorder.disclosed(
                    caller.subject(),
                    StatutoryPackRecorder.READ,
                    StatutoryPackRecorder.PLATFORM_SCOPE,
                    null,
                    metadata,
                    caller.correlationId(),
                    calendar.now());
                return new VersionPage(
                    shown.stream().map(StatutoryPackGovernanceService::summary).toList(), next);
              });
        });
  }

  /**
   * One pack version in detail.
   *
   * @param caller verified platform administrator
   * @param packCode raw path pack code
   * @param version raw path version
   * @return the version, after its disclosure audit committed
   */
  public VersionDetail get(Caller caller, String packCode, String version) {
    return plumbing.observed(
        LOG,
        "statutory_pack_read",
        READ,
        () -> {
          String code = code(packCode);
          int number = number(version);
          return read(
              () -> {
                VersionRow row =
                    governance.find(code, number).orElseThrow(StatutoryPackGovernanceService::notFound);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("packId", row.id().toString());
                metadata.put("packCode", row.code());
                metadata.put("packVersion", row.version());
                metadata.put("country", row.country());
                metadata.put("state", row.state());
                metadata.put("view", "detail");
                recorder.disclosed(
                    caller.subject(),
                    StatutoryPackRecorder.READ,
                    row.id(),
                    null,
                    metadata,
                    caller.correlationId(),
                    calendar.now());
                return detail(caller, row);
              });
        });
  }

  private VersionDetail detail(Caller caller, VersionRow row) {
    List<ValidationRun> runs =
        governance.runs(row.id(), 10).stream()
            .map(run -> validationRun(caller, run))
            .toList();
    Review review =
        governance
            .review(row.id())
            .map(
                found ->
                    new Review(
                        found.decision(),
                        found.decidedAt(),
                        found.reasonCode(),
                        found.reviewerSubject().equals(caller.subject())))
            .orElse(null);
    List<Transition> history =
        governance.transitions(row.id()).stream()
            .map(
                entry ->
                    new Transition(
                        entry.seq(),
                        entry.kind(),
                        entry.fromState(),
                        entry.toState(),
                        entry.occurredAt(),
                        entry.actor().equals(caller.subject())))
            .toList();
    Integer supersedes =
        row.supersedesPackId() == null
            ? null
            : governance.findById(row.supersedesPackId()).map(VersionRow::version).orElse(null);
    return new VersionDetail(
        summary(row),
        new Evidence(
            row.sourceAuthority(),
            row.sourceTitle(),
            row.sourcePublishedOn(),
            row.sourceReference(),
            row.sourceBundleSha256(),
            row.evidenceSchemaVersion()),
        new Digests(row.rulesDigest(), row.packSha256(), row.fixtureSha256()),
        row.sourceCount(),
        row.caseCount(),
        row.submittedAt(),
        row.submittedBy() != null && row.submittedBy().equals(caller.subject()),
        row.retireReason(),
        supersedes,
        runs,
        review,
        history);
  }

  private static ValidationRun validationRun(Caller caller, RunRow run) {
    return new ValidationRun(
        run.id(),
        run.runAt(),
        run.outcome(),
        run.checkCount(),
        run.failedCount(),
        checks(run.summary()),
        run.engineVersion(),
        run.runBy().equals(caller.subject()));
  }

  /** The summary is written by this service as a flat array of code and outcome pairs. */
  static List<ValidationCheck> checks(String summaryJson) {
    List<ValidationCheck> checks = new ArrayList<>();
    java.util.regex.Matcher matcher =
        Pattern.compile("\"code\"\\s*:\\s*\"([A-Z0-9_:-]+)\"\\s*,\\s*\"passed\"\\s*:\\s*(true|false)")
            .matcher(summaryJson);
    while (matcher.find()) {
      checks.add(new ValidationCheck(matcher.group(1), Boolean.parseBoolean(matcher.group(2))));
    }
    return checks;
  }

  static VersionSummary summary(VersionRow row) {
    return new VersionSummary(
        row.code(),
        row.version(),
        row.semanticVersion(),
        row.country(),
        row.applicability(),
        row.state(),
        row.effectiveFrom(),
        row.effectiveTo(),
        row.activeFrom(),
        row.evidenceComplete(),
        row.lastValidation(),
        row.rowVersion(),
        "ACTIVE".equals(row.state()));
  }

  private <T> T read(java.util.function.Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout((int) TIMEOUT.toSeconds());
    T result =
        template.execute(
            status -> {
              governance.limits(TIMEOUT);
              return work.get();
            });
    return java.util.Objects.requireNonNull(result, "read transaction returned nothing");
  }

  private static CursorScope scope(Caller caller, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "list");
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    return new CursorScope(READ, new TenantId(StatutoryPackRecorder.PLATFORM_SCOPE), filters);
  }

  // ------------------------------------------------------------------------------------------
  // Commands
  // ------------------------------------------------------------------------------------------

  /**
   * One lifecycle command, or the replay of an identical one.
   *
   * @param action the command
   * @param caller verified platform administrator
   * @param packCode raw path pack code
   * @param version raw path version
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the command body
   * @return the receipt
   */
  public IdempotentOperation.Result<CommandReceipt> command(
      Action action,
      Caller caller,
      String packCode,
      String version,
      String idempotencyKey,
      StatutoryPackCommandRequest body) {
    return plumbing.command(
        action.operation(),
        () -> {
          record Parsed(String code, int version, Command command) {}
          Parsed parsed =
              operations.validated(
                  action.spec(),
                  () ->
                      new Parsed(
                          code(packCode), number(version), parse(action, idempotencyKey, body)));
          Map<String, Object> canonical = new TreeMap<>();
          canonical.put("action", action.name());
          canonical.put("packCode", parsed.code());
          canonical.put("version", parsed.version());
          parsed.command().canonical(canonical);
          return operations.execute(
              action.spec(),
              caller.subject(),
              idempotencyKey,
              canonical,
              CommandReceipt.class,
              TIMEOUT,
              () -> run(action, caller, parsed.code(), parsed.version(), parsed.command()));
        });
  }

  private IdempotentOperation.Completed<CommandReceipt> run(
      Action action, Caller caller, String code, int number, Command command) {
    Instant now = calendar.now();
    governance.limits(TIMEOUT);
    VersionRow found =
        governance.find(code, number).orElseThrow(StatutoryPackGovernanceService::notFound);
    VersionRow row = governance.lock(found.id()).orElseThrow(StatutoryPackGovernanceService::notFound);
    if (row.rowVersion() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
    List<CaseRow> cases = governance.cases(row.id());
    String validationOutcome = null;
    Integer revision = null;
    String previous = row.state();
    try {
      switch (action) {
        case VALIDATE -> validationOutcome = validate(caller, row, cases, now);
        case SUBMIT -> submit(caller, row, cases, now);
        case APPROVE -> approve(caller, row, cases, now);
        case REJECT -> revision = reject(caller, row, command, now);
        case SCHEDULE -> schedule(caller, row, cases, command, now);
        case ACTIVATE -> activate(caller, row, cases, now);
        case RETIRE -> retire(caller, row, command, now);
        default -> throw new IllegalStateException("unreachable");
      }
    } catch (DataIntegrityViolationException violated) {
      throw integrity(violated);
    }
    VersionRow after =
        governance.findById(row.id()).orElseThrow(StatutoryPackGovernanceService::notFound);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("packId", after.id().toString());
    metadata.put("packCode", after.code());
    metadata.put("packVersion", after.version());
    metadata.put("country", after.country());
    metadata.put("state", after.state());
    metadata.put("previousState", previous);
    if (after.packSha256() != null) {
      metadata.put("packSha256", after.packSha256());
    }
    if (after.fixtureSha256() != null) {
      metadata.put("fixtureSha256", after.fixtureSha256());
    }
    if (validationOutcome != null) {
      metadata.put("outcome", validationOutcome);
    }
    recorder.command(
        caller.subject(),
        action.operation(),
        after.id(),
        metadata,
        eventType(action, validationOutcome),
        caller.correlationId(),
        now);
    LOG.atInfo()
        .addKeyValue("operation", action.operation())
        .addKeyValue("outcome", "recorded")
        .log("statutory_pack_command_recorded");
    return new IdempotentOperation.Completed<>(
        new CommandReceipt(
            after.code(), after.version(), after.state(), after.rowVersion(), validationOutcome, revision),
        after.id(),
        Outcome.UPDATED);
  }

  private static String eventType(Action action, String validationOutcome) {
    return switch (action) {
      case VALIDATE -> "PASSED".equals(validationOutcome) ? StatutoryPackRecorder.VALIDATED_EVENT : null;
      case APPROVE -> StatutoryPackRecorder.REVIEWED_EVENT;
      case ACTIVATE -> StatutoryPackRecorder.ACTIVATED_EVENT;
      case RETIRE -> StatutoryPackRecorder.RETIRED_EVENT;
      default -> null;
    };
  }

  // -- validate -------------------------------------------------------------------------------

  private String validate(Caller caller, VersionRow row, List<CaseRow> cases, Instant now) {
    if (!"DRAFT".equals(row.state()) && !"VALIDATED".equals(row.state())) {
      throw refusal(row);
    }
    quota(Action.VALIDATE, caller, now);
    String rules =
        governance.rules(row.id()).orElseThrow(StatutoryPackGovernanceService::notFound);
    Set<String> keys = new HashSet<>(governance.sourceKeys(row.id()));
    Report report =
        StatutoryPackValidator.validate(
            rules, keys, row.effectiveFrom(), row.effectiveTo(), references(cases));
    if ("VALIDATED".equals(row.state())
        && (report.fixtureSha256() == null
            || !report.fixtureSha256().equals(row.fixtureSha256())
            || !row.rulesDigest().equals(row.packSha256()))) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_DIGEST_MISMATCH, Map.of());
    }
    String fixture = report.fixtureSha256() == null ? NO_DIGEST : report.fixtureSha256();
    String outcome = report.passed() ? "PASSED" : "FAILED";
    governance.insertRun(
        UUID.randomUUID(),
        row.id(),
        caller.subject(),
        now,
        outcome,
        report.checks().size(),
        report.failed(),
        summaryJson(report),
        row.rulesDigest(),
        fixture,
        report.engineVersion());
    if ("DRAFT".equals(row.state()) && report.passed()) {
      if (!governance.markValidated(
          row.id(), row.rowVersion(), row.rulesDigest(), fixture, caller.subject(), now)) {
        throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
      }
    }
    return outcome;
  }

  private static String summaryJson(Report report) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < report.checks().size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"code\":\"")
          .append(report.checks().get(i).code())
          .append("\",\"passed\":")
          .append(report.checks().get(i).passed())
          .append('}');
    }
    return json.append(']').toString();
  }

  // -- submit / approve / reject --------------------------------------------------------------

  private void submit(Caller caller, VersionRow row, List<CaseRow> cases, Instant now) {
    requireValidated(row);
    if (row.submittedBy() != null) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_TRANSITION_NOT_ALLOWED, Map.of());
    }
    if (!row.evidenceComplete()) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_EVIDENCE_INCOMPLETE, Map.of());
    }
    requireDigests(row, cases);
    quota(Action.SUBMIT, caller, now);
    if (!governance.markSubmitted(row.id(), row.rowVersion(), caller.subject(), now)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
  }

  private void approve(Caller caller, VersionRow row, List<CaseRow> cases, Instant now) {
    requireSubmitted(row);
    requireIndependent(caller, row);
    requireDigests(row, cases);
    quota(Action.APPROVE, caller, now);
    governance.insertReview(row.id(), "APPROVED", caller.subject(), now, null, null);
    if (!governance.markReviewed(row.id(), row.rowVersion(), caller.subject(), now)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
  }

  private Integer reject(Caller caller, VersionRow row, Command command, Instant now) {
    requireSubmitted(row);
    requireIndependent(caller, row);
    if (governance.versionCount(row.code()) >= MAX_VERSIONS) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_TRANSITION_NOT_ALLOWED, Map.of());
    }
    quota(Action.REJECT, caller, now);
    governance.insertReview(
        row.id(), "REJECTED", caller.subject(), now, command.reasonCode(), command.note());
    if (!governance.markRejected(row.id(), row.rowVersion(), caller.subject(), now)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
    UUID revision = UUID.randomUUID();
    int next = governance.createRevision(row.id(), revision, now);
    governance.linkRevision(revision, row.id(), caller.subject(), now);
    return next;
  }

  // -- schedule / activate / retire -----------------------------------------------------------

  private void schedule(
      Caller caller, VersionRow row, List<CaseRow> cases, Command command, Instant now) {
    requireReviewed(row, false);
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    if (!command.effectiveFrom().isAfter(today)) {
      new FieldErrors().add("effectiveFrom", Constraint.RANGE).throwIfAny();
    }
    if (command.effectiveFrom().isBefore(row.effectiveFrom())
        || (row.effectiveTo() != null && command.effectiveFrom().isAfter(row.effectiveTo()))) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_EFFECTIVE_PERIOD_CONFLICT, Map.of());
    }
    requireDigests(row, cases);
    quota(Action.SCHEDULE, caller, now);
    if (!governance.markScheduled(
        row.id(), row.rowVersion(), command.effectiveFrom(), row.effectiveTo(), caller.subject(), now)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
  }

  private void activate(Caller caller, VersionRow row, List<CaseRow> cases, Instant now) {
    requireReviewed(row, true);
    LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate from = "SCHEDULED".equals(row.state()) ? row.activeFrom() : row.effectiveFrom();
    if ("SCHEDULED".equals(row.state()) && from.isAfter(today)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_TRANSITION_NOT_ALLOWED, Map.of());
    }
    requireDigests(row, cases);
    quota(Action.ACTIVATE, caller, now);
    if (!governance.markActive(
        row.id(), row.rowVersion(), from, row.effectiveTo(), caller.subject(), now)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
  }

  private void retire(Caller caller, VersionRow row, Command command, Instant now) {
    if (!Set.of("REVIEWED", "SCHEDULED", "ACTIVE").contains(row.state())) {
      throw refusal(row);
    }
    quota(Action.RETIRE, caller, now);
    if (!governance.markRetired(
        row.id(), row.rowVersion(), command.reasonCode(), caller.subject(), now)) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VERSION_CONFLICT, Map.of());
    }
  }

  // -- checks ---------------------------------------------------------------------------------

  /** The error of a state that does not allow the command. */
  private static ApiException refusal(VersionRow row) {
    return switch (row.state()) {
      case "ACTIVE" -> new ApiException(ErrorCode.STATUTORY_PACK_ALREADY_ACTIVE, Map.of());
      default -> new ApiException(ErrorCode.STATUTORY_PACK_TRANSITION_NOT_ALLOWED, Map.of());
    };
  }

  private static void requireValidated(VersionRow row) {
    if ("VALIDATED".equals(row.state())) {
      return;
    }
    if ("DRAFT".equals(row.state())) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_VALIDATION_FAILED, Map.of());
    }
    throw refusal(row);
  }

  private static void requireSubmitted(VersionRow row) {
    requireValidated(row);
    if (row.submittedBy() == null) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_TRANSITION_NOT_ALLOWED, Map.of());
    }
  }

  private static void requireIndependent(Caller caller, VersionRow row) {
    if (caller.subject().equals(row.submittedBy())) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN, Map.of());
    }
  }

  private static void requireReviewed(VersionRow row, boolean allowScheduled) {
    boolean ok =
        "REVIEWED".equals(row.state()) || (allowScheduled && "SCHEDULED".equals(row.state()));
    if (ok) {
      return;
    }
    if ("DRAFT".equals(row.state()) || "VALIDATED".equals(row.state())) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_REVIEW_REQUIRED, Map.of());
    }
    throw refusal(row);
  }

  /** The recorded digests still equal the pack and the reference cases as stored. */
  private static void requireDigests(VersionRow row, List<CaseRow> cases) {
    String fixture = StatutoryPackValidator.fixtureSha256(references(cases));
    if (row.packSha256() == null
        || !row.packSha256().equals(row.rulesDigest())
        || fixture == null
        || !fixture.equals(row.fixtureSha256())) {
      throw new ApiException(ErrorCode.STATUTORY_PACK_DIGEST_MISMATCH, Map.of());
    }
  }

  private static List<ReferenceCase> references(List<CaseRow> cases) {
    return cases.stream()
        .map(found -> new ReferenceCase(found.key(), found.inputs(), found.expected()))
        .toList();
  }

  private void quota(Action action, Caller caller, Instant now) {
    Instant since = now.minus(HOUR);
    boolean exceeded =
        action == Action.VALIDATE
            ? governance.runsBySubjectSince(caller.subject(), since) >= VALIDATIONS_PER_SUBJECT_PER_HOUR
                || governance.runsSince(since) >= VALIDATIONS_PER_PLATFORM_PER_HOUR
            : governance.commandsBySubjectSince(caller.subject(), since)
                >= COMMANDS_PER_SUBJECT_PER_HOUR;
    if (exceeded) {
      throw new RateLimitedException(ErrorCode.RATE_LIMITED, HOUR.toSeconds());
    }
  }

  private static RuntimeException integrity(DataIntegrityViolationException violated) {
    Optional<ErrorCode> answer =
        StatutoryPackConstraintViolations.constraint(violated)
            .flatMap(StatutoryPackConstraintViolations::answer);
    if (answer.isPresent()) {
      return new ApiException(answer.get(), Map.of());
    }
    return violated;
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.STATUTORY_PACK_NOT_FOUND, Map.of());
  }

  /** A malformed path value is the same answer as an unknown one. */
  static String code(String raw) {
    if (raw == null || !CODE.matcher(raw).matches()) {
      throw notFound();
    }
    return raw;
  }

  static int number(String raw) {
    try {
      int value = Integer.parseInt(raw);
      if (value >= 1 && value <= 1000 && Integer.toString(value).equals(raw)) {
        return value;
      }
    } catch (NumberFormatException malformed) {
      // falls through to the same answer as an unknown version
    }
    throw notFound();
  }

  // ------------------------------------------------------------------------------------------
  // Command parsing
  // ------------------------------------------------------------------------------------------

  /**
   * A well-formed command.
   *
   * @param expectedVersion the row version the caller read
   * @param reasonCode closed reason (reject and retire), or {@code null}
   * @param note bounded internal note (reject), or {@code null}
   * @param effectiveFrom future effective date (schedule), or {@code null}
   */
  record Command(int expectedVersion, String reasonCode, String note, LocalDate effectiveFrom) {

    void canonical(Map<String, Object> canonical) {
      canonical.put("expectedVersion", expectedVersion);
      canonical.put("reasonCode", reasonCode == null ? "" : reasonCode);
      canonical.put("note", note == null ? "" : note);
      canonical.put("effectiveFrom", effectiveFrom == null ? "" : effectiveFrom.toString());
    }
  }

  static Command parse(Action action, String idempotencyKey, StatutoryPackCommandRequest body) {
    FieldErrors errors = new FieldErrors();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    int expected = expectedVersion(body.getExpectedVersion(), errors);
    if (action.confirms()) {
      Object confirmed = body.getConfirmed();
      if (confirmed == null || Boolean.FALSE.equals(confirmed)) {
        errors.add("confirmed", Constraint.REQUIRED);
      } else if (!(confirmed instanceof Boolean)) {
        errors.add("confirmed", Constraint.FORMAT);
      }
    } else if (body.getConfirmed() != null) {
      errors.add("confirmed", Constraint.UNKNOWN_PROPERTY);
    }
    String reason = null;
    if (action == Action.REJECT || action == Action.RETIRE) {
      reason =
          closed(
              body.getReasonCode(),
              action == Action.REJECT ? REJECT_REASONS : RETIRE_REASONS,
              "reasonCode",
              errors);
    } else if (body.getReasonCode() != null) {
      errors.add("reasonCode", Constraint.UNKNOWN_PROPERTY);
    }
    String note = null;
    if (action == Action.REJECT) {
      note = note(body.getNote(), errors);
    } else if (body.getNote() != null) {
      errors.add("note", Constraint.UNKNOWN_PROPERTY);
    }
    LocalDate from = null;
    if (action == Action.SCHEDULE) {
      from = date(body.getEffectiveFrom(), errors);
    } else if (body.getEffectiveFrom() != null) {
      errors.add("effectiveFrom", Constraint.UNKNOWN_PROPERTY);
    }
    errors.throwIfAny();
    return new Command(expected, reason, note, from);
  }

  private static int expectedVersion(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("expectedVersion", Constraint.REQUIRED);
      return 0;
    }
    if ((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() >= 1
        && ((Number) raw).longValue() <= 1_000_000L) {
      return ((Number) raw).intValue();
    }
    errors.add(
        "expectedVersion",
        raw instanceof Integer || raw instanceof Long ? Constraint.RANGE : Constraint.FORMAT);
    return 0;
  }

  private static String closed(Object raw, Set<String> allowed, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (raw instanceof String text && allowed.contains(text)) {
      return text;
    }
    errors.add(field, Constraint.FORMAT);
    return null;
  }

  private static String note(Object raw, FieldErrors errors) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof String text)) {
      errors.add("note", Constraint.FORMAT);
      return null;
    }
    if (text.isEmpty() || text.length() > 500) {
      errors.add("note", Constraint.LENGTH);
      return null;
    }
    if (text.chars().anyMatch(Character::isISOControl)) {
      errors.add("note", Constraint.FORMAT);
      return null;
    }
    return text;
  }

  private static LocalDate date(Object raw, FieldErrors errors) {
    if (raw == null) {
      errors.add("effectiveFrom", Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !DATE.matcher(text).matches()) {
      errors.add("effectiveFrom", Constraint.FORMAT);
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException invalid) {
      errors.add("effectiveFrom", Constraint.FORMAT);
      return null;
    }
  }
}
