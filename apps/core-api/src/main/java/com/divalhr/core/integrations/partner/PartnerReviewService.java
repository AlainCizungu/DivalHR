package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Check;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Consent;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Contact;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Detail;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.HistoryEntry;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.HistoryPage;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Page;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Summary;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.TransitionReceipt;
import com.divalhr.core.integrations.partner.PartnerApplicationRepository.CheckRow;
import com.divalhr.core.integrations.partner.PartnerApplicationRepository.EventRow;
import com.divalhr.core.integrations.partner.PartnerApplicationRepository.Filter;
import com.divalhr.core.integrations.partner.PartnerApplicationRepository.Row;
import com.divalhr.core.integrations.partner.PartnerDomain.CheckCode;
import com.divalhr.core.integrations.partner.PartnerDomain.CheckResult;
import com.divalhr.core.integrations.partner.PartnerDomain.EventType;
import com.divalhr.core.integrations.partner.PartnerDomain.Reason;
import com.divalhr.core.integrations.partner.PartnerDomain.Status;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Platform-administrator review of partner applications (MVP-120D, ADR 0043). Every operation is
 * authorized before it gets here (platform-admin with exact MFA, never a tenant claim). Reads
 * disclose confidential contact data only after their audit entry commits in the same transaction;
 * audit entries carry the opaque reference, statuses, codes and counts, never contact data, notes,
 * licence references or the service summary. Approval re-reads and locks the whole checklist in the
 * same transaction as the status change, so no concurrent request can bypass the gate. A decision
 * creates no credential, membership, tenant access, integration or listing.
 */
@Service
public class PartnerReviewService {

  /** Audit action of a queue read. */
  public static final String LIST = "partner.application-list";

  /** Audit action of a detail read. */
  public static final String VIEW = "partner.application-view";

  /** Audit action of a history read. */
  public static final String HISTORY = "partner.application-history";

  /** Audit action of a check update. */
  public static final String CHECK_AUDIT = "partner.application-check-update";

  /** Audit action of a transition. */
  public static final String TRANSITION_AUDIT = "partner.application-transition";

  /** Operation name of a check update. */
  public static final String CHECK = "partner.application-check-update";

  /** Operation name of a transition. */
  public static final String TRANSITION = "partner.application-transition";

  /** Default and largest queue page. */
  static final int DEFAULT_LIMIT = 25;

  static final int MAX_LIMIT = 50;

