package com.divalhr.core.people.application;

import com.divalhr.core.people.api.EmployeeSearchRequest;
import com.divalhr.core.people.api.EmploymentHistoryResponses.AssignmentPage;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmployeePage;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmployeeProfile;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmployeeSummary;
import com.divalhr.core.people.api.EmploymentHistoryResponses.Employment;
import com.divalhr.core.people.api.EmploymentHistoryResponses.EmploymentChangePage;
import com.divalhr.core.people.domain.EmployeeSearchKey;
import com.divalhr.core.people.domain.history.Assignment;
import com.divalhr.core.people.domain.history.AssignmentKind;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.ChangeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmployeeRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.EmploymentRecord;
import com.divalhr.core.people.internal.JdbcEmploymentHistoryRepository.TimelineKey;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.TenantId;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-021: the employee directory, search, profile, timeline and change history (H13-H16, M21-1).
 *
 * <p>Every read is a disclosure of Confidential or Restricted HR data: the query and its audit
 * record run in one transaction and the body is returned only after both committed (fail-closed;
 * the pattern of the MVP-019 access review). Disclosure metadata is only {@code {view, page,
 * resultCount, schemaVersion}}; search text, filters and result IDs are never recorded, logged or
 * echoed. Cursors are bound to the operation, tenant, filters and page size.
 */
@Service
public class EmployeeDirectoryService {

  /** Directory operation. */
  public static final String LIST = "employee.list";

  /** Search operation. */
  public static final String SEARCH = "employee.search";

  /** Profile operation. */
  public static final String READ = "employee.read";

  /** Timeline operation. */
  public static final String TIMELINE = "employee.timeline";

  /** Change history operation. */
  public static final String CHANGES = "employment-change.list";

  /** Per-subject bucket of reads, searches and previews. */
  public static final String SUBJECT_READ_BUCKET = "employee-read";

  /** Per-tenant bucket of searches. */
  public static final String TENANT_SEARCH_BUCKET = "employee-search";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.employee-directory");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  static final Pattern EMPLOYEE_NUMBER = Pattern.compile("^[A-Z0-9][A-Z0-9._/-]{0,31}$");
  private static final Pattern TIMELINE_CODE = Pattern.compile("^([1-4])-([0-9]{8})$");
  private static final Pattern MICROS = Pattern.compile("^[0-9]{16}$");
  private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
  private static final String EMPLOYEE_CODE = "EM";

  private final JdbcEmploymentHistoryRepository history;
  private final EmploymentHistoryViews views;
  private final EmploymentHistoryEvents events;
  private final BusinessCalendar calendar;
  private final CursorCodec cursors;
  private final OperationMetrics metrics;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param history history repository
   * @param views response builder
   * @param events audit
   * @param calendar business date
   * @param cursors cursor codec
   * @param metrics operation metrics
   * @param properties settings
   * @param transactionManager transaction manager
   */
  @Autowired
  public EmployeeDirectoryService(
      JdbcEmploymentHistoryRepository history,
      EmploymentHistoryViews views,
      EmploymentHistoryEvents events,
      BusinessCalendar calendar,
      CursorCodec cursors,
      OperationMetrics metrics,
      EmploymentHistoryProperties properties,
      PlatformTransactionManager transactionManager) {
    this(
        history,
        views,
        events,
        calendar,
        cursors,
        metrics,
        properties,
        transactionManager,
        Clock.systemUTC());
  }

  EmployeeDirectoryService(
      JdbcEmploymentHistoryRepository history,
      EmploymentHistoryViews views,
      EmploymentHistoryEvents events,
      BusinessCalendar calendar,
      CursorCodec cursors,
      OperationMetrics metrics,
      EmploymentHistoryProperties properties,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.history = history;
    this.views = views;
    this.events = events;
    this.calendar = calendar;
    this.cursors = cursors;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setTimeout((int) Math.max(1, properties.transactionTimeout().toSeconds()));
    this.clock = clock;
  }

