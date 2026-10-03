package com.divalhr.core.people.application;

import com.divalhr.core.people.api.EmployeeImportResponse;
import com.divalhr.core.people.api.EmployeeImportRowPageResponse;
import com.divalhr.core.people.domain.ImportColumn;
import com.divalhr.core.people.domain.ImportRecord;
import com.divalhr.core.people.domain.ImportRow;
import com.divalhr.core.people.domain.ImportStatus;
import com.divalhr.core.people.domain.RowError;
import com.divalhr.core.people.domain.RowErrorCode;
import com.divalhr.core.people.domain.RowValues;
import com.divalhr.core.people.internal.JdbcEmployeeImportRepository;
import com.divalhr.core.people.internal.JdbcEmployeeImportRepository.RowFilter;
import com.divalhr.core.people.internal.JdbcEmployeeImportRepository.StoredRow;
import com.divalhr.core.people.internal.JdbcEmployeeRepository;
import com.divalhr.core.people.internal.TransactionLimits;
import com.divalhr.core.platform.csv.CsvCells;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory;
import com.divalhr.core.platform.tenancy.OrganizationPlacementDirectory.Placements;
import com.divalhr.core.platform.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-020: upload, preview, status, discard and template of employee imports (issue #45, decisions
 * E1-E16, amendments A20-1..A20-6).
 *
 * <p>Upload order: the per-subject and per-tenant limits and authorization already ran (before the
 * body was read); the body is read under a byte cap and a deadline, before any transaction; the
 * file and its rows are validated in memory; then one bounded transaction reserves the idempotency
 * key, serializes the tenant's imports, enforces the open-import cap, checks employee numbers and
 * placements, and stages the rows with their audit record. No raw file is stored. Logs and metrics
 * carry counts and codes only.
 */
@Service
public class EmployeeImportService {

  /** Upload operation (idempotency, audit, metrics, rate-limit evidence). */
  public static final String CREATE = "employee-import.create";

  /** Status read operation. */
  public static final String READ = "employee-import.read";

  /** Row preview operation. */
  public static final String ROWS = "employee-import.rows";

  /** Discard operation. */
  public static final String DISCARD = "employee-import.discard";

  /** Template operation. */
  public static final String TEMPLATE = "employee-import.template";

  /** Per-subject bucket shared by upload and commit (A20-4). */
  public static final String SUBJECT_BUCKET = "employee-import";

  /** Per-tenant upload bucket (A20-4). */
  public static final String TENANT_UPLOAD_BUCKET = "employee-import-upload";

  /** Per-tenant commit bucket (A20-4). */
  public static final String TENANT_COMMIT_BUCKET = "employee-import-commit";

  /** Row outcome counter (closed label set). */
  public static final String ROW_METRIC = "divalhr.employee_import.rows";

  private static final Logger LOG = LoggerFactory.getLogger("divalhr.employee-import");
  private static final Pattern IMPORT_ID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern ROW_LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(CREATE, "employee_import", "create", "created", 201);

  private final ImportFileReader reader;
  private final RowValidator validator;
  private final JdbcEmployeeImportRepository imports;
  private final JdbcEmployeeRepository employees;
  private final OrganizationPlacementDirectory placements;
  private final EmployeeImportEvents events;
  private final IdempotentOperation operations;
  private final TransactionLimits limits;
  private final CursorCodec cursors;
  private final EmployeeImportProperties properties;
  private final OperationMetrics metrics;
  private final MeterRegistry meters;
  private final TransactionTemplate transactions;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param reader file reader
   * @param validator row validator
   * @param imports import repository
   * @param employees employee repository
   * @param placements placement port (tenant module)
   * @param events audit and outbox
   * @param operations idempotent operation flow
   * @param limits transaction limits
   * @param cursors cursor codec
   * @param properties settings
   * @param metrics operation metrics
   * @param meters meter registry
   * @param transactionManager transaction manager
   */
  @Autowired
  public EmployeeImportService(
      ImportFileReader reader,
      RowValidator validator,
      JdbcEmployeeImportRepository imports,
      JdbcEmployeeRepository employees,
      OrganizationPlacementDirectory placements,
      EmployeeImportEvents events,
      IdempotentOperation operations,
      TransactionLimits limits,
      CursorCodec cursors,
      EmployeeImportProperties properties,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this(
        reader,
        validator,
        imports,
        employees,
        placements,
        events,
        operations,
        limits,
        cursors,
        properties,
        metrics,
        meters,
        transactionManager,
        Clock.systemUTC());
  }

