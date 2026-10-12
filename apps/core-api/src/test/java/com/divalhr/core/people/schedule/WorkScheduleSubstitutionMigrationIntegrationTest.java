package com.divalhr.core.people.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
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
 * MVP-051 V27 directly against PostgreSQL, bypassing the application (the database is the final
 * authority, D51-3, D51-4, D51-10 items 18 and 19, D51-11): the refusing rollback that restores V26
 * exactly; append-only requests and decisions; the request, decision and pending guards; the
 * decision's shape and authority; coverage bound to the original employee or to the approved
 * substitute and never updated, re-added or removed out of turn; the deferred agreement of
 * requests, decisions, pending rows and coverage, also across a cancellation; the employee
 * exclusion on substituted coverage; and the fresh-database query plans.
 */
@IntegrationTest
class WorkScheduleSubstitutionMigrationIntegrationTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final String KINSHASA = "Africa/Kinshasa";
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
  private static final String REASON = "Formation obligatoire";

  /** The people schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'people'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'people'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'people'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'people'::regnamespace), '')
      """;

  private static final String REQUEST =
      "INSERT INTO people.work_schedule_substitution_request (tenant_id, id, schedule_id,"
          + " slot_id, employee_id, employment_id, reason_locale, reason, requested_at,"
          + " requested_by) VALUES (?, ?, ?, ?, ?, ?, 'fr', ?, ?, 'test')";

  private static final String PENDING =
      "INSERT INTO people.work_schedule_substitution_pending (tenant_id, request_id, slot_id,"
          + " schedule_id) VALUES (?, ?, ?, ?)";

  private static final String DECISION =
      "INSERT INTO people.work_schedule_substitution_decision (tenant_id, id, request_id,"
          + " schedule_id, slot_id, original_employee_id, outcome, manager_employee_id,"
          + " replacement_employee_id, replacement_employment_id, reason_locale, reason,"
          + " decided_at, decided_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'fr', ?, ?, 'test')";

  private static final String COVERAGE =
      "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
          + " employee_id, start_at, end_at, decision_id) VALUES (?, ?, ?, ?, ?, ?, ?)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** An employee and their one employment. */
  private record Hired(UUID employee, UUID employment) {}

  /** A tenant whose manager Mia manages Ana and Ben; Noa reports to nobody here. */
  private record World(
      UUID tenant,
      UUID site,
      Hired mia,
      Hired ana,
      Hired ben,
      Hired noa,
      UUID schedule,
      UUID slot,
      Timestamp start,
      Timestamp end) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "subst27_" + UUID.randomUUID().toString().replace("-", "");
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

  private void refused(Db db, String constraint, SqlWork work) {
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
                    .as(e.getMessage())
                    .isEqualTo(constraint);
              });
      c.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private void refusedMessage(Db db, String message, SqlWork work) {
    try (Connection c = connect(db)) {
      assertThatThrownBy(
              () -> {
                work.run(c);
                c.commit();
              })
          .isInstanceOf(PSQLException.class)
          .hasMessageContaining(message);
      c.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Disables the table's user triggers in this transaction (only to reach a constraint). */
  private static void withoutGuards(Connection c, String table) throws SQLException {
    exec(c, "ALTER TABLE people." + table + " DISABLE TRIGGER USER");
  }

  private void committed(Db db, SqlWork work) {
    try (Connection c = connect(db)) {
      work.run(c);
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int count(Db db, String sql, Object... args) {
    return Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class, args));
  }

  private static String signature(Db db) {
    return db.jdbc().queryForObject(SIGNATURE, String.class);
  }

  private static Timestamp ts(LocalDateTime local) {
    return Timestamp.from(local.atZone(ZoneId.of(KINSHASA)).toInstant());
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private static UUID organization(Connection c, UUID site) throws SQLException {
    UUID tenant = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', ?, 'ACTIVE', now(),"
            + " 'test')",
        tenant,
        KINSHASA);
    exec(
        c,
        "INSERT INTO tenant.legal_entity (id, tenant_id, code, name, country_code,"
            + " effective_from, created_at, created_by) VALUES (?, ?, 'LE', 'Entité', 'CD',"
            + " DATE '2026-01-01', now(), 'test')",
        entity,
        tenant);
    exec(
        c,
        "INSERT INTO tenant.site (id, tenant_id, legal_entity_id, code, name, timezone,"
            + " effective_from, created_at, created_by) VALUES (?, ?, ?, 'S1', 'Site', ?,"
            + " DATE '2026-01-01', now(), 'test')",
        site,
        tenant,
        entity,
        KINSHASA);
    return tenant;
  }

  /** An employee placed at the site from START. */
  private static Hired hire(Connection c, UUID tenant, UUID site, String number)
      throws SQLException {
    Hired h = new Hired(UUID.randomUUID(), UUID.randomUUID());
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana', 'Mbuyi',"
            + " ' ana mbuyi ', now(), 'test')",
        h.employee(),
        tenant,
        number);
    exec(
        c,
        "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from, created_at,"
            + " created_by) VALUES (?, ?, ?, ?, now(), 'test')",
        h.employment(),
        tenant,
        h.employee(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, recorded_at, recorded_by, version_after) VALUES (?, ?, ?,"
            + " ?, 'HIRE', ?, ARRAY['PLACEMENT'], now(), 'test', 0)",
        change,
        tenant,
        h.employee(),
        h.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'PLACEMENT', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        h.employee(),
        h.employment(),
        START,
        UUID.randomUUID(),
        site,
        change,
        change);
    return h;
  }

  /** An open-ended MANAGER line of the report's employment, from START. */
  private static void reportsTo(Connection c, UUID tenant, Hired report, Hired manager)
      throws SQLException {
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, recorded_at, recorded_by, version_after) VALUES"
            + " (?, ?, ?, ?, 'CHANGE', ?, ARRAY['MANAGER'], 'SCHEDULED', now(), 'test', 1)",
        change,
        tenant,
        report.employee(),
        report.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, manager_employee_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'MANAGER', ?, ?, ?, ?)",
        UUID.randomUUID(),
        tenant,
        report.employee(),
        report.employment(),
        START,
        manager.employee(),
        change,
        change);
  }

  /** A complete, operational one-slot schedule (evidence and coverage); its slot id. */
  private static UUID operational(
      Connection c,
      UUID tenant,
      UUID site,
      UUID schedule,
      LocalDateTime start,
      LocalDateTime end,
      UUID employee)
      throws SQLException {
    UUID slot = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
            + " site_timezone, slot_count, assigned_count, published_at, published_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, 'test')",
        schedule,
        tenant,
        site,
        start.toLocalDate(),
        start.toLocalDate(),
        KINSHASA,
        EARLY);
    exec(
        c,
        "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position,"
            + " start_local, end_local, start_at, end_at, label_en, label_fr,"
            + " assigned_employee_id) VALUES (?, ?, ?, 1, ?, ?, ?, ?, 'Cashier', 'Caissière', ?)",
        slot,
        tenant,
        schedule,
        start,
        end,
        ts(start),
        ts(end),
        employee);
    exec(
        c,
        "INSERT INTO people.work_schedule_site_coverage (tenant_id, schedule_id, site_id,"
            + " first_date, last_date) VALUES (?, ?, ?, ?, ?)",
        tenant,
        schedule,
        site,
        start.toLocalDate(),
        start.toLocalDate());
    exec(
        c,
        "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
            + " employee_id, start_at, end_at) VALUES (?, ?, ?, ?, ?, ?)",
        tenant,
        slot,
        schedule,
        employee,
        ts(start),
        ts(end));
    return slot;
  }

  private World world(Db db) {
    UUID site = UUID.randomUUID();
    UUID schedule = UUID.randomUUID();
    LocalDateTime start = DAY.atTime(8, 0);
    LocalDateTime end = DAY.atTime(16, 0);
    World[] built = new World[1];
    committed(
        db,
        c -> {
          UUID tenant = organization(c, site);
          Hired mia = hire(c, tenant, site, "E-MIA");
          Hired ana = hire(c, tenant, site, "E-ANA");
          Hired ben = hire(c, tenant, site, "E-BEN");
          Hired noa = hire(c, tenant, site, "E-NOA");
          reportsTo(c, tenant, ana, mia);
          reportsTo(c, tenant, ben, mia);
          UUID slot = operational(c, tenant, site, schedule, start, end, ana.employee());
          built[0] =
              new World(tenant, site, mia, ana, ben, noa, schedule, slot, ts(start), ts(end));
        });
    return built[0];
  }

  private static void request(Connection c, World w, UUID id, Timestamp at) throws SQLException {
    exec(
        c,
        REQUEST,
        w.tenant(),
        id,
        w.schedule(),
        w.slot(),
        w.ana().employee(),
        w.ana().employment(),
        REASON,
        at);
  }

  private static void pending(Connection c, World w, UUID request) throws SQLException {
    exec(c, PENDING, w.tenant(), request, w.slot(), w.schedule());
  }

  private static void decision(
      Connection c,
      World w,
      UUID id,
      UUID request,
      String outcome,
      Hired manager,
      Hired substitute,
      Timestamp at)
      throws SQLException {
    exec(
        c,
        DECISION,
        w.tenant(),
        id,
        request,
        w.schedule(),
        w.slot(),
        w.ana().employee(),
        outcome,
        manager.employee(),
        substitute == null ? null : substitute.employee(),
        substitute == null ? null : substitute.employment(),
        "Accordé",
        at);
  }

  private static void deleteOriginal(Connection c, World w) throws SQLException {
    exec(
        c,
        "DELETE FROM people.work_schedule_slot_coverage WHERE tenant_id = ? AND slot_id = ?"
            + " AND decision_id IS NULL",
        w.tenant(),
        w.slot());
  }

  private static void substitute(Connection c, World w, UUID decision, Hired substitute)
      throws SQLException {
    exec(
        c,
        COVERAGE,
        w.tenant(),
        w.slot(),
        w.schedule(),
        substitute.employee(),
        w.start(),
        w.end(),
        decision);
  }

  private static void unpend(Connection c, World w, UUID request) throws SQLException {
    exec(
        c,
        "DELETE FROM people.work_schedule_substitution_pending WHERE tenant_id = ?"
            + " AND request_id = ?",
        w.tenant(),
        request);
  }

  /** A complete approval of a new request: request, pending, decision, coverage move. */
  private static void approved(Connection c, World w, UUID request, UUID decision)
      throws SQLException {
    request(c, w, request, EARLY);
    pending(c, w, request);
    decision(c, w, decision, request, "APPROVED", w.mia(), w.ben(), EARLY);
    deleteOriginal(c, w);
    substitute(c, w, decision, w.ben());
    unpend(c, w, request);
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV26ExactlyAndRefusesEvidenceOrSubstitutedCoverage() {
    withDatabase(
        db -> {
          migrate(db.url(), "26");
          String v26 = signature(db);
          World w = world(db);
          migrate(db.url(), "27");
          String v27 = signature(db);
          assertThat(v27)
              .isNotEqualTo(v26)
              .contains("work_schedule_substitution_request")
              .contains("published_employee_id");
          // The V26 coverage row is an original row.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_slot_coverage"
                          + " WHERE decision_id IS NULL AND published_employee_id = employee_id"))
              .isEqualTo(1);
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V27__rollback.sql"));
          assertThat(signature(db)).isEqualTo(v26);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot_coverage"))
              .isEqualTo(1);

          // A request refuses the rollback; nothing changes.
          db.jdbc().execute(resource("db/migration/V27__work_schedule_substitution.sql"));
          assertThat(signature(db)).isEqualTo(v27);
          committed(
              db,
              c -> {
                UUID id = UUID.randomUUID();
                request(c, w, id, EARLY);
                pending(c, w, id);
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V27__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V27 rollback refused: work schedule substitution evidence");
          assertThat(signature(db)).isEqualTo(v27);
        });
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          String v27 = signature(db);
          // Coverage that is no longer the original (only reachable with every trigger off).
          db.jdbc()
              .execute(
                  "ALTER TABLE people.work_schedule_slot_coverage DISABLE TRIGGER ALL;"
                      + " UPDATE people.work_schedule_slot_coverage SET employee_id = '"
                      + w.ben().employee()
                      + "';"
                      + " ALTER TABLE people.work_schedule_slot_coverage ENABLE TRIGGER ALL");
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V27__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("the slot coverage is not the original assignments");
          assertThat(signature(db)).isEqualTo(v27);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Evidence and guards
  // ------------------------------------------------------------------------------------------

  @Test
  void requestsAndDecisionsAreAppendOnlyAndThePendingProjectionIsGuarded() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          UUID rejected = UUID.randomUUID();
          UUID pendingRequest = UUID.randomUUID();
          committed(
              db,
              c -> {
                request(c, w, rejected, EARLY);
                pending(c, w, rejected);
                decision(c, w, UUID.randomUUID(), rejected, "REJECTED", w.mia(), null, EARLY);
                unpend(c, w, rejected);
                request(c, w, pendingRequest, EARLY);
                pending(c, w, pendingRequest);
              });
          for (String table :
              new String[] {
                "work_schedule_substitution_request", "work_schedule_substitution_decision"
              }) {
            refused(
                db,
                table + "_immutable",
                c -> exec(c, "UPDATE people." + table + " SET reason = 'Autre raison'"));
            refused(db, table + "_immutable", c -> exec(c, "DELETE FROM people." + table));
            // Referenced by foreign keys: PostgreSQL refuses before the trigger even runs.
            refusedMessage(
                db,
                "cannot truncate a table referenced in a foreign key constraint",
                c -> exec(c, "TRUNCATE people." + table));
          }
          refused(
              db,
              "work_schedule_substitution_pending_guard",
              c ->
                  exec(
                      c, "UPDATE people.work_schedule_substitution_pending SET slot_id = slot_id"));
          refused(
              db, "work_schedule_substitution_pending_guard", c -> unpend(c, w, pendingRequest));
          refused(
              db,
              "work_schedule_substitution_pending_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule_substitution_pending"));
          // A decided request is never pending again; one pending request per slot.
          refused(db, "work_schedule_substitution_pending_guard", c -> pending(c, w, rejected));
          refused(
              db,
              "work_schedule_substitution_pending_slot_unique",
              c -> {
                UUID another = UUID.randomUUID();
                request(c, w, another, EARLY);
                pending(c, w, another);
              });
          // A request without its pending row cannot commit.
          refused(
              db, "work_schedule_substitution_exact", c -> request(c, w, UUID.randomUUID(), EARLY));
        });
  }

  @Test
  void aRequestNamesTheOriginalOperationalFutureShiftAndAQualifyingManagedEmployment() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          // Another employee's slot.
          refused(
              db,
              "work_schedule_substitution_request_slot",
              c ->
                  exec(
                      c,
                      REQUEST,
                      w.tenant(),
                      UUID.randomUUID(),
                      w.schedule(),
                      w.slot(),
                      w.ben().employee(),
                      w.ben().employment(),
                      REASON,
                      EARLY));
          // Another employee's employment.
          refused(
              db,
              "work_schedule_substitution_request_employment",
              c -> {
                withoutGuards(c, "work_schedule_substitution_request");
                exec(
                    c,
                    REQUEST,
                    w.tenant(),
                    UUID.randomUUID(),
                    w.schedule(),
                    w.slot(),
                    w.ana().employee(),
                    w.ben().employment(),
                    REASON,
                    EARLY);
              });
          // At or after the shift's start.
          refused(
              db,
              "work_schedule_substitution_request_window",
              c -> request(c, w, UUID.randomUUID(), w.start()));
          // An invalid reason or locale.
          refused(
              db,
              "work_schedule_substitution_request_reason_valid",
              c ->
                  exec(
                      c,
                      REQUEST,
                      w.tenant(),
                      UUID.randomUUID(),
                      w.schedule(),
                      w.slot(),
                      w.ana().employee(),
                      w.ana().employment(),
                      "x‮y",
                      EARLY));
          // Noa has no manager: her own shift, on the next day, cannot be requested.
          UUID schedule = UUID.randomUUID();
          UUID[] slot = new UUID[1];
          committed(
              db,
              c ->
                  slot[0] =
                      operational(
                          c,
                          w.tenant(),
                          w.site(),
                          schedule,
                          DAY.plusDays(1).atTime(8, 0),
                          DAY.plusDays(1).atTime(12, 0),
                          w.noa().employee()));
          refused(
              db,
              "work_schedule_substitution_request_employment_qualifies",
              c ->
                  exec(
                      c,
                      REQUEST,
                      w.tenant(),
                      UUID.randomUUID(),
                      schedule,
                      slot[0],
                      w.noa().employee(),
                      w.noa().employment(),
                      REASON,
                      EARLY));
        });
  }

  @Test
  void aDecisionHasItsShapeAndIsTakenByTheEffectiveManagerForAQualifyingSubstitute() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          UUID id = UUID.randomUUID();
          committed(
              db,
              c -> {
                request(c, w, id, EARLY);
                pending(c, w, id);
              });
          refused(
              db,
              "work_schedule_substitution_decision_authority",
              c -> decision(c, w, UUID.randomUUID(), id, "REJECTED", w.noa(), null, EARLY));
          refused(
              db,
              "work_schedule_substitution_decision_substitute",
              c -> decision(c, w, UUID.randomUUID(), id, "APPROVED", w.mia(), w.noa(), EARLY));
          refused(
              db,
              "work_schedule_substitution_decision_shape",
              c -> {
                withoutGuards(c, "work_schedule_substitution_decision");
                decision(c, w, UUID.randomUUID(), id, "APPROVED", w.mia(), w.ana(), EARLY);
              });
          refused(
              db,
              "work_schedule_substitution_decision_shape",
              c -> {
                withoutGuards(c, "work_schedule_substitution_decision");
                decision(c, w, UUID.randomUUID(), id, "APPROVED", w.mia(), w.mia(), EARLY);
              });
          refused(
              db,
              "work_schedule_substitution_decision_shape",
              c -> decision(c, w, UUID.randomUUID(), id, "REJECTED", w.mia(), w.ben(), EARLY));
          refused(
              db,
              "work_schedule_substitution_decision_shape",
              c -> {
                withoutGuards(c, "work_schedule_substitution_decision");
                decision(c, w, UUID.randomUUID(), id, "APPROVED", w.mia(), null, EARLY);
              });
          refused(
              db,
              "work_schedule_substitution_decision_window",
              c -> decision(c, w, UUID.randomUUID(), id, "REJECTED", w.mia(), null, w.start()));
          // Still pending at commit, or approved without moving the coverage.
          refused(
              db,
              "work_schedule_substitution_exact",
              c -> decision(c, w, UUID.randomUUID(), id, "REJECTED", w.mia(), null, EARLY));
          refused(
              db,
              "work_schedule_substitution_exact",
              c -> {
                decision(c, w, UUID.randomUUID(), id, "APPROVED", w.mia(), w.ben(), EARLY);
                unpend(c, w, id);
              });
          // A separated substitute (a scheduled separation ending before the shift does; the
          // separation workflow's own triggers and keys are bypassed to place the row).
          committed(
              db,
              c -> {
                exec(c, "ALTER TABLE people.employment_separation DISABLE TRIGGER ALL");
                exec(
                    c,
                    "INSERT INTO people.employment_separation (id, tenant_id, employee_id,"
                        + " employment_id, change_id, last_day, reason_code, access_timing,"
                        + " report_count, interval_count, state, effective_at, recorded_at,"
                        + " recorded_by) VALUES (?, ?, ?, ?, ?, DATE '2026-11-01', 'RESIGNATION',"
                        + " 'END_OF_LAST_DAY', 0, 0, 'SCHEDULED', ?, now(), 'test')",
                    UUID.randomUUID(),
                    w.tenant(),
                    w.ben().employee(),
                    w.ben().employment(),
                    UUID.randomUUID(),
                    Timestamp.from(Instant.parse("2026-11-01T22:00:00Z")));
                exec(c, "ALTER TABLE people.employment_separation ENABLE TRIGGER ALL");
              });
          refused(
              db,
              "work_schedule_substitution_decision_substitute",
              c -> decision(c, w, UUID.randomUUID(), id, "APPROVED", w.mia(), w.ben(), EARLY));
          // One decision per request.
          committed(
              db,
              c -> {
                decision(c, w, UUID.randomUUID(), id, "REJECTED", w.mia(), null, EARLY);
                unpend(c, w, id);
              });
          refused(
              db,
              "work_schedule_substitution_decision_window",
              c -> decision(c, w, UUID.randomUUID(), id, "REJECTED", w.mia(), null, EARLY));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Coverage
  // ------------------------------------------------------------------------------------------

  @Test
  void coverageIsBoundToTheOriginalOrTheApprovedSubstituteAndMovesOnlyOnApproval() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          String evidence =
              db.jdbc()
                  .queryForObject(
                      "SELECT (SELECT row_to_json(s)::text FROM people.work_schedule s)"
                          + " || (SELECT row_to_json(l)::text FROM people.work_schedule_slot l)",
                      String.class);
          refused(
              db,
              "work_schedule_slot_coverage_guard",
              c -> exec(c, "UPDATE people.work_schedule_slot_coverage SET end_at = end_at"));
          refused(db, "work_schedule_slot_coverage_guard", c -> deleteOriginal(c, w));
          // Without a decision, only the original employee.
          refused(
              db,
              "work_schedule_slot_coverage_published",
              c -> {
                withoutGuards(c, "work_schedule_slot_coverage");
                deleteOriginal(c, w);
                substitute(c, w, null, w.ben());
              });
          UUID request = UUID.randomUUID();
          UUID rejectedDecision = UUID.randomUUID();
          committed(
              db,
              c -> {
                request(c, w, request, EARLY);
                pending(c, w, request);
                decision(c, w, rejectedDecision, request, "REJECTED", w.mia(), null, EARLY);
                unpend(c, w, request);
              });
          // A rejection binds nothing and moves nothing.
          refused(
              db,
              "work_schedule_slot_coverage_substitution",
              c -> {
                withoutGuards(c, "work_schedule_slot_coverage");
                deleteOriginal(c, w);
                substitute(c, w, rejectedDecision, w.ben());
              });
          refused(db, "work_schedule_slot_coverage_guard", c -> deleteOriginal(c, w));

          UUID approvedRequest = UUID.randomUUID();
          UUID approval = UUID.randomUUID();
          committed(db, c -> approved(c, w, approvedRequest, approval));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT (SELECT row_to_json(s)::text FROM people.work_schedule s)"
                              + " || (SELECT row_to_json(l)::text FROM"
                              + " people.work_schedule_slot l)",
                          String.class))
              .isEqualTo(evidence);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_slot_coverage"
                          + " WHERE employee_id = ? AND decision_id = ?",
                      w.ben().employee(),
                      approval))
              .isEqualTo(1);
          // No second approval, no original row back, no other substitute, no removal.
          refused(
              db,
              "work_schedule_substitution_request_window",
              c -> request(c, w, UUID.randomUUID(), EARLY));
          refused(
              db,
              "work_schedule_slot_coverage_guard",
              c ->
                  exec(
                      c,
                      "DELETE FROM people.work_schedule_slot_coverage WHERE decision_id = ?",
                      approval));
          refused(
              db,
              "work_schedule_slot_coverage_substitution",
              c -> {
                withoutGuards(c, "work_schedule_slot_coverage");
                exec(c, "DELETE FROM people.work_schedule_slot_coverage");
                substitute(c, w, approval, w.noa());
              });
          // Leaving the original row next to the substitute's is refused at commit.
          withDatabase(
              fresh -> {
                migrate(fresh.url(), "27");
                World v = world(fresh);
                refused(
                    fresh,
                    "work_schedule_substitution_exact",
                    c -> {
                      UUID r = UUID.randomUUID();
                      UUID d = UUID.randomUUID();
                      request(c, v, r, EARLY);
                      pending(c, v, r);
                      decision(c, v, d, r, "APPROVED", v.mia(), v.ben(), EARLY);
                      unpend(c, v, r);
                    });
              });
        });
  }

  /** Records the schedule's cancellation and removes its coverage, and its pending rows. */
  private static void cancelled(Connection c, World w, boolean releasePending) throws SQLException {
    exec(
        c,
        "INSERT INTO people.work_schedule_cancellation (tenant_id, schedule_id, reason,"
            + " cancelled_at, cancelled_by) VALUES (?, ?, ?, ?, 'test')",
        w.tenant(),
        w.schedule(),
        "Fermeture du site",
        EARLY);
    if (releasePending) {
      exec(
          c,
          "DELETE FROM people.work_schedule_substitution_pending WHERE tenant_id = ?"
              + " AND schedule_id = ?",
          w.tenant(),
          w.schedule());
    }
    exec(
        c,
        "DELETE FROM people.work_schedule_slot_coverage WHERE tenant_id = ? AND schedule_id = ?",
        w.tenant(),
        w.schedule());
    exec(
        c,
        "DELETE FROM people.work_schedule_site_coverage WHERE tenant_id = ? AND schedule_id = ?",
        w.tenant(),
        w.schedule());
  }

  @Test
  void substitutedCoverageKeepsTheEmployeeExclusionAndACancellationReleasesEverything() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          // Ben already works 12-20 the same day at another site: approving him overlaps.
          committed(
              db,
              c -> {
                UUID entity =
                    UUID.fromString(
                        java.util.Objects.requireNonNull(
                            db.jdbc()
                                .queryForObject(
                                    "SELECT legal_entity_id::text FROM tenant.site WHERE id = ?",
                                    String.class,
                                    w.site())));
                UUID other = UUID.randomUUID();
                exec(
                    c,
                    "INSERT INTO tenant.site (id, tenant_id, legal_entity_id, code, name,"
                        + " timezone, effective_from, created_at, created_by) VALUES (?, ?, ?,"
                        + " 'S2', 'Site', ?, DATE '2026-01-01', now(), 'test')",
                    other,
                    w.tenant(),
                    entity,
                    KINSHASA);
                operational(
                    c,
                    w.tenant(),
                    other,
                    UUID.randomUUID(),
                    DAY.atTime(12, 0),
                    DAY.atTime(20, 0),
                    w.ben().employee());
              });
          refused(
              db,
              "work_schedule_slot_employee_no_overlap",
              c -> approved(c, w, UUID.randomUUID(), UUID.randomUUID()));

          // A pending request: the cancellation must remove its pending row too.
          UUID request = UUID.randomUUID();
          committed(
              db,
              c -> {
                request(c, w, request, EARLY);
                pending(c, w, request);
              });
          refused(db, "work_schedule_substitution_exact", c -> cancelled(c, w, false));
          committed(db, c -> cancelled(c, w, true));
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_substitution_request"
                          + " WHERE id = ?",
                      request))
              .isEqualTo(1);
          // A voided request is never pending again and never decided.
          refused(db, "work_schedule_substitution_pending_guard", c -> pending(c, w, request));
          refused(
              db,
              "work_schedule_substitution_decision_window",
              c -> decision(c, w, UUID.randomUUID(), request, "REJECTED", w.mia(), null, EARLY));
        });
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          World w = world(db);
          committed(db, c -> approved(c, w, UUID.randomUUID(), UUID.randomUUID()));
          // The substitute's coverage goes with the schedule's cancellation.
          committed(db, c -> cancelled(c, w, true));
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot_coverage")).isZero();
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_substitution_decision"
                          + " WHERE outcome = 'APPROVED'"))
              .isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Query plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theLookupsUseTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "27");
          UUID tenant = UUID.randomUUID();
          UUID id = UUID.randomUUID();
          Timestamp at = EARLY;
          assertThat(
                  plan(
                      db,
                      "SELECT id FROM people.work_schedule_substitution_request r"
                          + " WHERE r.tenant_id = ? AND r.employee_id = ?"
                          + " ORDER BY r.requested_at DESC, r.id DESC LIMIT 26",
                      tenant,
                      id))
              .contains("work_schedule_substitution_request_employee");
          assertThat(
                  plan(
                      db,
                      "SELECT r.id FROM people.work_schedule_substitution_request r"
                          + " WHERE r.tenant_id = ? AND r.employment_id IN (SELECT"
                          + " ma.employment_id FROM people.employment_assignment ma"
                          + " WHERE ma.tenant_id = ? AND ma.kind = 'MANAGER'"
                          + " AND ma.superseded_by_change_id IS NULL"
                          + " AND ma.manager_employee_id = ?)",
                      tenant,
                      tenant,
                      id))
              .contains("work_schedule_substitution_request_employment")
              .contains("on employment_assignment ma")
              .doesNotContain("Seq Scan");
          assertThat(
                  plan(
                      db,
                      "SELECT p.id FROM people.employee p WHERE p.tenant_id = ?"
                          + " AND p.id IN (SELECT ra.employee_id FROM"
                          + " people.employment_assignment ra WHERE ra.tenant_id = ?"
                          + " AND ra.kind = 'MANAGER' AND ra.superseded_by_change_id IS NULL"
                          + " AND ra.manager_employee_id = ?)",
                      tenant,
                      tenant,
                      id))
              .contains("on employment_assignment ra")
              .doesNotContain("Seq Scan");
          assertThat(
                  plan(
                      db,
                      "SELECT slot_id FROM people.work_schedule_slot_coverage c"
                          + " WHERE c.tenant_id = ? AND c.employee_id = ? AND c.end_at > ?"
                          + " ORDER BY c.start_at, c.slot_id LIMIT 26",
                      tenant,
                      id,
                      at))
              .contains("work_schedule_slot_coverage_employee");
          assertThat(
                  plan(
                      db,
                      "SELECT employee_id FROM people.work_schedule_slot_coverage"
                          + " WHERE tenant_id = ? AND slot_id = ?",
                      tenant,
                      id))
              .contains("work_schedule_slot_coverage_pkey");
          assertThat(
                  plan(
                      db,
                      "DELETE FROM people.work_schedule_substitution_pending"
                          + " WHERE tenant_id = ? AND schedule_id = ?",
                      tenant,
                      id))
              .contains("work_schedule_substitution_pending_schedule");
          assertThat(
                  plan(
                      db,
                      "SELECT 1 FROM people.work_schedule_substitution_decision"
                          + " WHERE tenant_id = ? AND slot_id = ? AND outcome = 'APPROVED'",
                      tenant,
                      id))
              .contains("work_schedule_substitution_decision_one_approval");
          assertThat(
                  plan(
                      db,
                      "SELECT 1 FROM people.work_schedule_substitution_pending"
                          + " WHERE tenant_id = ? AND slot_id = ?",
                      tenant,
                      id))
              .contains("work_schedule_substitution_pending_slot_unique");
        });
  }

  private String plan(Db db, String sql, Object... args) {
    try (Connection c = connect(db)) {
      exec(c, "SET LOCAL enable_seqscan = off");
      StringBuilder plan = new StringBuilder();
      try (PreparedStatement explain = c.prepareStatement("EXPLAIN " + sql)) {
        for (int i = 0; i < args.length; i++) {
          explain.setObject(i + 1, args[i]);
        }
        try (var rows = explain.executeQuery()) {
          while (rows.next()) {
            plan.append(rows.getString(1)).append('\n');
          }
        }
      }
      c.rollback();
      return plan.toString();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
