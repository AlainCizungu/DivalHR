package com.divalhr.core.documents.application;

import com.divalhr.core.documents.api.ContractExpirationResponses.Counts;
import com.divalhr.core.documents.api.ContractExpirationResponses.Item;
import com.divalhr.core.documents.api.ContractExpirationResponses.Page;
import com.divalhr.core.documents.api.ContractExpirationResponses.Summary;
import com.divalhr.core.documents.api.ContractExpirationResponses.Unit;
import com.divalhr.core.documents.api.ContractExpirationSearchRequest;
import com.divalhr.core.documents.domain.ExpirationCategory;
import com.divalhr.core.documents.internal.JdbcContractExpirationRepository;
import com.divalhr.core.documents.internal.JdbcContractExpirationRepository.Head;
import com.divalhr.core.platform.access.EmploymentExpirationScope;
import com.divalhr.core.platform.access.EmploymentExpirationScope.EmployeeLabel;
import com.divalhr.core.platform.access.EmploymentExpirationScope.Filter;
import com.divalhr.core.platform.access.EmploymentExpirationScope.RelevantEmployment;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.OrganizationDirectory.OrganizationSummary;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.UnitView;
import com.divalhr.core.platform.tenancy.TenantId;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The contract expiration queue (MVP-031A, Issue #73): which contracts have expired or end within
 * 90 days, for tenant administrators. Two reads share one eligibility definition (the coverage
 * head, A31A-1, in {@link JdbcContractExpirationRepository}) and one business date: today in the
 * organization's IANA time zone from the server clock, never the browser's.
 *
 * <p>Every request runs in one {@code REPEATABLE READ} transaction (the people port, the counts and
 * the page read the same snapshot) together with its fail-closed disclosure audit. Across pages
 * (A31A-4): the signed cursor pins the business date and the filters, and the immutable keys {@code
 * (end_date, contract id)} never return a row twice; eligibility itself is re-evaluated on every
 * request, so a contract or separation changed between pages can add or remove rows that sort after
 * the cursor. The result set is not frozen across requests.
 */
@Service
public class ContractExpirationService {

  /** Queue operation. */
  public static final String SEARCH = "contract-expiration.search";

  /** Summary operation. */
  public static final String SUMMARY = "contract-expiration.summary";

  /** Audit action of both reads (disclosures). */
  public static final String READ = "contract-expiration.read";

  /** Per-subject request limit of both reads. */
  public static final String SUBJECT_BUCKET = "contract-expiration";

  /** Default and maximum page sizes (as the other contract and employee lists). */
  static final int DEFAULT_LIMIT = 25;

  static final int MAX_LIMIT = 50;

  /** Cursor row code: {@code E} and the pinned business date ({@code yyyyMMdd}). */
  private static final Pattern CURSOR_CODE = Pattern.compile("^E([0-9]{8})$");

  private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final ContractOperations flow;
  private final ContractCalendar calendar;
  private final ContractEvents events;
  private final JdbcContractExpirationRepository expirations;
  private final EmploymentExpirationScope employments;
  private final OrganizationPlacementDirectory units;
  private final CursorCodec cursors;

  /**
   * Creates the service.
   *
   * @param flow shared operation flow
   * @param calendar business date
   * @param events audit
   * @param expirations expiration queries
   * @param employments people port
   * @param units organization unit port
   * @param cursors cursor codec
   */
  public ContractExpirationService(
      ContractOperations flow,
      ContractCalendar calendar,
      ContractEvents events,
      JdbcContractExpirationRepository expirations,
      EmploymentExpirationScope employments,
      OrganizationPlacementDirectory units,
      CursorCodec cursors) {
    this.flow = flow;
    this.calendar = calendar;
    this.events = events;
    this.expirations = expirations;
    this.employments = employments;
    this.units = units;
    this.cursors = cursors;
  }

  // ------------------------------------------------------------------------------------------
  // Summary
  // ------------------------------------------------------------------------------------------

  /**
   * The unfiltered counts on today's business date.
   *
   * @param caller verified tenant administrator
   * @return the summary, after its disclosure audit committed
   */
  public Summary summary(DocumentsCaller caller) {
    TenantId tenant = caller.tenant();
    return flow.consistentRead(
        SUMMARY,
        "summary",
        "single",
        () -> {
          OrganizationSummary organization = calendar.organization(tenant);
          LocalDate asOf = calendar.today(organization);
          List<RelevantEmployment> scope = employments.relevant(tenant, asOf, Filter.NONE);
          Counts counts = counts(expirations.counts(tenant, scope, asOf));
          Summary body = new Summary(asOf, organization.timezone(), counts);
          events.expirationsDisclosed(
              caller,
              READ,
              "summary",
              "single",
              List.of(),
              "view=summary\nasOf="
                  + asOf
                  + "\ntimezone="
                  + organization.timezone()
                  + "\n"
                  + countsText(counts),
              0,
              calendar.now());
          return new ContractOperations.Audited<>(body, 0);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Search
  // ------------------------------------------------------------------------------------------

  /** A validated search; the query is never logged ({@code toString} hides it). */
  private record Search(
      String query, UUID unitId, Set<ExpirationCategory> categories, String cursor, int limit) {

    List<String> filterKinds() {
      List<String> kinds = new ArrayList<>();
      if (query != null) {
        kinds.add("query");
      }
      if (unitId != null) {
        kinds.add("unit");
      }
      if (categories.size() < ExpirationCategory.values().length) {
        kinds.add("categories");
      }
      return kinds;
    }

    @Override
    public String toString() {
      return "Search[limit=" + limit + ", filters=" + filterKinds() + "]";
    }
  }

  /** Where a next page continues: the pinned business date and the last row's keys. */
  private record After(LocalDate asOf, LocalDate endDate, UUID contractId) {}

  /**
   * A page of the queue, most urgent first, with the counts of the same search and unit.
   *
   * @param caller verified tenant administrator
   * @param request filters and position
   * @return the page, after its disclosure audit committed
   */
  public Page search(DocumentsCaller caller, ContractExpirationSearchRequest request) {
    Search search = flow.validated(SEARCH, () -> validate(request));
    TenantId tenant = caller.tenant();
    CursorScope scope = scope(tenant, search);
    KeysetPosition position =
        flow.validated(
            SEARCH, () -> search.cursor() == null ? null : position(search.cursor(), scope));
    String page = position == null ? "first" : "next";
    return flow.consistentRead(
        SEARCH,
        "search",
        page,
        () -> {
          OrganizationSummary organization = calendar.organization(tenant);
          After after = position == null ? null : after(tenant, position);
          LocalDate asOf = after == null ? calendar.today(organization) : after.asOf();
          List<RelevantEmployment> relevant =
              employments.relevant(tenant, asOf, new Filter(search.query(), search.unitId()));
          Counts counts = counts(expirations.counts(tenant, relevant, asOf));
          List<Head> rows =
              expirations.page(
                  tenant,
                  relevant,
                  asOf,
                  search.categories(),
                  after == null ? null : after.endDate(),
                  after == null ? null : after.contractId(),
                  search.limit() + 1);
          boolean more = rows.size() > search.limit();
          List<Head> shown = more ? rows.subList(0, search.limit()) : rows;
          List<Item> items = items(tenant, asOf, shown);
          String next =
              more
                  ? cursors.encode(
                      scope,
                      new KeysetPosition(
                          "E" + asOf.format(BASIC), shown.get(shown.size() - 1).contractId()))
                  : null;
          Page body = new Page(asOf, organization.timezone(), counts, items, next);
          StringBuilder disclosed =
              new StringBuilder("view=search\nasOf=").append(asOf).append('\n');
          disclosed.append(countsText(counts));
          shown.forEach(head -> disclosed.append("id=").append(head.contractId()).append('\n'));
          events.expirationsDisclosed(
              caller,
              READ,
              "search",
              page,
              search.filterKinds(),
              disclosed.toString(),
              items.size(),
              calendar.now());
          return new ContractOperations.Audited<>(body, items.size());
        });
  }

  private List<Item> items(TenantId tenant, LocalDate asOf, List<Head> shown) {
    if (shown.isEmpty()) {
      return List.of();
    }
    Set<UUID> employeeIds = shown.stream().map(Head::employeeId).collect(Collectors.toSet());
    Set<UUID> employmentIds = shown.stream().map(Head::employmentId).collect(Collectors.toSet());
    Map<UUID, EmployeeLabel> labels = employments.employees(tenant, employeeIds);
    Map<UUID, UUID> unitOf = employments.units(tenant, asOf, employmentIds);
    Map<UUID, UnitView> named =
        unitOf.isEmpty() ? Map.of() : units.resolveIds(tenant, new HashSet<>(unitOf.values()));
    List<Item> items = new ArrayList<>(shown.size());
    for (Head head : shown) {
      EmployeeLabel label = labels.get(head.employeeId());
      if (label == null) {
        throw new IllegalStateException("contract employee not found in its tenant");
      }
      UUID unitId = unitOf.get(head.employmentId());
      UnitView unit = unitId == null ? null : named.get(unitId);
      ExpirationCategory category =
          ExpirationCategory.of(head.days())
              .orElseThrow(() -> new IllegalStateException("head outside the window"));
      items.add(
          new Item(
              head.contractId(),
              head.employeeId(),
              label.employeeNumber(),
              label.givenNames(),
              label.familyName(),
              unit == null
                  ? null
                  : new Unit(unit.id(), unit.kind().name(), unit.code(), unit.name()),
              head.endDate(),
              category.name(),
              head.days()));
    }
    return items;
  }

  // ------------------------------------------------------------------------------------------
  // Validation and cursors
  // ------------------------------------------------------------------------------------------

  private static Search validate(ContractExpirationSearchRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("body", Constraint.REQUIRED).throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, Constraint.UNKNOWN_PROPERTY);
    }
    String query = null;
    if (request.getQuery() != null) {
      if (!(request.getQuery() instanceof String text)) {
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
    }
    UUID unitId = null;
    if (request.getUnitId() != null) {
      if (request.getUnitId() instanceof String text && UUID_TEXT.matcher(text).matches()) {
        unitId = UUID.fromString(text);
      } else {
        errors.add("unitId", Constraint.FORMAT);
      }
    }
    Set<ExpirationCategory> categories = EnumSet.allOf(ExpirationCategory.class);
    if (request.getCategories() != null) {
      if (!(request.getCategories() instanceof List<?> values)
          || values.isEmpty()
          || values.size() > ExpirationCategory.values().length) {
        errors.add("categories", Constraint.FORMAT);
      } else {
        Set<ExpirationCategory> chosen = EnumSet.noneOf(ExpirationCategory.class);
        for (Object value : values) {
          ExpirationCategory category = category(value);
          if (category == null) {
            errors.add("categories", Constraint.FORMAT);
            break;
          }
          if (!chosen.add(category)) {
            errors.add("categories", Constraint.DUPLICATE);
            break;
          }
        }
        categories = chosen;
      }
    }
    String cursor = null;
    if (request.getCursor() != null) {
      if (request.getCursor() instanceof String text) {
        cursor = text;
      } else {
        errors.add("cursor", Constraint.FORMAT);
      }
    }
    int limit = DEFAULT_LIMIT;
    if (request.getLimit() != null) {
      if (!(request.getLimit() instanceof Integer value)) {
        errors.add("limit", Constraint.FORMAT);
      } else if (value < 1 || value > MAX_LIMIT) {
        errors.add("limit", Constraint.RANGE);
      } else {
        limit = value;
      }
    }
    errors.throwIfAny();
    return new Search(query, unitId, categories, cursor, limit);
  }

  private static ExpirationCategory category(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    for (ExpirationCategory category : ExpirationCategory.values()) {
      if (category.name().equals(text)) {
        return category;
      }
    }
    return null;
  }

  /** The cursor binding: operation, tenant and every filter (A31A-3: the unit included). */
  private static CursorScope scope(TenantId tenant, Search search) {
    Map<String, String> filters = new TreeMap<>();
    filters.put("query", search.query() == null ? "-" : Fingerprints.sha256(search.query()));
    filters.put("unitId", search.unitId() == null ? "-" : search.unitId().toString());
    filters.put(
        "categories",
        search.categories().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
    filters.put("limit", Integer.toString(search.limit()));
    return new CursorScope(SEARCH, tenant, filters);
  }

  private KeysetPosition position(String cursor, CursorScope scope) {
    KeysetPosition position = cursors.decode(cursor, scope);
    if (!CURSOR_CODE.matcher(position.code()).matches()) {
      throw invalidCursor();
    }
    return position;
  }

  /** The pinned business date (signed in the cursor) and the last row's immutable end date. */
  private After after(TenantId tenant, KeysetPosition position) {
    var matcher = CURSOR_CODE.matcher(position.code());
    if (!matcher.matches()) {
      throw invalidCursor();
    }
    LocalDate asOf;
    try {
      asOf = LocalDate.parse(matcher.group(1), BASIC);
    } catch (DateTimeParseException malformed) {
      throw invalidCursor();
    }
    LocalDate end =
        expirations
            .endDate(tenant, position.id())
            .orElseThrow(ContractExpirationService::invalidCursor);
    return new After(asOf, end, position.id());
  }

  private static ApiException invalidCursor() {
    return new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
  }

  // ------------------------------------------------------------------------------------------
  // Counts
  // ------------------------------------------------------------------------------------------

  private static Counts counts(JdbcContractExpirationRepository.Counts counts) {
    return new Counts(
        counts.expired(),
        counts.next30Days(),
        counts.days31To60(),
        counts.days61To90(),
        counts.total());
  }

  private static String countsText(Counts counts) {
    return "counts="
        + counts.expired()
        + ","
        + counts.next30Days()
        + ","
        + counts.days31To60()
        + ","
        + counts.days61To90()
        + "\n";
  }
}