  EmployeeImportService(
      ImportFileReader reader,
      RowValidator validator,
      JdbcEmployeeImportRepository imports,
      JdbcEmployeeRepository employees,
      OrganizationPlacementDirectory placements,
      EmployeeImportEvents events,
      IdempotentOperation operations,
      TransactionLimits limits,
      CursorCodec cursors,
      EmployeeImportProperties properties,
      OperationMetrics metrics,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.reader = reader;
    this.validator = validator;
    this.imports = imports;
    this.employees = employees;
    this.placements = placements;
    this.events = events;
    this.operations = operations;
    this.limits = limits;
    this.cursors = cursors;
    this.properties = properties;
    this.metrics = metrics;
    this.meters = meters;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setTimeout(
        (int) Math.max(1, properties.validationTransactionTimeout().toSeconds()));
    this.clock = clock;
  }

  // ------------------------------------------------------------------------------------------
  // Upload
  // ------------------------------------------------------------------------------------------

  /**
   * Uploads, validates and stages a file, or replays an identical earlier upload.
   *
   * @param tenant verified tenant
   * @param actor verified subject
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body request body (read here, after authorization and the request limits)
   * @param correlationId correlation ID
   * @return the import summary and whether it was replayed
   */
  public IdempotentOperation.Result<EmployeeImportResponse> create(
      TenantId tenant,
      String actor,
      String idempotencyKey,
      InputStream body,
      String correlationId) {
    FieldErrors errors = new FieldErrors();
    requireKey(errors, idempotencyKey);
    operations.validated(
        SPEC,
        () -> {
          errors.throwIfAny();
          return Boolean.TRUE;
        });
    byte[] bytes =
        operations.validated(
            SPEC,
            () -> UploadBody.read(body, properties.maxBytes(), properties.uploadTimeout(), clock));
    ImportFile file = operations.validated(SPEC, () -> reader.read(bytes, properties.maxRows()));
    List<RowValidator.Checked> checked = new ArrayList<>(file.rows().size());
    for (ImportFile.RawRow raw : file.rows()) {
      checked.add(validator.check(raw));
    }
    List<ImportRow> rows = repeatedNumbers(checked);
    Map<String, Object> canonical = new TreeMap<>();
    canonical.put("tenantId", tenant.toString());
    canonical.put("fileSha256", file.fileSha256());
    IdempotentOperation.Result<EmployeeImportResponse> result =
        bounded(
            () ->
                operations.execute(
                    SPEC,
                    actor,
                    idempotencyKey,
                    canonical,
                    EmployeeImportResponse.class,
                    properties.validationTransactionTimeout(),
                    () -> stage(tenant, actor, file, rows, correlationId)));
    if (!result.replayed()) {
      LOG.atInfo()
          .addKeyValue("operation", CREATE)
          .addKeyValue("totalRows", result.body().totalRows())
          .addKeyValue("validRows", result.body().validRows())
          .addKeyValue("invalidRows", result.body().invalidRows())
          .addKeyValue("delimiter", result.body().delimiter())
          .addKeyValue("headerLanguage", result.body().headerLanguage())
          .log("employee_import_staged");
      countRows("valid", result.body().validRows());
      countRows("invalid", result.body().invalidRows());
    }
    return result;
  }

