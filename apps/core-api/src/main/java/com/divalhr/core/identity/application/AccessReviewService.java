package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.AccessReviewLookupRequest;
import com.divalhr.core.identity.api.AccessReviewPageResponse;
import com.divalhr.core.identity.api.AccessReviewPageResponse.DirectScope;
import com.divalhr.core.identity.api.AccessReviewPageResponse.EffectiveScope;
import com.divalhr.core.identity.api.AccessReviewPageResponse.Entry;
import com.divalhr.core.identity.api.AccessReviewPageResponse.MatchedUnit;
import com.divalhr.core.identity.api.AccessReviewSummaryResponse;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.JdbcAccessReviewRepository;
import com.divalhr.core.identity.internal.JdbcAccessReviewRepository.Row;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.OrganizationUnitDirectory;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read-only access review (MVP-012B, architect decisions on #37: D4-D8, A4-A6, R1-R8 and
 * B1-B5).
 *
 * <p>Every successful response is built inside one read-write transaction that also writes its
 * {@code access-review.read} audit row with the canonical digest ({@link AccessReviewDigest}). The
 * response object is returned only after that transaction commits; the controller serializes it
 * afterwards, so no review byte can reach the client unless the audit row is durable (B2). If the
 * audit row or the commit fails, the exception propagates and the caller gets the ordinary safe
 * {@code 500 INTERNAL_ERROR}.
 *
 * <p>Observability uses explicit allow-lists (B4): view, filter kinds, page, result count, digest
 * version and outcome. Never an address, lookup, subject, membership ID, unit ID, cursor, lookup
 * input, role filter value or response body.
 */
@Service
public class AccessReviewService {

  /** List operation (metrics, cursor binding). */
  public static final String LIST = "access-review.list";

  /** Lookup operation. */
  public static final String LOOKUP = "access-review.lookup";

  /** Summary operation. */
  public static final String SUMMARY = "access-review.summary";

  /** Shared per-subject rate-limit bucket of the three operations. */
  public static final String RATE_BUCKET = "access-review";

  /** Audit action of every successful disclosure. */
  public static final String AUDIT_ACTION = "access-review.read";

  /** Requests metric (bounded tags: view, filter_kind, outcome). */
  public static final String METRIC = "divalhr.access_review.requests";

  /** Default page size. */
  public static final int DEFAULT_LIMIT = 25;

  /** Maximum page size. */
  public static final int MAX_LIMIT = 50;

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.access-review");
  private static final Pattern MICROS = Pattern.compile("^[0-9]{16}$");
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final String ABSENT = "-";

  private final JdbcAccessReviewRepository reviews;
  private final OrganizationUnitDirectory units;
  private final CursorCodec cursors;
  private final EmailLookup lookups;
  private final AuditRecorder audit;
  private final TransactionTemplate transactions;
  private final OperationMetrics metrics;
  private final MeterRegistry registry;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param reviews review queries
   * @param units tenant-bound unit checks
   * @param cursors cursor codec
   * @param lookups address lookup
   * @param audit audit recorder
   * @param transactions transaction template
   * @param metrics operation metrics
   * @param registry meter registry
   */
  public AccessReviewService(
      JdbcAccessReviewRepository reviews,
      OrganizationUnitDirectory units,
      CursorCodec cursors,
      EmailLookup lookups,
      AuditRecorder audit,
      TransactionTemplate transactions,
      OperationMetrics metrics,
      MeterRegistry registry) {
    this.reviews = reviews;
    this.units = units;
    this.cursors = cursors;
    this.lookups = lookups;
    this.audit = audit;
    this.transactions = transactions;
    this.metrics = metrics;
    this.registry = registry;
    this.clock = Clock.systemUTC();
  }

  /** The verified caller of a review request. */
  public record Caller(TenantId tenant, String subject, String correlationId) {}

  private record ListQuery(TenantRole role, String unitType, UUID unitId, int limit) {

    String filterKinds() {
      List<String> kinds = new ArrayList<>();
      if (role != null) {
        kinds.add("role");
      }
      if (unitType != null) {
        kinds.add("LEGAL_ENTITY".equals(unitType) ? "legal-entity" : "site");
      }
      return kinds.isEmpty() ? "none" : String.join("+", kinds);
    }

