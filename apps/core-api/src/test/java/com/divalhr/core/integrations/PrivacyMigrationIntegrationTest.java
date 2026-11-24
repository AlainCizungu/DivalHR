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
 * LAUNCH-001 V58 directly against PostgreSQL (the database is the final authority): governed policy
 * versions, stamping at creation, holds, purge guards, immutable evidence and the refusing rollback
 * that restores V57 exactly. No retention duration is shipped: every duration below is a SYNTHETIC
 * test value, never a legal rule.
 */
@IntegrationTest
class PrivacyMigrationIntegrationTest {

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

  private static final String CHECKLIST_COMPLETE =
      "true, true, true, true, true, true, true, true, 'SYN-REVIEW-1'";

  /** Four rules, all MINIMIZE/ERASE after the given synthetic number of days from closing. */
  private static String rules(UUID id, int days, String consentAction) {
    String t = "'REFERRAL_CLOSED'";
    return """
        INSERT INTO integrations.privacy_policy_rule VALUES
          ('%1$s', 'PARTNER_REFERRAL_DISCLOSURE', 'CONSENTED_INTRODUCTION', 'ERASE', %2$s, %3$d),
          ('%1$s', 'PARTNER_REFERRAL_CONSENT', 'CONSENT_EVIDENCE', '%4$s', %2$s, %3$d),
          ('%1$s', 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS', 'DELIVERY_TRUTH', 'ERASE', %2$s, %3$d),
          ('%1$s', 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE', 'LIFECYCLE_EVIDENCE', 'HOLD', %2$s, NULL);
        """
        .formatted(id, t, days, consentAction);
  }

  private static UUID activePolicy(Db db, String country, int days, String consentAction) {
    UUID id = UUID.randomUUID();
    // The rules need the id before the row exists, so build the transaction around a fixed id.
    String rules = rules(id, days, consentAction);
    script(
        db,
        """
        BEGIN;
        INSERT INTO integrations.privacy_policy VALUES ('%1$s', '%2$s', '%3$s', 'DRAFT', 1, 'W1',
            'SYNTHETIC-SOURCE', current_date, current_date - 30, NULL, 'test', now(), now());
        %4$s
        INSERT INTO integrations.privacy_policy_checklist VALUES ('%1$s', %5$s, now());
        INSERT INTO integrations.privacy_policy_event VALUES ('%1$s', 1, NULL, 'DRAFT', now());
        COMMIT;
        BEGIN;
        UPDATE integrations.privacy_policy SET state = 'APPROVED', row_version = 2, updated_at = now() WHERE id = '%1$s';
        INSERT INTO integrations.privacy_policy_event VALUES ('%1$s', 2, 'DRAFT', 'APPROVED', now());
        COMMIT;
        BEGIN;
        UPDATE integrations.privacy_policy SET state = 'ACTIVE', row_version = 3, updated_at = now() WHERE id = '%1$s';
        INSERT INTO integrations.privacy_policy_event VALUES ('%1$s', 3, 'APPROVED', 'ACTIVE', now());
        COMMIT;
        """
            .formatted(id, ref("PL-"), country, rules, CHECKLIST_COMPLETE));
    return id;
  }

  private static void deliver(Db db, World w, UUID id) {
    claim(db, w, id, 2, "SUBMITTED", 1);
    script(
        db,
        """
        BEGIN;
        INSERT INTO integrations.partner_referral_attempt VALUES ('%2$s', '%1$s', 1, now(), now(),
            'PROVIDER_ACKNOWLEDGED', 200);
        INSERT INTO integrations.partner_referral_ack VALUES ('%2$s', '%1$s', 'SYN-ACK-0001', now());
        UPDATE integrations.partner_referral SET state = 'DELIVERED', row_version = 3, lease_until = NULL,
            updated_at = now() WHERE id = '%1$s';
        INSERT INTO integrations.partner_referral_event VALUES ('%2$s', '%1$s', 3, 'DELIVERY_PENDING',
            'DELIVERED', 'SYSTEM', 'PROVIDER_ACKNOWLEDGED', now());
        COMMIT;
        """
            .formatted(id, w.tenant()));
  }

  private static String stamp(Db db, UUID id) {
    return db.jdbc()
        .queryForObject(
            "SELECT status FROM integrations.privacy_referral_stamp WHERE referral_id = ?::uuid",
            String.class,
            id.toString());
  }

