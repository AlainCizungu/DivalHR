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
 * MVP-050A V25 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the refusing rollback that restores V24 exactly, immutable schedules and slots, the
 * tenant-consistent site, schedule and employee keys, period, time zone, interval, duration and
 * local-time rules, the label grammar, one published schedule per site and date, no overlapping
 * assigned instants for an employee (overnight and across sites), the deferred completeness rule,
 * and the lookups' indexes on a fresh database.
 */
@IntegrationTest
class WorkScheduleMigrationIntegrationTest {

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
    String database = "sched25_" + UUID.randomUUID().toString().replace("-", "");
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

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV24ExactlyAndRefusesOnceAScheduleExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "24");
          String v24 = signature(db);
          Org org = organization(db);
          migrate(db.url(), "25");
          String v25 = signature(db);
          assertThat(v25)
              .isNotEqualTo(v24)
              .contains("work_schedule")
              .contains("work_schedule_slot_employee_no_overlap");
          int recorded = count(db, "SELECT count(*) FROM flyway_schema_history");
          db.jdbc().execute(resource("db/rollback/V25__rollback.sql"));
          assertThat(signature(db)).isEqualTo(v24);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);

          // One published schedule refuses the rollback; nothing changes.
          db.jdbc().execute(resource("db/migration/V25__work_schedule.sql"));
          assertThat(signature(db)).isEqualTo(v25);
          UUID worker = employee(db, org.tenant(), "E-1");
          committed(
              db,
              c -> publish(c, schedule(org.tenant(), org.site(), DAY, DAY), at(DAY, 8), worker));
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V25__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V25 rollback refused");
          assertThat(signature(db)).isEqualTo(v25);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot")).isEqualTo(1);
          assertThat(count(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(recorded);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Immutability and keys
  // ------------------------------------------------------------------------------------------

  @Test
  void schedulesAndSlotsAreImmutableEvidence() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID worker = employee(db, org.tenant(), "E-1");
          Schedule s = schedule(org.tenant(), org.site(), DAY, DAY.plusDays(2));
          committed(db, c -> publish(c, s, at(DAY, 8), worker));
          refused(
              db,
              "work_schedule_immutable",
              c -> exec(c, "UPDATE people.work_schedule SET last_date = last_date + 1"));
          refused(
              db,
              "work_schedule_immutable",
              c -> exec(c, "UPDATE people.work_schedule SET published_by = 'other'"));
          refused(db, "work_schedule_immutable", c -> exec(c, "DELETE FROM people.work_schedule"));
          refused(
              db,
              "work_schedule_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule CASCADE"));
          refused(
              db,
              "work_schedule_slot_immutable",
              c -> exec(c, "UPDATE people.work_schedule_slot SET assigned_employee_id = NULL"));
          refused(
              db,
              "work_schedule_slot_immutable",
              c -> exec(c, "UPDATE people.work_schedule_slot SET label_fr = 'Autre'"));
          refused(
              db,
              "work_schedule_slot_immutable",
              c -> exec(c, "DELETE FROM people.work_schedule_slot"));
          refused(
              db,
              "work_schedule_slot_no_truncate",
              c -> exec(c, "TRUNCATE people.work_schedule_slot"));
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot")).isEqualTo(1);
        });
  }

  @Test
  void keysAreTenantConsistent() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          Org foreign = organization(db);
          UUID mine = employee(db, org.tenant(), "E-1");
          UUID theirs = employee(db, foreign.tenant(), "E-1");
          // Another tenant's site.
          refused(
              db,
              "work_schedule_site",
              c -> publish(c, schedule(org.tenant(), foreign.site(), DAY, DAY), at(DAY, 8), null));
          refused(
              db,
              "work_schedule_site",
              c ->
                  publish(
                      c, schedule(org.tenant(), UUID.randomUUID(), DAY, DAY), at(DAY, 8), null));
          // Another tenant's employee.
          refused(
              db,
              "work_schedule_slot_employee",
              c -> publish(c, schedule(org.tenant(), org.site(), DAY, DAY), at(DAY, 8), theirs));
          // A slot naming another tenant's schedule under its own tenant.
          Schedule other = schedule(foreign.tenant(), foreign.site(), DAY, DAY);
          committed(db, c -> publish(c, other, at(DAY, 8), null));
          refused(
              db,
              "work_schedule_slot_schedule",
              c -> {
                Object[] row = slot(other, 2, at(DAY, 12), at(DAY, 14), mine);
                row[1] = org.tenant();
                insertSlot(c, row);
              });
          // The legitimate shape commits.
          committed(
              db, c -> publish(c, schedule(org.tenant(), org.site(), DAY, DAY), at(DAY, 8), mine));
        });
  }

  // ------------------------------------------------------------------------------------------
  // Periods, zones, intervals, labels
  // ------------------------------------------------------------------------------------------

  @Test
  void periodsZonesIntervalsAndLabelsAreChecked() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID tenant = org.tenant();
          // Periods: 1 to 31 days, in order.
          refused(
              db,
              "work_schedule_period_valid",
              c ->
                  publish(
                      c, schedule(tenant, org.site(), DAY, DAY.plusDays(31)), at(DAY, 8), null));
          refused(
              db,
              "work_schedule_period_valid",
              c ->
                  publish(
                      c, schedule(tenant, org.site(), DAY, DAY.minusDays(1)), at(DAY, 8), null));
          committed(
              db,
              c ->
                  publish(
                      c, schedule(tenant, org.site(), DAY, DAY.plusDays(30)), at(DAY, 8), null));
          // Zones (R100-1): exactly the site's own time zone, a name PostgreSQL knows, in the IANA
          // shape. The organization's zone, or any other known zone, is not the site's clock.
          UUID goma = site(db, org, "GOMA", "Africa/Lubumbashi");
          refused(
              db,
              "work_schedule_site_timezone",
              c ->
                  insert(
                      c, new Schedule(UUID.randomUUID(), tenant, goma, DAY, DAY, KINSHASA, 1, 0)));
          refused(
              db,
              "work_schedule_site_timezone",
              c -> {
                Schedule s = schedule(tenant, org.otherSite(), DAY, DAY);
                insert(
                    c, new Schedule(s.id(), tenant, s.site(), DAY, DAY, "Africa/Lubumbashi", 1, 0));
              });
          UUID unknownZone = site(db, org, "MARS", "Mars/Olympus");
          refused(
              db,
              "work_schedule_site_timezone",
              c ->
                  insert(
                      c,
                      new Schedule(
                          UUID.randomUUID(), tenant, unknownZone, DAY, DAY, "Mars/Olympus", 1, 0)));
          committed(
              db,
              c ->
                  publish(
                      c,
                      new Schedule(
                          UUID.randomUUID(), tenant, goma, DAY, DAY, "Africa/Lubumbashi", 1, 0),
                      at(DAY, 8),
                      null));
          refused(
              db,
              "work_schedule_site_timezone_format",
              c -> {
                Schedule s = schedule(tenant, org.otherSite(), DAY, DAY);
                insert(c, new Schedule(s.id(), tenant, s.site(), DAY, DAY, "+01:00", 1, 0));
              });
          // Counts.
          refused(
              db,
              "work_schedule_counts_valid",
              c -> insert(c, schedule(tenant, org.otherSite(), DAY, DAY).withCounts(501, 0)));
          refused(
              db,
              "work_schedule_counts_valid",
              c -> insert(c, schedule(tenant, org.otherSite(), DAY, DAY).withCounts(1, 2)));
          // Slots: on a schedule date, minutes, order, at most the next date and 24 hours.
          LocalDate later = DAY.plusDays(40);
          Schedule base = schedule(tenant, org.otherSite(), later, later.plusDays(1));
          refused(
              db,
              "work_schedule_slot_consistent",
              c -> {
                insert(c, base);
                insertSlot(
                    c, slot(base, 1, at(later.plusDays(2), 8), at(later.plusDays(2), 9), null));
              });
          refused(
              db,
              "work_schedule_slot_consistent",
              c -> {
                insert(c, base);
                insertSlot(c, slot(base, 1, at(later.minusDays(1), 22), at(later, 6), null));
              });
          refused(
              db,
              "work_schedule_slot_minutes",
              c -> {
                insert(c, base);
                insertSlot(c, slot(base, 1, at(later, 8).plusSeconds(30), at(later, 9), null));
              });
          refused(
              db,
              "work_schedule_slot_interval_valid",
              c -> {
                insert(c, base);
                insertSlot(c, slot(base, 1, at(later, 9), at(later, 9), null));
              });
          refused(
              db,
              "work_schedule_slot_interval_valid",
              c -> {
                insert(c, base);
                insertSlot(
                    c, slot(base, 1, at(later, 8), at(later.plusDays(1), 8).plusMinutes(1), null));
              });
          refused(
              db,
              "work_schedule_slot_interval_valid",
              c -> {
                insert(c, base);
                insertSlot(c, slot(base, 1, at(later, 1), at(later.plusDays(2), 0), null));
              });
          refused(
              db,
              "work_schedule_slot_position_valid",
              c -> {
                insert(c, base);
                insertSlot(c, slot(base, 0, at(later, 8), at(later, 9), null));
              });
          // Instants are the local times in the schedule's zone.
          refused(
              db,
              "work_schedule_slot_consistent",
              c -> {
                insert(c, base);
                Object[] row = slot(base, 1, at(later, 8), at(later, 9), null);
                row[6] = Timestamp.from(at(later, 8).atZone(ZoneId.of("UTC")).toInstant());
                insertSlot(c, row);
              });
          // Labels: the closed grammar in both languages.
          for (String bad :
              new String[] {
                "x",
                " Lead",
                "Lead ",
                "two  spaces",
                "<b>Lead</b>",
                "a@b.cd",
                "www.example.com",
                "Go: now",
                "Tab\tlabel",
                "x".repeat(61)
              }) {
            refused(
                db,
                "work_schedule_slot_label_en_valid",
                c -> {
                  insert(c, base);
                  Object[] row = slot(base, 1, at(later, 8), at(later, 9), null);
                  row[8] = bad;
                  insertSlot(c, row);
                });
            refused(
                db,
                "work_schedule_slot_label_fr_valid",
                c -> {
                  insert(c, base);
                  Object[] row = slot(base, 1, at(later, 8), at(later, 9), null);
                  row[9] = bad;
                  insertSlot(c, row);
                });
          }
          // An overnight slot ending on the next date commits, with accents and punctuation.
          committed(
              db,
              c -> {
                insert(c, base);
                Object[] row =
                    slot(base, 1, at(later.plusDays(1), 22), at(later.plusDays(2), 6), null);
                row[8] = "Night desk (lead) n° 2";
                row[9] = "Agent d'accueil - équipe de nuit";
                insertSlot(c, row);
              });
        });
  }

  @Test
  void localTimesThatDoNotExistOrRepeatAreRefused() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          ZoneId paris = ZoneId.of("Europe/Paris");
          UUID parisSite = site(db, org, "PARIS", "Europe/Paris");
          LocalDate spring = LocalDate.of(2027, 3, 28);
          LocalDate autumn = LocalDate.of(2027, 10, 31);
          Schedule s =
              new Schedule(
                  UUID.randomUUID(), org.tenant(), parisSite, spring, spring, "Europe/Paris", 1, 0);
          // 02:30 does not exist on the spring date: no instant maps back to it.
          refused(
              db,
              "work_schedule_slot_consistent",
              c -> {
                insert(c, s);
                LocalDateTime gap = spring.atTime(2, 30);
                Object[] row = slot(s, 1, gap, spring.atTime(8, 0), null);
                row[6] = Timestamp.from(gap.atZone(paris).toInstant());
                insertSlot(c, row);
              });
          // 02:30 happens twice on the autumn date: refused at either instant.
          Schedule fall =
              new Schedule(
                  UUID.randomUUID(), org.tenant(), parisSite, autumn, autumn, "Europe/Paris", 1, 0);
          for (Instant instant :
              new Instant[] {
                autumn.atTime(2, 30).atZone(paris).withEarlierOffsetAtOverlap().toInstant(),
                autumn.atTime(2, 30).atZone(paris).withLaterOffsetAtOverlap().toInstant()
              }) {
            refused(
                db,
                "work_schedule_slot_consistent",
                c -> {
                  insert(c, fall);
                  Object[] row = slot(fall, 1, autumn.atTime(2, 30), autumn.atTime(9, 0), null);
                  row[6] = Timestamp.from(instant);
                  insertSlot(c, row);
                });
          }
          // Around the transitions, existing unique local times commit.
          committed(
              db,
              c -> {
                insert(c, fall);
                insertSlot(c, slot(fall, 1, autumn.atTime(3, 0), autumn.atTime(11, 0), null));
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Exclusions
  // ------------------------------------------------------------------------------------------

  @Test
  void oneScheduleCoversASiteOnAnyDate() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID tenant = org.tenant();
          committed(
              db,
              c ->
                  publish(c, schedule(tenant, org.site(), DAY, DAY.plusDays(6)), at(DAY, 8), null));
          // Overlapping by one day, inside, or covering it: refused.
          for (LocalDate[] period :
              new LocalDate[][] {
                {DAY.plusDays(6), DAY.plusDays(10)},
                {DAY.minusDays(3), DAY},
                {DAY.plusDays(2), DAY.plusDays(3)},
                {DAY.minusDays(1), DAY.plusDays(8)}
              }) {
            refused(
                db,
                "work_schedule_site_no_overlap",
                c ->
                    publish(
                        c,
                        schedule(tenant, org.site(), period[0], period[1]),
                        at(period[0], 8),
                        null));
          }
          // Adjacent periods and another site commit.
          committed(
              db,
              c -> {
                publish(
                    c,
                    schedule(tenant, org.site(), DAY.plusDays(7), DAY.plusDays(9)),
                    at(DAY.plusDays(7), 8),
                    null);
                publish(
                    c,
                    schedule(tenant, org.site(), DAY.minusDays(2), DAY.minusDays(1)),
                    at(DAY.minusDays(2), 8),
                    null);
                publish(
                    c, schedule(tenant, org.otherSite(), DAY, DAY.plusDays(6)), at(DAY, 8), null);
              });
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule")).isEqualTo(4);
        });
  }

  @Test
  void anEmployeeHasNoOverlappingAssignedInstantsAnywhere() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID tenant = org.tenant();
          UUID worker = employee(db, tenant, "E-1");
          UUID colleague = employee(db, tenant, "E-2");
          // Within one publication: two overlapping slots for the worker.
          refused(
              db,
              "work_schedule_slot_employee_no_overlap",
              c -> {
                Schedule s = schedule(tenant, org.site(), DAY, DAY).withCounts(2, 2);
                insert(c, s);
                insertSlot(c, slot(s, 1, at(DAY, 8), at(DAY, 16), worker));
                insertSlot(c, slot(s, 2, at(DAY, 15), at(DAY, 20), worker));
              });
          // An overnight shift at one site, then an early shift at another site the next morning.
          committed(
              db,
              c -> {
                Schedule night = schedule(tenant, org.site(), DAY, DAY).withCounts(2, 1);
                insert(c, night);
                insertSlot(c, slot(night, 1, at(DAY, 22), at(DAY.plusDays(1), 6), worker));
                // Unassigned slots may overlap freely: they are separate uncovered positions.
                insertSlot(c, slot(night, 2, at(DAY, 22), at(DAY.plusDays(1), 6), null));
              });
          refused(
              db,
              "work_schedule_slot_employee_no_overlap",
              c ->
                  publish(
                      c,
                      schedule(tenant, org.otherSite(), DAY.plusDays(1), DAY.plusDays(1)),
                      at(DAY.plusDays(1), 5),
                      worker));
          // Back to back (half-open instants) and another employee commit.
          committed(
              db,
              c -> {
                Schedule morning =
                    schedule(tenant, org.otherSite(), DAY.plusDays(1), DAY.plusDays(1))
                        .withCounts(2, 2);
                insert(c, morning);
                insertSlot(
                    c, slot(morning, 1, at(DAY.plusDays(1), 6), at(DAY.plusDays(1), 12), worker));
                insertSlot(
                    c,
                    slot(morning, 2, at(DAY.plusDays(1), 5), at(DAY.plusDays(1), 13), colleague));
              });
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM people.work_schedule_slot WHERE assigned_employee_id ="
                          + " ?",
                      worker))
              .isEqualTo(2);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Completeness
  // ------------------------------------------------------------------------------------------

  @Test
  void aScheduleCommitsOnlyWithExactlyItsStatedSlots() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID tenant = org.tenant();
          UUID worker = employee(db, tenant, "E-1");
          // No slot at all.
          refused(
              db, "work_schedule_complete", c -> insert(c, schedule(tenant, org.site(), DAY, DAY)));
          // Fewer or more slots than stated.
          refused(
              db,
              "work_schedule_complete",
              c -> {
                Schedule s = schedule(tenant, org.site(), DAY, DAY).withCounts(2, 0);
                insert(c, s);
                insertSlot(c, slot(s, 1, at(DAY, 8), at(DAY, 9), null));
              });
          refused(
              db,
              "work_schedule_complete",
              c -> {
                Schedule s = schedule(tenant, org.site(), DAY, DAY);
                insert(c, s);
                insertSlot(c, slot(s, 1, at(DAY, 8), at(DAY, 9), null));
                insertSlot(c, slot(s, 2, at(DAY, 9), at(DAY, 10), null));
              });
          // A wrong assigned count.
          refused(
              db,
              "work_schedule_complete",
              c -> {
                Schedule s = schedule(tenant, org.site(), DAY, DAY).withCounts(1, 0);
                insert(c, s);
                insertSlot(c, slot(s, 1, at(DAY, 8), at(DAY, 9), worker));
              });
          // Positions beyond the count.
          refused(
              db,
              "work_schedule_complete",
              c -> {
                Schedule s = schedule(tenant, org.site(), DAY, DAY);
                insert(c, s);
                insertSlot(c, slot(s, 3, at(DAY, 8), at(DAY, 9), null));
              });
          // A slot added to a committed schedule in a later transaction.
          Schedule done = schedule(tenant, org.site(), DAY, DAY);
          committed(db, c -> publish(c, done, at(DAY, 8), null));
          refused(
              db,
              "work_schedule_complete",
              c -> insertSlot(c, slot(done.withCounts(1, 0), 2, at(DAY, 18), at(DAY, 19), null)));
          assertThat(count(db, "SELECT count(*) FROM people.work_schedule_slot")).isEqualTo(1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Indexes
  // ------------------------------------------------------------------------------------------

  @Test
  void theLookupsReadTheirIndexes() {
    withDatabase(
        db -> {
          migrate(db.url(), "25");
          Org org = organization(db);
          UUID tenant = org.tenant();
          UUID someone = UUID.randomUUID();
          Timestamp now = Timestamp.from(Instant.now());
          // The site overlap check.
          assertThat(
                  plan(
                      db,
                      "SELECT s.id FROM people.work_schedule s WHERE s.tenant_id = ?"
                          + " AND s.site_id = ? AND daterange(s.first_date, s.last_date, '[]')"
                          + " && daterange(?, ?, '[]')",
                      tenant,
                      org.site(),
                      DAY,
                      DAY.plusDays(3)))
              .contains("work_schedule_site_no_overlap");
          // The administrators' list, newest period first, without a sort.
          assertThat(
                  plan(
                      db,
                      "SELECT s.id FROM people.work_schedule s WHERE s.tenant_id = ?"
                          + " AND (s.first_date, s.id) < (?, ?)"
                          + " ORDER BY s.first_date DESC, s.id DESC LIMIT 26",
                      tenant,
                      DAY,
                      someone))
              .containsPattern("Index (Only )?Scan using work_schedule_list_order")
              .doesNotContain("Sort");
          // A schedule's slots in publication order.
          assertThat(
                  plan(
                      db,
                      "SELECT l.id FROM people.work_schedule_slot l WHERE l.tenant_id = ?"
                          + " AND l.schedule_id = ? ORDER BY l.position",
                      tenant,
                      someone))
              .contains("work_schedule_slot_position")
              .doesNotContain("Sort");
          // An employee's conflicting assigned slots.
          assertThat(
                  plan(
                      db,
                      "SELECT l.id FROM people.work_schedule_slot l WHERE l.tenant_id = ?"
                          + " AND l.assigned_employee_id = ? AND tstzrange(l.start_at, l.end_at,"
                          + " '[)') && tstzrange(?, ?, '[)')",
                      tenant,
                      someone,
                      now,
                      now))
              .containsPattern("work_schedule_slot_employee_(no_overlap|start)");
          // The employee's own shifts in start order.
          assertThat(
                  plan(
                      db,
                      "SELECT l.id FROM people.work_schedule_slot l WHERE l.tenant_id = ?"
                          + " AND l.assigned_employee_id = ? AND (l.start_at, l.id) > (?, ?)"
                          + " ORDER BY l.start_at, l.id LIMIT 26",
                      tenant,
                      someone,
                      now,
                      someone))
              .containsPattern("Index (Only )?Scan using work_schedule_slot_employee_start")
              .doesNotContain("Sort");
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM pg_indexes WHERE schemaname = 'people'"
                          + " AND tablename = 'work_schedule'"))
              .isEqualTo(4);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM pg_indexes WHERE schemaname = 'people'"
                          + " AND tablename = 'work_schedule_slot'"))
              .isEqualTo(4);
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
