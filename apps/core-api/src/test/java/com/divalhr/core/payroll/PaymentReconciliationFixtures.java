package com.divalhr.core.payroll;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * MVP-095C direct SQL fixtures for the V49 database tests: submissions in a chosen state (written
 * with replica role, in the caller's transaction, because the database tests exercise the
 * reconciliation rules, not the submission rules) and reconciliations written exactly as {@code
 * JdbcPaymentReconciliationRepository} writes them, with the same statements and the same evidence.
 * Synthetic data only.
 */
final class PaymentReconciliationFixtures {

  static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  /** A submission written by {@link #submission}. */
  record Submission(
      UUID tenant, UUID entity, UUID id, UUID batch, UUID request, String state, int version) {}

  /** A reconciliation written by {@link #open}. */
  record Reconciliation(Submission submission, UUID id, UUID inquiry) {}

  private PaymentReconciliationFixtures() {}

  static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        Object arg = args[i];
        statement.setObject(i + 1, arg instanceof Instant instant ? Timestamp.from(instant) : arg);
      }
      statement.execute();
    }
  }

  static Instant at(int version) {
    return T0.plusSeconds(60L * version);
  }

  /** Inserts a version-1 submission (and two attempt rows) in the given state. */
  static Submission submission(Connection c, UUID tenant, String state) throws SQLException {
    Submission s =
        new Submission(
            tenant,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            state,
            1);
    int attempts =
        switch (state) {
          case "QUEUED" -> 0;
          case "MANUAL_INTERVENTION" -> 6;
          default -> 1;
        };
    String outcome =
        switch (state) {
          case "ACCEPTED_FOR_PROCESSING" -> "ACCEPTED";
          case "REJECTED" -> "REJECTED_OTHER";
          case "DELIVERY_UNKNOWN" -> "UNKNOWN_TIMEOUT";
          case "MANUAL_INTERVENTION" -> "RETRIES_EXHAUSTED";
          default -> null;
        };
    String reference = state.equals("ACCEPTED_FOR_PROCESSING") ? "SYNTH-00000001" : null;
    Instant next = state.equals("QUEUED") ? T0 : null;
    String owner = state.equals("SENDING") ? "worker-fixture" : null;
    Instant lease = state.equals("SENDING") ? T0.plusSeconds(3600) : null;
    exec(c, "SET LOCAL session_replication_role = replica");
    exec(
        c,
        "INSERT INTO payroll.payment_submission (tenant_id, legal_entity_id, id, batch_id,"
            + " package_id, request_id, provider_code, key_version, state, version, batch_version,"
            + " batch_digest, currency, line_count, bank_count, mobile_count, manual_count,"
            + " total_net_minor, attempt_count, outcome_code, provider_reference,"
            + " next_attempt_at, lease_owner, lease_expires_at, reviewed_provider_totals,"
            + " understands_not_paid, created_at, created_by_subject, updated_at) VALUES (?, ?,"
            + " ?, ?, ?, ?, 'SYNTHETIC-TEST', 1, ?, 1, 1, repeat('a', 64), 'CDF', 1, 1, 0, 0,"
            + " 92000, ?, ?, ?, ?, ?, ?, true, true, ?, 'sub-test', ?)",
        tenant,
        s.entity(),
        s.id(),
        s.batch(),
        UUID.randomUUID(),
        s.request(),
        state,
        attempts,
        outcome,
        reference,
        next,
        owner,
        lease,
        T0,
        T0);
    exec(
        c,
        "INSERT INTO payroll.payment_submission_attempt (tenant_id, submission_id, seq,"
            + " attempt_number, state, outcome_code, provider_reference, occurred_at) VALUES"
            + " (?, ?, 1, 0, 'QUEUED', NULL, NULL, ?), (?, ?, 2, 1, 'SENDING', NULL, NULL, ?)",
        tenant,
        s.id(),
        T0,
        tenant,
        s.id(),
        T0.plusSeconds(1));
    exec(c, "SET LOCAL session_replication_role = origin");
    return s;
  }

  /** Inserts only the reconciliation row (OPEN, version 1), without its opening evidence. */
  static void insertReconciliation(
      Connection c,
      Submission s,
      UUID id,
      UUID inquiry,
      UUID request,
      String openedFromState,
      int openedFromVersion)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.payment_reconciliation (tenant_id, legal_entity_id, id,"
            + " submission_id, batch_id, request_id, provider_code, key_version, inquiry_id,"
            + " opened_from_state, opened_from_version, state, version, attempt_count,"
            + " next_attempt_at, understands_no_resend, understands_not_paid, created_at,"
            + " created_by_subject, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'SYNTHETIC-TEST', 1, ?,"
            + " ?, ?, 'OPEN', 1, 0, ?, TRUE, TRUE, ?, 'admin-test', ?)",
        s.tenant(),
        s.entity(),
        id,
        s.id(),
        s.batch(),
        request,
        inquiry,
        openedFromState,
        openedFromVersion,
        T0,
        T0,
        T0);
  }

  static void insertEvidence(
      Connection c,
      UUID tenant,
      UUID reconciliation,
      int seq,
      int attempt,
      String state,
      String outcome,
      boolean authenticated,
      Instant at)
      throws SQLException {
    exec(
        c,
        "INSERT INTO payroll.payment_reconciliation_inquiry (tenant_id, reconciliation_id, seq,"
            + " attempt_number, state, outcome_code, authenticated, occurred_at) VALUES (?, ?, ?,"
            + " ?, ?, ?, ?, ?)",
        tenant,
        reconciliation,
        seq,
        attempt,
        state,
        outcome,
        authenticated,
        at);
  }

  /** Opens a reconciliation exactly as the repository does: the row and the opening evidence. */
  static Reconciliation open(Connection c, Submission s) throws SQLException {
    return open(c, s, UUID.randomUUID());
  }

  static Reconciliation open(Connection c, Submission s, UUID inquiry) throws SQLException {
    UUID id = UUID.randomUUID();
    insertReconciliation(c, s, id, inquiry, s.request(), s.state(), s.version());
    insertEvidence(c, s.tenant(), id, 1, 0, "OPEN", null, false, T0);
    return new Reconciliation(s, id, inquiry);
  }

  /** One legal transition and its evidence row, as the repository writes them. */
  static void transition(
      Connection c,
      Reconciliation r,
      int version,
      int attempts,
      String state,
      String outcome,
      boolean authenticated,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseExpiresAt)
      throws SQLException {
    exec(
        c,
        "UPDATE payroll.payment_reconciliation SET state = ?, version = ?, attempt_count = ?,"
            + " outcome_code = ?, next_attempt_at = ?, lease_owner = ?, lease_expires_at = ?,"
            + " updated_at = ? WHERE tenant_id = ? AND id = ?",
        state,
        version,
        attempts,
        outcome,
        nextAttemptAt,
        leaseOwner,
        leaseExpiresAt,
        at(version),
        r.submission().tenant(),
        r.id());
    insertEvidence(
        c,
        r.submission().tenant(),
        r.id(),
        version,
        attempts,
        state,
        outcome,
        authenticated,
        at(version));
  }

  /** OPEN to INQUIRY_IN_PROGRESS (attempt 1, version 2). */
  static void claim(Connection c, Reconciliation r) throws SQLException {
    transition(
        c,
        r,
        2,
        1,
        "INQUIRY_IN_PROGRESS",
        null,
        false,
        null,
        "worker-fixture",
        at(2).plusSeconds(60));
  }

  /** OPEN to INQUIRY_IN_PROGRESS to the given concluding state (versions 2 and 3). */
  static void conclude(
      Connection c, Reconciliation r, String state, String outcome, boolean authenticated)
      throws SQLException {
    claim(c, r);
    transition(c, r, 3, 1, state, outcome, authenticated, null, null, null);
  }

  /** Drives a fresh reconciliation to the named concluding state. */
  static void concludeAs(Connection c, Reconciliation r, String state) throws SQLException {
    switch (state) {
      case "CONFIRMED_ACCEPTED_FOR_PROCESSING" ->
          conclude(c, r, state, "ACCEPTED_FOR_PROCESSING", true);
      case "CONFIRMED_REJECTED" -> conclude(c, r, state, "REJECTED_OTHER", true);
      case "STILL_UNRESOLVED" -> conclude(c, r, state, "PENDING", true);
      case "MANUAL_REVIEW_REQUIRED" -> conclude(c, r, state, "RETRIES_EXHAUSTED", false);
      default -> throw new IllegalArgumentException(state);
    }
  }
}
