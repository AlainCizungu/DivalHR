package com.divalhr.core.identity.application;

import com.divalhr.core.identity.domain.Invitation;
import com.divalhr.core.identity.domain.InvitationOrigin;
import com.divalhr.core.identity.internal.JdbcInvitationRepository;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the audit record and outbox event of every invitation state change in the caller's
 * transaction. Metadata and event data are safe by construction: ids, role, locale, dates and
 * counters only; never an email address, address lookup, token, token hash, name, subject in event
 * data, client address or cursor.
 */
@Component
public class InvitationEvents {

  /** Audit resource type. */
  public static final String RESOURCE_TYPE = "invitation";

  /** Event source. */
  public static final String SOURCE = "core-api/identity";

  /** Actor recorded for the expiry job. */
  public static final String EXPIRY_ACTOR = "system:invitation-expiry";

  /** Actor recorded when a bootstrap acceptance is superseded (MVP-014, A2). */
  public static final String SUPERSEDED_ACTOR = JdbcInvitationRepository.SUPERSEDED_BY;

  /** Actor recorded for denied anonymous acceptances. */
  public static final String ANONYMOUS_ACTOR = "anonymous";

  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final JsonMapper json;

  /**
   * Creates the writer.
   *
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param json JSON mapper
   */
  public InvitationEvents(AuditRecorder audit, OutboxWriter outbox, JsonMapper json) {
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
  }

