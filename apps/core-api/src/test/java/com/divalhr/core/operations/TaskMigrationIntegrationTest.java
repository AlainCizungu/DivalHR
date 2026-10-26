package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.operations.application.TaskText;
import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-080 V41 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, D80-6, D80-12 item 10): the refusing rollback that restores V40 exactly; tenant and
 * task binding; one increasing action sequence; legal transitions only, each moving the version by
 * one; the exact match of the projection and its last action (deferred); terminal and identity
 * immutability; append-only evidence; no hard deletion; actor shape; the restricted text grammar (a
 * subset of what the application refuses); and the 10,000 non-terminal tasks per tenant limit.
 */
@IntegrationTest
class TaskMigrationIntegrationTest {

  /** The operations schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'operations'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'operations'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'operations'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'operations'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'operations'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'operations'::regnamespace), '')
      """;

  private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");

  private static final String TASK =
      "INSERT INTO operations.task (tenant_id, id, assignee_employee_id, title, instructions,"
          + " priority, due_at, state, version, created_at, created_by)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'admin-subject')";

  private static final String ACTION =
      "INSERT INTO operations.task_action (tenant_id, task_id, seq, action, occurred_at,"
          + " actor_subject, actor_role, actor_employee_id, from_state, to_state, completion_note,"
          + " reason_code, explanation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private record Person(UUID tenant, UUID employee) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "task80_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      body.accept(
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, postgres.getUsername(), postgres.getPassword()))));
    } finally {
      jdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }
  }

  private void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static String resource(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Connection connect(Db db) throws SQLException {
    Connection c =
        DriverManager.getConnection(db.url(), postgres.getUsername(), postgres.getPassword());
    c.setAutoCommit(false);
    return c;
  }

  private static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.execute();
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private void refused(Db db, String constraint, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      assertThatThrownBy(
              () -> {
                work.run(c);
                c.commit();
              })
          .as(constraint)
          .isInstanceOfSatisfying(
              PSQLException.class,
              e -> {
                org.postgresql.util.ServerErrorMessage message = e.getServerErrorMessage();
                assertThat(message == null ? "unnamed" : String.valueOf(message.getConstraint()))
                    .isEqualTo(constraint);
              });
      c.rollback();
    }
  }

  private void committed(Db db, SqlWork work) throws SQLException {
    try (Connection c = connect(db)) {
      work.run(c);
      c.commit();
    }
  }

  private static UUID organization(JdbcTemplate db) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', 'Africa/Kinshasa',"
            + " 'ACTIVE', now(), 'test')",
        id);
    return id;
  }

  /**
   * Runs a script as written. JDBC escape processing would rewrite the braces of the regular
   * expressions in the function bodies, so this is switched off (Flyway sends scripts as written).
   */
  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                connection -> {
                  try (java.sql.Statement statement = connection.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.execute(sql);
                  }
                  return null;
                });
  }

  private static Person employee(JdbcTemplate db, UUID tenant) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana', 'Mbuyi',"
            + " ' ana mbuyi ', now(), 'test')",
        id,
        tenant,
        "E-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    return new Person(tenant, id);
  }

  private static Timestamp at(Instant instant) {
    return Timestamp.from(instant);
  }

  /** A valid open task with its creation action; returns its id. */
  private static UUID openTask(Connection c, Person who) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        TASK,
        who.tenant(),
        id,
        who.employee(),
        "Prepare the delivery",
        null,
        "NORMAL",
        null,
        "OPEN",
        1,
        at(CREATED));
    exec(
        c,
        ACTION,
        who.tenant(),
        id,
        1,
        "CREATED",
        at(CREATED),
        "admin-subject",
        "tenant-admin",
        null,
        null,
        "OPEN",
        null,
        null,
        null);
    return id;
  }

  /** Starts a task as its assignee: projection and action together. */
  private static void start(Connection c, Person who, UUID id, Instant when) throws SQLException {
    exec(
        c,
        "UPDATE operations.task SET state = 'IN_PROGRESS', version = 2, started_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        at(when),
        who.tenant(),
        id);
    exec(
        c,
        ACTION,
        who.tenant(),
        id,
        2,
        "STARTED",
        at(when),
        "employee-subject",
        "employee",
        who.employee(),
        "OPEN",
        "IN_PROGRESS",
        null,
        null,
        null);
  }

  private static int count(Db db, String sql, Object... args) {
    return java.util.Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class, args));
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV40ExactlyAndRefusesOnceATaskExists() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "40");
          String v40 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "41");
          String v41 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v41).isNotEqualTo(v40).contains("task_action");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V41__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v40);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);

          // With no rows the rollback ran; the migration can be applied again.
          script(db, resource("db/migration/V41__operations_task_lifecycle.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v41);

          // Once a task exists, the rollback refuses and changes nothing.
          UUID tenant = organization(db.jdbc());
          Person who = employee(db.jdbc(), tenant);
          try {
            committed(db, c -> openTask(c, who));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V41__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V41 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v41);
          assertThat(count(db, "SELECT count(*) FROM operations.task")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM operations.task_action")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Binding, sequence, transitions, exact match, immutability and append-only evidence
  // ------------------------------------------------------------------------------------------

  @Test
  void everyLifecycleRuleIsEnforcedByTheDatabase() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "41");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          Person who = employee(db.jdbc(), tenant);
          Person foreign = employee(db.jdbc(), other);
          try {
            // A valid task, started, then completed: every legal step commits.
            committed(
                db,
                c -> {
                  UUID id = openTask(c, who);
                  start(c, who, id, CREATED.plusSeconds(60));
                  exec(
                      c,
                      "UPDATE operations.task SET state = 'COMPLETED', version = 3,"
                          + " completed_at = ?, completion_note = 'All done'"
                          + " WHERE tenant_id = ? AND id = ?",
                      at(CREATED.plusSeconds(120)),
                      tenant,
                      id);
                  exec(
                      c,
                      ACTION,
                      tenant,
                      id,
                      3,
                      "COMPLETED",
                      at(CREATED.plusSeconds(120)),
                      "employee-subject",
                      "employee",
                      who.employee(),
                      "IN_PROGRESS",
                      "COMPLETED",
                      "All done",
                      null,
                      null);
                });
            assertThat(count(db, "SELECT count(*) FROM operations.task_action")).isEqualTo(3);

            // Terminal immutability: a completed task never changes.
            UUID completed =
                db.jdbc().queryForObject("SELECT id FROM operations.task LIMIT 1", UUID.class);
            refused(
                db,
                "operations_task_terminal",
                c ->
                    exec(
                        c,
                        "UPDATE operations.task SET state = 'OPEN', version = 4"
                            + " WHERE tenant_id = ? AND id = ?",
                        tenant,
                        completed));
            refused(
                db,
                "operations_task_terminal",
                c ->
                    exec(
                        c,
                        "UPDATE operations.task SET completion_note = 'Edited'"
                            + " WHERE tenant_id = ? AND id = ?",
                        tenant,
                        completed));

            // The initial state and version.
            refused(
                db,
                "operations_task_initial",
                c ->
                    exec(
                        c,
                        TASK,
                        tenant,
                        UUID.randomUUID(),
                        who.employee(),
                        "Task",
                        null,
                        "NORMAL",
                        null,
                        "COMPLETED",
                        1,
                        at(CREATED)));
            refused(
                db,
                "operations_task_initial",
                c ->
                    exec(
                        c,
                        TASK,
                        tenant,
                        UUID.randomUUID(),
                        who.employee(),
                        "Task",
                        null,
                        "NORMAL",
                        null,
                        "OPEN",
                        2,
                        at(CREATED)));

            // A task without its creation action (deferred exact match).
            refused(
                db,
                "operations_task_exact",
                c ->
                    exec(
                        c,
                        TASK,
                        tenant,
                        UUID.randomUUID(),
                        who.employee(),
                        "Task",
                        null,
                        "NORMAL",
                        null,
                        "OPEN",
                        1,
                        at(CREATED)));
            // An action without the matching projection version (deferred).
            refused(
                db,
                "operations_task_exact",
                c -> {
                  UUID id = openTask(c, who);
                  exec(
                      c,
                      ACTION,
                      tenant,
                      id,
                      2,
                      "STARTED",
                      at(CREATED.plusSeconds(5)),
                      "employee-subject",
                      "employee",
                      who.employee(),
                      "OPEN",
                      "IN_PROGRESS",
                      null,
                      null,
                      null);
                });
            // A projection that disagrees with its last action (the timestamp).
            refused(
                db,
                "operations_task_exact",
                c -> {
                  UUID id = openTask(c, who);
                  exec(
                      c,
                      "UPDATE operations.task SET state = 'IN_PROGRESS', version = 2,"
                          + " started_at = ? WHERE tenant_id = ? AND id = ?",
                      at(CREATED.plusSeconds(60)),
                      tenant,
                      id);
                  exec(
                      c,
                      ACTION,
                      tenant,
                      id,
                      2,
                      "STARTED",
                      at(CREATED.plusSeconds(61)),
                      "employee-subject",
                      "employee",
                      who.employee(),
                      "OPEN",
                      "IN_PROGRESS",
                      null,
                      null,
                      null);
                });

            // Identity and content are immutable; versions move by exactly one.
            committed(db, c -> openTask(c, who));
            UUID openId =
                db.jdbc()
                    .queryForObject(
                        "SELECT id FROM operations.task WHERE state = 'OPEN' LIMIT 1", UUID.class);
            for (String change :
                List.of(
                    "title = 'Another title'",
                    "instructions = 'New instructions'",
                    "priority = 'URGENT'",
                    "due_at = created_at + interval '1 day'",
                    "created_by = 'someone-else'",
                    "assignee_employee_id = '" + UUID.randomUUID() + "'")) {
              refused(
                  db,
                  "operations_task_identity",
                  c -> exec(c, "UPDATE operations.task SET " + change + " WHERE id = ?", openId));
            }
            refused(
                db,
                "operations_task_transition",
                c ->
                    exec(
                        c,
                        "UPDATE operations.task SET state = 'IN_PROGRESS', version = 3,"
                            + " started_at = created_at + interval '1 minute' WHERE id = ?",
                        openId));
            refused(
                db,
                "operations_task_transition",
                c -> exec(c, "UPDATE operations.task SET version = 2 WHERE id = ?", openId));
            // IN_PROGRESS never goes back to OPEN.
            committed(
                db,
                c -> {
                  UUID id = openTask(c, who);
                  start(c, who, id, CREATED.plusSeconds(30));
                });
            UUID progressId =
                db.jdbc()
                    .queryForObject(
                        "SELECT id FROM operations.task WHERE state = 'IN_PROGRESS' LIMIT 1",
                        UUID.class);
            refused(
                db,
                "operations_task_transition",
                c ->
                    exec(
                        c,
                        "UPDATE operations.task SET state = 'OPEN', version = 3,"
                            + " started_at = NULL WHERE id = ?",
                        progressId));

            // One increasing sequence per task; each action starts from the previous state.
            refused(
                db,
                "operations_task_action_sequence",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        3,
                        "STARTED",
                        at(CREATED.plusSeconds(10)),
                        "employee-subject",
                        "employee",
                        who.employee(),
                        "OPEN",
                        "IN_PROGRESS",
                        null,
                        null,
                        null));
            refused(
                db,
                "operations_task_action_sequence",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        1,
                        "CREATED",
                        at(CREATED),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        null,
                        "OPEN",
                        null,
                        null,
                        null));
            refused(
                db,
                "operations_task_action_transition",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        progressId,
                        3,
                        "STARTED",
                        at(CREATED.plusSeconds(40)),
                        "employee-subject",
                        "employee",
                        who.employee(),
                        "OPEN",
                        "IN_PROGRESS",
                        null,
                        null,
                        null));
            refused(
                db,
                "operations_task_action_transition",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        completed,
                        4,
                        "CANCELLED",
                        at(CREATED.plusSeconds(300)),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        "COMPLETED",
                        "CANCELLED",
                        null,
                        "NO_LONGER_REQUIRED",
                        null));

            // Actor shape: only the assignee acts as the employee; roles match the action.
            refused(
                db,
                "operations_task_action_assignee",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "STARTED",
                        at(CREATED.plusSeconds(10)),
                        "employee-subject",
                        "employee",
                        UUID.randomUUID(),
                        "OPEN",
                        "IN_PROGRESS",
                        null,
                        null,
                        null));
            // An employee action names the assigned employee (the guard answers before the check).
            refused(
                db,
                "operations_task_action_assignee",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "STARTED",
                        at(CREATED.plusSeconds(10)),
                        "employee-subject",
                        "employee",
                        null,
                        "OPEN",
                        "IN_PROGRESS",
                        null,
                        null,
                        null));
            refused(
                db,
                "operations_task_action_states",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "STARTED",
                        at(CREATED.plusSeconds(10)),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        "OPEN",
                        "IN_PROGRESS",
                        null,
                        null,
                        null));
            refused(
                db,
                "operations_task_action_states",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "CANCELLED",
                        at(CREATED.plusSeconds(10)),
                        "employee-subject",
                        "employee",
                        who.employee(),
                        "OPEN",
                        "CANCELLED",
                        null,
                        "NO_LONGER_REQUIRED",
                        null));

            // Cancellation evidence: a closed reason; OTHER needs a 10 to 500 character
            // explanation, every other reason none.
            refused(
                db,
                "operations_task_action_cancellation",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "CANCELLED",
                        at(CREATED.plusSeconds(10)),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        "OPEN",
                        "CANCELLED",
                        null,
                        "OTHER",
                        null));
            refused(
                db,
                "operations_task_action_cancellation",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "CANCELLED",
                        at(CREATED.plusSeconds(10)),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        "OPEN",
                        "CANCELLED",
                        null,
                        "OTHER",
                        "too short"));
            refused(
                db,
                "operations_task_action_cancellation",
                c ->
                    exec(
                        c,
                        ACTION,
                        tenant,
                        openId,
                        2,
                        "CANCELLED",
                        at(CREATED.plusSeconds(10)),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        "OPEN",
                        "CANCELLED",
                        null,
                        "CREATED_IN_ERROR",
                        "An explanation that is not allowed here"));

            // Tenant and task binding.
            refused(
                db,
                "operations_task_assignee",
                c ->
                    exec(
                        c,
                        TASK,
                        tenant,
                        UUID.randomUUID(),
                        foreign.employee(),
                        "Task",
                        null,
                        "NORMAL",
                        null,
                        "OPEN",
                        1,
                        at(CREATED)));
            refused(
                db,
                "operations_task_action_task",
                c ->
                    exec(
                        c,
                        ACTION,
                        other,
                        openId,
                        1,
                        "CREATED",
                        at(CREATED),
                        "admin-subject",
                        "tenant-admin",
                        null,
                        null,
                        "OPEN",
                        null,
                        null,
                        null));

            // Append-only evidence and no hard deletion.
            refused(
                db,
                "operations_task_append_only",
                c -> exec(c, "DELETE FROM operations.task WHERE id = ?", openId));
            refused(
                db, "operations_task_append_only", c -> exec(c, "TRUNCATE operations.task_action"));
            refused(
                db,
                "operations_task_append_only",
                c -> exec(c, "TRUNCATE operations.task CASCADE"));
            refused(
                db,
                "operations_task_append_only",
                c -> exec(c, "DELETE FROM operations.task_action WHERE task_id = ?", openId));
            refused(
                db,
                "operations_task_append_only",
                c ->
                    exec(
                        c,
                        "UPDATE operations.task_action SET actor_subject = 'other'"
                            + " WHERE task_id = ?",
                        openId));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void theRestrictedTextAndShapeChecksAreEnforced() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "41");
          UUID tenant = organization(db.jdbc());
          Person who = employee(db.jdbc(), tenant);
          try {
            refused(db, "operations_task_title", c -> task(c, who, "<b>Bold</b>", null, null));
            refused(db, "operations_task_title", c -> task(c, who, "", null, null));
            refused(db, "operations_task_title", c -> task(c, who, "x".repeat(121), null, null));
            refused(
                db,
                "operations_task_title",
                c -> task(c, who, "See https://example.org", null, null));
            refused(db, "operations_task_title", c -> task(c, who, " padded", null, null));
            refused(
                db,
                "operations_task_instructions",
                c -> task(c, who, "Task", "x".repeat(1001), null));
            refused(db, "operations_task_instructions", c -> task(c, who, "Task", "", null));
            refused(
                db,
                "operations_task_due",
                c -> task(c, who, "Task", null, at(CREATED.minusSeconds(1))));
            refused(db, "operations_task_due", c -> task(c, who, "Task", null, at(CREATED)));
            refused(
                db,
                "operations_task_due",
                c -> task(c, who, "Task", null, at(CREATED.plusSeconds(367L * 86_400))));
            // The edges are accepted: one microsecond after creation and exactly 366 days.
            committed(
                db,
                c -> {
                  task(c, who, "Soon", null, at(CREATED.plusNanos(1_000)));
                  task(c, who, "Far", null, at(CREATED.plusSeconds(366L * 86_400)));
                });
            refused(
                db,
                "operations_task_priority",
                c ->
                    exec(
                        c,
                        TASK,
                        tenant,
                        UUID.randomUUID(),
                        who.employee(),
                        "Task",
                        null,
                        "LOW",
                        null,
                        "OPEN",
                        1,
                        at(CREATED)));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** Inserts a task with its creation action; each test breaks one column. */
  private static void task(
      Connection c, Person who, String title, String instructions, Timestamp due)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        TASK,
        who.tenant(),
        id,
        who.employee(),
        title,
        instructions,
        "NORMAL",
        due,
        "OPEN",
        1,
        at(CREATED));
    exec(
        c,
        ACTION,
        who.tenant(),
        id,
        1,
        "CREATED",
        at(CREATED),
        "admin-subject",
        "tenant-admin",
        null,
        null,
        "OPEN",
        null,
        null,
        null);
  }

  // ------------------------------------------------------------------------------------------
  // The text grammar: the database refuses at least everything the application refuses
  // ------------------------------------------------------------------------------------------

  @Test
  void theDatabaseGrammarIsASupersetOfWhatTheApplicationAccepts() {
    List<String> corpus =
        List.of(
            "Prepare the delivery",
            "Préparer la livraison — très tôt",
            "Équipe d'été : vérifier l'inventaire (étage 2)",
            "Dupont & Fils : 10 % de remise",
            "Réunion à 14:30, salle B/C et/ou D",
            "<b>bold</b>",
            "a < b and c > d",
            "https://example.org",
            "HTTP://EXAMPLE.ORG",
            "h t t p s : //x",
            "www.example.org",
            "example.com/path",
            "a://b",
            "mailto:someone",
            "data:text/html",
            "javascript:alert",
            "ws:abc",
            "%41",
            "%4a",
            "&#65;",
            "&amp;",
            "&lt;",
            "back\\slash",
            "\\u0041",
            " leading",
            "trailing ",
            "tab\there",
            "line\nbreak",
            "soft\u00ADhyphen",
            "zero\u200Bwidth",
            "bidi\u202Etext",
            "bom\uFEFFtext",
            "private\uE000use",
            "emoji 👍 ok",
            "ＨＴＴＰＳ：//x",
            "ｗｗｗ．example．org",
            "＜b＞",
            "c:/windows",
            "//host/share",
            "e\u0301cole",
            "École",
            "[x](y)",
            "![x]",
            "onclick=1",
            "style = 2",
            "100% sûr",
            "Note: ok",
            "tel:123456",
            "tel: 123456",
            "x".repeat(120),
            "x".repeat(121));
    withDatabase(
        db -> {
          migrate(db.url(), "41");
          for (String value : corpus) {
            boolean application = TaskText.isValid(value, 1, 120);
            Boolean database =
                db.jdbc()
                    .queryForObject(
                        "SELECT operations.task_text_valid(?, 1, 120)", Boolean.class, value);
            assertThat(!application || Boolean.TRUE.equals(database))
                .as("application accepts but the database refuses: %s", escaped(value))
                .isTrue();
          }
          // The shared boundaries.
          assertThat(TaskText.isValid("x".repeat(120), 1, 120)).isTrue();
          assertThat(TaskText.isValid("x".repeat(121), 1, 120)).isFalse();
          assertThat(TaskText.isValid("é".repeat(10), 10, 500)).isTrue();
          assertThat(TaskText.isValid("x".repeat(9), 10, 500)).isFalse();
        });
  }

  private static String escaped(String value) {
    StringBuilder text = new StringBuilder();
    value
        .codePoints()
        .forEach(
            cp -> text.append(cp < 128 ? Character.toString(cp) : "\\u" + Integer.toHexString(cp)));
    return text.toString();
  }

  // ------------------------------------------------------------------------------------------
  // At most 10,000 non-terminal tasks per tenant
  // ------------------------------------------------------------------------------------------

  @Test
  void aTenantHoldsAtMostTenThousandNonTerminalTasks() throws Exception {
    withDatabase(
        db -> {
          migrate(db.url(), "41");
          UUID tenant = organization(db.jdbc());
          UUID other = organization(db.jdbc());
          Person who = employee(db.jdbc(), tenant);
          Person elsewhere = employee(db.jdbc(), other);
          try {
            // Seed 10,000 open tasks with the guards disabled for the seeding statement only.
            committed(
                db,
                c -> {
                  exec(c, "SET LOCAL session_replication_role = replica");
                  exec(
                      c,
                      "INSERT INTO operations.task (tenant_id, id, assignee_employee_id, title,"
                          + " priority, state, version, created_at, created_by)"
                          + " SELECT ?, gen_random_uuid(), ?, 'Seed ' || g, 'NORMAL', 'OPEN', 1,"
                          + " ?, 'seed' FROM generate_series(1, 10000) g",
                      tenant,
                      who.employee(),
                      at(CREATED));
                });
            assertThat(
                    count(db, "SELECT count(*) FROM operations.task WHERE tenant_id = ?", tenant))
                .isEqualTo(10_000);
            refused(db, "operations_task_open_limit", c -> openTask(c, who));
            // Another tenant is not affected.
            committed(db, c -> openTask(c, elsewhere));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