  @Test
  void aPolicyVersionNeedsItsCompleteChecklistAndItsFourRulesAndActivatesOncePerCountry() {
    withDatabase(
        "58",
        db -> {
          UUID draft = UUID.randomUUID();
          // an incomplete checklist stays DRAFT: approval is refused
          script(
              db,
              """
              BEGIN;
              INSERT INTO integrations.privacy_policy VALUES ('%1$s', '%2$s', 'CD', 'DRAFT', 1, 'W1',
                  'SYNTHETIC-SOURCE', current_date, current_date - 1, NULL, 'test', now(), now());
              %3$s
              INSERT INTO integrations.privacy_policy_checklist VALUES ('%1$s', true, true, false, true, true,
                  true, true, true, NULL, now());
              INSERT INTO integrations.privacy_policy_event VALUES ('%1$s', 1, NULL, 'DRAFT', now());
              COMMIT;
              """
                  .formatted(draft, ref("PL-"), rules(draft, 30, "ERASE")));
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      UPDATE integrations.privacy_policy SET state = 'APPROVED', row_version = 2,
                          updated_at = now() WHERE id = '%1$s';
                      INSERT INTO integrations.privacy_policy_event VALUES ('%1$s', 2, 'DRAFT', 'APPROVED', now());
                      COMMIT;
                      """
                          .formatted(draft)));
          // a complete version activates; a second active version for the same country and period
          // is refused
          UUID first = activePolicy(db, "CD", 30, "ERASE");
          assertThatThrownBy(() -> activePolicy(db, "CD", 40, "ERASE"))
              .isInstanceOf(DataAccessException.class);
          rollbackQuietly(db);
          // an active version is never edited, and a retired one never returns
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE integrations.privacy_policy SET source_reference = 'CHANGED' WHERE id = ?::uuid",
                          first.toString()));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE integrations.privacy_policy_rule SET duration_days = 1 WHERE policy_id = ?::uuid",
                          first.toString()));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.privacy_policy_event WHERE policy_id = ?::uuid",
                          first.toString()));
        });
  }

  @Test
  void aRuleSetThatWouldLeaveTheDisclosureOutlivingTheConsentIsRefused() {
    withDatabase(
        "58",
        db -> {
          UUID id = UUID.randomUUID();
          // ERASE of the consent after 30 days while the disclosure is kept 90 days: orphaning
          String orphaning =
              """
              INSERT INTO integrations.privacy_policy_rule VALUES
                ('%1$s', 'PARTNER_REFERRAL_DISCLOSURE', 'CONSENTED_INTRODUCTION', 'ERASE', 'REFERRAL_CLOSED', 90),
                ('%1$s', 'PARTNER_REFERRAL_CONSENT', 'CONSENT_EVIDENCE', 'ERASE', 'REFERRAL_CLOSED', 30),
                ('%1$s', 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS', 'DELIVERY_TRUTH', 'ERASE', 'REFERRAL_CLOSED', 30),
                ('%1$s', 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE', 'LIFECYCLE_EVIDENCE', 'HOLD', 'REFERRAL_CLOSED', NULL);
              """
                  .formatted(id);
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      INSERT INTO integrations.privacy_policy VALUES ('%1$s', '%2$s', 'CD', 'DRAFT', 1, 'W1',
                          'SYNTHETIC-SOURCE', current_date, current_date - 1, NULL, 'test', now(), now());
                      %3$s
                      INSERT INTO integrations.privacy_policy_checklist VALUES ('%1$s', %4$s, now());
                      INSERT INTO integrations.privacy_policy_event VALUES ('%1$s', 1, NULL, 'DRAFT', now());
                      COMMIT;
                      """
                          .formatted(id, ref("PL-"), orphaning, CHECKLIST_COMPLETE)));
          assertThat(count(db, "privacy_policy")).isZero();
        });
  }

