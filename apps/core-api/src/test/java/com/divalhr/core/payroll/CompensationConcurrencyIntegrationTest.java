package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.CompensationWorld.JSON;
import static com.divalhr.core.payroll.CompensationWorld.command;
import static com.divalhr.core.payroll.CompensationWorld.commitRequest;
import static com.divalhr.core.payroll.CompensationWorld.committing;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.CompensationWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-093A (Issue #175, D93-8 and D93-6) against real PostgreSQL: commands that race on one
 * employee, each with its own Idempotency-Key and the same preview, produce exactly one recorded
 * change, one stable conflict for every other caller and one legal timeline with no partial row.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class CompensationConcurrencyIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private CompensationWorld w;
  private Person ana;

  @BeforeEach
  void world() throws Exception {
    w = new CompensationWorld(mvc, jdbc, lookups);
    ana = w.person("Ana", "Mukendi");
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private List<String> parallel(List<MockHttpServletRequestBuilder> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (MockHttpServletRequestBuilder call : calls) {
        Callable<MockHttpServletResponse> task =
            () -> {
              start.await();
              return w.call(call);
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS);
        JsonNode body = JSON.readTree(response.getContentAsString());
        outcomes.add(
            response.getStatus() >= 300
                ? response.getStatus() + " " + body.get("code").asText()
                : String.valueOf(response.getStatus()));
      }
      return outcomes;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void threeCommitsOfTheSamePreviewRecordOneChangeAndTheOthersAreVersionConflicts()
      throws Exception {
    Map<String, Object> establish = command("ESTABLISHED", w.today, "USD", "1250.00");
    JsonNode preview = w.preview(ana.employee(), establish);
    Map<String, Object> body = committing(establish, preview, false);
    List<Integer> before = w.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                commitRequest(w.admin, ana.employee(), Organizations.newKey(), body),
                commitRequest(w.admin, ana.employee(), Organizations.newKey(), body),
                commitRequest(w.admin, ana.employee(), Organizations.newKey(), body)));

    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            "201", "409 COMPENSATION_VERSION_CONFLICT", "409 COMPENSATION_VERSION_CONFLICT");
    List<Integer> after = w.evidence();
    // One change, one fact, one audit record, one event.
    assertThat(after.get(0)).isEqualTo(before.get(0) + 1);
    assertThat(after.get(1)).isEqualTo(before.get(1) + 1);
    assertThat(after.get(2)).isEqualTo(before.get(2) + 1);
    assertThat(after.get(3)).isEqualTo(before.get(3) + 1);
    JsonNode read = w.read(ana.employee());
    assertThat(read.get("version").asInt()).isEqualTo(1);
    assertThat(read.get("current").get("money").get("amount").asText()).isEqualTo("1250.00");
  }

  @Test
  void theSameKeyAndPayloadRacingRecordsExactlyOneChange() throws Exception {
    Map<String, Object> establish = command("ESTABLISHED", w.today, "CDF", "2500000");
    JsonNode preview = w.preview(ana.employee(), establish);
    Map<String, Object> body = committing(establish, preview, false);
    String key = Organizations.newKey();
    List<Integer> before = w.evidence();

    List<String> outcomes =
        parallel(
            List.of(
                commitRequest(w.admin, ana.employee(), key, body),
                commitRequest(w.admin, ana.employee(), key, body)));

    assertThat(outcomes).contains("201").noneMatch(outcome -> outcome.startsWith("5"));
    assertThat(w.evidence().get(0)).isEqualTo(before.get(0) + 1);
    assertThat(w.read(ana.employee()).get("version").asInt()).isEqualTo(1);
  }
}
