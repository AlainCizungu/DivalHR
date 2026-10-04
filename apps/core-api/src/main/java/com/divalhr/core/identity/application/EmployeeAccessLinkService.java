package com.divalhr.core.identity.application;

import com.divalhr.core.identity.api.AccessLinkCandidateResponse;
import com.divalhr.core.identity.api.AccessReviewLookupRequest;
import com.divalhr.core.identity.api.CreateEmployeeAccessLinkRequest;
import com.divalhr.core.identity.api.EmployeeAccessResponse;
import com.divalhr.core.identity.api.RemoveEmployeeAccessLinkRequest;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.LinkRow;
import com.divalhr.core.identity.internal.JdbcAccessLinkRepository.MembershipRow;
import com.divalhr.core.platform.access.EmployeeRecords;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-022: the explicit link between one employee and one tenant membership. It is never inferred
 * from a name or an address in an employee record: an administrator looks up an exact address of
 * the caller's tenant (keyed lookup, never echoed), reviews the membership and links it.
 *
 * <p>Every write takes the per-tenant access-link lock first (ADR 0008 step 3), which every
 * separation also takes before reading a link, so a link and a separation are always serialized.
 * Writes, audit and outbox commit together; reads commit their disclosure audit before the body is
 * returned. Logs carry operation, outcome and code only.
 */
@Service
public class EmployeeAccessLinkService {

  /** Read operation. */
  public static final String READ = "employee-access-link.read";

  /** Lookup operation. */
  public static final String LOOKUP = "employee-access-link.lookup";

  /** Link operation (and its audit action). */
  public static final String CREATE = "employee-access-link.create";

  /** Unlink operation (and its audit action). */
  public static final String REMOVE = "employee-access-link.remove";

  /** Per-subject read bucket (shared with the employee reads of MVP-021). */
  public static final String SUBJECT_READ_BUCKET = "employee-read";

  /** Per-subject write bucket (shared with the employment writes of MVP-021). */
  public static final String SUBJECT_WRITE_BUCKET = "employment-change";

  /** Per-tenant write bucket (shared with the employment writes of MVP-021). */
  public static final String TENANT_WRITE_BUCKET = "employment-change-write";

