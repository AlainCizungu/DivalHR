package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-080 (Issue #167, D80-12 items 1 to 5 and 9) through the public API against real PostgreSQL:
 * create, replay, key reuse and stale versions; every legal and illegal transition; assignee
 * eligibility; employee self-only access after link revalidation; tenant isolation of lists,
 * details, cursors and commands; and the privacy of every user-entered field.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TaskApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;

  private TaskWorld w;
  private Person ana;
  private Person ben;

  @BeforeEach
  void world() throws Exception {
    w = new TaskWorld(mvc, jdbc, lookups);
    ana = w.person("Ana", "Mukendi");
    ben = w.person("Ben", "Kasongo");
  }

  private static String key() {
    return Organizations.newKey();
  }

  private static String due(long hours) {
    return Instant.now().plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
  }

  // ---------------------------------------------------------------------------------- create

  @Test
  void createReplayReuseAndStale() throws Exception {
    String key = key();
    Map<String, Object> body = TaskWorld.newTask(ana.employee(), "Inventaire du dépôt");
    body.put("instructions", "Compter les palettes");
    body.put("priority", "HIGH");
    body.put("dueAt", due(72));

    JsonNode created = w.expect(w.createRequest(key, body), 201);
    assertThat(created.get("state").asText()).isEqualTo("OPEN");
    assertThat(created.get("version").asInt()).isEqualTo(1);
    String id = created.get("id").asText();
    List<Integer> after = w.evidence();
    assertThat(after).containsExactly(1, 1, 1, 1);

    var replay = w.call(w.createRequest(key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(TaskWorld.JSON.readTree(replay.getContentAsString()).get("id").asText())
        .isEqualTo(id);
    assertThat(w.evidence()).isEqualTo(after);

    Map<String, Object> other = TaskWorld.newTask(ana.employee(), "Autre titre");
    w.problem(w.createRequest(key, other), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(w.evidence()).isEqualTo(after);

    assertThat(w.call(w.createRequest(null, body)).getStatus()).isEqualTo(400);

    JsonNode detail = w.adminTask(id);
    assertThat(detail.get("title").asText()).isEqualTo("Inventaire du dépôt");
    assertThat(detail.get("history")).hasSize(1);
    assertThat(detail.get("history").get(0).get("action").asText()).isEqualTo("CREATED");

    // Stale: the employee started it (version 2); cancel with version 1 conflicts.
    w.start(ana, id, 1);
    w.problem(
        w.cancelRequest(id, key(), 1, "NO_LONGER_REQUIRED", null), 409, "TASK_VERSION_CONFLICT");
    assertThat(w.adminTask(id).get("state").asText()).isEqualTo("IN_PROGRESS");
  }

  @Test
  void validationRefusesWithoutEchoingText() throws Exception {
    String hostile = "<script>alert(1)</script> https://evil.example";
    Map<String, Object> body = TaskWorld.newTask(ana.employee(), hostile);
    var response = w.call(w.createRequest(key(), body));
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .doesNotContain("evil.example")
        .doesNotContain("script");

    for (Map<String, Object> bad :
        List.of(
            TaskWorld.newTask(ana.employee(), ""),
            TaskWorld.newTask(ana.employee(), "x".repeat(121)),
            withField("priority", "LOW"),
            withField("dueAt", Instant.now().minusSeconds(60).toString()),
            withField("dueAt", due(24 * 367)),
            withField("instructions", "y".repeat(1001)))) {
      assertThat(w.call(w.createRequest(key(), bad)).getStatus()).isEqualTo(400);
    }
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0);
  }

  private Map<String, Object> withField(String name, Object value) {
    Map<String, Object> body = TaskWorld.newTask(ana.employee(), "Titre valide");
    body.put(name, value);
    return body;
  }

  // ----------------------------------------------------------------------------- transitions

  @Test
  void everyLegalTransition() throws Exception {
    String a = w.create(ana.employee(), "A").get("id").asText();
    JsonNode started = w.start(ana, a, 1);
    assertThat(started.get("state").asText()).isEqualTo("IN_PROGRESS");
    assertThat(started.get("version").asInt()).isEqualTo(2);
    JsonNode done = w.complete(ana, a, 2, "Terminé sans incident");
    assertThat(done.get("state").asText()).isEqualTo("COMPLETED");

    String b = w.create(ana.employee(), "B").get("id").asText();
    assertThat(w.complete(ana, b, 1, null).get("state").asText()).isEqualTo("COMPLETED");

    String c = w.create(ana.employee(), "C").get("id").asText();
    JsonNode cancelled = w.expect(w.cancelRequest(c, key(), 1, "CREATED_IN_ERROR", null), 200);
    assertThat(cancelled.get("state").asText()).isEqualTo("CANCELLED");

    String d = w.create(ana.employee(), "D").get("id").asText();
    w.start(ana, d, 1);
    w.expect(w.cancelRequest(d, key(), 2, "OTHER", "Le chantier a été reporté"), 200);

    JsonNode history = w.adminTask(a).get("history");
    assertThat(history).hasSize(3);
    assertThat(history.get(1).get("action").asText()).isEqualTo("STARTED");
    assertThat(history.get(2).get("action").asText()).isEqualTo("COMPLETED");
    assertThat(history.get(2).get("actorRole").asText()).isEqualTo("employee");
    assertThat(w.myTask(ana, a).get("completionNote").asText()).isEqualTo("Terminé sans incident");
  }

  @Test
  void illegalTransitionsAndTerminalImmutability() throws Exception {
    String t = w.create(ana.employee(), "T").get("id").asText();
    w.start(ana, t, 1);
    // IN_PROGRESS cannot be started again.
    w.problem(
        TaskWorld.startRequest(ana.bearer(), t, key(), 2), 409, "TASK_TRANSITION_NOT_ALLOWED");
    w.complete(ana, t, 2, null);
    // COMPLETED is final for every command, current version or not.
    w.problem(
        TaskWorld.startRequest(ana.bearer(), t, key(), 3), 409, "TASK_TRANSITION_NOT_ALLOWED");
    w.problem(
        TaskWorld.completeRequest(ana.bearer(), t, key(), 3, null),
        409,
        "TASK_TRANSITION_NOT_ALLOWED");
    w.problem(
        w.cancelRequest(t, key(), 3, "NO_LONGER_REQUIRED", null),
        409,
        "TASK_TRANSITION_NOT_ALLOWED");

    String u = w.create(ana.employee(), "U").get("id").asText();
    w.expect(w.cancelRequest(u, key(), 1, "NO_LONGER_REQUIRED", null), 200);
    w.problem(
        TaskWorld.startRequest(ana.bearer(), u, key(), 2), 409, "TASK_TRANSITION_NOT_ALLOWED");
    w.problem(
        TaskWorld.completeRequest(ana.bearer(), u, key(), 2, null),
        409,
        "TASK_TRANSITION_NOT_ALLOWED");
    w.problem(
        w.cancelRequest(u, key(), 2, "NO_LONGER_REQUIRED", null),
        409,
        "TASK_TRANSITION_NOT_ALLOWED");
    assertThat(
            w.count(
                "SELECT count(*) FROM operations.task_action WHERE task_id = ?",
                UUID.fromString(t)))
        .isEqualTo(3);
    assertThat(
            w.count(
                "SELECT count(*) FROM operations.task_action WHERE task_id = ?",
                UUID.fromString(u)))
        .isEqualTo(2);
  }

  @Test
  void cancellationReasonRules() throws Exception {
    String t = w.create(ana.employee(), "R").get("id").asText();
    // OTHER needs 10-500 characters; other reasons accept none; unknown reasons are refused.
    assertThat(w.call(w.cancelRequest(t, key(), 1, "OTHER", null)).getStatus()).isEqualTo(400);
    assertThat(w.call(w.cancelRequest(t, key(), 1, "OTHER", "court")).getStatus()).isEqualTo(400);
    assertThat(w.call(w.cancelRequest(t, key(), 1, "OTHER", "x".repeat(501))).getStatus())
        .isEqualTo(400);
    assertThat(
            w.call(w.cancelRequest(t, key(), 1, "NO_LONGER_REQUIRED", "pas permis ici"))
                .getStatus())
        .isEqualTo(400);
    assertThat(w.call(w.cancelRequest(t, key(), 1, "BORED", null)).getStatus()).isEqualTo(400);
    assertThat(
            w.call(TaskWorld.completeRequest(ana.bearer(), t, key(), 1, "n".repeat(501)))
                .getStatus())
        .isEqualTo(400);
    assertThat(w.adminTask(t).get("state").asText()).isEqualTo("OPEN");
  }

  // ------------------------------------------------------------------------------ eligibility

  @Test
  void assigneeEligibilityIsIndistinguishable() throws Exception {
    UUID unlinked = w.hire("Cara", "Ilunga");
    TaskWorld foreign = new TaskWorld(mvc, jdbc, lookups);
    Person stranger = foreign.person("Dan", "Banza");
    // Linked, but whose employment has not started yet: not active at the business instant.
    UUID future =
        Employees.hire(
            mvc,
            jdbc,
            w.org,
            w.admin,
            Employees.number(),
            "Eli",
            "Tshibangu",
            java.time.LocalDate.now().plusDays(30));
    UUID futureMembership = w.member(UUID.randomUUID().toString());
    w.linkTo(future, futureMembership);

    JsonNode unknown =
        w.problem(
            w.createRequest(key(), TaskWorld.newTask(UUID.randomUUID(), "t")),
            404,
            "TASK_ASSIGNEE_NOT_FOUND");
    List<JsonNode> refusals = new ArrayList<>();
    refusals.add(
        w.problem(
            w.createRequest(key(), TaskWorld.newTask(unlinked, "t")),
            404,
            "TASK_ASSIGNEE_NOT_FOUND"));
    refusals.add(
        w.problem(
            w.createRequest(key(), TaskWorld.newTask(stranger.employee(), "t")),
            404,
            "TASK_ASSIGNEE_NOT_FOUND"));
    refusals.add(
        w.problem(
            w.createRequest(key(), TaskWorld.newTask(future, "t")),
            404,
            "TASK_ASSIGNEE_NOT_FOUND"));
    for (JsonNode refusal : refusals) {
      assertThat(refusal.get("title")).isEqualTo(unknown.get("title"));
      assertThat(refusal.get("status")).isEqualTo(unknown.get("status"));
      assertThat(refusal.has("errors")).isEqualTo(unknown.has("errors"));
    }
    assertThat(w.evidence()).containsExactly(0, 0, 0, 0);
  }

  // ----------------------------------------------------------------------------------- self

  @Test
  void employeeSeesOnlyOwnTasksAndLosesAccessWithTheLink() throws Exception {
    String mine = w.create(ana.employee(), "Pour Ana").get("id").asText();
    String his = w.create(ben.employee(), "Pour Ben").get("id").asText();

    JsonNode list = w.expect(get(TaskWorld.MY_TASKS).header("Authorization", ana.bearer()), 200);
    assertThat(list.get("items")).hasSize(1);
    assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(mine);
    assertThat(list.get("items").get(0).has("assigneeEmployeeId")).isFalse();

    w.problem(
        get(TaskWorld.MY_TASKS + "/" + his).header("Authorization", ana.bearer()),
        404,
        "TASK_NOT_FOUND");
    w.problem(TaskWorld.startRequest(ana.bearer(), his, key(), 1), 404, "TASK_NOT_FOUND");
    w.problem(TaskWorld.completeRequest(ana.bearer(), his, key(), 1, null), 404, "TASK_NOT_FOUND");
    w.problem(
        get(TaskWorld.MY_TASKS + "/" + UUID.randomUUID()).header("Authorization", ana.bearer()),
        404,
        "TASK_NOT_FOUND");
    assertThat(w.adminTask(his).get("state").asText()).isEqualTo("OPEN");

    // Administrator-only and employee-only surfaces refuse the other role.
    assertThat(w.call(get(TaskWorld.TASKS).header("Authorization", ana.bearer())).getStatus())
        .isEqualTo(403);
    assertThat(w.call(get(TaskWorld.MY_TASKS).header("Authorization", w.admin)).getStatus())
        .isEqualTo(403);
    assertThat(
            w.call(
                    TaskWorld.json(
                        TaskWorld.TASKS + "/" + mine + "/cancel",
                        ana.bearer(),
                        key(),
                        Map.of("expectedVersion", 1, "reason", "NO_LONGER_REQUIRED")))
                .getStatus())
        .isEqualTo(403);

    w.unlink(ana.employee());
    w.problem(
        get(TaskWorld.MY_TASKS).header("Authorization", ana.bearer()),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    w.problem(TaskWorld.startRequest(ana.bearer(), mine, key(), 1), 403, "EMPLOYEE_LINK_REQUIRED");
    // The administrator keeps the history.
    assertThat(w.adminTask(mine).get("history")).hasSize(1);
  }

  @Test
  void identicalReplayAfterLossOfAccessIsRefused() throws Exception {
    String t = w.create(ana.employee(), "Replay").get("id").asText();
    String key = key();
    w.expect(TaskWorld.startRequest(ana.bearer(), t, key, 1), 200);
    w.unlink(ana.employee());
    w.problem(TaskWorld.startRequest(ana.bearer(), t, key, 1), 403, "EMPLOYEE_LINK_REQUIRED");
  }

  // -------------------------------------------------------------------------------- isolation

  @Test
  void tenantsAreIsolated() throws Exception {
    TaskWorld other = new TaskWorld(mvc, jdbc, lookups);
    Person zoe = other.person("Zoe", "Mbuyi");
    String foreignTask = other.create(zoe.employee(), "Étranger").get("id").asText();
    String own = w.create(ana.employee(), "Propre").get("id").asText();

    w.problem(
        get(TaskWorld.TASKS + "/" + foreignTask).header("Authorization", w.admin),
        404,
        "TASK_NOT_FOUND");
    w.problem(
        w.cancelRequest(foreignTask, key(), 1, "NO_LONGER_REQUIRED", null), 404, "TASK_NOT_FOUND");
    other.problem(
        get(TaskWorld.TASKS + "/" + own).header("Authorization", other.admin),
        404,
        "TASK_NOT_FOUND");
    other.problem(TaskWorld.startRequest(zoe.bearer(), own, key(), 1), 404, "TASK_NOT_FOUND");
    assertThat(w.expect(get(TaskWorld.TASKS).header("Authorization", w.admin), 200).get("items"))
        .hasSize(1);

    // A cursor minted in one tenant is refused in another.
    w.create(ana.employee(), "Deuxième");
    JsonNode page =
        w.expect(get(TaskWorld.TASKS + "?limit=1").header("Authorization", w.admin), 200);
    String cursor = page.get("nextCursor").asText();
    other.problem(
        get(TaskWorld.TASKS + "?limit=1&cursor=" + cursor).header("Authorization", other.admin),
        400,
        "CURSOR_INVALID");
    // ... and bound to filters and page size within the tenant.
    w.problem(
        get(TaskWorld.TASKS + "?limit=2&cursor=" + cursor).header("Authorization", w.admin),
        400,
        "CURSOR_INVALID");
    w.problem(
        get(TaskWorld.TASKS + "?limit=1&state=OPEN&cursor=" + cursor)
            .header("Authorization", w.admin),
        400,
        "CURSOR_INVALID");
  }

  // ------------------------------------------------------------------------ list and ordering

  @Test
  void orderingFiltersAndPagination() throws Exception {
    Map<String, Object> soon = TaskWorld.newTask(ana.employee(), "Bientôt");
    soon.put("dueAt", due(10));
    Map<String, Object> later = TaskWorld.newTask(ana.employee(), "Plus tard");
    later.put("dueAt", due(200));
    String none = w.create(ana.employee(), "Sans échéance").get("id").asText();
    String lateId = w.expect(w.createRequest(key(), later), 201).get("id").asText();
    String soonId = w.expect(w.createRequest(key(), soon), 201).get("id").asText();
    String done = w.create(ana.employee(), "Fini").get("id").asText();
    w.complete(ana, done, 1, null);
    String other = w.create(ben.employee(), "Ben").get("id").asText();

    JsonNode all =
        w.expect(get(TaskWorld.TASKS + "?limit=100").header("Authorization", w.admin), 200);
    List<String> ids = new ArrayList<>();
    all.get("items").forEach(i -> ids.add(i.get("id").asText()));
    // Unfinished first by due (null last), newest creation next; terminal after.
    assertThat(ids).containsExactly(soonId, lateId, other, none, done);

    // Keyset pages cover the same sequence without repeats.
    List<String> paged = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode page =
          w.expect(
              get(TaskWorld.TASKS + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor))
                  .header("Authorization", w.admin),
              200);
      page.get("items").forEach(i -> paged.add(i.get("id").asText()));
      cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
    } while (cursor != null);
    assertThat(paged).isEqualTo(ids);

    assertThat(count(TaskWorld.TASKS + "?assigneeId=" + ben.employee())).isEqualTo(1);
    assertThat(count(TaskWorld.TASKS + "?state=COMPLETED")).isEqualTo(1);
    assertThat(count(TaskWorld.TASKS + "?due=DUE_SOON")).isEqualTo(1);
    assertThat(count(TaskWorld.TASKS + "?due=LATER")).isEqualTo(1);
    assertThat(count(TaskWorld.TASKS + "?due=NONE")).isEqualTo(2);
    assertThat(count(TaskWorld.TASKS + "?due=OVERDUE")).isZero();
    assertThat(
            w.call(get(TaskWorld.TASKS + "?limit=101").header("Authorization", w.admin))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            w.call(get(TaskWorld.TASKS + "?state=BOGUS").header("Authorization", w.admin))
                .getStatus())
        .isEqualTo(400);
    assertThat(
            w.expect(get(TaskWorld.TASKS).header("Authorization", w.admin), 200)
                .get("items")
                .size())
        .isEqualTo(5);

    var response = w.call(get(TaskWorld.TASKS).header("Authorization", w.admin));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
  }

  private int count(String path) throws Exception {
    return w.expect(get(path).header("Authorization", w.admin), 200).get("items").size();
  }

  // ------------------------------------------------------------------------------- privacy

  @Test
  void userEnteredTextNeverReachesAuditOutboxIdempotencyOrLogs(CapturedOutput output)
      throws Exception {
    String title = "TITRE-SENTINELLE-7391";
    String instructions = "INSTRUCTIONS-SENTINELLE-7391";
    String note = "NOTE-SENTINELLE-7391";
    String explanation = "EXPLICATION-SENTINELLE-7391";
    Map<String, Object> body = TaskWorld.newTask(ana.employee(), title);
    body.put("instructions", instructions);
    String a = w.expect(w.createRequest(key(), body), 201).get("id").asText();
    w.start(ana, a, 1);
    w.complete(ana, a, 2, note);
    String b = w.create(ana.employee(), title + "-bis").get("id").asText();
    w.expect(w.cancelRequest(b, key(), 1, "OTHER", explanation), 200);
    w.expect(get(TaskWorld.TASKS).header("Authorization", w.admin), 200);
    w.adminTask(a);
    w.myTask(ana, a);
    // Refusals must not echo the text either.
    w.call(w.createRequest(key(), TaskWorld.newTask(UUID.randomUUID(), title)));

    String persisted = w.persistedEvidence();
    assertThat(persisted).isNotBlank();
    for (String secret : List.of("SENTINELLE", ana.number(), "Mukendi", "Ana")) {
      assertThat(persisted).as(secret).doesNotContain(secret);
    }
    assertThat(output.getAll()).doesNotContain("SENTINELLE");
    // The disclosure audits exist and carry no text.
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action IN ('task.list', 'task.read')",
                w.tenant()))
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  void eventsCarryOnlyIdentifiers() throws Exception {
    String t = w.create(ana.employee(), "Événement").get("id").asText();
    w.start(ana, t, 1);
    w.complete(ana, t, 2, null);
    List<String> types =
        jdbc.queryForList(
            "SELECT event_type FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'operations.task.%' ORDER BY created_at, event_id",
            String.class, w.tenant());
    assertThat(types)
        .containsExactly(
            "operations.task.assigned.v1",
            "operations.task.started.v1",
            "operations.task.completed.v1");
  }
}
