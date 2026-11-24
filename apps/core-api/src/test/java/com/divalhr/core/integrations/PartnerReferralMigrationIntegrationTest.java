package com.divalhr.core.integrations;

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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MVP-120F V57 directly against PostgreSQL (the database is the final authority):
 * complete-at-commit submission, the employee and product gates, one referral per preview, the
 * closed lifecycle, the lock-ordered withdrawal-versus-delivery rule, immutable evidence and the
 * refusing rollback that restores V56 exactly. Every value is SYNTHETIC.
 */
@IntegrationTest
class PartnerReferralMigrationIntegrationTest {

  private static final String DIGEST_A = "a".repeat(64);
  private static final String DIGEST_B = "b".repeat(64);

  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'integrations'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'integrations'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'integrations'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'integrations'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'integrations'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'integrations'::regnamespace), '')
      """;

  /** The ten required fields plus the optional phone. */
  private static final String FIELDS =
      "ARRAY['REFERRAL_REFERENCE','PRODUCT_REFERENCE','PRODUCT_NAME','PARTNER_NAME',"
          + "'EMPLOYEE_LEGAL_NAME','EMPLOYEE_EMAIL','EMPLOYEE_COUNTRY','PREFERRED_LANGUAGE',"
          + "'CONSENT_TIMESTAMP','CONSENT_VERSION']";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  /**
   * One tenant, one linked employee and one live product of an active partner in the tenant's
   * country.
   */
  private record World(
      UUID tenant, UUID employee, String subject, UUID partner, UUID product, UUID version) {}

  private void withDatabase(String target, Consumer<Db> body) {
    String database = "pr120f_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE DATABASE " + database);
    try {
      String url =
          "jdbc:postgresql://"
              + postgres.getHost()
              + ":"
              + postgres.getMappedPort(5432)
              + "/"
              + database;
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .locations("classpath:db/migration")
          .target(target)
          .load()
          .migrate();
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

  private static String resource(String path) {
    try {
      return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Runs a script as written, on one connection (JDBC escape processing would rewrite braces). */
  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (ConnectionCallback<Void>)
                connection -> {
                  try (java.sql.Statement statement = connection.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.execute(sql);
                  }
                  return null;
                });
  }

  private static String ref(String prefix) {
    String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    StringBuilder b = new StringBuilder(prefix);
    for (int i = 0; i < 16; i++) {
      b.append(alphabet.charAt(REFERENCE_RANDOM.nextInt(alphabet.length())));
    }
    return b.toString();
  }

  /** Prerequisite rows are inserted with triggers off; the V57 guards are exercised afterwards. */
  private static World world(
      Db db, String productCountry, String versionStatus, String partnerStatus) {
    World w =
        new World(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sub-" + UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    UUID membership = UUID.randomUUID();
    script(
        db,
        """
        SET session_replication_role = replica;
        INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone, status,
            created_at, created_by) VALUES ('%1$s', 'Synthetic Employer', 'CD', 'en',
            'Africa/Kinshasa', 'ACTIVE', now(), 'test');
        INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,
            search_key, created_at, created_by) VALUES ('%2$s', '%1$s', 'E%7$s', 'Synthetic', 'Employee',
            ' synthetic employee ', now(), 'test');
        INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup, created_at)
            VALUES ('%3$s', '%1$s', '%4$s', 'employee', decode(repeat('00', 32), 'hex'), now());
        INSERT INTO identity.employee_access_link (id, tenant_id, employee_id, membership_id, linked_at,
            linked_by) VALUES (gen_random_uuid(), '%1$s', '%2$s', '%3$s', now(), 'test');
        INSERT INTO integrations.partner (id, reference, application_id, status, row_version,
            primary_category, legal_name, country_of_registration, website, supported_languages,
            created_at, updated_at) VALUES ('%5$s', '%8$s', gen_random_uuid(), '%10$s', 0, 'BANK',
            'Synthetic Partner', 'CD', 'https://partner.example.test/', ARRAY['en','fr'], now(), now());
        INSERT INTO integrations.partner_product (id, partner_id, product_code, public_reference,
            product_type, created_at) VALUES ('%6$s', '%5$s', 'SYN_PRODUCT', '%9$s',
            'OTHER_BENEFIT', now());
        INSERT INTO integrations.partner_product_version (id, product_id, version, status, row_version,
            name_en, name_fr, summary_en, summary_fr, eligibility_en, eligibility_fr, disclosure_en,
            disclosure_fr, effective_date, created_at, updated_at)
          VALUES ('%11$s', '%6$s', 1, '%12$s', 0, 'Synthetic benefit', 'Avantage synthetique',
            'A synthetic benefit for tests.', 'Un avantage synthetique de test.',
            'Open to every synthetic employee.', 'Ouvert a tout employe synthetique.',
            'Synthetic disclosure text for the migration test only.',
            'Texte synthetique de divulgation pour le test de migration.', current_date - 1, now(), now());
        INSERT INTO integrations.partner_product_country (version_id, country_code)
            VALUES ('%11$s', '%13$s');
        RESET session_replication_role;
        """
            .formatted(
                w.tenant(),
                w.employee(),
                membership,
                w.subject(),
                w.partner(),
                w.product(),
                UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                ref("PT-"),
                ref("PP-"),
                partnerStatus,
                w.version(),
                versionStatus,
                productCountry));
    return w;
  }

  private static World world(Db db) {
    return world(db, "CD", "ACTIVE", "ACTIVE");
  }

  /** A complete submission in one transaction; each flag removes one required piece. */
  private static UUID submit(
      Db db,
      World w,
      String subject,
      String preview,
      boolean consent,
      boolean disclosure,
      boolean event,
      String fieldsSql) {
    UUID id = UUID.randomUUID();
    script(
        db,
        """
        BEGIN;
        INSERT INTO integrations.partner_referral (tenant_id, id, reference, employee_id, partner_id,
            product_id, version_id, partner_code, language, state, row_version, attempt_count,
            preview_sha256, key_version, submitted_by, next_attempt_at, created_at, updated_at)
          VALUES ('%1$s', '%2$s', '%3$s', '%4$s', '%5$s', '%6$s', '%7$s', 'SYNTHETIC-TEST', 'en',
            'SUBMITTED', 1, 0, '%8$s', 'k1', '%9$s', now(), now(), now());
        %10$s
        %11$s
        %12$s
        COMMIT;
        """
            .formatted(
                w.tenant(),
                id,
                ref("PR-"),
                w.employee(),
                w.partner(),
                w.product(),
                w.version(),
                preview,
                subject,
                disclosure
                    ? "INSERT INTO integrations.partner_referral_disclosure VALUES ('"
                        + w.tenant()
                        + "', '"
                        + id
                        + "', "
                        + fieldsSql
                        + ", decode(repeat('ab', 60), 'hex'), '"
                        + DIGEST_B
                        + "', now());"
                    : "",
                consent
                    ? "INSERT INTO integrations.partner_referral_consent VALUES ('"
                        + w.tenant()
                        + "', '"
                        + id
                        + "', 'PR-REFERRAL-1', 'en', now(), '"
                        + subject
                        + "', '"
                        + DIGEST_B
                        + "', '"
                        + preview
                        + "', true);"
                    : "",
                event
                    ? "INSERT INTO integrations.partner_referral_event VALUES ('"
                        + w.tenant()
                        + "', '"
                        + id
                        + "', 1, NULL, 'SUBMITTED', 'EMPLOYEE', NULL, now());"
                    : ""));
    return id;
  }

  private static UUID submit(Db db, World w) {
    return submit(db, w, w.subject(), DIGEST_A, true, true, true, FIELDS);
  }

  private static UUID submit(Db db, World w, String preview) {
    return submit(db, w, w.subject(), preview, true, true, true, FIELDS);
  }

  private static void move(
      Db db,
      World w,
      UUID id,
      int version,
      String from,
      String to,
      String actor,
      String outcome,
      String set) {
    script(
        db,
        """
        BEGIN;
        UPDATE integrations.partner_referral SET state = '%1$s', row_version = %2$d, %3$s,
            updated_at = now() WHERE tenant_id = '%4$s' AND id = '%5$s';
        INSERT INTO integrations.partner_referral_event VALUES ('%4$s', '%5$s', %2$d, '%6$s', '%1$s',
            '%7$s', %8$s, now());
        COMMIT;
        """
            .formatted(
                to,
                version,
                set,
                w.tenant(),
                id,
                from,
                actor,
                outcome == null ? "NULL" : "'" + outcome + "'"));
  }

  private static void claim(Db db, World w, UUID id, int version, String from, int attempt) {
    move(
        db,
        w,
        id,
        version,
        from,
        "DELIVERY_PENDING",
        "SYSTEM",
        null,
        "attempt_count = "
            + attempt
            + ", next_attempt_at = NULL, lease_until = now() + interval '1 minute'");
  }

  private static void rollbackQuietly(Db db) {
    try {
      script(db, "ROLLBACK;");
    } catch (DataAccessException ignored) {
      // nothing open
    }
  }

  private static void refused(Db db, Runnable action) {
    try {
      assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class);
    } finally {
      rollbackQuietly(db);
    }
  }

  private static final java.security.SecureRandom REFERENCE_RANDOM =
      new java.security.SecureRandom();

  private static int count(Db db, String table) {
    Integer rows =
        db.jdbc().queryForObject("SELECT count(*) FROM integrations." + table, Integer.class);
    return rows == null ? 0 : rows;
  }

  @Test
  void aCompleteSubmissionCommitsAndAnyMissingPieceIsRefused() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          submit(db, w);
          assertThat(count(db, "partner_referral")).isEqualTo(1);
          assertThat(count(db, "partner_referral_event")).isEqualTo(1);
          refused(db, () -> submit(db, w, w.subject(), "1".repeat(64), false, true, true, FIELDS));
          refused(db, () -> submit(db, w, w.subject(), "2".repeat(64), true, false, true, FIELDS));
          refused(db, () -> submit(db, w, w.subject(), "3".repeat(64), true, true, false, FIELDS));
          assertThat(count(db, "partner_referral")).isEqualTo(1);
        });
  }

  @Test
  void onlyTheEmployeesOwnSubjectMayConsentAndSubmit() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          refused(
              db, () -> submit(db, w, "someone-else", "4".repeat(64), true, true, true, FIELDS));
          assertThat(count(db, "partner_referral")).isZero();
        });
  }

  @Test
  void aProductThatIsNotLiveInTheEmployeesCountryCannotBeReferred() {
    withDatabase(
        "57",
        db -> {
          World paused = world(db, "CD", "PAUSED", "ACTIVE");
          World foreign = world(db, "RW", "ACTIVE", "ACTIVE");
          World suspended = world(db, "CD", "ACTIVE", "SUSPENDED");
          refused(db, () -> submit(db, paused));
          refused(db, () -> submit(db, foreign));
          refused(db, () -> submit(db, suspended));
          assertThat(count(db, "partner_referral")).isZero();
        });
  }

  @Test
  void aPreviewBecomesExactlyOneReferralForTheEmployee() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          submit(db, w, DIGEST_A);
          refused(db, () -> submit(db, w, DIGEST_A));
          submit(db, w, DIGEST_B);
          assertThat(count(db, "partner_referral")).isEqualTo(2);
        });
  }

  @Test
  void theDisclosureIsAnAllowListWithoutForbiddenOrDuplicateFields() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          refused(
              db,
              () ->
                  submit(
                      db,
                      w,
                      w.subject(),
                      "5".repeat(64),
                      true,
                      true,
                      true,
                      "ARRAY['REFERRAL_REFERENCE','PRODUCT_REFERENCE','PRODUCT_NAME','PARTNER_NAME',"
                          + "'EMPLOYEE_LEGAL_NAME','EMPLOYEE_EMAIL','EMPLOYEE_COUNTRY','PREFERRED_LANGUAGE',"
                          + "'CONSENT_TIMESTAMP','CONSENT_VERSION','SALARY']"));
          refused(
              db,
              () ->
                  submit(
                      db,
                      w,
                      w.subject(),
                      "6".repeat(64),
                      true,
                      true,
                      true,
                      "ARRAY['REFERRAL_REFERENCE','PRODUCT_REFERENCE','PRODUCT_NAME','PARTNER_NAME',"
                          + "'EMPLOYEE_LEGAL_NAME','EMPLOYEE_EMAIL','EMPLOYEE_COUNTRY','PREFERRED_LANGUAGE',"
                          + "'CONSENT_TIMESTAMP']"));
          refused(
              db,
              () ->
                  submit(
                      db,
                      w,
                      w.subject(),
                      "7".repeat(64),
                      true,
                      true,
                      true,
                      "ARRAY['REFERRAL_REFERENCE','REFERRAL_REFERENCE','PRODUCT_REFERENCE','PRODUCT_NAME',"
                          + "'PARTNER_NAME','EMPLOYEE_LEGAL_NAME','EMPLOYEE_EMAIL','EMPLOYEE_COUNTRY',"
                          + "'PREFERRED_LANGUAGE','CONSENT_TIMESTAMP','CONSENT_VERSION']"));
          assertThat(count(db, "partner_referral")).isZero();
        });
  }

  @Test
  void deliveryFollowsTheClosedLifecycleAndADeliveredReferralCanNeverBeWithdrawn() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          UUID id = submit(db, w);
          claim(db, w, id, 2, "SUBMITTED", 1);
          // a state cannot be skipped: DELIVERED needs the receipt and the lease
          refused(
              db,
              () ->
                  move(
                      db,
                      w,
                      id,
                      3,
                      "DELIVERY_PENDING",
                      "DELIVERED",
                      "SYSTEM",
                      "PROVIDER_ACKNOWLEDGED",
                      "next_attempt_at = NULL, lease_until = NULL"));
          script(
              db,
              """
          BEGIN;
          INSERT INTO integrations.partner_referral_attempt VALUES ('%2$s', '%1$s', 1, now(), now(),
              'PROVIDER_ACKNOWLEDGED', 200);
          INSERT INTO integrations.partner_referral_ack VALUES ('%2$s', '%1$s', 'SYN-ACK-0001', now());
          UPDATE integrations.partner_referral SET state = 'DELIVERED', row_version = 3,
              lease_until = NULL, updated_at = now() WHERE id = '%1$s';
          INSERT INTO integrations.partner_referral_event VALUES ('%2$s', '%1$s', 3,
              'DELIVERY_PENDING', 'DELIVERED', 'SYSTEM', 'PROVIDER_ACKNOWLEDGED', now());
          COMMIT;
          """
                  .formatted(id, w.tenant()));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state FROM integrations.partner_referral", String.class))
              .isEqualTo("DELIVERED");
          refused(
              db,
              () ->
                  move(
                      db,
                      w,
                      id,
                      4,
                      "DELIVERED",
                      "WITHDRAWN",
                      "EMPLOYEE",
                      "WITHDRAWN_BEFORE_DELIVERY",
                      "next_attempt_at = NULL"));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state FROM integrations.partner_referral", String.class))
              .isEqualTo("DELIVERED");
        });
  }

  @Test
  void anAttemptInFlightCannotBeWithdrawnAndAnUncertainDeliveryIsNeverWithdrawable() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          UUID id = submit(db, w);
          claim(db, w, id, 2, "SUBMITTED", 1);
          refused(
              db,
              () ->
                  move(
                      db,
                      w,
                      id,
                      3,
                      "DELIVERY_PENDING",
                      "WITHDRAWN",
                      "EMPLOYEE",
                      "WITHDRAWN_BEFORE_DELIVERY",
                      "next_attempt_at = NULL, lease_until = NULL"));
          script(
              db,
              """
          BEGIN;
          INSERT INTO integrations.partner_referral_attempt VALUES ('%2$s', '%1$s', 1, now(), now(),
              'UNCERTAIN_NOT_RESENT', NULL);
          UPDATE integrations.partner_referral SET state = 'DELIVERY_UNKNOWN', row_version = 3,
              lease_until = NULL, updated_at = now() WHERE id = '%1$s';
          INSERT INTO integrations.partner_referral_event VALUES ('%2$s', '%1$s', 3,
              'DELIVERY_PENDING', 'DELIVERY_UNKNOWN', 'SYSTEM', 'UNCERTAIN_NOT_RESENT', now());
          COMMIT;
          """
                  .formatted(id, w.tenant()));
          refused(
              db,
              () ->
                  move(
                      db,
                      w,
                      id,
                      4,
                      "DELIVERY_UNKNOWN",
                      "WITHDRAWN",
                      "EMPLOYEE",
                      "WITHDRAWN_BEFORE_DELIVERY",
                      "next_attempt_at = NULL"));
          refused(db, () -> claim(db, w, id, 4, "DELIVERY_UNKNOWN", 2));
        });
  }

  @Test
  void aSubmittedReferralCanBeWithdrawnOnceAndNeverClaimedAfterwards() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          UUID id = submit(db, w);
          move(
              db,
              w,
              id,
              2,
              "SUBMITTED",
              "WITHDRAWN",
              "EMPLOYEE",
              "WITHDRAWN_BEFORE_DELIVERY",
              "next_attempt_at = NULL");
          refused(db, () -> claim(db, w, id, 3, "WITHDRAWN", 1));
          refused(
              db,
              () ->
                  script(
                      db,
                      "INSERT INTO integrations.partner_referral_ack VALUES ('"
                          + w.tenant()
                          + "', '"
                          + id
                          + "', 'SYN-ACK-0002', now());"));
          assertThat(count(db, "partner_referral_event")).isEqualTo(2);
        });
  }

  @Test
  void aRowVersionThatDoesNotMatchTheHistoryIsRefusedAtCommit() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          UUID id = submit(db, w);
          refused(
              db,
              () ->
                  script(
                      db,
                      """
          BEGIN;
          UPDATE integrations.partner_referral SET state = 'WITHDRAWN', row_version = 2,
              next_attempt_at = NULL, updated_at = now() WHERE id = '%s';
          COMMIT;
          """
                          .formatted(id)));
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT state FROM integrations.partner_referral", String.class))
              .isEqualTo("SUBMITTED");
        });
  }

  @Test
  void evidenceIsImmutableAndNothingIsEverDeleted() {
    withDatabase(
        "57",
        db -> {
          World w = world(db);
          submit(db, w);
          for (String sql :
              new String[] {
                "UPDATE integrations.partner_referral_disclosure SET field_names = field_names",
                "DELETE FROM integrations.partner_referral_disclosure",
                "TRUNCATE integrations.partner_referral_disclosure",
                "UPDATE integrations.partner_referral_consent SET language = 'fr'",
                "DELETE FROM integrations.partner_referral_consent",
                "TRUNCATE integrations.partner_referral_consent",
                "UPDATE integrations.partner_referral_event SET outcome = 'RETRIES_EXHAUSTED'",
                "DELETE FROM integrations.partner_referral_event",
                "TRUNCATE integrations.partner_referral_event",
                "DELETE FROM integrations.partner_referral",
                "TRUNCATE integrations.partner_referral CASCADE",
                "UPDATE integrations.partner_referral SET employee_id = gen_random_uuid()",
                "UPDATE integrations.partner_referral SET preview_sha256 = repeat('c', 64)"
              }) {
            assertThatThrownBy(() -> db.jdbc().execute(sql))
                .as(sql)
                .isInstanceOf(DataAccessException.class);
          }
          assertThat(count(db, "partner_referral")).isEqualTo(1);
          assertThat(count(db, "partner_referral_disclosure")).isEqualTo(1);
        });
  }

  @Test
  void theRollbackRefusesOnceReferralsExistAndOtherwiseRestoresV56Exactly() {
    withDatabase(
        "56",
        db -> {
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          Flyway.configure()
              .dataSource(db.url(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .target("57")
              .load()
              .migrate();
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isNotEqualTo(before);
          World w = world(db);
          submit(db, w);
          String rollback = resource("db/rollback/V57__rollback.sql");
          assertThatThrownBy(() -> script(db, rollback))
              .hasMessageContaining("V57 rollback refused: partner-referral rows exist");
          rollbackQuietly(db);
          assertThat(count(db, "partner_referral")).isEqualTo(1);
        });
    withDatabase(
        "56",
        db -> {
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          Flyway.configure()
              .dataSource(db.url(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .target("57")
              .load()
              .migrate();
          script(db, resource("db/rollback/V57__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(before);
        });
  }
}
