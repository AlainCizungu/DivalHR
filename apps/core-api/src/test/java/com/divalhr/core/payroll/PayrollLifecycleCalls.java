package com.divalhr.core.payroll;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** MockMvc calls of the MVP-092 payroll-input lifecycle operations. */
final class PayrollLifecycleCalls {

  static final String RUNS = "/api/v1/payroll-inputs";
  static final String EXPORTS = "/api/v1/payroll-input-exports";

  private PayrollLifecycleCalls() {}

  static MockHttpServletRequestBuilder supersedePreview(String bearer, String runId) {
    return MockMvcRequestBuilders.post(RUNS + "/" + runId + "/supersede-preview")
        .header("Authorization", bearer);
  }

  static Map<String, Object> superseding(String reason, String explanation, String digest) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reasonCode", reason);
    if (explanation != null) {
      body.put("explanation", explanation);
    }
    body.put("previewDigest", digest);
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder supersede(
      String bearer, String key, String runId, Object body) throws Exception {
    return command(bearer, key, RUNS + "/" + runId + "/supersede", body);
  }

  static MockHttpServletRequestBuilder lockPreview(String bearer, String runId) {
    return MockMvcRequestBuilders.post(RUNS + "/" + runId + "/lock-preview")
        .header("Authorization", bearer);
  }

  static Map<String, Object> locking(String digest) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("previewDigest", digest);
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder lock(String bearer, String key, String runId, Object body)
      throws Exception {
    return command(bearer, key, RUNS + "/" + runId + "/lock", body);
  }

  static MockHttpServletRequestBuilder versions(String bearer, String runId) {
    return MockMvcRequestBuilders.get(RUNS + "/" + runId + "/versions")
        .header("Authorization", bearer);
  }

  static Map<String, Object> exporting(String runId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("runId", runId);
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder createExport(String bearer, String key, Object body)
      throws Exception {
    return command(bearer, key, EXPORTS, body);
  }

  static MockHttpServletRequestBuilder exports(String bearer, String query) {
    return MockMvcRequestBuilders.get(EXPORTS + (query == null ? "" : "?" + query))
        .header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder export(String bearer, String id) {
    return MockMvcRequestBuilders.get(EXPORTS + "/" + id).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder csv(String bearer, String id) {
    return MockMvcRequestBuilders.get(EXPORTS + "/" + id + "/csv").header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder manifest(String bearer, String id) {
    return MockMvcRequestBuilders.get(EXPORTS + "/" + id + "/manifest")
        .header("Authorization", bearer);
  }

  private static MockHttpServletRequestBuilder command(
      String bearer, String key, String path, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.post(path)
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                body instanceof String text
                    ? text
                    : PayrollInputCalls.JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }
}
