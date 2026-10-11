package com.divalhr.core.people.leave.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The revocation of a leave approval substitute appointment (MVP-041G, V24): append-only, one per
 * appointment, effective immediately. Restricted HR: the reason is returned to tenant
 * administrators only.
 *
 * @param id revocation
 * @param substitutionId the revoked appointment
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason, as written
 * @param revokedAt revocation time
 */
public record LeaveSubstitutionRevocation(
    UUID id, UUID substitutionId, String reasonLocale, String reason, Instant revokedAt) {

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveSubstitutionRevocation[id=" + id + "]";
  }
}
