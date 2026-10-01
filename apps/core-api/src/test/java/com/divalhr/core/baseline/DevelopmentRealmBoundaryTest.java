package com.divalhr.core.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #27: plain HTTP ({@code sslRequired: none}) is allowed only in the development-only realm
 * {@code divalhr-dev}, which Compose publishes on loopback only. Every repository-owned Keycloak
 * realm import is inspected, whatever its file name, and the browser-facing security settings of
 * the development stack are pinned so that relaxing TLS cannot silently relax anything else.
 */
class DevelopmentRealmBoundaryTest {

  private static final Path ROOT = Path.of(System.getProperty("divalhr.repoRoot", "../.."));
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          ".gradle",
          ".idea",
          "node_modules",
          "build",
          "dist",
          "coverage",
          "test-results",
          "playwright-report",
          ".venv");
  private static final String DEV_REALM = "divalhr-dev";
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<Realm> REALMS = new ArrayList<>();

  private record Realm(Path file, JsonNode json) {

    String name() {
      return json.path("realm").asText();
    }

    /** Keycloak's default when the attribute is absent is {@code external}. */
    String sslRequired() {
      return json.path("sslRequired").asText("external").toLowerCase(Locale.ROOT);
    }
  }

  @BeforeAll
  static void findRealmImports() throws IOException {
    Files.walkFileTree(
        ROOT,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path name = dir.getFileName();
            return name != null && SKIPPED_DIRECTORIES.contains(name.toString())
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (file.toString().endsWith(".json")) {
              JsonNode json = readJson(file);
              // A Keycloak realm representation: a top-level realm name plus realm content.
              if (json != null
                  && json.isObject()
                  && json.path("realm").isTextual()
                  && (json.has("clients") || json.has("users") || json.has("sslRequired"))) {
                REALMS.add(new Realm(file, json));
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static JsonNode readJson(Path file) {
    try {
      return JSON.readTree(file.toFile());
    } catch (IOException notJson) {
      return null;
    }
  }

  private static Realm devRealm() {
    return REALMS.stream()
        .filter(realm -> DEV_REALM.equals(realm.name()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void onlyTheDevelopmentRealmMayAcceptPlainHttp() {
    assertThat(REALMS).as("realm imports found in the repository").isNotEmpty();
    for (Realm realm : REALMS) {
      assertThat(realm.sslRequired())
          .as("sslRequired of %s", realm.file())
          .isIn("none", "external", "all");
      if ("none".equals(realm.sslRequired())) {
        assertThat(realm.name()).as("realm relaxing TLS in %s", realm.file()).isEqualTo(DEV_REALM);
      }
    }
    assertThat(devRealm().sslRequired()).isEqualTo("none");
  }

  @Test
  void theDevelopmentRealmCarriesOnlyPublishedDevelopmentCredentials() {
    JsonNode realm = devRealm().json();
    for (JsonNode user : realm.path("users")) {
      String username = user.path("username").asText();
      for (JsonNode credential : user.path("credentials")) {
        String type = credential.path("type").asText();
        assertThat(type).as("credential type of %s", username).isIn("password", "otp");
        if ("otp".equals(type)) {
          // MVP-011 D11: published development-only TOTP seeds, visibly labelled, for the
          // privileged seed users only.
          assertThat(credential.path("userLabel").asText()).startsWith("DEV-ONLY");
          assertThat(readText(credential.path("secretData").asText()).path("value").asText())
              .as("TOTP seed of %s", username)
              .startsWith("dev-only-totp-");
          JsonNode data = readText(credential.path("credentialData").asText());
          assertThat(data.path("subType").asText()).isEqualTo("totp");
          assertThat(data.path("algorithm").asText()).isEqualTo("HmacSHA1");
          assertThat(data.path("digits").asInt()).isEqualTo(6);
          assertThat(data.path("period").asInt()).isEqualTo(30);
          assertThat(texts(user.path("realmRoles")))
              .as("only privileged seed users carry a TOTP seed: %s", username)
              .containsAnyOf("platform-admin", "tenant-admin");
        } else {
          assertThat(credential.path("value").asText())
              .as("credential of %s", username)
              .startsWith("dev-only-");
        }
      }
    }
    for (JsonNode client : realm.path("clients")) {
      if (client.has("secret")) {
        assertThat(client.path("secret").asText())
            .as("secret of %s", client.path("clientId").asText())
            .startsWith("dev-only-");
      }
    }
  }

  @Test
  void theBrowserClientKeepsPkceExactRedirectsAndNoPasswordGrant() {
    JsonNode web = null;
    for (JsonNode client : devRealm().json().path("clients")) {
      if ("divalhr-web".equals(client.path("clientId").asText())) {
        web = client;
      }
    }
    assertThat(web).as("divalhr-web client").isNotNull();
    assertThat(web.path("publicClient").asBoolean()).isTrue();
    assertThat(web.path("standardFlowEnabled").asBoolean()).isTrue();
    assertThat(web.path("implicitFlowEnabled").asBoolean(true)).isFalse();
    assertThat(web.path("directAccessGrantsEnabled").asBoolean(true)).isFalse();
    assertThat(web.path("attributes").path("pkce.code.challenge.method").asText())
        .isEqualTo("S256");
    assertThat(web.path("attributes").path("oauth2.device.authorization.grant.enabled").asText())
        .isEqualTo("false");
    assertThat(texts(web.path("redirectUris")))
        .containsExactly("http://localhost:5173/auth/callback");
    assertThat(texts(web.path("webOrigins"))).containsExactly("http://localhost:5173");
  }

  @Test
  @SuppressWarnings("unchecked")
  void composePublishesKeycloakOnLoopbackAndKeepsTheBrowserFacingSettings() throws IOException {
    Map<String, Object> compose =
        new Yaml(new LoaderOptions())
            .load(Files.readString(ROOT.resolve("infrastructure/docker/compose.yaml")));
    Map<String, Object> services = (Map<String, Object>) compose.get("services");

    Map<String, Object> keycloak = (Map<String, Object>) services.get("keycloak");
    assertThat((List<String>) keycloak.get("ports"))
        .containsExactly("127.0.0.1:${KEYCLOAK_PORT:-8180}:8080");
    assertThat((List<String>) keycloak.get("volumes"))
        .allSatisfy(volume -> assertThat(volume).contains("realm-" + DEV_REALM + ".json"));
    assertThat(((Map<String, Object>) keycloak.get("environment")).get("KC_HOSTNAME"))
        .isEqualTo("http://localhost:${KEYCLOAK_PORT:-8180}");

    Map<String, Object> core =
        (Map<String, Object>) ((Map<String, Object>) services.get("core-api")).get("environment");
    assertThat(core.get("DIVALHR_ENVIRONMENT")).isEqualTo("development");
    assertThat(core.get("DIVALHR_OIDC_ISSUER"))
        .isEqualTo("http://localhost:${KEYCLOAK_PORT:-8180}/realms/divalhr-dev");
    assertThat(core.get("DIVALHR_OIDC_AUDIENCE")).isEqualTo("divalhr-core-api");
    assertThat(core.get("DIVALHR_CORS_ALLOWED_ORIGINS"))
        .isEqualTo("http://localhost:${WEB_PORT:-5173}");

    Map<String, Object> web =
        (Map<String, Object>) ((Map<String, Object>) services.get("web")).get("environment");
    assertThat(web.get("DIVALHR_OIDC_AUTHORITY"))
        .isEqualTo("http://localhost:${KEYCLOAK_PORT:-8180}/realms/divalhr-dev");
    assertThat(web.get("DIVALHR_OIDC_CLIENT_ID")).isEqualTo("divalhr-web");
  }

  /**
   * MVP-011: the browser flow binds the level of authentication, level 2 is TOTP for the marker
   * role only, and an unenrolled privileged user is denied rather than offered self-enrollment.
   */
  @Test
  void theBrowserFlowRequiresTotpForPrivilegedRolesAndDeniesUnenrolledUsers() {
    JsonNode realm = devRealm().json();
    assertThat(realm.path("browserFlow").asText()).isEqualTo("divalhr browser");
    assertThat(steps(realm, "divalhr browser"))
        .containsExactly("ALTERNATIVE auth-cookie", "ALTERNATIVE flow:divalhr browser forms");
    assertThat(steps(realm, "divalhr browser forms"))
        .containsExactly(
            "CONDITIONAL flow:divalhr browser level 1 password",
            "CONDITIONAL flow:divalhr browser level 2 otp");
    assertThat(steps(realm, "divalhr browser level 1 password"))
        .containsExactly(
            "REQUIRED conditional-level-of-authentication", "REQUIRED auth-username-password-form");
    assertThat(steps(realm, "divalhr browser level 2 otp"))
        .containsExactly(
            "REQUIRED conditional-level-of-authentication",
            "REQUIRED conditional-user-role",
            "CONDITIONAL flow:divalhr browser level 2 enrolled",
            "CONDITIONAL flow:divalhr browser level 2 not enrolled");
    assertThat(steps(realm, "divalhr browser level 2 enrolled"))
        .containsExactly("REQUIRED conditional-user-configured", "REQUIRED auth-otp-form");
    assertThat(steps(realm, "divalhr browser level 2 not enrolled"))
        .containsExactly(
            "REQUIRED conditional-sub-flow-executed", "REQUIRED deny-access-authenticator");

    assertThat(config(realm, "divalhr-loa-1"))
        .containsEntry("loa-condition-level", "1")
        .containsEntry("loa-max-age", "36000");
    assertThat(config(realm, "divalhr-loa-2"))
        .containsEntry("loa-condition-level", "2")
        .containsEntry("loa-max-age", "36000");
    assertThat(config(realm, "divalhr-privileged-role"))
        .containsEntry("condUserRole", "divalhr-privileged-mfa")
        .containsEntry("negate", "false");
    assertThat(config(realm, "divalhr-not-enrolled"))
        .containsEntry("flow_to_check", "divalhr browser level 2 enrolled")
        .containsEntry("check_result", "not-executed");
    assertThat(config(realm, "divalhr-deny-not-enrolled"))
        .containsEntry("denyErrorMessage", "divalhrMfaEnrollmentRequired");

    // No self-enrollment: the TOTP action is never a default required action.
    for (JsonNode action : realm.path("requiredActions")) {
      if ("CONFIGURE_TOTP".equals(action.path("alias").asText())) {
        assertThat(action.path("defaultAction").asBoolean(true)).isFalse();
      }
    }
  }

  @Test
  void theWebClientRequestsTheMfaLevelAndCannotBeDowngraded() {
    JsonNode attributes = client(devRealm().json(), "divalhr-web").path("attributes");
    assertThat(readText(attributes.path("acr.loa.map").asText()))
        .isEqualTo(
            JSON.createObjectNode().put("urn:divalhr:loa:pwd", 1).put("urn:divalhr:loa:mfa", 2));
    assertThat(attributes.path("default.acr.values").asText()).isEqualTo("urn:divalhr:loa:mfa");
    assertThat(attributes.path("minimum.acr.value").asText()).isEqualTo("urn:divalhr:loa:mfa");
  }

  @Test
  void theMarkerRoleIsImpliedOnlyByPrivilegedRoles() {
    JsonNode realm = devRealm().json();
    for (JsonNode role : realm.path("roles").path("realm")) {
      String name = role.path("name").asText();
      List<String> composites = texts(role.path("composites").path("realm"));
      if (name.equals("platform-admin") || name.equals("tenant-admin")) {
        assertThat(composites)
            .as("composites of %s", name)
            .containsExactly("divalhr-privileged-mfa");
      } else {
        assertThat(composites).as("composites of %s", name).isEmpty();
        assertThat(role.path("composite").asBoolean(false)).as("%s is composite", name).isFalse();
      }
    }
    // Nobody is granted the marker directly; it follows the privileged role.
    for (JsonNode user : realm.path("users")) {
      assertThat(texts(user.path("realmRoles"))).doesNotContain("divalhr-privileged-mfa");
    }
  }

  @Test
  void otpPolicyBruteForceEventsAndMessagesArePinned() {
    JsonNode realm = devRealm().json();
    assertThat(realm.path("otpPolicyType").asText()).isEqualTo("totp");
    assertThat(realm.path("otpPolicyAlgorithm").asText()).isEqualTo("HmacSHA1");
    assertThat(realm.path("otpPolicyDigits").asInt()).isEqualTo(6);
    assertThat(realm.path("otpPolicyPeriod").asInt()).isEqualTo(30);
    assertThat(realm.path("otpPolicyLookAheadWindow").asInt()).isEqualTo(1);
    assertThat(realm.path("otpPolicyCodeReusable").asBoolean(true)).isFalse();

    assertThat(realm.path("bruteForceProtected").asBoolean()).isTrue();
    assertThat(realm.path("permanentLockout").asBoolean(true)).isFalse();
    assertThat(realm.path("failureFactor").asInt()).isEqualTo(5);
    assertThat(realm.path("waitIncrementSeconds").asInt()).isEqualTo(60);
    assertThat(realm.path("maxFailureWaitSeconds").asInt()).isEqualTo(900);

    assertThat(realm.path("ssoSessionMaxLifespan").asInt()).isEqualTo(36000);
    assertThat(realm.path("ssoSessionIdleTimeout").asInt()).isEqualTo(1800);

    assertThat(realm.path("eventsEnabled").asBoolean()).isTrue();
    assertThat(realm.path("adminEventsEnabled").asBoolean()).isTrue();
    // Representations of admin changes may carry personal data; they are not stored.
    assertThat(realm.path("adminEventsDetailsEnabled").asBoolean(true)).isFalse();

    assertThat(realm.path("internationalizationEnabled").asBoolean()).isTrue();
    assertThat(texts(realm.path("supportedLocales"))).containsExactlyInAnyOrder("fr", "en");
    for (String locale : List.of("fr", "en")) {
      assertThat(
              realm
                  .path("localizationTexts")
                  .path(locale)
                  .path("divalhrMfaEnrollmentRequired")
                  .asText())
          .as("enrollment-required message in %s", locale)
          .isNotBlank();
    }
  }

  private static List<String> steps(JsonNode realm, String alias) {
    for (JsonNode flow : realm.path("authenticationFlows")) {
      if (alias.equals(flow.path("alias").asText())) {
        List<String> steps = new ArrayList<>();
        List<JsonNode> executions = new ArrayList<>();
        flow.path("authenticationExecutions").forEach(executions::add);
        executions.sort(java.util.Comparator.comparingInt(e -> e.path("priority").asInt()));
        for (JsonNode execution : executions) {
          String what =
              execution.path("authenticatorFlow").asBoolean()
                  ? "flow:" + execution.path("flowAlias").asText()
                  : execution.path("authenticator").asText();
          steps.add(execution.path("requirement").asText() + " " + what);
        }
        return steps;
      }
    }
    throw new AssertionError("missing flow " + alias);
  }

  private static Map<String, String> config(JsonNode realm, String alias) {
    for (JsonNode config : realm.path("authenticatorConfig")) {
      if (alias.equals(config.path("alias").asText())) {
        Map<String, String> values = new java.util.TreeMap<>();
        config
            .path("config")
            .properties()
            .forEach(e -> values.put(e.getKey(), e.getValue().asText()));
        return values;
      }
    }
    throw new AssertionError("missing authenticator config " + alias);
  }

  private static JsonNode client(JsonNode realm, String clientId) {
    for (JsonNode client : realm.path("clients")) {
      if (clientId.equals(client.path("clientId").asText())) {
        return client;
      }
    }
    throw new AssertionError("missing client " + clientId);
  }

  private static JsonNode readText(String json) {
    try {
      return JSON.readTree(json);
    } catch (IOException malformed) {
      throw new AssertionError("malformed embedded JSON", malformed);
    }
  }

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }
}