  private static final Pattern REFERENCE = Pattern.compile("^PA-[0-9A-HJKMNP-TV-Z]{12}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,3}$");
  private static final int NOTE_MAX = 500;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);
  private static final String ROLE = "platform-admin";
  private static final Set<Reason> DECLINE_REASONS =
      EnumSet.of(
          Reason.OUT_OF_SCOPE,
          Reason.INCOMPLETE_INFORMATION,
          Reason.REGULATORY_CONCERN,
          Reason.DUE_DILIGENCE_CONCERN,
          Reason.NOT_PURSUING,
          Reason.OTHER);
  private static final Set<Reason> INFORMATION_REASONS =
      EnumSet.of(
          Reason.CLARIFY_REGULATORY_INFORMATION,
          Reason.CLARIFY_SERVICE_SCOPE,
          Reason.CLARIFY_TECHNICAL_READINESS,
          Reason.OTHER);

  /**
   * The verified caller.
   *
   * @param subject verified JWT subject
   * @param correlationId request correlation id
   */
  public record Caller(String subject, String correlationId) {}

  /** Queue filters as received (all optional). */
  public record Query(
      String cursor,
      String limit,
      String status,
      String category,
      String capability,
      String country,
      String submittedFrom,
      String submittedTo) {}

  private final PartnerApplicationRepository repository;
  private final PartnerCrypto crypto;
  private final AuditRecorder audit;
  private final CursorCodec cursors;
  private final IdempotentOperation operations;
  private final TransactionTemplate transactions;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param repository storage
   * @param crypto encryption and digests
   * @param audit audit recorder
   * @param cursors signed cursors
   * @param operations idempotent command flow
   * @param transactions transaction template
   * @param json JSON mapper
   * @param clock clock
   */
  public PartnerReviewService(
      PartnerApplicationRepository repository,
      PartnerCrypto crypto,
      AuditRecorder audit,
      CursorCodec cursors,
      IdempotentOperation operations,
      TransactionTemplate transactions,
      JsonMapper json,
      Clock clock) {
    this.repository = repository;
    this.crypto = crypto;
    this.audit = audit;
    this.cursors = cursors;
    this.operations = operations;
    this.transactions = transactions;
    this.json = json;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ reads

  /**
   * A page of the review queue.
   *
   * @param caller verified administrator
   * @param query raw filters
   * @return the page, after its disclosure audit committed
   */
  public Page list(Caller caller, Query query) {
    Filter filter = filter(query);
    int size = limit(query.limit());
    CursorScope scope = listScope(caller, query, size);
    return transactions.execute(
        status -> {
          Instant afterAt = null;
          UUID afterId = null;
          if (query.cursor() != null) {
            KeysetPosition position = cursors.decode(query.cursor(), scope);
            Row at =
                repository
                    .findById(position.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
            afterAt = at.submittedAt();
            afterId = at.id();
          }
          List<Row> rows = repository.page(filter, afterAt, afterId, size + 1);
          List<Row> shown = rows.subList(0, Math.min(rows.size(), size));
          String next = null;
          if (rows.size() > size) {
            Row last = shown.get(shown.size() - 1);
            next = cursors.encode(scope, new KeysetPosition("PA", last.id()));
          }
          List<UUID> ids = shown.stream().map(Row::id).toList();
          Map<UUID, List<String>> additional = repository.additionalCategories(ids);
          Map<UUID, List<String>> capabilities = repository.capabilities(ids);
          List<Summary> items = new ArrayList<>();
          for (Row row : shown) {
            items.add(
                new Summary(
                    row.reference(),
                    row.status(),
                    row.version(),
                    row.primaryCategory(),
                    additional.getOrDefault(row.id(), List.of()),
                    capabilities.getOrDefault(row.id(), List.of()),
                    row.legalName(),
                    row.countryOfRegistration(),
                    row.submittedAt(),
                    row.updatedAt()));
          }
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("view", "list");
          metadata.put("page", query.cursor() == null ? "first" : "cursor");
          metadata.put("resultCount", items.size());
          disclose(caller, LIST, PLATFORM_SCOPE, metadata);
          return new Page(items, next, repository.statusCounts(filter));
        });
  }

  /**
   * One application in detail.
   *
   * @param caller verified administrator
   * @param reference raw path reference
   * @return the detail, after its disclosure audit committed
   */
  public Detail get(Caller caller, String reference) {
    String ref = reference(reference);
    return transactions.execute(
        status -> {
          Row row = find(ref);
          List<CheckRow> stored = repository.checks(row.id());
          List<Check> checklist = checklist(caller, row, stored);
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("view", "detail");
          metadata.put("reference", row.reference());
          metadata.put("status", row.status());
          disclose(caller, VIEW, row.id(), metadata);
          Status current = Status.valueOf(row.status());
          List<String> blocked = blocking(current, stored);
          return new Detail(
              row.reference(),
              row.status(),
              row.version(),
              row.primaryCategory(),
              repository.additionalCategories(List.of(row.id())).getOrDefault(row.id(), List.of()),
              repository.capabilities(List.of(row.id())).getOrDefault(row.id(), List.of()),
              row.legalName(),
              row.tradingName(),
              row.countryOfRegistration(),
              row.countriesOfOperation(),
              row.website(),
              row.contactRole(),
              row.languages(),
              row.apiAvailability(),
              row.regulatorName(),
              row.serviceSummary(),
              row.submissionLocale(),
              row.submittedAt(),
              row.updatedAt(),
              contact(row),
              consent(row),
              checklist,
              current.next().stream().map(Status::name).toList(),
              blocked,
              repository.possibleDuplicates(row.id(), row.organizationFingerprint()));
        });
  }

  /**
   * The immutable history of an application.
   *
   * @param caller verified administrator
   * @param reference raw path reference
   * @param cursor opaque cursor or {@code null}
   * @param limit raw page size or {@code null}
   * @return a page of history
   */
  public HistoryPage history(Caller caller, String reference, String cursor, String limit) {
    String ref = reference(reference);
    int size = limit(limit);
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "history");
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("reference", ref);
    filters.put("limit", Integer.toString(size));
    CursorScope scope = new CursorScope(HISTORY, new TenantId(PLATFORM_SCOPE), filters);
    return transactions.execute(
        status -> {
          Row row = find(ref);
          int after = 0;
          if (cursor != null) {
            KeysetPosition position = cursors.decode(cursor, scope);
            after = (int) position.id().getLeastSignificantBits();
          }
          List<EventRow> rows = repository.events(row.id(), after, size + 1);
          List<EventRow> shown = rows.subList(0, Math.min(rows.size(), size));
          String next = null;
          if (rows.size() > size) {
            next =
                cursors.encode(
                    scope,
                    new KeysetPosition(
                        "SEQ", new UUID(0L, shown.get(shown.size() - 1).sequence())));
          }
          List<HistoryEntry> items = new ArrayList<>();
          for (EventRow event : shown) {
            items.add(
                new HistoryEntry(
                    event.sequence(),
                    event.eventType(),
                    event.fromStatus(),
                    event.toStatus(),
                    event.checkCode(),
                    event.checkResult(),
                    event.reasonCode(),
                    event.actorType(),
                    "APPLICANT".equals(event.actorType())
                        ? null
                        : caller.subject().equals(event.actorSubject()),
                    event.occurredAt()));
          }
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("view", "history");
          metadata.put("reference", row.reference());
          metadata.put("resultCount", items.size());
          disclose(caller, HISTORY, row.id(), metadata);
          return new HistoryPage(items, next);
        });
  }

  // --------------------------------------------------------------- commands

  /**
   * Records one due-diligence check result.
   *
   * @param caller verified administrator
   * @param reference raw path reference
   * @param checkCode raw path check code
   * @param idempotencyKey the {@code Idempotency-Key} header
   * @param body the parsed body
   * @return the updated check, or the replay of an identical request
   */
  public IdempotentOperation.Result<Check> updateCheck(
      Caller caller, String reference, String checkCode, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(CHECK, "partner_application_check", "update", "updated", 200);
    record Parsed(String reference, CheckCode code, CheckCommand command) {}
    Parsed parsed =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              return new Parsed(reference(reference), check(checkCode), CheckCommand.parse(body));
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("reference", parsed.reference());
    canonical.put("check", parsed.code().name());
    canonical.put("result", parsed.command().result().name());
    canonical.put("expectedVersion", parsed.command().expectedVersion());
    canonical.put(
        "note",
        parsed.command().note() == null ? "" : crypto.digest("note", parsed.command().note()));
    IdempotentOperation.Result<Check> result =
        operations.execute(
            spec,
            caller.subject(),
            idempotencyKey,
            canonical,
            Check.class,
            TIMEOUT,
            () -> runCheck(caller, parsed.reference(), parsed.code(), parsed.command()));
    // The stored idempotent response never carries the note; it is attached from the encrypted
    // column afterwards, and only while it is still the version the response describes.
    return new IdempotentOperation.Result<>(
        withNote(caller, parsed.reference(), parsed.code(), result.body()), result.replayed());
  }

  private Check withNote(Caller caller, String reference, CheckCode code, Check stored) {
    return transactions.execute(
        status -> {
          Row row = find(reference);
          CheckRow current = repository.check(row.id(), code.name()).orElse(null);
          if (current == null || current.version() != stored.version()) {
            return stored;
          }
          return check(caller, row, current, true);
        });
  }

  private IdempotentOperation.Completed<Check> runCheck(
      Caller caller, String reference, CheckCode code, CheckCommand command) {
    Instant at = now();
    Row row = repository.lockByReference(reference).orElseThrow(PartnerReviewService::notFound);
    Status status = Status.valueOf(row.status());
    if (status.terminal()) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TERMINAL, Map.of());
    }
    CheckRow current =
        repository.check(row.id(), code.name()).orElseThrow(PartnerReviewService::notFound);
    if (current.version() != command.expectedVersion()) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    }
    if (code == CheckCode.REGULATORY_INFORMATION
        && command.result() == CheckResult.NOT_APPLICABLE
        && PartnerDomain.parse(PartnerDomain.Category.class, row.primaryCategory()).regulated()) {
      throw invalid("result", "FORMAT");
    }
    byte[] note =
        command.note() == null
            ? null
            : crypto.seal(
                command.note().getBytes(StandardCharsets.UTF_8), noteContext(row.id(), code));
    String actor = command.result() == CheckResult.PENDING ? null : caller.subject();
    int changed;
    try {
      changed =
          repository.updateCheck(
              row.id(), code.name(), current.version(), command.result().name(), actor, note, at);
    } catch (DataIntegrityViolationException violated) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    }
    if (changed != 1) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    }
    repository.appendEvent(
        row.id(),
        EventType.CHECKLIST_UPDATED,
        row.status(),
        row.status(),
        code.name(),
        command.result().name(),
        null,
        "PLATFORM_ADMIN",
        caller.subject(),
        at);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", row.reference());
    metadata.put("checkCode", code.name());
    metadata.put("result", command.result().name());
    audited(caller, CHECK_AUDIT, row.id(), metadata, at);
    CheckRow after = repository.check(row.id(), code.name()).orElseThrow();
    return new IdempotentOperation.Completed<>(
        check(caller, row, after, false), row.id(), Outcome.UPDATED);
  }

  /**
   * Moves an application to its next status.
   *
   * @param caller verified administrator
   * @param reference raw path reference
   * @param idempotencyKey the {@code Idempotency-Key} header
   * @param body the parsed body
   * @return the receipt, or the replay of an identical request
   */
  public IdempotentOperation.Result<TransitionReceipt> transition(
      Caller caller, String reference, String idempotencyKey, JsonNode body) {
    IdempotentOperation.Spec spec =
        new IdempotentOperation.Spec(
            TRANSITION, "partner_application", "transition", "transitioned", 200);
    record Parsed(String reference, TransitionCommand command) {}
    Parsed parsed =
        operations.validated(
            spec,
            () -> {
              key(idempotencyKey);
              return new Parsed(reference(reference), TransitionCommand.parse(body));
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("reference", parsed.reference());
    canonical.put("toStatus", parsed.command().to().name());
    canonical.put("expectedVersion", parsed.command().expectedVersion());
    canonical.put(
        "reason", parsed.command().reason() == null ? "" : parsed.command().reason().name());
    return operations.execute(
        spec,
        caller.subject(),
        idempotencyKey,
        canonical,
        TransitionReceipt.class,
        TIMEOUT,
        () -> runTransition(caller, parsed.reference(), parsed.command()));
  }

  private IdempotentOperation.Completed<TransitionReceipt> runTransition(
      Caller caller, String reference, TransitionCommand command) {
    Instant at = now();
    Row row = repository.lockByReference(reference).orElseThrow(PartnerReviewService::notFound);
    Status from = Status.valueOf(row.status());
    if (from.terminal()) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TERMINAL, Map.of());
    }
    if (row.version() != command.expectedVersion()
        || command.to() == from
        || !from.next().contains(command.to())) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    }
    if (command.to() == Status.APPROVED_FOR_ONBOARDING) {
      // The whole checklist is locked and re-read inside this transaction: a concurrent check
      // update waits on the application lock taken above, and the database trigger re-checks.
      List<String> blocked = blocking(from, repository.lockChecks(row.id()));
      if (!blocked.isEmpty()) {
        throw new ApiException(
            ErrorCode.PARTNER_APPLICATION_CHECKLIST_INCOMPLETE, Map.of("checks", blocked));
      }
    }
    int changed;
    try {
      changed = repository.updateStatus(row.id(), row.version(), command.to().name(), at);
    } catch (DataIntegrityViolationException violated) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    }
    if (changed != 1) {
      throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    }
    int sequence =
        repository.appendEvent(
            row.id(),
            eventType(command.to()),
            from.name(),
            command.to().name(),
            null,
            null,
            command.reason() == null ? null : command.reason().name(),
            "PLATFORM_ADMIN",
            caller.subject(),
            at);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reference", row.reference());
    metadata.put("fromStatus", from.name());
    metadata.put("toStatus", command.to().name());
    if (command.reason() != null) {
      metadata.put("reasonCode", command.reason().name());
    }
    audited(caller, TRANSITION_AUDIT, row.id(), metadata, at);
    return new IdempotentOperation.Completed<>(
        new TransitionReceipt(
            row.reference(), command.to().name(), row.version() + 1, sequence, at),
        row.id(),
        Outcome.UPDATED);
  }

  // ---------------------------------------------------------------- helpers

  private static EventType eventType(Status to) {
    return switch (to) {
      case UNDER_REVIEW -> EventType.REVIEW_STARTED;
      case MORE_INFORMATION_REQUIRED -> EventType.INFORMATION_REQUESTED;
      case APPROVED_FOR_ONBOARDING -> EventType.APPROVED_FOR_ONBOARDING;
      case DECLINED -> EventType.DECLINED;
      case WITHDRAWN -> EventType.WITHDRAWAL_RECORDED;
      default -> throw new ApiException(ErrorCode.PARTNER_APPLICATION_TRANSITION_INVALID, Map.of());
    };
  }

  /** Mandatory checks that currently block approval (none once the application is final). */
  private static List<String> blocking(Status status, List<CheckRow> checks) {
    if (status.terminal()) {
      return List.of();
    }
    List<String> blocked = new ArrayList<>();
    for (CheckCode code : CheckCode.values()) {
      if (!code.mandatory()) {
        continue;
      }
      CheckRow row =
          checks.stream().filter(c -> c.code().equals(code.name())).findFirst().orElse(null);
      CheckResult result = row == null ? CheckResult.PENDING : CheckResult.valueOf(row.result());
      if (!result.clears()) {
        blocked.add(code.name());
      }
    }
    return blocked;
  }

  private List<Check> checklist(Caller caller, Row row, List<CheckRow> stored) {
    List<Check> checks = new ArrayList<>();
    for (CheckCode code : CheckCode.values()) {
      stored.stream()
          .filter(c -> c.code().equals(code.name()))
          .findFirst()
          .ifPresent(c -> checks.add(check(caller, row, c, true)));
    }
    return checks;
  }

  private Check check(Caller caller, Row row, CheckRow stored, boolean includeNote) {
    CheckCode code = CheckCode.valueOf(stored.code());
    String note =
        stored.note() == null || !includeNote
            ? null
            : new String(
                crypto.open(stored.note(), noteContext(row.id(), code)), StandardCharsets.UTF_8);
    return new Check(
        stored.code(),
        code.mandatory(),
        stored.result(),
        stored.version(),
        stored.actor() == null ? null : caller.subject().equals(stored.actor()),
        stored.updatedAt(),
        note);
  }

  private Contact contact(Row row) {
    JsonNode node =
        json.readTree(
            crypto.open(row.confidential(), PartnerSubmissionService.contactContext(row.id())));
    return new Contact(
        text(node, "contactName"),
        text(node, "businessEmail"),
        text(node, "businessPhone"),
        text(node, "registrationReference"),
        text(node, "licenceReference"),
        text(node, "privacyContactEmail"));
  }

  private Consent consent(Row row) {
    PartnerApplicationRepository.ConsentRow stored = repository.consent(row.id());
    return new Consent(
        stored.privacyNoticeVersion(),
        stored.consentVersion(),
        stored.locale(),
        stored.acceptedAt());
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String noteContext(UUID id, CheckCode code) {
    return "divalhr-partner-application\nnote\napplication=" + id + "\ncheck=" + code.name();
  }

  private Row find(String reference) {
    return repository.findByReference(reference).orElseThrow(PartnerReviewService::notFound);
  }

  private void disclose(Caller caller, String action, UUID resource, Map<String, Object> metadata) {
    audited(caller, action, resource, metadata, now());
  }

  private void audited(
      Caller caller, String action, UUID resource, Map<String, Object> metadata, Instant at) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            caller.subject(),
            action,
            "partner-application",
            resource,
            PLATFORM_SCOPE,
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-PARTNER-APPLICATION\nversion=1\naction="
                    + action
                    + "\nresource="
                    + resource
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.PARTNER_APPLICATION_NOT_FOUND, Map.of());
  }

  private static ApiException invalid(String field, String constraint) {
    return new ApiException(
        ErrorCode.PARTNER_APPLICATION_INVALID,
        Map.of("fields", List.of(Map.of("field", field, "constraint", constraint))));
  }

  private static String reference(String raw) {
    if (raw == null || !REFERENCE.matcher(raw).matches()) {
      throw notFound();
    }
    return raw;
  }

  private static CheckCode check(String raw) {
    CheckCode code = PartnerDomain.parse(CheckCode.class, raw);
    if (code == null) {
      throw notFound();
    }
    return code;
  }

  private static void key(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw invalid(IdempotencyKeys.HEADER, "REQUIRED");
    }
    if (!IdempotencyKeys.isWellFormed(idempotencyKey)) {
      throw invalid(IdempotencyKeys.HEADER, "FORMAT");
    }
  }

  private static int limit(String raw) {
    if (raw == null || raw.isEmpty()) {
      return DEFAULT_LIMIT;
    }
    if (!DIGITS.matcher(raw).matches()) {
      throw invalid("limit", "FORMAT");
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > MAX_LIMIT) {
      throw invalid("limit", "RANGE");
    }
    return value;
  }

  private static Filter filter(Query q) {
    String status = null;
    if (q.status() != null) {
      status = PartnerDomain.parse(Status.class, q.status()) == null ? bad("status") : q.status();
    }
    String category = null;
    if (q.category() != null) {
      category =
          PartnerDomain.parse(PartnerDomain.Category.class, q.category()) == null
              ? bad("category")
              : q.category();
    }
    String capability = null;
    if (q.capability() != null) {
      capability =
          PartnerDomain.parse(PartnerDomain.Capability.class, q.capability()) == null
              ? bad("capability")
              : q.capability();
    }
    String country = null;
    if (q.country() != null) {
      country = COUNTRY.matcher(q.country()).matches() ? q.country() : bad("country");
    }
    Instant from = date(q.submittedFrom(), "submittedFrom", 0);
    Instant before = date(q.submittedTo(), "submittedTo", 1);
    if (from != null && before != null && !from.isBefore(before)) {
      bad("submittedTo");
    }
    return new Filter(status, category, capability, country, from, before);
  }

  private static String bad(String field) {
    throw invalid(field, "FORMAT");
  }

  private static Instant date(String raw, String field, int plusDays) {
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(raw).plusDays(plusDays).atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException malformed) {
      throw invalid(field, "FORMAT");
    }
  }

  private static CursorScope listScope(Caller caller, Query q, int size) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("view", "list");
    filters.put("role", ROLE);
    filters.put("subject", caller.subject());
    filters.put("limit", Integer.toString(size));
    put(filters, "status", q.status());
    put(filters, "category", q.category());
    put(filters, "capability", q.capability());
    put(filters, "country", q.country());
    put(filters, "from", q.submittedFrom());
    put(filters, "to", q.submittedTo());
    return new CursorScope(LIST, new TenantId(PLATFORM_SCOPE), filters);
  }

  private static void put(Map<String, String> filters, String name, String value) {
    if (value != null) {
      filters.put(name, value);
    }
  }

  /** A validated check command. */
  record CheckCommand(CheckResult result, int expectedVersion, String note) {

    static CheckCommand parse(JsonNode body) {
      requireOnly(body, Set.of("result", "expectedVersion", "note"));
      CheckResult result = enumOf(body, "result", CheckResult.class);
      int version = version(body);
      String note = null;
      JsonNode node = body.get("note");
      if (node != null && !node.isNull()) {
        if (!node.isString()) {
          throw invalid("note", "FORMAT");
        }
        String text = node.asString().strip();
        if (text.length() > NOTE_MAX) {
          throw invalid("note", "LENGTH");
        }
        if (text.chars()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
          throw invalid("note", "FORMAT");
        }
        note = text.isEmpty() ? null : text;
      }
      return new CheckCommand(result, version, note);
    }
  }

  /** A validated transition command. */
  record TransitionCommand(Status to, int expectedVersion, Reason reason, boolean confirmed) {

    static TransitionCommand parse(JsonNode body) {
      requireOnly(body, Set.of("toStatus", "expectedVersion", "reasonCode", "confirmed"));
      Status to = enumOf(body, "toStatus", Status.class);
      int version = version(body);
      Reason reason = null;
      JsonNode node = body.get("reasonCode");
      if (node != null && !node.isNull()) {
        reason = node.isString() ? PartnerDomain.parse(Reason.class, node.asString()) : null;
        if (reason == null) {
          throw invalid("reasonCode", "FORMAT");
        }
      }
      JsonNode confirmedNode = body.get("confirmed");
      if (confirmedNode != null && !confirmedNode.isNull() && !confirmedNode.isBoolean()) {
        throw invalid("confirmed", "FORMAT");
      }
      boolean confirmed =
          confirmedNode != null && confirmedNode.isBoolean() && confirmedNode.booleanValue();
      switch (to) {
        case UNDER_REVIEW, SUBMITTED -> {
          if (reason != null) {
            throw invalid("reasonCode", "FORMAT");
          }
        }
        case MORE_INFORMATION_REQUIRED -> {
          if (reason == null || !INFORMATION_REASONS.contains(reason)) {
            throw invalid("reasonCode", reason == null ? "REQUIRED" : "FORMAT");
          }
        }
        case APPROVED_FOR_ONBOARDING -> {
          if (reason != null) {
            throw invalid("reasonCode", "FORMAT");
          }
          requireConfirmed(confirmed);
        }
        case DECLINED -> {
          if (reason == null || !DECLINE_REASONS.contains(reason)) {
            throw invalid("reasonCode", reason == null ? "REQUIRED" : "FORMAT");
          }
          requireConfirmed(confirmed);
        }
        case WITHDRAWN -> {
          if (reason != Reason.APPLICANT_REQUEST) {
            throw invalid("reasonCode", reason == null ? "REQUIRED" : "FORMAT");
          }
          requireConfirmed(confirmed);
        }
        default -> throw invalid("toStatus", "FORMAT");
      }
      return new TransitionCommand(to, version, reason, confirmed);
    }

    private static void requireConfirmed(boolean confirmed) {
      if (!confirmed) {
        throw invalid("confirmed", "REQUIRED");
      }
    }
  }

  private static void requireOnly(JsonNode body, Set<String> allowed) {
    for (String name : body.propertyNames()) {
      if (!allowed.contains(name)) {
        throw invalid("body", "UNKNOWN_PROPERTY");
      }
    }
  }

  private static <E extends Enum<E>> E enumOf(JsonNode body, String name, Class<E> type) {
    JsonNode node = body.get(name);
    if (node == null || node.isNull()) {
      throw invalid(name, "REQUIRED");
    }
    E value = node.isString() ? PartnerDomain.parse(type, node.asString()) : null;
    if (value == null) {
      throw invalid(name, "FORMAT");
    }
    return value;
  }

  private static int version(JsonNode body) {
    JsonNode node = body.get("expectedVersion");
    if (node == null || node.isNull()) {
      throw invalid("expectedVersion", "REQUIRED");
    }
    if (!node.isInt() || node.intValue() < 0) {
      throw invalid("expectedVersion", "RANGE");
    }
    return node.intValue();
  }
}
