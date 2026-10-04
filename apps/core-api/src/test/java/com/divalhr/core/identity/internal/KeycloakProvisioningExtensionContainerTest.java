package com.divalhr.core.identity.internal;

import static com.divalhr.core.identity.internal.KeycloakCalls.identityPath;
import static com.divalhr.core.identity.internal.KeycloakCalls.provisionBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.internal.KeycloakCalls.Reply;
import com.divalhr.core.identity.internal.ScriptedBrowser.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;

/**
 * Issue #31 merge gates, against the real Keycloak 26.7.4 image with the {@code
 * divalhr-provisioning} extension and the committed development realm: authorization before input
 * (A2), the field allow-list, idempotency and ambiguity, role-specific setup states (A1),
 * compensation, the negative Admin API matrix including the enrolled-tenant-administrator exploit,
 * sanitized audit evidence, and the start-up version guard.
 */
class KeycloakProvisioningExtensionContainerTest {

  private static final UUID TENANT_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
  private static final UUID TENANT_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
  private static final String PASSWORD = "test-only-Password-2026!";
  private static final String UNAUTHORIZED = "{\"code\":\"UNAUTHORIZED\"}";
  private static final String FORBIDDEN = "{\"code\":\"FORBIDDEN\"}";

  private static KeycloakTestStack stack;
  private static KeycloakCalls calls;

