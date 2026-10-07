package com.divalhr.core.documents;

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
import java.time.LocalDate;
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
 * MVP-030 V16 directly against PostgreSQL, bypassing the application (the database is the final
 * authority): the guarded rollback, immutable template versions, immutable issued snapshots that
 * move only ISSUED to ACKNOWLEDGED (with evidence) or VOID, non-overlapping periods, evidence bound
 * by key to the exact snapshot digest and versions (A30-4), tenant-composite and cross-schema keys
 * (ADR 0009), and no deletes or truncation.
 */
@IntegrationTest
class ContractMigrationIntegrationTest {

  /** The documents, people and identity schemas. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_schema || '.' || table_name, ','
                           ORDER BY table_schema, table_name)
                       FROM information_schema.tables
                       WHERE table_schema IN ('documents', 'people', 'identity')), '')
          || '|' || coalesce((SELECT string_agg(table_schema || '.' || table_name || '.'
                                  || column_name || ':' || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_schema, table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema IN ('documents', 'people', 'identity')), '')
          || '|' || coalesce((SELECT string_agg(schemaname || '.' || indexname || ':' || indexdef,
                                  ',' ORDER BY schemaname, indexname)
                              FROM pg_indexes
                              WHERE schemaname IN ('documents', 'people', 'identity')), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace IN ('documents'::regnamespace,
                                  'people'::regnamespace, 'identity'::regnamespace)), '')
          || '|' || coalesce((SELECT string_agg(c.relname || '.' || t.tgname, ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace IN ('documents'::regnamespace,
                                  'people'::regnamespace, 'identity'::regnamespace)
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY pronamespace, proname) FROM pg_proc
                              WHERE pronamespace IN ('documents'::regnamespace,
                                  'people'::regnamespace, 'identity'::regnamespace)), '')
      """;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final LocalDate START = LocalDate.of(2026, 3, 1);
  private static final String DIGEST = "a".repeat(64);
  private static final String CANONICAL =
      "{\"blocks\":[{\"t\":\"p\",\"x\":\"Texte\"}],\"grammar\":1,\"locale\":\"fr\","
          + "\"renderer\":1,\"title\":\"Contrat\"}";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "documents16_" + UUID.randomUUID().toString().replace("-", "");
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

  // ------------------------------------------------------------------------------------------
  // Fixtures (direct SQL)
  // ------------------------------------------------------------------------------------------

  private static UUID organization(JdbcTemplate db) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone,"
            + " status, created_at, created_by) VALUES (?, 'Org', 'CD', 'fr', 'Africa/Kinshasa',"
            + " 'ACTIVE', now(), 'test')",
        id);
    return id;
  }

  private record Hired(UUID tenant, UUID employee, UUID employment) {}

  private static Hired hire(Connection c, UUID tenant, String number) throws SQLException {
    Hired h = new Hired(tenant, UUID.randomUUID(), UUID.randomUUID());
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
        UUID.randomUUID(),
        change,
        change);
    return h;
  }

  private static UUID membership(Connection c, UUID tenant) throws SQLException {
    UUID id = UUID.randomUUID();
    byte[] lookup = new byte[32];
    RANDOM.nextBytes(lookup);
    exec(
        c,
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        id,
        tenant,
        UUID.randomUUID().toString(),
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

  private static UUID template(Connection c, UUID tenant, String code) throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO documents.contract_template (id, tenant_id, code, name, contract_type,"
            + " created_at, created_by) VALUES (?, ?, ?, 'Contrat type', 'PERMANENT', now(),"
            + " 'test')",
        id,
        tenant,
        code);
    return id;
  }

  private static UUID draft(Connection c, UUID tenant, UUID template, String locale, int number)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO documents.contract_template_version (id, tenant_id, template_id, locale,"
            + " version_number, state, title, body, placeholders, body_sha256, grammar_version,"
            + " digest_version, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?,"
            + " ?, ?, 'DRAFT', 'Contrat', 'Texte {{employee.fullName}}',"
            + " ARRAY['employee.fullName'], ?, 1, 1, now(), 'test', now(), 'test')",
        id,
        tenant,
        template,
        locale,
        number,
        DIGEST);
    return id;
  }

  private static void approve(Connection c, UUID version) throws SQLException {
    exec(
        c,
        "UPDATE documents.contract_template_version SET state = 'APPROVED', approved_at = now(),"
            + " approved_by = 'test', version = version + 1 WHERE id = ?",
        version);
  }

  private static void guard(Connection c, Hired h) throws SQLException {
    exec(
        c,
        "INSERT INTO documents.contract_employment_guard (tenant_id, employment_id) VALUES (?, ?)"
            + " ON CONFLICT DO NOTHING",
        h.tenant(),
        h.employment());
  }

  private static UUID contract(
      Connection c, Hired h, UUID template, UUID version, LocalDate start, LocalDate end)
      throws SQLException {
    UUID id = UUID.randomUUID();
    guard(c, h);
    exec(
        c,
        "INSERT INTO documents.contract (id, tenant_id, employee_id, employment_id, template_id,"
            + " template_version_id, contract_type, locale, start_date, end_date, snapshot,"
            + " snapshot_canonical, snapshot_sha256, digest_version, grammar_version,"
            + " renderer_version, state, issued_at, issued_by) VALUES (?, ?, ?, ?, ?, ?,"
            + " 'PERMANENT', 'fr', ?, ?, CAST(? AS jsonb), ?, ?, 1, 1, 1, 'ISSUED', now(), 'test')",
        id,
        h.tenant(),
        h.employee(),
        h.employment(),
        template,
        version,
        start,
        end,
        CANONICAL,
        CANONICAL,
        DIGEST);
    return id;
  }

  private static UUID acknowledgement(
      Connection c, Hired h, UUID contract, UUID membership, UUID link, String snapshotDigest)
      throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        c,
        "INSERT INTO documents.contract_acknowledgement (id, tenant_id, contract_id, employee_id,"
            + " membership_id, link_id, snapshot_sha256, snapshot_digest_version,"
            + " grammar_version, renderer_version, statement_code, statement_version,"
            + " statement_locale, statement_sha256, evidence_sha256, acknowledged_at,"
            + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, 1, 'RECEIVED_AND_REVIEWED', 1,"
            + " 'fr', ?, ?, statement_timestamp(), 'corr')",
        id,
        h.tenant(),
        contract,
        h.employee(),
        membership,
        link,
        snapshotDigest,
        "b".repeat(64),
        "c".repeat(64));
    return id;
  }

  private static void acknowledged(Connection c, UUID contract) throws SQLException {
    exec(
        c,
        "UPDATE documents.contract SET state = 'ACKNOWLEDGED', version = version + 1,"
            + " acknowledged_at = (SELECT acknowledged_at FROM documents.contract_acknowledgement"
            + " WHERE contract_id = ?) WHERE id = ?",
        contract,
        contract);
  }

  // ------------------------------------------------------------------------------------------
  // Rollback
  // ------------------------------------------------------------------------------------------

  @Test
  void theRollbackRestoresV15ExactlyAndRefusesOnceContractDataExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "15");
          String v15 = db.jdbc().queryForObject(SIGNATURE, String.class);
          // V16 exactly: later migrations (V17 adds an index) have their own rollback tests.
          migrate(db.url(), "16");
          String v16 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v16).isNotEqualTo(v15).contains("documents.contract_acknowledgement");
          int recorded = history(db);
          db.jdbc().execute(resource("db/rollback/V16__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v15);
          assertThat(history(db)).isEqualTo(recorded);

          // Re-apply V16 by hand (Flyway still records it) and add a template: refused.
          db.jdbc().execute(resource("db/migration/V16__contracts.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v16);
          try (Connection c = connect(db)) {
            template(c, organization(db.jdbc()), "CDI");
            c.commit();
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
          assertThatThrownBy(() -> db.jdbc().execute(resource("db/rollback/V16__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V16 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v16);
        });
  }

  private static int history(Db db) {
    return java.util.Objects.requireNonNull(
        db.jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  // ------------------------------------------------------------------------------------------
  // Templates
  // ------------------------------------------------------------------------------------------

  @Test
  void templateVersionsMoveOnlyForwardAndApprovedTextNeverChanges() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            UUID[] ids = new UUID[2];
            committed(
                db,
                c -> {
                  ids[0] = template(c, tenant, "CDI");
                  ids[1] = draft(c, tenant, ids[0], "fr", 1);
                });
            UUID template = ids[0];
            UUID v1 = ids[1];
            String immutable = "contract_template_version_immutable";
            refused(
                db,
                "contract_template_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract_template SET name = 'X' WHERE id = ?",
                        template));
            refused(db, "contract_template_code_unique", c -> template(c, tenant, "CDI"));
            // A draft's text changes, one version at a time; its identity never does.
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract_template_version SET body = 'Autre',"
                            + " version = version + 1 WHERE id = ?",
                        v1));
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract_template_version SET locale = 'en',"
                            + " version = version + 1 WHERE id = ?",
                        v1));
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c,
                        "INSERT INTO documents.contract_template_version (id, tenant_id,"
                            + " template_id, locale, version_number, state, title, body,"
                            + " placeholders, body_sha256, grammar_version, digest_version,"
                            + " created_at, created_by, updated_at, updated_by, approved_at,"
                            + " approved_by) VALUES (?, ?, ?, 'en', 1, 'APPROVED', 'T', 'B',"
                            + " ARRAY[]::text[], ?, 1, 1, now(), 't', now(), 't', now(), 't')",
                        UUID.randomUUID(),
                        tenant,
                        template,
                        DIGEST));
            // One draft per template and language; pinned grammar and digest versions (A30-4);
            // allow-listed placeholders only.
            refused(
                db,
                "contract_template_version_one_draft",
                c -> draft(c, tenant, template, "fr", 2));
            refused(
                db,
                "contract_template_version_grammar_v1",
                c ->
                    exec(
                        c,
                        "INSERT INTO documents.contract_template_version (id, tenant_id,"
                            + " template_id, locale, version_number, state, title, body,"
                            + " placeholders, body_sha256, grammar_version, digest_version,"
                            + " created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?,"
                            + " 'en', 1, 'DRAFT', 'T', 'B', ARRAY[]::text[], ?, 2, 1, now(), 't',"
                            + " now(), 't')",
                        UUID.randomUUID(),
                        tenant,
                        template,
                        DIGEST));
            refused(
                db,
                "contract_template_version_placeholders_valid",
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract_template_version SET placeholders ="
                            + " ARRAY['employee.salary'], version = version + 1 WHERE id = ?",
                        v1));

            committed(db, c -> approve(c, v1));
            for (String update :
                List.of(
                    "UPDATE documents.contract_template_version SET body = 'Modifié',"
                        + " version = version + 1 WHERE id = ?",
                    "UPDATE documents.contract_template_version SET title = 'Autre',"
                        + " version = version + 1 WHERE id = ?",
                    "UPDATE documents.contract_template_version SET body_sha256 = repeat('d', 64),"
                        + " version = version + 1 WHERE id = ?",
                    "UPDATE documents.contract_template_version SET state = 'DRAFT',"
                        + " approved_at = NULL, approved_by = NULL, version = version + 1"
                        + " WHERE id = ?")) {
              refused(db, immutable, c -> exec(c, update, v1));
            }
            refused(
                db,
                immutable,
                c -> exec(c, "DELETE FROM documents.contract_template_version WHERE id = ?", v1));
            // One approved version per template and language.
            UUID[] v2 = new UUID[1];
            committed(db, c -> v2[0] = draft(c, tenant, template, "fr", 2));
            refused(db, "contract_template_version_one_approved", c -> approve(c, v2[0]));
            // A never-approved draft can be deleted.
            committed(
                db,
                c ->
                    exec(c, "DELETE FROM documents.contract_template_version WHERE id = ?", v2[0]));
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract_template_version SET state = 'RETIRED',"
                            + " retired_at = now(), retired_by = 'test', version = version + 1"
                            + " WHERE id = ?",
                        v1));
            refused(db, immutable, c -> approve(c, v1));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // ------------------------------------------------------------------------------------------
  // Contracts and evidence
  // ------------------------------------------------------------------------------------------

  @Test
  void issuedSnapshotsNeverChangeAndPeriodsNeverOverlap() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            UUID foreign = organization(db.jdbc());
            Hired[] hired = new Hired[2];
            UUID[] t = new UUID[3];
            committed(
                db,
                c -> {
                  hired[0] = hire(c, tenant, "E-1");
                  hired[1] = hire(c, foreign, "E-1");
                  t[0] = template(c, tenant, "CDI");
                  t[1] = draft(c, tenant, t[0], "fr", 1);
                });
            Hired h = hired[0];
            String immutable = "contract_immutable";
            // Only from an approved version.
            refused(db, immutable, c -> contract(c, h, t[0], t[1], START, null));
            committed(db, c -> approve(c, t[1]));
            // Never for another tenant's employment.
            refused(
                db,
                "contract_employee",
                c -> {
                  exec(
                      c,
                      "INSERT INTO documents.contract (id, tenant_id, employee_id, employment_id,"
                          + " template_id, template_version_id, contract_type, locale,"
                          + " start_date, snapshot, snapshot_canonical, snapshot_sha256,"
                          + " digest_version, grammar_version, renderer_version, state,"
                          + " issued_at, issued_by) VALUES (?, ?, ?, ?, ?, ?, 'PERMANENT', 'fr',"
                          + " ?, CAST(? AS jsonb), ?, ?, 1, 1, 1, 'ISSUED', now(), 'test')",
                      UUID.randomUUID(),
                      tenant,
                      hired[1].employee(),
                      hired[1].employment(),
                      t[0],
                      t[1],
                      START,
                      CANONICAL,
                      CANONICAL,
                      DIGEST);
                });
            committed(db, c -> t[2] = contract(c, h, t[0], t[1], START, START.plusMonths(6)));
            UUID issued = t[2];
            // Non-void periods never overlap; successive ones may follow.
            refused(
                db,
                "contract_no_overlap",
                c -> contract(c, h, t[0], t[1], START.plusMonths(3), null));
            committed(db, c -> contract(c, h, t[0], t[1], START.plusMonths(6).plusDays(1), null));
            // The snapshot is exactly its canonical text; versions are pinned (A30-4).
            refused(
                db,
                "contract_snapshot_canonical",
                c ->
                    exec(
                        c,
                        "INSERT INTO documents.contract (id, tenant_id, employee_id,"
                            + " employment_id, template_id, template_version_id, contract_type,"
                            + " locale, start_date, snapshot, snapshot_canonical,"
                            + " snapshot_sha256, digest_version, grammar_version,"
                            + " renderer_version, state, issued_at, issued_by) VALUES (?, ?, ?, ?,"
                            + " ?, ?, 'PERMANENT', 'fr', ?, '{\"title\":\"x\"}', ?, ?, 1, 1, 1,"
                            + " 'ISSUED', now(), 'test')",
                        UUID.randomUUID(),
                        tenant,
                        h.employee(),
                        h.employment(),
                        t[0],
                        t[1],
                        START.minusYears(5),
                        CANONICAL,
                        DIGEST));
            for (String change :
                List.of(
                    "UPDATE documents.contract SET snapshot_canonical = '{}', snapshot = '{}',"
                        + " version = version + 1 WHERE id = ?",
                    "UPDATE documents.contract SET snapshot_sha256 = repeat('e', 64),"
                        + " version = version + 1 WHERE id = ?",
                    "UPDATE documents.contract SET start_date = start_date + 1,"
                        + " version = version + 1 WHERE id = ?")) {
              refused(db, immutable, c -> exec(c, change, issued));
            }
            refused(
                db,
                "contract_digest_v1",
                c ->
                    exec(
                        c,
                        "INSERT INTO documents.contract (id, tenant_id, employee_id,"
                            + " employment_id, template_id, template_version_id, contract_type,"
                            + " locale, start_date, snapshot, snapshot_canonical,"
                            + " snapshot_sha256, digest_version, grammar_version,"
                            + " renderer_version, state, issued_at, issued_by) VALUES (?, ?, ?, ?,"
                            + " ?, ?, 'PERMANENT', 'fr', ?, CAST(? AS jsonb), ?, ?, 2, 1, 1,"
                            + " 'ISSUED', now(), 'test')",
                        UUID.randomUUID(),
                        tenant,
                        h.employee(),
                        h.employment(),
                        t[0],
                        t[1],
                        START.minusYears(5),
                        CANONICAL,
                        CANONICAL,
                        DIGEST));
            // Acknowledged only with evidence.
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract SET state = 'ACKNOWLEDGED',"
                            + " acknowledged_at = now(), version = version + 1 WHERE id = ?",
                        issued));
            // Voided once, then frozen; never deleted or truncated.
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract SET state = 'VOID', void_reason ="
                            + " 'ISSUED_IN_ERROR', voided_at = now(), voided_by = 'test',"
                            + " version = version + 1 WHERE id = ?",
                        issued));
            refused(
                db,
                immutable,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract SET state = 'ISSUED', void_reason = NULL,"
                            + " voided_at = NULL, voided_by = NULL, version = version + 1"
                            + " WHERE id = ?",
                        issued));
            // The voided period is free again.
            committed(db, c -> contract(c, h, t[0], t[1], START, START.plusMonths(6)));
            refused(
                db, immutable, c -> exec(c, "DELETE FROM documents.contract WHERE id = ?", issued));
            for (String table :
                List.of(
                    "contract",
                    "contract_acknowledgement",
                    "contract_template",
                    "contract_template_version",
                    "contract_employment_guard")) {
              refused(
                  db,
                  "contract_no_truncate",
                  c -> {
                    try (Statement st = c.createStatement()) {
                      st.execute("TRUNCATE documents." + table + " CASCADE");
                    }
                  });
            }
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void evidenceNamesTheExactSnapshotTheEmployeesOwnLinkAndIsWrittenOnce() {
    withDatabase(
        db -> {
          migrate(db.url(), "latest");
          try {
            UUID tenant = organization(db.jdbc());
            Hired[] hired = new Hired[2];
            UUID[] ids = new UUID[8];
            committed(
                db,
                c -> {
                  hired[0] = hire(c, tenant, "E-1");
                  hired[1] = hire(c, tenant, "E-2");
                  ids[0] = template(c, tenant, "CDI");
                  ids[1] = draft(c, tenant, ids[0], "fr", 1);
                  approve(c, ids[1]);
                  ids[2] = membership(c, tenant);
                  ids[3] = link(c, hired[0], ids[2]);
                  ids[4] = membership(c, tenant);
                  ids[5] = link(c, hired[1], ids[4]);
                  ids[6] = contract(c, hired[0], ids[0], ids[1], START, null);
                  ids[7] = contract(c, hired[1], ids[0], ids[1], START, null);
                });
            Hired h = hired[0];
            UUID contract = ids[6];
            // Another snapshot digest: no such contract key (A30-4).
            refused(
                db,
                "contract_acknowledgement_contract",
                c -> acknowledgement(c, h, contract, ids[2], ids[3], "f".repeat(64)));
            // Another employee's link to their own membership.
            refused(
                db,
                "contract_acknowledgement_link",
                c -> acknowledgement(c, h, contract, ids[4], ids[5], DIGEST));
            // Pinned statement and versions.
            refused(
                db,
                "contract_acknowledgement_statement_v1",
                c ->
                    exec(
                        c,
                        "INSERT INTO documents.contract_acknowledgement (id, tenant_id,"
                            + " contract_id, employee_id, membership_id, link_id,"
                            + " snapshot_sha256, snapshot_digest_version, grammar_version,"
                            + " renderer_version, statement_code, statement_version,"
                            + " statement_locale, statement_sha256, evidence_sha256,"
                            + " acknowledged_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, 1,"
                            + " 1, 1, 'RECEIVED_AND_REVIEWED', 2, 'fr', ?, ?, now(), 'corr')",
                        UUID.randomUUID(),
                        tenant,
                        contract,
                        h.employee(),
                        ids[2],
                        ids[3],
                        DIGEST,
                        "b".repeat(64),
                        "c".repeat(64)));
            // Written once.
            refused(
                db,
                "contract_acknowledgement_once",
                c -> {
                  acknowledgement(c, h, contract, ids[2], ids[3], DIGEST);
                  acknowledgement(c, h, contract, ids[2], ids[3], DIGEST);
                });
            UUID[] evidence = new UUID[1];
            committed(
                db,
                c -> {
                  evidence[0] = acknowledgement(c, h, contract, ids[2], ids[3], DIGEST);
                  acknowledged(c, contract);
                });
            for (String sql :
                List.of(
                    "UPDATE documents.contract_acknowledgement SET statement_locale = 'en'"
                        + " WHERE id = ?",
                    "DELETE FROM documents.contract_acknowledgement WHERE id = ?")) {
              refused(db, "contract_acknowledgement_immutable", c -> exec(c, sql, evidence[0]));
            }
            // An acknowledged contract is never voided.
            refused(
                db,
                "contract_immutable",
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract SET state = 'VOID', void_reason = 'OTHER',"
                            + " voided_at = now(), voided_by = 'test', version = version + 1"
                            + " WHERE id = ?",
                        contract));
            // A void contract is never acknowledged.
            committed(
                db,
                c ->
                    exec(
                        c,
                        "UPDATE documents.contract SET state = 'VOID', void_reason = 'OTHER',"
                            + " voided_at = now(), voided_by = 'test', version = version + 1"
                            + " WHERE id = ?",
                        ids[7]));
            refused(
                db,
                "contract_acknowledgement_immutable",
                c -> acknowledgement(c, hired[1], ids[7], ids[4], ids[5], DIGEST));
          } catch (SQLException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
