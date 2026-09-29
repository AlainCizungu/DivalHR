package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.idempotency.IdempotentCreate;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.tenant.api.AssignSiteRegionRequest;
import com.divalhr.core.tenant.api.CreateDepartmentRequest;
import com.divalhr.core.tenant.api.CreateRegionRequest;
import com.divalhr.core.tenant.api.DepartmentResponse;
import com.divalhr.core.tenant.api.RegionResponse;
import com.divalhr.core.tenant.api.SiteResponse;
import com.divalhr.core.tenant.application.AssignSiteRegionService;
import com.divalhr.core.tenant.application.CreateRegionService;
import com.divalhr.core.tenant.application.CreateSiteUnitService;
import com.divalhr.core.tenant.domain.SiteUnitKind;
import java.sql.Date;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Concurrency and atomicity of region creation and first site-region assignment against real
 * PostgreSQL: one winner, stable conflicts, the parent-before-child lock order, and all-or-nothing
 * transactions that never consume a key on failure.
 */
@IntegrationTest
class RegionConcurrencyAndRollbackIntegrationTest {

  private static final int PARALLEL = 8;
  private static final String SUBJECT = "sub-parallel";
  private static final String CORRELATION = "parallel-corr-0001";

  @Autowired private MockMvc mvc;
  @Autowired private CreateRegionService regions;
  @Autowired private AssignSiteRegionService assignments;
  @Autowired private CreateSiteUnitService units;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private OutboxWriter outbox;

  private UUID tenantId;
  private TenantId tenant;
  private UUID legalEntity;

