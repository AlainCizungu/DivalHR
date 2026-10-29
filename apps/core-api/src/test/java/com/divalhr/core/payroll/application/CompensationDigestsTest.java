package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationDates.Span;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.payroll.domain.CompensationTimeline.Plan;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The confirmation digests change with everything a confirmation binds and with nothing else. */
class CompensationDigestsTest {

  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID EMPLOYEE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TARGET = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID EMPLOYMENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final Instant EXPIRES = Instant.parse("2026-10-29T11:00:00Z");

  private static Plan plan(UUID origin, long minor) {
    Interval created =
        new Interval(
            origin,
            CompensationMoney.ofMinor(CompensationCurrency.CDF, minor),
            LocalDate.parse("2026-11-01"),
            null);
    return new Plan(List.of(), List.of(created), List.of(created));
  }

  private static String cancellation(int version, String employments, Plan plan, Instant at) {
    return CompensationDigests.cancellation(
        TENANT, EMPLOYEE, TARGET, version, employments, plan, at);
  }

  @Test
  void theEmploymentDigestDoesNotDependOnTheOrderButOnEveryField() {
    Span first = new Span(EMPLOYMENT, 1, LocalDate.parse("2024-01-01"), null);
    Span old = new Span(TARGET, 2, LocalDate.parse("2022-01-01"), LocalDate.parse("2023-12-31"));
    String digest = CompensationDigests.employments(List.of(first, old));
    assertThat(digest).matches("[0-9a-f]{64}");
    assertThat(CompensationDigests.employments(List.of(old, first))).isEqualTo(digest);
    Span moved = new Span(EMPLOYMENT, 2, LocalDate.parse("2024-01-01"), null);
    assertThat(CompensationDigests.employments(List.of(moved, old))).isNotEqualTo(digest);
    Span ended =
        new Span(EMPLOYMENT, 1, LocalDate.parse("2024-01-01"), LocalDate.parse("2026-12-31"));
    assertThat(CompensationDigests.employments(List.of(ended, old))).isNotEqualTo(digest);
  }

  @Test
  void aCancellationDigestBindsVersionEmploymentsPlanAndExpiry() {
    Plan plan = plan(TARGET, 100);
    String digest = cancellation(2, "a", plan, EXPIRES);
    assertThat(cancellation(2, "a", plan, EXPIRES)).isEqualTo(digest);
    assertThat(cancellation(3, "a", plan, EXPIRES)).isNotEqualTo(digest);
    assertThat(cancellation(2, "b", plan, EXPIRES)).isNotEqualTo(digest);
    assertThat(cancellation(2, "a", plan(TARGET, 101), EXPIRES)).isNotEqualTo(digest);
    assertThat(cancellation(2, "a", plan, EXPIRES.plusSeconds(900))).isNotEqualTo(digest);
  }

  @Test
  void aCancellationDigestNeverContainsTheAmount() {
    assertThat(cancellation(2, "a", plan(TARGET, 987654321), EXPIRES)).doesNotContain("987654321");
  }
}
