package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-080 (Issue #167, D80-12 item 6 and D80-5) on a controlled clock: the due instant must be
 * after creation and within 366 days; a task is overdue only strictly after its due instant, never
 * exactly at it; and overdue is derived, so it changes nothing stored.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class TaskDueBoundaryIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private TaskWorld w;
  private Person ana;
  private Instant now;

  @BeforeEach
  void world() throws Exception {
    w = new TaskWorld(mvc, jdbc, lookups);
    ana = w.person("Ana", "Mukendi");
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    clock.set(now);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private Map<String, Object> due(Instant at) {
    Map<String, Object> body = TaskWorld.newTask(ana.employee(), "Échéance");
    body.put("dueAt", at.toString());
    return body;
  }

  private int createStatus(Instant at) throws Exception {
    return w.call(w.createRequest(Organizations.newKey(), due(at))).getStatus();
  }

  @Test
  void dueMustBeAfterCreationAndWithinTheLimit() throws Exception {
    assertThat(createStatus(now.minusSeconds(1))).isEqualTo(400);
    assertThat(createStatus(now)).isEqualTo(400);
    assertThat(createStatus(now.plusSeconds(1))).isEqualTo(201);
    Instant limit = now.plus(Duration.ofHours(366 * 24));
    assertThat(createStatus(limit)).isEqualTo(201);
    assertThat(createStatus(limit.plusSeconds(1))).isEqualTo(400);
  }

  @Test
  void overdueOnlyStrictlyAfterTheDueInstant() throws Exception {
    Instant dueAt = now.plus(1, ChronoUnit.HOURS);
    String id =
        w.expect(w.createRequest(Organizations.newKey(), due(dueAt)), 201).get("id").asText();

    assertThat(dueClass(id)).isEqualTo("DUE_SOON");
    clock.set(dueAt.minusSeconds(1));
    assertThat(dueClass(id)).isEqualTo("DUE_SOON");
    clock.set(dueAt);
    assertThat(dueClass(id)).as("exactly at the due instant").isEqualTo("DUE_SOON");
    assertThat(listed("OVERDUE")).isZero();
    clock.set(dueAt.plus(1, ChronoUnit.MICROS));
    assertThat(dueClass(id)).as("one microsecond later").isEqualTo("OVERDUE");
    assertThat(listed("OVERDUE")).isEqualTo(1);
    assertThat(listed("DUE_SOON")).isZero();

    // Derived only: nothing stored changes, and the employee can still finish it.
    assertThat(w.adminTask(id).get("state").asText()).isEqualTo("OPEN");
    assertThat(w.adminTask(id).get("version").asInt()).isEqualTo(1);
    assertThat(w.myTask(ana, id).get("dueClass").asText()).isEqualTo("OVERDUE");
    JsonNode done = w.complete(ana, id, 1, null);
    assertThat(done.get("state").asText()).isEqualTo("COMPLETED");
    JsonNode finished = w.adminTask(id).get("dueClass");
    assertThat(finished == null || finished.isNull()).as("finished tasks are not overdue").isTrue();
  }

  @Test
  void soonAndLaterAreSeparatedAtFortyEightHours() throws Exception {
    String soon =
        w.expect(w.createRequest(Organizations.newKey(), due(now.plus(48, ChronoUnit.HOURS))), 201)
            .get("id")
            .asText();
    String later =
        w.expect(
                w.createRequest(
                    Organizations.newKey(), due(now.plus(48, ChronoUnit.HOURS).plusSeconds(1))),
                201)
            .get("id")
            .asText();
    assertThat(dueClass(soon)).isEqualTo("DUE_SOON");
    assertThat(dueClass(later)).isEqualTo("LATER");
  }

  private String dueClass(String id) throws Exception {
    return w.adminTask(id).get("dueClass").asText();
  }

  private int listed(String due) throws Exception {
    return w.expect(get(TaskWorld.TASKS + "?due=" + due).header("Authorization", w.admin), 200)
        .get("items")
        .size();
  }
}
