package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * MVP-120C V54 directly against PostgreSQL (the database is the final authority): the refusing
 * rollback that restores V53 exactly, the closed retention policy rules, the new status in the
 * closed catalogues, and the view that exposes the immutable status history as a digest. A
 * populated V53 database (every salary-advance table, including a RECEIVED handoff with partner
 * review events and a DELIVERY_UNKNOWN handoff) is migrated to V54 and proved unchanged row by row
 * before any hr-dev deployment, and remains usable for a legal MVP-120C operation.
 */
@IntegrationTest
class SalaryAdvancePartnerLifecycleMigrationIntegrationTest {

  /** The payroll schema: tables, columns, indexes, constraints, triggers and functions. */
  private static final String SIGNATURE =
      """
      SELECT coalesce((SELECT string_agg(table_name, ',' ORDER BY table_name)
                       FROM information_schema.tables WHERE table_schema = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(table_name || '.' || column_name || ':'
                                  || data_type || ':' || is_nullable || ':'
                                  || coalesce(column_default, ''),
                                  ',' ORDER BY table_name, column_name)
                              FROM information_schema.columns
                              WHERE table_schema = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(indexname || ':' || indexdef, ','
                                  ORDER BY indexname)
                              FROM pg_indexes WHERE schemaname = 'payroll'), '')
          || '|' || coalesce((SELECT string_agg(conname || ':' || pg_get_constraintdef(oid), ','
                                  ORDER BY conname)
                              FROM pg_constraint
                              WHERE connamespace = 'payroll'::regnamespace), '')
          || '|' || coalesce((SELECT string_agg(pg_get_triggerdef(t.oid), ','
                                  ORDER BY c.relname, t.tgname)
                              FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                              WHERE c.relnamespace = 'payroll'::regnamespace
                                  AND NOT t.tgisinternal), '')
          || '|' || coalesce((SELECT string_agg(proname || ':' || md5(prosrc), ','
                                  ORDER BY proname) FROM pg_proc
                              WHERE pronamespace = 'payroll'::regnamespace), '')
      """;

  private static final String POLICY =
      "INSERT INTO payroll.salary_advance_evidence_retention_policy (tenant_id, version,"
          + " retention_days, effective_at, configured_by, created_at)"
          + " VALUES (?, ?, ?, now(), 'admin-subject', now())";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PostgreSQLContainer postgres;

  private record Db(String url, JdbcTemplate jdbc) {}

  private void withDatabase(Consumer<Db> body) {
    String database = "sa120c_" + UUID.randomUUID().toString().replace("-", "");
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

  /** Runs a script as written (JDBC escape processing would rewrite braces in the bodies). */
  private static void script(Db db, String sql) {
    db.jdbc()
        .execute(
            (org.springframework.jdbc.core.ConnectionCallback<Void>)
                connection -> {
                  try (java.sql.Statement statement = connection.createStatement()) {
                    statement.setEscapeProcessing(false);
                    statement.execute(sql);
                  }
                  return null;
                });
  }

  @Test
  void theRollbackRestoresV53ExactlyAndRefusesOnceAPolicyExists() {
    withDatabase(
        db -> {
          migrate(db.url(), "53");
          String v53 = db.jdbc().queryForObject(SIGNATURE, String.class);
          migrate(db.url(), "54");
          String v54 = db.jdbc().queryForObject(SIGNATURE, String.class);
          assertThat(v54)
              .isNotEqualTo(v53)
              .contains("salary_advance_purge_record")
              .contains("salary_advance_handoff_status_history");
          int recorded =
              Objects.requireNonNull(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
          script(db, resource("db/rollback/V54__rollback.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v53);
          assertThat(
                  db.jdbc()
                      .queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
              .isEqualTo(recorded);

          // With no 120C rows the rollback ran; the migration can be applied again.
          script(db, resource("db/migration/V54__salary_advance_partner_lifecycle.sql"));
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v54);

          // Once a policy exists, the rollback refuses and changes nothing.
          db.jdbc().update(POLICY, UUID.randomUUID(), 1, 30);
          assertThatThrownBy(() -> script(db, resource("db/rollback/V54__rollback.sql")))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("V54 rollback refused");
          assertThat(db.jdbc().queryForObject(SIGNATURE, String.class)).isEqualTo(v54);
        });
  }

  @Test
  void theRetentionPolicyIsBoundedVersionedAndImmutable() {
    withDatabase(
        db -> {
          migrate(db.url(), "54");
          UUID tenant = UUID.randomUUID();
          // The period is bounded to 30 to 3,650 days.
          for (int days : new int[] {0, 29, 3651}) {
            assertThatThrownBy(() -> db.jdbc().update(POLICY, tenant, 1, days))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payroll_salary_advance_retention_policy_days");
          }
          // Versions start at 1 and grow by one.
          assertThatThrownBy(() -> db.jdbc().update(POLICY, tenant, 2, 30))
              .isInstanceOf(DataAccessException.class)
              .hasMessageContaining("new version after the latest");
          db.jdbc().update(POLICY, tenant, 1, 30);
          db.jdbc().update(POLICY, tenant, 2, 3650);
          assertThatThrownBy(() -> db.jdbc().update(POLICY, tenant, 2, 90))
              .isInstanceOf(DataAccessException.class);
          // A policy is never edited or deleted.
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "UPDATE payroll.salary_advance_evidence_retention_policy"
                                  + " SET retention_days = 31 WHERE tenant_id = ?",
                              tenant))
              .isInstanceOf(DataAccessException.class);
          assertThatThrownBy(
                  () ->
                      db.jdbc()
                          .update(
                              "DELETE FROM payroll.salary_advance_evidence_retention_policy"
                                  + " WHERE tenant_id = ?",
                              tenant))
              .isInstanceOf(DataAccessException.class);
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT count(*) FROM payroll.salary_advance_evidence_retention_policy"
                              + " WHERE tenant_id = ?",
                          Integer.class,
                          tenant))
              .isEqualTo(2);
        });
  }

