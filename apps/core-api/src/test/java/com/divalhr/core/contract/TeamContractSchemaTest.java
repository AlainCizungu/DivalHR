package com.divalhr.core.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Issue #23 amendment 2: the authoritative contract expresses the team exactly-one-parent (XOR)
 * rule mechanically. The {@code CreateTeam} schema in {@code docs/API-SPEC.yaml} is validated as a
 * JSON Schema 2020-12 document against the approved payload matrix, where an absent property and an
 * explicit {@code null} both mean "not supplied".
 */
class TeamContractSchemaTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ID = "11111111-1111-4111-8111-111111111111";
  private static Map<String, Object> spec;
  private static JsonSchema createTeam;

  @BeforeAll
  static void load() throws Exception {
    Path specPath = Path.of(System.getProperty("divalhr.apiSpecPath", "../../docs/API-SPEC.yaml"));
    try (InputStream in = Files.newInputStream(specPath)) {
      spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
    Object schema = map(map(spec.get("components")).get("schemas")).get("CreateTeam");
    createTeam =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(JSON.valueToTree(schema));
  }

  @ParameterizedTest(name = "{0} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "department UUID only | {\"departmentId\": \"ID\"} | valid",
        "cost-center UUID only | {\"costCenterId\": \"ID\"} | valid",
        "department UUID, cost center null | {\"departmentId\": \"ID\", \"costCenterId\": null} |"
            + " valid",
        "cost-center UUID, department null | {\"costCenterId\": \"ID\", \"departmentId\": null} |"
            + " valid",
        "both absent | {} | invalid",
        "both explicit null | {\"departmentId\": null, \"costCenterId\": null} | invalid",
        "both UUIDs | {\"departmentId\": \"ID\", \"costCenterId\": \"ID\"} | invalid",
        "malformed department | {\"departmentId\": \"not-a-uuid\"} | invalid",
        "malformed cost center | {\"costCenterId\": \"\"} | invalid",
        "site supplied | {\"departmentId\": \"ID\", \"siteId\": \"ID\"} | invalid",
        "tenant supplied | {\"departmentId\": \"ID\", \"tenantId\": \"ID\"} | invalid",
      })
  void createTeamSchemaEnforcesExactlyOneParent(String label, String parents, String expected)
      throws Exception {
    JsonNode parentFields = JSON.readTree(parents.replace("ID", ID));
    var body = JSON.createObjectNode();
    body.setAll((com.fasterxml.jackson.databind.node.ObjectNode) parentFields);
    body.put("code", "EQ-01");
    body.put("name", "Équipe de maintenance");
    body.put("effectiveFrom", "2026-01-01");
    assertThat(createTeam.validate(body).isEmpty()).as(label).isEqualTo("valid".equals(expected));
  }

  @Test
  void bothTeamOperationsDeclareTheExactlyOneOfRule() {
    Map<String, Object> teams = map(map(spec.get("paths")).get("/teams"));
    for (String method : List.of("get", "post")) {
      assertThat(map(teams.get(method)).get("x-divalhr-exactly-one-of"))
          .as(method)
          .isEqualTo(List.of("departmentId", "costCenterId"));
    }
    Map<String, Object> schema =
        map(map(map(spec.get("components")).get("schemas")).get("CreateTeam"));
    assertThat((List<?>) schema.get("oneOf")).hasSize(2);
    assertThat(schema.get("additionalProperties")).isEqualTo(false);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
