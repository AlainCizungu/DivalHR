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
import com.divalhr.core.tenant.api.CreateDepartmentRequest;
import com.divalhr.core.tenant.api.CreateTeamRequest;
import com.divalhr.core.tenant.api.DepartmentResponse;
import com.divalhr.core.tenant.api.TeamResponse;
import com.divalhr.core.tenant.application.CreateSiteUnitService;
import com.divalhr.core.tenant.application.CreateTeamService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Concurrency and atomicity of team creation against real PostgreSQL: one winner, stable conflicts,
 * parent-before-child locking, and all-or-nothing transactions that never consume a key on failure.
 */
@IntegrationTest
class TeamConcurrencyAndRollbackIntegrationTest {

  private static final int PARALLEL = 8;
  private static final String SUBJECT = "sub-parallel";
  private static final String CORRELATION = "parallel-corr-0001";

  @Autowired private MockMvc mvc;
  @Autowired private CreateTeamService teams;
  @Autowired private CreateSiteUnitService units;
  @Autowired private JdbcTemplate jdbc;
  @MockitoSpyBean private OutboxWriter outbox;

  private UUID tenantId;
  private TenantId tenant;
  private UUID site;
  private UUID department;
  private UUID costCenter;

  @BeforeEach
  void hierarchy() throws Exception {
    tenantId = Hierarchy.newTenant(mvc);
    tenant = new TenantId(tenantId);
    UUID legal = Hierarchy.newLegalEntity(mvc, tenantId, Hierarchy.code("le"), "2026-01-01", null);
    site = Hierarchy.newSite(mvc, tenantId, legal, Hierarchy.code("st"), "2026-01-01", null);
    department = unit("/api/v1/departments", "2026-01-01", "2026-12-31");
    costCenter = unit("/api/v1/cost-centers", "2026-01-01", "2026-12-31");
  }

  @AfterEach
  void resetSpy() {
    reset((OutboxWriter) AopTestUtils.getUltimateTargetObject(outbox));
  }

  private UUID unit(String path, String from, String to) throws Exception {
    return Hierarchy.newSiteUnit(mvc, path, tenantId, site, Hierarchy.code("u"), from, to);
  }

  private UUID parent(TeamParentResource resource) {
    return resource == TeamParentResource.DEPARTMENT ? department : costCenter;
  }

