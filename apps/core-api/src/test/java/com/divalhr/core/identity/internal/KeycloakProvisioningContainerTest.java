package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.IdentityDirectory.IdentityConflict;
import com.divalhr.core.identity.application.IdentityDirectory.Provisioned;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningResult;
import com.divalhr.core.identity.application.InvitationMailer.InvitationMessage;
import com.divalhr.core.identity.domain.DeliveryState;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.identity.internal.keycloak.KeycloakIdentityDirectory;
import com.divalhr.core.identity.internal.keycloak.KeycloakProperties;
import com.divalhr.core.identity.internal.mail.InvitationTemplates;
import com.divalhr.core.identity.internal.mail.MailProperties;
import com.divalhr.core.identity.internal.mail.SmtpInvitationMailer;
import com.divalhr.core.platform.tenancy.TenantId;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guardrails 1 and 2 against a real Keycloak 26.7 importing the committed development realm, with a
 * Mailpit capture: the provisioner can give {@code tenant-admin} and {@code employee} and can never
 * give {@code platform-admin} or touch identities it did not create; provisioning is idempotent per
 * invitation; the "choose your password" and invitation emails are delivered.
 */
class KeycloakProvisioningContainerTest {

  /** Same pinned image as infrastructure/docker/compose.yaml. */
  static final String KEYCLOAK_IMAGE =
      "quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c";

  /** Same pinned image as infrastructure/docker/compose.yaml. */
  static final String MAILPIT_IMAGE = MailpitImage.PINNED;

  static final String REALM = "divalhr-dev";
  static final String ADMIN = "test-kc-admin";
  static final String ADMIN_PASSWORD = "test-only-kc-admin-password";
  static final String PROVISIONER_SECRET = "dev-only-provisioner-secret-2026";
  static final TenantId TENANT =
      new TenantId(UUID.fromString("00000000-0000-4000-8000-00000000000a"));

  static Network network;
  static GenericContainer<?> mailpit;
  static GenericContainer<?> keycloak;
  static KeycloakIdentityDirectory directory;
  static final HttpClient HTTP = HttpClient.newHttpClient();
  static final JsonMapper JSON = JsonMapper.builder().build();

