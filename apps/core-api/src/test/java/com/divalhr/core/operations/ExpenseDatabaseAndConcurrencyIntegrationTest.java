package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-081 (D81-14 items 13 and 14): the database is the final authority, whatever the application
 * does (append-only evidence, no deletion, legal transitions only, exact projection, receipt shape
 * and digest), and a decision racing a withdrawal or another decision yields exactly one legal
 * result with exactly one terminal action.
 */
@IntegrationTest
class ExpenseDatabaseAndConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;

  private ExpenseWorld x;
  private TaskWorld w;
  private Person ana;
  private Person mina;

  @BeforeEach
  void world() throws Exception {
    x = new ExpenseWorld(mvc, jdbc, lookups);
    w = x.w;
    ana = x.person("Ana", "Mukendi");
    mina = x.person("Mina", "Kabasele");
    x.manage(ana.employee(), mina.employee(), x.today.minusDays(30));
  }

  private static String key() {
    return Organizations.newKey();
  }

  private String approved() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    x.submit(ana, id, 1);
    x.approve(mina, id, 2);
    return id;
  }

  private void refused(String sql, Object... args) {
    assertThatThrownBy(() -> jdbc.update(sql, args))
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            e -> {
              // A constraint (class 23) or a guard trigger, never a mistake in the statement.
              Throwable root = e;
              while (root.getCause() != null) {
                root = root.getCause();
              }
              String state = ((java.sql.SQLException) root).getSQLState();
              assertThat(state).as(root.getMessage()).matches("23...|P0001|55000|42501");
            });
  }

  // ------------------------------------------------------------------------------- database

  @Test
  void terminalExpensesAndAllEvidenceAreImmutableAndNeverDeleted() throws Exception {
    String id = approved();
    UUID expense = UUID.fromString(id);
    UUID tenant = w.tenant();
    refused("UPDATE operations.expense SET state = 'DRAFT' WHERE tenant_id = ? AND id = ?", tenant, expense);
    refused("UPDATE operations.expense SET amount_minor = 5 WHERE tenant_id = ? AND id = ?", tenant, expense);
    refused("DELETE FROM operations.expense WHERE tenant_id = ? AND id = ?", tenant, expense);
    refused("UPDATE operations.expense_action SET actor_role = 'manager' WHERE tenant_id = ? AND expense_id = ?", tenant, expense);
    refused("DELETE FROM operations.expense_action WHERE tenant_id = ? AND expense_id = ?", tenant, expense);
    refused("DELETE FROM operations.expense_revision WHERE tenant_id = ? AND expense_id = ?", tenant, expense);
    refused("UPDATE operations.expense_revision SET merchant = 'Autre' WHERE tenant_id = ? AND expense_id = ?", tenant, expense);
    refused("TRUNCATE operations.expense_action");
    assertThat(w.count("SELECT count(*) FROM operations.expense WHERE tenant_id = ? AND id = ? AND state = 'APPROVED' AND amount_minor = 1000", tenant, expense)).isEqualTo(1);
  }

  @Test
  void aSubmittedRevisionsReceiptCannotBeDeletedOrChanged() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    w.expect(ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.png(1), ExpenseWorld.FILE_NAME, key()), 200);
    x.submit(ana, id, 2);
    UUID expense = UUID.fromString(id);
    refused("DELETE FROM operations.expense_receipt WHERE tenant_id = ? AND expense_id = ?", w.tenant(), expense);
    refused("UPDATE operations.expense_receipt SET file_name = 'autre.png' WHERE tenant_id = ? AND expense_id = ?", w.tenant(), expense);
    refused("UPDATE operations.expense_receipt SET content = '\\x89504e470d0a1a0a00'::bytea WHERE tenant_id = ? AND expense_id = ?", w.tenant(), expense);
  }

  @Test
  void onlyLegalTransitionsAndAnExactProjectionSurvive() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    UUID expense = UUID.fromString(id);
    // A jump from draft to approved, a projection that no longer matches its revision, a version
    // that does not move by one: all refused at the database.
    refused("UPDATE operations.expense SET state = 'APPROVED', submitted_at = now(), decided_at = now(), routed_manager_employee_id = ?, version = version + 1 WHERE tenant_id = ? AND id = ?", mina.employee(), w.tenant(), expense);
    refused("UPDATE operations.expense SET amount_minor = 9999, version = version + 1 WHERE tenant_id = ? AND id = ?", w.tenant(), expense);
    refused("UPDATE operations.expense SET version = version + 5 WHERE tenant_id = ? AND id = ?", w.tenant(), expense);
    refused("UPDATE operations.expense SET employee_id = ? WHERE tenant_id = ? AND id = ?", mina.employee(), w.tenant(), expense);
    refused("UPDATE operations.expense SET currency = 'CDF' WHERE tenant_id = ? AND id = ?", w.tenant(), expense);
    // Range per currency, at the table.
    refused("UPDATE operations.expense SET currency = 'CDF', amount_minor = 100000001 WHERE tenant_id = ? AND id = ?", w.tenant(), expense);
    assertThat(w.count("SELECT count(*) FROM operations.expense WHERE tenant_id = ? AND id = ? AND state = 'DRAFT' AND version = 1 AND amount_minor = 1000", w.tenant(), expense)).isEqualTo(1);
  }

  @Test
  void receiptRowsMustMatchTheirMagicDigestAndSize() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    UUID expense = UUID.fromString(id);
    String insert =
        "INSERT INTO operations.expense_receipt (tenant_id, expense_id, id, media_type, size_bytes,"
            + " sha256, file_name, content, created_at, created_by)"
            + " VALUES (?, ?, gen_random_uuid(), ?, octet_length(?::bytea), ?, ?, ?::bytea, now(), 'x')";
    byte[] png = Receipts.png(1);
    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(png);
    // Wrong magic for the declared type.
    refused(insert, w.tenant(), expense, "application/pdf", png, digest, "a.pdf", png);
    // Wrong digest.
    refused(insert, w.tenant(), expense, "image/png", png, new byte[32], "a.png", png);
    // Path separator in the display name.
    refused(insert, w.tenant(), expense, "image/png", png, digest, "../a.png", png);
    // Unsupported type.
    refused(insert, w.tenant(), expense, "image/gif", png, digest, "a.gif", png);
  }

  // ------------------------------------------------------------------------------ concurrency

  private List<MockHttpServletResponse> parallel(List<MockHttpServletRequestBuilder> calls)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return w.call(call);
                }));
      }
      go.countDown();
      List<MockHttpServletResponse> responses = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        responses.add(future.get(60, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      pool.shutdownNow();
    }
  }

  private int decisions(String id) {
    return w.count(
        "SELECT count(*) FROM operations.expense_action WHERE expense_id = ?"
            + " AND action IN ('APPROVED', 'RETURNED', 'WITHDRAWN')",
        UUID.fromString(id));
  }

  @Test
  void approvalRacingWithdrawalYieldsExactlyOneLegalResult() throws Exception {
    for (int round = 0; round < 4; round++) {
      String id = x.create(ana, x.usd("10", 1)).get("id").asText();
      x.submit(ana, id, 1);
      List<MockHttpServletResponse> responses =
          parallel(
              List.of(
                  ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(2)),
                  ExpenseWorld.post(ana, ExpenseWorld.MINE + "/" + id + "/withdraw", key(), ExpenseWorld.version(2))));
      List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
      assertThat(statuses.stream().filter(s -> s == 200).count()).as("round " + round + " " + statuses).isEqualTo(1);
      assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 409 || s == 404);
      assertThat(decisions(id)).isEqualTo(1);
      assertThat(w.count("SELECT count(*) FROM operations.expense WHERE id = ? AND state IN ('APPROVED','WITHDRAWN') AND version = 3", UUID.fromString(id))).isEqualTo(1);
    }
  }

  @Test
  void approvalRacingReturnYieldsExactlyOneLegalResult() throws Exception {
    for (int round = 0; round < 4; round++) {
      String id = x.create(ana, x.usd("10", 1)).get("id").asText();
      x.submit(ana, id, 1);
      List<MockHttpServletResponse> responses =
          parallel(
              List.of(
                  ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(2)),
                  ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/return", key(), Map.of("expectedVersion", 2, "reason", ExpenseWorld.REASON))));
      List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
      assertThat(statuses.stream().filter(s -> s == 200).count()).as("round " + round + " " + statuses).isEqualTo(1);
      assertThat(decisions(id)).isEqualTo(1);
    }
  }

  @Test
  void twoIdenticalApprovalsWithTheSameKeyApplyOnce() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    x.submit(ana, id, 1);
    String same = key();
    List<MockHttpServletResponse> responses =
        parallel(
            List.of(
                ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", same, ExpenseWorld.version(2)),
                ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", same, ExpenseWorld.version(2))));
    assertThat(responses).allMatch(r -> r.getStatus() == 200 || r.getStatus() == 409);
    assertThat(responses.stream().filter(r -> r.getStatus() == 200).count()).isGreaterThanOrEqualTo(1);
    assertThat(decisions(id)).isEqualTo(1);
    assertThat(w.count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = 'operations.expense.approved.v1'", w.tenant())).isEqualTo(1);
  }
}
