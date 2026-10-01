package com.divalhr.keycloak.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.keycloak.authentication.actiontoken.execactions.ExecuteActionsActionToken;
import org.keycloak.credential.CredentialModel;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.resources.LoginActionsService;

/**
 * The three invitation-keyed operations of {@code packages/shared-contracts/openapi/
 * keycloak-provisioning.yaml}. Every method authorizes first; only then is the path validated, the
 * body read or any identity looked up (A2). Deliberately not a root resource (no class-level
 * {@code @Path}): Keycloak serves it only through {@link ProvisioningResourceProvider}.
 */
public class ProvisioningResource {

  /** Path of the invitation identity, relative to {@code /realms/{realm}/divalhr-provisioning}. */
  static final String IDENTITY = "v1/invitations/{invitationId}/identity";

  /** Admin-only user-profile attribute linking an identity to its invitation (MVP-010). */
  static final String INVITATION_ATTRIBUTE = "divalhr_invitation_id";

  /** Tenant attribute mapped into tokens. */
  static final String TENANT_ATTRIBUTE = "tenant_id";

  /** Retryable outcome of a creation race whose committed state is not yet settled. */
  static final String BUSY = "IDENTITY_BUSY";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final KeycloakSession session;
  private final ProvisioningConfig config;

  /**
   * Creates the resource for one request.
   *
   * @param session Keycloak session
   * @param config extension configuration
   */
  public ProvisioningResource(KeycloakSession session, ProvisioningConfig config) {
    this.session = session;
    this.config = config;
  }

  /**
   * Creates, or confirms, the invitation identity.
   *
   * @param invitationId path value
   * @param contentType request media type
   * @param contentLength declared length
   * @param correlation correlation ID header
   * @param body request body
   * @return response
   */
  @PUT
  @Path(IDENTITY)
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Response provision(
      @PathParam("invitationId") String invitationId,
      @HeaderParam("Content-Type") String contentType,
      @HeaderParam("Content-Length") String contentLength,
      @HeaderParam("X-Correlation-Id") String correlation,
      InputStream body) {
    String operation = "identity.create";
    CallerAuthorizer.Caller caller;
    try {
      caller = CallerAuthorizer.authorize(session, config);
    } catch (CallerAuthorizer.Denied denied) {
      return deny(operation, denied);
    }
    String correlationId = RequestBodies.correlationId(correlation).orElse(null);
    UUID invitation;
    RequestBodies.Provision request;
    try {
      invitation = RequestBodies.invitationId(invitationId);
      request =
          RequestBodies.provision(
              RequestBodies.readObject(contentType, length(contentLength), body));
    } catch (RequestBodies.Rejected rejected) {
      return refuse(
          caller,
          operation,
          null,
          null,
          null,
          correlationId,
          OperationType.CREATE,
          rejected.code(),
          rejected.status());
    }
    RealmModel realm = session.getContext().getRealm();
    Optional<GroupModel> group = roleGroup(realm, request.role());
    if (group.isEmpty()) {
      return refuse(
          caller,
          operation,
          invitation,
          null,
          request.tenantId(),
          correlationId,
          OperationType.CREATE,
          "IDENTITY_CONFLICT",
          409);
    }
    List<UserModel> existing = findByInvitation(realm, invitation);
    if (existing.size() > 1) {
      return refuse(
          caller,
          operation,
          invitation,
          null,
          request.tenantId(),
          correlationId,
          OperationType.CREATE,
          "IDENTITY_AMBIGUOUS",
          409);
    }
    if (existing.size() == 1) {
      UserModel user = existing.get(0);
      if (matches(user, request)) {
        AuditRecorder.record(
            session,
            caller,
            new AuditRecorder.Entry(
                operation,
                "confirmed",
                invitation,
                user.getId(),
                request.tenantId(),
                correlationId),
            OperationType.CREATE,
            null);
        return json(200, Map.of("subject", user.getId()));
      }
      return refuse(
          caller,
          operation,
          invitation,
          user.getId(),
          request.tenantId(),
          correlationId,
          OperationType.CREATE,
          "IDENTITY_CONFLICT",
          409);
    }
    // Every check happens before the first mutation. An identical call may have committed between
    // the invitation lookup above and this check: an address held by this invitation's own
    // identity is settled like a replay, never reported as a conflict (PR #32 review).
    UserModel holder = addressHolder(realm, request.email());
    if (holder != null
        && List.of(invitation.toString())
            .equals(holder.getAttributeStream(INVITATION_ATTRIBUTE).toList())
        && matches(holder, request)) {
      AuditRecorder.record(
          session,
          caller,
          new AuditRecorder.Entry(
              operation,
              "confirmed",
              invitation,
              holder.getId(),
              request.tenantId(),
              correlationId),
          OperationType.CREATE,
          null);
      return json(200, Map.of("subject", holder.getId()));
    }
    if (holder != null) {
      return refuse(
          caller,
          operation,
          invitation,
          null,
          request.tenantId(),
          correlationId,
          OperationType.CREATE,
          "IDENTITY_CONFLICT",
          409);
    }
    UserModel user;
    try {
      // No realm default roles or default groups, and no default required actions (PR #32
      // review): the identity's only rights come from the role group joined below.
      user = session.users().addUser(realm, null, request.email(), false, false);
    } catch (ModelDuplicateException raced) {
      // A concurrent call committed the same username first. This transaction can no longer read
      // or write; settle the outcome from committed state in a fresh one.
      session.getTransactionManager().setRollbackOnly();
      return afterRace(caller, operation, invitation, request, correlationId);
    }
    user.setEmail(request.email());
    user.setEmailVerified(true);
    user.setEnabled(true);
    user.setSingleAttribute(TENANT_ATTRIBUTE, request.tenantId().toString());
    user.setSingleAttribute(INVITATION_ATTRIBUTE, invitation.toString());
    user.setSingleAttribute("locale", request.locale());
    user.joinGroup(group.get());
    request.role().requiredActions().forEach(user::addRequiredAction);
    AuditRecorder.record(
        session,
        caller,
        new AuditRecorder.Entry(
            operation, "created", invitation, user.getId(), request.tenantId(), correlationId),
        OperationType.CREATE,
        null);
    return json(201, Map.of("subject", user.getId()));
  }