  @BeforeAll
  static void start() {
    network = Network.newNetwork();
    mailpit =
        new GenericContainer<>(MAILPIT_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("mailpit")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));
    mailpit.start();
    Path realm =
        Path.of(System.getProperty("divalhr.repoRoot", "../.."))
            .resolve("infrastructure/docker/keycloak/realm-divalhr-dev.json");
    keycloak =
        new GenericContainer<>(KEYCLOAK_IMAGE)
            .withNetwork(network)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(realm),
                "/opt/keycloak/data/import/realm-divalhr-dev.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/" + REALM)
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    directory =
        new KeycloakIdentityDirectory(
            new KeycloakProperties(
                baseUrl(),
                REALM,
                null,
                PROVISIONER_SECRET,
                null,
                "http://localhost:5173/auth/callback",
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)),
            "development",
            JSON);
  }

  @AfterAll
  static void stop() {
    if (keycloak != null) {
      keycloak.stop();
    }
    if (mailpit != null) {
      mailpit.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  static String baseUrl() {
    return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
  }

  static String mailpitUrl() {
    return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
  }

  private static ProvisioningRequest request(UUID invitation, String email, TenantRole role) {
    return new ProvisioningRequest(
        invitation, TENANT, EmailAddress.parse(email).orElseThrow(), role, InvitationLocale.EN);
  }

  private static String unique(String prefix) {
    return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
        .toLowerCase(Locale.ROOT);
  }

  @Test
  void provisionsEachTenantRoleWithTheTenantAndNothingElse() throws Exception {
    for (TenantRole role : TenantRole.values()) {
      UUID invitation = UUID.randomUUID();
      String email = unique(role.wireName());
      ProvisioningResult result = directory.provision(request(invitation, email, role));
      assertThat(result).isInstanceOf(Provisioned.class);
      String subject = ((Provisioned) result).subject();
      JsonNode token = exampleAccessToken(subject);
      assertThat(token.path("tenant_id").asString()).isEqualTo(TENANT.toString());
      assertThat(token.path("realm_access").path("roles").toString())
          .isEqualTo("[\"" + role.wireName() + "\"]");
      assertThat(token.has("email")).isFalse();
      assertThat(token.has("preferred_username")).isFalse();
      JsonNode user = adminGet("/users/" + subject);
      // Verified only because the invitee presented the single-use token sent to this address.
      assertThat(user.path("emailVerified").asBoolean()).isTrue();
      assertThat(user.path("requiredActions").toString()).contains("UPDATE_PASSWORD");
      // Idempotent: the same invitation finds the same identity.
      assertThat(directory.provision(request(invitation, email, role)))
          .isEqualTo(new Provisioned(subject));
    }
  }

  @Test
  void anAddressThatAlreadyHasAnIdentityIsAConflictWithoutDetail() {
    String email = unique("taken");
    directory.provision(request(UUID.randomUUID(), email, TenantRole.EMPLOYEE));
    assertThat(directory.provision(request(UUID.randomUUID(), email, TenantRole.TENANT_ADMIN)))
        .isInstanceOf(IdentityConflict.class);
  }

  @Test
  void compensationRemovesOnlyTheInvitationsOwnIdentity() throws Exception {
    UUID mine = UUID.randomUUID();
    String email = unique("comp");
    String subject =
        ((Provisioned) directory.provision(request(mine, email, TenantRole.EMPLOYEE))).subject();
    directory.compensate(UUID.randomUUID());
    assertThat(adminStatus("/users/" + subject)).isEqualTo(200);
    directory.compensate(mine);
    assertThat(adminStatus("/users/" + subject)).isEqualTo(404);
  }

  @Test
  void theProvisionerCanNeverGrantPlatformAdminOrTouchOtherIdentities() throws Exception {
    String provisioner = provisionerToken();
    String subject =
        ((Provisioned)
                directory.provision(
                    request(UUID.randomUUID(), unique("guard"), TenantRole.EMPLOYEE)))
            .subject();
    String platformAdmin = adminGet("/roles/platform-admin").path("id").asString();
    String tenantAdmin = adminGet("/roles/tenant-admin").path("id").asString();
    String mapping = "[{\"id\":\"%s\",\"name\":\"%s\"}]";
    assertThat(
            call(
                provisioner,
                "POST",
                "/users/" + subject + "/role-mappings/realm",
                mapping.formatted(platformAdmin, "platform-admin")))
        .as("direct platform-admin mapping")
        .isEqualTo(403);
    assertThat(
            call(
                provisioner,
                "POST",
                "/users/" + subject + "/role-mappings/realm",
                mapping.formatted(tenantAdmin, "tenant-admin")))
        .as("no role mapping at all")
        .isEqualTo(403);
    String employeeGroup =
        adminGet("/groups?search=divalhr-role-employee&exact=true").get(0).path("id").asString();
    String adminGroup =
        adminGet("/groups?search=divalhr-role-tenant-admin&exact=true")
            .get(0)
            .path("id")
            .asString();
    assertThat(
            call(
                provisioner,
                "POST",
                "/groups/" + employeeGroup + "/role-mappings/realm",
                mapping.formatted(platformAdmin, "platform-admin")))
        .as("group role change")
        .isEqualTo(403);
    assertThat(
            call(
                provisioner,
                "POST",
                "/roles-by-id/" + adminGet("/roles/employee").path("id").asString() + "/composites",
                mapping.formatted(platformAdmin, "platform-admin")))
        .as("composite role")
        .isEqualTo(403);
    assertThat(call(provisioner, "PUT", "/users/" + subject + "/groups/" + adminGroup, ""))
        .as("moving a member to another role group")
        .isEqualTo(403);
    assertThat(call(provisioner, "POST", "/groups", "{\"name\":\"escalate\"}")).isEqualTo(403);
    String outside = unique("outside");
    assertThat(
            call(
                provisioner,
                "POST",
                "/users",
                "{\"username\":\""
                    + outside
                    + "\",\"email\":\""
                    + outside
                    + "\",\"enabled\":true}"))
        .as("creating a user outside the role groups")
        .isEqualTo(403);
    String seed =
        adminGet("/users?username=dev-platform-admin&exact=true").get(0).path("id").asString();
    assertThat(call(provisioner, "GET", "/users/" + seed, null)).isEqualTo(403);
    assertThat(call(provisioner, "PUT", "/users/" + seed, "{\"enabled\":false}")).isEqualTo(403);
    assertThat(call(provisioner, "DELETE", "/users/" + seed, null)).isEqualTo(403);
    assertThat(call(provisioner, "PUT", "/users/" + seed + "/groups/" + adminGroup, ""))
        .isEqualTo(403);
    assertThat(call(provisioner, "PUT", "", "{\"displayName\":\"x\"}"))
        .as("realm settings")
        .isEqualTo(403);
    // A representation asking for more roles at creation is ignored.
    String sneaky = unique("sneaky");
    assertThat(
            call(
                provisioner,
                "POST",
                "/users",
                "{\"username\":\"%s\",\"email\":\"%s\",\"enabled\":true,\"groups\":[\"/divalhr-role-employee\"],\"realmRoles\":[\"platform-admin\"],\"clientRoles\":{\"realm-management\":[\"manage-users\"]}}"
                    .formatted(sneaky, sneaky)))
        .isEqualTo(201);
    String sneakySubject =
        adminGet("/users?email=" + enc(sneaky) + "&exact=true").get(0).path("id").asString();
    assertThat(adminGet("/users/" + sneakySubject + "/role-mappings/realm/composite").toString())
        .contains("\"employee\"")
        .doesNotContain("platform-admin");
  }

  @Test
  void theChooseYourPasswordEmailIsSentInTheInviteesLanguage() throws Exception {
    String email = unique("password");
    String subject =
        ((Provisioned) directory.provision(request(UUID.randomUUID(), email, TenantRole.EMPLOYEE)))
            .subject();
    directory.requestCredentialSetup(subject);
    JsonNode message = awaitMessage(email);
    assertThat(message.path("Subject").asString()).isEqualTo("Update Your Account");
  }

  @Test
  void theSmtpMailerDeliversTheVersionedInvitationTemplate() throws Exception {
    SmtpInvitationMailer mailer =
        new SmtpInvitationMailer(
            new MailProperties(
                mailpit.getHost(),
                mailpit.getMappedPort(1025),
                null,
                null,
                "no-reply@divalhr.test",
                "DivalHR (test)",
                false,
                false,
                Duration.ofSeconds(10)),
            "test",
            new InvitationTemplates());
    String email = unique("smtp");
    DeliveryState state =
        mailer.send(
            new InvitationMessage(
                EmailAddress.parse(email).orElseThrow(),
                InvitationLocale.FR,
                "Société Minière de Kinshasa",
                "Africa/Kinshasa",
                TenantRole.EMPLOYEE,
                Instant.now().plus(Duration.ofDays(7)),
                "http://localhost:5173/invitation#token=" + "B".repeat(43)));
    assertThat(state).isEqualTo(DeliveryState.SENT);
    JsonNode summary = awaitMessage(email);
    assertThat(summary.path("Subject").asString())
        .isEqualTo("Invitation à rejoindre Société Minière de Kinshasa sur DivalHR");
    JsonNode full = get(mailpitUrl() + "/api/v1/message/" + summary.path("ID").asString(), null);
    assertThat(full.path("Text").asString())
        .contains("Employé")
        .contains("#token=" + "B".repeat(43));
    assertThat(full.path("HTML").asString()).contains("lang=\"fr\"").doesNotContain(email);

    SmtpInvitationMailer unreachable =
        new SmtpInvitationMailer(
            new MailProperties(
                "127.0.0.1",
                1,
                null,
                null,
                "no-reply@divalhr.test",
                null,
                false,
                false,
                Duration.ofSeconds(2)),
            "test",
            new InvitationTemplates());
    assertThat(
            unreachable.send(
                new InvitationMessage(
                    EmailAddress.parse(email).orElseThrow(),
                    InvitationLocale.EN,
                    "Org",
                    "UTC",
                    TenantRole.EMPLOYEE,
                    Instant.now(),
                    "http://localhost/invitation#token=x")))
        .isEqualTo(DeliveryState.FAILED);
  }

  // --------------------------------------------------------------------------------------------

  private static JsonNode awaitMessage(String email) throws Exception {
    for (int i = 0; i < 50; i++) {
      JsonNode list = get(mailpitUrl() + "/api/v1/search?query=" + enc("to:" + email), null);
      if (list.path("messages").size() > 0) {
        return list.path("messages").get(0);
      }
      Thread.sleep(200);
    }
    throw new AssertionError("no message captured");
  }

  private static String adminToken() throws Exception {
    return tokenFor("master", "admin-cli", null, ADMIN, ADMIN_PASSWORD);
  }

  private static String provisionerToken() throws Exception {
    return tokenFor(REALM, "divalhr-core-provisioner", PROVISIONER_SECRET, null, null);
  }

  private static String tokenFor(
      String realm, String client, String secret, String user, String password) throws Exception {
    StringBuilder form = new StringBuilder("client_id=" + enc(client));
    if (secret != null) {
      form.append("&grant_type=client_credentials&client_secret=").append(enc(secret));
    } else {
      form.append("&grant_type=password&username=")
          .append(enc(user))
          .append("&password=")
          .append(enc(password));
    }
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create(baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).path("access_token").asString();
  }

  private static JsonNode adminGet(String path) throws Exception {
    return get(baseUrl() + "/admin/realms/" + REALM + path, adminToken());
  }

  private static int adminStatus(String path) throws Exception {
    return HTTP.send(
            HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/realms/" + REALM + path))
                .header("Authorization", "Bearer " + adminToken())
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  private static JsonNode exampleAccessToken(String subject) throws Exception {
    String web = adminGet("/clients?clientId=divalhr-web").get(0).path("id").asString();
    return adminGet(
        "/clients/"
            + web
            + "/evaluate-scopes/generate-example-access-token?scope=openid&userId="
            + subject);
  }

  private static int call(String bearer, String method, String path, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/realms/" + REALM + path))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json");
    builder =
        switch (method) {
          case "GET" -> builder.GET();
          case "DELETE" -> builder.DELETE();
          default ->
              builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        };
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  private static JsonNode get(String url, String bearer) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(url).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
