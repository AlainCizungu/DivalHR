package com.divalhr.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * MVP-010: the authoritative contract only lets tenant roles through, never accepts a tenant or any
 * server-owned property, keeps the email address out of receipts, and marks the anonymous
 * operations as public with no token requirement.
 */
class InvitationContractSchemaTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static Map<String, Object> spec;
  private static JsonSchema createInvitation;
  private static JsonSchema tokenRequest;

  @BeforeAll
  static void load() throws Exception {
    Path specPath = Path.of(System.getProperty("divalhr.apiSpecPath", "../../docs/API-SPEC.yaml"));
    try (InputStream in = Files.newInputStream(specPath)) {
      spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
    Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
    ObjectNode create = JSON.valueToTree(schemas.get("CreateInvitation"));
    ((ObjectNode) create.get("properties"))
        .set("role", JSON.valueToTree(schemas.get("InvitationRole")));
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    createInvitation = factory.getSchema(create);
    tokenRequest = factory.getSchema(JSON.valueToTree(schemas.get("InvitationTokenRequest")));
  }

  @ParameterizedTest(name = "{0} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "employee | {\"email\": \"a@example.cd\", \"role\": \"employee\", \"locale\": \"fr\"} |"
            + " valid",
        "tenant admin | {\"email\": \"a@example.cd\", \"role\": \"tenant-admin\", \"locale\":"
            + " \"en\"} | valid",
        "platform admin | {\"email\": \"a@example.cd\", \"role\": \"platform-admin\", \"locale\":"
            + " \"fr\"} | invalid",
        "manager | {\"email\": \"a@example.cd\", \"role\": \"manager\", \"locale\": \"fr\"} |"
            + " invalid",
        "tenant supplied | {\"email\": \"a@example.cd\", \"role\": \"employee\", \"locale\":"
            + " \"fr\", \"tenantId\": \"x\"} | invalid",
        "status supplied | {\"email\": \"a@example.cd\", \"role\": \"employee\", \"locale\":"
            + " \"fr\", \"status\": \"ACCEPTED\"} | invalid",
        "token supplied | {\"email\": \"a@example.cd\", \"role\": \"employee\", \"locale\": \"fr\","
            + " \"token\": \"x\"} | invalid",
        "unsupported locale | {\"email\": \"a@example.cd\", \"role\": \"employee\", \"locale\":"
            + " \"sw\"} | invalid",
        "missing email | {\"role\": \"employee\", \"locale\": \"fr\"} | invalid",
      })
  void createInvitationSchema(String label, String body, String expected) throws Exception {
    JsonNode node = JSON.readTree(body);
    assertThat(createInvitation.validate(node).isEmpty())
        .as(label)
        .isEqualTo("valid".equals(expected));
  }

  @Test
  void tokenRequestsAcceptOnlyTheToken() throws Exception {
    assertThat(tokenRequest.validate(JSON.readTree("{\"token\": \"abc\"}"))).isEmpty();
    assertThat(tokenRequest.validate(JSON.readTree("{\"token\": \"abc\", \"tenantId\": \"x\"}")))
        .isNotEmpty();
    assertThat(tokenRequest.validate(JSON.readTree("{}"))).isNotEmpty();
  }

  @Test
  void receiptsCarryNoAddressAndPublicOperationsNeedNoToken() {
    Map<String, Object> schemas = map(map(spec.get("components")).get("schemas"));
    assertThat(map(map(schemas.get("InvitationReceipt")).get("properties")))
        .doesNotContainKey("email");
    assertThat(map(map(schemas.get("InvitationPreview")).get("properties")).keySet())
        .containsExactlyInAnyOrder("role", "locale", "expiresAt");
    assertThat(
            ((List<?>) map(schemas.get("InvitationRole")).get("enum"))
                .stream().map(String::valueOf).toList())
        .containsExactlyInAnyOrder("tenant-admin", "employee");
    Map<String, Object> paths = map(spec.get("paths"));
    for (String path : List.of("/public/invitations/inspect", "/public/invitations/accept")) {
      Map<String, Object> post = map(map(paths.get(path)).get("post"));
      assertThat(post.get("x-divalhr-scope")).as(path).isEqualTo("public");
      assertThat(post.get("security")).as(path).isEqualTo(List.of());
      assertThat(post).as(path).doesNotContainKey("x-divalhr-required-role");
    }
    for (String path :
        List.of(
            "/invitations",
            "/invitations/{invitationId}/revoke",
            "/invitations/{invitationId}/resend")) {
      for (Object operation : map(paths.get(path)).values()) {
        Map<String, Object> op = map(operation);
        assertThat(op.get("x-divalhr-scope")).as(path).isEqualTo("tenant");
        assertThat(op.get("x-divalhr-required-role")).as(path).isEqualTo("tenant-admin");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
