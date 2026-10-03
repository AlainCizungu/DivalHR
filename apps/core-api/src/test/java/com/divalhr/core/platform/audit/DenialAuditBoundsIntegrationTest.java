package com.divalhr.core.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.platform.web.CorrelationId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.TestTokens;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP-013 (D6, A13-2, A13-6): flood bounds. Small budgets make the bounds observable: each actor
 * produces at most three rows per window, an instance at most forty, and a rate-limited caller one
 * row per window. Suppressed attempts never reach the denial store and are telemetry only.
 *
 * <p>Budgets use fixed one-minute windows; each test first makes sure enough of the current window
 * is left, so its requests share one window. The instance ceiling is shared by every test of the
 * class, so the test that exhausts it runs last.
 */
@IntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "divalhr.access-review.requests-per-minute=2",
      "divalhr.denial-audit.per-actor-per-minute=3",
      "divalhr.denial-audit.per-instance-per-minute=40"
    })
class DenialAuditBoundsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private DenialAuditStore store;
  @Autowired private DataSource dataSource;

  private UUID tenant;

  @BeforeEach
  void tenant() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
  }

  /** Waits for the next window when fewer than 15 seconds of the current one are left. */
  private static void headroom() throws InterruptedException {
    int second = LocalTime.now(ZoneOffset.UTC).getSecond();
    if (second >= 45) {
      Thread.sleep((61 - second) * 1000L);
    }
  }

  private static long minute() {
    return Instant.now().getEpochSecond() / 60;
  }

  private static String subject(String prefix) {
    return "sub-bounds-" + prefix + "-" + UUID.randomUUID();
  }

  private static String employeeToken(UUID tenant, String subject) {
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(List.of("employee")).build();
  }

  private int rowsOf(String actor, String stage) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM platform.authorization_denial WHERE actor_subject = ? AND stage = ?",
        Integer.class,
        actor,
        stage);
  }

  private double outcome(String outcome, String stage) {
    Counter counter =
        meters
            .find(AuthorizationDenialAudit.METRIC)
            .tags("outcome", outcome, "stage", stage, "scope", "tenant")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private int deny(String actor) throws Exception {
    return mvc.perform(
            get("/api/v1/legal-entities").header("Authorization", employeeToken(tenant, actor)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @Order(1)
  void eachActorWritesAtMostItsBudgetAndSuppressedDenialsNeverTouchTheStore() throws Exception {
    headroom();
    String actor = subject("budget");
    Memberships.grant(tenant, actor, "employee");
    long attempts = store.writeAttempts();
    double suppressed = outcome("suppressed", "role");
    long window = minute();
    for (int i = 0; i < 6; i++) {
      assertThat(deny(actor)).isEqualTo(403);
    }
    if (minute() == window) {
      assertThat(rowsOf(actor, "role")).isEqualTo(3);
      assertThat(outcome("suppressed", "role")).isEqualTo(suppressed + 3);
      assertThat(store.writeAttempts()).isEqualTo(attempts + 3);
    }
  }

  @Test
  @Order(2)
  void concurrentDenialsOfOneActorStayWithinTheBudget() throws Exception {
    headroom();
    String actor = subject("concurrent");
    Memberships.grant(tenant, actor, "employee");
    long window = minute();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 24; i++) {
        calls.add(() -> deny(actor));
      }
      for (Future<Integer> result : pool.invokeAll(calls)) {
        assertThat(result.get()).isEqualTo(403);
      }
    } finally {
      pool.shutdownNow();
    }
    if (minute() == window) {
      assertThat(rowsOf(actor, "role")).isEqualTo(3);
    }
    assertThat(rowsOf(actor, "role")).isLessThanOrEqualTo(6);
  }

  @Test
  @Order(5)
  void theInstanceCeilingBoundsManyActors() throws Exception {
    headroom();
    long window = minute();
    double written = outcome("written", "role");
    double suppressed = outcome("suppressed", "role");
    for (int i = 0; i < 60; i++) {
      String actor = subject("ceiling");
      Memberships.grant(tenant, actor, "employee");
      assertThat(deny(actor)).isEqualTo(403);
    }
    if (minute() == window) {
      assertThat(outcome("written", "role") - written).isLessThanOrEqualTo(40);
      assertThat(outcome("suppressed", "role") - suppressed).isGreaterThanOrEqualTo(20);
      assertThat(outcome("written", "role") - written + outcome("suppressed", "role") - suppressed)
          .isEqualTo(60);
    }
  }

  @Test
  @Order(3)
  void onlyTheFirstRefusalPerWindowIsRecordedWithTheEffectiveTenant(CapturedOutput output)
      throws Exception {
    headroom();
    String admin = subject("limited");
    String bearer = Hierarchy.bearer(tenant, admin, "tenant-admin");
    long window = minute();
    List<Integer> statuses = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      MvcResult result =
          mvc.perform(
                  get("/api/v1/access-review/summary")
                      .header("Authorization", bearer)
                      .header(CorrelationId.HEADER, "bounds-" + i + "-" + UUID.randomUUID()))
              .andReturn();
      statuses.add(result.getResponse().getStatus());
      if (result.getResponse().getStatus() == 429) {
        assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
      }
    }
    if (minute() == window) {
      assertThat(statuses).containsExactly(200, 200, 429, 429, 429, 429);
      assertThat(rowsOf(admin, "rate_limit")).isEqualTo(1);
    }
    assertThat(rowsOf(admin, "rate_limit")).isBetween(1, 2);
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM platform.authorization_denial"
                    + " WHERE actor_subject = ? AND stage = 'rate_limit'",
                UUID.class,
                admin))
        .containsExactly(tenant);
    assertThat(output.getAll())
        .contains("\"audit\":\"written\"")
        .contains("\"audit\":\"not_first\"")
        .doesNotContain(admin);
  }

  @Test
  @Order(4)
  void aStorageFailureKeepsThe429AndItsRetryAfter() throws Exception {
    headroom();
    String admin = subject("limited-outage");
    String bearer = Hierarchy.bearer(tenant, admin, "tenant-admin");
    double failed = outcome("failed", "rate_limit");
    long window = minute();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute("LOCK TABLE platform.authorization_denial IN ACCESS EXCLUSIVE MODE");
      }
      try {
        List<MvcResult> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
          results.add(
              mvc.perform(get("/api/v1/access-review/summary").header("Authorization", bearer))
                  .andReturn());
        }
        MvcResult limited = results.get(2);
        if (minute() == window) {
          assertThat(limited.getResponse().getStatus()).isEqualTo(429);
          assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
          assertThat(limited.getResponse().getContentAsString()).contains("RATE_LIMITED");
        }
      } finally {
        lock.rollback();
      }
    }
    if (minute() == window) {
      assertThat(outcome("failed", "rate_limit")).isEqualTo(failed + 1);
      assertThat(rowsOf(admin, "rate_limit")).isZero();
    }
  }
}
