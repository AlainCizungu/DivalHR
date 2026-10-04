package com.divalhr.core.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SearchKeyGolden;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * MVP-021 V14, V14.1 and V14.2 (H2, H3, H4, H6, H16; M21-3, M21-4, M21-5), directly against
 * PostgreSQL, bypassing the application: the backfill, the guarded rollback, both {@code
 * btree_gist} environments, the immutable history and the manager-cycle trigger's own lock.
 */
@IntegrationTest
class EmploymentHistoryMigrationIntegrationTest {

  /** The people schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, '') || ':' || is_generated,
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ',' ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'people'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname) FROM pg_constraint
                              WHERE connamespace = 'people'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(c.relname || '.' || t.tgname, ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'people'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname, ',' ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'people'::regnamespace), '')
      """;

  private static final LocalDate START = LocalDate.of(2026, 3, 1);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(boolean provisionBtreeGist, Consumer<Db> body) {
    String database = "people14_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      JdbcTemplate db =
          new JdbcTemplate(
              new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
      if (provisionBtreeGist) {
        db.execute("CREATE EXTENSION btree_gist");
      }
      body.accept(new Db(url, db));
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

  private static String rollback() {
    try {
      return new ClassPathResource("db/rollback/V14__rollback.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String signature(Db db) {
    return db.jdbc().queryForObject(SIGNATURE, String.class);
  }

  private static boolean btreeGist(Db db) {
    return Boolean.TRUE.equals(
        db.jdbc()
            .queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist')",
                Boolean.class));
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
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

  // ------------------------------------------------------------------------------------------
  // Backfill and rollback (H2, H16, M21-3)
  // ------------------------------------------------------------------------------------------

  /** A V13 employment and its placement columns. */
  private record V13Employment(UUID employment, UUID employee, List<UUID> placement) {}

