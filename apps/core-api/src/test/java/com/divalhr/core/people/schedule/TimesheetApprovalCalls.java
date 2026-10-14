package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.support.Employees;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-070B/C calls over the public API: submission, the manager's queue, reads and decisions. */
final class TimesheetApprovalCalls {

  static final String MINE = "/api/v1/me/timesheets";
  static final String APPROVALS = "/api/v1/me/time-approvals";

  private TimesheetApprovalCalls() {}

  /** The submission body of a reviewed own timesheet ({@code GET /me/timesheets/{id}}). */
  static Map<String, Object> submission(JsonNode mine) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("revision", mine.get("revision").asInt());
    body.put("generationVersion", mine.get("generationVersion").asInt());
    body.put("confirm", true);
    return body;
  }

  /** The approval body of a reviewed submission ({@code GET /me/time-approvals/{id}}). */
  static Map<String, Object> decision(JsonNode approval) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("revision", approval.get("revision").asInt());
    body.put("generationVersion", approval.get("generationVersion").asInt());
    body.put("cycle", approval.get("cycle").asInt());
    body.put("submissionActionId", approval.get("submissionActionId").asText());
    body.put("confirm", true);
    return body;
  }

  /** The return body of a reviewed submission with a reason. */
  static Map<String, Object> giveBack(JsonNode approval, Object reason) {
    Map<String, Object> body = decision(approval);
    body.put("reason", reason);
    return body;
  }

  static MockHttpServletRequestBuilder submit(String bearer, String key, String id, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, MINE + "/" + id + "/submissions", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder queue(String bearer) {
    return get(APPROVALS).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder queue(String bearer, String query) {
    return get(APPROVALS + "?" + query).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder approval(String bearer, String id) {
    return get(APPROVALS + "/" + id).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder approve(String bearer, String key, String id, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, APPROVALS + "/" + id + "/approval", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder giveBack(String bearer, String key, String id, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(bearer, APPROVALS + "/" + id + "/return", text(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private static String text(Object body) throws Exception {
    return body instanceof String raw ? raw : JSON.writeValueAsString(body);
  }
}