  @BeforeAll
  static void start() throws Exception {
    stack = KeycloakTestStack.start();
    calls = new KeycloakCalls(stack);
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  @AfterEach
  void clearLockouts() throws Exception {
    calls.admin("DELETE", "/attack-detection/brute-force/users", null);
  }

  /** An identity created through the extension. */
  private record Created(UUID invitation, String email, String subject) {}

  private static Created create(String role) throws Exception {
    UUID invitation = UUID.randomUUID();
    String email = unique(role);
    Reply reply =
        calls.extension(
            calls.provisionerToken(),
            "PUT",
            identityPath(invitation.toString()),
            provisionBody(email, role, TENANT_A, "en"));
    assertThat(reply.status()).isEqualTo(201);
    return new Created(invitation, email, reply.json().path("subject").asString());
  }

  /** Credential setup takes no body: the extension derives the role from the identity. */
  private static Reply setup(Created created) throws Exception {
    return calls.extensionRaw(
        calls.provisionerToken(),
        "POST",
        identityPath(created.invitation().toString()) + "/credential-setup",
        null,
        null,
        false);
  }

  private static Reply compensate(Created created) throws Exception {
    return calls.extension(
        calls.provisionerToken(), "DELETE", identityPath(created.invitation().toString()), null);
  }

  private static String groupId(String name) throws Exception {
    return calls
        .admin("GET", "/groups?search=" + name + "&exact=true", null)
        .get(0)
        .path("id")
        .asString();
  }

  private static String unique(String prefix) {
    return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
        .toLowerCase(Locale.ROOT);
  }

  // --- A2: authorization before any input
  // ---------------------------------------------------------

  @Test
  void unauthorizedCallersGetOneAnswerWhateverThePathBodyOrIdentity() throws Exception {
    Created existing = create("employee");
    String employeeAdminCli =
        calls.token("divalhr-dev", "admin-cli", null, "dev-employee-a", "dev-only-Employee-A-2026");
    String masterAdmin = calls.adminToken();
    List<String> tokens = new ArrayList<>();
    tokens.add(null);
    tokens.add("not-a-jwt");
    tokens.add(masterAdmin);
    tokens.add(employeeAdminCli);
    tokens.add(webToken("dev-employee-a", "dev-only-Employee-A-2026", null));
    tokens.add(webToken("dev-admin-a", "dev-only-Admin-A-2026", "dev-only-totp-admin-a-2026"));
    for (String token : tokens) {
      assertUniformDenial(token, 401, UNAUTHORIZED, existing);
    }
    assertThat(calls.admin("GET", "/users/" + existing.subject(), null).path("id").asString())
        .isEqualTo(existing.subject());
  }

  @Test
  void aProvisionerTokenWithoutTheAudienceIsUnauthorized() throws Exception {
    Created existing = create("employee");
    String client = calls.clientUuid(KeycloakCalls.PROVISIONER);
    JsonNode mapper =
        calls.admin("GET", "/clients/" + client + "/protocol-mappers/models", null).get(0);
    calls.admin(
        "DELETE",
        "/clients/" + client + "/protocol-mappers/models/" + mapper.path("id").asString(),
        null);
    try {
      String withoutAudience = calls.provisionerToken();
      assertThat(KeycloakCalls.claims(withoutAudience).path("aud").toString())
          .doesNotContain("divalhr-provisioning");
      assertUniformDenial(withoutAudience, 401, UNAUTHORIZED, existing);
    } finally {
      ((tools.jackson.databind.node.ObjectNode) mapper).remove("id");
      calls.admin("POST", "/clients/" + client + "/protocol-mappers/models", mapper.toString());
    }
    assertThat(setup(existing).status()).isEqualTo(202);
  }

  @Test
  void anotherClientOrAProvisionerWithoutTheCapabilityIsForbidden() throws Exception {
    Created existing = create("employee");
    // Another confidential client that adds the extension's audience to its own tokens.
    String attackerId = "attacker-" + UUID.randomUUID().toString().substring(0, 8);
    String attackerSecret = "test-only-attacker-secret-" + UUID.randomUUID();
    calls.admin(
        "POST",
        "/clients",
        "{\"clientId\":\""
            + attackerId
            + "\",\"enabled\":true,\"publicClient\":false,"
            + "\"serviceAccountsEnabled\":true,\"standardFlowEnabled\":false,"
            + "\"secret\":\""
            + attackerSecret
            + "\",\"protocolMappers\":[{\"name\":\"aud\",\"protocol\":\"openid-connect\","
            + "\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{"
            + "\"included.custom.audience\":\"divalhr-provisioning\",\"access.token.claim\":\"true\"}}]}");
    try {
      String attacker = calls.token("divalhr-dev", attackerId, attackerSecret, null, null);
      assertUniformDenial(attacker, 403, FORBIDDEN, existing);
      // Even when its service account is given the capability role: wrong client.
      grantCapability("service-account-" + attackerId, true);
      assertUniformDenial(attacker, 403, FORBIDDEN, existing);
    } finally {
      // The realm verifier rightly fails while another client emits the extension's audience.
      calls.admin("DELETE", "/clients/" + calls.clientUuid(attackerId), null);
    }

    // The provisioner whose capability was revoked: refused at once, with the token still valid.
    String before = calls.provisionerToken();
    grantCapability("service-account-divalhr-core-provisioner", false);
    try {
      assertUniformDenial(before, 403, FORBIDDEN, existing);
      assertUniformDenial(calls.provisionerToken(), 403, FORBIDDEN, existing);
    } finally {
      grantCapability("service-account-divalhr-core-provisioner", true);
    }
    assertThat(setup(existing).status()).isEqualTo(202);

    // A disabled provisioner client: its earlier token no longer works.
    String client = calls.clientUuid(KeycloakCalls.PROVISIONER);
    String token = calls.provisionerToken();
    calls.admin("PUT", "/clients/" + client, "{\"enabled\":false}");
    try {
      Reply reply =
          calls.extension(token, "DELETE", identityPath(existing.invitation().toString()), null);
      assertThat(reply.status()).isIn(401, 403);
    } finally {
      calls.admin("PUT", "/clients/" + client, "{\"enabled\":true}");
    }
    assertThat(calls.admin("GET", "/users/" + existing.subject(), null).path("id").asString())
        .isEqualTo(existing.subject());
  }

  private static void grantCapability(String serviceAccount, boolean grant) throws Exception {
    String user = calls.userId(serviceAccount);
    String capabilityClient = calls.clientUuid("divalhr-provisioning");
    JsonNode role =
        calls.admin("GET", "/clients/" + capabilityClient + "/roles/provision-invitations", null);
    calls.admin(
        grant ? "POST" : "DELETE",
        "/users/" + user + "/role-mappings/clients/" + capabilityClient,
        "[" + role + "]");
  }

  /**
   * Every method, path and body variant answers exactly the same status and body, and nothing about
   * the identity changes.
   */
  private static void assertUniformDenial(String token, int status, String body, Created existing)
      throws Exception {
    String valid = provisionBody(existing.email(), "employee", TENANT_A, "en");
    List<String[]> paths =
        List.of(
            new String[] {existing.invitation().toString()},
            new String[] {UUID.randomUUID().toString()},
            new String[] {"not-a-uuid"});
    for (String[] path : paths) {
      String identity = identityPath(path[0]);
      List<Reply> replies = new ArrayList<>();
      replies.add(calls.extensionRaw(token, "PUT", identity, "application/json", valid, false));
      replies.add(
          calls.extensionRaw(token, "PUT", identity, "application/json", "{not json", false));
      replies.add(
          calls.extensionRaw(token, "PUT", identity, "application/json", "x".repeat(5000), true));
      replies.add(
          calls.extensionRaw(token, "PUT", identity, "application/json", "x".repeat(5000), false));
      replies.add(calls.extensionRaw(token, "PUT", identity, "text/plain", valid, false));
      replies.add(
          calls.extensionRaw(
              token,
              "POST",
              identity + "/credential-setup",
              "application/json",
              "{\"role\":\"employee\"}",
              false));
      replies.add(calls.extensionRaw(token, "DELETE", identity, null, null, false));
      // MVP-022: the access revocation answers the same way, whatever the subject or body.
      String subject =
          path[0].equals(existing.invitation().toString()) ? existing.subject() : path[0];
      String revocation =
          "/realms/divalhr-dev/divalhr-provisioning/v1/tenants/"
              + TENANT_A
              + "/identities/"
              + subject
              + "/access-revocation";
      replies.add(calls.extensionRaw(token, "PUT", revocation, null, null, false));
      replies.add(
          calls.extensionRaw(token, "PUT", revocation, "application/json", "{\"x\":1}", false));
      for (Reply reply : replies) {
        assertThat(reply.status()).as("status for %s", path[0].length()).isEqualTo(status);
        assertThat(reply.body()).isEqualTo(body);
      }
    }
    assertThat(calls.messagesTo(existing.email())).isZero();
    assertThat(calls.admin("GET", "/users/" + existing.subject(), null).path("enabled").asBoolean())
        .isTrue();
  }

  /** A divalhr-web access token from a real browser sign-in. */
  private static String webToken(String username, String password, String totpSeed)
      throws Exception {
    ScriptedBrowser browser = new ScriptedBrowser(stack.baseUrl(), KeycloakTestStack.REALM);
    Outcome outcome =
        browser.drive(
            browser.authorize(Map.of()),
            username,
            password,
            totpSeed == null
                ? null
                : () -> ScriptedBrowser.totp(totpSeed, System.currentTimeMillis() / 1000),
            secret -> {});
    assertThat(outcome.reachedApplication()).isTrue();
    return KeycloakCalls.JSON
        .readTree(browser.exchange(outcome.last()))
        .path("access_token")
        .asString();
  }

  // --- A2: strict, bounded input -----------------------------------------------------------------

  @Test
  void bodiesAreBoundedStrictAndNeverEchoed() throws Exception {
    String token = calls.provisionerToken();
    String invitation = identityPath(UUID.randomUUID().toString());
    String email = unique("strict");
    String valid = provisionBody(email, "employee", TENANT_A, "fr");
    Map<String, Integer> statuses = new java.util.LinkedHashMap<>();
    statuses.put(
        "chunked",
        calls
            .extensionRaw(
                token,
                "PUT",
                invitation,
                "application/json",
                "{\"role\":\"" + "x".repeat(3000) + "\"}",
                true)
            .status());
    statuses.put(
        "declared",
        calls
            .extensionRaw(
                token,
                "PUT",
                invitation,
                "application/json",
                "{\"role\":\"" + "x".repeat(3000) + "\"}",
                false)
            .status());
    statuses.put(
        "text", calls.extensionRaw(token, "PUT", invitation, "text/plain", valid, false).status());
    statuses.put("none", calls.extensionRaw(token, "PUT", invitation, null, valid, false).status());
    assertThat(statuses)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("chunked", 413, "declared", 413, "text", 415, "none", 415));
    String base = valid.substring(0, valid.length() - 1);
    List<String> invalid =
        List.of(
            valid + valid,
            "[" + valid + "]",
            base + ",\"groups\":[\"/divalhr-role-tenant-admin\"]}",
            base + ",\"attributes\":{\"tenant_id\":[\"x\"]}}",
            base + ",\"credentials\":[{\"type\":\"password\",\"value\":\"x\"}]}",
            base + ",\"enabled\":false}",
            base + ",\"emailVerified\":false}",
            base + ",\"requiredActions\":[]}",
            base + ",\"realmRoles\":[\"platform-admin\"]}",
            valid.replace("\"employee\"", "\"platform-admin\""),
            valid.replace("\"employee\"", "\"divalhr-privileged-mfa\""),
            valid.replace(email, email.toUpperCase(Locale.ROOT)),
            valid.replace("\"fr\"", "\"de\""),
            valid.replace(TENANT_A.toString(), "tenant-a"));
    for (String body : invalid) {
      Reply reply = calls.extension(token, "PUT", invitation, body);
      assertThat(reply.status()).isEqualTo(400);
      assertThat(reply.body()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
    }
    assertThat(calls.extension(token, "PUT", identityPath("NOT-A-UUID"), valid).status())
        .isEqualTo(400);
    assertThat(calls.admin("GET", "/users?email=" + KeycloakCalls.enc(email), null).size())
        .isZero();
  }

  // --- create, idempotency, conflicts, ambiguity
  // ---------------------------------------------------

  @Test
  void identicalConcurrentCreatesNeverConflictAndYieldOneIdentity() throws Exception {
    UUID invitation = UUID.randomUUID();
    String email = unique("parallel");
    String body = provisionBody(email, "tenant-admin", TENANT_A, "fr");
    String token = calls.provisionerToken();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Reply> replies = new ArrayList<>();
    try {
      List<Future<Reply>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        Callable<Reply> call =
            () -> calls.extension(token, "PUT", identityPath(invitation.toString()), body);
        futures.add(pool.submit(call));
      }
      for (Future<Reply> future : futures) {
        replies.add(future.get());
      }
    } finally {
      pool.shutdownNow();
    }
    // PR #32 review: identical calls never conflict. Each one resolves to the same subject (201
    // once, 200 otherwise, including a call that lost the race), or is a retryable 503 that a
    // retry resolves.
    Set<String> subjects = new HashSet<>();
    int created = 0;
    for (Reply reply : replies) {
      assertThat(reply.status()).isIn(200, 201, 503);
      if (reply.status() == 503) {
        assertThat(reply.code()).isEqualTo("IDENTITY_BUSY");
        reply = calls.extension(token, "PUT", identityPath(invitation.toString()), body);
        assertThat(reply.status()).isEqualTo(200);
      }
      if (reply.status() == 201) {
        created++;
      }
      subjects.add(reply.json().path("subject").asString());
    }
    assertThat(created).isEqualTo(1);
    assertThat(subjects).hasSize(1);
    String subject = subjects.iterator().next();
    assertThat(calls.admin("GET", "/users?exact=true&username=" + KeycloakCalls.enc(email), null))
        .hasSize(1);
    assertThat(calls.extension(token, "PUT", identityPath(invitation.toString()), body).status())
        .isEqualTo(200);
    // Replays with another tenant, role or address are conflicts and change nothing.
    for (String other :
        List.of(
            provisionBody(email, "tenant-admin", TENANT_B, "fr"),
            provisionBody(email, "employee", TENANT_A, "fr"),
            provisionBody(unique("other"), "tenant-admin", TENANT_A, "fr"))) {
      assertThat(calls.extension(token, "PUT", identityPath(invitation.toString()), other).code())
          .isEqualTo("IDENTITY_CONFLICT");
    }
    JsonNode user = calls.admin("GET", "/users/" + subject, null);
    assertThat(user.path("attributes").path("tenant_id").get(0).asString())
        .isEqualTo(TENANT_A.toString());
    assertThat(user.path("email").asString()).isEqualTo(email);
    // Another invitation for the same address is a conflict.
    assertThat(
            calls.extension(token, "PUT", identityPath(UUID.randomUUID().toString()), body).code())
        .isEqualTo("IDENTITY_CONFLICT");
  }

