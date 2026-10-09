package com.divalhr.core.people.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.CreateLeavePolicyRequest;
import com.divalhr.core.people.leave.application.LeavePolicyService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-040A concurrency and atomicity against real PostgreSQL (AC8, AC9): concurrent duplicate codes
 * give one policy and stable conflicts with no partial rows; concurrent identical retries give one
 * policy, audit and event; when the outbox fails, the policy, its version, the audit record and the
 * idempotency key all roll back together.
 */
@IntegrationTest
class LeavePolicyConcurrencyAndRollbackIntegrationTest {

  private static final int PARALLEL = 8;
  private static final String CORRELATION = "leave-parallel-0001";

  @Autowired private MockMvc mvc;
  @Autowired private LeavePolicyService policies;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private OutboxWriter outbox;

  private UUID tenantId;
  private PeopleCaller caller;

  @BeforeEach
  void organization() throws Exception {
    tenantId = Hierarchy.newTenant(mvc);
    caller = new PeopleCaller(new TenantId(tenantId), "sub-leave-parallel", CORRELATION);
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  private static CreateLeavePolicyRequest request(String code) {
    CreateLeavePolicyRequest request = new CreateLeavePolicyRequest();
    request.setCode(code);
    request.setNames(Map.of("en", "Annual leave", "fr", "Congé annuel"));
    request.setUnit("DAYS");
    request.setBalanceMode("TRACKED");
    request.setAnnualEntitlement(25);
    request.setMinimumServiceDays(0);
    request.setApprovalRoute("MANAGER");
    request.setPayrollEffect("PAID");
    request.setEffectiveFrom("2026-01-01");
    return request;
  }

  @Test
  void concurrentDuplicateCodesCreateOnePolicyAndStableConflicts() throws Exception {
    String code = Hierarchy.code("RACE");
    List<Object> outcomes =
        runInParallel(
            i ->
                policies.create(
                    caller,
                    Organizations.newKey(),
                    request(i % 2 == 0 ? code : code.toLowerCase(Locale.ROOT))));
    assertThat(outcomes.stream().filter(IdempotentOperation.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .hasSize(PARALLEL - 1)
        .containsOnly("LEAVE_POLICY_CODE_EXISTS");
    assertThat(count("people.leave_policy")).isEqualTo(1);
    assertThat(count("people.leave_policy_version")).isEqualTo(1);
    assertThat(audits()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
    assertThat(keys()).isEqualTo(1);
  }

  @Test
  void concurrentIdenticalRetriesCreateOnePolicyAuditAndEvent() throws Exception {
    String key = Organizations.newKey();
    String code = Hierarchy.code("SAME");
    List<Object> outcomes = runInParallel(i -> policies.create(caller, key, request(code)));
    List<IdempotentOperation.Result<?>> results =
        outcomes.stream()
            .filter(IdempotentOperation.Result.class::isInstance)
            .<IdempotentOperation.Result<?>>map(o -> (IdempotentOperation.Result<?>) o)
            .toList();
    // Retries racing the first reservation either replay or are told the key is in use.
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(IdempotentOperation.Result::body).distinct()).hasSize(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .doesNotContain("LEAVE_POLICY_CODE_EXISTS");
    assertThat(count("people.leave_policy")).isEqualTo(1);
    assertThat(audits()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
    assertThat(keys()).isEqualTo(1);
  }

  @Test
  void anOutboxFailureRollsBackThePolicyItsVersionTheAuditAndTheKey() {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));
    String key = Organizations.newKey();
    String code = Hierarchy.code("ATOM");
    assertThatThrownBy(() -> policies.create(caller, key, request(code)))
        .hasMessageContaining("simulated outbox failure");
    assertThat(count("people.leave_policy")).isZero();
    assertThat(count("people.leave_policy_version")).isZero();
    assertThat(audits()).isZero();
    assertThat(events()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isZero();

    // The key was never consumed: the same command succeeds once the outbox works again.
    reset(target);
    IdempotentOperation.Result<?> retried = policies.create(caller, key, request(code));
    assertThat(retried.replayed()).isFalse();
    assertThat(count("people.leave_policy")).isEqualTo(1);
    assertThat(audits()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
  }

  private int count(String table) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenantId);
    return value == null ? 0 : value;
  }

  private int audits() {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'leave-policy.create'",
            Integer.class,
            tenantId);
    return value == null ? 0 : value;
  }

  private int events() {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type = 'people.leave-policy.created.v1'",
            Integer.class,
            tenantId);
    return value == null ? 0 : value;
  }

  private int keys() {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.idempotency_record r"
                + " WHERE r.operation = 'leave-policy.create' AND r.principal = ?"
                + " AND r.state = 'COMPLETED' AND EXISTS (SELECT 1 FROM people.leave_policy p"
                + " WHERE p.tenant_id = ? AND p.id = r.resource_id)",
            Integer.class,
            caller.subject(),
            tenantId);
    return value == null ? 0 : value;
  }

  @FunctionalInterface
  private interface Attempt {
    Object run(int index) throws Exception;
  }

  private static List<Object> runInParallel(Attempt attempt) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < PARALLEL; i++) {
        int index = i;
        Callable<Object> task =
            () -> {
              start.await();
              try {
                return attempt.run(index);
              } catch (ApiException e) {
                return e;
              }
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      List<Object> results = new ArrayList<>();
      for (Future<Object> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }
}
