package com.divalhr.core.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** MockMvc calls of the MVP-090A payroll-input operations. */
final class PayrollInputCalls {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String PATH = "/api/v1/payroll-inputs";

  private PayrollInputCalls() {}

  static Map<String, Object> scope(Object legalEntity, Object start, Object end) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("legalEntityId", legalEntity == null ? null : legalEntity.toString());
    scope.put("periodStart", start == null ? null : start.toString());
    scope.put("periodEnd", end == null ? null : end.toString());
    return scope;
  }

  static Map<String, Object> generating(Map<String, Object> scope, JsonNode preview) {
    return generating(
        scope, preview.get("previewDigest").asText(), preview.get("sourceVersionsDigest").asText());
  }

  static Map<String, Object> generating(Map<String, Object> scope, String digest, String versions) {
    Map<String, Object> body = new LinkedHashMap<>(scope);
    body.put("previewDigest", digest);
    body.put("sourceVersionsDigest", versions);
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder preview(String bearer, Object body) throws Exception {
    return post(bearer, PATH + "/preview", body);
  }

  static MockHttpServletRequestBuilder generate(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request = post(bearer, PATH, body);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder list(String bearer, String query) {
    return MockMvcRequestBuilders.get(PATH + (query == null ? "" : "?" + query))
        .header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder run(String bearer, String id) {
    return MockMvcRequestBuilders.get(PATH + "/" + id).header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder post(String bearer, String path, Object body)
      throws Exception {
    return MockMvcRequestBuilders.post(path)
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body instanceof String text ? text : JSON.writeValueAsString(body));
  }
}