  // --- PR #32 review: no realm default roles ---------------------------------------------------

  @Test
  void newIdentitiesHoldOnlyWhatTheirRoleGroupGrants() throws Exception {
    // Even if the realm's default roles grew a privileged role, a new identity would not get it.
    String defaults = "/roles/default-roles-" + KeycloakCalls.REALM + "/composites";
    String platformAdmin = calls.admin("GET", "/roles/platform-admin", null).toString();
    calls.admin("POST", defaults, "[" + platformAdmin + "]");
    try {
      Map<String, List<String>> expected =
          Map.of(
              "employee", List.of("employee"),
              "tenant-admin", List.of("tenant-admin", "divalhr-privileged-mfa"));
      for (Map.Entry<String, List<String>> role : expected.entrySet()) {
        Created created = create(role.getKey());
        JsonNode direct =
            calls.admin("GET", "/users/" + created.subject() + "/role-mappings", null);
        assertThat(direct.path("realmMappings").size()).as("direct realm roles").isZero();
        assertThat(direct.path("clientMappings").size()).as("direct client roles").isZero();
        List<String> effective = new ArrayList<>();
        calls
            .admin("GET", "/users/" + created.subject() + "/role-mappings/realm/composite", null)
            .forEach(r -> effective.add(r.path("name").asString()));
        assertThat(effective).containsExactlyInAnyOrderElementsOf(role.getValue());
        for (JsonNode client : calls.admin("GET", "/clients", null)) {
          assertThat(
                  calls
                      .admin(
                          "GET",
                          "/users/"
                              + created.subject()
                              + "/role-mappings/clients/"
                              + client.path("id").asString()
                              + "/composite",
                          null)
                      .size())
              .as("effective roles of %s", client.path("clientId").asString())
              .isZero();
        }
      }
    } finally {
      calls.admin("DELETE", defaults, "[" + platformAdmin + "]");
    }
    // The invitation groups carry exactly their tenant role; only tenant-admin implies the MFA
    // marker, and the marker implies nothing.
    Map<String, String> groups =
        Map.of("divalhr-role-employee", "employee", "divalhr-role-tenant-admin", "tenant-admin");
    for (Map.Entry<String, String> group : groups.entrySet()) {
      JsonNode mappings =
          calls.admin("GET", "/groups/" + groupId(group.getKey()) + "/role-mappings", null);
      List<String> realmRoles = new ArrayList<>();
      mappings.path("realmMappings").forEach(r -> realmRoles.add(r.path("name").asString()));
      assertThat(realmRoles).containsExactly(group.getValue());
      assertThat(mappings.path("clientMappings").size()).isZero();
    }
    assertThat(calls.admin("GET", "/roles/employee/composites", null).size()).isZero();
    List<String> adminComposites = new ArrayList<>();
    calls
        .admin("GET", "/roles/tenant-admin/composites", null)
        .forEach(r -> adminComposites.add(r.path("name").asString()));
    assertThat(adminComposites).containsExactly("divalhr-privileged-mfa");
    assertThat(calls.admin("GET", "/roles/divalhr-privileged-mfa/composites", null).size())
        .isZero();
  }

