package com.divalhr.core.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operation-neutral vocabulary of {@link IdempotentOperation} (Issue #21 amendment 1) and the
 * replay check (MVP-041A, R88-2).
 */
class IdempotentOperationTest {

  @Test
  void successStatusMustBe2xx() {
    assertThat(
            new IdempotentOperation.Spec(
                    "site.region.assign", "site_region", "assign", "assigned", 200)
                .successStatus())
        .isEqualTo(200);
    for (int status : new int[] {199, 300, 409}) {
      assertThatThrownBy(() -> new IdempotentOperation.Spec("a.b", "a", "b", "bed", status))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void completionOutcomesAreBoundedToCreatedUpdatedAndUnchanged() {
    UUID id = UUID.randomUUID();
    for (Outcome outcome : new Outcome[] {Outcome.CREATED, Outcome.UPDATED, Outcome.UNCHANGED}) {
      assertThat(new IdempotentOperation.Completed<>("body", id, outcome).outcome())
          .isEqualTo(outcome);
    }
    for (Outcome outcome :
        new Outcome[] {Outcome.REPLAYED, Outcome.STATE_CONFLICT, Outcome.FAILURE, Outcome.LISTED}) {
      assertThatThrownBy(() -> new IdempotentOperation.Completed<>("body", id, outcome))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static final IdempotentOperation.Spec SPEC =
      new IdempotentOperation.Spec(
          "leave-request.create", "leave_request", "create", "created", 201);

  private record Fixture(IdempotentOperation operation, IdempotencyService records) {}

  private static Fixture replaying(StoredResponse stored) {
    IdempotencyService records = mock(IdempotencyService.class);
    when(records.reserve(any(), anyString())).thenReturn(new IdempotencyDecision.Replay(stored));
    TransactionTemplate transactions =
        new TransactionTemplate(mock(PlatformTransactionManager.class));
    return new Fixture(
        new IdempotentOperation(
            records,
            new OperationMetrics(new SimpleMeterRegistry()),
            transactions,
            JsonMapper.builder().build()),
        records);
  }

  @Test
  void aReplayIsCheckedWithTheStoredResourceBeforeItIsReturned() {
    UUID resource = UUID.randomUUID();
    Fixture fixture = replaying(new StoredResponse(201, "\"stored\"", resource));
    List<UUID> checked = new ArrayList<>();
    AtomicInteger work = new AtomicInteger();
    IdempotentOperation.Result<String> result =
        fixture
            .operation()
            .execute(
                SPEC,
                "subject",
                "key",
                Map.of("a", "b"),
                String.class,
                Duration.ofSeconds(5),
                checked::add,
                () -> {
                  work.incrementAndGet();
                  throw new AssertionError("a replay never runs the work");
                });
    assertThat(result.replayed()).isTrue();
    assertThat(result.body()).isEqualTo("stored");
    assertThat(checked).containsExactly(resource);
    assertThat(work).hasValue(0);
  }

  @Test
  void aRefusedReplayReturnsNothingAndStoresNothing() {
    Fixture fixture = replaying(new StoredResponse(201, "\"stored\"", UUID.randomUUID()));
    assertThatThrownBy(
            () ->
                fixture
                    .operation()
                    .execute(
                        SPEC,
                        "subject",
                        "key",
                        Map.of("a", "b"),
                        String.class,
                        Duration.ofSeconds(5),
                        id -> {
                          throw new ApiException(ErrorCode.EMPLOYEE_LINK_REQUIRED, Map.of());
                        },
                        () -> {
                          throw new AssertionError("a replay never runs the work");
                        }))
        .isInstanceOfSatisfying(
            ApiException.class,
            refused -> assertThat(refused.code()).isEqualTo(ErrorCode.EMPLOYEE_LINK_REQUIRED));
    verify(fixture.records(), never()).complete(any(), any());
  }

  @Test
  void existingCallersStillReplayUnchecked() {
    Fixture fixture = replaying(new StoredResponse(201, "\"stored\"", UUID.randomUUID()));
    IdempotentOperation.Result<String> result =
        fixture
            .operation()
            .execute(
                SPEC,
                "subject",
                "key",
                Map.of("a", "b"),
                String.class,
                Duration.ofSeconds(5),
                () -> {
                  throw new AssertionError("a replay never runs the work");
                });
    assertThat(result.replayed()).isTrue();
    assertThat(result.body()).isEqualTo("stored");
  }
}