    List<String> filterKindList() {
      List<String> kinds = new ArrayList<>();
      if (unitType != null) {
        kinds.add("LEGAL_ENTITY".equals(unitType) ? "legalEntity" : "site");
      }
      if (role != null) {
        kinds.add("role");
      }
      return kinds.stream().sorted().toList();
    }
  }

  // ------------------------------------------------------------------------------------------
  // List
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the organization's active access.
   *
   * @param caller verified caller
   * @param role raw role filter or null
   * @param legalEntityId raw legal entity filter or null
   * @param siteId raw site filter or null
   * @param cursor opaque cursor or null
   * @param limit raw page size or null
   * @return the page, after its audit row committed
   */
  public AccessReviewPageResponse list(
      Caller caller,
      String role,
      String legalEntityId,
      String siteId,
      String cursor,
      String limit) {
    ListQuery query;
    try {
      query = validateList(role, legalEntityId, siteId, limit);
    } catch (ApiException invalid) {
      outcome("list", "none", LIST, Outcome.VALIDATION_FAILED, "invalid", "first", 0);
      throw invalid;
    }
    String page = cursor == null ? "first" : "next";
    // Tenant-bound, non-enumerating unit check (A5, R5).
    if (query.unitId() != null) {
      boolean exists =
          "LEGAL_ENTITY".equals(query.unitType())
              ? units.legalEntityExists(caller.tenant(), query.unitId())
              : units.siteExists(caller.tenant(), query.unitId());
      if (!exists) {
        outcome("list", query.filterKinds(), LIST, Outcome.NOT_FOUND, "not_found", page, 0);
        throw new ApiException(
            "LEGAL_ENTITY".equals(query.unitType())
                ? ErrorCode.LEGAL_ENTITY_NOT_FOUND
                : ErrorCode.SITE_NOT_FOUND,
            Map.of());
      }
    }
    // Cursor verification before any membership query (B3).
    CursorScope scope = scope(caller.tenant(), query);
    Instant afterGrantedAt = null;
    UUID afterId = null;
    if (cursor != null) {
      try {
        KeysetPosition after = cursors.decode(cursor, scope);
        if (!MICROS.matcher(after.code()).matches()) {
          throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
        }
        afterGrantedAt = fromMicros(Long.parseLong(after.code()));
        afterId = after.id();
      } catch (ApiException invalid) {
        outcome("list", query.filterKinds(), LIST, Outcome.VALIDATION_FAILED, "invalid", page, 0);
        throw invalid;
      }
    }
    Instant keysetTime = afterGrantedAt;
    UUID keysetId = afterId;
    MatchedUnit matched =
        query.unitId() == null
            ? null
            : new MatchedUnit(query.unitType(), query.unitId(), "INHERITED_FROM_TENANT");
    AccessReviewPageResponse response =
        disclose(
            caller,
            "list",
            query.filterKinds(),
            query.filterKindList(),
            page,
            LIST,
            () -> {
              List<Row> rows =
                  reviews.page(
                      caller.tenant(), query.role(), keysetTime, keysetId, query.limit() + 1);
              List<Row> shown = rows.subList(0, Math.min(rows.size(), query.limit()));
              String next = null;
              if (rows.size() > query.limit()) {
                Row last = shown.get(shown.size() - 1);
                next =
                    cursors.encode(
                        scope,
                        new KeysetPosition(
                            String.format("%016d", toMicros(last.grantedAt())), last.id()));
              }
              AccessReviewPageResponse body =
                  new AccessReviewPageResponse(
                      shown.stream().map(row -> entry(row, matched)).toList(), next);
              String digest =
                  AccessReviewDigest.sha256(
                      AccessReviewDigest.listText(
                          keysetId == null, next != null, ids(body.data())));
              return new Disclosure<>(body, body.data().size(), digest);
            });
    return response;
  }

