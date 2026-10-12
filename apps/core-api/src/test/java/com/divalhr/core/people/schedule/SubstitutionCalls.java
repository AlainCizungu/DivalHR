package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Employees;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-051 calls over the public API: requests, history, inbox, candidates and decisions. */
final class SubstitutionCalls {

  static final String MINE = "/api/v1/me/work-schedule-substitutions";
  static final String APPROVALS = "/api/v1/me/work-schedule-substitution-approvals";
  static final String REASON = "Formation obligatoire à Lubumbashi";
  static final String APPROVAL_REASON = "Remplacement accordé pour la formation";
  static final String REJECTION_REASON = "Effectif insuffisant cette semaine";

  private SubstitutionCalls() {}

  static Map<String, Object> requestBody(Object slotId, String locale, Object reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slotId", slotId);
    body.put("reasonLocale", locale);
    body.put("reason", reason);
    return body;
  }

  static MockHttpServletRequestBuilder request(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer, MINE, body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder request(Person person, String key, String slotId)
      throws Exception {
    return request(person.bearer(), key, requestBody(slotId, "fr", REASON));
  }

  static Map<String, Object> decisionBody(
      String decision, UUID substitute, String locale, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", decision);
    if (substitute != null) {
      body.put("substituteEmployeeId", substitute.toString());
    }
    body.put("reasonLocale", locale);
    body.put("reason", reason);
    return body;
  }

  static MockHttpServletRequestBuilder decide(
      String bearer, String requestId, String key, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer,
            APPROVALS + "/" + requestId + "/decision",
            body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder approve(
      Person manager, String requestId, String key, UUID substitute) throws Exception {
    return decide(
        manager.bearer(),
        requestId,
        key,
        decisionBody("APPROVED", substitute, "fr", APPROVAL_REASON));
  }

  static MockHttpServletRequestBuilder reject(Person manager, String requestId, String key)
      throws Exception {
    return decide(
        manager.bearer(), requestId, key, decisionBody("REJECTED", null, "fr", REJECTION_REASON));
  }

  static MockHttpServletRequestBuilder history(String bearer) {
    return get(MINE).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder inbox(String bearer) {
    return get(APPROVALS).header("Authorization", bearer);
  }

  static MockHttpServletRequestBuilder candidates(String bearer, String requestId, String query) {
    MockHttpServletRequestBuilder request =
        get(APPROVALS + "/" + requestId + "/candidates").header("Authorization", bearer);
    return query == null ? request : request.queryParam("query", query);
  }

  /** The slot IDs of a schedule, in publication order (administrators' detail). */
  static List<String> slotIds(ScheduleWorld w, String scheduleId) throws Exception {
    List<String> ids = new ArrayList<>();
    w.expect(get(ScheduleWorld.SCHEDULES + "/" + scheduleId).header("Authorization", w.admin), 200)
        .get("slots")
        .forEach(slot -> ids.add(slot.get("id").asText()));
    return ids;
  }

  static List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