  @Test
  void aNewReferralIsStampedWithTheActiveVersionOrStaysPendingAndAnExistingOneNeverChanges() {
    withDatabase(
        "58",
        db -> {
          World w = world(db);
          UUID pending = submit(db, w, DIGEST_A);
          assertThat(stamp(db, pending)).isEqualTo("POLICY_PENDING");
          activePolicy(db, "CD", 30, "ERASE");
          UUID stamped = submit(db, w, DIGEST_B);
          assertThat(stamp(db, stamped)).isEqualTo("STAMPED");
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM integrations.privacy_referral_stamp_rule WHERE referral_id = ?::uuid",
                          Integer.class,
                          stamped.toString()))
              .isEqualTo(4);
          // the existing referral stays pending, and a stamp never moves outside an application
          assertThat(stamp(db, pending)).isEqualTo("POLICY_PENDING");
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE integrations.privacy_referral_stamp SET status = 'STAMPED' WHERE referral_id = ?::uuid",
                          pending.toString()));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE integrations.privacy_referral_stamp_rule SET duration_days = 1 WHERE referral_id = ?::uuid",
                          stamped.toString()));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.privacy_referral_stamp WHERE referral_id = ?::uuid",
                          stamped.toString()));
        });
  }

  @Test
  void aHoldIsAppendOnlyAndOtherDocumentedNeedsItsNote() {
    withDatabase(
        "58",
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
                      INSERT INTO integrations.privacy_hold VALUES ('%1$s', '%2$s', gen_random_uuid(), '%3$s',
                          'OTHER_DOCUMENTED', NULL, 'ACTIVE', 'test', now(), NULL, NULL, current_date);
                      COMMIT;
                      """
                          .formatted(w.tenant(), id, ref("PH-"))));
          UUID hold = UUID.randomUUID();
          script(
              db,
              """
              BEGIN;
              INSERT INTO integrations.privacy_hold VALUES ('%1$s', '%2$s', '%3$s', '%4$s', 'DISPUTE',
                  decode(repeat('ab', 60), 'hex'), 'ACTIVE', 'test', now(), NULL, NULL, current_date);
              INSERT INTO integrations.privacy_hold_event VALUES ('%3$s', 1, 'PLACED', now());
              COMMIT;
              """
                  .formatted(w.tenant(), id, hold, ref("PH-")));
          // a release without its event, an edit and a delete are refused
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      UPDATE integrations.privacy_hold SET state = 'RELEASED', released_by = 'test',
                          released_at = now() WHERE id = '%1$s';
                      COMMIT;
                      """
                          .formatted(hold)));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE integrations.privacy_hold SET reason = 'LEGAL_PROCEEDING' WHERE id = ?::uuid",
                          hold.toString()));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.privacy_hold WHERE id = ?::uuid",
                          hold.toString()));
          script(
              db,
              """
              BEGIN;
              UPDATE integrations.privacy_hold SET state = 'RELEASED', released_by = 'test', released_at = now()
                  WHERE id = '%1$s';
              INSERT INTO integrations.privacy_hold_event VALUES ('%1$s', 2, 'RELEASED', now());
              COMMIT;
              """
                  .formatted(hold));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "UPDATE integrations.privacy_hold SET state = 'ACTIVE', released_by = NULL, released_at = NULL WHERE id = ?::uuid",
                          hold.toString()));
        });
  }

  @Test
  void aRequestFollowsTheClosedLifecycleOwnedByTheEmployeeWithOneOpenPerScope() {
    withDatabase(
        "58",
        db -> {
          World w = world(db);
          UUID other = UUID.randomUUID();
          script(
              db,
              """
              SET session_replication_role = replica;
              INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name, search_key,
                  created_at, created_by) VALUES ('%1$s', '%2$s', 'EOTHER', 'Other', 'Employee', ' other employee ', now(), 'test');
              RESET session_replication_role;
              """
                  .formatted(other, w.tenant()));
          UUID referral = submit(db, w);
          UUID request = UUID.randomUUID();
          script(
              db,
              """
              BEGIN;
              INSERT INTO integrations.privacy_request VALUES ('%1$s', '%2$s', '%3$s', '%4$s', 'ACCESS', NULL,
                  'SUBMITTED', 1, NULL, now(), now());
              INSERT INTO integrations.privacy_request_event VALUES ('%1$s', '%2$s', 1, NULL, 'SUBMITTED',
                  'EMPLOYEE', NULL, now());
              COMMIT;
              """
                  .formatted(w.tenant(), request, ref("PQ-"), w.employee()));
          // a second open request of the same type and scope, and another employee's referral, are
          // refused
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      INSERT INTO integrations.privacy_request VALUES ('%1$s', gen_random_uuid(), '%2$s', '%3$s',
                          'ACCESS', NULL, 'SUBMITTED', 1, NULL, now(), now());
                      COMMIT;
                      """
                          .formatted(w.tenant(), ref("PQ-"), w.employee())));
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      INSERT INTO integrations.privacy_request VALUES ('%1$s', gen_random_uuid(), '%2$s', '%3$s',
                          'ERASURE', '%4$s', 'SUBMITTED', 1, NULL, now(), now());
                      COMMIT;
                      """
                          .formatted(w.tenant(), ref("PQ-"), other, referral)));
          // the lifecycle is closed: APPROVED cannot be reached from SUBMITTED, and a refusal needs
          // its reason
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      UPDATE integrations.privacy_request SET state = 'APPROVED', row_version = 2, updated_at = now()
                          WHERE id = '%1$s';
                      INSERT INTO integrations.privacy_request_event VALUES ('%2$s', '%1$s', 2, 'SUBMITTED',
                          'APPROVED', 'PLATFORM', NULL, now());
                      COMMIT;
                      """
                          .formatted(request, w.tenant())));
          script(
              db,
              """
              BEGIN;
              UPDATE integrations.privacy_request SET state = 'UNDER_REVIEW', row_version = 2, updated_at = now()
                  WHERE id = '%1$s';
              INSERT INTO integrations.privacy_request_event VALUES ('%2$s', '%1$s', 2, 'SUBMITTED',
                  'UNDER_REVIEW', 'PLATFORM', NULL, now());
              COMMIT;
              """
                  .formatted(request, w.tenant()));
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      UPDATE integrations.privacy_request SET state = 'REFUSED', row_version = 3, updated_at = now()
                          WHERE id = '%1$s';
                      INSERT INTO integrations.privacy_request_event VALUES ('%2$s', '%1$s', 3, 'UNDER_REVIEW',
                          'REFUSED', 'PLATFORM', NULL, now());
                      COMMIT;
                      """
                          .formatted(request, w.tenant())));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.privacy_request_event WHERE request_id = ?::uuid",
                          request.toString()));
        });
  }

  @Test
  void aPurgeItemIsAcceptedOnlyWhenTheStampedRuleTheClosureTheDurationAndTheHoldsAllowIt() {
    withDatabase(
        "58",
        db -> {
          World w = world(db);
          UUID pending = submit(db, w, DIGEST_A);
          activePolicy(db, "CD", 30, "ERASE");
          UUID id = submit(db, w, DIGEST_B);
          UUID purgeNow = purge(db, "now()");
          // a pending-policy referral, an open referral and an unelapsed duration are refused
          refused(db, () -> item(db, w, purgeNow, pending, "PARTNER_REFERRAL_DISCLOSURE", "ERASE"));
          refused(db, () -> item(db, w, purgeNow, id, "PARTNER_REFERRAL_DISCLOSURE", "ERASE"));
          deliver(db, w, id);
          refused(db, () -> item(db, w, purgeNow, id, "PARTNER_REFERRAL_DISCLOSURE", "ERASE"));
          // after the duration, a purge dated later accepts the stamped action only, and never HOLD
          // or a pending class
          UUID later = purge(db, "now() + interval '31 days'");
          refused(db, () -> item(db, w, later, id, "PARTNER_REFERRAL_DISCLOSURE", "MINIMIZE"));
          refused(
              db, () -> item(db, w, later, id, "PARTNER_REFERRAL_LIFECYCLE_EVIDENCE", "MINIMIZE"));
          // an active hold blocks every item
          UUID hold = UUID.randomUUID();
          script(
              db,
              """
              BEGIN;
              INSERT INTO integrations.privacy_hold VALUES ('%1$s', '%2$s', '%3$s', '%4$s', 'DISPUTE',
                  decode(repeat('ab', 60), 'hex'), 'ACTIVE', 'test', now(), NULL, NULL, current_date);
              INSERT INTO integrations.privacy_hold_event VALUES ('%3$s', 1, 'PLACED', now());
              COMMIT;
              """
                  .formatted(w.tenant(), id, hold, ref("PH-")));
          refused(db, () -> item(db, w, later, id, "PARTNER_REFERRAL_DISCLOSURE", "ERASE"));
          script(
              db,
              """
              BEGIN;
              UPDATE integrations.privacy_hold SET state = 'RELEASED', released_by = 'test', released_at = now()
                  WHERE id = '%1$s';
              INSERT INTO integrations.privacy_hold_event VALUES ('%1$s', 2, 'RELEASED', now());
              COMMIT;
              """
                  .formatted(hold));
          item(db, w, later, id, "PARTNER_REFERRAL_DISCLOSURE", "ERASE");
          assertThat(count(db, "privacy_purge_item")).isEqualTo(1);
        });
  }

  @Test
  void evidenceIsDeletedOnlyInsideAnExecutingPurgeWithItsItemAndTheConsentGoesLast() {
    withDatabase(
        "58",
        db -> {
          World w = world(db);
          activePolicy(db, "CD", 30, "ERASE");
          UUID id = submit(db, w, DIGEST_B);
          deliver(db, w, id);
          // no purge, no deletion
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.partner_referral_disclosure WHERE referral_id = ?::uuid",
                          id.toString()));
          UUID purge = purge(db, "now() + interval '31 days'");
          item(db, w, purge, id, "PARTNER_REFERRAL_DISCLOSURE", "ERASE");
          item(db, w, purge, id, "PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS", "ERASE");
          item(db, w, purge, id, "PARTNER_REFERRAL_CONSENT", "ERASE");
          // the consent cannot go while its disclosure and delivery evidence remain
          refused(
              db,
              () ->
                  script(
                      db,
                      """
                      BEGIN;
                      SET LOCAL divalhr.privacy_purge = '%1$s';
                      DELETE FROM integrations.partner_referral_consent WHERE referral_id = '%2$s';
                      COMMIT;
                      """
                          .formatted(purge, id)));
          script(
              db,
              """
              BEGIN;
              SET LOCAL divalhr.privacy_purge = '%1$s';
              DELETE FROM integrations.partner_referral_disclosure WHERE referral_id = '%2$s';
              DELETE FROM integrations.partner_referral_ack WHERE referral_id = '%2$s';
              DELETE FROM integrations.partner_referral_attempt WHERE referral_id = '%2$s';
              DELETE FROM integrations.partner_referral_consent WHERE referral_id = '%2$s';
              UPDATE integrations.privacy_purge SET state = 'COMPLETED', item_count = 3, completed_at = now()
                  WHERE id = '%1$s';
              COMMIT;
              """
                  .formatted(purge, id));
          assertThat(count(db, "partner_referral_disclosure")).isZero();
          assertThat(count(db, "partner_referral_consent")).isZero();
          assertThat(count(db, "partner_referral")).isEqualTo(1);
          // the referral header and its history stay, and an update of any evidence is still
          // refused
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.partner_referral_event WHERE referral_id = ?::uuid",
                          id.toString()));
          refused(
              db,
              () ->
                  db.jdbc()
                      .update(
                          "DELETE FROM integrations.privacy_purge_item WHERE referral_id = ?::uuid",
                          id.toString()));
        });
  }

  private static UUID purge(Db db, String createdAt) {
    UUID preview = UUID.randomUUID();
    UUID purge = UUID.randomUUID();
    script(
        db,
        """
        BEGIN;
        INSERT INTO integrations.privacy_preview VALUES ('%1$s', '%3$s', 'PURGE', 'test', '%5$s', 'OPEN',
            %4$s, %4$s + interval '15 minutes', NULL, 'ERASE', NULL);
        INSERT INTO integrations.privacy_purge VALUES ('%2$s', '%6$s', '%1$s', 'test', 'EXECUTING', NULL,
            %4$s, NULL);
        COMMIT;
        """
            .formatted(preview, purge, ref("PV-"), createdAt, DIGEST_A, ref("PG-")));
    return purge;
  }

  private static void item(
      Db db, World w, UUID purge, UUID referral, String dataClass, String action) {
    script(
        db,
        """
        BEGIN;
        INSERT INTO integrations.privacy_purge_item VALUES ('%1$s', '%2$s', '%3$s', '%4$s', '%5$s', now());
        COMMIT;
        """
            .formatted(purge, w.tenant(), referral, dataClass, action));
  }

  @Test
  void theRollbackRefusesOnceGovernanceEvidenceExistsAndOtherwiseRestoresV57Exactly() {
    withDatabase(
        "57",
        db -> {
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          Flyway.configure()
              .dataSource(db.url(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .target("58")
              .load()
              .migrate();
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isNotEqualTo(before);
          World w = world(db);
          submit(db, w);
          activePolicy(db, "CD", 30, "ERASE");
          assertThatThrownBy(() -> script(db, resource("db/rollback/V58__rollback.sql")))
              .hasMessageContaining("V58 rollback refused: LAUNCH-001 governance evidence exists");
          rollbackQuietly(db);
          assertThat(count(db, "privacy_policy")).isEqualTo(1);
        });
    withDatabase(
        "57",
        db -> {
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          World w = world(db);
          submit(db, w);
          Flyway.configure()
              .dataSource(db.url(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .target("58")
              .load()
              .migrate();
          assertThat(stampOf(db)).isEqualTo("POLICY_PENDING");
          script(db, resource("db/rollback/V58__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(before);
        });
  }

  private static String stampOf(Db db) {
    return db.jdbc()
        .queryForObject("SELECT status FROM integrations.privacy_referral_stamp", String.class);
  }
}
