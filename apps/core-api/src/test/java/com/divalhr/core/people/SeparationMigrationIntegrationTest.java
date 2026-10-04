package com.divalhr.core.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * MVP-022 V15 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the guarded rollback, the governed employment end, the exact SEPARATION shape and its
 * exact reversal, the manager-employed backstop across transactions, separation-bound direct-report
 * changes (A22-2), and the identity link and revocation guards (D22-6, D22-7).
 */
@IntegrationTest
class SeparationMigrationIntegrationTest {

  /** The people and identity schemas: tables, columns, indexes, constraints, triggers, functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_schema || '.' || table_name, ','
                           ORDER BY table_schema, table_name)
                       FROM information_schema.tables
                       WHERE table_schema IN ('people', 'identity')), '')
          || '|' || coalesce((SELECT string_agg(table_schema || '.' || table_name || '.'
                                  || column_name || ':' || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, '') || ':' || is_generated,
                                  ',' ORDER BY table_schema, table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema IN ('people', 'identity')), '')
          || '|' || coalesce((SELECT string_agg(schemaname || '.' || indexname || ':' || indexdef,
                                  ',' ORDER BY schemaname, indexname)
                              FROM pg_indexes WHERE schemaname IN ('people', 'identity')), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY connamespace, conname) FROM pg_constraint
                              WHERE connamespace IN ('people'::regnamespace,
                                  'identity'::regnamespace)), '')
          || '|' || coalesce((SELECT string_agg(c.relname || '.' || t.tgname, ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace IN ('people'::regnamespace,
                                  'identity'::regnamespace) AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY pronamespace, proname) FROM pg_proc
                              WHERE pronamespace IN ('people'::regnamespace,
                                  'identity'::regnamespace)), '')
      """;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final LocalDate START = LocalDate.of(2026, 3, 1);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "people15_" + UUID.randomUUID().toString().replace("-", "");
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
                  new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))));
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
      return new ClassPathResource("db/rollback/V15__rollback.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String signature(Db db) {
    return db.jdbc().queryForObject(SIGNATURE, String.class);
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
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

  private static String constraint(PSQLException failure) {
    org.postgresql.util.ServerErrorMessage message = failure.getServerErrorMessage();
    return message == null ? "unnamed" : String.valueOf(message.getConstraint());
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  /** Runs work in its own transaction and expects it (statement or commit) to fail by name. */
  private void refused(Db db, String constraint, SqlWork work) throws SQLException {
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

  /** A hired employee (committed by the caller). */
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

  private static UUID change(
      Connection c, Hired h, String type, String kinds, LocalDate from, String reason, UUID sep)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, reason_code, timing, recorded_at, recorded_by,"
            + " version_after, separation_id) VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?,"
            + " 'SCHEDULED', now(), 'test', 1, ?)",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        type,
        from,
        kinds,
        reason,
        sep);
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

