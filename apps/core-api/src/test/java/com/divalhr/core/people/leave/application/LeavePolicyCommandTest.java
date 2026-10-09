package com.divalhr.core.people.leave.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.people.leave.api.CreateLeavePolicyRequest;
import com.divalhr.core.people.leave.domain.ApprovalRoute;
import com.divalhr.core.people.leave.domain.BalanceMode;
import com.divalhr.core.people.leave.domain.LeaveUnit;
import com.divalhr.core.people.leave.domain.PayrollEffect;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.tenancy.TenantId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * MVP-040A (D40A-2, D40A-3): validation and normalization of a leave policy creation. Problems are
 * {@code {field, constraint}} pairs only; no submitted value appears in the exception.
 */
class LeavePolicyCommandTest {

  private static final String KEY = "c2b0bd35-2f60-4c41-9cfb-6a3e40a3d1aa";

  private static CreateLeavePolicyRequest valid() {
    CreateLeavePolicyRequest request = new CreateLeavePolicyRequest();
    request.setCode("annual");
    request.setNames(Map.of("en", "Annual leave", "fr", "Congé annuel"));
    request.setUnit("DAYS");
    request.setBalanceMode("TRACKED");
    request.setAnnualEntitlement(18.5);
    request.setMinimumServiceDays(90);
    request.setApprovalRoute("MANAGER");
    request.setPayrollEffect("PAID");
    request.setEffectiveFrom("2026-01-01");
    return request;
  }

  private static CreateLeavePolicyRequest valid(Consumer<CreateLeavePolicyRequest> change) {
    CreateLeavePolicyRequest request = valid();
    change.accept(request);
    return request;
  }

