package com.divalhr.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.tenant.api.AssignSiteRegionRequest;
import com.divalhr.core.tenant.api.CreateLegalEntityRequest;
import com.divalhr.core.tenant.api.CreateRegionRequest;
import com.divalhr.core.tenant.api.CreateSiteRequest;
import com.divalhr.core.tenant.api.CreateTeamRequest;
import com.divalhr.core.tenant.application.HierarchyValidator;
import com.divalhr.core.tenant.application.LegalEntityCommand;
import com.divalhr.core.tenant.application.RegionCommand;
import com.divalhr.core.tenant.application.SiteCommand;
import com.divalhr.core.tenant.application.SiteRegionCommand;
import com.divalhr.core.tenant.application.TeamCommand;
import com.divalhr.core.tenant.application.TeamParentRef;
import com.divalhr.core.tenant.domain.TeamParentKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  void siteRegionIsOptionalAndOnlyChangesTheFingerprintWhenPresent() {
    String parent = "33333333-3333-4333-8333-333333333333";
    String region = "44444444-4444-4444-8444-444444444444";
    TenantId tenant = new TenantId(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    SiteCommand without =
        validator.site(
            KEY,
            CreateSiteRequest.of(parent, "S-1", "Site", "Africa/Kinshasa", "2026-01-01", null));
    SiteCommand withNull =
        validator.site(
            KEY,
            CreateSiteRequest.of(
                parent, null, "S-1", "Site", "Africa/Kinshasa", "2026-01-01", null));
    SiteCommand with =
        validator.site(
            KEY,
            CreateSiteRequest.of(
                parent,
                region.toUpperCase(java.util.Locale.ROOT),
                "S-1",
                "Site",
                "Africa/Kinshasa",
                "2026-01-01",
                null));
    assertThat(without.regionId()).isNull();
    assertThat(without.canonical(tenant)).doesNotContainKey("regionId");
    assertThat(withNull.canonical(tenant)).isEqualTo(without.canonical(tenant));
    assertThat(with.regionId()).isEqualTo(UUID.fromString(region));
    assertThat(with.canonical(tenant)).containsEntry("regionId", region);
    for (String bad : List.of("", "not-a-uuid")) {
      assertThat(
              fields(
                  () ->
                      validator.site(
                          KEY,
                          CreateSiteRequest.of(
                              parent, bad, "S-1", "Site", "Africa/Kinshasa", "2026-01-01", null))))
          .containsExactly(Map.of("field", "regionId", "constraint", "FORMAT"));
    }
  }

  @Test
  void validatesRegionsWithoutConsultingTheParent() {
    RegionCommand command =
        validator.region(
            KEY,
            CreateRegionRequest.of(
                "33333333-3333-4333-8333-333333333333",
                " kat_nord ",
                "  Région Grand Katanga  ",
                "2026-01-01",
                "2026-01-01"));
    assertThat(command.code()).isEqualTo("KAT_NORD");
    assertThat(command.name()).isEqualTo("Région Grand Katanga");
    assertThat(
            fields(
                () -> validator.region(null, CreateRegionRequest.of(null, null, null, null, null))))
        .containsExactlyInAnyOrder(
            Map.of("field", "Idempotency-Key", "constraint", "REQUIRED"),
            Map.of("field", "legalEntityId", "constraint", "REQUIRED"),
            Map.of("field", "code", "constraint", "REQUIRED"),
            Map.of("field", "name", "constraint", "REQUIRED"),
            Map.of("field", "effectiveFrom", "constraint", "REQUIRED"));
    assertThatThrownBy(
            () ->
                validator.region(
                    KEY,
                    CreateRegionRequest.of(
                        "33333333-3333-4333-8333-333333333333",
                        "R-1",
                        "Région",
                        "2026-02-01",
                        "2026-01-31")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.EFFECTIVE_DATE_INVALID));
  }

  @Test
  void validatesSiteRegionAssignmentsIncludingThePathSite() {
    SiteRegionCommand command =
        validator.siteRegion(
            "33333333-3333-4333-8333-333333333333",
            KEY,
            AssignSiteRegionRequest.of("44444444-4444-4444-8444-444444444444"));
    assertThat(command.siteId().toString()).isEqualTo("33333333-3333-4333-8333-333333333333");
    TenantId tenant = new TenantId(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    assertThat(command.canonical(tenant))
        .containsOnlyKeys("regionId", "siteId", "tenantId")
        .containsEntry("tenantId", tenant.toString());
    assertThat(
            fields(
                () -> validator.siteRegion("not-a-site", null, AssignSiteRegionRequest.of(null))))
        .containsExactlyInAnyOrder(
            Map.of("field", "siteId", "constraint", "FORMAT"),
            Map.of("field", "Idempotency-Key", "constraint", "REQUIRED"),
            Map.of("field", "regionId", "constraint", "REQUIRED"));
  }

  @Test
  void teamsNeedExactlyOneParentAfterFieldValidation() {
    String department = "33333333-3333-4333-8333-333333333333";
    String costCenter = "44444444-4444-4444-8444-444444444444";
    TeamCommand byDepartment =
        validator.team(
            KEY, CreateTeamRequest.of(department, null, " eq_1 ", " Équipe ", "2026-01-01", null));
    assertThat(byDepartment.parentKind()).isEqualTo(TeamParentKind.DEPARTMENT);
    assertThat(byDepartment.code()).isEqualTo("EQ_1");
    assertThat(byDepartment.name()).isEqualTo("Équipe");
    TeamCommand byCostCenter =
        validator.team(
            KEY, CreateTeamRequest.of(null, costCenter, "EQ-2", "Équipe", "2026-01-01", null));
    assertThat(byCostCenter.parentKind()).isEqualTo(TeamParentKind.COST_CENTER);
    TenantId tenant = new TenantId(UUID.fromString("55555555-5555-4555-8555-555555555555"));
    assertThat(byCostCenter.canonical(tenant))
        .containsEntry("parentType", "cost-center")
        .containsEntry("parentId", costCenter)
        .containsEntry("effectiveTo", "")
        .doesNotContainKeys("departmentId", "costCenterId", "siteId");
    assertThatThrownBy(
            () ->
                validator.team(
                    KEY, CreateTeamRequest.of(null, null, "EQ-3", "Équipe", "2026-01-01", null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.TEAM_PARENT_REQUIRED);
              assertThat(e.params()).isEmpty();
            });
    assertThatThrownBy(
            () ->
                validator.team(
                    KEY,
                    CreateTeamRequest.of(
                        department, costCenter, "EQ-4", "Équipe", "2026-01-01", null)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TEAM_PARENT_AMBIGUOUS));
    // A malformed supplied parent is a field error, reported before cardinality.
    assertThat(
            fields(
                () ->
                    validator.team(
                        KEY,
                        CreateTeamRequest.of(
                            "", costCenter, "EQ-5", "Équipe", "2026-01-01", null))))
        .containsExactly(Map.of("field", "departmentId", "constraint", "FORMAT"));
    // Cardinality precedes date order.
    assertThatThrownBy(
            () ->
                validator.team(
                    KEY,
                    CreateTeamRequest.of(null, null, "EQ-6", "Équipe", "2026-06-01", "2026-05-31")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TEAM_PARENT_REQUIRED));
  }

  @Test
  void teamListNeedsExactlyOneParentFilter() {
    String department = "33333333-3333-4333-8333-333333333333";
    TeamParentRef parent = validator.teamListParent(department, "", null);
    assertThat(parent.kind()).isEqualTo(TeamParentKind.DEPARTMENT);
    assertThatThrownBy(() -> validator.teamListParent(null, "", null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TEAM_PARENT_REQUIRED));
    assertThatThrownBy(() -> validator.teamListParent(department, department, null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.TEAM_PARENT_AMBIGUOUS));
    assertThat(fields(() -> validator.teamListParent("bad", null, "0")))
        .containsExactlyInAnyOrder(
            Map.of("field", "departmentId", "constraint", "FORMAT"),
            Map.of("field", "limit", "constraint", "RANGE"));
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