  private ListQuery validateList(String role, String legalEntityId, String siteId, String limit) {
    FieldErrors errors = new FieldErrors();
    TenantRole parsedRole = null;
    if (role != null) {
      parsedRole = TenantRole.fromWire(role).orElse(null);
      if (parsedRole == null) {
        errors.add("role", Constraint.FORMAT);
      }
    }
    UUID legalEntity = uuid(errors, "legalEntityId", legalEntityId);
    UUID site = uuid(errors, "siteId", siteId);
    if (legalEntityId != null && siteId != null) {
      // At most one unit filter (A5); field names only, never values.
      errors.add("legalEntityId", Constraint.FORMAT);
      errors.add("siteId", Constraint.FORMAT);
    }
    int size = DEFAULT_LIMIT;
    if (limit != null && !limit.isEmpty()) {
      if (!LIMIT.matcher(limit).matches()) {
        errors.add("limit", Constraint.RANGE);
      } else {
        size = Integer.parseInt(limit);
        if (size < 1 || size > MAX_LIMIT) {
          errors.add("limit", Constraint.RANGE);
        }
      }
    }
    errors.throwIfAny();
    String unitType = legalEntity != null ? "LEGAL_ENTITY" : site != null ? "SITE" : null;
    UUID unitId = legalEntity != null ? legalEntity : site;
    return new ListQuery(parsedRole, unitType, unitId, size);
  }