  private static V13Employment v13Employment(
      JdbcTemplate db,
      UUID tenant,
      String number,
      SearchKeyGolden.Vector names,
      UUID department,
      UUID costCenter,
      UUID team,
      long version) {
    UUID employee = UUID.randomUUID();
    UUID employment = UUID.randomUUID();
    UUID legalEntity = UUID.randomUUID();
    UUID site = UUID.randomUUID();
    db.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " created_at, created_by) VALUES (?, ?, ?, ?, ?, now(), 'import')",
        employee,
        tenant,
        number,
        names.givenNames(),
        names.familyName());
    db.update(
        "INSERT INTO people.employment (id, tenant_id, employee_id, legal_entity_id, site_id,"
            + " department_id, cost_center_id, team_id, effective_from, created_at, created_by,"
            + " version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 'import', ?)",
        employment,
        tenant,
        employee,
        legalEntity,
        site,
        department,
        costCenter,
        team,
        START,
        version);
    List<UUID> placement = new ArrayList<>();
    placement.add(legalEntity);
    placement.add(site);
    placement.add(department);
    placement.add(costCenter);
    placement.add(team);
    return new V13Employment(employment, employee, placement);
  }

  private static List<UUID> v13Placement(JdbcTemplate db, UUID employment) {
    Map<String, Object> row =
        db.queryForMap(
            "SELECT legal_entity_id, site_id, department_id, cost_center_id, team_id"
                + " FROM people.employment WHERE id = ?",
            employment);
    List<UUID> placement = new ArrayList<>();
    for (String column :
        List.of("legal_entity_id", "site_id", "department_id", "cost_center_id", "team_id")) {
      placement.add((UUID) row.get(column));
    }
    return placement;
  }

  private static List<V13Employment> seedV13(JdbcTemplate db) {
    UUID tenant = organization(db);
    List<SearchKeyGolden.Vector> vectors = SearchKeyGolden.vectors();
    List<V13Employment> seeded = new ArrayList<>();
    for (int i = 0; i < vectors.size(); i++) {
      UUID department = i % 3 == 0 ? UUID.randomUUID() : null;
      UUID costCenter = i % 3 == 1 ? UUID.randomUUID() : null;
      UUID team = i % 3 != 2 && i % 2 == 0 ? UUID.randomUUID() : null;
      seeded.add(
          v13Employment(
              db, tenant, "E-" + (100 + i), vectors.get(i), department, costCenter, team, i));
    }
    return seeded;
  }

  @Test
  void theBackfillRecordsEachHireAndTheRollbackRestoresV13Exactly() {
    withDatabase(
        false,
        db -> {
          migrate(db.url(), "13");
          String v13 = signature(db);
          List<V13Employment> seeded = seedV13(db.jdbc());
          assertThat(btreeGist(db)).isFalse();

          migrate(db.url(), "latest");
          String v14 = signature(db);
          assertThat(v14).isNotEqualTo(v13).contains("employment_assignment,employment_change");
          assertThat(btreeGist(db)).as("provisioned by V14").isTrue();
          int recorded = history(db);

          // One HIRE per employment with its version, and one open placement row of its values.
          for (V13Employment e : seeded) {
            Map<String, Object> hire =
                db.jdbc()
                    .queryForMap(
                        "SELECT c.id, c.type, c.effective_from, c.kinds::text AS kinds, c.timing,"
                            + " c.reason_code, c.version_after, c.state, m.version"
                            + " FROM people.employment_change c JOIN people.employment m"
                            + " ON m.id = c.employment_id WHERE c.employment_id = ?",
                        e.employment());
            assertThat(hire.get("type")).isEqualTo("HIRE");
            assertThat(hire.get("kinds")).isEqualTo("{PLACEMENT}");
            assertThat(hire.get("timing")).isNull();
            assertThat(hire.get("reason_code")).isNull();
            assertThat(hire.get("state")).isEqualTo("ACTIVE");
            assertThat(hire.get("version_after")).isEqualTo(hire.get("version"));
            Map<String, Object> row =
                db.jdbc()
                    .queryForMap(
                        "SELECT * FROM people.employment_assignment WHERE employment_id = ?",
                        e.employment());
            assertThat(row.get("kind")).isEqualTo("PLACEMENT");
            assertThat(row.get("effective_from").toString()).isEqualTo(START.toString());
            assertThat(row.get("effective_to")).isNull();
            assertThat(row.get("created_by_change_id")).isEqualTo(hire.get("id"));
            assertThat(row.get("origin_change_id")).isEqualTo(hire.get("id"));
            assertThat(row.get("superseded_by_change_id")).isNull();
            List<UUID> placement = new ArrayList<>();
            for (String column :
                List.of(
                    "legal_entity_id", "site_id", "department_id", "cost_center_id", "team_id")) {
              placement.add((UUID) row.get(column));
            }
            assertThat(placement).isEqualTo(e.placement());
          }
          // V14_1 fills the search key with the application's normalizer (golden vectors).
          List<SearchKeyGolden.Vector> vectors = SearchKeyGolden.vectors();
          for (int i = 0; i < vectors.size(); i++) {
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT search_key FROM people.employee WHERE id = ?",
                            String.class,
                            seeded.get(i).employee()))
                .isEqualTo(vectors.get(i).key());
          }

          // Rollback: V13 exactly, its data restored; Flyway history and btree_gist untouched.
          db.jdbc().execute(rollback());
          assertThat(signature(db)).isEqualTo(v13);
          assertThat(history(db)).isEqualTo(recorded);
          assertThat(btreeGist(db)).isTrue();
          for (V13Employment e : seeded) {
            assertThat(v13Placement(db.jdbc(), e.employment())).isEqualTo(e.placement());
          }
        });
  }

  @Test
  void theRollbackRefusesOnceHistoryBeyondTheHireExists() {
    withDatabase(
        true,
        db -> {
          migrate(db.url(), "13");
          List<V13Employment> seeded = seedV13(db.jdbc());
          migrate(db.url(), "latest");
          assertThat(btreeGist(db)).as("pre-existing").isTrue();
          String v14 = signature(db);
          V13Employment e = seeded.get(0);
          UUID change = UUID.randomUUID();
          db.jdbc()
              .execute(
                  "BEGIN;"
                      + " INSERT INTO people.employment_change (id, tenant_id, employee_id,"
                      + " employment_id, type, effective_from, kinds, timing, recorded_at,"
                      + " recorded_by, version_after) SELECT '"
                      + change
                      + "', tenant_id, employee_id, id, 'CHANGE', DATE '2026-06-01',"
                      + " ARRAY['CONTRACT'], 'SCHEDULED', now(), 'test', version + 1"
                      + " FROM people.employment WHERE id = '"
                      + e.employment()
                      + "'; INSERT INTO people.employment_assignment (id, tenant_id,"
                      + " employee_id, employment_id, kind, effective_from, contract_code,"
                      + " created_by_change_id, origin_change_id) SELECT gen_random_uuid(),"
                      + " tenant_id, employee_id, id, 'CONTRACT', DATE '2026-06-01', 'PERMANENT',"
                      + " '"
                      + change
                      + "', '"
                      + change
                      + "' FROM people.employment WHERE id = '"
                      + e.employment()
                      + "'; COMMIT;");
          assertThatThrownBy(() -> db.jdbc().execute(rollback()))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V14 rollback refused");
          assertThat(signature(db)).isEqualTo(v14);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM people.employment_assignment", Integer.class))
              .isEqualTo(seeded.size() + 1);
        });
  }

  // ------------------------------------------------------------------------------------------
  // Direct-database guarantees (M21-4, M21-5)
  // ------------------------------------------------------------------------------------------

  /** A V14 employee with its hire (committed). */
  private record Hired(UUID tenant, UUID employee, UUID employment, UUID hire, UUID placement) {}

  private static Hired hire(Connection c, UUID tenant, String number) throws SQLException {
    Hired h =
        new Hired(
            tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
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
        h.hire(),
        tenant,
        h.employee(),
        h.employment(),
        START);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'PLACEMENT', ?, ?, ?, ?, ?)",
        h.placement(),
        tenant,
        h.employee(),
        h.employment(),
        START,
        UUID.randomUUID(),
        UUID.randomUUID(),
        h.hire(),
        h.hire());
    return h;
  }

  private static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.execute();
    }
  }

  private Connection connect(Db db) throws SQLException {
    Connection c =
        DriverManager.getConnection(db.url(), postgres.getUsername(), postgres.getPassword());
    c.setAutoCommit(false);
    return c;
  }

  /** Runs work in its own transaction and expects it (statement or commit) to fail by name. */
  private void refused(Db db, String constraint, SqlWork work) throws Exception {
    try (Connection c = connect(db)) {
      assertThatThrownBy(
              () -> {
                work.run(c);
                c.commit();
              })
          .as(constraint)
          .isInstanceOfSatisfying(
              PSQLException.class, e -> assertThat(constraint(e)).isEqualTo(constraint));
      c.rollback();
    }
  }

  private static String constraint(PSQLException failure) {
    org.postgresql.util.ServerErrorMessage message = failure.getServerErrorMessage();
    return message == null ? "unnamed" : String.valueOf(message.getConstraint());
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private static UUID change(Connection c, Hired h, String type, String kinds, LocalDate from)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, reason_code, timing, recorded_at, recorded_by,"
            + " version_after) VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, 'SCHEDULED', now(), 'test',"
            + " 1)",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        type,
        from,
        kinds,
        "CORRECTION".equals(type) ? "DATA_ENTRY_ERROR" : null);
    return id;
  }

  private static UUID placementRow(
      Connection c, Hired h, LocalDate from, LocalDate to, UUID createdBy, UUID origin)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, effective_to, legal_entity_id, site_id,"
            + " created_by_change_id, origin_change_id) SELECT ?, tenant_id, employee_id,"
            + " employment_id, 'PLACEMENT', ?, ?, legal_entity_id, site_id, ?, ?"
            + " FROM people.employment_assignment WHERE id = ?",
        id,
        from,
        to,
        createdBy,
        origin,
        h.placement());
    return id;
  }

  private static void supersede(Connection c, UUID row, UUID change) throws SQLException {
    exec(
        c,
        "UPDATE people.employment_assignment SET superseded_by_change_id = ?, superseded_at ="
            + " now() WHERE id = ?",
        change,
        row);
  }

  @Test
  void historyIsImmutableAndEveryWriteHasItsExactShape() throws Exception {
    withDatabase(
        false,
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired h;
            Hired other;
            try (Connection c = connect(db)) {
              h = hire(c, tenant, "E-1");
              other = hire(c, tenant, "E-2");
              c.commit();
            }
            String immutable = "employment_assignment_immutable";
            LocalDate june = LocalDate.of(2026, 6, 1);

            // Business values and dates never change.
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c,
                        "UPDATE people.employment_assignment SET site_id = ? WHERE id = ?",
                        UUID.randomUUID(),
                        h.placement()));
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c,
                        "UPDATE people.employment_assignment SET effective_to = ? WHERE id = ?",
                        june,
                        h.placement()));
            // The supersession pair points to a change of the same employee and employment.
            refused(
                db,
                "employment_assignment_superseded_by",
                c -> supersede(c, h.placement(), other.hire()));
            // It moves once, from null to set: never to another change, never back.
            refused(
                db,
                immutable,
                c -> {
                  UUID first = change(c, h, "CHANGE", "{PLACEMENT}", june);
                  UUID second = change(c, h, "CHANGE", "{PLACEMENT}", june.plusDays(1));
                  supersede(c, h.placement(), first);
                  supersede(c, h.placement(), second);
                });
            refused(
                db,
                immutable,
                c -> {
                  supersede(c, h.placement(), change(c, h, "CHANGE", "{PLACEMENT}", june));
                  exec(
                      c,
                      "UPDATE people.employment_assignment SET superseded_by_change_id = NULL,"
                          + " superseded_at = NULL WHERE id = ?",
                      h.placement());
                });
            // created_by ownership: a row is written by a change of its own employment.
            refused(
                db,
                "employment_assignment_created_by",
                c ->
                    exec(
                        c,
                        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                            + " employment_id, kind, effective_from, contract_code,"
                            + " created_by_change_id, origin_change_id) VALUES (?, ?, ?, ?,"
                            + " 'CONTRACT', ?, 'PERMANENT', ?, ?)",
                        UUID.randomUUID(),
                        tenant,
                        h.employee(),
                        h.employment(),
                        june,
                        other.hire(),
                        h.hire()));
            // Nothing is deleted or truncated.
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c, "DELETE FROM people.employment_assignment WHERE id = ?", h.placement()));
            refused(
                db,
                "employment_change_immutable",
                c -> exec(c, "DELETE FROM people.employment_change WHERE id = ?", h.hire()));
            for (String table : List.of("employment_assignment", "employment_change")) {
              refused(
                  db,
                  "employment_history_no_truncate",
                  c -> {
                    try (Statement s = c.createStatement()) {
                      s.execute("TRUNCATE people." + table + " CASCADE");
                    }
                  });
            }
            // A change never changes, except ACTIVE -> CANCELLED by a recorded cancellation.
            refused(
                db,
                "employment_change_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.employment_change SET effective_from = ? WHERE id = ?",
                        june,
                        h.hire()));
            refused(
                db,
                "employment_change_immutable",
                c -> {
                  UUID scheduled = change(c, h, "CHANGE", "{PLACEMENT}", june);
                  exec(
                      c,
                      "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?",
                      scheduled);
                });

            // A correction supersedes exactly one row and creates one with identical dates and
            // itself as origin.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  UUID correction = change(c, h, "CORRECTION", "{PLACEMENT}", START);
                  supersede(c, h.placement(), correction);
                  placementRow(c, h, START, june.minusDays(1), correction, correction);
                  placementRow(c, h, june, null, correction, correction);
                });
            refused(
                db,
                "employment_change_shape",
                c -> {
                  UUID correction = change(c, h, "CORRECTION", "{PLACEMENT}", START);
                  supersede(c, h.placement(), correction);
                  placementRow(c, h, START, null, correction, h.hire());
                });
            // Gap-free placement.
            refused(
                db,
                "employment_placement_coverage",
                c -> {
                  UUID scheduled = change(c, h, "CHANGE", "{PLACEMENT}", june);
                  supersede(c, h.placement(), scheduled);
                  placementRow(c, h, june, null, scheduled, scheduled);
                });

            // A valid scheduled change, then cancellation lineage.
            UUID scheduled;
            UUID copy;
            UUID value;
            try (Connection c = connect(db)) {
              scheduled = change(c, h, "CHANGE", "{PLACEMENT}", june);
              supersede(c, h.placement(), scheduled);
              copy = placementRow(c, h, START, june.minusDays(1), scheduled, h.hire());
              value = placementRow(c, h, june, null, scheduled, scheduled);
              c.commit();
            }
            UUID copied = copy;
            UUID changed = value;
            // The restored row must name the row the cancelled change replaced.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  UUID cancellation = cancellation(c, h, scheduled, june);
                  exec(
                      c,
                      "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?",
                      scheduled);
                  supersede(c, copied, cancellation);
                  supersede(c, changed, cancellation);
                  restored(c, h, cancellation, copied);
                });
            try (Connection c = connect(db)) {
              UUID cancellation = cancellation(c, h, scheduled, june);
              exec(
                  c,
                  "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?",
                  scheduled);
              supersede(c, copied, cancellation);
              supersede(c, changed, cancellation);
              restored(c, h, cancellation, h.placement());
              c.commit();
            }
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT count(*) FROM people.employment_assignment"
                                + " WHERE employment_id = ?",
                            Integer.class,
                            h.employment()))
                .as("every row is kept")
                .isEqualTo(4);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /**
   * R21-1: a committed change is revalidated whenever a later transaction writes or replaces a row
   * in its name, and a change or cancellation must touch exactly its kinds.
   */
  @Test
  void laterWritesInTheNameOfACommittedChangeAreRevalidated() throws Exception {
    withDatabase(
        false,
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired h;
            try (Connection c = connect(db)) {
              h = hire(c, tenant, "E-1");
              c.commit();
            }
            String shape = "employment_change_shape";
            LocalDate june = LocalDate.of(2026, 6, 1);
            LocalDate september = LocalDate.of(2026, 9, 1);
            LocalDate november = LocalDate.of(2026, 11, 1);

            // A committed placement change.
            UUID moved;
            try (Connection c = connect(db)) {
              moved = change(c, h, "CHANGE", "{PLACEMENT}", june);
              supersede(c, h.placement(), moved);
              placementRow(c, h, START, june.minusDays(1), moved, h.hire());
              placementRow(c, h, june, null, moved, moved);
              c.commit();
            }
            // A committed contract change (a gap insertion).
            UUID contracted;
            UUID contractRow;
            try (Connection c = connect(db)) {
              contracted = change(c, h, "CHANGE", "{CONTRACT}", september);
              contractRow =
                  coded(
                      c, h, "CONTRACT", "PERMANENT", september, null, contracted, contracted, null);
              c.commit();
            }

            // 1. An extra row written in the name of the committed placement change.
            refused(
                db,
                shape,
                c -> coded(c, h, "COMPENSATION", "MONTHLY", june, null, moved, moved, null));
            // 2. A supersession in the name of the committed placement change.
            refused(db, shape, c -> supersede(c, contractRow, moved));
            // 3. A change whose kinds[] names a kind it does not touch.
            refused(
                db,
                shape,
                c -> {
                  UUID extra = change(c, h, "CHANGE", "{COMPENSATION,MANAGER}", november);
                  coded(c, h, "COMPENSATION", "HOURLY", november, null, extra, extra, null);
                });

            // Positive control: a multi-kind change (contract split, compensation gap insertion).
            UUID both;
            UUID contractCopy;
            UUID contractValue;
            UUID compensationValue;
            try (Connection c = connect(db)) {
              both = change(c, h, "CHANGE", "{CONTRACT,COMPENSATION}", november);
              supersede(c, contractRow, both);
              contractCopy =
                  coded(
                      c,
                      h,
                      "CONTRACT",
                      "PERMANENT",
                      september,
                      november.minusDays(1),
                      both,
                      contracted,
                      null);
              contractValue =
                  coded(c, h, "CONTRACT", "FIXED_TERM", november, null, both, both, null);
              compensationValue =
                  coded(c, h, "COMPENSATION", "MONTHLY", november, null, both, both, null);
              c.commit();
            }
            UUID multi = both;
            UUID copied = contractCopy;
            UUID valued = contractValue;
            UUID paid = compensationValue;
            UUID replaced = contractRow;
            UUID original = contracted;

            // 4. A partial cancellation of the multi-kind change (the compensation stays).
            refused(
                db,
                shape,
                c -> {
                  UUID cancel = cancellationOf(c, h, multi, november, "{CONTRACT,COMPENSATION}");
                  exec(
                      c,
                      "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?",
                      multi);
                  supersede(c, copied, cancel);
                  supersede(c, valued, cancel);
                  coded(c, h, "CONTRACT", "PERMANENT", september, null, cancel, original, replaced);
                });
            // Positive control: the full cancellation commits.
            try (Connection c = connect(db)) {
              UUID cancel = cancellationOf(c, h, multi, november, "{CONTRACT,COMPENSATION}");
              exec(
                  c, "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?", multi);
              supersede(c, copied, cancel);
              supersede(c, valued, cancel);
              supersede(c, paid, cancel);
              coded(c, h, "CONTRACT", "PERMANENT", september, null, cancel, original, replaced);
              c.commit();
            }
            // ... and it cannot be extended afterwards either.
            UUID cancelled =
                db.jdbc()
                    .queryForObject(
                        "SELECT id FROM people.employment_change WHERE cancels_change_id = ?",
                        UUID.class,
                        multi);
            refused(
                db,
                shape,
                c -> coded(c, h, "MANAGER", null, november, null, cancelled, cancelled, null));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** A CONTRACT, COMPENSATION or MANAGER row (a MANAGER row reports to a new employee). */
  private static UUID coded(
      Connection c,
      Hired h,
      String kind,
      String code,
      LocalDate from,
      LocalDate to,
      UUID createdBy,
      UUID origin,
      UUID restores)
      throws SQLException {
    UUID id = UUID.randomUUID();
    UUID manager = null;
    if ("MANAGER".equals(kind)) {
      manager =
          hire(
                  c,
                  h.tenant(),
                  "E-M-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT))
              .employee();
    }
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, effective_to, contract_code, compensation_basis_code,"
            + " manager_employee_id, created_by_change_id, origin_change_id,"
            + " restores_assignment_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        kind,
        from,
        to,
        "CONTRACT".equals(kind) ? code : null,
        "COMPENSATION".equals(kind) ? code : null,
        manager,
        createdBy,
        origin,
        restores);
    return id;
  }

  private static UUID cancellationOf(
      Connection c, Hired h, UUID cancelled, LocalDate from, String kinds) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, cancels_change_id, recorded_at, recorded_by,"
            + " version_after) VALUES (?, ?, ?, ?, 'CANCELLATION', ?, ?::text[], 'SCHEDULED', ?,"
            + " now(), 'test', 9)",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        from,
        kinds,
        cancelled);
    return id;
  }

  private static UUID cancellation(Connection c, Hired h, UUID cancelled, LocalDate from)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, cancels_change_id, recorded_at, recorded_by,"
            + " version_after) VALUES (?, ?, ?, ?, 'CANCELLATION', ?, ARRAY['PLACEMENT'],"
            + " 'SCHEDULED', ?, now(), 'test', 2)",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        from,
        cancelled);
    return id;
  }

  private static void restored(Connection c, Hired h, UUID cancellation, UUID restores)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, created_by_change_id,"
            + " origin_change_id, restores_assignment_id) SELECT gen_random_uuid(), tenant_id,"
            + " employee_id, employment_id, 'PLACEMENT', effective_from, legal_entity_id,"
            + " site_id, ?, origin_change_id, ? FROM people.employment_assignment WHERE id = ?",
        cancellation,
        restores,
        h.placement());
  }

  /**
   * M21-4: two transactions bypassing the service close a loop (A reports to B, B to A). The
   * deferred trigger takes the tenant's manager-graph lock itself, so at most one commits.
   */
  @Test
  void concurrentManagerAssignmentsNeverCloseALoop() throws Exception {
    withDatabase(
        true,
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired a;
            Hired b;
            try (Connection c = connect(db)) {
              a = hire(c, tenant, "E-A");
              b = hire(c, tenant, "E-B");
              c.commit();
            }
            for (int round = 0; round < 3; round++) {
              LocalDate from = LocalDate.of(2026, 6, 1).plusDays(round * 10L);
              Connection first = connect(db);
              Connection second = connect(db);
              try {
                reportsTo(first, a, b, from);
                reportsTo(second, b, a, from);
                CountDownLatch go = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                  List<Future<String>> outcomes = new ArrayList<>();
                  for (Connection c : List.of(first, second)) {
                    outcomes.add(
                        pool.submit(
                            () -> {
                              go.await();
                              try {
                                c.commit();
                                return "committed";
                              } catch (PSQLException refused) {
                                return constraint(refused);
                              }
                            }));
                  }
                  go.countDown();
                  List<String> results = new ArrayList<>();
                  for (Future<String> outcome : outcomes) {
                    results.add(outcome.get());
                  }
                  assertThat(results)
                      .containsExactlyInAnyOrder(
                          "committed", "employment_assignment_manager_acyclic");
                } finally {
                  pool.shutdownNow();
                }
              } finally {
                first.close();
                second.close();
              }
              // Rounds use disjoint periods; the direction alternates.
              Hired swap = a;
              a = b;
              b = swap;
            }
          } catch (SQLException
              | InterruptedException
              | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static void reportsTo(Connection c, Hired employee, Hired manager, LocalDate from)
      throws SQLException {
    UUID change = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, recorded_at, recorded_by, version_after) VALUES"
            + " (?, ?, ?, ?, 'CHANGE', ?, ARRAY['MANAGER'], 'SCHEDULED', now(), 'test', 1)",
        change,
        employee.tenant(),
        employee.employee(),
        employee.employment(),
        from);
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, effective_to, manager_employee_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'MANAGER', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        employee.tenant(),
        employee.employee(),
        employee.employment(),
        from,
        from.plusDays(5),
        manager.employee(),
        change,
        change);
  }
}