  /**
   * Sends the setup email while setup is pending, or confirms the proven completed state (A1). The
   * request has no body: the role, and so the required actions, come only from the identity's own
   * role group (PR #32 review).
   *
   * @param invitationId path value
   * @param contentLength declared length
   * @param correlation correlation ID header
   * @param body request body, which must be empty
   * @return response
   */
  @POST
  @Path(IDENTITY + "/credential-setup")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Response credentialSetup(
      @PathParam("invitationId") String invitationId,
      @HeaderParam("Content-Length") String contentLength,
      @HeaderParam("X-Correlation-Id") String correlation,
      InputStream body) {
    String operation = "identity.credential-setup";
    CallerAuthorizer.Caller caller;
    try {
      caller = CallerAuthorizer.authorize(session, config);
    } catch (CallerAuthorizer.Denied denied) {
      return deny(operation, denied);
    }
    String correlationId = RequestBodies.correlationId(correlation).orElse(null);
    UUID invitation;
    try {
      invitation = RequestBodies.invitationId(invitationId);
      RequestBodies.requireEmpty(length(contentLength), body);
    } catch (RequestBodies.Rejected rejected) {
      return refuse(
          caller,
          operation,
          null,
          null,
          null,
          correlationId,
          OperationType.ACTION,
          rejected.code(),
          rejected.status());
    }
    RealmModel realm = session.getContext().getRealm();
    List<UserModel> found = findByInvitation(realm, invitation);
    if (found.isEmpty()) {
      return refuse(
          caller,
          operation,
          invitation,
          null,
          null,
          correlationId,
          OperationType.ACTION,
          "IDENTITY_NOT_FOUND",
          404);
    }
    if (found.size() > 1) {
      return refuse(
          caller,
          operation,
          invitation,
          null,
          null,
          correlationId,
          OperationType.ACTION,
          "IDENTITY_AMBIGUOUS",
          409);
    }
    UserModel user = found.get(0);
    UUID tenant = tenantOf(user);
    // Exactly one approved role group, or the state is invalid and nothing is sent.
    Optional<InvitationRole> derived = singleRole(realm, user);
    if (derived.isEmpty()) {
      return refuse(
          caller,
          operation,
          invitation,
          user.getId(),
          tenant,
          correlationId,
          OperationType.ACTION,
          "SETUP_STATE_INVALID",
          409);
    }
    InvitationRole role = derived.get();
    SetupState state = SetupState.classify(role, snapshot(realm, user));
    if (state == SetupState.COMPLETED) {
      AuditRecorder.record(
          session,
          caller,
          new AuditRecorder.Entry(
              operation, "completed", invitation, user.getId(), tenant, correlationId),
          OperationType.ACTION,
          null);
      return json(200, Map.of("state", "COMPLETED"));
    }
    if (state == SetupState.INVALID || !user.isEnabled() || user.getEmail() == null) {
      return refuse(
          caller,
          operation,
          invitation,
          user.getId(),
          tenant,
          correlationId,
          OperationType.ACTION,
          "SETUP_STATE_INVALID",
          409);
    }
    Set<String> pending = user.getRequiredActionsStream().collect(Collectors.toSet());
    List<String> actions = role.requiredActions().stream().filter(pending::contains).toList();
    try {
      sendActionEmail(realm, user, actions);
    } catch (EmailException | RuntimeException failed) {
      return refuse(
          caller,
          operation,
          invitation,
          user.getId(),
          tenant,
          correlationId,
          OperationType.ACTION,
          "EMAIL_FAILED",
          503);
    }
    AuditRecorder.record(
        session,
        caller,
        new AuditRecorder.Entry(
            operation, "email_sent", invitation, user.getId(), tenant, correlationId),
        OperationType.ACTION,
        null);
    return json(202, Map.of("state", "PENDING"));
  }

