package com.divalhr.core.people.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An employee import's stored summary (MVP-020). No personal data.
 *
 * @param id import ID
 * @param tenantId owning tenant
 * @param status lifecycle status
 * @param createdAt creation time
 * @param createdBy verified subject of the uploader
 * @param expiresAt when an open import's staged values are erased
 * @param fileSha256 SHA-256 of the uploaded bytes
 * @param previewDigest preview digest v1
 * @param delimiter {@code COMMA} or {@code SEMICOLON}
 * @param headerLanguage {@code fr}, {@code en} or {@code mixed}
 * @param totalRows data rows
 * @param validRows valid rows
 * @param invalidRows invalid rows
 * @param createdCount employees created, once committed
 * @param closedAt when the import closed, or {@code null} while open
 */
public record ImportRecord(
    UUID id,
    UUID tenantId,
    ImportStatus status,
    Instant createdAt,
    String createdBy,
    Instant expiresAt,
    String fileSha256,
    String previewDigest,
    String delimiter,
    String headerLanguage,
    int totalRows,
    int validRows,
    int invalidRows,
    Integer createdCount,
    Instant closedAt) {

  /** Requires the identifying components. */
  public ImportRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(status, "status");
  }

  /**
   * Whether the import can still be committed or discarded.
   *
   * @param now current time
   * @return true when validated and not yet expired
   */
  public boolean openAt(Instant now) {
    return status == ImportStatus.VALIDATED && now.isBefore(expiresAt);
  }
}