  private IdempotentCreate.Result<TeamResponse> create(
      TeamParentResource resource, UUID parent, String key, String code, String from, String to) {
    String id = parent.toString();
    CreateTeamRequest request =
        resource == TeamParentResource.DEPARTMENT
            ? CreateTeamRequest.of(id, null, code, "Équipe " + code, from, to)
            : CreateTeamRequest.of(null, id, code, "Équipe " + code, from, to);
    return teams.create(tenant, SUBJECT, key, request, CORRELATION);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void parallelIdenticalRequestsCreateOneTeamAuditAndEvent(TeamParentResource resource)
      throws Exception {
    String key = Organizations.newKey();
    String code = Hierarchy.code("par");
    List<Object> outcomes =
        runInParallel(
            i -> create(resource, parent(resource), key, code, "2026-01-01", "2026-06-30"));
    List<IdempotentCreate.Result<?>> results =
        outcomes.stream()
            .<IdempotentCreate.Result<?>>map(o -> (IdempotentCreate.Result<?>) o)
            .toList();
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(IdempotentCreate.Result::body).distinct()).hasSize(1);
    UUID id = jdbc.queryForObject("SELECT id FROM tenant.team WHERE code = ?", UUID.class, code);
    assertThat(count("tenant.team", "code = ?", code)).isEqualTo(1);
    assertThat(count("platform.audit_event", "resource_id = ?", id)).isEqualTo(1);
    assertThat(count("platform.outbox_event", "envelope ->> 'subject' = ?", id.toString()))
        .isEqualTo(1);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);
  }

  @Test
  void parallelDuplicateCodesAcrossParentsYieldOneTeamAndStableConflicts() throws Exception {
    String code = Hierarchy.code("race");
    List<Object> outcomes =
        runInParallel(
            i ->
                create(
                    i % 2 == 0 ? TeamParentResource.DEPARTMENT : TeamParentResource.COST_CENTER,
                    i % 2 == 0 ? department : costCenter,
                    Organizations.newKey(),
                    i % 4 < 2 ? code : code.toLowerCase(Locale.ROOT),
                    "2026-01-01",
                    "2026-06-30"));
    assertThat(outcomes.stream().filter(IdempotentCreate.Result.class::isInstance).count())
        .isEqualTo(1);
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(o -> ((ApiException) o).code().name()))
        .hasSize(PARALLEL - 1)
        .containsOnly("DUPLICATE_TEAM_CODE");
    assertThat(count("tenant.team", "code = ?", code)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void creationRacingAParentNarrowingNeverStrandsATeam(TeamParentResource resource)
      throws Exception {
    for (int round = 0; round < 5; round++) {
      UUID parent = unit(resource.parentPath, "2026-01-01", "2026-12-31");
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        String code = Hierarchy.code("rc");
        Future<Object> creation =
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return create(
                        resource, parent, Organizations.newKey(), code, "2026-02-01", "2026-11-30");
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
                        "UPDATE " + resource.parentTable + " SET effective_to = ? WHERE id = ?",
                        Date.valueOf("2026-06-30"),
                        parent);
                  } catch (DataAccessException rejected) {
                    return rejected;
                  }
                });
        start.countDown();
        Object created = creation.get(30, TimeUnit.SECONDS);
        Object narrowed = narrowing.get(30, TimeUnit.SECONDS);
        boolean createdOk = created instanceof IdempotentCreate.Result<?>;
        boolean narrowedOk = narrowed instanceof Integer;
        assertThat(createdOk && narrowedOk).as("both succeeded in round %d", round).isFalse();
        assertThat(createdOk || narrowedOk).as("neither succeeded in round %d", round).isTrue();
        if (!createdOk) {
          assertThat(((ApiException) created).code().name()).isEqualTo(resource.periodCode);
        }
        Integer stranded =
            jdbc.queryForObject(
                "SELECT count(*) FROM tenant.team t JOIN "
                    + resource.parentTable
                    + " p ON p.tenant_id = t.tenant_id AND p.id = t."
                    + resource.column
                    + " WHERE t.code = ? AND (t.effective_from < p.effective_from"
                    + " OR (p.effective_to IS NOT NULL"
                    + " AND (t.effective_to IS NULL OR t.effective_to > p.effective_to)))",
                Integer.class,
                code);
        assertThat(stranded).isZero();
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void teamCreationRacingDepartmentCreationOnTheSameSiteBothSucceed() throws Exception {
    List<Object> outcomes =
        runInParallel(
            i ->
                i % 2 == 0
                    ? create(
                        TeamParentResource.DEPARTMENT,
                        department,
                        Organizations.newKey(),
                        Hierarchy.code("t"),
                        "2026-01-01",
                        "2026-06-30")
                    : units.create(
                        SiteUnitKind.DEPARTMENT,
                        tenant,
                        SUBJECT,
                        Organizations.newKey(),
                        CreateDepartmentRequest.of(
                            site.toString(),
                            Hierarchy.code("d"),
                            "Département " + i,
                            "2026-01-01",
                            null),
                        CORRELATION,
                        DepartmentResponse.class,
                        DepartmentResponse::from));
    assertThat(outcomes).noneMatch(ApiException.class::isInstance);
    assertThat(count("tenant.team", "department_id = ?", department)).isEqualTo(PARALLEL / 2);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void outboxFailureRollsBackAndTheKeyStaysReusable(TeamParentResource resource) {
    String key = Organizations.newKey();
    String code = Hierarchy.code("rb");
    int auditBefore = total("platform.audit_event");
    int outboxBefore = total("platform.outbox_event");
    OutboxWriter target = AopTestUtils.getUltimateTargetObject(outbox);
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(target)
        .append(any(EventEnvelope.class));

    assertThatThrownBy(
            () -> create(resource, parent(resource), key, code, "2026-01-01", "2026-06-30"))
        .hasMessageContaining("simulated outbox failure");
    assertThat(count("tenant.team", "code = ?", code)).isZero();
    assertThat(total("platform.audit_event")).isEqualTo(auditBefore);
    assertThat(total("platform.outbox_event")).isEqualTo(outboxBefore);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();

    reset(target);
    assertThat(create(resource, parent(resource), key, code, "2026-01-01", "2026-06-30").replayed())
        .isFalse();
    assertThat(count("tenant.team", "code = ?", code)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void rejectedRequestsDoNotConsumeTheKey(TeamParentResource resource) {
    String key = Organizations.newKey();
    String code = Hierarchy.code("k");
    // Missing parent, then a period outside the parent: both rolled back.
    assertThat(rejection(() -> create(resource, UUID.randomUUID(), key, code, "2026-01-01", null)))
        .isEqualTo(resource.notFoundCode);
    assertThat(rejection(() -> create(resource, parent(resource), key, code, "2026-01-01", null)))
        .isEqualTo(resource.periodCode);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isZero();
    assertThat(create(resource, parent(resource), key, code, "2026-01-01", "2026-06-30").replayed())
        .isFalse();
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);
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
