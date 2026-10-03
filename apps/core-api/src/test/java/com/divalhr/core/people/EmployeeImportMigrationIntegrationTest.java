package com.divalhr.core.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-020 V13 (E3, E6, E13, E15, A20-2, A20-6): the people tables, their checks mirroring the
 * application, and a guarded rollback that restores V12 exactly but refuses while data exists.
 */
@IntegrationTest
class EmployeeImportMigrationIntegrationTest {

  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'people'), '')
          || '|' || coalesce((SELECT string_agg(indexname, ',' ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'people'), '')
          || '|' || coalesce((SELECT string_agg(conname, ',' ORDER BY conname) FROM pg_constraint
                              WHERE connamespace = 'people'::regnamespace), '')
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private void withDatabase(Consumer<Db> body) {
    String database = "people13_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      migrate(url, "12");
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

  private record Db(String url, JdbcTemplate jdbc) {}

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
      return new ClassPathResource("db/rollback/V13__rollback.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
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

  private static void employee(JdbcTemplate db, UUID tenant, String number, String given) {
    db.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " created_at, created_by) VALUES (?, ?, ?, ?, 'Family', now(), 'test')",
        UUID.randomUUID(),
        tenant,
        number,
        given);
  }

  private static void rejected(Runnable statement) {
    assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class);
  }

  @Test
  void v13ChecksMirrorTheApplicationRules() {
    withDatabase(
        db -> {
          migrate(db.url(), "13");
          UUID tenant = organization(db.jdbc());
          employee(db.jdbc(), tenant, "E-001", "Élodie");
          employee(db.jdbc(), tenant, "E-002", "é".repeat(100));
          rejected(() -> employee(db.jdbc(), tenant, "E-001", "Duplicate"));
          rejected(() -> employee(db.jdbc(), tenant, "e-003", "Lower"));
          rejected(() -> employee(db.jdbc(), tenant, "-E4", "Formula"));
          rejected(() -> employee(db.jdbc(), tenant, "E-005", "=cmd"));
          rejected(() -> employee(db.jdbc(), tenant, "E-006", "é".repeat(101)));
          // Lengths are code points: a supplementary-plane letter (4 UTF-8 bytes) counts once.
          String gothic = new String(Character.toChars(0x10330));
          employee(db.jdbc(), tenant, "E-011", gothic.repeat(100));
          rejected(() -> employee(db.jdbc(), tenant, "E-012", gothic.repeat(101)));
          // Only NFC is stored: the decomposed form of a valid name is refused.
          rejected(() -> employee(db.jdbc(), tenant, "E-013", "E\u0301lodie"));
          rejected(() -> employee(db.jdbc(), tenant, "E-007", "é"));
          rejected(() -> employee(db.jdbc(), tenant, "E-008", " padded"));
          rejected(() -> employee(db.jdbc(), tenant, "E-009", "two  spaces"));
          rejected(() -> employee(db.jdbc(), tenant, "E-010", "tab\there"));
          // Staged values exist only for valid rows of open imports.
          UUID importId = UUID.randomUUID();
          db.jdbc()
              .update(
                  "INSERT INTO people.employee_import (id, tenant_id, status, created_at,"
                      + " created_by, expires_at, file_sha256, preview_digest, delimiter,"
                      + " header_language, total_rows, valid_rows, invalid_rows) VALUES (?, ?,"
                      + " 'VALIDATED', now(), 'test', now() + interval '1 hour', ?, ?, 'COMMA',"
                      + " 'fr', 2, 1, 1)",
                  importId,
                  tenant,
                  "a".repeat(64),
                  "b".repeat(64));
          db.jdbc()
              .update(
                  "INSERT INTO people.employee_import_row (tenant_id, import_id, row_number,"
                      + " status, employee_number, given_names, family_name, start_date,"
                      + " legal_entity_code, site_code) VALUES (?, ?, 1, 'VALID', 'E-100', 'Ana',"
                      + " 'B', DATE '2026-01-01', 'LE-1', 'ST-1')",
                  tenant,
                  importId);
          rejected(
              () ->
                  db.jdbc()
                      .update(
                          "INSERT INTO people.employee_import_row (tenant_id, import_id,"
                              + " row_number, status, error_columns, error_codes, given_names)"
                              + " VALUES (?, ?, 2, 'INVALID', '{given_names}', '{ROW_FORMAT}',"
                              + " 'Leak')",
                          tenant,
                          importId));
          rejected(
              () ->
                  db.jdbc()
                      .update(
                          "INSERT INTO people.employee_import_row (tenant_id, import_id,"
                              + " row_number, status, error_columns, error_codes) VALUES (?, ?,"
                              + " 2, 'INVALID', '{given_names}', '{NOT_A_CODE}')",
                          tenant,
                          importId));
          rejected(
              () ->
                  db.jdbc()
                      .update(
                          "INSERT INTO people.employee_import_row (tenant_id, import_id,"
                              + " row_number, status) VALUES (?, ?, 2, 'INVALID')",
                          tenant,
                          importId));
          // Commit fields exist exactly for committed imports.
          rejected(
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE people.employee_import SET status = 'COMMITTED' WHERE id = ?",
                          importId));
        });
  }

  @Test
  void theRollbackRefusesWhilePeopleDataExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "13");
          String v13 = db.jdbc().queryForObject(SIGNATURE, String.class);
          employee(db.jdbc(), organization(db.jdbc()), "E-001", "Ana");
          assertThatThrownBy(() -> db.jdbc().execute(rollback()))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V13 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v13);
        });
  }

  @Test
  void anEmptyPeopleSchemaRollsBackToV12ExactlyAndMigratesAgain() {
    withDatabase(
        db -> {
          String v12 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "13");
          String v13 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v13)
              .contains("employee,employee_import,employee_import_row,employment")
              .isNotEqualTo(v12);
          db.jdbc().execute(rollback());
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v12);
          db.jdbc().update("DELETE FROM flyway_schema_history WHERE version = '13'");
          migrate(db.url(), "13");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v13);
        });
  }
}
