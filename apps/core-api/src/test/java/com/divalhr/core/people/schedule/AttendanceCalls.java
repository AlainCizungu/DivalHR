package com.divalhr.core.people.schedule;

import static com.divalhr.core.people.schedule.ScheduleWorld.JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.people.schedule.ScheduleWorld.Person;
import com.divalhr.core.support.Employees;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-060A calls over the public API: check-in, check-out and history. */
final class AttendanceCalls {

  static final String ATTENDANCE = "/api/v1/me/attendance";

  private AttendanceCalls() {}

  static MockHttpServletRequestBuilder checkIn(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        Employees.postJson(
            bearer,
            ATTENDANCE + "/check-in",
            body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder checkIn(Person person, String key, String slotId)
      throws Exception {
    return checkIn(person.bearer(), key, Map.of("slotId", slotId));
  }

  static MockHttpServletRequestBuilder checkOut(String bearer, String key, String recordId) {
    MockHttpServletRequestBuilder request =
        post(ATTENDANCE + "/" + recordId + "/check-out").header("Authorization", bearer);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder checkOut(Person person, String key, String recordId) {
    return checkOut(person.bearer(), key, recordId);
  }

  static MockHttpServletRequestBuilder history(String bearer) {
    return get(ATTENDANCE).header("Authorization", bearer);
  }

  /** The stored scheduled start and end of a slot. */
  static Instant[] instants(JdbcTemplate jdbc, String slotId) {
    return jdbc.queryForObject(
        "SELECT start_at, end_at FROM people.work_schedule_slot WHERE id = ?::uuid",
        (rs, n) ->
            new Instant[] {
              rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()
            },
        slotId);
  }
}