  /**
   * Deletes the invitation identity while it is pristine (compensation).
   *
   * @param invitationId path value
   * @param correlation correlation ID header
   * @return response
   */
  @DELETE
  @Path(IDENTITY)
  @Produces(MediaType.APPLICATION_JSON)
  public Response compensate(
      @PathParam("invitationId") String invitationId,
      @HeaderParam("X-Correlation-Id") String correlation) {
    String operation = "identity.compensate";
    CallerAuthorizer.Caller caller;
    try {
      caller = CallerAuthorizer.authorize(session, config);
    } catch (CallerAuthorizer.Denied denied) {
      return deny(operation, denied);
    }
    String correlationId = RequestBodies.correlationId(correlation).orElse(null);
    UUID invitation;
    try {
      invitation = RequestBodies.invitationId(invitationId);
    } catch (RequestBodies.Rejected rejected) {
      return refuse(
          caller,
          operation,
          null,
          null,
          null,
          correlationId,
          OperationType.DELETE,
          rejected.code(),
          rejected.status());
    }
    RealmModel realm = session.getContext().getRealm();
    List<UserModel> found = findByInvitation(realm, invitation);
    if (found.isEmpty()) {
      AuditRecorder.record(
          session,
          caller,
          new AuditRecorder.Entry(operation, "absent", invitation, null, null, correlationId),
          OperationType.DELETE,
          null);
      return Response.noContent().build();
    }
    if (found.size() > 1) {
      return refuse(
          caller,
          operation,
          invitation,
          null,
          null,
          correlationId,
          OperationType.DELETE,
          "IDENTITY_AMBIGUOUS",
          409);
    }
    UserModel user = found.get(0);
    UUID tenant = tenantOf(user);
    Optional<InvitationRole> role = singleRole(realm, user);
    boolean pristine =
        role.isPresent()
            && SetupState.classify(role.get(), snapshot(realm, user)) == SetupState.PRISTINE;
    if (!pristine) {
      return refuse(
          caller,
          operation,
          invitation,
          user.getId(),
          tenant,
          correlationId,
          OperationType.DELETE,
          "COMPENSATION_REFUSED",
          409);
    }
    String userId = user.getId();
    session.users().removeUser(realm, user);
    AuditRecorder.record(
        session,
        caller,
        new AuditRecorder.Entry(operation, "deleted", invitation, userId, tenant, correlationId),
        OperationType.DELETE,
        null);
    return Response.noContent().build();
  }

