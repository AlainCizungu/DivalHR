package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.support.Employees;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-070A calls over the public API: previews, generations, refreshes and reads. */
final class TimesheetCalls {

  static final String TIMESHEETS = "/api/v1/timesheets";
  static final String MINE = "/api/v1/me/timesheets";

  private TimesheetCalls() {}

  static Map<String, Object> period(Object site, Object monday) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("siteId", String.valueOf(site));
    body.put("weekStart", String.valueOf(monday));
    return body;
  }

  static Map<String, Object> confirmed(Map<String, Object> period) {
    Map<String, Object> body = new LinkedHashMap<>(period);
    body.put("confirm", true);
    return body;
  }

  /** The commit body of a reviewed refresh preview. */
  static Map<String, Object> refreshBody(Map<String, Object> period, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(period);
    body.put("version", preview.get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder previewGeneration(String bearer, Object body)
      throws Exception {
    return Employees.postJson(bearer, TIMESHEETS + "/generation-preview", text(body));
  }

  static MockHttpServletRequestBuilder generate(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, TIMESHEETS + "/generations", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder generation(String bearer, String id) {
    return get(TIMESHEETS + "/generations/" + id).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder previewRefresh(String bearer, String id, Object body)
      throws Exception {
    return Employees.postJson(
        bearer, TIMESHEETS + "/generations/" + id + "/refresh-preview", text(body));
  }

  static MockHttpServletRequestBuilder refresh(String bearer, String key, String id, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, TIMESHEETS + "/generations/" + id + "/refreshes", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder timesheet(String bearer, String id) {
    return get(TIMESHEETS + "/" + id).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder mine(String bearer) {
    return get(MINE).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder mine(String bearer, String id) {
    return get(MINE + "/" + id).header("Authorization", bearer);
  }

  private static String text(Object body) throws Exception {
    return body instanceof String raw ? raw : JSON.writeValueAsString(body);
  }
}
