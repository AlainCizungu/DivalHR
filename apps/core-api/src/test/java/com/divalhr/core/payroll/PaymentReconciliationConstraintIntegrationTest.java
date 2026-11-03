package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PaymentReconciliationFixtures.at;
import static com.divalhr.core.payroll.PaymentReconciliationFixtures.exec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.PaymentReconciliationFixtures.Reconciliation;
import com.divalhr.core.payroll.PaymentReconciliationFixtures.Submission;
import com.divalhr.core.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MVP-095C (Issue #190, ADR 0038) V49 directly against PostgreSQL: whatever the application checks,
 * the database refuses an illegal transition, an exit from a conclusion, a rewritten or deleted or
 * truncated evidence row, a foreign tenant, a changed request ID, a conclusion without
 * authenticated evidence, a second reconciliation of a submission or of an inquiry ID and a
 * submission that is not DELIVERY_UNKNOWN or MANUAL_INTERVENTION; and a reconciliation never
 * changes the submission or its attempts. Each refusal is proved by the constraint it names.
 * Synthetic data only.
 */
@IntegrationTest
class PaymentReconciliationConstraintIntegrationTest {

  private static final List<String> CONCLUSIONS =
      List.of(
          "CONFIRMED_ACCEPTED_FOR_PROCESSING",
          "CONFIRMED_REJECTED",
          "STILL_UNRESOLVED",
          "MANUAL_REVIEW_REQUIRED");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcConnectionDetails connection;

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection c) throws SQLException;
  }

  private Connection connect() throws SQLException {
    Connection c =
        DriverManager.getConnection(
            connection.getJdbcUrl(), connection.getUsername(), connection.getPassword());
    c.setAutoCommit(false);
    return c;
  }

  /** Runs work in a transaction that is committed. */
  private void committing(SqlWork work) throws SQLException {
    try (Connection c = connect()) {
      work.run(c);
      exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
      c.commit();
    }
  }

  /** Runs work in a transaction that is always rolled back and must be refused by a constraint. */
  private void refuses(String constraint, SqlWork work) {
    assertThatThrownBy(
            () -> {
              try (Connection c = connect()) {
                try {
                  work.run(c);
                  exec(c, "SET CONSTRAINTS ALL IMMEDIATE");
                } finally {
                  c.rollback();
                }
              }
            })
        .as(constraint)
        .isInstanceOfSatisfying(
            PSQLException.class,
            e -> assertThat(e.getServerErrorMessage().getConstraint()).isEqualTo(constraint));
  }

  private Submission committedSubmission(UUID tenant, String state) throws SQLException {
    Submission[] made = new Submission[1];
    committing(c -> made[0] = PaymentReconciliationFixtures.submission(c, tenant, state));
    return made[0];
  }

  private Reconciliation committedOpen(Submission s) throws SQLException {
    Reconciliation[] made = new Reconciliation[1];
    committing(c -> made[0] = PaymentReconciliationFixtures.open(c, s));
    return made[0];
  }

  private Reconciliation committedAs(UUID tenant, String state) throws SQLException {
    Submission s = committedSubmission(tenant, "DELIVERY_UNKNOWN");
    Reconciliation[] made = new Reconciliation[1];
    committing(
        c -> {
          made[0] = PaymentReconciliationFixtures.open(c, s);
          PaymentReconciliationFixtures.concludeAs(c, made[0], state);
        });
    return made[0];
  }

  private int count(String sql, Object... args) {
    return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
  }

  // ---- eligibility and creation ----

  @Test
  void onlyDeliveryUnknownAndManualInterventionSubmissionsCanBeInvestigated() throws Exception {
    UUID tenant = UUID.randomUUID();
    for (String eligible : List.of("DELIVERY_UNKNOWN", "MANUAL_INTERVENTION")) {
      Submission s = committedSubmission(tenant, eligible);
      Reconciliation r = committedOpen(s);
      assertThat(
              count(
                  "SELECT count(*) FROM payroll.payment_reconciliation WHERE tenant_id = ?"
                      + " AND id = ? AND state = 'OPEN' AND opened_from_state = ?",
                  tenant,
                  r.id(),
                  eligible))
          .isEqualTo(1);
    }
    for (String refused : List.of("QUEUED", "SENDING", "ACCEPTED_FOR_PROCESSING", "REJECTED")) {
      Submission s = committedSubmission(tenant, refused);
      refuses(
          "payment_reconciliation_submission_state", c -> PaymentReconciliationFixtures.open(c, s));
    }
  }

  @Test
  void aReconciliationOfAMovedSubmissionOrOfAnotherStateIsRefused() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    refuses(
        "payment_reconciliation_submission_state",
        c -> {
          UUID id = UUID.randomUUID();
          PaymentReconciliationFixtures.insertReconciliation(
              c, s, id, UUID.randomUUID(), s.request(), "MANUAL_INTERVENTION", s.version());
        });
    refuses(
        "payment_reconciliation_submission_state",
        c -> {
          UUID id = UUID.randomUUID();
          PaymentReconciliationFixtures.insertReconciliation(
              c, s, id, UUID.randomUUID(), s.request(), s.state(), s.version() + 1);
        });
  }

  @Test
  void aReconciliationMustUseItsSubmissionsRequestIdExactly() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    refuses(
        "payment_reconciliation_submission_exact",
        c ->
            PaymentReconciliationFixtures.insertReconciliation(
                c,
                s,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                s.state(),
                s.version()));
  }

  @Test
  void aReconciliationIsCreatedOpenAtVersionOneWithItsOpeningEvidence() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    refuses(
        "payment_reconciliation_creation",
        c ->
            exec(
                c,
                "INSERT INTO payroll.payment_reconciliation (tenant_id, legal_entity_id, id,"
                    + " submission_id, batch_id, request_id, provider_code, key_version,"
                    + " inquiry_id, opened_from_state, opened_from_version, state, version,"
                    + " attempt_count, next_attempt_at, understands_no_resend,"
                    + " understands_not_paid, created_at, created_by_subject, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 'SYNTHETIC-TEST', 1, ?, ?, ?, 'OPEN', 2, 0, ?,"
                    + " TRUE, TRUE, ?, 'admin-test', ?)",
                s.tenant(),
                s.entity(),
                UUID.randomUUID(),
                s.id(),
                s.batch(),
                s.request(),
                UUID.randomUUID(),
                s.state(),
                s.version(),
                PaymentReconciliationFixtures.T0,
                PaymentReconciliationFixtures.T0,
                PaymentReconciliationFixtures.T0));
    // A row without its opening evidence does not commit.
    refuses(
        "payment_reconciliation_evidence_exact",
        c ->
            PaymentReconciliationFixtures.insertReconciliation(
                c, s, UUID.randomUUID(), UUID.randomUUID(), s.request(), s.state(), s.version()));
  }

  @Test
  void aSubmissionHasOneReconciliationAndAnInquiryIdBelongsToOne() throws Exception {
    UUID tenant = UUID.randomUUID();
    Submission s = committedSubmission(tenant, "DELIVERY_UNKNOWN");
    Reconciliation first = committedOpen(s);
    refuses(
        "payment_reconciliation_one_per_submission", c -> PaymentReconciliationFixtures.open(c, s));

    Submission other = committedSubmission(tenant, "DELIVERY_UNKNOWN");
    refuses(
        "payment_reconciliation_inquiry_id",
        c -> PaymentReconciliationFixtures.open(c, other, first.inquiry()));
    // The same inquiry ID in another tenant is another investigation.
    Submission foreign = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    committing(c -> PaymentReconciliationFixtures.open(c, foreign, first.inquiry()));
  }

  // ---- transitions ----

  @Test
  void anIllegalTransitionIsRefusedByTheDatabase() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    Reconciliation r = committedOpen(s);
    // OPEN straight to a conclusion.
    refuses(
        "payment_reconciliation_transition",
        c ->
            PaymentReconciliationFixtures.transition(
                c, r, 2, 1, "CONFIRMED_REJECTED", "REJECTED_OTHER", true, null, null, null));
    refuses(
        "payment_reconciliation_transition",
        c ->
            PaymentReconciliationFixtures.transition(
                c,
                r,
                2,
                1,
                "CONFIRMED_ACCEPTED_FOR_PROCESSING",
                "ACCEPTED_FOR_PROCESSING",
                true,
                null,
                null,
                null));
    refuses(
        "payment_reconciliation_transition",
        c ->
            PaymentReconciliationFixtures.transition(
                c, r, 2, 1, "STILL_UNRESOLVED", "PENDING", true, null, null, null));
    // OPEN to OPEN is not a transition either.
    refuses(
        "payment_reconciliation_transition",
        c ->
            PaymentReconciliationFixtures.transition(
                c, r, 2, 0, "OPEN", "TRANSIENT_SERVER", false, at(3), null, null));
  }

  @Test
  void aTransitionMovesOneVersionAndOneAttemptAtATime() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    Reconciliation r = committedOpen(s);
    // Skipping a version.
    refuses(
        "payment_reconciliation_version_order",
        c ->
            PaymentReconciliationFixtures.transition(
                c, r, 3, 1, "INQUIRY_IN_PROGRESS", null, false, null, "worker-fixture", at(4)));
    // Claiming without counting the attempt.
    refuses(
        "payment_reconciliation_attempt_count",
        c ->
            PaymentReconciliationFixtures.transition(
                c, r, 2, 0, "INQUIRY_IN_PROGRESS", null, false, null, "worker-fixture", at(4)));
  }

  @Test
  void aConcludedReconciliationHasNoExitWhateverTheTarget() throws Exception {
    for (String conclusion : CONCLUSIONS) {
      Reconciliation r = committedAs(UUID.randomUUID(), conclusion);
      assertThat(
              count(
                  "SELECT count(*) FROM payroll.payment_reconciliation WHERE tenant_id = ? AND"
                      + " id = ? AND state = ? AND version = 3",
                  r.submission().tenant(),
                  r.id(),
                  conclusion))
          .as(conclusion)
          .isEqualTo(1);
      refuses(
          "payment_reconciliation_transition",
          c ->
              PaymentReconciliationFixtures.transition(
                  c, r, 4, 1, "OPEN", "TRANSIENT_SERVER", false, at(5), null, null));
      refuses(
          "payment_reconciliation_transition",
          c ->
              PaymentReconciliationFixtures.transition(
                  c, r, 4, 2, "INQUIRY_IN_PROGRESS", null, false, null, "worker-fixture", at(5)));
      for (String other : CONCLUSIONS) {
        refuses(
            "payment_reconciliation_transition",
            c ->
                PaymentReconciliationFixtures.transition(
                    c, r, 4, 1, other, "REJECTED_OTHER", true, null, null, null));
      }
    }
  }

  @Test
  void aReconciliationNeverChangesAnythingButItsLifecycle() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    Reconciliation r = committedOpen(s);
    for (String change :
        List.of(
            "request_id = gen_random_uuid()",
            "inquiry_id = gen_random_uuid()",
            "submission_id = gen_random_uuid()",
            "batch_id = gen_random_uuid()",
            "provider_code = 'OTHER-PROVIDER'",
            "key_version = 2",
            "opened_from_state = 'MANUAL_INTERVENTION'",
            "opened_from_version = 2",
            "created_by_subject = 'someone-else'",
            "created_at = created_at + interval '1 second'")) {
      refuses(
          "payment_reconciliation_lifecycle_only",
          c ->
              exec(
                  c,
                  "UPDATE payroll.payment_reconciliation SET "
                      + change
                      + " WHERE tenant_id = ? AND id = ?",
                  s.tenant(),
                  r.id()));
    }
  }

  // ---- evidence ----

  @Test
  void evidenceIsAppendOnlyNeverUpdatedDeletedOrTruncated() throws Exception {
    Reconciliation r = committedAs(UUID.randomUUID(), "CONFIRMED_REJECTED");
    UUID tenant = r.submission().tenant();
    refuses(
        "payment_reconciliation_immutable",
        c ->
            exec(
                c,
                "UPDATE payroll.payment_reconciliation_inquiry SET authenticated = FALSE"
                    + " WHERE tenant_id = ? AND reconciliation_id = ? AND seq = 3",
                tenant,
                r.id()));
    refuses(
        "payment_reconciliation_immutable",
        c ->
            exec(
                c,
                "UPDATE payroll.payment_reconciliation_inquiry SET outcome_code = 'PENDING'"
                    + " WHERE tenant_id = ? AND reconciliation_id = ?",
                tenant,
                r.id()));
    refuses(
        "payment_reconciliation_immutable",
        c ->
            exec(
                c,
                "DELETE FROM payroll.payment_reconciliation_inquiry WHERE tenant_id = ?"
                    + " AND reconciliation_id = ?",
                tenant,
                r.id()));
    refuses(
        "payment_reconciliation_immutable",
        c -> exec(c, "TRUNCATE payroll.payment_reconciliation_inquiry"));
    refuses(
        "payment_reconciliation_immutable",
        c -> exec(c, "TRUNCATE payroll.payment_reconciliation_inquiry CASCADE"));
    assertThat(
            count(
                "SELECT count(*) FROM payroll.payment_reconciliation_inquiry WHERE tenant_id = ?"
                    + " AND reconciliation_id = ?",
                tenant,
                r.id()))
        .isEqualTo(3);
  }

  @Test
  void aReconciliationIsNeverDeletedOrTruncated() throws Exception {
    Reconciliation r = committedOpen(committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN"));
    refuses(
        "payment_reconciliation_immutable",
        c ->
            exec(
                c,
                "DELETE FROM payroll.payment_reconciliation WHERE tenant_id = ? AND id = ?",
                r.submission().tenant(),
                r.id()));
    refuses(
        "payment_reconciliation_immutable",
        c -> exec(c, "TRUNCATE payroll.payment_reconciliation CASCADE"));
    assertThat(
            count(
                "SELECT count(*) FROM payroll.payment_reconciliation WHERE tenant_id = ? AND id"
                    + " = ?",
                r.submission().tenant(),
                r.id()))
        .isEqualTo(1);
  }

  @Test
  void evidenceIsAppendedInOrderAndAsALegalTransition() throws Exception {
    Reconciliation r = committedOpen(committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN"));
    UUID tenant = r.submission().tenant();
    refuses(
        "payment_reconciliation_inquiry_order",
        c ->
            PaymentReconciliationFixtures.insertEvidence(
                c, tenant, r.id(), 3, 1, "INQUIRY_IN_PROGRESS", null, false, at(3)));
    refuses(
        "payment_reconciliation_inquiry_transition",
        c ->
            PaymentReconciliationFixtures.insertEvidence(
                c, tenant, r.id(), 2, 1, "CONFIRMED_REJECTED", "REJECTED_OTHER", true, at(2)));
    refuses(
        "payment_reconciliation_inquiry_time",
        c ->
            PaymentReconciliationFixtures.insertEvidence(
                c,
                tenant,
                r.id(),
                2,
                1,
                "INQUIRY_IN_PROGRESS",
                null,
                false,
                PaymentReconciliationFixtures.T0.minusSeconds(1)));
  }

  @Test
  void aConclusionNeedsMatchingAuthenticatedEvidence() throws Exception {
    Reconciliation r = committedOpen(committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN"));
    UUID tenant = r.submission().tenant();
    record Bad(String state, String outcome, boolean authenticated) {}
    List<Bad> unauthenticatedOrMismatched =
        List.of(
            new Bad("CONFIRMED_ACCEPTED_FOR_PROCESSING", "ACCEPTED_FOR_PROCESSING", false),
            new Bad("CONFIRMED_ACCEPTED_FOR_PROCESSING", "PENDING", true),
            new Bad("CONFIRMED_ACCEPTED_FOR_PROCESSING", null, true),
            new Bad("CONFIRMED_REJECTED", "REJECTED_OTHER", false),
            new Bad("CONFIRMED_REJECTED", "ACCEPTED_FOR_PROCESSING", true),
            new Bad("STILL_UNRESOLVED", "PENDING", false),
            new Bad("STILL_UNRESOLVED", "BAD_RESPONSE", true),
            new Bad("STILL_UNRESOLVED", "ACCEPTED_FOR_PROCESSING", true),
            new Bad("MANUAL_REVIEW_REQUIRED", "RETRIES_EXHAUSTED", true),
            new Bad("MANUAL_REVIEW_REQUIRED", "TRANSIENT_SERVER", false));
    for (Bad bad : unauthenticatedOrMismatched) {
      refuses(
          "payment_reconciliation_inquiry_evidence",
          c -> {
            PaymentReconciliationFixtures.claim(c, r);
            PaymentReconciliationFixtures.insertEvidence(
                c, tenant, r.id(), 3, 1, bad.state(), bad.outcome(), bad.authenticated(), at(3));
          });
    }
    // A bad answer or a transport failure is never authenticated; an OPEN row is never
    // authenticated.
    refuses(
        "payment_reconciliation_inquiry_evidence",
        c ->
            PaymentReconciliationFixtures.insertEvidence(
                c, tenant, UUID.randomUUID(), 1, 0, "OPEN", null, true, at(1)));
  }

  @Test
  void theCurrentRowAlwaysEqualsItsLatestEvidence() throws Exception {
    Reconciliation r = committedOpen(committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN"));
    refuses(
        "payment_reconciliation_evidence_exact",
        c ->
            exec(
                c,
                "UPDATE payroll.payment_reconciliation SET state = 'INQUIRY_IN_PROGRESS',"
                    + " version = 2, attempt_count = 1, next_attempt_at = NULL,"
                    + " lease_owner = 'worker-fixture', lease_expires_at = ?, updated_at = ?"
                    + " WHERE tenant_id = ? AND id = ?",
                at(3),
                at(2),
                r.submission().tenant(),
                r.id()));
  }

  // ---- tenants ----

  @Test
  void aReconciliationAndItsEvidenceCannotCrossTenants() throws Exception {
    Submission s = committedSubmission(UUID.randomUUID(), "DELIVERY_UNKNOWN");
    Submission foreign =
        new Submission(
            UUID.randomUUID(), s.entity(), s.id(), s.batch(), s.request(), s.state(), s.version());
    refuses(
        "payment_reconciliation_submission_fk",
        c ->
            PaymentReconciliationFixtures.insertReconciliation(
                c,
                foreign,
                UUID.randomUUID(),
                UUID.randomUUID(),
                s.request(),
                s.state(),
                s.version()));
    Reconciliation r = committedOpen(s);
    refuses(
        "payment_reconciliation_inquiry_fk",
        c ->
            PaymentReconciliationFixtures.insertEvidence(
                c, UUID.randomUUID(), r.id(), 1, 0, "OPEN", null, false, at(1)));
  }

  // ---- the submission is never touched ----

  private List<String> snapshot(UUID tenant) {
    List<String> rows =
        new java.util.ArrayList<>(
            jdbc.queryForList(
                "SELECT to_jsonb(s)::text FROM payroll.payment_submission s WHERE s.tenant_id = ?"
                    + " ORDER BY s.id",
                String.class,
                tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT to_jsonb(a)::text FROM payroll.payment_submission_attempt a"
                + " WHERE a.tenant_id = ? ORDER BY a.submission_id, a.seq",
            String.class,
            tenant));
    return rows;
  }

  @Test
  void theOriginalSubmissionAndItsAttemptsStayRowForRowUnchanged() throws Exception {
    UUID tenant = UUID.randomUUID();
    Submission unknown = committedSubmission(tenant, "DELIVERY_UNKNOWN");
    Submission manual = committedSubmission(tenant, "MANUAL_INTERVENTION");
    Submission accepted = committedSubmission(tenant, "ACCEPTED_FOR_PROCESSING");
    List<String> before = snapshot(tenant);
    assertThat(before).hasSize(3 + 6);

    Reconciliation first = committedOpen(unknown);
    committing(
        c -> {
          Reconciliation second = PaymentReconciliationFixtures.open(c, manual);
          PaymentReconciliationFixtures.concludeAs(c, second, "CONFIRMED_ACCEPTED_FOR_PROCESSING");
        });
    committing(
        c ->
            PaymentReconciliationFixtures.conclude(
                c, first, "CONFIRMED_REJECTED", "REJECTED_LIMIT", true));
    refuses(
        "payment_reconciliation_submission_state",
        c -> PaymentReconciliationFixtures.open(c, accepted));

    assertThat(snapshot(tenant)).isEqualTo(before);
    assertThat(
            count(
                "SELECT count(*) FROM payroll.payment_reconciliation WHERE tenant_id = ?", tenant))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM payroll.payment_reconciliation_inquiry WHERE tenant_id = ?",
                tenant))
        .isEqualTo(3 + 3);
  }
}
