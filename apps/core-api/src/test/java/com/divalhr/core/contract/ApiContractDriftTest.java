package com.divalhr.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.support.IntegrationTest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fails the build when the implementation drifts from the authoritative design-first contract in
 * {@code docs/API-SPEC.yaml}. Generated springdoc output is verification input only.
 *
 * <p>Checked for every operation marked {@code x-divalhr-lifecycle: implemented}: that it exists,
 * that nothing else is exposed, operation IDs, success status codes, and top-level response
 * properties.
 */
@IntegrationTest
class ApiContractDriftTest {

  private static final String BASE_PATH = "/api/v1";
  private static final Set<String> METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options");

  @Autowired private MockMvc mvc;

  private Map<String, Object> spec;
  private Map<String, Object> generated;

  @BeforeEach
  void load() throws Exception {
    Path specPath = Path.of(System.getProperty("divalhr.apiSpecPath", "../../docs/API-SPEC.yaml"));
    try (InputStream in = Files.newInputStream(specPath)) {
      spec = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
    String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
    generated = castMap(JsonMapper.builder().build().readValue(json, Map.class));
  }

  @Test
  void implementedOperationsMatchContractExactly() {
    assertThat(operations(generated, true, false).keySet())
        .as("operations exposed by the Core API vs. operations marked implemented in the contract")
        .isEqualTo(operations(spec, false, true).keySet());
  }

  @Test
  void operationIdsStatusCodesAndResponseShapesMatch() {
    Map<String, Map<String, Object>> contract = operations(spec, false, true);
    Map<String, Map<String, Object>> actual = operations(generated, true, false);
    for (Map.Entry<String, Map<String, Object>> entry : contract.entrySet()) {
      Map<String, Object> expectedOp = entry.getValue();
      Map<String, Object> actualOp = actual.get(entry.getKey());
      assertThat(actualOp).as("missing operation %s", entry.getKey()).isNotNull();
      assertThat(actualOp.get("operationId"))
          .as("operationId of %s", entry.getKey())
          .isEqualTo(expectedOp.get("operationId"));
      Set<String> expectedSuccess = successCodes(expectedOp);
      assertThat(successCodes(actualOp))
          .as("2xx responses of %s", entry.getKey())
          .isEqualTo(expectedSuccess);
      for (String code : expectedSuccess) {
        assertThat(responseProperties(generated, actualOp, code))
            .as("response %s properties of %s", code, entry.getKey())
            .isEqualTo(responseProperties(spec, expectedOp, code));
      }
    }
  }

  private static Map<String, Map<String, Object>> operations(
      Map<String, Object> document, boolean stripBasePath, boolean onlyImplemented) {
    Map<String, Map<String, Object>> result = new TreeMap<>();
    Map<String, Object> paths = castMap(document.getOrDefault("paths", Map.of()));
    for (Map.Entry<String, Object> path : paths.entrySet()) {
      String key = path.getKey();
      if (stripBasePath && key.startsWith(BASE_PATH)) {
        key = key.substring(BASE_PATH.length());
      }
      for (Map.Entry<String, Object> method : castMap(path.getValue()).entrySet()) {
        if (!METHODS.contains(method.getKey())) {
          continue;
        }
        Map<String, Object> op = castMap(method.getValue());
        if (onlyImplemented && !"implemented".equals(op.get("x-divalhr-lifecycle"))) {
          continue;
        }
        result.put(method.getKey().toUpperCase(java.util.Locale.ROOT) + " " + key, op);
      }
    }
    return result;
  }

  private static Set<String> successCodes(Map<String, Object> op) {
    Set<String> codes = new TreeSet<>();
    for (String code : castMap(op.getOrDefault("responses", Map.of())).keySet()) {
      if (code.startsWith("2")) {
        codes.add(code);
      }
    }
    return codes;
  }

  private static Set<String> responseProperties(
      Map<String, Object> document, Map<String, Object> op, String code) {
    Map<String, Object> response =
        resolve(document, castMap(castMap(op.get("responses")).get(code)));
    Map<String, Object> content = castMap(response.getOrDefault("content", Map.of()));
    Set<String> properties = new TreeSet<>();
    for (Object media : content.values()) {
      Map<String, Object> schema = resolve(document, castMap(castMap(media).get("schema")));
      properties.addAll(castMap(schema.getOrDefault("properties", Map.of())).keySet());
    }
    return properties;
  }

  private static Map<String, Object> resolve(
      Map<String, Object> document, Map<String, Object> node) {
    Object ref = node.get("$ref");
    if (ref == null) {
      return node;
    }
    Map<String, Object> current = document;
    for (String part : ref.toString().substring(2).split("/")) {
      current = castMap(current.get(part));
    }
    return resolve(document, current);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return value == null ? Map.of() : (Map<String, Object>) value;
  }
}
