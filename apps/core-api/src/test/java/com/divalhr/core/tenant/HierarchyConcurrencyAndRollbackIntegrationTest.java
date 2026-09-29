package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.api.LegalEntityResponse;
import com.divalhr.core.tenant.application.CreateLegalEntityService;
import com.divalhr.core.tenant.application.CreateSiteService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

/** Concurrency and atomicity of hierarchy creation against a real PostgreSQL. */
@IntegrationTest
class HierarchyConcurrencyAndRollbackIntegrationTest {

  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private CreateLegalEntityService legalEntities;
  @Autowired private CreateSiteService sites;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private OutboxWriter outbox;

  private TenantId tenant;

  @BeforeEach
  void newTenant() throws Exception {
    tenant = new TenantId(Hierarchy.newTenant(mvc));
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  @Test
  void parallelIdenticalRequestsCreateOneLegalEntity() throws Exception {
    String key = Organizations.newKey();
    String code = Hierarchy.code("par");
    List<Object> outcomes =
        runInParallel(
            i ->
                legalEntities.create(
                    tenant,
                    "sub-parallel",
                    key,
                    CreateLegalEntityRequest.of(code, "Parallèle", "CD", "2026-01-01", null),
                    "parallel-corr-" + i));
    List<IdempotentCreate.Result<?>> results =
        outcomes.stream()
            .<IdempotentCreate.Result<?>>map(o -> (IdempotentCreate.Result<?>) o)
            .toList();
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(IdempotentCreate.Result::body).distinct()).hasSize(1);
    assertThat(count("tenant.legal_entity", "code = ?", code)).isEqualTo(1);
    UUID id = ((LegalEntityResponse) results.get(0).body()).id();
    assertThat(count("platform.audit_event", "resource_id = ?", id)).isEqualTo(1);
    assertThat(count("platform.outbox_event", "envelope ->> 'subject' = ?", id.toString()))
        .isEqualTo(1);
  }

  @Test
  void parallelDuplicateCodesYieldOneCreationAndStableConflicts() throws Exception {
    String code = Hierarchy.code("race");
    List<Object> outcomes =
        runInParallel(
            i ->
                legalEntities.create(
                    tenant,
                    "sub-race",
                    Organizations.newKey(),
                    CreateLegalEntityRequest.of(
                        i % 2 == 0 ? code : code.toLowerCase(Locale.ROOT),
                        "Course " + i,
                        "CD",
                        "2026-01-01",
                        null),
                    "race-corr-" + i));
    assertThat(outcomes.stream().filter(IdempotentCreate.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code()))
        .hasSize(PARALLEL - 1)
        .containsOnly(ErrorCode.DUPLICATE_LEGAL_ENTITY_CODE);
    assertThat(count("tenant.legal_entity", "code = ?", code)).isEqualTo(1);
  }

  @Test
  void failureAfterSiteInsertRollsEverythingBack() throws Exception {
    UUID parent =
        Hierarchy.newLegalEntity(mvc, tenant.value(), Hierarchy.code("rb"), "2026-01-01", null);
    String key = Organizations.newKey();
    String code = Hierarchy.code("rbs");
    int auditBefore = total("platform.audit_event");
    int outboxBefore = total("platform.outbox_event");
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));

    assertThatThrownBy(
            () ->
                sites.create(
                    tenant,
                    "sub-rollback",
                    key,
                    CreateSiteRequest.of(
                        parent.toString(), code, "Annulé", "Africa/Kinshasa", "2026-01-01", null),
                    "rollback-corr-0002"))
        .hasMessageContaining("simulated outbox failure");

    assertThat(count("tenant.site", "code = ?", code)).isZero();
    assertThat(total("platform.audit_event")).isEqualTo(auditBefore);
    assertThat(total("platform.outbox_event")).isEqualTo(outboxBefore);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();
  }

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

  private int total(String table) {
    Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return value == null ? 0 : value;
  }

  private int count(String table, String where, Object arg) {
    Integer value =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, arg);
    return value == null ? 0 : value;
  }
}
