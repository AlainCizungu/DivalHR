package com.divalhr.keycloak.provisioning;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Durable evidence of each operation (D6): a Keycloak admin event and one sanitized structured log
 * line. Records only the operation, outcome, invitation, target user, tenant and correlation IDs;
 * never an email address, username, token content, credential, link or request body. Target user
 * and tenant appear only once safely resolved.
 */
public final class AuditRecorder {

  private static final Logger LOG = Logger.getLogger("divalhr.provisioning");

  private AuditRecorder() {}

  /** Facts about one operation; null fields are omitted. */
  public record Entry(
      String operation,
      String outcome,
      UUID invitationId,
      String userId,
      UUID tenantId,
      String correlationId) {}

  /**
   * Logs a refused caller (401/403). No admin event: the caller is not an authorized identity, and
   * nothing from the request is parsed to describe it.
   *
   * @param operation operation name
   * @param status 401 or 403
   * @param reason internal reason
   */
  public static void denied(String operation, int status, String reason) {
    LOG.warnf(
        "event=provisioning_denied operation=%s status=%d reason=%s", operation, status, reason);
  }

  /**
   * Records an authorized operation's outcome.
   *
   * @param session Keycloak session
   * @param caller authorized caller
   * @param entry the facts
   * @param type admin-event operation type
   * @param error stable error code, or null on success
   */
  public static void record(
      KeycloakSession session,
      CallerAuthorizer.Caller caller,
      Entry entry,
      OperationType type,
      String error) {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("divalhr.operation", entry.operation());
    details.put("divalhr.outcome", entry.outcome());
    if (entry.invitationId() != null) {
      details.put("divalhr.invitationId", entry.invitationId().toString());
    }
    if (entry.tenantId() != null) {
      details.put("divalhr.tenantId", entry.tenantId().toString());
    }
    if (entry.correlationId() != null) {
      details.put("divalhr.correlationId", entry.correlationId());
    }
    LOG.infof(
        "event=provisioning operation=%s outcome=%s invitationId=%s userId=%s tenantId=%s"
            + " correlationId=%s",
        entry.operation(),
        entry.outcome(),
        entry.invitationId(),
        entry.userId(),
        entry.tenantId(),
        entry.correlationId());
    RealmModel realm = session.getContext().getRealm();
    if (realm == null || !realm.isAdminEventsEnabled()) {
      return;
    }
    AdminEvent event = new AdminEvent();
    event.setId(UUID.randomUUID().toString());
    event.setTime(System.currentTimeMillis());
    event.setRealmId(realm.getId());
    event.setRealmName(realm.getName());
    AuthDetails auth = new AuthDetails();
    auth.setRealmId(realm.getId());
    auth.setRealmName(realm.getName());
    auth.setClientId(caller.client().getId());
    auth.setUserId(caller.serviceAccount().getId());
    if (session.getContext().getConnection() != null) {
      auth.setIpAddress(session.getContext().getConnection().getRemoteAddr());
    }
    event.setAuthDetails(auth);
    event.setOperationType(type);
    event.setResourceType(ResourceType.USER);
    event.setResourcePath(entry.userId() == null ? "users" : "users/" + entry.userId());
    event.setDetails(details);
    if (error != null) {
      event.setError(error);
    }
    EventStoreProvider store = session.getProvider(EventStoreProvider.class);
    if (store != null) {
      // Never the representation: it would contain the email address.
      store.onEvent(event, false);
    }
  }
}