  @Test
  void twoIdentitiesCarryingOneInvitationFailClosed() throws Exception {
    Created first = create("employee");
    Created second = create("employee");
    calls.admin(
        "PUT",
        "/users/" + second.subject(),
        "{\"attributes\":{\"divalhr_invitation_id\":[\""
            + first.invitation()
            + "\"],"
            + "\"tenant_id\":[\""
            + TENANT_A
            + "\"],\"locale\":[\"en\"]}}");
    String token = calls.provisionerToken();
    String path = identityPath(first.invitation().toString());
    assertThat(
            calls
                .extension(
                    token, "PUT", path, provisionBody(first.email(), "employee", TENANT_A, "en"))
                .code())
        .isEqualTo("IDENTITY_AMBIGUOUS");
    assertThat(setup(first).code()).isEqualTo("IDENTITY_AMBIGUOUS");
    assertThat(compensate(first).code()).isEqualTo("IDENTITY_AMBIGUOUS");
    assertThat(calls.admin("GET", "/users/" + first.subject(), null).path("id").asString())
        .isEqualTo(first.subject());
    assertThat(calls.admin("GET", "/users/" + second.subject(), null).path("id").asString())
        .isEqualTo(second.subject());
    assertThat(calls.messagesTo(first.email()) + calls.messagesTo(second.email())).isZero();
  }

