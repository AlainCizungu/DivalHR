package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * MVP-120D V55 directly against PostgreSQL (the database is the final authority): the closed
 * catalogues, the complete-at-commit submission, the transition table, terminal immutability, the
 * checklist gate on approval, the immutable contiguous history and the refusing rollback that
 * restores V54 exactly.
 */
@IntegrationTest
class PartnerApplicationMigrationIntegrationTest {

  private static final List<String> STATES =
      List.of(
          "SUBMITTED",
          "UNDER_REVIEW",
          "MORE_INFORMATION_REQUIRED",
          "APPROVED_FOR_ONBOARDING",
          "DECLINED",
          "WITHDRAWN");

  private static final List<String> CATEGORIES =
      List.of(
          "BANK",
          "MICROFINANCE",
          "INSURANCE",
          "HEALTH_BENEFITS",
          "TELECOM",
          "MOBILE_MONEY_PAYMENT",
          "PENSION_SAVINGS",
          "OTHER_REGULATED_PROVIDER");

  /** The integrations schema: tables, columns, indexes, constraints, triggers and functions. */
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

  /**
   * A complete application in one transaction: the row, a capability, the consent, the seven
   * checklist rows and the submission event (the deferred completeness trigger fires at commit).
   */
  private static final String MAKE =
      """
      CREATE FUNCTION public.mk_partner(ref text, cat text, reg text, caps boolean,
                                        consent boolean, checks integer, status text,
                                        result text) RETURNS uuid LANGUAGE plpgsql AS $mk$
      DECLARE
        id uuid := gen_random_uuid();
        c text;
      BEGIN
        IF status <> 'SUBMITTED' THEN
          SET LOCAL session_replication_role = replica;
        END IF;
        INSERT INTO integrations.partner_application
          (id, reference, status, row_version, primary_category, legal_name,
           country_of_registration, countries_of_operation, website, contact_role,
           supported_languages, api_availability, regulator_name, service_summary,
           confidential_ciphertext, email_digest, organization_fingerprint, submission_locale,
           submitted_at, updated_at)
        VALUES (id, ref, status, 0, cat, 'Synthetic Partner', 'CD', ARRAY['CD', 'RW'],
                'https://example.org', 'CTO', ARRAY['en', 'fr'], 'PLANNED', reg,
                'Synthetic service summary for the migration test',
                decode(repeat('ab', 50), 'hex'), repeat('a', 64), repeat('b', 64), 'en',
                now(), now());
        IF caps THEN
          INSERT INTO integrations.partner_application_capability VALUES (id, 'SALARY_ADVANCE');
        END IF;
        IF consent THEN
          INSERT INTO integrations.partner_application_consent
            VALUES (id, '2026-11', '2026-11', true, 'en', now());
        END IF;
        FOREACH c IN ARRAY (ARRAY['BUSINESS_IDENTITY', 'REGISTRATION_REFERENCE',
            'REGULATORY_INFORMATION', 'SERVICE_SCOPE', 'PRIVACY_SECURITY_CONTACT',
            'TECHNICAL_API_READINESS', 'COMMERCIAL_DISCUSSION'])[1:checks] LOOP
          INSERT INTO integrations.partner_application_check
            VALUES (id, c, result, CASE WHEN result = 'PENDING' THEN NULL ELSE 'admin' END,
                    NULL, 0, now());
        END LOOP;
        INSERT INTO integrations.partner_application_event
          VALUES (id, 1, 'SUBMITTED', NULL, 'SUBMITTED', NULL, NULL, NULL, 'APPLICANT', NULL, now());
        RETURN id;
      END
      $mk$
      """;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(String target, Consumer<Db> body) {
    String database = "pa120d_" + UUID.randomUUID().toString().replace("-", "");
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
      Db db =
          new Db(
              url,
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      url, postgres.getUsername(), postgres.getPassword())));
      script(db, MAKE);
      body.accept(db);
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

  /** Runs a script as written (JDBC escape processing would rewrite braces in the bodies). */
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

  private static UUID make(Db db, String reference, String category, String regulator) {
    return make(db, reference, category, regulator, true, true, 7, "SUBMITTED", "PENDING");
  }

  private static UUID make(
      Db db,
      String reference,
      String category,
      String regulator,
      boolean capabilities,
      boolean consent,
      int checks,
      String status,
      String result) {
    return db.jdbc()
        .queryForObject(
            "SELECT public.mk_partner(?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.class,
            reference,
            category,
            regulator,
            capabilities,
            consent,
            checks,
            status,
            result);
  }

  private static String reference(int n) {
    return "PA-" + String.format("%012d", n);
  }

  @Test
  void everyPartnerCategoryCanBeSubmittedAndTheRegulatoryFieldIsRequiredWhereDecided() {
    withDatabase(
        "55",
        db -> {
          int n = 1;
          for (String category : CATEGORIES) {
            boolean regulated =
                List.of(
                        "BANK",
                        "MICROFINANCE",
                        "INSURANCE",
                        "TELECOM",
                        "MOBILE_MONEY_PAYMENT",
                        "PENSION_SAVINGS")
                    .contains(category);
            make(db, reference(n++), category, "Regulator " + category);
            if (regulated) {
              assertThatThrownBy(() -> make(db, reference(900), category, null))
                  .isInstanceOf(DataAccessException.class)
                  .hasMessageContaining("integrations_partner_application_regulated");
            } else {
              make(db, reference(n++), category, null);
            }
          }
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM integrations.partner_application", Integer.class))
              .isGreaterThanOrEqualTo(CATEGORIES.size());
        });
  }

  @Test
  void aSubmissionMustBeCompleteAtCommitAndTheCataloguesAreClosed() {
    withDatabase(
        "55",
        db -> {
          assertThatThrownBy(
                  () ->
                      make(db, reference(1), "BANK", "BCC", true, false, 7, "SUBMITTED", "PENDING"))
              .hasMessageContaining("privacy notice and consent");
          assertThatThrownBy(
                  () ->
                      make(db, reference(2), "BANK", "BCC", false, true, 7, "SUBMITTED", "PENDING"))
              .hasMessageContaining("at least one capability");
          assertThatThrownBy(
                  () ->
                      make(db, reference(3), "BANK", "BCC", true, true, 3, "SUBMITTED", "PENDING"))
              .hasMessageContaining("complete due-diligence checklist");
          assertThatThrownBy(() -> make(db, "PA-ILOU00000000", "BANK", "BCC"))
              .hasMessageContaining("integrations_partner_application_reference");
          assertThatThrownBy(() -> make(db, reference(4), "CRYPTO", "BCC"))
              .hasMessageContaining("integrations_partner_application_category");
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM integrations.partner_application", Integer.class))
              .isZero();
        });
  }

  @Test
  void everyStatusTransitionPairIsEnforcedByTheDatabase() {
    withDatabase(
        "55",
        db -> {
          int n = 1;
          for (String from : STATES) {
            for (String to : STATES) {
              if (from.equals(to)) {
                continue;
              }
              boolean allowed =
                  switch (from) {
                    case "SUBMITTED" -> List.of("UNDER_REVIEW", "WITHDRAWN").contains(to);
                    case "UNDER_REVIEW" ->
                        List.of(
                                "MORE_INFORMATION_REQUIRED",
                                "APPROVED_FOR_ONBOARDING",
                                "DECLINED",
                                "WITHDRAWN")
                            .contains(to);
                    case "MORE_INFORMATION_REQUIRED" ->
                        List.of("UNDER_REVIEW", "DECLINED", "WITHDRAWN").contains(to);
                    default -> false;
                  };
              UUID id = make(db, reference(n++), "BANK", "BCC", true, true, 7, from, "SATISFIED");
              String update =
                  "UPDATE integrations.partner_application SET status = '"
                      + to
                      + "', row_version = 1, updated_at = now() WHERE id = '"
                      + id
                      + "'";
              if (allowed) {
                db.jdbc().update(update);
                assertThat(
                        db.jdbc()
                            .queryForObject(
                                "SELECT status FROM integrations.partner_application WHERE id = ?",
                                String.class,
                                id))
                    .isEqualTo(to);
              } else {
                assertThatThrownBy(() -> db.jdbc().update(update))
                    .as(from + " -> " + to)
                    .isInstanceOf(DataAccessException.class);
              }
            }
          }
        });
  }

  @Test
  void approvalNeedsEveryMandatoryCheckSatisfiedAndTerminalRecordsAreFrozen() {
    withDatabase(
        "55",
        db -> {
          UUID pending =
              make(db, reference(1), "BANK", "BCC", true, true, 7, "UNDER_REVIEW", "PENDING");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE integrations.partner_application SET status ="
                                  + " 'APPROVED_FOR_ONBOARDING', row_version = 1, updated_at = now()"
                                  + " WHERE id = ?",
                              pending))
              .hasMessageContaining("mandatory due-diligence check must be satisfied");
          // A regulated category can never mark its regulatory information not applicable.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE integrations.partner_application_check SET result ="
                                  + " 'NOT_APPLICABLE', actor_subject = 'admin', row_version = 1,"
                                  + " updated_at = now() WHERE application_id = ? AND check_code ="
                                  + " 'REGULATORY_INFORMATION'",
                              pending))
              .hasMessageContaining("applicable to a regulated category");
          // The commercial-discussion check is advisory and never blocks approval.
          UUID ready =
              make(db, reference(2), "BANK", "BCC", true, true, 6, "UNDER_REVIEW", "SATISFIED");
          db.jdbc()
              .update(
                  "INSERT INTO integrations.partner_application_check VALUES (?,"
                      + " 'COMMERCIAL_DISCUSSION', 'PENDING', NULL, NULL, 0, now())",
                  ready);
          db.jdbc()
              .update(
                  "UPDATE integrations.partner_application SET status = 'APPROVED_FOR_ONBOARDING',"
                      + " row_version = 1, updated_at = now() WHERE id = ?",
                  ready);
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE integrations.partner_application SET status = 'DECLINED',"
                                  + " row_version = 2, updated_at = now() WHERE id = ?",
                              ready))
              .hasMessageContaining("terminal partner application is never changed");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE integrations.partner_application_check SET result ="
                                  + " 'CONCERN', actor_subject = 'admin', row_version = 1,"
                                  + " updated_at = now() WHERE application_id = ? AND check_code ="
                                  + " 'SERVICE_SCOPE'",
                              ready))
              .hasMessageContaining("checklist of a terminal partner application");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "DELETE FROM integrations.partner_application WHERE id = ?", ready))
              .hasMessageContaining("never deleted, truncated or updated");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE integrations.partner_application SET legal_name = 'Other Name',"
                                  + " row_version = 1, updated_at = now() WHERE id = ?",
                              pending))
              .hasMessageContaining("submitted content of a partner application is frozen");
        });
  }

  @Test
  void theHistoryIsImmutableContiguousAndShapedByTheLifecycle() {
    withDatabase(
        "55",
        db -> {
          UUID id = make(db, reference(1), "BANK", "BCC");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE integrations.partner_application_event SET reason_code ="
                                  + " 'OTHER' WHERE application_id = ?",
                              id))
              .hasMessageContaining("never deleted, truncated or updated");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "DELETE FROM integrations.partner_application_event WHERE"
                                  + " application_id = ?",
                              id))
              .hasMessageContaining("never deleted, truncated or updated");
          assertThatThrownBy(
                  () -> db.jdbc().execute("TRUNCATE integrations.partner_application_event"))
              .hasMessageContaining("never deleted, truncated or updated");
          // A gap in the sequence, a status the application does not have, and a shape the
          // lifecycle does not allow are all refused.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "INSERT INTO integrations.partner_application_event VALUES (?, 5,"
                                  + " 'CHECKLIST_UPDATED', 'SUBMITTED', 'SUBMITTED',"
                                  + " 'SERVICE_SCOPE', 'SATISFIED', NULL, 'PLATFORM_ADMIN',"
                                  + " 'admin', now())",
                              id))
              .hasMessageContaining("next contiguous sequence");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "INSERT INTO integrations.partner_application_event VALUES (?, 2,"
                                  + " 'REVIEW_STARTED', 'SUBMITTED', 'UNDER_REVIEW', NULL, NULL,"
                                  + " NULL, 'PLATFORM_ADMIN', 'admin', now())",
                              id))
              .hasMessageContaining("status the application actually has");
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "INSERT INTO integrations.partner_application_event VALUES (?, 2,"
                                  + " 'APPROVED_FOR_ONBOARDING', 'SUBMITTED',"
                                  + " 'APPROVED_FOR_ONBOARDING', NULL, NULL, NULL,"
                                  + " 'PLATFORM_ADMIN', 'admin', now())",
                              id))
              .isInstanceOf(DataAccessException.class);
        });
  }

  @Test
  void theRollbackRefusesOnceApplicationsExistAndOtherwiseRestoresV54Exactly() {
    withDatabase(
        "54",
        db -> {
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          Flyway.configure()
              .dataSource(db.url(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .target("55")
              .load()
              .migrate();
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isNotEqualTo(before);
          String rollback = resource("db/rollback/V55__rollback.sql");
          make(db, reference(1), "BANK", "BCC");
          assertThatThrownBy(() -> script(db, rollback))
              .hasMessageContaining("V55 rollback refused: partner-application rows exist");
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM integrations.partner_application", Integer.class))
              .isEqualTo(1);
        });
    withDatabase(
        "54",
        db -> {
          String before = db.jdbc().queryForObject(SIGNATURE, String.class);
          Flyway.configure()
              .dataSource(db.url(), postgres.getUsername(), postgres.getPassword())
              .locations("classpath:db/migration")
              .target("55")
              .load()
              .migrate();
          script(db, resource("db/rollback/V55__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(before);
        });
  }
}
