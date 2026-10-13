package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.support.Employees;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-062 calls over the public API: previews, commits and a record's history. */
final class CorrectionCalls {

  static final String RECORDS = "/api/v1/attendance/records/";
  static final String MISSING = "/api/v1/attendance/corrections/missing-record";
  static final String EXPLANATION = "Le lecteur de badge était hors service à l’entrée";

  private CorrectionCalls() {}

  /** An instant at an explicit offset (minutes), as an administrator's browser sends it. */
  static String at(Instant instant, int offsetMinutes) {
    return instant
        .atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  static String at(Instant instant) {
    return at(instant, 60);
  }

  /** The proposal values; {@code out == null} proposes no check-out. */
  static Map<String, Object> values(Instant in, Instant out, String reason, String explanation) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("checkedInAt", in == null ? null : at(in));
    body.put("checkedOutAt", out == null ? null : at(out));
    body.put("reasonCode", reason);
    body.put("explanation", explanation);
    return body;
  }

  static Map<String, Object> values(Instant in, Instant out) {
    return values(in, out, "DATA_ENTRY_ERROR", EXPLANATION);
  }

  /** A missing record's proposal. */
  static Map<String, Object> missing(String slot, Object employee, Instant in, Instant out) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slotId", slot);
    body.put("employeeId", String.valueOf(employee));
    body.putAll(values(in, out, "SUPERVISOR_OR_EMPLOYEE_REPORT", EXPLANATION));
    return body;
  }

  /** The commit body of a reviewed preview. */
  static Map<String, Object> commit(Map<String, Object> proposal, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(proposal);
    body.put("targetVersion", preview.get("targetVersion").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("confirm", true);
    return body;
  }

  static MockHttpServletRequestBuilder previewRecord(String bearer, String record, Object body)
      throws Exception {
    return Employees.postJson(bearer, RECORDS + record + "/correction-preview", text(body));
  }

  static MockHttpServletRequestBuilder commitRecord(
      String bearer, String key, String record, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, RECORDS + record + "/corrections", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder previewMissing(String bearer, Object body) throws Exception {
    return Employees.postJson(bearer, MISSING + "-preview", text(body));
  }

  static MockHttpServletRequestBuilder commitMissing(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request = Employees.postJson(bearer, MISSING, text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder history(String bearer, String record) {
    return get(RECORDS + record + "/corrections").header("Authorization", bearer);
  }

  private static String text(Object body) throws Exception {
    return body instanceof String raw ? raw : JSON.writeValueAsString(body);
  }
}