  /** Every occurrence of an employee number repeated in the file is invalid (E8). */
  private static List<ImportRow> repeatedNumbers(List<RowValidator.Checked> checked) {
    Map<String, Integer> occurrences = new HashMap<>();
    for (RowValidator.Checked row : checked) {
      if (row.employeeNumber() != null) {
        occurrences.merge(row.employeeNumber(), 1, Integer::sum);
      }
    }
    List<ImportRow> rows = new ArrayList<>(checked.size());
    for (RowValidator.Checked row : checked) {
      boolean repeated = row.employeeNumber() != null && occurrences.get(row.employeeNumber()) > 1;
      rows.add(
          repeated
              ? row.row()
                  .withErrors(
                      List.of(
                          new RowError(
                              ImportColumn.EMPLOYEE_NUMBER,
                              RowErrorCode.ROW_EMPLOYEE_NUMBER_REPEATED)))
              : row.row());
    }
    return sorted(rows);
  }

  private IdempotentOperation.Completed<EmployeeImportResponse> stage(
      TenantId tenant,
      String actor,
      ImportFile file,
      List<ImportRow> validated,
      String correlationId) {
    limits.apply(properties.validationStatementTimeout());
    Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    imports.lockTenant(tenant);
    if (imports.countOpen(tenant, now) >= properties.maxOpenImports()) {
      throw new ApiException(ErrorCode.IMPORT_LIMIT_REACHED, Map.of());
    }
    List<ImportRow> rows = tenantChecks(tenant, validated);
    int valid = (int) rows.stream().filter(ImportRow::valid).count();
    ImportRecord record =
        new ImportRecord(
            UUID.randomUUID(),
            tenant.value(),
            ImportStatus.VALIDATED,
            now,
            actor,
            now.plus(properties.stagingTtl()),
            file.fileSha256(),
            PreviewDigest.of(rows),
            file.delimiter(),
            file.headerLanguage(),
            rows.size(),
            valid,
            rows.size() - valid,
            null,
            null);
    imports.insert(tenant, record, rows);
    events.uploaded(tenant, actor, record, correlationId);
    return new IdempotentOperation.Completed<>(
        EmployeeImportResponse.from(record, errorCounts(rows)), record.id(), Outcome.CREATED);
  }

  /**
   * Existing employee numbers (any row with a well-formed number) and placements (otherwise valid
   * rows) in the caller's tenant, with batched lookups.
   *
   * @param tenant verified tenant
   * @param rows rows after the row and cross-row checks
   * @return rows with tenant errors added
   */
  List<ImportRow> tenantChecks(TenantId tenant, List<ImportRow> rows) {
    Set<String> numbers = new HashSet<>();
    List<RowValues> valid = new ArrayList<>();
    for (ImportRow row : rows) {
      if (row.valid()) {
        numbers.add(row.values().employeeNumber());
        valid.add(row.values());
      }
    }
    Set<String> existing = employees.existingNumbers(tenant, numbers);
    Placements units =
        valid.isEmpty() ? null : placements.resolve(tenant, PlacementRules.codesOf(valid));
    List<ImportRow> checked = new ArrayList<>(rows.size());
    for (ImportRow row : rows) {
      if (!row.valid()) {
        checked.add(row);
        continue;
      }
      List<RowError> more = new ArrayList<>();
      if (existing.contains(row.values().employeeNumber())) {
        more.add(
            new RowError(ImportColumn.EMPLOYEE_NUMBER, RowErrorCode.ROW_EMPLOYEE_NUMBER_EXISTS));
      }
      more.addAll(PlacementRules.check(row.values(), units).errors());
      more.sort((a, b) -> a.column().compareTo(b.column()));
      checked.add(row.withErrors(more));
    }
    return checked;
  }

  private static List<ImportRow> sorted(List<ImportRow> rows) {
    List<ImportRow> result = new ArrayList<>(rows.size());
    for (ImportRow row : rows) {
      if (row.valid()) {
        result.add(row);
      } else {
        List<RowError> errors = new ArrayList<>(row.errors());
        errors.sort((a, b) -> a.column().compareTo(b.column()));
        result.add(new ImportRow(row.rowNumber(), errors, null));
      }
    }
    return result;
  }

