package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.tenant.api.CreateOrganizationRequest;
import com.divalhr.core.tenant.application.CreateOrganizationService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Concurrent same-key requests against a real PostgreSQL create exactly one organization. */
@IntegrationTest
class CreateOrganizationConcurrencyIntegrationTest {

  private static final int PARALLEL = 8;

  @Autowired private CreateOrganizationService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void parallelIdenticalRequestsCreateExactlyOneOrganization() throws Exception {
    String key = Organizations.newKey();
    String name = Organizations.uniqueName();
    List<Object> outcomes =
        runInParallel(
            i ->
                service.create(
                    "sub-parallel",
                    key,
                    CreateOrganizationRequest.of(
                        name, "CD", "fr", "Africa/Kinshasa", List.of("CDF", "USD")),
                    "parallel-corr-" + i));

    List<CreateOrganizationService.Result> results =
        outcomes.stream().map(CreateOrganizationService.Result.class::cast).toList();
    assertThat(results).hasSize(PARALLEL);
    assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
    assertThat(results.stream().map(CreateOrganizationService.Result::organization).distinct())
        .hasSize(1);
    var id = results.get(0).organization().id();
    assertThat(count("tenant.organization", "name = ?", name)).isEqualTo(1);
    assertThat(count("platform.audit_event", "resource_id = ?", id)).isEqualTo(1);
    assertThat(count("platform.outbox_event", "tenant_id = ?", id)).isEqualTo(1);
    assertThat(count("platform.idempotency_record", "idempotency_key = ?", key)).isEqualTo(1);
  }

  @Test
  void parallelDifferentPayloadsWithOneKeyYieldOneCreationAndConflicts() throws Exception {
    String key = Organizations.newKey();
    String prefix = "Conflict " + java.util.UUID.randomUUID().toString().substring(0, 8) + " ";
    List<Object> outcomes =
        runInParallel(
            i ->
                service.create(
                    "sub-parallel-conflict",
                    key,
                    CreateOrganizationRequest.of(
                        prefix + i, "CD", "fr", "Africa/Kinshasa", List.of("CDF")),
                    "conflict-corr-" + i));
    long created =
        outcomes.stream().filter(CreateOrganizationService.Result.class::isInstance).count();
    long conflicts =
        outcomes.stream()
            .filter(ApiException.class::isInstance)
            .map(ApiException.class::cast)
            .filter(e -> e.code() == ErrorCode.IDEMPOTENCY_KEY_REUSED)
            .count();
    assertThat(created).isEqualTo(1);
    assertThat(conflicts).isEqualTo(PARALLEL - 1);
    assertThat(count("tenant.organization", "name LIKE ?", prefix + "%")).isEqualTo(1);
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

  private int count(String table, String where, Object arg) {
    Integer value =
        jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, arg);
    return value == null ? 0 : value;
  }
}
