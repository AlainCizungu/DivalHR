package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.tenant.api.CostCenterResponse;
import com.divalhr.core.tenant.api.CreateCostCenterRequest;
import com.divalhr.core.tenant.api.CreateDepartmentRequest;
import com.divalhr.core.tenant.api.DepartmentResponse;
import com.divalhr.core.tenant.application.CreateSiteUnitService;
import com.divalhr.core.tenant.domain.SiteUnitKind;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** Concurrency and atomicity of department and cost-center creation against real PostgreSQL. */
@IntegrationTest
class SiteUnitConcurrencyAndRollbackIntegrationTest {

  private static final int PARALLEL = 8;

  @Autowired private MockMvc mvc;
  @Autowired private CreateSiteUnitService service;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private OutboxWriter outbox;

  private TenantId tenant;
  private UUID site;

  @BeforeEach
  void hierarchy() throws Exception {
    UUID tenantId = Hierarchy.newTenant(mvc);
    tenant = new TenantId(tenantId);
    UUID legal = Hierarchy.newLegalEntity(mvc, tenantId, Hierarchy.code("le"), "2026-01-01", null);
    site = Hierarchy.newSite(mvc, tenantId, legal, Hierarchy.code("st"), "2026-01-01", null);
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  private Object create(SiteUnitResource resource, String key, String code, String name) {
    SiteUnitKind kind = SiteUnitKind.valueOf(resource.name());
    String siteId = site.toString();
    return resource == SiteUnitResource.DEPARTMENT
        ? service.create(
            kind,
            tenant,
            "sub-parallel",
            key,
            CreateDepartmentRequest.of(siteId, code, name, "2026-01-01", null),
            "parallel-corr-0001",
            DepartmentResponse.class,
            DepartmentResponse::from)
        : service.create(
            kind,
            tenant,
            "sub-parallel",
            key,
            CreateCostCenterRequest.of(siteId, code, name, "2026-01-01", null),
            "parallel-corr-0001",
            CostCenterResponse.class,
            CostCenterResponse::from);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void parallelIdenticalRequestsCreateOneEntityAuditAndEvent(SiteUnitResource resource)
      throws Exception {
    String key = Organizations.newKey();
    String code = Hierarchy.code("par");
    List<Object> outcomes = runInParallel(i -> create(resource, key, code, "Parallèle"));
    List<IdempotentCreate.Result<?>> results =
        outcomes.stream()
            .<IdempotentCreate.Result<?>>map(o -> (IdempotentCreate.Result<?>) o)
            .toList();
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(IdempotentCreate.Result::body).distinct()).hasSize(1);
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM " + resource.table + " WHERE code = ?", UUID.class, code);
    assertThat(count(resource.table, "code = ?", code)).isEqualTo(1);
    assertThat(count("platform.audit_event", "resource_id = ?", id)).isEqualTo(1);
    assertThat(count("platform.outbox_event", "envelope ->> 'subject' = ?", id.toString()))
        .isEqualTo(1);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void parallelDuplicateCodesYieldOneEntityAndStableConflicts(SiteUnitResource resource)
      throws Exception {
    String code = Hierarchy.code("race");
    List<Object> outcomes =
        runInParallel(
            i ->
                create(
                    resource,
                    Organizations.newKey(),
                    i % 2 == 0 ? code : code.toLowerCase(Locale.ROOT),
                    "Course " + i));
    assertThat(outcomes.stream().filter(IdempotentCreate.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .hasSize(PARALLEL - 1)
        .containsOnly(resource.duplicateCode);
    assertThat(count(resource.table, "code = ?", code)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void failureAfterInsertRollsEverythingBack(SiteUnitResource resource) {
    String key = Organizations.newKey();
    String code = Hierarchy.code("rb");
    int auditBefore = total("platform.audit_event");
    int outboxBefore = total("platform.outbox_event");
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));

    assertThatThrownBy(() -> create(resource, key, code, "Annulé"))
        .hasMessageContaining("simulated outbox failure");

    assertThat(count(resource.table, "code = ?", code)).isZero();
    assertThat(total("platform.audit_event")).isEqualTo(auditBefore);
    assertThat(total("platform.outbox_event")).isEqualTo(outboxBefore);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void rejectedRequestsDoNotConsumeTheKey(SiteUnitResource resource) {
    String key = Organizations.newKey();
    // Missing site: rolled back, key free for a corrected retry.
    SiteUnitKind kind = SiteUnitKind.valueOf(resource.name());
    UUID missing = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.create(
                    kind,
                    tenant,
                    "sub-key",
                    key,
                    CreateDepartmentRequest.of(
                        missing.toString(), Hierarchy.code("k"), "Clé", "2026-01-01", null),
                    "key-corr-00001",
                    DepartmentResponse.class,
                    DepartmentResponse::from))
        .isInstanceOf(ApiException.class);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();
    create(resource, key, Hierarchy.code("k2"), "Corrigé");
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);
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
