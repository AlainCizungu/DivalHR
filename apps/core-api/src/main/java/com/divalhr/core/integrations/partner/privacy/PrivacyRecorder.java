package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit records and internal outbox events of LAUNCH-001 privacy governance (ADR 0046). Metadata is
 * an allow-list of identifiers, closed codes and counts. It never carries a name, an email address,
 * a phone number, a disclosed value, a free text, a note, a package, a digest of personal data or a
 * key. A failing audit or outbox write fails the whole transaction.
 */
@Component
public class PrivacyRecorder {

  /** Schema version of the audit metadata and event payloads. */
  public static final int SCHEMA_VERSION = 1;

  /** The platform scope: platform operations belong to no tenant. */
  public static final UUID PLATFORM_SCOPE = new UUID(0L, 0L);

  private static final String SOURCE = "core-api/integrations";

  private static final Set<String> ALLOWED =
      Set.of(
          "schemaVersion",
          "policyId",
          "requestId",
          "holdId",
          "purgeId",
          "applicationId",
          "referralId",
          "state",
          "fromState",
          "version",
          "requestType",
          "reasonCode",
          "holdReason",
          "itemCount",
          "stampedCount",
          "actorRole");

  private final AuditRecorder audit;
  private final OutboxWriter outbox;

  /**
   * Creates the recorder.
   *
   * @param audit audit recorder (mandatory transaction)
   * @param outbox outbox writer (mandatory transaction)
   */
  public PrivacyRecorder(AuditRecorder audit, OutboxWriter outbox) {
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * Records one privacy change.
   *
   * @param actor verified subject
   * @param action audit action name (for example {@code privacy.policy.create})
   * @param eventType internal outbox event type
   * @param tenantId the tenant of the affected data, or {@link #PLATFORM_SCOPE}
   * @param resourceType audit resource type
   * @param resourceId internal resource id
   * @param correlationId request correlation id
   * @param fields allow-listed metadata
   * @param at server instant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      String actor,
      String action,
      String eventType,
      UUID tenantId,
      String resourceType,
      UUID resourceId,
      String correlationId,
      Map<String, Object> fields,
      Instant at) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", SCHEMA_VERSION);
    data.putAll(fields);
    if (!ALLOWED.containsAll(data.keySet())) {
      throw new IllegalStateException("privacy audit data outside the allow-list");
    }
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            at,
            actor,
            action,
            resourceType,
            resourceId,
            tenantId,
            "SUCCESS",
            correlationId,
            data,
            Fingerprints.sha256(
                "DIVALHR-PRIVACY\nversion=1\naction="
                    + action
                    + "\nresource="
                    + resourceId
                    + "\nstate="
                    + data.getOrDefault("state", "-")
                    + "\nrowVersion="
                    + data.getOrDefault("version", "-")
                    + "\n")));
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            SCHEMA_VERSION,
            tenantId.toString(),
            SOURCE,
            resourceId.toString(),
            at.toString(),
            correlationId,
            null,
            data));
  }
}