  // --- A1: role-specific setup states
  // --------------------------------------------------------------

  @Test
  void employeeSetupStates() throws Exception {
    Created employee = create("employee");
    assertThat(setup(employee).json().path("state").asString()).isEqualTo("PENDING");
    calls.awaitMessage(employee.email(), 1);
    // The caller cannot name the role: any body, even a well-formed role, is refused unread.
    Reply named =
        calls.extension(
            calls.provisionerToken(),
            "POST",
            identityPath(employee.invitation().toString()) + "/credential-setup",
            "{\"role\":\"tenant-admin\"}");
    assertThat(named.status()).isEqualTo(400);
    assertThat(named.code()).isEqualTo("INVALID_REQUEST");
    // The role comes from the identity's group: two role groups, or none, is invalid.
    String employeeGroup = groupId("divalhr-role-employee");
    String adminGroup = groupId("divalhr-role-tenant-admin");
    calls.admin("PUT", "/users/" + employee.subject() + "/groups/" + adminGroup, "");
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
    calls.admin("DELETE", "/users/" + employee.subject() + "/groups/" + employeeGroup, null);
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
    calls.admin("DELETE", "/users/" + employee.subject() + "/groups/" + adminGroup, null);
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
    calls.admin("PUT", "/users/" + employee.subject() + "/groups/" + employeeGroup, "");
    assertThat(calls.messagesTo(employee.email())).isEqualTo(1);
    // An extra required action.
    calls.admin(
        "PUT",
        "/users/" + employee.subject(),
        "{\"requiredActions\":[\"UPDATE_PASSWORD\",\"VERIFY_EMAIL\"]}");
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
    // The action removed without its credential.
    calls.admin("PUT", "/users/" + employee.subject(), "{\"requiredActions\":[]}");
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
    // A password while the action is still pending (a non-temporary reset clears the action, so
    // it is pending again only when re-added afterwards).
    setPassword(employee.subject());
    calls.admin(
        "PUT", "/users/" + employee.subject(), "{\"requiredActions\":[\"UPDATE_PASSWORD\"]}");
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
    // Proven terminal state: password and no pending action.
    calls.admin("PUT", "/users/" + employee.subject(), "{\"requiredActions\":[]}");
    Reply completed = setup(employee);
    assertThat(completed.status()).isEqualTo(200);
    assertThat(completed.json().path("state").asString()).isEqualTo("COMPLETED");
    assertThat(calls.messagesTo(employee.email())).isEqualTo(1);
    // An unexpected authenticator on an employee.
    enrollExtraAuthenticator(employee);
    assertThat(setup(employee).code()).isEqualTo("SETUP_STATE_INVALID");
  }

  @Test
  void tenantAdministratorSetupStates() throws Exception {
    Created admin = create("tenant-admin");
    assertThat(setup(admin).status()).isEqualTo(202);
    // The password set and its action done, the authenticator still pending: consistent progress.
    setPassword(admin.subject());
    calls.admin("PUT", "/users/" + admin.subject(), "{\"requiredActions\":[\"CONFIGURE_TOTP\"]}");
    assertThat(setup(admin).status()).isEqualTo(202);
    assertThat(compensate(admin).code()).isEqualTo("COMPENSATION_REFUSED");
    // Only the password, and nothing pending: partial, never completed.
    calls.admin("PUT", "/users/" + admin.subject(), "{\"requiredActions\":[]}");
    assertThat(setup(admin).code()).isEqualTo("SETUP_STATE_INVALID");
    // Nothing set and nothing pending.
    Created bare = create("tenant-admin");
    calls.admin("PUT", "/users/" + bare.subject(), "{\"requiredActions\":[]}");
    assertThat(setup(bare).code()).isEqualTo("SETUP_STATE_INVALID");

    // Fully enrolled through the link: completed, no further email.
    Enrolled enrolled = enrolledTenantAdmin();
    int before = calls.messagesTo(enrolled.created().email());
    Reply completed = setup(enrolled.created());
    assertThat(completed.status()).isEqualTo(200);
    assertThat(calls.messagesTo(enrolled.created().email())).isEqualTo(before);
    // A second authenticator: unexpected credential count.
    enrollExtraAuthenticator(enrolled.created());
    assertThat(setup(enrolled.created()).code()).isEqualTo("SETUP_STATE_INVALID");
  }

