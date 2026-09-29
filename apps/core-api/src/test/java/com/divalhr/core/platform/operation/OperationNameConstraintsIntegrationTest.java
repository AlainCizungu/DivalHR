package com.divalhr.core.platform.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** V4 (Issue #17): both platform checks enforce exactly the shared operation-name grammar. */
@IntegrationTest
class OperationNameConstraintsIntegrationTest {

  private static final String V3_GRAMMAR = "^[a-z]+(-[a-z]+)*(\\.[a-z-]+)+$";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;

  @Test
  void constraintDefinitionsMatchTheSharedGrammar() {
    for (String constraint : new String[] {"idempotency_operation_format", "audit_action_format"}) {
      String definition =
          jdbc.queryForObject(
              "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
              String.class,
              constraint);
      assertThat(definition).as(constraint).contains("'" + OperationName.GRAMMAR + "'");
    }
  }

  @Test
  void everyCurrentNameIsAcceptedInBothTables() {
    for (String name : OperationNameTest.CURRENT) {
      insertIdempotency(name);
      insertAudit(name);
    }
  }

  @Test
  void malformedNamesAreRejectedByTheRightConstraint() {
    for (String name : OperationNameTest.MALFORMED) {
      assertThat(constraintOf(() -> insertIdempotency(name)))
          .as("idempotency %s", name)
          .isEqualTo("idempotency_operation_format");
      assertThat(constraintOf(() -> insertAudit(name)))
          .as("audit %s", name)
          .isEqualTo("audit_action_format");
    }
  }

  @Test
  void preflightFailsLoudlyAndLeavesBothConstraintsUntouched() throws Exception {
    String v4 =
        Files.readString(
            Path.of("src/main/resources/db/migration/V4__tighten_operation_name_checks.sql"));
    transactions.executeWithoutResult(
        status -> {
          // Recreate the pre-V4 state with one malformed audit row, then replay V4 atomically.
          jdbc.execute(
              "ALTER TABLE platform.idempotency_record DROP CONSTRAINT"
                  + " idempotency_operation_format, ADD CONSTRAINT idempotency_operation_format"
                  + " CHECK (operation ~ '"
                  + V3_GRAMMAR
                  + "')");
          jdbc.execute(
              "ALTER TABLE platform.audit_event DROP CONSTRAINT audit_action_format,"
                  + " ADD CONSTRAINT audit_action_format CHECK (action ~ '"
                  + V3_GRAMMAR
                  + "')");
          insertAudit("x.---");
          jdbc.execute("SAVEPOINT before_v4");
          assertThatThrownBy(() -> jdbc.execute(v4))
              .isInstanceOf(DataAccessException.class)
              .satisfies(
                  failure -> {
                    // The server-side error reports counts only, never row contents.
                    String message = serverMessage(failure);
                    assertThat(message)
                        .contains("0 idempotency operation(s) and 1 audit action(s)")
                        .doesNotContain("x.---");
                  });
          jdbc.execute("ROLLBACK TO SAVEPOINT before_v4");
          assertThat(definition("idempotency_operation_format")).contains(V3_GRAMMAR);
          assertThat(definition("audit_action_format")).contains(V3_GRAMMAR);
          // Leave the shared database exactly as V4 made it.
          status.setRollbackOnly();
        });
    assertThat(definition("audit_action_format")).contains(OperationName.GRAMMAR);
    assertThat(definition("idempotency_operation_format")).contains(OperationName.GRAMMAR);
  }

  private String definition(String constraint) {
    return jdbc.queryForObject(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
        String.class,
        constraint);
  }

  private void insertIdempotency(String operation) {
    jdbc.update(
        """
        INSERT INTO platform.idempotency_record
          (operation, principal, idempotency_key, request_fingerprint, state, response_status,
           response_body, resource_id, created_at, expires_at)
        VALUES (?, 'sub-db', ?, ?, 'COMPLETED', 201, '{}'::jsonb, ?, now(),
                now() + interval '7 days')
        """,
        operation,
        "op-name-" + UUID.randomUUID(),
        "c".repeat(64),
        UUID.randomUUID());
  }

  private void insertAudit(String action) {
    jdbc.update(
        """
        INSERT INTO platform.audit_event
          (id, occurred_at, actor_subject, action, resource_type, resource_id, tenant_id, result,
           correlation_id, metadata, after_state_sha256)
        VALUES (?, now(), 'sub-db', ?, 'organization', ?, ?, 'SUCCESS', 'corr-op-name-01',
                '{}'::jsonb, repeat('a', 64))
        """,
        UUID.randomUUID(),
        action,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private static String serverMessage(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage server = psql.getServerErrorMessage();
        if (server != null) {
          return server.getMessage();
        }
      }
    }
    throw new AssertionError("no PostgreSQL error", failure);
  }

  private static String constraintOf(Runnable statement) {
    try {
      statement.run();
    } catch (DataAccessException failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof PSQLException psql) {
          ServerErrorMessage server = psql.getServerErrorMessage();
          if (server != null) {
            return server.getConstraint();
          }
        }
      }
      throw new AssertionError("no PostgreSQL error", failure);
    }
    throw new AssertionError("statement unexpectedly succeeded");
  }
}