  // ---------------------------------------------------------------------------------------------

  /** What committed state shows after a lost creation race. */
  private record Race(String code, String userId) {}

  /**
   * Settles a lost creation race from committed state, in a fresh transaction: the same identity
   * for this invitation is confirmed (200); a proven differing binding is a conflict; anything else
   * is a retryable 503 that the Core API never treats as a business conflict.
   */
  private Response afterRace(
      CallerAuthorizer.Caller caller,
      String operation,
      UUID invitation,
      RequestBodies.Provision request,
      String correlationId) {
    String realmId = session.getContext().getRealm().getId();
    Race race;
    try {
      race =
          KeycloakModelUtils.runJobInTransactionWithResult(
              session.getKeycloakSessionFactory(),
              fresh -> {
                RealmModel realm = fresh.realms().getRealm(realmId);
                fresh.getContext().setRealm(realm);
                ProvisioningResource committed = new ProvisioningResource(fresh, config);
                Race settled = committed.settle(realm, invitation, request);
                AuditRecorder.record(
                    fresh,
                    caller,
                    new AuditRecorder.Entry(
                        operation,
                        settled.code() == null ? "confirmed_after_race" : "refused_after_race",
                        invitation,
                        settled.userId(),
                        request.tenantId(),
                        correlationId),
                    OperationType.CREATE,
                    settled.code());
                return settled;
              });
    } catch (RuntimeException unreadable) {
      race = new Race(BUSY, null);
    }
    if (race.code() == null) {
      return json(200, Map.of("subject", race.userId()));
    }
    Response.ResponseBuilder answer =
        Response.status(BUSY.equals(race.code()) ? 503 : 409)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(text(Map.of("code", race.code())));
    if (BUSY.equals(race.code())) {
      answer.header("Retry-After", "1");
    }
    return answer.build();
  }

  private Race settle(RealmModel realm, UUID invitation, RequestBodies.Provision request) {
    List<UserModel> found = findByInvitation(realm, invitation);
    if (found.size() > 1) {
      return new Race("IDENTITY_AMBIGUOUS", null);
    }
    if (found.size() == 1) {
      UserModel user = found.get(0);
      return matches(user, request)
          ? new Race(null, user.getId())
          : new Race("IDENTITY_CONFLICT", user.getId());
    }
    if (addressHolder(realm, request.email()) != null) {
      // The address is bound to an identity that does not carry this invitation.
      return new Race("IDENTITY_CONFLICT", null);
    }
    return new Race(BUSY, null);
  }

  private UserModel addressHolder(RealmModel realm, String email) {
    UserModel byUsername = session.users().getUserByUsername(realm, email);
    return byUsername != null ? byUsername : session.users().getUserByEmail(realm, email);
  }

  /** Exact, bounded lookup: zero, one, or "more than one" (never mutated). */
  private List<UserModel> findByInvitation(RealmModel realm, UUID invitation) {
    String value = invitation.toString();
    return session
        .users()
        .searchForUserByUserAttributeStream(realm, INVITATION_ATTRIBUTE, value)
        .filter(
            user -> List.of(value).equals(user.getAttributeStream(INVITATION_ATTRIBUTE).toList()))
        .limit(2)
        .toList();
  }

  private boolean matches(UserModel user, RequestBodies.Provision request) {
    return request.email().equals(user.getEmail())
        && request.email().equals(user.getUsername())
        && List.of(request.tenantId().toString())
            .equals(user.getAttributeStream(TENANT_ATTRIBUTE).toList())
        && singleRole(session.getContext().getRealm(), user).equals(Optional.of(request.role()));
  }

