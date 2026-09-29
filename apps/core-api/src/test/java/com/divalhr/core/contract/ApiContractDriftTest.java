package com.divalhr.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.security.TenantScoped;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.tenant.domain.SupportedConfiguration;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMappings;

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

  @Test
  void requestBodiesMatchContract() {
    Map<String, Map<String, Object>> contract = operations(spec, false, true);
    Map<String, Map<String, Object>> actual = operations(generated, true, false);
    for (Map.Entry<String, Map<String, Object>> entry : contract.entrySet()) {
      Object expectedBody = entry.getValue().get("requestBody");
      Object actualBody = actual.get(entry.getKey()).get("requestBody");
      if (expectedBody == null) {
        assertThat(actualBody).as("unexpected request body on %s", entry.getKey()).isNull();
        continue;
      }
      assertThat(bodyProperties(generated, castMap(actualBody)))
          .as("request body properties of %s", entry.getKey())
          .isEqualTo(bodyProperties(spec, castMap(expectedBody)));
    }
  }

  @Test
  void hierarchyPathsAreExposedUnderTheFullPublicBasePath() {
    Map<String, Object> paths = castMap(generated.get("paths"));
    for (String path :
        List.of(
            "/api/v1/legal-entities",
            "/api/v1/sites",
            "/api/v1/departments",
            "/api/v1/cost-centers")) {
      assertThat(paths).as(path).containsKey(path);
    }
    assertThat(castMap(spec.get("paths"))).containsKeys("/departments", "/cost-centers");
    assertThat(((List<?>) spec.get("servers")).toString()).contains("/api/v1");
  }

  @Test
  void requiredRolesAndScopesMatchHandlers() {
    Map<String, String> expected = new TreeMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : operations(spec, false, true).entrySet()) {
      Object role = entry.getValue().get("x-divalhr-required-role");
      Object scope = entry.getValue().get("x-divalhr-scope");
      if (role != null || scope != null) {
        expected.put(entry.getKey(), scope + "/" + role);
      }
    }
    Map<String, String> actual = new TreeMap<>();
    for (var mapping : handlerMappings.getHandlerMethods().entrySet()) {
      HandlerMethod handler = mapping.getValue();
      String marker = null;
      PlatformScoped platform = handler.getMethodAnnotation(PlatformScoped.class);
      TenantScoped tenant =
          AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), TenantScoped.class);
      if (platform != null) {
        marker = "platform/" + PlatformScoped.ROLE;
      } else if (tenant != null && !tenant.role().isEmpty()) {
        marker = "tenant/" + tenant.role();
      }
      if (marker == null) {
        continue;
      }
      for (String pattern : mapping.getKey().getPatternValues()) {
        if (!pattern.startsWith(BASE_PATH)) {
          continue;
        }
        for (var method : mapping.getKey().getMethodsCondition().getMethods()) {
          actual.put(method.name() + " " + pattern.substring(BASE_PATH.length()), marker);
        }
      }
    }
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  @SuppressWarnings("unchecked")
  void errorCodesMatchContract() {
    Map<String, Object> schemas = castMap(castMap(spec.get("components")).get("schemas"));
    List<String> contract = (List<String>) castMap(schemas.get("ErrorCode")).get("enum");
    assertThat(
            java.util.Arrays.stream(com.divalhr.core.platform.error.ErrorCode.values())
                .map(Enum::name)
                .toList())
        .containsExactlyElementsOf(contract);
  }

  @Test
  @SuppressWarnings("unchecked")
  void supportedConfigurationCannotDriftFromContract() {
    Map<String, Object> schemas = castMap(castMap(spec.get("components")).get("schemas"));
    Map<String, Object> create =
        castMap(castMap(schemas.get("CreateOrganization")).get("properties"));
    List<String> countries = (List<String>) castMap(create.get("countryCode")).get("enum");
    List<String> locales = (List<String>) castMap(create.get("defaultLocale")).get("enum");
    Map<String, Object> timezone = castMap(create.get("timezone"));
    List<String> currencies =
        (List<String>) castMap(castMap(create.get("currencies")).get("items")).get("enum");
    Map<String, Object> zonesByCountry = castMap(timezone.get("x-divalhr-timezones-by-country"));

    assertThat(SupportedConfiguration.COUNTRIES.keySet())
        .containsExactlyInAnyOrderElementsOf(countries);
    assertThat(SupportedConfiguration.LOCALES).containsExactlyInAnyOrderElementsOf(locales);
    Set<String> allZones = new TreeSet<>();
    Set<String> allCurrencies = new TreeSet<>();
    for (var country : SupportedConfiguration.COUNTRIES.values()) {
      assertThat(country.timezones())
          .containsExactlyInAnyOrderElementsOf(
              (List<String>) zonesByCountry.get(country.countryCode()));
      allZones.addAll(country.timezones());
      allCurrencies.addAll(country.currencies());
    }
    assertThat(allZones).containsExactlyInAnyOrderElementsOf((List<String>) timezone.get("enum"));
    assertThat(allCurrencies).containsExactlyInAnyOrderElementsOf(currencies);
    // The response schema documents the same allow-list.
    Map<String, Object> org = castMap(castMap(schemas.get("Organization")).get("properties"));
    assertThat((List<String>) castMap(org.get("countryCode")).get("enum")).isEqualTo(countries);
    assertThat((List<String>) castMap(org.get("timezone")).get("enum"))
        .isEqualTo(timezone.get("enum"));
    // Hierarchy schemas share the same allow-lists (MVP-002).
    for (String schema : List.of("CreateLegalEntity", "LegalEntity")) {
      Map<String, Object> props = castMap(castMap(schemas.get(schema)).get("properties"));
      assertThat((List<String>) castMap(props.get("countryCode")).get("enum"))
          .as(schema)
          .isEqualTo(countries);
    }
    for (String schema : List.of("CreateSite", "Site")) {
      Map<String, Object> props = castMap(castMap(schemas.get(schema)).get("properties"));
      assertThat((List<String>) castMap(props.get("timezone")).get("enum"))
          .as(schema)
          .isEqualTo(timezone.get("enum"));
    }
  }

  private static Set<String> bodyProperties(
      Map<String, Object> document, Map<String, Object> body) {
    Set<String> properties = new TreeSet<>();
    for (Object media :
        castMap(resolve(document, body).getOrDefault("content", Map.of())).values()) {
      properties.addAll(
          castMap(
                  resolve(document, castMap(castMap(media).get("schema")))
                      .getOrDefault("properties", Map.of()))
              .keySet());
    }
    return properties;
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
