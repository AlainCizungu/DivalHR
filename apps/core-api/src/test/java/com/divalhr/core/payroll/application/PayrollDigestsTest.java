package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employee;
import com.divalhr.core.payroll.domain.PayrollCalculator.Employment;
import com.divalhr.core.payroll.domain.PayrollCalculator.Fact;
import com.divalhr.core.payroll.domain.PayrollCalculator.Placement;
import com.divalhr.core.payroll.domain.PayrollPayDay;
import com.divalhr.core.payroll.domain.PayrollPeriod;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.payroll.domain.PayrollProration;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The payroll digests change with everything they bind and with nothing else. */
class PayrollDigestsTest {

  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID EMPLOYEE = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER = UUID.fromString("33333333-3333-4333-8333-333333333334");
  private static final UUID EMPLOYMENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID PLACEMENT = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID FACT = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID ORIGIN = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final PayrollPeriod MAY = PayrollPeriod.parse("2026-05").orElseThrow();
  private static final PayrollPolicy POLICY =
      new PayrollPolicy(
          UUID.fromString("88888888-8888-4888-8888-888888888888"),
          2,
          LocalDate.parse("2026-01-01"),
          PayrollPayDay.ofDay(25),
          PayrollProration.CALENDAR_DAYS);
  private static final Instant EXPIRES = Instant.parse("2026-06-02T11:00:00Z");

  private static Employee employee(UUID id, long version, long minor, String number) {
    return new Employee(
        id,
        number,
        List.of(new Employment(EMPLOYMENT, version, LocalDate.parse("2020-01-01"), null)),
        List.of(new Placement(PLACEMENT, ENTITY, LocalDate.parse("2020-01-01"), null)),
        List.of(
            new Fact(
                FACT,
                ORIGIN,
                4,
                CompensationCurrency.USD,
                minor,
                LocalDate.parse("2020-01-01"),
                null)));
  }

  private static String input(List<Employee> employees) {
    return PayrollDigests.input(TENANT, ENTITY, MAY, CompensationCurrency.USD, POLICY, employees);
  }

  @Test
  void theInputDigestBindsEverySourceRecordAndTheScope() {
    List<Employee> employees = List.of(employee(EMPLOYEE, 1, 125_000, "E1"));
    String digest = input(employees);
    assertThat(digest).matches("[0-9a-f]{64}");
    assertThat(input(List.of(employee(EMPLOYEE, 1, 125_000, "E1")))).isEqualTo(digest);
    // An employment version, an amount, a number and the population change it.
    assertThat(input(List.of(employee(EMPLOYEE, 2, 125_000, "E1")))).isNotEqualTo(digest);
    assertThat(input(List.of(employee(EMPLOYEE, 1, 125_001, "E1")))).isNotEqualTo(digest);
    assertThat(input(List.of(employee(EMPLOYEE, 1, 125_000, "E2")))).isNotEqualTo(digest);
    assertThat(input(List.of(employee(EMPLOYEE, 1, 125_000, "E1"), employee(OTHER, 1, 1, "E3"))))
        .isNotEqualTo(digest);
    // The scope: tenant, entity, month, currency and policy.
    assertThat(
            PayrollDigests.input(
                TENANT, ENTITY, MAY, CompensationCurrency.CDF, POLICY, employees))
        .isNotEqualTo(digest);
    assertThat(
            PayrollDigests.input(
                TENANT,
                ENTITY,
                PayrollPeriod.parse("2026-04").orElseThrow(),
                CompensationCurrency.USD,
                POLICY,
                employees))
        .isNotEqualTo(digest);
    assertThat(
            PayrollDigests.input(
                TENANT,
                UUID.fromString("22222222-2222-4222-8222-222222222223"),
                MAY,
                CompensationCurrency.USD,
                POLICY,
                employees))
        .isNotEqualTo(digest);
    PayrollPolicy later =
        new PayrollPolicy(
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            3,
            LocalDate.parse("2026-04-01"),
            PayrollPayDay.lastDay(),
            PayrollProration.NONE);
    assertThat(
            PayrollDigests.input(
                TENANT, ENTITY, MAY, CompensationCurrency.USD, later, employees))
        .isNotEqualTo(digest);
  }

  @Test
  void theInputDigestDoesNotDependOnTheOrderOfTheEmployees() {
    Employee a = employee(EMPLOYEE, 1, 100, "E1");
    Employee b = employee(OTHER, 1, 200, "E2");
    assertThat(input(List.of(a, b))).isEqualTo(input(List.of(b, a)));
  }

  @Test
  void theInputDigestNeverContainsAnAmountOrANumber() {
    assertThat(input(List.of(employee(EMPLOYEE, 1, 987_654, "E-SENTINEL")))).doesNotContain("987654");
  }

  @Test
  void thePreviewDigestBindsTheCommandTheVersionTheInputsAndTheExpiry() {
    String input = "a".repeat(64);
    String digest =
        PayrollDigests.run(TENANT, ENTITY, MAY, CompensationCurrency.USD, 1, input, EXPIRES);
    assertThat(digest).matches("[0-9a-f]{64}");
    assertThat(PayrollDigests.run(TENANT, ENTITY, MAY, CompensationCurrency.USD, 1, input, EXPIRES))
        .isEqualTo(digest);
    assertThat(PayrollDigests.run(TENANT, ENTITY, MAY, CompensationCurrency.USD, 2, input, EXPIRES))
        .isNotEqualTo(digest);
    assertThat(PayrollDigests.run(TENANT, ENTITY, MAY, CompensationCurrency.CDF, 1, input, EXPIRES))
        .isNotEqualTo(digest);
    assertThat(
            PayrollDigests.run(
                TENANT, ENTITY, MAY, CompensationCurrency.USD, 1, "b".repeat(64), EXPIRES))
        .isNotEqualTo(digest);
    assertThat(
            PayrollDigests.run(
                TENANT, ENTITY, MAY, CompensationCurrency.USD, 1, input, EXPIRES.plusSeconds(900)))
        .isNotEqualTo(digest);
  }

  @Test
  void thePolicyDigestBindsThePolicyTheVersionAndTheExpiry() {
    PayrollCommands.PolicyDraft draft =
        new PayrollCommands.PolicyDraft(
            LocalDate.parse("2026-07-01"), PayrollPayDay.ofDay(25), PayrollProration.NONE);
    String digest = PayrollDigests.policy(TENANT, ENTITY, draft, 2, EXPIRES);
    assertThat(PayrollDigests.policy(TENANT, ENTITY, draft, 2, EXPIRES)).isEqualTo(digest);
    assertThat(PayrollDigests.policy(TENANT, ENTITY, draft, 3, EXPIRES)).isNotEqualTo(digest);
    PayrollCommands.PolicyDraft other =
        new PayrollCommands.PolicyDraft(
            LocalDate.parse("2026-07-01"), PayrollPayDay.lastDay(), PayrollProration.NONE);
    assertThat(PayrollDigests.policy(TENANT, ENTITY, other, 2, EXPIRES)).isNotEqualTo(digest);
    assertThat(PayrollDigests.policy(TENANT, ENTITY, draft, 2, EXPIRES.plusSeconds(900)))
        .isNotEqualTo(digest);
  }
}