  private Optional<GroupModel> roleGroup(RealmModel realm, InvitationRole role) {
    List<GroupModel> groups =
        session
            .groups()
            .getTopLevelGroupsStream(realm)
            .filter(group -> role.groupName().equals(group.getName()))
            .limit(2)
            .toList();
    return groups.size() == 1 ? Optional.of(groups.get(0)) : Optional.empty();
  }

  /** The identity's role, when it is a member of exactly one top-level role group. */
  private Optional<InvitationRole> singleRole(RealmModel realm, UserModel user) {
    Set<InvitationRole> roles = roles(realm, user);
    return roles.size() == 1 ? Optional.of(roles.iterator().next()) : Optional.empty();
  }

  private Set<InvitationRole> roles(RealmModel realm, UserModel user) {
    Set<InvitationRole> roles = new HashSet<>();
    for (InvitationRole role : InvitationRole.values()) {
      roleGroup(realm, role).filter(user::isMemberOf).ifPresent(group -> roles.add(role));
    }
    return roles;
  }

  private SetupState.Snapshot snapshot(RealmModel realm, UserModel user) {
    Map<String, Long> credentials =
        user.credentialManager()
            .getStoredCredentialsStream()
            .collect(Collectors.groupingBy(CredentialModel::getType, Collectors.counting()));
    return new SetupState.Snapshot(
        roles(realm, user),
        credentials,
        user.getRequiredActionsStream().collect(Collectors.toSet()));
  }

  private static UUID tenantOf(UserModel user) {
    List<String> values = user.getAttributeStream(TENANT_ATTRIBUTE).toList();
    try {
      return values.size() == 1 ? UUID.fromString(values.get(0)) : null;
    } catch (IllegalArgumentException notAnId) {
      return null;
    }
  }

  private void sendActionEmail(RealmModel realm, UserModel user, List<String> actions)
      throws EmailException {
    long expiration = System.currentTimeMillis() / 1000L + config.actionLifespanSeconds();
    ExecuteActionsActionToken token =
        new ExecuteActionsActionToken(
            user.getId(),
            user.getEmail(),
            (int) expiration,
            actions,
            config.webRedirectUri(),
            config.webClientId());
    UriBuilder link = LoginActionsService.actionTokenProcessor(session.getContext().getUri());
    link.queryParam("key", token.serialize(session, realm, session.getContext().getUri()));
    session
        .getProvider(EmailTemplateProvider.class)
        .setAttribute(Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS, token.getRequiredActions())
        .setRealm(realm)
        .setUser(user)
        .sendExecuteActions(
            link.build(realm.getName()).toString(),
            TimeUnit.SECONDS.toMinutes(config.actionLifespanSeconds()));
  }

  private Response deny(String operation, CallerAuthorizer.Denied denied) {
    AuditRecorder.denied(operation, denied.status(), denied.reason());
    return json(
        denied.status(), Map.of("code", denied.status() == 401 ? "UNAUTHORIZED" : "FORBIDDEN"));
  }

  private Response refuse(
      CallerAuthorizer.Caller caller,
      String operation,
      UUID invitation,
      String userId,
      UUID tenant,
      String correlationId,
      OperationType type,
      String code,
      int status) {
    AuditRecorder.record(
        session,
        caller,
        new AuditRecorder.Entry(operation, "refused", invitation, userId, tenant, correlationId),
        type,
        code);
    return json(status, Map.of("code", code));
  }

  private static long length(String header) {
    if (header == null) {
      return -1;
    }
    try {
      return Long.parseLong(header.trim());
    } catch (NumberFormatException malformed) {
      return Long.MAX_VALUE;
    }
  }

  private static Response json(int status, Map<String, String> body) {
    return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(text(body)).build();
  }

  private static String text(Map<String, String> body) {
    try {
      return JSON.writeValueAsString(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      return "{}";
    }
  }
}