  private static List<String> problems(String key, CreateLeavePolicyRequest request) {
    try {
      LeavePolicyCommand.from(key, request);
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

  private static List<String> problems(CreateLeavePolicyRequest request) {
    return problems(KEY, request);
  }

  @Test
  void aValidRequestIsNormalized() {
    LeavePolicyCommand command =
        LeavePolicyCommand.from(
            KEY,
            valid(
                r -> {
                  r.setCode("  sick_leave-2 ");
                  r.setNames(Map.of("en", "  Sick leave\t", "fr", "Conge\u0301 maladie"));
                  r.setEffectiveTo("2026-12-31");
                }));
    assertThat(command.code()).isEqualTo("SICK_LEAVE-2");
    assertThat(command.nameEn()).isEqualTo("Sick leave");
    assertThat(command.nameFr()).isEqualTo("Congé maladie").hasSize(13);
    assertThat(command.unit()).isEqualTo(LeaveUnit.DAYS);
    assertThat(command.balanceMode()).isEqualTo(BalanceMode.TRACKED);
    assertThat(command.annualEntitlement()).isEqualTo(new BigDecimal("18.50"));
    assertThat(command.minimumServiceDays()).isEqualTo(90);
    assertThat(command.approvalRoute()).isEqualTo(ApprovalRoute.MANAGER);
    assertThat(command.payrollEffect()).isEqualTo(PayrollEffect.PAID);
    assertThat(command.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(command.effectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
    assertThat(command.toString()).doesNotContain("Sick").doesNotContain("Congé");
  }

  @Test
  void entitlementsAcceptEveryJsonNumberShapeWithAtMostTwoDecimals() {
    for (Object value :
        List.of(1, 25L, BigInteger.valueOf(10000), 0.01, 12.5, new BigDecimal("9999.99"), 1e2)) {
      LeavePolicyCommand command =
          LeavePolicyCommand.from(KEY, valid(r -> r.setAnnualEntitlement(value)));
      assertThat(command.annualEntitlement().scale()).as("%s", value).isEqualTo(2);
    }
    assertThat(problems(valid(r -> r.setAnnualEntitlement(0.001))))
        .containsExactly("annualEntitlement:FORMAT");
    assertThat(problems(valid(r -> r.setAnnualEntitlement(new BigDecimal("1.005")))))
        .containsExactly("annualEntitlement:FORMAT");
    assertThat(problems(valid(r -> r.setAnnualEntitlement("12.5"))))
        .containsExactly("annualEntitlement:FORMAT");
    assertThat(problems(valid(r -> r.setAnnualEntitlement(true))))
        .containsExactly("annualEntitlement:FORMAT");
    for (Object outOfRange : List.of(0, -0.5, 10000.01, 1e9)) {
      assertThat(problems(valid(r -> r.setAnnualEntitlement(outOfRange))))
          .as("%s", outOfRange)
          .containsExactly("annualEntitlement:RANGE");
    }
    assertThat(problems(valid(r -> r.setAnnualEntitlement(null))))
        .containsExactly("annualEntitlement:REQUIRED");
  }

  @Test
  void untrackedPoliciesHaveNoEntitlement() {
    LeavePolicyCommand command =
        LeavePolicyCommand.from(
            KEY,
            valid(
                r -> {
                  r.setBalanceMode("UNTRACKED");
                  r.setAnnualEntitlement(null);
                }));
    assertThat(command.annualEntitlement()).isNull();
    assertThat(command.canonical(new TenantId(UUID.randomUUID())))
        .containsEntry("annualEntitlement", null);
    assertThat(problems(valid(r -> r.setBalanceMode("UNTRACKED"))))
        .containsExactly("annualEntitlement:RANGE");
    // An unknown mode reports the mode only.
    assertThat(problems(valid(r -> r.setBalanceMode("PARTIAL"))))
        .containsExactly("balanceMode:FORMAT");
  }

  @Test
  void codesAreTrimmedUpperCasedAndBounded() {
    assertThat(problems(valid(r -> r.setCode(null)))).containsExactly("code:REQUIRED");
    assertThat(problems(valid(r -> r.setCode("   ")))).containsExactly("code:REQUIRED");
    assertThat(problems(valid(r -> r.setCode(12)))).containsExactly("code:FORMAT");
    assertThat(problems(valid(r -> r.setCode("A")))).containsExactly("code:LENGTH");
    assertThat(problems(valid(r -> r.setCode("A".repeat(21))))).containsExactly("code:LENGTH");
    assertThat(LeavePolicyCommand.from(KEY, valid(r -> r.setCode("A".repeat(20)))).code())
        .hasSize(20);
    for (String code : List.of("-AB", "_AB", "A B", "ÉTÉ", "A.B", "A/B")) {
      assertThat(problems(valid(r -> r.setCode(code)))).as(code).containsExactly("code:FORMAT");
    }
  }

  @Test
  void bothNamesAreRequiredTrimmedSafeAndBounded() {
    assertThat(problems(valid(r -> r.setNames(null)))).containsExactly("names:REQUIRED");
    assertThat(problems(valid(r -> r.setNames("Congé")))).containsExactly("names:FORMAT");
    assertThat(problems(valid(r -> r.setNames(List.of("a", "b"))))).containsExactly("names:FORMAT");
    assertThat(problems(valid(r -> r.setNames(Map.of("en", "Annual")))))
        .containsExactly("names.fr:REQUIRED");
    Map<String, Object> extra = new LinkedHashMap<>(Map.of("en", "Annual", "fr", "Annuel"));
    extra.put("es", "Anual");
    assertThat(problems(valid(r -> r.setNames(extra)))).containsExactly("names:UNKNOWN_PROPERTY");
    assertThat(problems(valid(r -> r.setNames(Map.of("en", "   ", "fr", 7)))))
        .containsExactly("names.en:REQUIRED", "names.fr:FORMAT");
    assertThat(problems(valid(r -> r.setNames(Map.of("en", "A", "fr", "é".repeat(101))))))
        .containsExactly("names.en:LENGTH", "names.fr:LENGTH");
    // 100 code points, including characters outside the Basic Multilingual Plane.
    String hundred = "𝔸".repeat(50) + "é".repeat(50);
    assertThat(
            LeavePolicyCommand.from(KEY, valid(r -> r.setNames(Map.of("en", hundred, "fr", "Ab"))))
                .nameEn())
        .isEqualTo(hundred);
    for (String unsafe :
        List.of(
            "Tab\there",
            "Line\nbreak",
            "Null\u0000",
            "Bidi\u202Eoverride",
            "Zero\u200Bwidth",
            "Sep\u2028line",
            "Private\uE000use",
            "Lone\uD800surrogate",
            "Lone\uDC00low")) {
      assertThat(problems(valid(r -> r.setNames(Map.of("en", unsafe, "fr", "Congé")))))
          .as(unsafe)
          .containsExactly("names.en:FORMAT");
    }
    // NFC: a decomposed accent is composed, so it counts once.
    assertThat(
            LeavePolicyCommand.from(
                    KEY, valid(r -> r.setNames(Map.of("en", "E\u0301té", "fr", "Été"))))
                .nameEn())
        .isEqualTo("Été");
  }

  @Test
  void enumsServiceDaysAndDatesAreStrict() {
    assertThat(
            problems(
                valid(
                    r -> {
                      r.setUnit("days");
                      r.setApprovalRoute(null);
                      r.setPayrollEffect(List.of("PAID"));
                    })))
        .containsExactly("unit:FORMAT", "approvalRoute:REQUIRED", "payrollEffect:FORMAT");
    assertThat(problems(valid(r -> r.setMinimumServiceDays(null))))
        .containsExactly("minimumServiceDays:REQUIRED");
    assertThat(problems(valid(r -> r.setMinimumServiceDays("10"))))
        .containsExactly("minimumServiceDays:FORMAT");
    assertThat(problems(valid(r -> r.setMinimumServiceDays(10.0))))
        .containsExactly("minimumServiceDays:FORMAT");
    assertThat(problems(valid(r -> r.setMinimumServiceDays(-1))))
        .containsExactly("minimumServiceDays:RANGE");
    assertThat(problems(valid(r -> r.setMinimumServiceDays(3651L))))
        .containsExactly("minimumServiceDays:RANGE");
    assertThat(
            LeavePolicyCommand.from(KEY, valid(r -> r.setMinimumServiceDays(3650)))
                .minimumServiceDays())
        .isEqualTo(3650);
    assertThat(
            LeavePolicyCommand.from(KEY, valid(r -> r.setMinimumServiceDays(0)))
                .minimumServiceDays())
        .isZero();

    assertThat(problems(valid(r -> r.setEffectiveFrom(null))))
        .containsExactly("effectiveFrom:REQUIRED");
    for (Object bad :
        List.of("2026-1-1", "2026-02-29", "01/01/2026", "2026-01-01T00:00", 20260101)) {
      assertThat(problems(valid(r -> r.setEffectiveFrom(bad))))
          .as("%s", bad)
          .containsExactly("effectiveFrom:FORMAT");
    }
    assertThat(problems(valid(r -> r.setEffectiveFrom("1899-12-31"))))
        .containsExactly("effectiveFrom:RANGE");
    assertThat(problems(valid(r -> r.setEffectiveTo("3000-01-01"))))
        .containsExactly("effectiveTo:RANGE");
    assertThat(problems(valid(r -> r.setEffectiveTo("2025-12-31"))))
        .containsExactly("effectiveTo:RANGE");
    assertThat(
            LeavePolicyCommand.from(KEY, valid(r -> r.setEffectiveTo("2026-01-01"))).effectiveTo())
        .isEqualTo(LocalDate.of(2026, 1, 1));
  }

  @Test
  void theKeyAndUnknownPropertiesAreChecked() throws Exception {
    assertThat(problems(null, valid())).containsExactly("Idempotency-Key:REQUIRED");
    assertThat(problems("not a key!", valid())).containsExactly("Idempotency-Key:FORMAT");
    CreateLeavePolicyRequest withUnknown =
        new ObjectMapper()
            .readValue(
                "{\"code\":\"AB\",\"names\":{\"en\":\"Annual\",\"fr\":\"Annuel\"},\"unit\":\"DAYS\","
                    + "\"balanceMode\":\"UNTRACKED\",\"minimumServiceDays\":0,"
                    + "\"approvalRoute\":\"MANAGER\",\"payrollEffect\":\"UNPAID\",\"effectiveFrom\":\"2026-01-01\",\"secretComment\":\"Ne"
                    + " pas montrer\"}",
                CreateLeavePolicyRequest.class);
    assertThat(problems(withUnknown)).containsExactly("body:UNKNOWN_PROPERTY");
    assertThatThrownBy(() -> LeavePolicyCommand.from(KEY, null))
        .isInstanceOf(ApiException.class)
        .hasMessageNotContaining("Ne pas");
  }

  @Test
  void rejectionsNeverCarryTheSubmittedValues() {
    String marker = "Valeur Secrète";
    try {
      LeavePolicyCommand.from(
          KEY,
          valid(
              r -> {
                r.setCode(marker);
                r.setNames(Map.of("en", marker + "\n", "fr", marker));
                r.setUnit(marker);
              }));
    } catch (ApiException rejected) {
      assertThat(rejected.toString()).doesNotContain("Secrète");
      assertThat(String.valueOf(rejected.getMessage())).doesNotContain("Secrète");
      assertThat(rejected.params().toString()).doesNotContain("Secrète");
      return;
    }
    throw new AssertionError("expected a rejection");
  }

  @Test
  void theCanonicalFormCoversTheTenantAndEveryNormalizedValue() {
    TenantId tenant = new TenantId(UUID.randomUUID());
    Map<String, Object> canonical = LeavePolicyCommand.from(KEY, valid()).canonical(tenant);
    assertThat(canonical.keySet())
        .containsExactly(
            "annualEntitlement",
            "approvalRoute",
            "balanceMode",
            "code",
            "effectiveFrom",
            "effectiveTo",
            "minimumServiceDays",
            "nameEn",
            "nameFr",
            "payrollEffect",
            "tenantId",
            "unit");
    assertThat(canonical)
        .containsEntry("annualEntitlement", "18.50")
        .containsEntry("code", "ANNUAL");
    // Equivalent submissions fingerprint identically.
    assertThat(
            LeavePolicyCommand.from(
                    KEY,
                    valid(
                        r -> {
                          r.setCode(" ANNUAL ");
                          r.setAnnualEntitlement(new BigDecimal("18.500"));
                        }))
                .canonical(tenant))
        .isEqualTo(canonical);
  }
}