  // ------------------------------------------------------------------------------------------
  // Directory and search
  // ------------------------------------------------------------------------------------------

  /**
   * A directory page, by employee number.
   *
   * @param caller verified caller
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return a page
   */
  public EmployeePage list(PeopleCaller caller, String cursor, String limit) {
    int pageSize = validated(LIST, () -> limit(limit, 50, 25));
    CursorScope scope =
        new CursorScope(LIST, caller.tenant(), Map.of("limit", Integer.toString(pageSize)));
    EmployeeRecord after = validated(LIST, () -> employeeAfter(caller.tenant(), cursor, scope));
    return directory(caller, LIST, "directory", scope, null, List.of(), after, pageSize);
  }

  /**
   * Searches employees by employee-number prefix or name words (accent- and case-insensitive).
   *
   * @param caller verified caller
   * @param request body
   * @return a page
   */
  public EmployeePage search(PeopleCaller caller, EmployeeSearchRequest request) {
    Search search = validated(SEARCH, () -> search(request));
    CursorScope scope =
        new CursorScope(
            SEARCH,
            caller.tenant(),
            Map.of(
                "query",
                Fingerprints.sha256(search.query()),
                "limit",
                Integer.toString(search.limit())));
    EmployeeRecord after =
        validated(SEARCH, () -> employeeAfter(caller.tenant(), search.cursor(), scope));
    String upper = search.query().toUpperCase(Locale.ROOT);
    String numberPrefix = EMPLOYEE_NUMBER.matcher(upper).matches() ? upper : null;
    List<String> words = EmployeeSearchKey.words(search.query());
    return directory(caller, SEARCH, "search", scope, numberPrefix, words, after, search.limit());
  }

  /** A validated search; the query is never logged ({@code toString} hides it). */
  private record Search(String query, String cursor, int limit) {
    @Override
    public String toString() {
      return "Search[limit=" + limit + "]";
    }
  }

