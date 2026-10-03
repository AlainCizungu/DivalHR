package com.divalhr.core.people.application;

import com.divalhr.core.people.api.CommitEmployeeImportRequest;
import com.divalhr.core.people.api.EmployeeImportResponse;
import com.divalhr.core.people.domain.ImportRecord;
import com.divalhr.core.people.domain.ImportRow;
import com.divalhr.core.people.domain.NewEmployee;
import com.divalhr.core.people.domain.RowValues;
import com.divalhr.core.people.internal.JdbcEmployeeImportRepository;
import com.divalhr.core.people.internal.JdbcEmployeeRepository;
import com.divalhr.core.people.internal.TransactionLimits;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.Placements;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * MVP-020: commits an employee import (decisions E7-E9, amendments A20-1, A20-2, A20-5).
 *
 * <p>One bounded transaction: reserve the idempotency key, lock the import, check that it is open
 * and that the command repeats the reviewed preview (digest, valid count, acknowledgement),
 * re-validate every valid row against the tenant's current employees and units, insert the
 * employees and employments in employee-number order, write the audit and outbox records, mark the
 * rows and erase every staged value. Any change since the preview, including a concurrent import
 * taking an employee number, rolls everything back with {@code 409 IMPORT_STALE}; a timeout rolls
 * everything back with {@code 503 IMPORT_TIMEOUT}. Nothing is ever partially committed.
 */
@Service
public class EmployeeImportCommitService {

  /** Commit operation. */
  public static final String COMMIT = "employee-import.commit";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.employee-import");
  private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(COMMIT, "employee_import", "commit", "committed", 200);

  private final JdbcEmployeeImportRepository imports;
  private final JdbcEmployeeRepository employees;
  private final OrganizationPlacementDirectory placements;
  private final EmployeeImportEvents events;
  private final IdempotentOperation operations;
  private final TransactionLimits limits;
  private final EmployeeImportProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param imports import repository
   * @param employees employee repository
   * @param placements placement port (tenant module)
   * @param events audit and outbox
   * @param operations idempotent operation flow
   * @param limits transaction limits
   * @param properties settings
   * @param meters meter registry
   */
  @Autowired
  public EmployeeImportCommitService(
      JdbcEmployeeImportRepository imports,
      JdbcEmployeeRepository employees,
      OrganizationPlacementDirectory placements,
      EmployeeImportEvents events,
      IdempotentOperation operations,
      TransactionLimits limits,
      EmployeeImportProperties properties,
      MeterRegistry meters) {
    this(
        imports,
        employees,
        placements,
        events,
        operations,
        limits,
        properties,
        meters,
        Clock.systemUTC());
  }

  EmployeeImportCommitService(
      JdbcEmployeeImportRepository imports,
      JdbcEmployeeRepository employees,
      OrganizationPlacementDirectory placements,
      EmployeeImportEvents events,
      IdempotentOperation operations,
      TransactionLimits limits,
      EmployeeImportProperties properties,
      MeterRegistry meters,
      Clock clock) {
    this.imports = imports;
    this.employees = employees;
    this.placements = placements;
    this.events = events;
    this.operations = operations;
    this.limits = limits;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;
  }

  /** The validated command. */
  private record Command(UUID importId, String previewDigest, int validRows, boolean acknowledge) {}

  /**
   * Commits the import, or replays an identical earlier commit.
   *
   * @param tenant verified tenant
   * @param actor verified subject
   * @param importId raw path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param request body
   * @param correlationId correlation ID
   * @return the committed import and whether it was replayed
   */
  public IdempotentOperation.Result<EmployeeImportResponse> commit(
      TenantId tenant,
      String actor,
      String importId,
      String idempotencyKey,
      CommitEmployeeImportRequest request,
      String correlationId) {
    UUID id = EmployeeImportService.importId(importId);
    Command command = operations.validated(SPEC, () -> validate(id, idempotencyKey, request));
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", tenant.toString());
    canonical.put("importId", id.toString());
    canonical.put("previewDigest", command.previewDigest());
    canonical.put("validRows", command.validRows());
    canonical.put("acknowledgeInvalidRows", command.acknowledge());
    IdempotentOperation.Result<EmployeeImportResponse> result =
        EmployeeImportService.bounded(
            () ->
                operations.execute(
                    SPEC,
                    actor,
                    idempotencyKey,
                    canonical,
                    EmployeeImportResponse.class,
                    properties.commitTransactionTimeout(),
                    () -> commitInTransaction(tenant, actor, command, correlationId)));
    if (!result.replayed()) {
      LOG.atInfo()
          .addKeyValue("operation", COMMIT)
          .addKeyValue("createdCount", result.body().createdCount())
          .addKeyValue("notImportedCount", result.body().notImportedCount())
          .log("employee_import_committed");
      Counter.builder(EmployeeImportService.ROW_METRIC)
          .description("Employee import rows by outcome")
          .tag("outcome", "created")
          .register(meters)
          .increment(result.body().createdCount());
    }
    return result;
  }