  private static void setPassword(String subject) throws Exception {
    calls.admin(
        "PUT",
        "/users/" + subject + "/reset-password",
        "{\"type\":\"password\",\"value\":\"" + PASSWORD + "\",\"temporary\":false}");
  }

  /** A realm administrator sends a CONFIGURE_TOTP link; the user completes it. */
  private static void enrollExtraAuthenticator(Created created) throws Exception {
    int before = calls.messagesTo(created.email());
    calls.admin(
        "PUT",
        "/users/"
            + created.subject()
            + "/execute-actions-email?client_id=divalhr-web&redirect_uri="
            + KeycloakCalls.enc(ScriptedBrowser.REDIRECT_URI),
        "[\"CONFIGURE_TOTP\"]");
    ScriptedBrowser browser = new ScriptedBrowser(stack.baseUrl(), KeycloakTestStack.REALM);
    AtomicReference<String> secret = new AtomicReference<>();
    browser.drive(
        browser.open(calls.actionLink(created.email(), before + 1)),
        created.email(),
        PASSWORD,
        null,
        secret::set);
    assertThat(secret.get()).as("an authenticator was set up").isNotNull();
    long otps = 0;
    for (JsonNode credential :
        calls.admin("GET", "/users/" + created.subject() + "/credentials", null)) {
      if ("otp".equals(credential.path("type").asString())) {
        otps++;
      }
    }
    assertThat(otps).as("authenticators after the extra enrollment").isPositive();
  }

  // --- the Issue #31 exploit and the negative Admin API matrix (merge gate)
  // -----------------------

  /** A tenant administrator who completed the setup link; the secret stays in memory. */
  private record Enrolled(Created created, String secret) {
    @Override
    public String toString() {
      return "Enrolled[" + created.subject() + "]";
    }
  }

  private static Enrolled enrolledTenantAdmin() throws Exception {
    Created created = create("tenant-admin");
    assertThat(setup(created).status()).isEqualTo(202);
    ScriptedBrowser browser = new ScriptedBrowser(stack.baseUrl(), KeycloakTestStack.REALM);
    AtomicReference<String> secret = new AtomicReference<>();
    browser.drive(
        browser.open(calls.actionLink(created.email(), 1)),
        created.email(),
        PASSWORD,
        null,
        secret::set);
    assertThat(secret.get()).as("an authenticator was set up").isNotNull();
    return new Enrolled(created, secret.get());
  }

