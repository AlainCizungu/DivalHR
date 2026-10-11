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
 * MVP-050B V26 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the backfill of every V25 schedule and assigned slot into exact coverage projections;
 * the refusing rollback that restores V25 exactly; append-only cancellation and replacement
 * evidence with one terminal action per schedule; the change window; the replacement's shape and
 * its A, B, C chain without branch or cycle; guarded projections (never updated or truncated,
 * inserted only for an operational schedule, deleted only once it has a terminal action); the
 * deferred exactness of coverage; the exclusions on the projections, released by a terminal action
 * and reusable; and the lookups' indexes.
 */
@IntegrationTest
class WorkScheduleLifecycleMigrationIntegrationTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
  private static final String KINSHASA = "Africa/Kinshasa";

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

  private static final String SCHEDULE =
      "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
          + " site_timezone, slot_count, assigned_count, published_at, published_by) VALUES (?, ?,"
          + " ?, ?, ?, ?, ?, ?, now(), 'test')";

  private static final String SLOT =
      "INSERT INTO people.work_schedule_slot (id, tenant_id, schedule_id, position, start_local,"
          + " end_local, start_at, end_at, label_en, label_fr, assigned_employee_id)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /** A tenant with one legal entity and two sites in the organization's time zone. */
  private record Org(UUID tenant, UUID entity, UUID site, UUID otherSite) {}

  /** A schedule row: id, tenant, site, dates, zone, counts. */
  private record Schedule(
      UUID id,
      UUID tenant,
      UUID site,
      LocalDate first,
      LocalDate last,
      String zone,
      int slots,
      int assigned) {

    Schedule withCounts(int slotCount, int assignedCount) {
      return new Schedule(id, tenant, site, first, last, zone, slotCount, assignedCount);
    }

    Object[] row() {
      return new Object[] {id, tenant, site, first, last, zone, slots, assigned};
    }
  }

  private void withDatabase(Consumer<Db> body) {
    String database = "sched26_" + UUID.randomUUID().toString().replace("-", "");
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
                    .isEqualTo(constraint);
              });
      c.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
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
    return java.util.Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class, args));
  }

  private static String signature(Db db) {
    return db.jdbc().queryForObject(SIGNATURE, String.class);
  }

  private static Org organization(Db db) {
    UUID tenant = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    UUID site = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    JdbcTemplate t = db.jdbc();
    t.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', ?, 'ACTIVE', now(),"
            + " 'test')",
        tenant,
        KINSHASA);
    t.update(
        "INSERT INTO tenant.legal_entity (id, tenant_id, code, name, country_code,"
            + " effective_from, created_at, created_by) VALUES (?, ?, 'LE', 'Entité', 'CD',"
            + " DATE '2026-01-01', now(), 'test')",
        entity,
        tenant);
    for (UUID id : new UUID[] {site, other}) {
      t.update(
          "INSERT INTO tenant.site (id, tenant_id, legal_entity_id, code, name, timezone,"
              + " effective_from, created_at, created_by) VALUES (?, ?, ?, ?, 'Site', ?,"
              + " DATE '2026-01-01', now(), 'test')",
          id,
          tenant,
          entity,
          id.equals(site) ? "S1" : "S2",
          KINSHASA);
    }
    return new Org(tenant, entity, site, other);
  }

  /** One more site of the organization's legal entity, with its own time zone. */
  private static UUID site(Db db, Org org, String code, String zone) {
    UUID id = UUID.randomUUID();
    db.jdbc()
        .update(
            "INSERT INTO tenant.site (id, tenant_id, legal_entity_id, code, name, timezone,"
                + " effective_from, created_at, created_by) VALUES (?, ?, ?, ?, 'Site', ?,"
                + " DATE '2026-01-01', now(), 'test')",
            id,
            org.tenant(),
            org.entity(),
            code,
            zone);
    return id;
  }

  private static UUID employee(Db db, UUID tenant, String number) {
    UUID id = UUID.randomUUID();
    db.jdbc()
        .update(
            "INSERT INTO people.employee (id, tenant_id, employee_number, given_names,"
                + " family_name, search_key, created_at, created_by) VALUES (?, ?, ?, 'Ana',"
                + " 'Mbuyi', ' ana mbuyi ', now(), 'test')",
            id,
            tenant,
            number);
    return id;
  }

  private static Schedule schedule(UUID tenant, UUID site, LocalDate first, LocalDate last) {
    return new Schedule(UUID.randomUUID(), tenant, site, first, last, KINSHASA, 1, 0);
  }

  private static void insert(Connection c, Schedule s) throws SQLException {
    exec(c, SCHEDULE, s.row());
  }

  /** A slot row of the schedule at the local times, converted in the schedule's zone. */
  private static Object[] slot(
      Schedule s, int position, LocalDateTime start, LocalDateTime end, UUID employee) {
    ZoneId zone = ZoneId.of(s.zone());
    return new Object[] {
      UUID.randomUUID(),
      s.tenant(),
      s.id(),
      position,
      start,
      end,
      Timestamp.from(start.atZone(zone).toInstant()),
      Timestamp.from(end.atZone(zone).toInstant()),
      "Cashier",
      "Caissière",
      employee
    };
  }

  private static void insertSlot(Connection c, Object[] slot) throws SQLException {
    exec(c, SLOT, slot);
  }

  /** A complete one-slot schedule (assigned when the employee is not null). */
  private static void publish(Connection c, Schedule s, LocalDateTime start, UUID employee)
      throws SQLException {
    Schedule counted = s.withCounts(1, employee == null ? 0 : 1);
    insert(c, counted);
    insertSlot(c, slot(counted, 1, start, start.plusHours(8), employee));
  }

  private static LocalDateTime at(LocalDate day, int hour) {
    return day.atTime(hour, 0);
  }

  private static final String SITE_COVERAGE =
      "INSERT INTO people.work_schedule_site_coverage (tenant_id, schedule_id, site_id,"
          + " first_date, last_date) VALUES (?, ?, ?, ?, ?)";

  private static final String SLOT_COVERAGE =
      "INSERT INTO people.work_schedule_slot_coverage (tenant_id, slot_id, schedule_id,"
          + " employee_id, start_at, end_at) VALUES (?, ?, ?, ?, ?, ?)";

  private static final String CANCEL =
      "INSERT INTO people.work_schedule_cancellation (tenant_id, schedule_id, reason,"
          + " cancelled_at, cancelled_by) VALUES (?, ?, ?, ?, 'test')";

  private static final String REPLACE =
      "INSERT INTO people.work_schedule_replacement (tenant_id, schedule_id,"
          + " replacement_schedule_id, reason, replaced_at, replaced_by)"
          + " VALUES (?, ?, ?, ?, ?, 'test')";

  /** Long before every slot of the tests: the change window is open. */
  private static final Timestamp EARLY = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));

  private static final String SCHEDULE_AT =
      "INSERT INTO people.work_schedule (id, tenant_id, site_id, first_date, last_date,"
          + " site_timezone, slot_count, assigned_count, published_at, published_by)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'test')";

  /** A complete, operational one-slot schedule: evidence and coverage. */
  private static Object[] operational(
      Connection c, Schedule s, LocalDateTime start, UUID employee, Timestamp publishedAt)
      throws SQLException {
    Schedule counted = s.withCounts(1, employee == null ? 0 : 1);
    Object[] row = counted.row();
    exec(
        c,
        SCHEDULE_AT,
        row[0],
        row[1],
        row[2],
        row[3],
        row[4],
        row[5],
        row[6],
        row[7],
        publishedAt);
    Object[] slot = slot(counted, 1, start, start.plusHours(8), employee);
    insertSlot(c, slot);
    cover(c, counted);
    if (employee != null) {
      exec(c, SLOT_COVERAGE, s.tenant(), slot[0], s.id(), employee, slot[6], slot[7]);
    }
    return slot;
  }

  private static Object[] operational(Connection c, Schedule s, LocalDateTime start, UUID employee)
      throws SQLException {
    return operational(c, s, start, employee, EARLY);
  }

  private static void cover(Connection c, Schedule s) throws SQLException {
    exec(c, SITE_COVERAGE, s.tenant(), s.id(), s.site(), s.first(), s.last());
  }

  private static void release(Connection c, Schedule s) throws SQLException {
    exec(
        c,
        "DELETE FROM people.work_schedule_slot_coverage WHERE tenant_id = ? AND schedule_id = ?",
        s.tenant(),
        s.id());
    exec(
        c,
        "DELETE FROM people.work_schedule_site_coverage WHERE tenant_id = ? AND schedule_id = ?",
        s.tenant(),
        s.id());
  }

  private static void cancel(Connection c, Schedule s, Timestamp at) throws SQLException {
    exec(c, CANCEL, s.tenant(), s.id(), "Changement de planning", at);
  }

  private static Timestamp ts(LocalDateTime local) {
    return Timestamp.from(local.atZone(ZoneId.of(KINSHASA)).toInstant());
  }

  // ------------------------------------------------------------------------------------------
  // Backfill and rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void everyV25ScheduleAndAssignedSlotIsBackfilledIntoExactCoverage() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID ana = employee(db, org.tenant(), "E-1");
          UUID ben = employee(db, org.tenant(), "E-2");
          Schedule assigned = schedule(org.tenant(), org.site(), DAY, DAY.plusDays(2));
          Schedule uncovered = schedule(org.tenant(), org.otherSite(), DAY, DAY);
          committed(
              db,
              c -> {
                Schedule two = assigned.withCounts(3, 2);
                insert(c, two);
                insertSlot(c, slot(two, 1, at(DAY, 8), at(DAY, 16), ana));
                insertSlot(c, slot(two, 2, at(DAY, 8), at(DAY, 16), ben));
                insertSlot(c, slot(two, 3, at(DAY, 22), at(DAY.plusDays(1), 6), null));
              });
          committed(db, c -> publish(c, uncovered, at(DAY, 8), null));
          migrate(db.url(), "26");
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_site_coverage"))
              .isEqualTo(2);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_site_coverage c"
                          + " JOIN people.work_schedule s ON s.tenant_id = c.tenant_id"
                          + " AND s.id = c.schedule_id AND s.site_id = c.site_id"
                          + " AND s.first_date = c.first_date AND s.last_date = c.last_date"))
              .isEqualTo(2);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot_coverage"))
              .isEqualTo(2);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_slot_coverage c"
                          + " JOIN people.work_schedule_slot l ON l.tenant_id = c.tenant_id"
                          + " AND l.id = c.slot_id AND l.assigned_employee_id = c.employee_id"
                          + " AND l.start_at = c.start_at AND l.end_at = c.end_at"))
              .isEqualTo(2);
          // The exclusions moved: none on the evidence, one on each projection.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM pg_constraint WHERE contype = 'x' AND conrelid IN"
                          + " ('people.work_schedule'::regclass,"
                          + " 'people.work_schedule_slot'::regclass)"))
              .isZero();
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT string_agg(conrelid::regclass::text || ':' || conname, ','"
                              + " ORDER BY conname) FROM pg_constraint WHERE contype = 'x'"
                              + " AND connamespace = 'people'::regnamespace"
                              + " AND conname LIKE 'work_schedule%'",
                          String.class))
              .isEqualTo(
                  "people.work_schedule_site_coverage:work_schedule_site_no_overlap,"
                      + "people.work_schedule_slot_coverage:work_schedule_slot_employee_no_overlap");
          // The backfilled projections still refuse what V25 refused.
          refused(
              db,
              "work_schedule_site_no_overlap",
              c ->
                  operational(
                      c,
                      schedule(org.tenant(), org.site(), DAY.plusDays(1), DAY.plusDays(1)),
                      at(DAY.plusDays(1), 8),
                      null));
          refused(
              db,
              "work_schedule_slot_employee_no_overlap",
              c ->
                  operational(
                      c,
                      schedule(org.tenant(), site(db, org, "S3", KINSHASA), DAY, DAY),
                      at(DAY, 12),
                      ana));
        });
  }

  @Test
  void theRollbackRestoresV25ExactlyAndRefusesTerminalEvidenceOrDriftedCoverage() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          String v25 = signature(db);
          Org org = organization(db);
          UUID ana = employee(db, org.tenant(), "E-1");
          Schedule kept = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(db, c -> publish(c, kept, at(DAY, 8), ana));
          migrate(db.url(), "26");
          String v26 = signature(db);
          assertThat(v26)
              .isNotEqualTo(v25)
              .contains("work_schedule_cancellation")
              .contains("work_schedule_slot_coverage");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V26__rollback.sql"));
          assertThat(signature(db)).isEqualTo(v25);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot")).isEqualTo(1);

          // A cancellation refuses the rollback; nothing changes.
          db.jdbc().execute(resource("db/migration/V26__work_schedule_lifecycle.sql"));
          assertThat(signature(db)).isEqualTo(v26);
          committed(
              db,
              c -> {
                cancel(c, kept, EARLY);
                release(c, kept);
              });
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V26__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V26 rollback refused: cancelled or replaced");
          assertThat(signature(db)).isEqualTo(v26);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_cancellation"))
              .isEqualTo(1);
        });
    withDatabase(
        db -> {
          migrate(db.url(), "26");
          Org org = organization(db);
          Schedule s = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(db, c -> operational(c, s, at(DAY, 8), null));
          String v26 = signature(db);
          // Coverage that no longer matches (only reachable with the guards disabled) refuses too.
          db.jdbc()
              .execute(
                  "ALTER TABLE people.work_schedule_site_coverage DISABLE TRIGGER USER;"
                      + " DELETE FROM people.work_schedule_site_coverage;"
                      + " ALTER TABLE people.work_schedule_site_coverage ENABLE TRIGGER USER");
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V26__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("the coverage projections do not match");
          assertThat(signature(db)).isEqualTo(v26);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Terminal evidence
  // ------------------------------------------------------------------------------------------

  @Test
  void aCancellationIsAppendOnlyReleasesCoverageAtomicallyAndTheDatesAreReusable() {
    withDatabase(
        db -> {
          migrate(db.url(), "26");
          Org org = organization(db);
          UUID ana = employee(db, org.tenant(), "E-1");
          Schedule a = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(db, c -> operational(c, a, at(DAY, 8), ana));
          // Coverage is never removed without a terminal action, nor kept with one.
          refused(db, "work_schedule_slot_coverage_guard", c -> release(c, a));
          refused(db, "work_schedule_coverage_exact", c -> cancel(c, a, EARLY));
          refused(
              db,
              "work_schedule_cancellation_reason_valid",
              c -> exec(c, CANCEL, a.tenant(), a.id(), " padded", EARLY));
          // Released atomically: the cancellation and the coverage removal commit together.
          committed(
              db,
              c -> {
                cancel(c, a, EARLY);
                release(c, a);
              });
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_site_coverage"
                          + " WHERE schedule_id = ?",
                      a.id()))
              .isZero();
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule WHERE id = ?", a.id()))
              .isEqualTo(1);
          // The same site date and the same employee instants are free again.
          committed(
              db,
              c -> operational(c, schedule(org.tenant(), org.site(), DAY, DAY), at(DAY, 8), ana));
          // One terminal action, append-only.
          refused(db, "work_schedule_single_terminal", c -> cancel(c, a, EARLY));
          refused(
              db,
              "work_schedule_cancellation_immutable",
              c -> exec(c, "UPDATE people.work_schedule_cancellation SET reason = 'Autre raison'"));
          refused(
              db,
              "work_schedule_cancellation_immutable",
              c -> exec(c, "DELETE FROM people.work_schedule_cancellation"));
          refused(
              db,
              "work_schedule_cancellation_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule_cancellation"));
          // A cancelled schedule never regains coverage.
          refused(db, "work_schedule_site_coverage_guard", c -> cover(c, a));
          // The projections are never updated or truncated.
          refused(
              db,
              "work_schedule_site_coverage_guard",
              c ->
                  exec(c, "UPDATE people.work_schedule_site_coverage SET first_date = first_date"));
          refused(
              db,
              "work_schedule_slot_coverage_guard",
              c -> exec(c, "UPDATE people.work_schedule_slot_coverage SET start_at = start_at"));
          refused(
              db,
              "work_schedule_site_coverage_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule_site_coverage"));
          refused(
              db,
              "work_schedule_slot_coverage_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule_slot_coverage"));
          // A publication commits only with its exact coverage.
          refused(
              db,
              "work_schedule_coverage_exact",
              c -> publish(c, schedule(org.tenant(), org.otherSite(), DAY, DAY), at(DAY, 8), null));
          refused(
              db,
              "work_schedule_site_coverage_schedule",
              c -> {
                Schedule s = schedule(org.tenant(), org.otherSite(), DAY, DAY).withCounts(1, 0);
                insert(c, s);
                insertSlot(c, slot(s, 1, at(DAY, 8), at(DAY, 16), null));
                exec(c, SITE_COVERAGE, s.tenant(), s.id(), s.site(), DAY, DAY.plusDays(1));
              });
        });
  }

  @Test
  void theChangeWindowClosesExactlyWhenTheEarliestSlotBegins() {
    withDatabase(
        db -> {
          migrate(db.url(), "26");
          Org org = organization(db);
          Schedule a = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(db, c -> operational(c, a, at(DAY, 8), null));
          Timestamp start = ts(at(DAY, 8));
          refused(
              db,
              "work_schedule_change_window",
              c -> {
                cancel(c, a, start);
                release(c, a);
              });
          refused(
              db,
              "work_schedule_change_window",
              c -> {
                cancel(c, a, Timestamp.from(start.toInstant().plusSeconds(3600)));
                release(c, a);
              });
          committed(
              db,
              c -> {
                cancel(c, a, Timestamp.from(start.toInstant().minusNanos(1_000)));
                release(c, a);
              });
        });
  }

  @Test
  void aReplacementKeepsTheSiteAndPeriodChainsWithoutBranchOrCycle() {
    withDatabase(
        db -> {
          migrate(db.url(), "26");
          Org org = organization(db);
          UUID ana = employee(db, org.tenant(), "E-1");
          Schedule a = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(db, c -> operational(c, a, at(DAY, 8), ana));
          Timestamp t1 = Timestamp.from(EARLY.toInstant().plusSeconds(60));
          Timestamp t2 = Timestamp.from(EARLY.toInstant().plusSeconds(120));
          // Wrong shapes: another site, another period, not published at the replacement's
          // instant, a slot not ahead of it, the original itself.
          for (Object[] wrong :
              new Object[][] {
                {schedule(org.tenant(), org.otherSite(), DAY, DAY), t1, at(DAY, 8)},
                {schedule(org.tenant(), org.site(), DAY, DAY.plusDays(1)), t1, at(DAY, 8)},
                {schedule(org.tenant(), org.site(), DAY, DAY), EARLY, at(DAY, 8)}
              }) {
            Schedule b = (Schedule) wrong[0];
            Timestamp published = (Timestamp) wrong[1];
            refused(
                db,
                "work_schedule_replacement_shape",
                c -> {
                  Schedule counted = b.withCounts(1, 0);
                  Object[] row = counted.row();
                  exec(
                      c,
                      SCHEDULE_AT,
                      row[0],
                      row[1],
                      row[2],
                      row[3],
                      row[4],
                      row[5],
                      row[6],
                      row[7],
                      published);
                  insertSlot(
                      c,
                      slot(
                          counted,
                          1,
                          (LocalDateTime) wrong[2],
                          ((LocalDateTime) wrong[2]).plusHours(8),
                          null));
                  exec(c, REPLACE, a.tenant(), a.id(), b.id(), "Nouvelle organisation", t1);
                });
          }
          refused(
              db,
              // The guard runs first (a schedule is never published after itself).
              "work_schedule_replacement_shape",
              c -> exec(c, REPLACE, a.tenant(), a.id(), a.id(), "Nouvelle organisation", t1));
          // A -> B: the original keeps its evidence; only B is operational.
          Schedule b = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(
              db,
              c -> {
                Schedule counted = b.withCounts(1, 1);
                Object[] row = counted.row();
                exec(
                    c,
                    SCHEDULE_AT,
                    row[0],
                    row[1],
                    row[2],
                    row[3],
                    row[4],
                    row[5],
                    row[6],
                    row[7],
                    t1);
                Object[] slot = slot(counted, 1, at(DAY, 9), at(DAY, 17), ana);
                insertSlot(c, slot);
                exec(c, REPLACE, a.tenant(), a.id(), b.id(), "Nouvelle organisation", t1);
                release(c, a);
                cover(c, counted);
                exec(c, SLOT_COVERAGE, b.tenant(), slot[0], b.id(), ana, slot[6], slot[7]);
              });
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_site_coverage"
                          + " WHERE schedule_id IN (?, ?)",
                      a.id(),
                      b.id()))
              .isEqualTo(1);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_slot WHERE schedule_id = ?",
                      a.id()))
              .isEqualTo(1);
          // No branch: A has its one terminal action; B has its one predecessor.
          Schedule other = schedule(org.tenant(), org.site(), DAY, DAY);
          refused(
              db,
              "work_schedule_single_terminal",
              c -> exec(c, REPLACE, a.tenant(), a.id(), other.id(), "Encore", t2));
          // B -> C chains on.
          Schedule cc = schedule(org.tenant(), org.site(), DAY, DAY);
          committed(
              db,
              c -> {
                Schedule counted = cc.withCounts(1, 0);
                Object[] row = counted.row();
                exec(
                    c,
                    SCHEDULE_AT,
                    row[0],
                    row[1],
                    row[2],
                    row[3],
                    row[4],
                    row[5],
                    row[6],
                    row[7],
                    t2);
                insertSlot(c, slot(counted, 1, at(DAY, 10), at(DAY, 18), null));
                exec(c, REPLACE, b.tenant(), b.id(), cc.id(), "Encore une fois", t2);
                release(c, b);
                cover(c, counted);
              });
          // No cycle: C cannot be replaced by A (published earlier), nor A gain a predecessor.
          refused(
              db,
              "work_schedule_replacement_shape",
              c ->
                  exec(
                      c,
                      REPLACE,
                      cc.tenant(),
                      cc.id(),
                      a.id(),
                      "Retour",
                      Timestamp.from(t2.toInstant().plusSeconds(60))));
          refused(
              db,
              "work_schedule_replacement_immutable",
              c -> exec(c, "DELETE FROM people.work_schedule_replacement"));
          refused(
              db,
              "work_schedule_replacement_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule_replacement"));
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_replacement"))
              .isEqualTo(2);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Plans
  // ------------------------------------------------------------------------------------------

  @Test
  void theLookupsUseTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "26");
          UUID tenant = UUID.randomUUID();
          UUID id = UUID.randomUUID();
          assertThat(
                  plan(
                      db,
                      "SELECT 1 FROM people.work_schedule_site_coverage c WHERE c.tenant_id = ?"
                          + " AND c.site_id = ? AND daterange(c.first_date, c.last_date, '[]')"
                          + " && daterange(?, ?, '[]')",
                      tenant,
                      id,
                      DAY,
                      DAY))
              .contains("work_schedule_site_no_overlap");
          assertThat(
                  plan(
                      db,
                      "SELECT 1 FROM people.work_schedule_slot_coverage c WHERE c.tenant_id = ?"
                          + " AND c.employee_id = ? AND tstzrange(c.start_at, c.end_at, '[)')"
                          + " && tstzrange(?, ?, '[)')",
                      tenant,
                      id,
                      EARLY,
                      EARLY))
              .contains("work_schedule_slot_employee_no_overlap");
          assertThat(
                  plan(
                      db,
                      "DELETE FROM people.work_schedule_slot_coverage WHERE tenant_id = ?"
                          + " AND schedule_id = ?",
                      tenant,
                      id))
              .contains("work_schedule_slot_coverage_schedule");
          assertThat(
                  plan(
                      db,
                      "SELECT schedule_id FROM people.work_schedule_replacement"
                          + " WHERE tenant_id = ? AND replacement_schedule_id = ?",
                      tenant,
                      id))
              .contains("work_schedule_replacement_successor");
          assertThat(
                  plan(
                      db,
                      "SELECT 1 FROM people.work_schedule_cancellation WHERE tenant_id = ?"
                          + " AND schedule_id = ?",
                      tenant,
                      id))
              .contains("work_schedule_cancellation_pkey");
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