  private static UUID uuid(FieldErrors errors, String field, String raw) {
    if (raw == null) {
      return null;
    }
    if (!UUID_TEXT.matcher(raw).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(raw);
  }

  /**
   * Cursor binding (A5, B3): operation, tenant, role filter, unit type, unit ID and page size, with
   * absent values encoded explicitly as {@code -}.
   */
  private static CursorScope scope(TenantId tenant, ListQuery query) {
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("role", query.role() == null ? ABSENT : query.role().wireName());
    filters.put("unitType", query.unitType() == null ? ABSENT : query.unitType());
    filters.put("unitId", query.unitId() == null ? ABSENT : query.unitId().toString());
    filters.put("limit", Integer.toString(query.limit()));
    return new CursorScope(LIST, tenant, filters);
  }

  // ------------------------------------------------------------------------------------------
  // Lookup
  // ------------------------------------------------------------------------------------------

  /**
   * The active access of one exact address in the caller's tenant (D6, R7).
   *
   * @param caller verified caller
   * @param request body
   * @return zero or one entry, after its audit row committed
   */
  public AccessReviewPageResponse lookup(Caller caller, AccessReviewLookupRequest request) {
    EmailAddress address;
    try {
      FieldErrors errors = new FieldErrors();
      if (request == null) {
        errors.add("email", Constraint.REQUIRED);
        errors.throwIfAny();
      }
      if (!request.unknownProperties().isEmpty()) {
        errors.add("body", Constraint.UNKNOWN_PROPERTY);
      }
      Optional<EmailAddress.Defect> defect = EmailAddress.defectOf(request.getEmail());
      defect.ifPresent(
          d ->
              errors.add(
                  "email",
                  switch (d) {
                    case REQUIRED -> Constraint.REQUIRED;
                    case LENGTH -> Constraint.LENGTH;
                    case FORMAT -> Constraint.FORMAT;
                  }));
      errors.throwIfAny();
      address =
          EmailAddress.parse(request.getEmail())
              .orElseThrow(
                  () ->
                      new ApiException(
                          ErrorCode.VALIDATION_FAILED,
                          Map.of(
                              "fields",
                              List.of(Map.of("field", "email", "constraint", "FORMAT")))));
    } catch (ApiException invalid) {
      outcome("lookup", "none", LOOKUP, Outcome.VALIDATION_FAILED, "invalid", "single", 0);
      throw invalid;
    }
    byte[] lookup = lookups.of(address);
    return disclose(
        caller,
        "lookup",
        "none",
        List.of(),
        "single",
        LOOKUP,
        () -> {
          // The submitted address is never echoed: a match returns the stored value (or null).
          List<Entry> found =
              reviews.findByLookup(caller.tenant(), lookup).stream()
                  .map(row -> entry(row, null))
                  .toList();
          AccessReviewPageResponse body = new AccessReviewPageResponse(found, null);
          String digest = AccessReviewDigest.sha256(AccessReviewDigest.lookupText(ids(found)));
          return new Disclosure<>(body, found.size(), digest);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Summary
  // ------------------------------------------------------------------------------------------

  /**
   * Active members per role, with the same predicate as the entries (A5).
   *
   * @param caller verified caller
   * @return counts, after the audit row committed
   */
  public AccessReviewSummaryResponse summary(Caller caller) {
    return disclose(
        caller,
        "summary",
        "none",
        List.of(),
        "single",
        SUMMARY,
        () -> {
          long admins = reviews.count(caller.tenant(), TenantRole.TENANT_ADMIN);
          long employees = reviews.count(caller.tenant(), TenantRole.EMPLOYEE);
          AccessReviewSummaryResponse body =
              new AccessReviewSummaryResponse(
                  new AccessReviewSummaryResponse.ByRole(admins, employees));
          String digest =
              AccessReviewDigest.sha256(AccessReviewDigest.summaryText(admins, employees));
          return new Disclosure<>(body, 2, digest);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Fail-closed disclosure (A4, B2)
  // ------------------------------------------------------------------------------------------

  private record Disclosure<T>(T body, int resultCount, String digest) {}

  @FunctionalInterface
  private interface Query<T> {
    Disclosure<T> run();
  }

  private <T> T disclose(
      Caller caller,
      String view,
      String filterKind,
      List<String> filterKinds,
      String page,
      String operation,
      Query<T> query) {
    Disclosure<T> disclosure;
    try {
      disclosure =
          transactions.execute(
              status -> {
                Disclosure<T> built = query.run();
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("view", view);
                metadata.put("filterKinds", filterKinds);
                metadata.put("page", page);
                metadata.put("resultCount", built.resultCount());
                metadata.put("digestVersion", AccessReviewDigest.VERSION);
                audit.record(
                    new AuditEvent(
                        UUID.randomUUID(),
                        Instant.now(clock),
                        caller.subject(),
                        AUDIT_ACTION,
                        "access-review",
                        caller.tenant().value(),
                        caller.tenant().value(),
                        "SUCCESS",
                        caller.correlationId(),
                        metadata,
                        built.digest()));
                return built;
              });
    } catch (RuntimeException failure) {
      // Nothing was disclosed: the transaction (query and audit) rolled back.
      outcome(view, filterKind, operation, Outcome.FAILURE, "failed", page, 0);
      throw failure;
    }
    if (disclosure == null) {
      throw new IllegalStateException("access review transaction returned nothing");
    }
    outcome(view, filterKind, operation, Outcome.LISTED, "ok", page, disclosure.resultCount());
    return disclosure.body();
  }

  private void outcome(
      String view,
      String filterKind,
      String operation,
      Outcome outcome,
      String label,
      String page,
      int resultCount) {
    metrics.record(operation, outcome);
    Counter.builder(METRIC)
        .description("Access-review requests by view, filter kind and outcome")
        .tag("view", view)
        .tag("filter_kind", filterKind)
        .tag("outcome", label)
        .register(registry)
        .increment();
    LOG.atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("view", view)
        .addKeyValue("filterKinds", filterKind)
        .addKeyValue("page", page)
        .addKeyValue("resultCount", resultCount)
        .addKeyValue("outcome", label)
        .log("access_review");
  }

  private static Entry entry(Row row, MatchedUnit matched) {
    return new Entry(
        row.id(),
        row.email(),
        row.role().wireName(),
        row.grantedAt(),
        DirectScope.TENANT,
        EffectiveScope.TENANT,
        matched);
  }

  private static List<UUID> ids(List<Entry> entries) {
    return entries.stream().map(Entry::membershipId).toList();
  }

  static long toMicros(Instant instant) {
    Instant micros = instant.truncatedTo(ChronoUnit.MICROS);
    return Math.addExact(
        Math.multiplyExact(micros.getEpochSecond(), 1_000_000L), micros.getNano() / 1_000L);
  }

  static Instant fromMicros(long micros) {
    return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L))
        .plusNanos(Math.floorMod(micros, 1_000_000L) * 1_000L);
  }
}
