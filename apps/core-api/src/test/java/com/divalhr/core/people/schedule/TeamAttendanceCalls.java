package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Employees;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-060B calls over the public API: the manager's queue, check-in and check-out. */
final class TeamAttendanceCalls {

  static final String TEAM = "/api/v1/me/team-attendance";
  static final String REASON_FR = "Téléphone de la salariée en panne à Lubumbashi";
  static final String REASON_EN = "Employee badge reader was out of order";

  private TeamAttendanceCalls() {}

  static Map<String, Object> checkInBody(Object slotId, String locale, Object reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("slotId", slotId);
    body.put("reasonLocale", locale);
    body.put("reason", reason);
    return body;
  }

  static MockHttpServletRequestBuilder teamCheckIn(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer,
            TEAM + "/check-in",
            body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder teamCheckIn(Person manager, String key, String slotId)
      throws Exception {
    return teamCheckIn(manager.bearer(), key, checkInBody(slotId, "fr", REASON_FR));
  }

  static MockHttpServletRequestBuilder teamCheckOut(
      String bearer, String key, String recordId, Object body) throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer,
            TEAM + "/" + recordId + "/check-out",
            body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder teamCheckOut(Person manager, String key, String recordId)
      throws Exception {
    return teamCheckOut(
        manager.bearer(), key, recordId, Map.of("reasonLocale", "en", "reason", REASON_EN));
  }

  static MockHttpServletRequestBuilder actions(String bearer) {
    return get(TEAM + "/actions").header("Authorization", bearer);
  }
}