  private static Search search(EmployeeSearchRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED).throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    String query = null;
    if (request.getQuery() == null) {
      errors.add("query", Constraint.REQUIRED);
    } else if (!(request.getQuery() instanceof String text)) {
      errors.add("query", Constraint.FORMAT);
    } else {
      String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
      int length = normalized.codePointCount(0, normalized.length());
      if (length < 2 || length > 100) {
        errors.add("query", Constraint.LENGTH);
      } else {
        query = normalized;
      }
    }
    String cursor = null;
    if (request.getCursor() != null) {
      if (!(request.getCursor() instanceof String text)) {
        errors.add("cursor", Constraint.FORMAT);
      } else {
        cursor = text;
      }
    }
    int limit = 25;
    if (request.getLimit() != null) {
      if (!(request.getLimit() instanceof Integer value)) {
        errors.add("limit", Constraint.FORMAT);
      } else if (value < 1 || value > 50) {
        errors.add("limit", Constraint.RANGE);
      } else {
        limit = value;
      }
    }
    errors.throwIfAny();
    return new Search(query, cursor, limit);
  }

  private EmployeeRecord employeeAfter(TenantId tenant, String cursor, CursorScope scope) {
    if (cursor == null) {
      return null;
    }
    KeysetPosition position = cursors.decode(cursor, scope);
    if (!EMPLOYEE_CODE.equals(position.code())) {
      throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
    }
    return history
        .employee(tenant, position.id())
        .orElseThrow(() -> new ApiException(ErrorCode.CURSOR_INVALID, Map.of()));
  }

  private EmployeePage directory(
      PeopleCaller caller,
      String operation,
      String view,
      CursorScope scope,
      String numberPrefix,
      List<String> words,
      EmployeeRecord after,
      int pageSize) {
    TenantId tenant = caller.tenant();
    boolean searchable = !SEARCH.equals(operation) || numberPrefix != null || !words.isEmpty();
    return disclose(
        caller,
        operation,
        view,
        after == null ? "first" : "next",
        () -> {
          List<EmployeeRecord> found =
              searchable
                  ? history.employeePage(
                      tenant,
                      numberPrefix,
                      words,
                      after == null ? null : after.employeeNumber(),
                      after == null ? null : after.id(),
                      pageSize + 1)
                  : List.of();
          List<EmployeeRecord> shown = found.subList(0, Math.min(found.size(), pageSize));
          LocalDate today = calendar.today(tenant);
          Map<UUID, List<EmploymentRecord>> employments =
              history.employments(
                  tenant, shown.stream().map(EmployeeRecord::id).collect(Collectors.toSet()));
          String next = null;
          if (found.size() > pageSize) {
            next =
                cursors.encode(
                    scope, new KeysetPosition(EMPLOYEE_CODE, shown.get(shown.size() - 1).id()));
          }
          EmployeePage page =
              new EmployeePage(
                  shown.stream()
                      .map(
                          e ->
                              new EmployeeSummary(
                                  e.id(),
                                  e.employeeNumber(),
                                  e.givenNames(),
                                  e.familyName(),
                                  EmploymentHistoryViews.status(
                                      employments.getOrDefault(e.id(), List.of()), today)))
                      .toList(),
                  next);
          return new Disclosure<>(
              page,
              "organization",
              tenant.value(),
              shown.size(),
              shown.stream().map(EmployeeRecord::id).toList());
        });
  }

  // ------------------------------------------------------------------------------------------
  // One employee
  // ------------------------------------------------------------------------------------------

  /**
   * An employee's profile on the business date.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @return the profile
   */
  public EmployeeProfile read(PeopleCaller caller, String employeeId) {
    UUID id = validated(READ, () -> employeeId(employeeId));
    TenantId tenant = caller.tenant();
    return disclose(
        caller,
        READ,
        "profile",
        "first",
        () -> {
          EmployeeRecord employee =
              history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
          LocalDate today = calendar.today(tenant);
          EmploymentRecord employment =
              history
                  .employment(tenant, id, today)
                  .orElseThrow(() -> new IllegalStateException("employee without employment"));
          List<Assignment> rows = history.assignments(tenant, employment.id());
          EmployeeProfile profile =
              new EmployeeProfile(
                  employee.id(),
                  employee.employeeNumber(),
                  employee.givenNames(),
                  employee.familyName(),
                  today,
                  new Employment(
                      employment.id(),
                      employment.start(),
                      employment.end(),
                      EmploymentHistoryViews.status(employment, today),
                      employment.version()),
                  views.current(tenant, rows, today));
          return new Disclosure<>(profile, "employee", id, 1, List.of(id));
        });
  }

  /**
   * A page of an employee's assignment rows, by kind then start date.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param kind raw kind filter
   * @param includeSuperseded raw flag
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return a page
   */
  public AssignmentPage timeline(
      PeopleCaller caller,
      String employeeId,
      String kind,
      String includeSuperseded,
      String cursor,
      String limit) {
    UUID id = validated(TIMELINE, () -> employeeId(employeeId));
    TenantId tenant = caller.tenant();
    record Query(AssignmentKind kind, boolean superseded, int limit) {}
    Query query =
        validated(
            TIMELINE,
            () -> {
              FieldErrors errors = new FieldErrors();
              AssignmentKind kindFilter = null;
              if (kind != null) {
                kindFilter =
                    Set.of(AssignmentKind.values()).stream()
                        .filter(k -> k.name().equals(kind))
                        .findFirst()
                        .orElse(null);
                if (kindFilter == null) {
                  errors.add("kind", Constraint.FORMAT);
                }
              }
              boolean superseded = false;
              if (includeSuperseded != null) {
                if ("true".equals(includeSuperseded)) {
                  superseded = true;
                } else if (!"false".equals(includeSuperseded)) {
                  errors.add("includeSuperseded", Constraint.FORMAT);
                }
              }
              int pageSize = limit(limit, 100, 50, errors);
              errors.throwIfAny();
              return new Query(kindFilter, superseded, pageSize);
            });
    CursorScope scope =
        new CursorScope(
            TIMELINE,
            tenant,
            Map.of(
                "employee", id.toString(),
                "kind", query.kind() == null ? "-" : query.kind().name(),
                "includeSuperseded", Boolean.toString(query.superseded()),
                "limit", Integer.toString(query.limit())));
    TimelineKey after =
        validated(
            TIMELINE,
            () -> {
              if (cursor == null) {
                return null;
              }
              KeysetPosition position = cursors.decode(cursor, scope);
              Matcher code = TIMELINE_CODE.matcher(position.code());
              if (!code.matches()) {
                throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
              }
              try {
                return new TimelineKey(
                    Integer.parseInt(code.group(1)),
                    LocalDate.parse(code.group(2), BASIC),
                    position.id());
              } catch (java.time.format.DateTimeParseException malformed) {
                throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
              }
            });
    return disclose(
        caller,
        TIMELINE,
        "timeline",
        after == null ? "first" : "next",
        () -> {
          history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
          List<Assignment> found =
              history.timelinePage(
                  tenant, id, query.kind(), query.superseded(), after, query.limit() + 1);
          List<Assignment> shown = found.subList(0, Math.min(found.size(), query.limit()));
          String next = null;
          if (found.size() > query.limit()) {
            Assignment last = shown.get(shown.size() - 1);
            next =
                cursors.encode(
                    scope,
                    new KeysetPosition(
                        (last.kind().ordinal() + 1) + "-" + last.from().format(BASIC), last.id()));
          }
          AssignmentPage page =
              new AssignmentPage(views.assignments(tenant, shown, calendar.today(tenant)), next);
          return new Disclosure<>(
              page, "employee", id, shown.size(), shown.stream().map(Assignment::id).toList());
        });
  }

  /**
   * A page of an employee's recorded changes, newest first.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return a page
   */
  public EmploymentChangePage changes(
      PeopleCaller caller, String employeeId, String cursor, String limit) {
    UUID id = validated(CHANGES, () -> employeeId(employeeId));
    TenantId tenant = caller.tenant();
    int pageSize = validated(CHANGES, () -> limit(limit, 50, 25));
    CursorScope scope =
        new CursorScope(
            CHANGES,
            tenant,
            Map.of("employee", id.toString(), "limit", Integer.toString(pageSize)));
    KeysetPosition after =
        validated(
            CHANGES,
            () -> {
              if (cursor == null) {
                return null;
              }
              KeysetPosition position = cursors.decode(cursor, scope);
              if (!MICROS.matcher(position.code()).matches()) {
                throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
              }
              return position;
            });
    return disclose(
        caller,
        CHANGES,
        "changes",
        after == null ? "first" : "next",
        () -> {
          history.employee(tenant, id).orElseThrow(EmployeeDirectoryService::notFound);
          List<ChangeRecord> found =
              history.changePage(
                  tenant,
                  id,
                  after == null ? null : fromMicros(Long.parseLong(after.code())),
                  after == null ? null : after.id(),
                  pageSize + 1);
          List<ChangeRecord> shown = found.subList(0, Math.min(found.size(), pageSize));
          String next = null;
          if (found.size() > pageSize) {
            ChangeRecord last = shown.get(shown.size() - 1);
            next =
                cursors.encode(
                    scope,
                    new KeysetPosition(
                        String.format(Locale.ROOT, "%016d", toMicros(last.recordedAt())),
                        last.id()));
          }
          EmploymentChangePage page =
              new EmploymentChangePage(
                  shown.stream().map(EmploymentHistoryViews::change).toList(), next);
          return new Disclosure<>(
              page, "employee", id, shown.size(), shown.stream().map(ChangeRecord::id).toList());
        });
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure
  // ------------------------------------------------------------------------------------------

  /**
   * What a read disclosed.
   *
   * @param body response body
   * @param resourceType audit resource type
   * @param resourceId audit resource ID
   * @param resultCount items disclosed
   * @param ids disclosed IDs in response order (hashed only)
   * @param <T> body type
   */
  record Disclosure<T>(
      T body, String resourceType, UUID resourceId, int resultCount, List<UUID> ids) {}

  /**
   * Runs a read and its audit record in one transaction; the body is returned only after both
   * committed. Shared with the previews of {@link EmploymentChangeService}.
   */
  <T> T disclose(
      PeopleCaller caller,
      String operation,
      String view,
      String page,
      Supplier<Disclosure<T>> query) {
    Disclosure<T> disclosure;
    try {
      disclosure =
          transactions.execute(
              status -> {
                Disclosure<T> built = query.get();
                events.disclosed(
                    caller.tenant(),
                    caller.subject(),
                    operation,
                    built.resourceType(),
                    built.resourceId(),
                    view,
                    page,
                    built.resultCount(),
                    EmploymentHistoryDigests.disclosure(view, built.ids()),
                    Instant.now(clock).truncatedTo(ChronoUnit.MICROS),
                    caller.correlationId());
                return built;
              });
    } catch (ApiException rejected) {
      // Nothing was disclosed: the transaction (query and audit) rolled back.
      outcome(operation, view, page, outcomeOf(rejected), 0);
      throw rejected;
    } catch (RuntimeException failure) {
      outcome(operation, view, page, Outcome.FAILURE, 0);
      throw failure;
    }
    if (disclosure == null) {
      throw new IllegalStateException("disclosure transaction returned nothing");
    }
    outcome(operation, view, page, Outcome.LISTED, disclosure.resultCount());
    return disclosure.body();
  }

  <C> C validated(String operation, Supplier<C> validation) {
    try {
      return validation.get();
    } catch (ApiException invalid) {
      metrics.record(operation, outcomeOf(invalid));
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "rejected")
          .addKeyValue("code", invalid.code().name())
          .log("employee_request_rejected");
      throw invalid;
    }
  }

  private void outcome(String operation, String view, String page, Outcome outcome, int count) {
    metrics.record(operation, outcome);
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("view", view)
        .addKeyValue("page", page)
        .addKeyValue("resultCount", count)
        .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
        .log("employee_disclosure");
  }

  private static Outcome outcomeOf(ApiException exception) {
    return switch (exception.code().status().value()) {
      case 400 -> Outcome.VALIDATION_FAILED;
      case 404 -> Outcome.NOT_FOUND;
      default -> Outcome.FAILURE;
    };
  }

  // ------------------------------------------------------------------------------------------
  // Shared parsing
  // ------------------------------------------------------------------------------------------

  /**
   * Parses an employee ID; malformed IDs are indistinguishable from unknown ones.
   *
   * @param raw raw path value
   * @return the ID
   */
  static UUID employeeId(String raw) {
    if (raw == null || !UUID_TEXT.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw);
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, Map.of());
  }

  private static int limit(String raw, int max, int fallback) {
    FieldErrors errors = new FieldErrors();
    int value = limit(raw, max, fallback, errors);
    errors.throwIfAny();
    return value;
  }

  private static int limit(String raw, int max, int fallback, FieldErrors errors) {
    if (raw == null) {
      return fallback;
    }
    if (!LIMIT.matcher(raw).matches()) {
      errors.add("limit", Constraint.FORMAT);
      return fallback;
    }
    int value = Integer.parseInt(raw);
    if (value < 1 || value > max) {
      errors.add("limit", Constraint.RANGE);
      return fallback;
    }
    return value;
  }

  static long toMicros(Instant instant) {
    Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
    return Math.addExact(
        Math.multiplyExact(micros.getEpochSecond(), 1_000_000L), micros.getNano() / 1_000L);
  }

  static Instant fromMicros(long micros) {
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
  }
}
