package com.divalhr.core.people.leave.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.people.leave.api.CreateMyLeaveRequest;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.tenancy.TenantId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * MVP-041A (D41A-3): validation of a leave request. Problems are {@code {field, constraint}} pairs
 * only; no date, amount or other submitted value is echoed. Parsing depends on the request alone
 * (R88-1); the business-date rule is a separate step applied to new executions only.
 */
class LeaveRequestCommandTest {

  private static final String KEY = "c2b0bd35-2f60-4c41-9cfb-6a3e40a3d1aa";
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
  private static final String POLICY = "0b7a8a0e-1f0b-4f7c-9a8a-3d5e8c6f2a11";

  private static CreateMyLeaveRequest valid(Consumer<CreateMyLeaveRequest> change) {
    CreateMyLeaveRequest request = new CreateMyLeaveRequest();
    request.setPolicyId(POLICY);
    request.setStartDate("2026-10-12");
    request.setEndDate("2026-10-14");
    request.setAmount(2.5);
    change.accept(request);
    return request;
  }

  private static List<String> problems(CreateMyLeaveRequest request) {
    try {
      LeaveRequestCommand.from(KEY, request).requireStartFrom(TODAY);
    } catch (ApiException rejected) {
      assertThat(rejected.code().name()).isEqualTo("VALIDATION_FAILED");
      List<String> fields = new ArrayList<>();
      for (Object entry : (List<?>) rejected.params().get("fields")) {
        Map<?, ?> field = (Map<?, ?>) entry;
        fields.add(field.get("field") + ":" + field.get("constraint"));
      }
      return fields;
    }
    return List.of();
  }

  @Test
  void aRequestStartingOnTheBusinessDateIsNormalized() {
    LeaveRequestCommand command =
        LeaveRequestCommand.from(KEY, valid(r -> r.setPolicyId(POLICY.toUpperCase())));
    assertThat(command.policyId()).isEqualTo(UUID.fromString(POLICY));
    assertThat(command.amount()).isEqualTo(new BigDecimal("2.50"));
    assertThat(command.toString()).doesNotContain("2026").doesNotContain("2.5");
    assertThat(command.canonical(new TenantId(UUID.randomUUID())))
        .containsEntry("amount", "2.50")
        .containsEntry("startDate", "2026-10-12");
  }

  @Test
  void datesFollowTheBusinessDateTheOrderAndThe366DayLimit() {
    assertThat(problems(valid(r -> r.setStartDate("2026-10-11"))))
        .containsExactly("startDate:RANGE");
    assertThat(problems(valid(r -> r.setEndDate("2026-10-11")))).containsExactly("endDate:RANGE");
    // 366 inclusive days are allowed, 367 are not.
    assertThat(problems(valid(r -> r.setEndDate(TODAY.plusDays(365).toString())))).isEmpty();
    assertThat(problems(valid(r -> r.setEndDate(TODAY.plusDays(366).toString()))))
        .containsExactly("endDate:RANGE");
    assertThat(problems(valid(r -> r.setStartDate("3000-01-01"))))
        .containsExactly("startDate:RANGE");
    for (Object bad : List.of("2026-02-30", "12/10/2026", 20261012, "2026-10-12T00:00")) {
      assertThat(problems(valid(r -> r.setStartDate(bad))))
          .as("%s", bad)
          .containsExactly("startDate:FORMAT");
    }
    assertThat(problems(valid(r -> r.setEndDate(null)))).containsExactly("endDate:REQUIRED");
  }

  @Test
  void parsingDoesNotDependOnTheBusinessDate() {
    // A request whose first day has passed still parses (an exact retry must reach the replay);
    // only the separate new-execution rule refuses it.
    CreateMyLeaveRequest past = valid(r -> r.setStartDate("2026-10-11"));
    LeaveRequestCommand command = LeaveRequestCommand.from(KEY, past);
    assertThat(command.startDate()).isEqualTo(LocalDate.of(2026, 10, 11));
    command.requireStartFrom(TODAY.minusDays(1));
    assertThat(problems(past)).containsExactly("startDate:RANGE");
  }

  @Test
  void theAmountIsAPositiveNumberWithAtMostTwoDecimals() {
    for (Object amount : List.of(1, 7L, 0.5, 0.01, new BigDecimal("10000.00"), 1e2)) {
      assertThat(problems(valid(r -> r.setAmount(amount)))).as("%s", amount).isEmpty();
    }
    for (Object amount : List.of(0, -1, 10000.01)) {
      assertThat(problems(valid(r -> r.setAmount(amount))))
          .as("%s", amount)
          .containsExactly("amount:RANGE");
    }
    for (Object amount : List.of(1.005, "2", true)) {
      assertThat(problems(valid(r -> r.setAmount(amount))))
          .as("%s", amount)
          .containsExactly("amount:FORMAT");
    }
    assertThat(problems(valid(r -> r.setAmount(null)))).containsExactly("amount:REQUIRED");
  }

  @Test
  void thePolicyIdAndKeyAreChecked() {
    assertThat(problems(valid(r -> r.setPolicyId("not-a-uuid"))))
        .containsExactly("policyId:FORMAT");
    assertThat(problems(valid(r -> r.setPolicyId(null)))).containsExactly("policyId:REQUIRED");
    try {
      LeaveRequestCommand.from("bad key!", valid(r -> {}));
    } catch (ApiException rejected) {
      assertThat(rejected.params().toString()).contains("Idempotency-Key").contains("FORMAT");
      return;
    }
    throw new AssertionError("expected a rejection");
  }
}