  private static Command validate(UUID id, String key, CommitEmployeeImportRequest request) {
    FieldErrors errors = new FieldErrors();
    EmployeeImportService.requireKey(errors, key);
    if (request == null) {
      errors.add("body", FieldErrors.Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    for (String unknown : request.unknownProperties()) {
      errors.add(unknown, FieldErrors.Constraint.UNKNOWN_PROPERTY);
    }
    String digest = null;
    if (request.getPreviewDigest() == null) {
      errors.add("previewDigest", FieldErrors.Constraint.REQUIRED);
    } else if (!(request.getPreviewDigest() instanceof String text)
        || !DIGEST.matcher(text).matches()) {
      errors.add("previewDigest", FieldErrors.Constraint.FORMAT);
    } else {
      digest = text;
    }
    int validRows = -1;
    if (request.getValidRows() == null) {
      errors.add("validRows", FieldErrors.Constraint.REQUIRED);
    } else if (!(request.getValidRows() instanceof Integer count)) {
      errors.add("validRows", FieldErrors.Constraint.FORMAT);
    } else if (count < 0 || count > 5_000) {
      errors.add("validRows", FieldErrors.Constraint.RANGE);
    } else {
      validRows = count;
    }
    boolean acknowledge = false;
    if (request.getAcknowledgeInvalidRows() == null) {
      errors.add("acknowledgeInvalidRows", FieldErrors.Constraint.REQUIRED);
    } else if (!(request.getAcknowledgeInvalidRows() instanceof Boolean flag)) {
      errors.add("acknowledgeInvalidRows", FieldErrors.Constraint.FORMAT);
    } else {
      acknowledge = flag;
    }
    errors.throwIfAny();
    return new Command(id, digest, validRows, acknowledge);
  }

  private IdempotentOperation.Completed<EmployeeImportResponse> commitInTransaction(
      TenantId tenant, String actor, Command command, String correlationId) {
    limits.apply(properties.commitStatementTimeout());
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    ImportRecord record =
        imports.lock(tenant, command.importId()).orElseThrow(EmployeeImportService::notFound);
    if (!record.openAt(now)) {
      throw EmployeeImportService.notCommittable(record, now);
    }
    if (!record.previewDigest().equals(command.previewDigest())
        || record.validRows() != command.validRows()) {
      throw new ApiException(ErrorCode.IMPORT_PREVIEW_CHANGED, Map.of());
    }
    if (record.validRows() == 0) {
      throw new ApiException(ErrorCode.IMPORT_NOTHING_TO_COMMIT, Map.of());
    }
    if (record.invalidRows() > 0 && !command.acknowledge()) {
      new FieldErrors().add("acknowledgeInvalidRows", FieldErrors.Constraint.REQUIRED).throwIfAny();
    }
    List<ImportRow> rows = imports.validRows(tenant, record.id());
    if (rows.size() != record.validRows()) {
      throw new ApiException(ErrorCode.IMPORT_STALE, Map.of());
    }
    List<NewEmployee> created = revalidate(tenant, rows);
    try {
      employees.insertAll(tenant, created, actor, now);
    } catch (DuplicateKeyException taken) {
      // A concurrent import or create took an employee number: nothing is kept.
      throw new ApiException(ErrorCode.IMPORT_STALE, Map.of());
    }
    events.committed(tenant, actor, record, created, now, correlationId);
    imports.markCommitted(tenant, record.id(), created.size(), actor, now);
    ImportRecord committed = imports.find(tenant, record.id()).orElseThrow();
    return new IdempotentOperation.Completed<>(
        EmployeeImportResponse.from(
            committed, new TreeMap<>(imports.errorCounts(tenant, record.id()))),
        record.id(),
        Outcome.CREATED);
  }

  /**
   * Re-checks every staged valid row inside the commit transaction; any difference from the preview
   * is {@code IMPORT_STALE}.
   */
  private List<NewEmployee> revalidate(TenantId tenant, List<ImportRow> rows) {
    Set<String> numbers = new HashSet<>();
    List<RowValues> values = new ArrayList<>(rows.size());
    for (ImportRow row : rows) {
      numbers.add(row.values().employeeNumber());
      values.add(row.values());
    }
    if (numbers.size() != rows.size() || !employees.existingNumbers(tenant, numbers).isEmpty()) {
      throw new ApiException(ErrorCode.IMPORT_STALE, Map.of());
    }
    Placements units = placements.resolve(tenant, PlacementRules.codesOf(values));
    List<NewEmployee> created = new ArrayList<>(rows.size());
    for (RowValues row : values) {
      PlacementRules.Outcome outcome = PlacementRules.check(row, units);
      if (!outcome.errors().isEmpty()) {
        throw new ApiException(ErrorCode.IMPORT_STALE, Map.of());
      }
      PlacementRules.Placement placement = outcome.placement();
      created.add(
          new NewEmployee(
              UUID.randomUUID(),
              UUID.randomUUID(),
              row.employeeNumber(),
              row.givenNames(),
              row.familyName(),
              row.startDate(),
              placement.legalEntityId(),
              placement.siteId(),
              placement.departmentId(),
              placement.costCenterId(),
              placement.teamId()));
    }
    return created;
  }
}