  @BeforeEach
  void hierarchy() throws Exception {
    tenantId = Hierarchy.newTenant(mvc);
    tenant = new TenantId(tenantId);
    legalEntity = Hierarchy.newLegalEntity(mvc, tenantId, Hierarchy.code("le"), "2026-01-01", null);
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  private IdempotentCreate.Result<RegionResponse> createRegion(
      String key, String code, String from, String to) {
    return regions.create(
        tenant,
        SUBJECT,
        key,
        CreateRegionRequest.of(legalEntity.toString(), code, "Région " + code, from, to),
        CORRELATION);
  }

  private UUID region(String from, String to) {
    return createRegion(Organizations.newKey(), Hierarchy.code("rg"), from, to).body().id();
  }

  private UUID site(String from, String to) throws Exception {
    return Hierarchy.newSite(mvc, tenantId, legalEntity, Hierarchy.code("st"), from, to);
  }

  private IdempotentOperation.Result<SiteResponse> assign(String key, UUID site, UUID region) {
    return assignments.assign(
        tenant,
        SUBJECT,
        site.toString(),
        key,
        AssignSiteRegionRequest.of(region.toString()),
        CORRELATION);
  }

  @Test
  void parallelIdenticalRegionCreatesYieldOneRegionAuditAndEvent() throws Exception {
    String key = Organizations.newKey();
    String code = Hierarchy.code("par");
    List<Object> outcomes = runInParallel(i -> createRegion(key, code, "2026-01-01", null));
    List<IdempotentCreate.Result<?>> results =
        outcomes.stream()
            .<IdempotentCreate.Result<?>>map(o -> (IdempotentCreate.Result<?>) o)
            .toList();
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(IdempotentCreate.Result::body).distinct()).hasSize(1);
    UUID id = jdbc.queryForObject("SELECT id FROM tenant.region WHERE code = ?", UUID.class, code);
    assertThat(count("tenant.region", "code = ?", code)).isEqualTo(1);
    assertThat(count("platform.audit_event", "resource_id = ?", id)).isEqualTo(1);
    assertThat(count("platform.outbox_event", "envelope ->> 'subject' = ?", id.toString()))
        .isEqualTo(1);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);
  }

  @Test
  void parallelDuplicateRegionCodesYieldOneRegionAndStableConflicts() throws Exception {
    String code = Hierarchy.code("race");
    List<Object> outcomes =
        runInParallel(
            i ->
                createRegion(
                    Organizations.newKey(),
                    i % 2 == 0 ? code : code.toLowerCase(Locale.ROOT),
                    "2026-01-01",
                    null));
    assertThat(outcomes.stream().filter(IdempotentCreate.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .hasSize(PARALLEL - 1)
        .containsOnly("DUPLICATE_REGION_CODE");
    assertThat(count("tenant.region", "code = ?", code)).isEqualTo(1);
  }

  @Test
  void parallelAssignmentsOfDifferentRegionsYieldOneWinnerAndStableConflicts() throws Exception {
    UUID site = site("2026-02-01", "2026-03-31");
    List<UUID> candidates = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      candidates.add(region("2026-01-01", null));
    }
    List<Object> outcomes =
        runInParallel(i -> assign(Organizations.newKey(), site, candidates.get(i)));
    List<IdempotentOperation.Result<?>> winners =
        outcomes.stream()
            .filter(IdempotentOperation.Result.class::isInstance)
            .<IdempotentOperation.Result<?>>map(o -> (IdempotentOperation.Result<?>) o)
            .toList();
    assertThat(winners).hasSize(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .hasSize(PARALLEL - 1)
        .containsOnly("SITE_REGION_ALREADY_ASSIGNED");
    SiteResponse winner = (SiteResponse) winners.get(0).body();
    assertThat(
            jdbc.queryForObject("SELECT region_id FROM tenant.site WHERE id = ?", UUID.class, site))
        .isEqualTo(winner.regionId());
    assertThat(
            count(
                "platform.audit_event", "action = 'site.region.assign' AND resource_id = ?", site))
        .isEqualTo(1);
    assertThat(
            count(
                "platform.outbox_event",
                "event_type = 'tenant.site-region-assigned.v1' AND envelope ->> 'subject' = ?",
                site.toString()))
        .isEqualTo(1);
    assertThat(
            count(
                "platform.idempotency_record",
                "operation = 'site.region.assign' AND resource_id = ?",
                site))
        .isEqualTo(1);
  }

  @Test
  void parallelAssignmentsOfTheSameRegionWithNewKeysAuditOnce() throws Exception {
    UUID site = site("2026-02-01", "2026-03-31");
    UUID region = region("2026-01-01", null);
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < PARALLEL; i++) {
      keys.add(Organizations.newKey());
    }
    List<Object> outcomes = runInParallel(i -> assign(keys.get(i), site, region));
    assertThat(outcomes).allMatch(IdempotentOperation.Result.class::isInstance);
    assertThat(outcomes.stream().map(o -> ((IdempotentOperation.Result<?>) o).body()).distinct())
        .hasSize(1);
    assertThat(outcomes)
        .allSatisfy(o -> assertThat(((IdempotentOperation.Result<?>) o).replayed()).isFalse());
    assertThat(
            count(
                "platform.audit_event", "action = 'site.region.assign' AND resource_id = ?", site))
        .isEqualTo(1);
    assertThat(
            count(
                "platform.outbox_event",
                "event_type = 'tenant.site-region-assigned.v1' AND envelope ->> 'subject' = ?",
                site.toString()))
        .isEqualTo(1);
    assertThat(
            count(
                "platform.idempotency_record",
                "operation = 'site.region.assign' AND state = 'COMPLETED'"
                    + " AND response_status = 200 AND resource_id = ?",
                site))
        .isEqualTo(PARALLEL);
    assertThat(
            jdbc.queryForObject("SELECT version FROM tenant.site WHERE id = ?", Long.class, site))
        .isEqualTo(1L);
  }

  @Test
  void assignmentRacingARegionNarrowingNeverStrandsTheSite() throws Exception {
    for (int round = 0; round < 5; round++) {
      UUID region = region("2026-01-01", "2026-12-31");
      UUID site = site("2026-02-01", "2026-11-30");
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<Object> assignment =
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return assign(Organizations.newKey(), site, region);
                  } catch (ApiException rejected) {
                    return rejected;
                  }
                });
        Future<Object> narrowing =
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return jdbc.update(
                        "UPDATE tenant.region SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-06-30"),
                        region);
                  } catch (DataAccessException rejected) {
                    return rejected;
                  }
                });
        start.countDown();
        Object assigned = assignment.get(30, TimeUnit.SECONDS);
        Object narrowed = narrowing.get(30, TimeUnit.SECONDS);
        boolean assignedOk = assigned instanceof IdempotentOperation.Result<?>;
        boolean narrowedOk = narrowed instanceof Integer;
        assertThat(assignedOk && narrowedOk).as("both succeeded in round %d", round).isFalse();
        assertThat(assignedOk || narrowedOk).as("neither succeeded in round %d", round).isTrue();
        if (!assignedOk) {
          assertThat(((ApiException) assigned).code().name())
              .isEqualTo("SITE_PERIOD_OUTSIDE_REGION");
        }
        Integer stranded =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM tenant.site s JOIN tenant.region r
                  ON r.tenant_id = s.tenant_id AND r.id = s.region_id
                 WHERE s.id = ? AND (s.effective_from < r.effective_from
                   OR (r.effective_to IS NOT NULL
                       AND (s.effective_to IS NULL OR s.effective_to > r.effective_to)))
                """,
                Integer.class,
                site);
        assertThat(stranded).isZero();
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void assignmentRacingDepartmentCreationOnTheSameSiteBothSucceed() throws Exception {
    UUID site = site("2026-02-01", "2026-03-31");
    UUID region = region("2026-01-01", null);
    List<Object> outcomes =
        runInParallel(
            i ->
                i == 0
                    ? assign(Organizations.newKey(), site, region)
                    : units.create(
                        SiteUnitKind.DEPARTMENT,
                        tenant,
                        SUBJECT,
                        Organizations.newKey(),
                        CreateDepartmentRequest.of(
                            site.toString(),
                            Hierarchy.code("d"),
                            "Département " + i,
                            "2026-02-01",
                            "2026-03-31"),
                        CORRELATION,
                        DepartmentResponse.class,
                        DepartmentResponse::from));
    assertThat(outcomes).noneMatch(ApiException.class::isInstance);
    assertThat(count("tenant.department", "site_id = ?", site)).isEqualTo(PARALLEL - 1);
    assertThat(
            jdbc.queryForObject("SELECT region_id FROM tenant.site WHERE id = ?", UUID.class, site))
        .isEqualTo(region);
  }

  @Test
  void outboxFailureRollsBackRegionCreationAndAssignment() throws Exception {
    UUID site = site("2026-02-01", "2026-03-31");
    UUID region = region("2026-01-01", null);
    String createKey = Organizations.newKey();
    String assignKey = Organizations.newKey();
    String code = Hierarchy.code("rb");
    int auditBefore = total("platform.audit_event");
    int outboxBefore = total("platform.outbox_event");
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));

    assertThatThrownBy(() -> createRegion(createKey, code, "2026-01-01", null))
        .hasMessageContaining("simulated outbox failure");
    assertThatThrownBy(() -> assign(assignKey, site, region))
        .hasMessageContaining("simulated outbox failure");

    assertThat(count("tenant.region", "code = ?", code)).isZero();
    assertThat(
            jdbc.queryForObject("SELECT region_id FROM tenant.site WHERE id = ?", UUID.class, site))
        .isNull();
    assertThat(
            jdbc.queryForObject("SELECT version FROM tenant.site WHERE id = ?", Long.class, site))
        .isZero();
    assertThat(total("platform.audit_event")).isEqualTo(auditBefore);
    assertThat(total("platform.outbox_event")).isEqualTo(outboxBefore);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", createKey)).isZero();
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", assignKey)).isZero();

    // The same keys succeed once the failure is gone.
    reset(target);
    assertThat(createRegion(createKey, code, "2026-01-01", null).replayed()).isFalse();
    assertThat(assign(assignKey, site, region).replayed()).isFalse();
    assertThat(
            jdbc.queryForObject("SELECT region_id FROM tenant.site WHERE id = ?", UUID.class, site))
        .isEqualTo(region);
  }

  @Test
  void rejectedRequestsDoNotConsumeTheKey() throws Exception {
    UUID site = site("2026-02-01", "2026-03-31");
    UUID region = region("2026-01-01", null);
    String key = Organizations.newKey();
    // Missing region: rolled back, key free for a corrected retry.
    assertThat(rejection(() -> assign(key, site, UUID.randomUUID()))).isEqualTo("REGION_NOT_FOUND");
    // A region starting after the site: rolled back too.
    UUID late = region("2026-03-01", null);
    assertThat(rejection(() -> assign(key, site, late))).isEqualTo("SITE_PERIOD_OUTSIDE_REGION");
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();
    assertThat(assign(key, site, region).replayed()).isFalse();
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);

    String createKey = Organizations.newKey();
    assertThatThrownBy(
            () ->
                regions.create(
                    tenant,
                    SUBJECT,
                    createKey,
                    CreateRegionRequest.of(
                        UUID.randomUUID().toString(),
                        Hierarchy.code("m"),
                        "Manquant",
                        "2026-01-01",
                        null),
                    CORRELATION))
        .isInstanceOf(ApiException.class);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", createKey)).isZero();
  }

  private static String rejection(Runnable call) {
    try {
      call.run();
    } catch (ApiException rejected) {
      return rejected.code().name();
    }
    throw new AssertionError("call unexpectedly succeeded");
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