  /**
   * Invitation created.
   *
   * @param invitation new invitation
   * @param actor verified subject
   * @param correlationId correlation ID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void created(Invitation invitation, String actor, String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("role", invitation.role().wireName());
    metadata.put("locale", invitation.locale().tag());
    metadata.put("expiresAt", invitation.expiresAt().toString());
    metadata.put("issueCount", invitation.issueCount());
    metadata.put("origin", invitation.origin().name());
    record(
        bootstrap(invitation) ? "invitation.bootstrap-create" : "invitation.create",
        actor,
        "SUCCESS",
        invitation,
        metadata,
        correlationId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invitationId", invitation.id().toString());
    data.put("role", invitation.role().wireName());
    data.put("locale", invitation.locale().tag());
    data.put("expiresAt", invitation.expiresAt().toString());
    data.put("origin", invitation.origin().name());
    event("identity.invitation-created.v1", invitation, data, correlationId);
  }

  /**
   * Invitation reissued.
   *
   * @param invitation reissued invitation
   * @param actor verified subject
   * @param correlationId correlation ID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reissued(Invitation invitation, String actor, String correlationId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("expiresAt", invitation.expiresAt().toString());
    metadata.put("issueCount", invitation.issueCount());
    metadata.put("origin", invitation.origin().name());
    record(
        bootstrap(invitation) ? "invitation.bootstrap-resend" : "invitation.resend",
        actor,
        "SUCCESS",
        invitation,
        metadata,
        correlationId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("invitationId", invitation.id().toString());
    data.put("expiresAt", invitation.expiresAt().toString());
    data.put("origin", invitation.origin().name());
    event("identity.invitation-reissued.v1", invitation, data, correlationId);
  }

  /**
   * Invitation revoked.
   *
   * @param invitation revoked invitation
   * @param actor verified subject
   * @param correlationId correlation ID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void revoked(Invitation invitation, String actor, String correlationId) {
    record(
        bootstrap(invitation) ? "invitation.bootstrap-revoke" : "invitation.revoke",
        actor,
        "SUCCESS",
        invitation,
        Map.of("role", invitation.role().wireName(), "origin", invitation.origin().name()),
        correlationId);
    event(
        "identity.invitation-revoked.v1",
        invitation,
        Map.of("invitationId", invitation.id().toString(), "origin", invitation.origin().name()),
        correlationId);
  }

  /**
   * A bootstrap acceptance found another tenant administrator and ended without provisioning
   * (MVP-014, A2). Records only ids, role, origin and the reason.
   *
   * @param invitation the bootstrap invitation (now REVOKED by the system)
   * @param correlationId correlation ID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bootstrapSuperseded(Invitation invitation, String correlationId) {
    record(
        "invitation.bootstrap-supersede",
        SUPERSEDED_ACTOR,
        "SUCCESS",
        invitation,
        Map.of(
            "role",
            invitation.role().wireName(),
            "origin",
            invitation.origin().name(),
            "reason",
            "bootstrap_superseded"),
        correlationId);
    event(
        "identity.invitation-revoked.v1",
        invitation,
        Map.of(
            "invitationId",
            invitation.id().toString(),
            "origin",
            invitation.origin().name(),
            "reason",
            "BOOTSTRAP_SUPERSEDED"),
        correlationId);
  }

  /**
   * Invitation accepted.
   *
   * @param invitation accepted invitation
   * @param subject the new member's subject (audit actor only; never event data)
   * @param membershipId new membership
   * @param correlationId correlation ID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void accepted(
      Invitation invitation, String subject, UUID membershipId, String correlationId) {
    record(
        "invitation.accept",
        subject,
        "SUCCESS",
        invitation,
        Map.of(
            "role",
            invitation.role().wireName(),
            "membershipId",
            membershipId.toString(),
            "origin",
            invitation.origin().name()),
        correlationId);
    event(
        "identity.invitation-accepted.v1",
        invitation,
        Map.of(
            "invitationId",
            invitation.id().toString(),
            "membershipId",
            membershipId.toString(),
            "role",
            invitation.role().wireName(),
            "origin",
            invitation.origin().name()),
        correlationId);
  }

  /**
   * Acceptance refused because the address already has an identity (no reason is disclosed).
   *
   * @param invitation invitation (stays pending)
   * @param correlationId correlation ID
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acceptanceDenied(Invitation invitation, String correlationId) {
    record(
        "invitation.accept",
        ANONYMOUS_ACTOR,
        "DENIED",
        invitation,
        Map.of("reason", "not_acceptable"),
        correlationId);
  }

  /**
   * Invitation expired.
   *
   * @param invitation expired invitation
   * @param correlationId correlation ID of the job run
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void expired(Invitation invitation, String correlationId) {
    record("invitation.expire", EXPIRY_ACTOR, "SUCCESS", invitation, Map.of(), correlationId);
    event(
        "identity.invitation-expired.v1",
        invitation,
        Map.of("invitationId", invitation.id().toString()),
        correlationId);
  }

  private static boolean bootstrap(Invitation invitation) {
    return invitation.origin() == InvitationOrigin.PLATFORM_BOOTSTRAP;
  }

  private void record(
      String action,
      String actor,
      String result,
      Invitation invitation,
      Map<String, Object> metadata,
      String correlationId) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            Instant.now(),
            actor,
            action,
            RESOURCE_TYPE,
            invitation.id(),
            invitation.tenant().value(),
            result,
            correlationId,
            metadata,
            Fingerprints.sha256(json.writeValueAsString(afterState(invitation, action, result)))));
  }

  private void event(
      String type, Invitation invitation, Map<String, Object> data, String correlationId) {
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            1,
            invitation.tenant().toString(),
            SOURCE,
            invitation.id().toString(),
            Instant.now().toString(),
            correlationId,
            null,
            data));
  }

  /** Integrity reference: the safe persisted state (no address, token or lookup). */
  private static Map<String, Object> afterState(
      Invitation invitation, String action, String result) {
    Map<String, Object> state = new TreeMap<>();
    state.put("id", invitation.id().toString());
    state.put("tenantId", invitation.tenant().toString());
    state.put("role", invitation.role().wireName());
    state.put("locale", invitation.locale().tag());
    state.put("expiresAt", invitation.expiresAt().toString());
    state.put("issueCount", invitation.issueCount());
    state.put("createdAt", invitation.createdAt().toString());
    state.put("origin", invitation.origin().name());
    state.put("action", action);
    state.put("result", result);
    return state;
  }
}
