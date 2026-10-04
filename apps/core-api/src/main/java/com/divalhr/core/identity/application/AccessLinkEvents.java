package com.divalhr.core.identity.application;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Audit and outbox records of employee access links and access revocations (MVP-022), written in
 * the caller's transaction (fail closed).
 *
 * <ul>
 *   <li>Audit metadata is an allow-list (A22-6): {@code schemaVersion} plus versions, attempt
 *       numbers, closed state and outcome codes and counts. Integrity hashes go to the record's own
 *       {@code after_state_sha256}, never to metadata.
 *   <li>Outbox data is identifiers only.
 *   <li>Never an address, subject, role, name, date or reason.
 * </ul>
 */
@Component
public class AccessLinkEvents {

  /** Schema version of metadata and event data. */
  public static final int VERSION = 1;

  /** Actor recorded for background transitions. */
  public static final String SYSTEM_ACTOR = "system:access-revocation";

  /** An employee was linked to a membership. */
  public static final String LINK_CREATED = "identity.employee-access-link.created.v1";

  /** An employee's link was removed. */
  public static final String LINK_REMOVED = "identity.employee-access-link.removed.v1";

  /** A membership's DivalHR access ended. */
  public static final String ACCESS_REVOKED = "identity.membership.access-revoked.v1";

  static final String SOURCE = "core-api/identity";
  static final String LINK_RESOURCE = "employee-access-link";
  static final String REVOCATION_RESOURCE = "access-revocation";
  static final String MEMBERSHIP_RESOURCE = "tenant-membership";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder
   * @param outbox outbox writer
   */
  public AccessLinkEvents(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * A link was created or removed.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param action {@code employee-access-link.create|remove}
   * @param eventType {@link #LINK_CREATED} or {@link #LINK_REMOVED}
   * @param linkId link
   * @param employeeId employee
   * @param membershipId membership
   * @param linkVersion version after the write
   * @param correlationId correlation ID
   */
  void link(
      TenantId tenant,
      String actor,
      String action,
      String eventType,
      UUID linkId,
      UUID employeeId,
      UUID membershipId,
      long linkVersion,
      String correlationId) {
    Instant now = now();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", VERSION);
    metadata.put("linkVersion", linkVersion);
    record(
        tenant,
        actor,
        action,
        LINK_RESOURCE,
        linkId,
        metadata,
        sha256("link", linkId, employeeId, membershipId, Long.toString(linkVersion)),
        correlationId,
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("employeeId", employeeId.toString());
    data.put("membershipId", membershipId.toString());
    data.put("linkId", linkId.toString());
    publish(tenant, eventType, employeeId, data, correlationId, now);
  }

  /**
   * A revocation was recorded by a separation; when it is effective at once the membership's
   * access-revoked records follow.
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param revocationId revocation
   * @param membershipId membership
   * @param effective whether access ended at once
   * @param correlationId correlation ID
   */
  void requested(
      TenantId tenant,
      String actor,
      UUID revocationId,
      UUID membershipId,
      boolean effective,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", VERSION);
    metadata.put("state", effective ? "IDP_PENDING" : "SCHEDULED");
    record(
        tenant,
        actor,
        "access-revocation.request",
        REVOCATION_RESOURCE,
        revocationId,
        metadata,
        sha256("revocation", revocationId, membershipId, effective ? "effective" : "scheduled"),
        correlationId,
        now());
    if (effective) {
      revoked(tenant, actor, revocationId, membershipId, correlationId);
    }
  }

  /**
   * A membership's DivalHR access ended (at once, or when the worker observed its instant pass).
   *
   * @param tenant tenant
   * @param actor verified subject, or {@link #SYSTEM_ACTOR}
   * @param revocationId revocation
   * @param membershipId membership
   * @param correlationId correlation ID
   */
  void revoked(
      TenantId tenant, String actor, UUID revocationId, UUID membershipId, String correlationId) {
    Instant now = now();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", VERSION);
    record(
        tenant,
        actor,
        "tenant-membership.access-revoke",
        MEMBERSHIP_RESOURCE,
        membershipId,
        metadata,
        sha256("membership-revoked", membershipId, revocationId),
        correlationId,
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("membershipId", membershipId.toString());
    data.put("revocationId", revocationId.toString());
    publish(tenant, ACCESS_REVOKED, membershipId, data, correlationId, now);
  }

  /**
   * A revocation changed state outside the identity-provider attempt (cancelled, re-queued).
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param action {@code access-revocation.cancel|retry}
   * @param revocationId revocation
   * @param fromState state before
   * @param toState state after
   * @param correlationId correlation ID
   */
  void transition(
      TenantId tenant,
      String actor,
      String action,
      UUID revocationId,
      String fromState,
      String toState,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", VERSION);
    metadata.put("fromState", fromState);
    metadata.put("toState", toState);
    record(
        tenant,
        actor,
        action,
        REVOCATION_RESOURCE,
        revocationId,
        metadata,
        sha256("revocation-transition", revocationId, fromState, toState),
        correlationId,
        now());
  }

  /**
   * The outcome of one identity-provider step (A22-5): only the closed outcome code and attempt
   * number are recorded, never a subject or a provider response.
   *
   * @param tenant tenant
   * @param action {@code access-revocation.idp-succeeded|idp-failed|manual-intervention}
   * @param revocationId revocation
   * @param attempt attempt number
   * @param outcomeCode closed outcome code
   * @param correlationId correlation ID
   */
  void providerOutcome(
      TenantId tenant,
      String action,
      UUID revocationId,
      int attempt,
      String outcomeCode,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", VERSION);
    metadata.put("attempt", attempt);
    metadata.put("outcomeCode", outcomeCode);
    record(
        tenant,
        SYSTEM_ACTOR,
        action,
        REVOCATION_RESOURCE,
        revocationId,
        metadata,
        sha256("revocation-outcome", revocationId, Integer.toString(attempt), outcomeCode),
        correlationId,
        now());
  }

  /**
   * A disclosure of link data (reads and lookups).
   *
   * @param tenant tenant
   * @param actor verified subject
   * @param action the read operation
   * @param employeeId employee
   * @param resultCount items disclosed
   * @param ids disclosed IDs (hashed only)
   * @param correlationId correlation ID
   */
  void disclosed(
      TenantId tenant,
      String actor,
      String action,
      UUID employeeId,
      int resultCount,
      List<UUID> ids,
      String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", VERSION);
    metadata.put("resultCount", resultCount);
    record(
        tenant,
        actor,
        action,
        "employee",
        employeeId,
        metadata,
        sha256(action, ids.toArray()),
        correlationId,
        now());
  }

  private void record(
      TenantId tenant,
      String actor,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> metadata,
      String hash,
      String correlationId,
      Instant now) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            actor,
            action,
            resourceType,
            resourceId,
            tenant.value(),
            "SUCCESS",
            correlationId,
            metadata,
            hash));
  }

  private void publish(
      TenantId tenant,
      String eventType,
      UUID subjectId,
      Map<String, Object> data,
      String correlationId,
      Instant now) {
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            VERSION,
            tenant.toString(),
            SOURCE,
            subjectId.toString(),
            now.toString(),
            correlationId,
            null,
            data));
  }

  /** SHA-256 of a versioned canonical text of opaque identifiers and closed codes. */
  static String sha256(String kind, Object... parts) {
    StringBuilder text = new StringBuilder("divalhr.access.v").append(VERSION).append('|');
    text.append(kind);
    for (Object part : parts) {
      text.append('|').append(part == null ? "-" : part.toString());
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
