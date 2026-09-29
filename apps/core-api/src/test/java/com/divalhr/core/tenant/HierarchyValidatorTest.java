package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.application.HierarchyValidator;
import com.divalhr.core.tenant.application.LegalEntityCommand;
import com.divalhr.core.tenant.application.SiteCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HierarchyValidatorTest {

  private static final String KEY = "test-key-0000000000000001";
  private final HierarchyValidator validator = new HierarchyValidator();

  @Test
  void normalizesCodesAndNames() {
    LegalEntityCommand command =
        validator.legalEntity(
            KEY,
            CreateLegalEntityRequest.of(
                "  kin_01 ", "  Société Minière du Katanga  ", "CD", "2026-01-01", null));
    assertThat(command.code()).isEqualTo("KIN_01");
    assertThat(command.name()).isEqualTo("Société Minière du Katanga");
    assertThat(command.period().to()).isNull();
  }

  @Test
  void reportsEveryFormatProblemBeforeDateOrderOrSupport() {
    assertThat(
            fields(
                () ->
                    validator.legalEntity(
                        "short",
                        CreateLegalEntityRequest.of("!", "x", "zz", "2026-13-01", "2026-01-01"))))
        .containsExactlyInAnyOrder(
            Map.of("field", "Idempotency-Key", "constraint", "FORMAT"),
            Map.of("field", "code", "constraint", "LENGTH"),
            Map.of("field", "name", "constraint", "LENGTH"),
            Map.of("field", "countryCode", "constraint", "FORMAT"),
            Map.of("field", "effectiveFrom", "constraint", "FORMAT"));
    assertThat(
            fields(
                () ->
                    validator.legalEntity(
                        KEY,
                        CreateLegalEntityRequest.of(
                            "AB", "Nom", "CD", "1899-12-31", "3000-01-01"))))
        .containsExactlyInAnyOrder(
            Map.of("field", "effectiveFrom", "constraint", "RANGE"),
            Map.of("field", "effectiveTo", "constraint", "RANGE"));
    assertThat(
            fields(
                () ->
                    validator.legalEntity(
                        KEY, CreateLegalEntityRequest.of("A B", "Nom", "CD", "2026-01-01", null))))
        .containsExactly(Map.of("field", "code", "constraint", "FORMAT"));
  }

  @Test
  void dateOrderPrecedesCountrySupport() {
    assertThatThrownBy(
            () ->
                validator.legalEntity(
                    KEY,
                    CreateLegalEntityRequest.of("AB", "Nom", "FR", "2026-02-01", "2026-01-31")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.EFFECTIVE_DATE_INVALID);
              assertThat(e.params()).isEqualTo(Map.of("field", "effectiveTo"));
            });
    assertThatThrownBy(
            () ->
                validator.legalEntity(
                    KEY, CreateLegalEntityRequest.of("AB", "Nom", "FR", "2026-01-01", null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.COUNTRY_NOT_SUPPORTED));
  }

  @Test
  void validatesSitesWithoutConsultingTheParent() {
    SiteCommand command =
        validator.site(
            KEY,
            CreateSiteRequest.of(
                "33333333-3333-4333-8333-333333333333",
                "site-1",
                "Site",
                "Europe/Paris",
                "2026-01-01",
                "2026-01-01"));
    assertThat(command.code()).isEqualTo("SITE-1");
    assertThat(command.timezone()).isEqualTo("Europe/Paris");
    assertThat(
            fields(
                () ->
                    validator.site(
                        KEY, CreateSiteRequest.of(null, null, null, "Not/AZone", null, null))))
        .containsExactlyInAnyOrder(
            Map.of("field", "legalEntityId", "constraint", "REQUIRED"),
            Map.of("field", "code", "constraint", "REQUIRED"),
            Map.of("field", "name", "constraint", "REQUIRED"),
            Map.of("field", "timezone", "constraint", "FORMAT"),
            Map.of("field", "effectiveFrom", "constraint", "REQUIRED"));
  }

  @Test
  void pageLimitsAreBounded() {
    assertThat(validator.page(null).limit()).isEqualTo(50);
    assertThat(validator.page("1").limit()).isEqualTo(1);
    assertThat(validator.page("200").limit()).isEqualTo(200);
    for (String bad : List.of("0", "201", "-5", "1e2", " 5", "999")) {
      assertThat(fields(() -> validator.page(bad)))
          .as(bad)
          .containsExactly(Map.of("field", "limit", "constraint", "RANGE"));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> fields(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
      return (List<Map<String, Object>>) e.params().get("fields");
    }
    throw new AssertionError("expected VALIDATION_FAILED");
  }
}
