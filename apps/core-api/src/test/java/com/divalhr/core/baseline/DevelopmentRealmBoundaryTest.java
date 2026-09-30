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
      for (JsonNode credential : user.path("credentials")) {
        assertThat(credential.path("value").asText())
            .as("credential of %s", user.path("username").asText())
            .startsWith("dev-only-");
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

  private static List<String> texts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }
}