  private static final Logger LOG = LoggerFactory.getLogger(EmployeeAccessLinkService.class);
  private static final Pattern UUID_TEXT =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "employee_access_link", "create", "created", 201);
  private static final IdempotentOperation.Spec REMOVE_SPEC =
      new IdempotentOperation.Spec(REMOVE, "employee_access_link", "remove", "removed", 200);

  private final JdbcAccessLinkRepository links;
  private final EmployeeRecords employees;
  private final EmailLookup lookups;
  private final AccessLinkEvents events;
  private final IdempotentOperation operations;
  private final TransactionTemplate transactions;
  private final OperationMetrics metrics;

  /**
   * Creates the service.
   *
   * @param links link repository
   * @param employees employee port (people)
   * @param lookups keyed address lookup
   * @param events audit and outbox
   * @param operations idempotent operation flow
   * @param transactions transaction template
   * @param metrics operation metrics
   */
  public EmployeeAccessLinkService(
      JdbcAccessLinkRepository links,
      EmployeeRecords employees,
      EmailLookup lookups,
      AccessLinkEvents events,
      IdempotentOperation operations,
      TransactionTemplate transactions,
      OperationMetrics metrics) {
    this.links = links;
    this.employees = employees;
    this.lookups = lookups;
    this.events = events;
    this.operations = operations;
    this.transactions = transactions;
    this.metrics = metrics;
  }

  /**
   * A verified caller.
   *
   * @param tenant tenant from the verified token, confirmed by the membership gate
   * @param subject verified subject
   * @param correlationId correlation ID
   */
  public record Caller(TenantId tenant, String subject, String correlationId) {}

  // ------------------------------------------------------------------------------------------
  // Reads
  // ------------------------------------------------------------------------------------------

  /**
   * The employee's link and access state.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @return the access, after its disclosure audit committed
   */
  public EmployeeAccessResponse read(Caller caller, String employeeId) {
    UUID id = rejectedAs(READ, () -> employeeId(employeeId));
    return disclose(
        READ,
        () -> {
          requireEmployee(caller.tenant(), id);
          Optional<LinkRow> link = links.activeLink(caller.tenant(), id);
          EmployeeAccessResponse body = response(link.orElse(null));
          events.disclosed(
              caller.tenant(),
              caller.subject(),
              READ,
              id,
              link.isPresent() ? 1 : 0,
              link.map(l -> List.of(l.id(), l.membershipId())).orElse(List.of()),
              caller.correlationId());
          return body;
        });
  }

  /**
   * The membership of one exact address, with whether it can be linked to the employee.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param request body
   * @return the candidate, after its disclosure audit committed
   */
  public AccessLinkCandidateResponse lookup(
      Caller caller, String employeeId, AccessReviewLookupRequest request) {
    UUID id = rejectedAs(LOOKUP, () -> employeeId(employeeId));
    EmailAddress address = rejectedAs(LOOKUP, () -> address(request));
    byte[] lookup = lookups.of(address);
    return disclose(
        LOOKUP,
        () -> {
          requireEmployee(caller.tenant(), id);
          MembershipRow membership =
              links
                  .membershipByLookup(caller.tenant(), lookup)
                  .orElseThrow(EmployeeAccessLinkService::membershipNotFound);
          String reason = notLinkable(caller.tenant(), id, membership);
          events.disclosed(
              caller.tenant(),
              caller.subject(),
              LOOKUP,
              id,
              1,
              List.of(membership.id()),
              caller.correlationId());
          return new AccessLinkCandidateResponse(
              membership.id(), membership.role(), reason == null, reason);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Writes
  // ------------------------------------------------------------------------------------------

  /**
   * Links the employee to a membership of the caller's tenant, or replays an identical link.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the access and whether it was replayed
   */
  public IdempotentOperation.Result<EmployeeAccessResponse> create(
      Caller caller,
      String employeeId,
      String idempotencyKey,
      CreateEmployeeAccessLinkRequest request) {
    UUID id = employeeId(employeeId);
    UUID membershipId =
        operations.validated(
            CREATE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              if (request == null) {
                errors.add("body", Constraint.REQUIRED);
                errors.throwIfAny();
              }
              request.unknownProperties().forEach(p -> errors.add(p, Constraint.UNKNOWN_PROPERTY));
              UUID parsed = uuid(request.getMembershipId(), "membershipId", errors);
              errors.throwIfAny();
              return parsed;
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("membershipId", membershipId.toString());
    return operations.execute(
        CREATE_SPEC,
        caller.subject(),
        idempotencyKey,
        canonical,
        EmployeeAccessResponse.class,
        TIMEOUT,
        () -> {
          TenantId tenant = caller.tenant();
          links.lockTenant(tenant);
          requireEmployee(tenant, id);
          if (employees.separated(tenant, id)) {
            throw new ApiException(ErrorCode.ACCESS_LINK_LOCKED, Map.of());
          }
          MembershipRow membership =
              links
                  .lockMembership(tenant, membershipId)
                  .orElseThrow(EmployeeAccessLinkService::membershipNotFound);
          String reason = notLinkable(tenant, id, membership);
          if (reason != null) {
            throw new ApiException(ErrorCode.ACCESS_LINK_CONFLICT, Map.of("reason", reason));
          }
          UUID linkId = UUID.randomUUID();
          Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
          links.insertLink(tenant, linkId, id, membershipId, now, caller.subject());
          events.link(
              tenant,
              caller.subject(),
              CREATE,
              AccessLinkEvents.LINK_CREATED,
              linkId,
              id,
              membershipId,
              0,
              caller.correlationId());
          LinkRow created =
              links
                  .activeLink(tenant, id)
                  .orElseThrow(() -> new IllegalStateException("link vanished"));
          return new IdempotentOperation.Completed<>(response(created), linkId, Outcome.CREATED);
        });
  }

  /**
   * Removes the employee's active link, or replays an identical removal.
   *
   * @param caller verified caller
   * @param employeeId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @return the access and whether it was replayed
   */
  public IdempotentOperation.Result<EmployeeAccessResponse> remove(
      Caller caller,
      String employeeId,
      String idempotencyKey,
      RemoveEmployeeAccessLinkRequest request) {
    UUID id = employeeId(employeeId);
    record Removal(UUID linkId, long version) {}
    Removal removal =
        operations.validated(
            REMOVE_SPEC,
            () -> {
              FieldErrors errors = new FieldErrors();
              requireKey(errors, idempotencyKey);
              if (request == null) {
                errors.add("body", Constraint.REQUIRED);
                errors.throwIfAny();
              }
              request.unknownProperties().forEach(p -> errors.add(p, Constraint.UNKNOWN_PROPERTY));
              UUID linkId = uuid(request.getLinkId(), "linkId", errors);
              Long version = version(request.getExpectedVersion(), "expectedVersion", errors);
              errors.throwIfAny();
              return new Removal(linkId, version);
            });
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", caller.tenant().toString());
    canonical.put("employeeId", id.toString());
    canonical.put("linkId", removal.linkId().toString());
    canonical.put("expectedVersion", removal.version());
    return operations.execute(
        REMOVE_SPEC,
        caller.subject(),
        idempotencyKey,
        canonical,
        EmployeeAccessResponse.class,
        TIMEOUT,
        () -> {
          TenantId tenant = caller.tenant();
          links.lockTenant(tenant);
          requireEmployee(tenant, id);
          LinkRow link =
              links
                  .lockActiveLink(tenant, id)
                  .filter(l -> l.id().equals(removal.linkId()))
                  .orElseThrow(
                      () -> new ApiException(ErrorCode.ACCESS_LINK_VERSION_CONFLICT, Map.of()));
          if (link.revoked() || employees.separated(tenant, id)) {
            throw new ApiException(ErrorCode.ACCESS_LINK_LOCKED, Map.of());
          }
          if (link.version() != removal.version()) {
            throw new ApiException(ErrorCode.ACCESS_LINK_VERSION_CONFLICT, Map.of());
          }
          Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
          if (links.unlink(tenant, link.id(), id, link.version(), now, caller.subject()) != 1) {
            throw new ApiException(ErrorCode.ACCESS_LINK_VERSION_CONFLICT, Map.of());
          }
          events.link(
              tenant,
              caller.subject(),
              REMOVE,
              AccessLinkEvents.LINK_REMOVED,
              link.id(),
              id,
              link.membershipId(),
              link.version() + 1,
              caller.correlationId());
          return new IdempotentOperation.Completed<>(response(null), link.id(), Outcome.UPDATED);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /** Why the membership cannot be linked to the employee, or null. */
  private String notLinkable(TenantId tenant, UUID employeeId, MembershipRow membership) {
    if (links.activeLink(tenant, employeeId).isPresent()) {
      return "EMPLOYEE_LINKED";
    }
    if (membership.revoked()) {
      return "ACCESS_REVOKED";
    }
    if (membership.linked()) {
      return "ALREADY_LINKED";
    }
    return null;
  }

  private static EmployeeAccessResponse response(LinkRow link) {
    if (link == null) {
      return new EmployeeAccessResponse("NOT_LINKED", null);
    }
    String state;
    if (link.revocationEffective()) {
      state = "REVOKED";
    } else if (link.revoked()) {
      state = "REVOCATION_SCHEDULED";
    } else {
      state = "ACTIVE";
    }
    return new EmployeeAccessResponse(
        state,
        new EmployeeAccessResponse.Link(
            link.id(), link.membershipId(), link.role(), link.linkedAt(), link.version()));
  }

  private void requireEmployee(TenantId tenant, UUID employeeId) {
    if (!employees.exists(tenant, employeeId)) {
      throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, Map.of());
    }
  }

  private <T> T disclose(String operation, Supplier<T> read) {
    T body;
    try {
      body = transactions.execute(status -> read.get());
    } catch (ApiException rejected) {
      outcome(
          operation,
          rejected.code().status().value() == 404 ? Outcome.NOT_FOUND : Outcome.FAILURE,
          rejected.code().name());
      throw rejected;
    } catch (RuntimeException failure) {
      outcome(operation, Outcome.FAILURE, "INTERNAL_ERROR");
      throw failure;
    }
    if (body == null) {
      throw new IllegalStateException("read transaction returned nothing");
    }
    outcome(operation, Outcome.LISTED, null);
    return body;
  }

  private <T> T rejectedAs(String operation, Supplier<T> validation) {
    try {
      return validation.get();
    } catch (ApiException invalid) {
      outcome(
          operation,
          invalid.code().status().value() == 404 ? Outcome.NOT_FOUND : Outcome.VALIDATION_FAILED,
          invalid.code().name());
      throw invalid;
    }
  }

  private void outcome(String operation, Outcome outcome, String code) {
    metrics.record(operation, outcome);
    var log =
        LOG.atInfo()
            .addKeyValue("operation", operation)
            .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT));
    if (code != null) {
      log = log.addKeyValue("code", code);
    }
    log.log("employee_access_link");
  }

  private static EmailAddress address(AccessReviewLookupRequest request) {
    FieldErrors errors = new FieldErrors();
    if (request == null) {
      errors.add("email", Constraint.REQUIRED);
      errors.throwIfAny();
    }
    request.unknownProperties().forEach(p -> errors.add(p, Constraint.UNKNOWN_PROPERTY));
    EmailAddress.defectOf(request.getEmail())
        .ifPresent(
            d ->
                errors.add(
                    "email",
                    switch (d) {
                      case REQUIRED -> Constraint.REQUIRED;
                      case LENGTH -> Constraint.LENGTH;
                      case FORMAT -> Constraint.FORMAT;
                    }));
    errors.throwIfAny();
    return EmailAddress.parse(request.getEmail())
        .orElseThrow(() -> new IllegalStateException("validated address did not parse"));
  }

  private static void requireKey(FieldErrors errors, String key) {
    if (key == null || key.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(key)) {
      errors.add(IdempotencyKeys.HEADER, Constraint.FORMAT);
    }
  }

  private static UUID uuid(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof String text) || !UUID_TEXT.matcher(text).matches()) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    return UUID.fromString(text);
  }

  private static Long version(Object raw, String field, FieldErrors errors) {
    if (raw == null) {
      errors.add(field, Constraint.REQUIRED);
      return null;
    }
    if (!(raw instanceof Integer || raw instanceof Long)) {
      errors.add(field, Constraint.FORMAT);
      return null;
    }
    long value = ((Number) raw).longValue();
    if (value < 0) {
      errors.add(field, Constraint.RANGE);
      return null;
    }
    return value;
  }

  /**
   * Parses an employee ID; malformed IDs are indistinguishable from unknown ones.
   *
   * @param raw raw path value
   * @return the ID
   */
  static UUID employeeId(String raw) {
    if (raw == null || !UUID_TEXT.matcher(raw).matches()) {
      throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND, Map.of());
    }
    return UUID.fromString(raw);
  }

  private static ApiException membershipNotFound() {
    return new ApiException(ErrorCode.MEMBERSHIP_NOT_FOUND, Map.of());
  }
}
