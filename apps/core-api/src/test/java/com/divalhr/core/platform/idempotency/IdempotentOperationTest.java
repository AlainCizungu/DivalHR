package com.divalhr.core.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The operation-neutral vocabulary of {@link IdempotentOperation} (Issue #21 amendment 1). */
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
}
