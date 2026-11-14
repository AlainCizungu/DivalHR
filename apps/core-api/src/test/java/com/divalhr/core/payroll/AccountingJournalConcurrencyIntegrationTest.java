package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.application.AccountingRecorder;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-096 (Issue #194, ADR 0039 section 5) against real PostgreSQL: two and eight simultaneous
 * commits of the same package journal with different idempotency keys create exactly one journal
 * with its lines, audit row and event, and every other commit is the stable {@code
 * ACCOUNTING_JOURNAL_ALREADY_EXISTS} conflict; the same key replays the original receipt
 * identically. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.accounting-preview=600",
      "divalhr.request-limits.subject.accounting-journal-write=600",
      "divalhr.request-limits.subject.accounting-mapping-write=600",
      "divalhr.request-limits.subject.accounting-read=600",
      "divalhr.request-limits.tenant.accounting-journal-write.requests=1000",
      "divalhr.request-limits.tenant.accounting-mapping-write.requests=1000",
      "divalhr.request-limits.tenant.accounting-read.requests=1000"
    })
class AccountingJournalConcurrencyIntegrationTest {

  private static final String ALREADY_EXISTS = "ACCOUNTING_JOURNAL_ALREADY_EXISTS";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private AccountingWorld a;

  /** One answer of a racing commit. */
  private record Answer(String key, int status, String code, String body, String replayed) {
    String outcome() {
      return status + " " + code;
    }
  }

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    a = new AccountingWorld(w, w.approve(w.ready()).get("packageId").asText());
    a.createMapping("2000-01-01");
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static List<String> distinctKeys(int count) {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      keys.add(Organizations.newKey());
    }
    return keys;
  }

  private List<Answer> race(List<String> keys, Map<String, Object> body) throws Exception {
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    for (String key : keys) {
      calls.add(a.generate(key, body));
    }
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return w.c.call(call);
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<Answer> answers = new ArrayList<>();
      for (int i = 0; i < futures.size(); i++) {
        MockHttpServletResponse response = futures.get(i).get(60, TimeUnit.SECONDS);
        String text = response.getContentAsString();
        JsonNode json = CompensationWorld.JSON.readTree(text);
        String code =
            response.getStatus() >= 300 && json.has("code") ? json.get("code").asText() : "";
        String replayed = response.getHeader("Idempotent-Replayed");
        answers.add(new Answer(keys.get(i), response.getStatus(), code, text, replayed));
      }
      return answers;
    } finally {
      pool.shutdownNow();
    }
  }

  private static long created(List<Answer> answers) {
    return answers.stream().filter(answer -> answer.status() == 201).count();
  }

  private static Answer winner(List<Answer> answers) {
    return answers.stream().filter(answer -> answer.status() == 201).findFirst().orElseThrow();
  }

  private void assertOneWinnerAndStableConflicts(List<Answer> answers, int racers) {
    List<String> outcomes = answers.stream().map(Answer::outcome).toList();
    assertThat(created(answers)).as(outcomes.toString()).isEqualTo(1);
    assertThat(answers)
        .filteredOn(answer -> answer.status() != 201)
        .hasSize(racers - 1)
        .allSatisfy(
            answer -> {
              assertThat(answer.status()).as(answer.outcome()).isEqualTo(409);
              assertThat(answer.code()).isEqualTo(ALREADY_EXISTS);
            });
  }

  private void assertOneJournalWithItsEvidence(JsonNode preview, Answer winner) throws Exception {
    assertThat(a.journalCount()).isEqualTo(1);
    assertThat(a.lineCount()).isEqualTo(preview.get("lineCount").asInt());
    assertThat(w.audits(AccountingRecorder.JOURNAL_GENERATE)).isEqualTo(1);
    assertThat(a.events(AccountingRecorder.JOURNAL_GENERATED_EVENT)).isEqualTo(1);
    String journal = CompensationWorld.JSON.readTree(winner.body()).get("journalId").asText();
    JsonNode view = w.c.expect(PayrollPackageWorld.get(w.admin, a.journalPath(journal)), 200);
    assertThat(view.get("lines")).hasSize(preview.get("lineCount").asInt());
  }

  private void assertReplaysIdentically(Answer original, Map<String, Object> body)
      throws Exception {
    MockHttpServletResponse replay = w.c.call(a.generate(original.key(), body));
    assertThat(replay.getStatus()).as(replay.getContentAsString()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(CompensationWorld.JSON.readTree(replay.getContentAsString()))
        .isEqualTo(CompensationWorld.JSON.readTree(original.body()));
    // A replay writes nothing.
    assertThat(a.journalCount()).isEqualTo(1);
    assertThat(w.audits(AccountingRecorder.JOURNAL_GENERATE)).isEqualTo(1);
    assertThat(a.events(AccountingRecorder.JOURNAL_GENERATED_EVENT)).isEqualTo(1);
  }

  @Test
  void twoSimultaneousCommitsWithDifferentKeysCreateOneJournal() throws Exception {
    JsonNode preview = a.journalPreview();
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    Map<String, Object> body = AccountingWorld.journalBody(preview);

    List<Answer> answers = race(distinctKeys(2), body);

    assertOneWinnerAndStableConflicts(answers, 2);
    assertOneJournalWithItsEvidence(preview, winner(answers));
    assertReplaysIdentically(winner(answers), body);
  }

  @Test
  void eightSimultaneousCommitsWithDifferentKeysCreateOneJournal() throws Exception {
    JsonNode preview = a.journalPreview();
    assertThat(preview.get("status").asText()).as(preview.toString()).isEqualTo("READY");
    Map<String, Object> body = AccountingWorld.journalBody(preview);

    List<Answer> answers = race(distinctKeys(8), body);

    assertOneWinnerAndStableConflicts(answers, 8);
    assertOneJournalWithItsEvidence(preview, winner(answers));
    assertReplaysIdentically(winner(answers), body);
    // A later commit with yet another key is the same stable conflict.
    w.c.problem(a.generate(Organizations.newKey(), body), 409, ALREADY_EXISTS);
    assertThat(a.journalCount()).isEqualTo(1);
  }

  @Test
  void theSameKeyRacingCreatesOneJournalAndEveryAnswerIsTheOriginalReceipt() throws Exception {
    JsonNode preview = a.journalPreview();
    Map<String, Object> body = AccountingWorld.journalBody(preview);
    String key = Organizations.newKey();

    List<Answer> answers = race(List.of(key, key, key, key), body);

    assertThat(created(answers)).as(answers.toString()).isGreaterThanOrEqualTo(1);
    assertThat(answers).noneMatch(answer -> answer.status() >= 500);
    JsonNode first = CompensationWorld.JSON.readTree(winner(answers).body());
    for (Answer answer : answers) {
      if (answer.status() == 201) {
        assertThat(CompensationWorld.JSON.readTree(answer.body())).isEqualTo(first);
      }
    }
    assertOneJournalWithItsEvidence(preview, winner(answers));
    assertReplaysIdentically(winner(answers), body);
  }

  @Test
  void aMixOfSharedAndDistinctKeysStillCreatesExactlyOneJournal() throws Exception {
    JsonNode preview = a.journalPreview();
    Map<String, Object> body = AccountingWorld.journalBody(preview);
    String shared = Organizations.newKey();
    String other = Organizations.newKey();

    List<Answer> answers = race(List.of(shared, shared, other, other), body);

    assertThat(answers).noneMatch(answer -> answer.status() >= 500);
    Set<String> journals = new HashSet<>();
    for (Answer answer : answers) {
      if (answer.status() == 201) {
        journals.add(CompensationWorld.JSON.readTree(answer.body()).get("journalId").asText());
      }
    }
    assertThat(journals).hasSize(1);
    assertOneJournalWithItsEvidence(preview, winner(answers));
  }
}