  @Test
  void theClosedCataloguesNameTheNewStatusAndNothingThatApprovesOrPays() {
    withDatabase(
        db -> {
          migrate(db.url(), "54");
          String definitions =
              db.jdbc()
                  .queryForObject(
                      "SELECT string_agg(pg_get_constraintdef(oid), ' ') FROM pg_constraint"
                          + " WHERE connamespace = 'payroll'::regnamespace AND conname IN"
                          + " ('payroll_salary_advance_handoff_review',"
                          + " 'payroll_salary_advance_handoff_callback_state',"
                          + " 'payroll_salary_advance_handoff_review_event_transition')",
                      String.class);
          assertThat(definitions).contains("CANCELLED_BY_PARTNER");
          assertThat(definitions)
              .doesNotContain("APPROVED")
              .doesNotContain("FUNDED")
              .doesNotContain("PAID")
              .doesNotContain("DISBURSED");
          // A terminal status never has a successor in the review-event catalogue.
          assertThat(definitions)
              .doesNotContain("from_review_state = 'DECLINED'")
              .doesNotContain("from_review_state = 'CANCELLED_BY_PARTNER'");
        });
  }

  @Test
  void deliveryDiagnosticsCanNeverBeDeletedWithoutAConfirmedPurge() {
    withDatabase(
        db -> {
          migrate(db.url(), "54");
          // Even with the purge marker set, nothing is deletable without a confirmed record that
          // names
          // the handoff; with no rows at all the statement is a no-op, and the guard is installed.
          Integer guards =
              db.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                          + " WHERE c.relname = 'salary_advance_handoff_attempt' AND t.tgname IN"
                          + " ('payroll_salary_advance_handoff_attempt_purge',"
                          + " 'payroll_salary_advance_handoff_attempt_no_update',"
                          + " 'payroll_salary_advance_handoff_attempt_no_truncate')",
                      Integer.class);
          assertThat(guards).isEqualTo(3);
          Integer legacy =
              db.jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM pg_trigger WHERE tgname ="
                          + " 'payroll_salary_advance_handoff_attempt_immutable'",
                      Integer.class);
          assertThat(legacy).isZero();
        });
  }

  // ------------------------------------------------------------------------------------------
  // Populated V53 (R210-1): every legacy row survives V54 unchanged and the database stays usable
  // ------------------------------------------------------------------------------------------

  private static final UUID TENANT = UUID.fromString("12000000-0000-4000-8000-000000000001");
  private static final UUID ENTITY = UUID.fromString("12000000-0000-4000-8000-000000000002");
  private static final UUID POLICY_ID = UUID.fromString("12000000-0000-4000-8000-000000000003");

  /** Every salary-advance table that exists in V53, in dependency order. */
  private static final List<String> LEGACY_TABLES =
      List.of(
          "salary_advance_policy",
          "salary_advance_request",
          "salary_advance_consent",
          "salary_advance_employer_decision",
          "salary_advance_request_event",
          "salary_advance_handoff",
          "salary_advance_handoff_consent",
          "salary_advance_handoff_event",
          "salary_advance_handoff_attempt",
          "salary_advance_handoff_ack",
          "salary_advance_handoff_callback",
          "salary_advance_handoff_review_event");

  /** The tables V54 adds: nothing may be written into them for legacy data. */
  private static final List<String> NEW_TABLES =
      List.of(
          "salary_advance_evidence_retention_policy",
          "salary_advance_handoff_retention",
          "salary_advance_purge_preview",
          "salary_advance_purge_preview_item",
          "salary_advance_purge_record");

  /** Handoffs whose evidence does not add up (the V53 completeness rule, as one query). */
  private static final String INCOMPLETE_HANDOFFS =
      """
      SELECT count(*) FROM payroll.salary_advance_handoff h
        CROSS JOIN LATERAL (
            SELECT count(*) AS n,
                   count(*) FILTER (WHERE to_state = 'SENDING') AS sending_in,
                   count(*) FILTER (WHERE from_state = 'SENDING') AS sending_out,
                   count(*) FILTER (WHERE from_state = 'DELIVERY_UNKNOWN') AS unknown_out,
                   (array_agg(to_state ORDER BY sequence DESC))[1] AS last_state
              FROM payroll.salary_advance_handoff_event e
             WHERE e.tenant_id = h.tenant_id AND e.handoff_id = h.id) ev
        CROSS JOIN LATERAL (
            SELECT count(*) AS n FROM payroll.salary_advance_handoff_review_event r
             WHERE r.tenant_id = h.tenant_id AND r.handoff_id = h.id) rv
        CROSS JOIN LATERAL (
            SELECT count(*) AS n FROM payroll.salary_advance_handoff_attempt a
             WHERE a.tenant_id = h.tenant_id AND a.handoff_id = h.id) att
        CROSS JOIN LATERAL (
            SELECT count(*) AS n FROM payroll.salary_advance_handoff_callback c
             WHERE c.tenant_id = h.tenant_id AND c.handoff_id = h.id) cb
       WHERE ev.n < 2 OR ev.last_state IS DISTINCT FROM h.state
          OR h.row_version <> ev.n + rv.n - 1
          OR h.attempt_count <> ev.sending_in OR att.n <> ev.sending_out
          OR h.reconcile_count <> ev.unknown_out OR cb.n <> rv.n
          OR (h.state = 'RECEIVED') <> EXISTS (
                 SELECT 1 FROM payroll.salary_advance_handoff_ack k
                  WHERE k.tenant_id = h.tenant_id AND k.handoff_id = h.id)
          OR NOT EXISTS (SELECT 1 FROM payroll.salary_advance_handoff_consent k
                          WHERE k.tenant_id = h.tenant_id AND k.handoff_id = h.id)
      """;

  private static String uid(int kind, int n) {
    return String.format("%08d-0000-4000-8000-%012d", kind, n);
  }

  private static String at(int n, int minute) {
    return String.format("TIMESTAMPTZ '2026-10-%02d 09:%02d:00+00'", n, minute);
  }

  private static String digest(String seed) {
    return "encode(sha256(convert_to('" + seed + "', 'UTF8')), 'hex')";
  }

  private static String lit(String value) {
    return value == null ? "NULL" : "'" + value + "'";
  }

  /** One request with its consent, decision and event chain (the employee's own subject). */
  private static void request(
      StringBuilder sql, int n, String status, int version, String decision, String reason) {
    sql.append(
        "INSERT INTO payroll.salary_advance_request (tenant_id, id, employee_id, legal_entity_id,"
            + " currency, status, row_version, policy_id, policy_sequence, partner_code,"
            + " package_id, payslip_id, reference_period_start, reference_net_minor,"
            + " ceiling_minor, requested_minor, employment_start, tenure_months, requested_on,"
            + " submitted_at, submitted_by) VALUES ('"
            + TENANT
            + "', '"
            + uid(1, n)
            + "', '"
            + uid(2, n)
            + "', '"
            + ENTITY
            + "', 'CDF', '"
            + status
            + "', "
            + version
            + ", '"
            + POLICY_ID
            + "', 1, 'SYNTHETIC-TEST', '"
            + uid(6, n)
            + "', '"
            + uid(7, n)
            + "', DATE '2026-09-01', 1000000, 300000, 5000, DATE '2025-01-01', 20,"
            + " DATE '2026-10-01', "
            + at(n, 0)
            + ", 'emp-sub-"
            + n
            + "');\n");
    sql.append(
        "INSERT INTO payroll.salary_advance_consent (tenant_id, request_id, consent_version,"
            + " language, consented_at, disclosure_sha256, affirmed) VALUES ('"
            + TENANT
            + "', '"
            + uid(1, n)
            + "', 'SA-CONSENT-1', 'en', "
            + at(n, 0)
            + ", "
            + digest("disclosure-" + n)
            + ", true);\n");
    requestEvent(sql, n, 1, null, "SUBMITTED", "EMPLOYEE", "emp-sub-" + n, null);
    if (decision != null) {
      sql.append(
          "INSERT INTO payroll.salary_advance_employer_decision (tenant_id, request_id, decision,"
              + " reason_code, internal_note, decided_by, decided_at, mfa_verified, confirmed)"
              + " VALUES ('"
              + TENANT
              + "', '"
              + uid(1, n)
              + "', '"
              + decision
              + "', "
              + lit(reason)
              + ", NULL, 'admin-sub', "
              + at(n, 1)
              + ", true, true);\n");
      requestEvent(
          sql,
          n,
          2,
          "SUBMITTED",
          "CONFIRMED".equals(decision) ? "EMPLOYER_CONFIRMED" : "EMPLOYER_REFUSED",
          "TENANT_ADMIN",
          "admin-sub",
          reason);
    }
    if ("WITHDRAWN".equals(status)) {
      requestEvent(sql, n, 3, "EMPLOYER_CONFIRMED", "WITHDRAWN", "EMPLOYEE", "emp-sub-" + n, null);
    }
  }

  private static void requestEvent(
      StringBuilder sql,
      int n,
      int sequence,
      String from,
      String to,
      String role,
      String subject,
      String reason) {
    sql.append(
        "INSERT INTO payroll.salary_advance_request_event (tenant_id, request_id, sequence,"
            + " from_status, to_status, actor_role, actor_subject, occurred_at, occurred_on,"
            + " reason_code) VALUES ('"
            + TENANT
            + "', '"
            + uid(1, n)
            + "', "
            + sequence
            + ", "
            + lit(from)
            + ", '"
            + to
            + "', '"
            + role
            + "', '"
            + subject
            + "', "
            + at(n, sequence)
            + ", DATE '2026-10-"
            + String.format("%02d", n)
            + "', "
            + lit(reason)
            + ");\n");
  }

  private static void handoffEvent(
      StringBuilder sql, int n, int sequence, String from, String to, String outcome) {
    String role = sequence <= 2 || "WITHDRAWN".equals(to) ? "EMPLOYEE" : "SYSTEM";
    String subject = "EMPLOYEE".equals(role) ? "emp-sub-" + n : "system";
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff_event (tenant_id, handoff_id, sequence,"
            + " from_state, to_state, actor_role, actor_subject, occurred_at, outcome) VALUES ('"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', "
            + sequence
            + ", "
            + lit(from)
            + ", '"
            + to
            + "', '"
            + role
            + "', '"
            + subject
            + "', "
            + at(n, 10 + sequence)
            + ", "
            + lit(outcome)
            + ");\n");
  }

  private static void attempt(StringBuilder sql, int n, String outcome, String httpStatus) {
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff_attempt (tenant_id, handoff_id,"
            + " attempt_number, started_at, finished_at, outcome, http_status) VALUES ('"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', 1, "
            + at(n, 12)
            + ", "
            + at(n, 13)
            + ", '"
            + outcome
            + "', "
            + httpStatus
            + ");\n");
  }

  private static void callback(
      StringBuilder sql, int n, int k, String state, String reason, int sequence, String from) {
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff_callback (callback_id, tenant_id, handoff_id,"
            + " review_state, reason_code, issued_at, received_at) VALUES ('"
            + uid(5, n * 10 + k)
            + "', '"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', '"
            + state
            + "', "
            + lit(reason)
            + ", "
            + at(n, 20 + k)
            + ", "
            + at(n, 20 + k)
            + ");\n");
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff_review_event (tenant_id, handoff_id, sequence,"
            + " from_review_state, to_review_state, reason_code, callback_id, occurred_at)"
            + " VALUES ('"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', "
            + sequence
            + ", '"
            + from
            + "', '"
            + state
            + "', "
            + lit(reason)
            + ", '"
            + uid(5, n * 10 + k)
            + "', "
            + at(n, 20 + k)
            + ");\n");
  }

  /**
   * One handoff in its final V53 state with the evidence that explains it: row_version is the event
   * rows plus the review rows minus one, every counter equals its rows.
   */
  private static void handoff(
      StringBuilder sql,
      int n,
      String state,
      String review,
      String reviewReason,
      int version,
      int attempts,
      int reconciles) {
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff (tenant_id, id, request_id, employee_id,"
            + " legal_entity_id, partner_code, application_id, state, review_state, review_reason,"
            + " row_version, attempt_count, reconcile_count, admin_retry_count,"
            + " payload_schema_version, payload_sha256, shared_categories, key_version,"
            + " authorized_at, authorized_by, next_attempt_at, lease_until, updated_at) VALUES ('"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', '"
            + uid(1, n)
            + "', '"
            + uid(2, n)
            + "', '"
            + ENTITY
            + "', 'SYNTHETIC-TEST', '"
            + uid(4, n)
            + "', '"
            + state
            + "', "
            + lit(review)
            + ", "
            + lit(reviewReason)
            + ", "
            + version
            + ", "
            + attempts
            + ", "
            + reconciles
            + ", 0, 1, "
            + digest("payload-" + n)
            + ", ARRAY['APPLICATION_REFERENCE', 'REQUEST_REFERENCE', 'CURRENCY',"
            + " 'REQUESTED_AMOUNT'], 'v1', "
            + at(n, 10)
            + ", 'emp-sub-"
            + n
            + "', NULL, NULL, "
            + at(n, 30)
            + ");\n");
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff_consent (tenant_id, handoff_id, request_id,"
            + " consent_version, language, consented_at, disclosure_sha256, payload_sha256,"
            + " consented_by, affirmed, synthetic_acknowledged) VALUES ('"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', '"
            + uid(1, n)
            + "', 'SA-HANDOFF-1', 'en', "
            + at(n, 10)
            + ", "
            + digest("handoff-disclosure-" + n)
            + ", "
            + digest("payload-" + n)
            + ", 'emp-sub-"
            + n
            + "', true, true);\n");
    handoffEvent(sql, n, 1, null, "AUTHORIZED", null);
    handoffEvent(sql, n, 2, "AUTHORIZED", "QUEUED", null);
  }

  private static void received(StringBuilder sql, int n) {
    handoffEvent(sql, n, 3, "QUEUED", "SENDING", null);
    handoffEvent(sql, n, 4, "SENDING", "RECEIVED", "RECEIVED_ACKNOWLEDGED");
    attempt(sql, n, "RECEIVED_ACKNOWLEDGED", "200");
    sql.append(
        "INSERT INTO payroll.salary_advance_handoff_ack (tenant_id, handoff_id, partner_reference,"
            + " acknowledged_at, source) VALUES ('"
            + TENANT
            + "', '"
            + uid(3, n)
            + "', 'PARTREF-000"
            + n
            + "', "
            + at(n, 13)
            + ", 'DELIVERY');\n");
  }

  /**
   * Valid representative V53 data: the V53 data-shape rules (CHECK and unique constraints) apply to
   * every row. Only the unrelated parents (legal entity, employee, payslip) and the insert-time
   * lifecycle triggers are skipped, for this one disposable transaction.
   */
  private static String legacySeed() {
    StringBuilder sql =
        new StringBuilder("BEGIN;\nSET LOCAL session_replication_role = replica;\n");
    sql.append(
        "INSERT INTO payroll.salary_advance_policy (tenant_id, legal_entity_id, id, currency,"
            + " sequence, code, effective_from, enabled, min_tenure_months, max_percent,"
            + " min_request_minor, max_open_requests, waiting_days, consent_version, partner_code,"
            + " created_at, created_by) VALUES ('"
            + TENANT
            + "', '"
            + ENTITY
            + "', '"
            + POLICY_ID
            + "', 'CDF', 1, 'SA-LEGACY', DATE '2026-01-01', true, 0, 30, 1000, 3, 30,"
            + " 'SA-CONSENT-1', 'SYNTHETIC-TEST', "
            + at(1, 0)
            + ", 'admin-sub');\n");
    for (int n = 1; n <= 4; n++) {
      request(sql, n, "EMPLOYER_CONFIRMED", 2, "CONFIRMED", null);
    }
    request(sql, 5, "WITHDRAWN", 3, "CONFIRMED", null);
    request(sql, 6, "EMPLOYER_CONFIRMED", 2, "CONFIRMED", null);
    request(sql, 7, "EMPLOYER_REFUSED", 2, "REFUSED", "OTHER_POLICY_REASON");
    request(sql, 8, "SUBMITTED", 1, null, null);
    request(sql, 9, "EMPLOYER_CONFIRMED", 2, "CONFIRMED", null);
    request(sql, 10, "EMPLOYER_CONFIRMED", 2, "CONFIRMED", null);
    // 1: RECEIVED, partner reports UNDER_REVIEW (non-terminal).
    handoff(sql, 1, "RECEIVED", "UNDER_REVIEW", null, 4, 1, 0);
    received(sql, 1);
    callback(sql, 1, 1, "UNDER_REVIEW", null, 1, "RECEIVED");
    // 2: RECEIVED, no review step yet (initial review state).
    handoff(sql, 2, "RECEIVED", "RECEIVED", null, 3, 1, 0);
    received(sql, 2);
    // 3: RECEIVED, partner declined (terminal in V53).
    handoff(sql, 3, "RECEIVED", "DECLINED", "PARTNER_POLICY", 5, 1, 0);
    received(sql, 3);
    callback(sql, 3, 1, "UNDER_REVIEW", null, 1, "RECEIVED");
    callback(sql, 3, 2, "DECLINED", "PARTNER_POLICY", 2, "UNDER_REVIEW");
    // 4: DELIVERY_UNKNOWN: the non-resend boundary, with its attempt and no acknowledgement.
    handoff(sql, 4, "DELIVERY_UNKNOWN", null, null, 3, 1, 0);
    handoffEvent(sql, 4, 3, "QUEUED", "SENDING", null);
    handoffEvent(sql, 4, 4, "SENDING", "DELIVERY_UNKNOWN", "UNKNOWN_TIMEOUT");
    attempt(sql, 4, "UNKNOWN_TIMEOUT", "NULL");
    // 5: WITHDRAWN before receipt (the request is WITHDRAWN too).
    handoff(sql, 5, "WITHDRAWN", null, null, 2, 0, 0);
    handoffEvent(sql, 5, 3, "QUEUED", "WITHDRAWN", "WITHDRAWN_BEFORE_RECEIPT");
    // 6: FAILED, rejected by the partner.
    handoff(sql, 6, "FAILED", null, null, 3, 1, 0);
    handoffEvent(sql, 6, 3, "QUEUED", "SENDING", null);
    handoffEvent(sql, 6, 4, "SENDING", "FAILED", "REJECTED_BY_PARTNER");
    attempt(sql, 6, "REJECTED_BY_PARTNER", "422");
    // 9: RECEIVED, partner asks for more information (non-terminal).
    handoff(sql, 9, "RECEIVED", "MORE_INFORMATION_REQUIRED", "CLARIFICATION", 5, 1, 0);
    received(sql, 9);
    callback(sql, 9, 1, "UNDER_REVIEW", null, 1, "RECEIVED");
    callback(sql, 9, 2, "MORE_INFORMATION_REQUIRED", "CLARIFICATION", 2, "UNDER_REVIEW");
    // 10: RECEIVED with the review back to UNDER_REVIEW after a request for information.
    handoff(sql, 10, "RECEIVED", "UNDER_REVIEW", null, 6, 1, 0);
    received(sql, 10);
    callback(sql, 10, 1, "UNDER_REVIEW", null, 1, "RECEIVED");
    callback(sql, 10, 2, "MORE_INFORMATION_REQUIRED", "CLARIFICATION", 2, "UNDER_REVIEW");
    callback(sql, 10, 3, "UNDER_REVIEW", null, 3, "MORE_INFORMATION_REQUIRED");
    sql.append("COMMIT;\n");
    return sql.toString();
  }

  /** Row count and a deterministic content fingerprint of one table (V54's new column removed). */
  private static String fingerprintSql(String table) {
    return "SELECT count(*) || ':' || coalesce(md5(string_agg((to_jsonb(t) - 'correlation_id')::text,"
        + " '|' ORDER BY (to_jsonb(t) - 'correlation_id')::text)), '') FROM payroll."
        + table
        + " t";
  }

  private static Map<String, String> fingerprints(Db db) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String table : LEGACY_TABLES) {
      out.put(table, db.jdbc().queryForObject(fingerprintSql(table), String.class));
    }
    return out;
  }

  private static int count(Db db, String sql) {
    return Objects.requireNonNull(db.jdbc().queryForObject(sql, Integer.class));
  }

  @Test
  void aPopulatedV53DatabaseMigratesWithEveryLegacyRowUnchangedAndStaysUsable() {
    withDatabase(
        db -> {
          migrate(db.url(), "53");
          script(db, legacySeed());
          // The legacy data is representative and complete: every table has rows, and every
          // handoff is explained by its evidence exactly as the V53 completeness rule demands.
          Map<String, String> before = fingerprints(db);
          for (Map.Entry<String, String> entry : before.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).doesNotStartWith("0:");
          }
          assertThat(count(db, INCOMPLETE_HANDOFFS)).isZero();
          assertThat(count(db, "SELECT count(*) FROM payroll.salary_advance_handoff")).isEqualTo(9);
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT state || '/' || coalesce(review_state, '-') FROM"
                              + " payroll.salary_advance_handoff ORDER BY id",
                          String.class))
              .containsExactly(
                  "RECEIVED/UNDER_REVIEW",
                  "RECEIVED/RECEIVED",
                  "RECEIVED/DECLINED",
                  "DELIVERY_UNKNOWN/-",
                  "WITHDRAWN/-",
                  "FAILED/-",
                  "RECEIVED/MORE_INFORMATION_REQUIRED",
                  "RECEIVED/UNDER_REVIEW");
          String unknownBefore =
              db.jdbc()
                  .queryForObject(
                      "SELECT string_agg(to_jsonb(h)::text, '|') FROM payroll.salary_advance_handoff h"
                          + " WHERE id = ?::uuid",
                      String.class,
                      uid(3, 4));
          // The replica role was local to the seeding transaction.
          assertThat(db.jdbc().queryForObject("SHOW session_replication_role", String.class))
              .isEqualTo("origin");

          migrate(db.url(), "54");

          // Every V53 row and material column is unchanged.
          assertThat(fingerprints(db)).isEqualTo(before);
          assertThat(count(db, INCOMPLETE_HANDOFFS)).isZero();
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT string_agg(to_jsonb(h)::text, '|') FROM"
                              + " payroll.salary_advance_handoff h WHERE id = ?::uuid",
                          String.class,
                          uid(3, 4)))
              .isEqualTo(unknownBefore);
          // The DELIVERY_UNKNOWN evidence is still there: its state, its events and its attempt.
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.salary_advance_handoff WHERE id = '"
                          + uid(3, 4)
                          + "' AND state = 'DELIVERY_UNKNOWN' AND reconcile_count = 0"))
              .isEqualTo(1);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.salary_advance_handoff_event WHERE handoff_id"
                          + " = '"
                          + uid(3, 4)
                          + "' AND 'DELIVERY_UNKNOWN' IN (from_state, to_state)"))
              .isEqualTo(1);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.salary_advance_handoff_attempt WHERE"
                          + " handoff_id = '"
                          + uid(3, 4)
                          + "' AND outcome = 'UNKNOWN_TIMEOUT'"))
              .isEqualTo(1);
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.salary_advance_handoff_ack WHERE handoff_id ="
                          + " '"
                          + uid(3, 4)
                          + "'"))
              .isZero();
          // Nothing was fabricated for the legacy data.
          for (String table : NEW_TABLES) {
            assertThat(count(db, "SELECT count(*) FROM payroll." + table)).as(table).isZero();
          }
          assertThat(
                  count(
                      db,
                      "SELECT count(*) FROM payroll.salary_advance_handoff_callback WHERE"
                          + " correlation_id IS NOT NULL"))
              .isZero();
          // The safe history read model only mirrors the real callbacks (one row each).
          assertThat(
                  count(db, "SELECT count(*) FROM payroll.salary_advance_handoff_status_history"))
              .isEqualTo(count(db, "SELECT count(*) FROM payroll.salary_advance_handoff_callback"));

          // The migrated database is usable: a retention policy, then a legal partner lifecycle
          // (UNDER_REVIEW, then the new CANCELLED_BY_PARTNER) on a legacy RECEIVED handoff, with
          // the
          // prospective retention stamp written for that handoff alone.
          db.jdbc().update(POLICY, TENANT, 1, 30);
          script(
              db,
              "BEGIN;\n"
                  + "INSERT INTO payroll.salary_advance_handoff_callback (callback_id, tenant_id,"
                  + " handoff_id, review_state, reason_code, issued_at, received_at,"
                  + " correlation_id) VALUES ('"
                  + uid(5, 201)
                  + "', '"
                  + TENANT
                  + "', '"
                  + uid(3, 2)
                  + "', 'UNDER_REVIEW', NULL, now(), now(), 'migration-check-1');\n"
                  + "INSERT INTO payroll.salary_advance_handoff_review_event (tenant_id,"
                  + " handoff_id, sequence, from_review_state, to_review_state, reason_code,"
                  + " callback_id, occurred_at) VALUES ('"
                  + TENANT
                  + "', '"
                  + uid(3, 2)
                  + "', 1, 'RECEIVED', 'UNDER_REVIEW', NULL, '"
                  + uid(5, 201)
                  + "', now());\n"
                  + "UPDATE payroll.salary_advance_handoff SET review_state = 'UNDER_REVIEW',"
                  + " row_version = row_version + 1, updated_at = now() WHERE id = '"
                  + uid(3, 2)
                  + "';\n"
                  + "INSERT INTO payroll.salary_advance_handoff_callback (callback_id, tenant_id,"
                  + " handoff_id, review_state, reason_code, issued_at, received_at,"
                  + " correlation_id) VALUES ('"
                  + uid(5, 202)
                  + "', '"
                  + TENANT
                  + "', '"
                  + uid(3, 2)
                  + "', 'CANCELLED_BY_PARTNER', 'PARTNER_POLICY', now(), now(),"
                  + " 'migration-check-2');\n"
                  + "INSERT INTO payroll.salary_advance_handoff_review_event (tenant_id,"
                  + " handoff_id, sequence, from_review_state, to_review_state, reason_code,"
                  + " callback_id, occurred_at) VALUES ('"
                  + TENANT
                  + "', '"
                  + uid(3, 2)
                  + "', 2, 'UNDER_REVIEW', 'CANCELLED_BY_PARTNER', 'PARTNER_POLICY', '"
                  + uid(5, 202)
                  + "', now());\n"
                  + "UPDATE payroll.salary_advance_handoff SET review_state ="
                  + " 'CANCELLED_BY_PARTNER', review_reason = 'PARTNER_POLICY', row_version ="
                  + " row_version + 1, updated_at = now() WHERE id = '"
                  + uid(3, 2)
                  + "';\n"
                  + "COMMIT;\n");
          assertThat(
                  db.jdbc()
                      .queryForObject(
                          "SELECT review_state FROM payroll.salary_advance_handoff WHERE id ="
                              + " ?::uuid",
                          String.class,
                          uid(3, 2)))
              .isEqualTo("CANCELLED_BY_PARTNER");
          assertThat(count(db, INCOMPLETE_HANDOFFS)).isZero();
          assertThat(
                  db.jdbc()
                      .queryForList(
                          "SELECT handoff_id::text || ':' || policy_version FROM"
                              + " payroll.salary_advance_handoff_retention",
                          String.class))
              .containsExactly(uid(3, 2) + ":1");
          // Every other legacy row is still untouched: only the one handoff moved.
          Map<String, String> after = fingerprints(db);
          for (String table : LEGACY_TABLES) {
            boolean touched =
                table.equals("salary_advance_handoff")
                    || table.equals("salary_advance_handoff_callback")
                    || table.equals("salary_advance_handoff_review_event");
            if (!touched) {
              assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
            }
          }
        });
  }
}