  /** A copy of a row with new dates, writer and (optionally) restored row. */
  private static UUID copy(
      Connection c, UUID row, LocalDate from, LocalDate to, UUID createdBy, UUID restores)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, effective_to, legal_entity_id, site_id, department_id,"
            + " cost_center_id, team_id, manager_employee_id, contract_code,"
            + " compensation_basis_code, created_by_change_id, origin_change_id,"
            + " restores_assignment_id) SELECT ?, tenant_id, employee_id, employment_id, kind, ?,"
            + " ?, legal_entity_id, site_id, department_id, cost_center_id, team_id,"
            + " manager_employee_id, contract_code, compensation_basis_code, ?,"
            + " origin_change_id, ? FROM people.employment_assignment WHERE id = ?",
        id,
        from,
        to,
        createdBy,
        restores,
        row);
    return id;
  }

  private static UUID managerRow(
      Connection c, Hired h, UUID manager, LocalDate from, LocalDate to, UUID change)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, effective_to, manager_employee_id, created_by_change_id,"
            + " origin_change_id) VALUES (?, ?, ?, ?, 'MANAGER', ?, ?, ?, ?, ?)",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        from,
        to,
        manager,
        change,
        change);
    return id;
  }

  /** A committed manager line from a date, open-ended. */
  private UUID reportsTo(Db db, Hired report, Hired manager, LocalDate from) throws SQLException {
    UUID[] row = new UUID[1];
    committed(
        db,
        c -> {
          UUID change = change(c, report, "CHANGE", "{MANAGER}", from, null, null);
          row[0] = managerRow(c, report, manager.employee(), from, null, change);
        });
    return row[0];
  }

  private static Instant startOfDayAfter(LocalDate lastDay) {
    return lastDay.plusDays(1).atStartOfDay(java.time.ZoneId.of("Africa/Kinshasa")).toInstant();
  }

  /** The separation header (change and separation rows); the caller writes the rows. */
  private record Sep(UUID id, UUID change, LocalDate lastDay) {}

  private static Sep header(
      Connection c, Hired h, LocalDate lastDay, String kinds, String action, UUID replacement,
      int intervals)
      throws SQLException {
    Sep s = new Sep(UUID.randomUUID(), UUID.randomUUID(), lastDay);
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, recorded_at, recorded_by, version_after,"
            + " separation_id) VALUES (?, ?, ?, ?, 'SEPARATION', ?, ?::text[], 'SCHEDULED', now(),"
            + " 'test', 1, ?)",
        s.change(),
        h.tenant(),
        h.employee(),
        h.employment(),
        lastDay.plusDays(1),
        kinds,
        s.id());
    exec(
        c,
        "INSERT INTO people.employment_separation (id, tenant_id, employee_id, employment_id,"
            + " change_id, last_day, reason_code, access_timing, report_action,"
            + " replacement_manager_id, report_count, interval_count, state, effective_at,"
            + " recorded_at, recorded_by) VALUES (?, ?, ?, ?, ?, ?, 'RESIGNATION',"
            + " 'END_OF_LAST_DAY', ?, ?, ?, ?, 'SCHEDULED', ?, now(), 'test')",
        s.id(),
        h.tenant(),
        h.employee(),
        h.employment(),
        s.change(),
        lastDay,
        action,
        replacement,
        intervals == 0 ? 0 : 1,
        intervals,
        Timestamp.from(startOfDayAfter(lastDay)));
    return s;
  }

  private static void close(Connection c, Hired h, LocalDate lastDay) throws SQLException {
    exec(
        c,
        "UPDATE people.employment SET effective_to = ?, version = version + 1 WHERE id = ?",
        lastDay,
        h.employment());
  }

  /** A complete, valid separation of an employee whose only row is the hire placement. */
  private Sep separate(Db db, Hired h, LocalDate lastDay) throws SQLException {
    Sep[] s = new Sep[1];
    committed(
        db,
        c -> {
          s[0] = header(c, h, lastDay, "{PLACEMENT}", null, null, 0);
          supersede(c, h.placement(), s[0].change());
          copy(c, h.placement(), START, lastDay, s[0].change(), null);
          close(c, h, lastDay);
        });
    return s[0];
  }

  private static UUID membership(Connection c, UUID tenant, String role) throws SQLException {
    UUID id = UUID.randomUUID();
    byte[] lookup = new byte[32];
    RANDOM.nextBytes(lookup);
    exec(
        c,
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, ?, ?, now())",
        id,
        tenant,
        UUID.randomUUID().toString(),
        role,
        lookup);
    return id;
  }

  private static UUID link(Connection c, Hired h, UUID membership) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO identity.employee_access_link (id, tenant_id, employee_id, membership_id,"
            + " linked_at, linked_by) VALUES (?, ?, ?, ?, now(), 'test')",
        id,
        h.tenant(),
        h.employee(),
        membership);
    return id;
  }

  private static UUID revocation(
      Connection c, Hired h, UUID membership, String role, UUID link, UUID separation,
      Instant effectiveAt)
      throws SQLException {
    UUID id = UUID.randomUUID();
    boolean due = !effectiveAt.isAfter(Instant.now());
    exec(
        c,
        "INSERT INTO identity.access_revocation (id, tenant_id, membership_id, membership_role,"
            + " link_id, employee_id, separation_id, effective_at, state, next_attempt_at,"
            + " requested_at, effective_marked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(),"
            + " ?)",
        id,
        h.tenant(),
        membership,
        role,
        link,
        h.employee(),
        separation,
        Timestamp.from(effectiveAt),
        due ? "IDP_PENDING" : "SCHEDULED",
        due ? Timestamp.from(Instant.now()) : null,
        due ? Timestamp.from(Instant.now()) : null);
    return id;
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV14ExactlyAndRefusesOnceSeparationDataExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "14.2");
          String v14 = signature(db);
          migrate(db.url(), "latest");
          String v15 = signature(db);
          assertThat(v15)
              .isNotEqualTo(v14)
              .contains("people.employment_separation", "identity.access_revocation");
          int recorded = history(db);
          try {
            UUID tenant = organization(db.jdbc());
            Hired h;
            try (Connection c = connect(db)) {
              h = hire(c, tenant, "E-1");
              c.commit();
            }
            // Ordinary history (MVP-021) does not block the V15 rollback.
            db.jdbc().execute(rollback());
            assertThat(signature(db)).isEqualTo(v14);
            assertThat(history(db)).isEqualTo(recorded);

            // Re-apply V15 by hand (Flyway still records it) and add a separation: refused.
            db.jdbc()
                .execute(
                    new ClassPathResource(
                            "db/migration/V15__separation_and_access_revocation.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
            assertThat(signature(db)).isEqualTo(v15);
            separate(db, h, LocalDate.now().plusDays(30));
            assertThatThrownBy(() -> db.jdbc().execute(rollback()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("V15 rollback refused");
            assertThat(signature(db)).isEqualTo(v15);
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT effective_to FROM people.employment WHERE id = ?",
                            LocalDate.class,
                            h.employment()))
                .isNotNull();
          } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // The employment end, the SEPARATION shape and its exact reversal
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyASeparationEndsAnEmploymentAndItsShapeIsExact() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired h;
            try (Connection c = connect(db)) {
              h = hire(c, tenant, "E-1");
              c.commit();
            }
            LocalDate d = LocalDate.now().plusDays(30);
            String governed = "employment_end_governed";

            // No end date without a separation; nothing else on an employment ever changes.
            refused(db, governed, c -> close(c, h, d));
            refused(
                db,
                governed,
                c ->
                    exec(
                        c,
                        "UPDATE people.employment SET effective_from = ? WHERE id = ?",
                        START.plusDays(1),
                        h.employment()));
            refused(
                db,
                governed,
                c -> exec(c, "DELETE FROM people.employment WHERE id = ?", h.employment()));
            refused(
                db,
                "employment_history_no_truncate",
                c -> {
                  try (Statement s = c.createStatement()) {
                    s.execute("TRUNCATE people.employment CASCADE");
                  }
                });
            // The end date must be the separation's last day.
            refused(
                db,
                governed,
                c -> {
                  Sep s = header(c, h, d, "{PLACEMENT}", null, null, 0);
                  supersede(c, h.placement(), s.change());
                  copy(c, h.placement(), START, d, s.change(), null);
                  close(c, h, d.plusDays(1));
                });
            // A row of another kind left past the last day.
            Hired withContract;
            try (Connection c = connect(db)) {
              withContract = hire(c, tenant, "E-2");
              UUID contract = change(c, withContract, "CHANGE", "{CONTRACT}", START.plusDays(5),
                  null, null);
              exec(
                  c,
                  "INSERT INTO people.employment_assignment (id, tenant_id, employee_id,"
                      + " employment_id, kind, effective_from, contract_code,"
                      + " created_by_change_id, origin_change_id) VALUES (?, ?, ?, ?, 'CONTRACT',"
                      + " ?, 'PERMANENT', ?, ?)",
                  UUID.randomUUID(),
                  tenant,
                  withContract.employee(),
                  withContract.employment(),
                  START.plusDays(5),
                  contract,
                  contract);
              c.commit();
            }
            refused(
                db,
                "employment_assignment_within_employment",
                c -> {
                  Sep s = header(c, withContract, d, "{PLACEMENT}", null, null, 0);
                  supersede(c, withContract.placement(), s.change());
                  copy(c, withContract.placement(), START, d, s.change(), null);
                  close(c, withContract, d);
                });
            // The copy must end exactly on the last day.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  Sep s = header(c, h, d, "{PLACEMENT}", null, null, 0);
                  supersede(c, h.placement(), s.change());
                  copy(c, h.placement(), START, d.minusDays(1), s.change(), null);
                  copy(c, h.placement(), d, d, s.change(), null);
                  close(c, h, d);
                });
            // kinds[] equals the touched kinds exactly.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  Sep s = header(c, h, d, "{PLACEMENT,CONTRACT}", null, null, 0);
                  supersede(c, h.placement(), s.change());
                  copy(c, h.placement(), START, d, s.change(), null);
                  close(c, h, d);
                });
            // A SEPARATION change always names its separation.
            refused(
                db,
                "employment_change_separation_valid",
                c ->
                    exec(
                        c,
                        "INSERT INTO people.employment_change (id, tenant_id, employee_id,"
                            + " employment_id, type, effective_from, kinds, timing, recorded_at,"
                            + " recorded_by, version_after) VALUES (?, ?, ?, ?, 'SEPARATION', ?,"
                            + " ARRAY['PLACEMENT'], 'SCHEDULED', now(), 'test', 1)",
                        UUID.randomUUID(),
                        tenant,
                        h.employee(),
                        h.employment(),
                        d.plusDays(1)));

            // The valid separation; then a second one is refused.
            Sep s = separate(db, h, d);
            refused(
                db,
                "employment_separation_one_open",
                c -> header(c, h, d.minusDays(1), "{PLACEMENT}", null, null, 0));
            // A separation never changes except its state, at the right time.
            refused(
                db,
                "employment_separation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.employment_separation SET last_day = ?, version = 1"
                            + " WHERE id = ?",
                        d.minusDays(1),
                        s.id()));
            refused(
                db,
                "employment_separation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.employment_separation SET state = 'EFFECTIVE',"
                            + " effective_marked_at = now(), version = 1 WHERE id = ?",
                        s.id()));
            // Cancellation needs the SEPARATION change cancelled first.
            refused(
                db,
                "employment_separation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.employment_separation SET state = 'CANCELLED',"
                            + " cancelled_at = now(), cancelled_by = 'test', version = 1"
                            + " WHERE id = ?",
                        s.id()));
            refused(
                db,
                "employment_separation_immutable",
                c -> exec(c, "DELETE FROM people.employment_separation WHERE id = ?", s.id()));

            UUID truncated =
                db.jdbc()
                    .queryForObject(
                        "SELECT id FROM people.employment_assignment WHERE created_by_change_id"
                            + " = ?",
                        UUID.class,
                        s.change());
            // A reversal that names another row than the one the separation replaced is refused.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  UUID cancel = cancellation(c, h, s);
                  supersede(c, truncated, cancel);
                  copy(c, h.placement(), START, null, cancel, truncated);
                  cancelSeparation(c, h, s);
                });
            // A reversal restoring other dates is refused.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  UUID cancel = cancellation(c, h, s);
                  supersede(c, truncated, cancel);
                  copy(c, h.placement(), START, d.plusDays(10), cancel, h.placement());
                  cancelSeparation(c, h, s);
                });
            // The exact reversal reopens the employment.
            committed(
                db,
                c -> {
                  UUID cancel = cancellation(c, h, s);
                  supersede(c, truncated, cancel);
                  copy(c, h.placement(), START, null, cancel, h.placement());
                  cancelSeparation(c, h, s);
                });
            assertThat(
                    db.jdbc()
                        .queryForObject(
                            "SELECT effective_to FROM people.employment WHERE id = ?",
                            LocalDate.class,
                            h.employment()))
                .isNull();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static UUID cancellation(Connection c, Hired h, Sep s) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, timing, cancels_change_id, recorded_at, recorded_by,"
            + " version_after, separation_id) SELECT ?, tenant_id, employee_id, employment_id,"
            + " 'CANCELLATION', effective_from, kinds, 'SCHEDULED', id, now(), 'test', 2,"
            + " separation_id FROM people.employment_change WHERE id = ?",
        id,
        s.change());
    exec(
        c,
        "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?",
        s.change());
    return id;
  }

  private static void cancelSeparation(Connection c, Hired h, Sep s) throws SQLException {
    exec(
        c,
        "UPDATE people.employment_separation SET state = 'CANCELLED', cancelled_at = now(),"
            + " cancelled_by = 'test', version = version + 1 WHERE id = ?",
        s.id());
    exec(
        c,
        "UPDATE people.employment SET effective_to = NULL, version = version + 1 WHERE id = ?",
        h.employment());
  }

  // ------------------------------------------------------------------------------------------
  // Manager continuity (D22-18) and separation-bound direct-report changes (A22-2)
  // ------------------------------------------------------------------------------------------

  @Test
  void aSeparatedManagerNeverKeepsAReportAndBoundChangesAreExact() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired manager;
            Hired report;
            Hired replacement;
            try (Connection c = connect(db)) {
              manager = hire(c, tenant, "E-M");
              report = hire(c, tenant, "E-R");
              replacement = hire(c, tenant, "E-X");
              c.commit();
            }
            LocalDate d = LocalDate.now().plusDays(30);
            UUID line = reportsTo(db, report, manager, START.plusDays(10));
            String employed = "employment_manager_employed";

            // Separating the manager while the report still names them after D is refused.
            refused(
                db,
                employed,
                c -> {
                  Sep s = header(c, manager, d, "{PLACEMENT}", null, null, 0);
                  supersede(c, manager.placement(), s.change());
                  copy(c, manager.placement(), START, d, s.change(), null);
                  close(c, manager, d);
                });

            // MANAGER_SEPARATED only with a separation, and only for MANAGER.
            refused(
                db,
                "employment_change_separation_valid",
                c ->
                    change(c, report, "CHANGE", "{MANAGER}", d.plusDays(1), "MANAGER_SEPARATED",
                        null));

            // A bound change that writes another manager than the plan's is refused.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  Sep s =
                      header(c, manager, d, "{PLACEMENT}", "REASSIGN", replacement.employee(), 1);
                  supersede(c, manager.placement(), s.change());
                  copy(c, manager.placement(), START, d, s.change(), null);
                  UUID bound =
                      change(c, report, "CHANGE", "{MANAGER}", d.plusDays(1), "MANAGER_SEPARATED",
                          s.id());
                  supersede(c, line, bound);
                  copy(c, line, START.plusDays(10), d, bound, null);
                  managerRow(c, report, manager.employee(), d.plusDays(1), null, bound);
                  close(c, manager, d);
                });
            // A bound change starting on another date than D+1 for a row covering D is refused.
            refused(
                db,
                "employment_change_shape",
                c -> {
                  Sep s =
                      header(c, manager, d, "{PLACEMENT}", "REASSIGN", replacement.employee(), 1);
                  supersede(c, manager.placement(), s.change());
                  copy(c, manager.placement(), START, d, s.change(), null);
                  UUID bound =
                      change(c, report, "CHANGE", "{MANAGER}", d.minusDays(3), "MANAGER_SEPARATED",
                          s.id());
                  supersede(c, line, bound);
                  copy(c, line, START.plusDays(10), d.minusDays(4), bound, null);
                  managerRow(c, report, replacement.employee(), d.minusDays(3), null, bound);
                  close(c, manager, d);
                });

            // The valid separation with its bound change (REASSIGN).
            Sep[] valid = new Sep[1];
            UUID[] boundChange = new UUID[1];
            committed(
                db,
                c -> {
                  Sep s =
                      header(c, manager, d, "{PLACEMENT}", "REASSIGN", replacement.employee(), 1);
                  supersede(c, manager.placement(), s.change());
                  copy(c, manager.placement(), START, d, s.change(), null);
                  UUID bound =
                      change(c, report, "CHANGE", "{MANAGER}", d.plusDays(1), "MANAGER_SEPARATED",
                          s.id());
                  supersede(c, line, bound);
                  copy(c, line, START.plusDays(10), d, bound, null);
                  managerRow(c, report, replacement.employee(), d.plusDays(1), null, bound);
                  close(c, manager, d);
                  valid[0] = s;
                  boundChange[0] = bound;
                });

            // A later, separate transaction cannot add a reporting line to the separated manager
            // after D (cross-transaction backstop).
            refused(
                db,
                employed,
                c -> {
                  UUID other = change(c, replacement, "CHANGE", "{MANAGER}", d.plusDays(5), null,
                      null);
                  managerRow(c, replacement, manager.employee(), d.plusDays(5), null, other);
                });
            // Before D it is still allowed.
            committed(
                db,
                c -> {
                  UUID other =
                      change(c, replacement, "CHANGE", "{MANAGER}", START.plusDays(20), null, null);
                  managerRow(c, replacement, manager.employee(), START.plusDays(20), d, other);
                });

            // A bound change cannot be cancelled on its own (only with its separation).
            refused(
                db,
                "employment_change_shape",
                c -> {
                  UUID cancel = UUID.randomUUID();
                  exec(
                      c,
                      "INSERT INTO people.employment_change (id, tenant_id, employee_id,"
                          + " employment_id, type, effective_from, kinds, timing,"
                          + " cancels_change_id, recorded_at, recorded_by, version_after,"
                          + " separation_id) SELECT ?, tenant_id, employee_id, employment_id,"
                          + " 'CANCELLATION', effective_from, kinds, 'SCHEDULED', id, now(),"
                          + " 'test', 3, separation_id FROM people.employment_change"
                          + " WHERE id = ?",
                      cancel,
                      boundChange[0]);
                  exec(
                      c,
                      "UPDATE people.employment_change SET state = 'CANCELLED' WHERE id = ?",
                      boundChange[0]);
                  exec(
                      c,
                      "UPDATE people.employment_assignment SET superseded_by_change_id = ?,"
                          + " superseded_at = now() WHERE created_by_change_id = ?",
                      cancel,
                      boundChange[0]);
                  copy(c, line, START.plusDays(10), null, cancel, line);
                });
            assertThat(valid[0]).isNotNull();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // identity: links and revocations (D22-6, D22-7)
  // ------------------------------------------------------------------------------------------

  @Test
  void linksAndRevocationsAreTenantBoundEmployeeOnlyAndMoveOnlyForward() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            UUID foreign = organization(db.jdbc());
            Hired h;
            Hired other;
            UUID employee;
            UUID admin;
            UUID foreignMember;
            try (Connection c = connect(db)) {
              h = hire(c, tenant, "E-1");
              other = hire(c, tenant, "E-2");
              employee = membership(c, tenant, "employee");
              admin = membership(c, tenant, "tenant-admin");
              foreignMember = membership(c, foreign, "employee");
              c.commit();
            }
            // A membership of another tenant can never be linked.
            refused(
                db, "employee_access_link_membership", c -> link(c, h, foreignMember));
            UUID[] linked = new UUID[1];
            committed(db, c -> linked[0] = link(c, h, employee));
            // One active link per employee and per membership.
            refused(
                db, "employee_access_link_membership_active", c -> link(c, other, employee));
            refused(db, "employee_access_link_employee_active", c -> link(c, h, admin));

            LocalDate d = LocalDate.now().plusDays(30);
            Sep s = separate(db, h, d);
            Instant at = startOfDayAfter(d);

            // A tenant administrator is never a target, whatever role is claimed.
            refused(
                db,
                "access_revocation_membership",
                c -> revocation(c, h, admin, "employee", linked[0], s.id(), at));
            refused(
                db,
                "access_revocation_membership_employee_role",
                c -> revocation(c, h, admin, "tenant-admin", linked[0], s.id(), at));
            // Through the employee's own active link only.
            refused(
                db,
                "access_revocation_link",
                c -> revocation(c, other, employee, "employee", linked[0], s.id(), at));

            UUID[] revoked = new UUID[1];
            committed(
                db,
                c -> revoked[0] = revocation(c, h, employee, "employee", linked[0], s.id(), at));
            // The link it holds cannot be removed; the revoked membership cannot be relinked.
            refused(
                db,
                "employee_access_link_locked",
                c ->
                    exec(
                        c,
                        "UPDATE identity.employee_access_link SET unlinked_at = now(),"
                            + " unlinked_by = 'test', version = 1 WHERE id = ?",
                        linked[0]));
            // Starting SCHEDULED in the past, or jumping to COMPLETED, is refused.
            refused(
                db,
                "access_revocation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE identity.access_revocation SET state = 'IDP_PENDING',"
                            + " effective_marked_at = now(), next_attempt_at = now(), version = 1"
                            + " WHERE id = ?",
                        revoked[0]));
            refused(
                db,
                "access_revocation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE identity.access_revocation SET effective_at = now(), version = 1"
                            + " WHERE id = ?",
                        revoked[0]));
            refused(
                db,
                "access_revocation_immutable",
                c -> exec(c, "DELETE FROM identity.access_revocation WHERE id = ?", revoked[0]));
            for (String table : List.of("access_revocation", "employee_access_link")) {
              refused(
                  db,
                  "access_history_no_truncate",
                  c -> {
                    try (Statement st = c.createStatement()) {
                      st.execute("TRUNCATE identity." + table + " CASCADE");
                    }
                  });
            }
            // A scheduled revocation can be cancelled before it takes effect.
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE identity.access_revocation SET state = 'CANCELLED',"
                            + " cancelled_at = now(), cancelled_by = 'test', version = 1"
                            + " WHERE id = ?",
                        revoked[0]));
            // A revocation that took effect is never cancelled, and its membership is never
            // linked again; IDP_PENDING may only finish, retry or need intervention.
            UUID[] due = new UUID[1];
            committed(
                db,
                c ->
                    due[0] =
                        revocation(
                            c,
                            h,
                            employee,
                            "employee",
                            linked[0],
                            s.id(),
                            Instant.now().minus(1, ChronoUnit.MINUTES)));
            refused(
                db,
                "access_revocation_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE identity.access_revocation SET state = 'CANCELLED',"
                            + " cancelled_at = now(), cancelled_by = 'test',"
                            + " effective_marked_at = NULL, next_attempt_at = NULL, version = 1"
                            + " WHERE id = ?",
                        due[0]));
            refused(
                db,
                "employee_access_link_immutable",
                c -> {
                  exec(
                      c,
                      "UPDATE identity.access_revocation SET state = 'COMPLETED',"
                          + " next_attempt_at = NULL, idp_completed_at = now(),"
                          + " outcome_code = 'REVOKED', version = 1 WHERE id = ?",
                      due[0]);
                  link(c, other, employee);
                });
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE identity.access_revocation SET state = 'MANUAL_INTERVENTION',"
                            + " next_attempt_at = NULL, outcome_code = 'STALE_LINK', version = 1"
                            + " WHERE id = ?",
                        due[0]));
            // Retry resets the attempt budget only from MANUAL_INTERVENTION.
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE identity.access_revocation SET state = 'IDP_PENDING',"
                            + " next_attempt_at = now(), outcome_code = NULL, attempts = 0,"
                            + " version = 2 WHERE id = ?",
                        due[0]));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Checklist
  // ------------------------------------------------------------------------------------------

  @Test
  void checklistTasksMoveThroughAllowedStatusesWithDatabaseRecordedHistory() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired h;
            try (Connection c = connect(db)) {
              h = hire(c, tenant, "E-1");
              c.commit();
            }
            LocalDate d = LocalDate.now().plusDays(30);
            Sep s = separate(db, h, d);
            UUID task = UUID.randomUUID();
            refused(
                db,
                "separation_task_immutable",
                c -> task(c, h, s, UUID.randomUUID(), "RETURN_ASSIGNED_ASSETS", "DONE"));
            committed(db, c -> task(c, h, s, task, "RETURN_ASSIGNED_ASSETS", "OPEN"));
            refused(
                db,
                "separation_task_one_per_code",
                c -> task(c, h, s, UUID.randomUUID(), "RETURN_ASSIGNED_ASSETS", "OPEN"));
            // DONE, then OPEN again; never DONE -> NOT_APPLICABLE; never CANCELLED while the
            // separation is not cancelled.
            committed(db, c -> status(c, task, "DONE", 1));
            refused(
                db, "separation_task_immutable", c -> status(c, task, "NOT_APPLICABLE", 2));
            refused(db, "separation_task_immutable", c -> status(c, task, "CANCELLED", 2));
            refused(db, "separation_task_immutable", c -> status(c, task, "OPEN", 5));
            committed(db, c -> status(c, task, "OPEN", 2));
            assertThat(
                    db.jdbc()
                        .queryForList(
                            "SELECT coalesce(from_status, '-') || '>' || to_status FROM"
                                + " people.separation_task_event WHERE task_id = ? ORDER BY"
                                + " version",
                            String.class,
                            task))
                .containsExactly("->OPEN", "OPEN>DONE", "DONE>OPEN");
            refused(
                db,
                "separation_task_event_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE people.separation_task_event SET to_status = 'DONE'"
                            + " WHERE task_id = ?",
                        task));
            refused(
                db,
                "separation_task_immutable",
                c -> exec(c, "DELETE FROM people.separation_task WHERE id = ?", task));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static void task(Connection c, Hired h, Sep s, UUID id, String code, String status)
      throws SQLException {
    exec(
        c,
        "INSERT INTO people.separation_task (id, tenant_id, employee_id, separation_id, code,"
            + " status, due_date, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, now(),"
            + " 'test')",
        id,
        h.tenant(),
        h.employee(),
        s.id(),
        code,
        status,
        s.lastDay());
  }

  private static void status(Connection c, UUID task, String status, long version)
      throws SQLException {
    exec(
        c,
        "UPDATE people.separation_task SET status = ?, updated_at = now(), updated_by = 'test',"
            + " version = ? WHERE id = ?",
        status,
        version,
        task);
  }
}
