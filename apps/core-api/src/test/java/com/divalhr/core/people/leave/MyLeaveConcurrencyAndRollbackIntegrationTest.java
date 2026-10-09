package com.divalhr.core.people.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.CreateMyLeaveRequest;
import com.divalhr.core.people.leave.application.MyLeaveService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-041A concurrency and atomicity against real PostgreSQL (AC5, AC7): concurrent overlapping
 * requests of one employee give exactly one pending request and stable overlap conflicts;
 * concurrent identical retries give one request, one audit and one event; when the outbox fails,
 * the request, its audit and the idempotency key all roll back together.
 */
@IntegrationTest
class MyLeaveConcurrencyAndRollbackIntegrationTest {

  private static final int PARALLEL = 8;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private MyLeaveService leave;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @MockitoSpyBean private OutboxWriter outbox;

  private UUID tenant;
  private PeopleCaller caller;
  private String policyId;
  private LocalDate today;

  @BeforeEach
  void linkedEmployee() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    tenant = org.tenant();
    String admin = Hierarchy.bearer(tenant, "sub-leave41-par-" + UUID.randomUUID(), "tenant-admin");
    today = Employees.today(jdbc, tenant);
    UUID employee =
        Employees.hire(
            mvc,
            jdbc,
            org,
            admin,
            Employees.number(),
            "Zébulon",
            "Kalala",
            LocalDate.of(2026, 3, 1));
    String subject = UUID.randomUUID().toString();
    UUID membership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        membership,
        tenant,
        subject,
        lookups.of(
            EmailAddress.parse(
                    "par-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd")
                .orElseThrow()));
    mvc.perform(
            Employees.postJson(
                    admin,
                    "/api/v1/employees/" + employee + "/access-link",
                    "{\"membershipId\":\"" + membership + "\"}")
                .header("Idempotency-Key", Organizations.newKey()))
        .andExpect(status().isCreated());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", Hierarchy.code("LV"));
    body.put("names", Map.of("en", "Annual leave", "fr", "Congé annuel"));
    body.put("unit", "DAYS");
    body.put("balanceMode", "UNTRACKED");
    body.put("minimumServiceDays", 0);
    body.put("approvalRoute", "MANAGER");
    body.put("payrollEffect", "PAID");
    body.put("effectiveFrom", "2026-01-01");
    String created =
        mvc.perform(
                post("/api/v1/leave-policies")
                    .header("Authorization", admin)
                    .header("Idempotency-Key", Organizations.newKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    policyId = JSON.readTree(created).get("policy").get("id").asText();
    caller = new PeopleCaller(new TenantId(tenant), subject, "leave41-parallel-0001");
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  private CreateMyLeaveRequest request(int fromDays, int toDays) {
    CreateMyLeaveRequest request = new CreateMyLeaveRequest();
    request.setPolicyId(policyId);
    request.setStartDate(today.plusDays(fromDays).toString());
    request.setEndDate(today.plusDays(toDays).toString());
    request.setAmount(2);
    return request;
  }

  @Test
  void concurrentOverlappingRequestsLeaveExactlyOnePending() throws Exception {
    // Every attempt overlaps every other: day 10 is in all of them.
    List<Object> outcomes =
        runInParallel(i -> leave.create(caller, Organizations.newKey(), request(10 - i, 10 + i)));
    assertThat(outcomes.stream().filter(IdempotentOperation.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .hasSize(PARALLEL - 1)
        .containsOnly("LEAVE_REQUEST_OVERLAP");
    assertThat(count("people.leave_request")).isEqualTo(1);
    assertThat(audits()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
  }

  @Test
  void concurrentIdenticalRetriesCreateOneRequestAuditAndEvent() throws Exception {
    String key = Organizations.newKey();
    List<Object> outcomes = runInParallel(i -> leave.create(caller, key, request(1, 2)));
    List<IdempotentOperation.Result<?>> results =
        outcomes.stream()
            .filter(IdempotentOperation.Result.class::isInstance)
            .<IdempotentOperation.Result<?>>map(o -> (IdempotentOperation.Result<?>) o)
            .toList();
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(IdempotentOperation.Result::body).distinct()).hasSize(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .doesNotContain("LEAVE_REQUEST_OVERLAP");
    assertThat(count("people.leave_request")).isEqualTo(1);
    assertThat(audits()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
  }

  @Test
  void anOutboxFailureRollsBackTheRequestTheAuditAndTheKey() {
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));
    String key = Organizations.newKey();
    assertThatThrownBy(() -> leave.create(caller, key, request(1, 1)))
        .hasMessageContaining("simulated outbox failure");
    assertThat(count("people.leave_request")).isZero();
    assertThat(audits()).isZero();
    assertThat(events()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.idempotency_record WHERE idempotency_key = ?",
                Integer.class,
                key))
        .isZero();
    reset(target);
    assertThat(leave.create(caller, key, request(1, 1)).replayed()).isFalse();
    assertThat(count("people.leave_request")).isEqualTo(1);
    assertThat(audits()).isEqualTo(1);
    assertThat(events()).isEqualTo(1);
  }

  private int count(String table) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    return value == null ? 0 : value;
  }

  private int audits() {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'leave-request.create'",
            Integer.class,
            tenant);
    return value == null ? 0 : value;
  }

  private int events() {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type = 'people.leave-request.created.v1'",
            Integer.class,
            tenant);
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