  @Test
  void theProvisionerCannotTakeOverAnEnrolledTenantAdministrator() throws Exception {
    Enrolled admin = enrolledTenantAdmin();
    String subject = admin.created().subject();
    JsonNode credentials = calls.admin("GET", "/users/" + subject + "/credentials", null);
    String otpId = null;
    for (JsonNode credential : credentials) {
      if ("otp".equals(credential.path("type").asString())) {
        otpId = credential.path("id").asString();
      }
    }
    assertThat(otpId).isNotNull();
    String provisioner = calls.provisionerToken();
    String employeeGroup =
        calls
            .admin("GET", "/groups?search=divalhr-role-employee&exact=true", null)
            .get(0)
            .path("id")
            .asString();
    String adminGroup =
        calls
            .admin("GET", "/groups?search=divalhr-role-tenant-admin&exact=true", null)
            .get(0)
            .path("id")
            .asString();
    String platformAdmin = calls.admin("GET", "/roles/platform-admin", null).toString();
    String seed = calls.userId("dev-platform-admin");
    for (String target : List.of(subject, seed)) {
      Map<String, Reply> matrix = new java.util.LinkedHashMap<>();
      matrix.put(
          "delete credential",
          calls.adminAs(provisioner, "DELETE", "/users/" + target + "/credentials/" + otpId, null));
      matrix.put(
          "reset password",
          calls.adminAs(
              provisioner,
              "PUT",
              "/users/" + target + "/reset-password",
              "{\"type\":\"password\",\"value\":\"x\",\"temporary\":false}"));
      matrix.put(
          "required actions",
          calls.adminAs(
              provisioner,
              "PUT",
              "/users/" + target,
              "{\"requiredActions\":[\"CONFIGURE_TOTP\"]}"));
      matrix.put(
          "attributes",
          calls.adminAs(
              provisioner,
              "PUT",
              "/users/" + target,
              "{\"attributes\":{\"tenant_id\":[\"" + TENANT_B + "\"]}}"));
      matrix.put(
          "email",
          calls.adminAs(
              provisioner, "PUT", "/users/" + target, "{\"email\":\"attacker@example.test\"}"));
      matrix.put(
          "disable", calls.adminAs(provisioner, "PUT", "/users/" + target, "{\"enabled\":false}"));
      matrix.put(
          "join group",
          calls.adminAs(provisioner, "PUT", "/users/" + target + "/groups/" + employeeGroup, ""));
      matrix.put(
          "leave group",
          calls.adminAs(provisioner, "DELETE", "/users/" + target + "/groups/" + adminGroup, null));
      matrix.put("delete user", calls.adminAs(provisioner, "DELETE", "/users/" + target, null));
      matrix.put(
          "impersonate",
          calls.adminAs(provisioner, "POST", "/users/" + target + "/impersonation", ""));
      matrix.put(
          "execute actions email",
          calls.adminAs(
              provisioner,
              "PUT",
              "/users/" + target + "/execute-actions-email",
              "[\"CONFIGURE_TOTP\"]"));
      matrix.put(
          "map platform-admin",
          calls.adminAs(
              provisioner,
              "POST",
              "/users/" + target + "/role-mappings/realm",
              "[" + platformAdmin + "]"));
      matrix.put("logout", calls.adminAs(provisioner, "POST", "/users/" + target + "/logout", ""));
      matrix.put("read user", calls.adminAs(provisioner, "GET", "/users/" + target, null));
      matrix.put(
          "read credentials",
          calls.adminAs(provisioner, "GET", "/users/" + target + "/credentials", null));
      for (Map.Entry<String, Reply> entry : matrix.entrySet()) {
        assertThat(entry.getValue().status()).as(entry.getKey()).isEqualTo(403);
      }
    }
    Map<String, Reply> realmWide = new java.util.LinkedHashMap<>();
    realmWide.put("list users", calls.adminAs(provisioner, "GET", "/users", null));
    realmWide.put(
        "create user",
        calls.adminAs(
            provisioner,
            "POST",
            "/users",
            "{\"username\":\"x@example.test\",\"enabled\":true,\"groups\":[\"/divalhr-role-tenant-admin\"]}"));
    realmWide.put("groups", calls.adminAs(provisioner, "GET", "/groups", null));
    realmWide.put("clients", calls.adminAs(provisioner, "GET", "/clients", null));
    realmWide.put("realm", calls.adminAs(provisioner, "PUT", "", "{\"displayName\":\"x\"}"));
    realmWide.put(
        "composite",
        calls.adminAs(
            provisioner, "POST", "/roles/employee/composites", "[" + platformAdmin + "]"));
    for (Map.Entry<String, Reply> entry : realmWide.entrySet()) {
      assertThat(entry.getValue().status()).as(entry.getKey()).isEqualTo(403);
    }

    // Through the extension: no setup link and no deletion for an enrolled administrator.
    int mails = calls.messagesTo(admin.created().email());
    assertThat(setup(admin.created()).json().path("state").asString()).isEqualTo("COMPLETED");
    assertThat(compensate(admin.created()).code()).isEqualTo("COMPENSATION_REFUSED");
    assertThat(
            calls
                .extension(
                    provisioner,
                    "PUT",
                    identityPath(admin.created().invitation().toString()),
                    provisionBody(admin.created().email(), "tenant-admin", TENANT_B, "en"))
                .code())
        .isEqualTo("IDENTITY_CONFLICT");
    assertThat(calls.messagesTo(admin.created().email())).isEqualTo(mails);

    // Nothing changed: same credentials, and the administrator still signs in with the code.
    assertThat(calls.admin("GET", "/users/" + subject + "/credentials", null).toString())
        .isEqualTo(credentials.toString());
    ScriptedBrowser browser = new ScriptedBrowser(stack.baseUrl(), KeycloakTestStack.REALM);
    waitForNextPeriod();
    Outcome signIn =
        browser.drive(
            browser.authorize(Map.of()),
            admin.created().email(),
            PASSWORD,
            () -> ScriptedBrowser.totp(admin.secret(), System.currentTimeMillis() / 1000),
            s -> {});
    assertThat(signIn.reachedApplication()).isTrue();
    String access =
        KeycloakCalls.JSON
            .readTree(browser.exchange(signIn.last()))
            .path("access_token")
            .asString();
    assertThat(KeycloakCalls.claims(access).path("acr").asString())
        .isEqualTo("urn:divalhr:loa:mfa");
  }

