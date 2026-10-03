package com.divalhr.core.people.api;

import com.divalhr.core.people.domain.ImportRecord;
import com.divalhr.core.people.domain.ImportStatus;
import com.divalhr.core.people.domain.RowErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An employee import (MVP-020): status, counts and the preview digest. Mirrors {@code
 * EmployeeImport} in {@code docs/API-SPEC.yaml}. Never contains personal data, so it can be stored
 * for idempotent replay.
 *
 * @param id import ID
 * @param status status
 * @param createdAt creation time
 * @param expiresAt when an open import's staged values are erased
 * @param totalRows data rows
 * @param validRows valid rows
 * @param invalidRows invalid rows
 * @param createdCount employees created, once committed
 * @param notImportedCount rows not imported, once committed
 * @param delimiter {@code COMMA} or {@code SEMICOLON}
 * @param headerLanguage {@code fr}, {@code en} or {@code mixed}
 * @param previewDigest preview digest v1
 * @param requiresAcknowledgement whether some rows are invalid
 * @param errorCounts rows per error code
 */
@Schema(name = "EmployeeImport")
public record EmployeeImportResponse(
    UUID id,
    ImportStatus status,
    Instant createdAt,
    Instant expiresAt,
    int totalRows,
    int validRows,
    int invalidRows,
    @JsonInclude(JsonInclude.Include.ALWAYS) Integer createdCount,
    @JsonInclude(JsonInclude.Include.ALWAYS) Integer notImportedCount,
    String delimiter,
    String headerLanguage,
    String previewDigest,
    boolean requiresAcknowledgement,
    List<ErrorCount> errorCounts) {

  /** Copies the counts. */
  public EmployeeImportResponse {
    errorCounts = List.copyOf(errorCounts);
  }

  /**
   * Rows with one error code.
   *
   * @param code row error code
   * @param count rows
   */
  @Schema(name = "EmployeeImportErrorCount")
  public record ErrorCount(RowErrorCode code, int count) {}

  /**
   * Builds the response.
   *
   * @param record the import
   * @param errorCounts rows per error code
   * @return response
   */
  public static EmployeeImportResponse from(
      ImportRecord record, Map<RowErrorCode, Integer> errorCounts) {
    boolean committed = record.status() == ImportStatus.COMMITTED;
    return new EmployeeImportResponse(
        record.id(),
        record.status(),
        record.createdAt(),
        record.expiresAt(),
        record.totalRows(),
        record.validRows(),
        record.invalidRows(),
        committed ? record.createdCount() : null,
        committed ? record.totalRows() - record.createdCount() : null,
        record.delimiter(),
        record.headerLanguage(),
        record.previewDigest(),
        record.invalidRows() > 0,
        errorCounts.entrySet().stream()
            .map(entry -> new ErrorCount(entry.getKey(), entry.getValue()))
            .toList());
  }
}