  private static Map<RowErrorCode, Integer> errorCounts(List<ImportRow> rows) {
    Map<RowErrorCode, Integer> counts = new TreeMap<>();
    for (ImportRow row : rows) {
      for (RowError error : row.errors()) {
        counts.merge(error.code(), 1, Integer::sum);
      }
    }
    return counts;
  }

  // ------------------------------------------------------------------------------------------
  // Read, rows, discard, template
  // ------------------------------------------------------------------------------------------

  /**
   * Reads an import of the tenant.
   *
   * @param tenant verified tenant
   * @param importId raw path value
   * @return the import
   */
  public EmployeeImportResponse read(TenantId tenant, String importId) {
    UUID id = importId(importId);
    ImportRecord record = imports.find(tenant, id).orElseThrow(EmployeeImportService::notFound);
    metrics.record(READ, Outcome.LISTED);
    return EmployeeImportResponse.from(record, new TreeMap<>(imports.errorCounts(tenant, id)));
  }

  /**
   * A page of an import's rows.
   *
   * @param tenant verified tenant
   * @param importId raw path value
   * @param status raw status filter
   * @param cursor opaque cursor
   * @param limit raw page size
   * @return a page
   */
  public EmployeeImportRowPageResponse rows(
      TenantId tenant, String importId, String status, String cursor, String limit) {
    UUID id = importId(importId);
    FieldErrors errors = new FieldErrors();
    RowFilter filter = RowFilter.ALL;
    if (status != null) {
      switch (status) {
        case "all" -> filter = RowFilter.ALL;
        case "valid" -> filter = RowFilter.VALID;
        case "invalid" -> filter = RowFilter.INVALID;
        default -> errors.add("status", FieldErrors.Constraint.FORMAT);
      }
    }
    int pageSize = 50;
    if (limit != null) {
      if (!ROW_LIMIT.matcher(limit).matches()) {
        errors.add("limit", FieldErrors.Constraint.FORMAT);
      } else {
        pageSize = Integer.parseInt(limit);
        if (pageSize < 1 || pageSize > 100) {
          errors.add("limit", FieldErrors.Constraint.RANGE);
        }
      }
    }
    errors.throwIfAny();
    imports.find(tenant, id).orElseThrow(EmployeeImportService::notFound);
    CursorScope scope =
        new CursorScope(
            ROWS,
            tenant,
            Map.of(
                "import", id.toString(),
                "status", filter.name(),
                "limit", Integer.toString(pageSize)));
    int after = 0;
    if (cursor != null) {
      KeysetPosition position = cursors.decode(cursor, scope);
      if (!position.id().equals(id) || !position.code().matches("^[0-9]{8}$")) {
        throw new ApiException(ErrorCode.CURSOR_INVALID, Map.of());
      }
      after = Integer.parseInt(position.code());
    }
    List<StoredRow> found = imports.rows(tenant, id, filter, after, pageSize + 1);
    List<StoredRow> shown = found.subList(0, Math.min(found.size(), pageSize));
    String next = null;
    if (found.size() > pageSize) {
      StoredRow last = shown.get(shown.size() - 1);
      next = cursors.encode(scope, new KeysetPosition(String.format("%08d", last.rowNumber()), id));
    }
    metrics.record(ROWS, Outcome.LISTED);
    return new EmployeeImportRowPageResponse(
        shown.stream()
            .map(
                row ->
                    new EmployeeImportRowPageResponse.Row(
                        row.rowNumber(),
                        row.status(),
                        row.errors().stream()
                            .map(e -> EmployeeImportRowPageResponse.Error.of(e.column(), e.code()))
                            .toList(),
                        row.values() == null
                            ? null
                            : EmployeeImportRowPageResponse.Values.of(row.values())))
            .toList(),
        next);
  }

