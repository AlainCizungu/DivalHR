package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.support.Employees;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-071 period-lock calls (Issue #139). */
final class TimesheetPeriodCalls {

  static final String PERIODS = "/api/v1/timesheet-periods";

  /** A valid explanation (16 characters). */
  static final String EXPLANATION = "Pointage du lundi à corriger.";

  private TimesheetPeriodCalls() {}

  static Map<String, Object> scope(Object legalEntity, Object site, Object monday) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("legalEntityId", String.valueOf(legalEntity));
    body.put("siteId", String.valueOf(site));
    body.put("weekStart", String.valueOf(monday));
    return body;
  }

  /** A lock body from a preview: its versions and digest. */
  static Map<String, Object> locking(Map<String, Object> scope, JsonNode preview) {
    return locking(
        scope,
        preview.get("generationVersion").asInt(),
        preview.get("lock").get("version").asInt(),
        preview.get("previewDigest").asText());
  }

  static Map<String, Object> locking(
      Map<String, Object> scope, int generationVersion, int lockVersion, String digest) {
    Map<String, Object> body = new LinkedHashMap<>(scope);
    body.put("generationVersion", generationVersion);
    body.put("lockVersion", lockVersion);
    body.put("previewDigest", digest);
    body.put("confirm", true);
    return body;
  }

  /** An unlock preview body. */
  static Map<String, Object> reasoned(
      Map<String, Object> scope, Object reason, Object explanation) {
    Map<String, Object> body = new LinkedHashMap<>(scope);
    body.put("reasonCode", reason);
    body.put("explanation", explanation);
    return body;
  }

  /** An unlock body from its preview (the same reason and explanation). */
  static Map<String, Object> unlocking(Map<String, Object> reasoned, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(reasoned);
    body.put("lockVersion", preview.get("lock").get("version").asInt());
    body.put("lockActionId", preview.get("currentLock").get("actionId").asText());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder previewLock(String bearer, Object body) throws Exception {
    return Employees.postJson(bearer, PERIODS + "/lock-preview", text(body));
  }

  static MockHttpServletRequestBuilder lock(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, PERIODS + "/locks", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder previewUnlock(String bearer, Object body) throws Exception {
    return Employees.postJson(bearer, PERIODS + "/unlock-preview", text(body));
  }

  static MockHttpServletRequestBuilder unlock(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, PERIODS + "/unlocks", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder history(String bearer, String generationId) {
    return get(PERIODS + "/" + generationId).header("Authorization", bearer);
  }

  private static String text(Object body) throws Exception {
    return body instanceof String raw ? raw : JSON.writeValueAsString(body);
  }
}