  @Test
  void theProvisionerHoldsOnlyTheCapability() throws Exception {
    String serviceAccount = calls.userId("service-account-divalhr-core-provisioner");
    JsonNode realmRoles =
        calls.admin("GET", "/users/" + serviceAccount + "/role-mappings/realm/composite", null);
    for (JsonNode role : realmRoles) {
      assertThat(role.path("name").asString())
          .isIn("offline_access", "uma_authorization", "default-roles-divalhr-dev");
    }
    JsonNode mappings = calls.admin("GET", "/users/" + serviceAccount + "/role-mappings", null);
    assertThat(mappings.path("clientMappings").has("realm-management")).isFalse();
    String realmManagement = calls.clientUuid("realm-management");
    assertThat(
            calls
                .admin(
                    "GET",
                    "/users/"
                        + serviceAccount
                        + "/role-mappings/clients/"
                        + realmManagement
                        + "/composite",
                    null)
                .size())
        .isZero();
    assertThat(calls.admin("GET", "/users/" + serviceAccount + "/groups", null).size()).isZero();
    assertThat(calls.admin("GET", "", null).path("adminPermissionsEnabled").asBoolean(false))
        .isFalse();
  }

  // --- audit evidence and secrecy
  // -------------------------------------------------------------------

  @Test
  void operationsLeaveSanitizedEvidence() throws Exception {
    Created created = create("employee");
    calls.extension(
        calls.provisionerToken(),
        "PUT",
        identityPath(created.invitation().toString()),
        provisionBody(created.email(), "employee", TENANT_A, "en"));
    assertThat(setup(created).status()).isEqualTo(202);
    calls.awaitMessage(created.email(), 1);
    // Still pristine (no credential yet): compensation deletes it.
    assertThat(compensate(created).status()).isEqualTo(204);
    JsonNode events = calls.admin("GET", "/admin-events?resourceTypes=USER&max=500", null);
    List<JsonNode> ours = new ArrayList<>();
    for (JsonNode event : events) {
      if (created
          .invitation()
          .toString()
          .equals(event.path("details").path("divalhr.invitationId").asString())) {
        ours.add(event);
      }
    }
    Set<String> outcomes = new HashSet<>();
    for (JsonNode event : ours) {
      JsonNode details = event.path("details");
      outcomes.add(
          details.path("divalhr.operation").asString()
              + ":"
              + details.path("divalhr.outcome").asString());
      assertThat(event.has("representation")).isFalse();
      assertThat(details.path("divalhr.tenantId").asString()).isEqualTo(TENANT_A.toString());
      assertThat(details.path("divalhr.correlationId").asString()).matches("^[0-9a-f-]{36}$");
      assertThat(event.path("resourcePath").asString()).isEqualTo("users/" + created.subject());
      assertThat(event.path("authDetails").path("clientId").asString())
          .isEqualTo(calls.clientUuid(KeycloakCalls.PROVISIONER));
    }
    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            "identity.create:created",
            "identity.create:confirmed",
            "identity.credential-setup:email_sent",
            "identity.compensate:deleted");
    String everything = events.toString() + stack.keycloakLogs();
    assertThat(everything)
        .contains("event=provisioning")
        .doesNotContain(created.email())
        .doesNotContain(KeycloakTestStack.PROVISIONER_SECRET)
        .doesNotContain("action-token?key=")
        .doesNotContainPattern("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.");
  }

  // --- version guard
  // --------------------------------------------------------------------------------

  @Test
  void anExtensionBuiltForAnotherKeycloakVersionStopsStartUp() throws Exception {
    Path jar = withBuildVersion(KeycloakTestStack.extensionJar(), "0.0.0");
    StringBuilder logs = new StringBuilder();
    try (GenericContainer<?> keycloak =
        KeycloakTestStack.keycloak(jar)
            .withCommand("start-dev")
            .withExposedPorts(8080)
            .withLogConsumer(frame -> logs.append(frame.getUtf8String()))
            .waitingFor(
                Wait.forHttp("/realms/master")
                    .forPort(8080)
                    .withStartupTimeout(java.time.Duration.ofMinutes(3)))) {
      assertThatThrownBy(keycloak::start).isInstanceOf(RuntimeException.class);
    }
    assertThat(logs.toString()).contains("built for Keycloak 0.0.0 and refuses to run on 26.7.4");
  }

  private static Path withBuildVersion(Path jar, String version) throws Exception {
    Path copy = Files.createTempFile("divalhr-provisioning-", ".jar");
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar));
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(copy))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        out.putNextEntry(new ZipEntry(entry.getName()));
        byte[] bytes = readAll(in);
        if (entry.getName().equals("META-INF/divalhr-provisioning.properties")) {
          bytes = ("keycloak.version=" + version + "\n").getBytes(StandardCharsets.UTF_8);
        }
        out.write(bytes);
        out.closeEntry();
      }
    }
    return copy;
  }

  private static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    in.transferTo(buffer);
    return buffer.toByteArray();
  }

  private static void waitForNextPeriod() throws InterruptedException {
    Thread.sleep(30_000 - (System.currentTimeMillis() % 30_000) + 500);
  }
}