  /**
   * Discards an open import: its staged values are erased. Discarding a discarded import returns it
   * unchanged.
   *
   * @param tenant verified tenant
   * @param actor verified subject
   * @param importId raw path value
   * @param correlationId correlation ID
   * @return the import
   */
  public EmployeeImportResponse discard(
      TenantId tenant, String actor, String importId, String correlationId) {
    UUID id = importId(importId);
    EmployeeImportResponse response =
        bounded(
            () ->
                transactions.execute(
                    status -> {
                      limits.apply(properties.validationStatementTimeout());
                      ImportRecord record =
                          imports.lock(tenant, id).orElseThrow(EmployeeImportService::notFound);
                      if (record.status() == ImportStatus.DISCARDED) {
                        return EmployeeImportResponse.from(
                            record, new TreeMap<>(imports.errorCounts(tenant, id)));
                      }
                      Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
                      if (!record.openAt(now)) {
                        throw notCommittable(record, now);
                      }
                      imports.markClosed(tenant, id, ImportStatus.DISCARDED, now);
                      events.closed(tenant, actor, DISCARD, record, now, correlationId);
                      return EmployeeImportResponse.from(
                          imports.find(tenant, id).orElseThrow(),
                          new TreeMap<>(imports.errorCounts(tenant, id)));
                    }));
    metrics.record(DISCARD, Outcome.UPDATED);
    return response;
  }

  /**
   * The header-only template in one language: UTF-8 with a byte-order mark, comma-delimited, every
   * cell neutralized and quoted ({@link CsvCells}).
   *
   * @param lang raw {@code lang} query value
   * @return the CSV bytes
   */
  public byte[] template(String lang) {
    if (!"fr".equals(lang) && !"en".equals(lang)) {
      new FieldErrors().add("lang", FieldErrors.Constraint.FORMAT).throwIfAny();
    }
    boolean french = "fr".equals(lang);
    String header =
        CsvCells.line(
            Arrays.stream(ImportColumn.values()).map(column -> column.label(french)).toList(), ',');
    metrics.record(TEMPLATE, Outcome.LISTED);
    return ("﻿" + header).getBytes(StandardCharsets.UTF_8);
  }

  // ------------------------------------------------------------------------------------------
  // Shared
  // ------------------------------------------------------------------------------------------

  /**
   * Parses an import ID; malformed IDs are indistinguishable from unknown ones.
   *
   * @param raw raw path value
   * @return the ID
   */
  static UUID importId(String raw) {
    if (raw == null || !IMPORT_ID.matcher(raw).matches()) {
      throw notFound();
    }
    return UUID.fromString(raw);
  }

  static ApiException notFound() {
    return new ApiException(ErrorCode.EMPLOYEE_IMPORT_NOT_FOUND, Map.of());
  }

  static ApiException notCommittable(ImportRecord record, Instant now) {
    ImportStatus status =
        record.status() == ImportStatus.VALIDATED && !now.isBefore(record.expiresAt())
            ? ImportStatus.EXPIRED
            : record.status();
    return new ApiException(ErrorCode.IMPORT_NOT_COMMITTABLE, Map.of("status", status.name()));
  }

  static void requireKey(FieldErrors errors, String key) {
    if (key == null || key.isBlank()) {
      errors.add(IdempotencyKeys.HEADER, FieldErrors.Constraint.REQUIRED);
    } else if (!IdempotencyKeys.isWellFormed(key)) {
      errors.add(IdempotencyKeys.HEADER, FieldErrors.Constraint.FORMAT);
    }
  }

  /**
   * Runs database work and turns a statement, lock or transaction timeout into {@code 503
   * IMPORT_TIMEOUT}: the transaction was rolled back entirely (A20-5).
   *
   * @param work the work
   * @param <T> result type
   * @return the result
   */
  static <T> T bounded(Supplier<T> work) {
    try {
      return work.get();
    } catch (ApiException expected) {
      throw expected;
    } catch (RuntimeException failure) {
      if (DatabaseTimeouts.isTimeout(failure)) {
        throw new ApiException(ErrorCode.IMPORT_TIMEOUT, Map.of());
      }
      throw failure;
    }
  }

  private void countRows(String outcome, int count) {
    if (count > 0) {
      Counter.builder(ROW_METRIC)
          .description("Employee import rows by outcome")
          .tag("outcome", outcome)
          .register(meters)
          .increment(count);
    }
  }
}
