package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.support.Employees;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-070E calls over the public API: previews, creations, history, reads and downloads. */
final class TimesheetExportCalls {

  static final String EXPORTS = "/api/v1/timesheet-exports";

  private TimesheetExportCalls() {}

  static Map<String, Object> scope(Object legalEntity, Object site, Object monday) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("legalEntityId", String.valueOf(legalEntity));
    body.put("siteId", String.valueOf(site));
    body.put("weekStart", String.valueOf(monday));
    return body;
  }

  /** The creation body of a reviewed preview. */
  static Map<String, Object> creation(Map<String, Object> scope, JsonNode preview) {
    return creation(scope, preview.get("previewDigest").asText());
  }

  static Map<String, Object> creation(Map<String, Object> scope, String digest) {
    Map<String, Object> body = new LinkedHashMap<>(scope);
    body.put("previewDigest", digest);
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder preview(String bearer, Object body) throws Exception {
    return Employees.postJson(bearer, EXPORTS + "/preview", text(body));
  }

  static MockHttpServletRequestBuilder create(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request = Employees.postJson(bearer, EXPORTS, text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder list(String bearer, String query) {
    return get(EXPORTS + (query == null ? "" : "?" + query)).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder export(String bearer, String id) {
    return get(EXPORTS + "/" + id).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder csv(String bearer, String id) {
    return get(EXPORTS + "/" + id + "/csv").header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder manifest(String bearer, String id) {
    return get(EXPORTS + "/" + id + "/manifest").header("Authorization", bearer);
  }

  private static String text(Object body) throws Exception {
    return body instanceof String raw ? raw : JSON.writeValueAsString(body);
  }
}
