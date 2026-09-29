package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MVP-002 Increment 3B team listing: exactly one parent filter, stable byte order, tenant
 * predicates on every page, explicit parent checks, and cursors bound to operation, tenant, parent
 * type and parent id.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TeamListingIntegrationTest {

  private static final String PATH = "/api/v1/teams";

  @Autowired private MockMvc mvc;

  private UUID tenantA;
  private UUID tenantB;
  private UUID siteA;
  private UUID departmentA;
  private UUID departmentA2;
  private UUID costCenterA;
  private UUID departmentB;

  @BeforeEach
  void hierarchy() throws Exception {
    tenantA = Hierarchy.newTenant(mvc);
    tenantB = Hierarchy.newTenant(mvc);
    UUID legalA = Hierarchy.newLegalEntity(mvc, tenantA, code("la"), "2026-01-01", null);
    UUID legalB = Hierarchy.newLegalEntity(mvc, tenantB, code("lb"), "2026-01-01", null);
    siteA = Hierarchy.newSite(mvc, tenantA, legalA, code("sa"), "2026-01-01", null);
    UUID siteB = Hierarchy.newSite(mvc, tenantB, legalB, code("sb"), "2026-01-01", null);
    departmentA = unit("/api/v1/departments", tenantA, siteA, "da");
    departmentA2 = unit("/api/v1/departments", tenantA, siteA, "da2");
    costCenterA = unit("/api/v1/cost-centers", tenantA, siteA, "ca");
    departmentB = unit("/api/v1/departments", tenantB, siteB, "db");
  }

  private UUID unit(String path, UUID tenant, UUID site, String prefix) throws Exception {
    return Hierarchy.newSiteUnit(mvc, path, tenant, site, code(prefix), "2026-01-01", null);
  }

  private UUID parent(TeamParentResource resource) {
    return resource == TeamParentResource.DEPARTMENT ? departmentA : costCenterA;
  }

  private static String path(TeamParentResource resource, UUID parent, String extra) {
    return PATH + "?" + resource.field + "=" + parent + extra;
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void emptyListUnderAnExistingParentIsOk(TeamParentResource resource) throws Exception {
    JsonNode page = ok(admin(tenantA), path(resource, parent(resource), ""));
    assertThat(page.get("data")).isEmpty();
    assertThat(page.has("nextCursor")).isFalse();
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void pagesInCodeOrderBeneathTheSelectedParentOnly(TeamParentResource resource) throws Exception {
    String prefix = resource == TeamParentResource.DEPARTMENT ? "D" : "C";
    // Byte order: '-' (0x2D) < '1' (0x31) < 'B' (0x42) < '_' (0x5F).
    for (String suffix : List.of("_1", "B", "1", "-1")) {
      Hierarchy.newTeam(
          mvc, tenantA, resource.field, parent(resource), prefix + suffix, "2026-01-01", null);
    }
    // Teams beneath other parents never appear.
    Hierarchy.newTeam(
        mvc, tenantA, "departmentId", departmentA2, prefix + "-0", "2026-01-01", null);
    Hierarchy.newTeam(
        mvc,
        tenantA,
        resource.otherField,
        parent(resource.other()),
        prefix + "-00",
        "2026-01-01",
        null);
    Hierarchy.newTeam(
        mvc, tenantB, "departmentId", departmentB, prefix + "-000", "2026-01-01", null);

    List<String> codes = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page =
          ok(
              admin(tenantA),
              path(
                  resource,
                  parent(resource),
                  "&limit=3" + (cursor == null ? "" : "&cursor=" + cursor)));
      for (JsonNode team : page.get("data")) {
        codes.add(team.get("code").asText());
        assertThat(team.get(resource.field).asText()).isEqualTo(parent(resource).toString());
        assertThat(team.get(resource.otherField).isNull()).isTrue();
        assertThat(team.get("siteId").asText()).isEqualTo(siteA.toString());
        assertThat(team.has("tenantId")).isFalse();
        assertThat(team.has("createdBy")).isFalse();
      }
      assertThat(page.has("total")).isFalse();
      cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null);
    assertThat(codes).containsExactly(prefix + "-1", prefix + "1", prefix + "B", prefix + "_1");
    assertThat(pages).isEqualTo(2);

    // Tenant and site parameters in headers or query are ignored.
    JsonNode other =
        json(
            mvc.perform(
                    list(
                            admin(tenantA),
                            path(
                                TeamParentResource.DEPARTMENT,
                                departmentA2,
                                "&tenantId=" + tenantB + "&siteId=" + UUID.randomUUID()))
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(other.get("data")).hasSize(1);
    assertThat(other.get("data").get(0).get("code").asText()).isEqualTo(prefix + "-0");
  }

  @ParameterizedTest
  @EnumSource(TeamParentResource.class)
  void missingAndForeignParentsAreIndistinguishable(TeamParentResource resource) throws Exception {
    UUID foreign = resource == TeamParentResource.DEPARTMENT ? departmentB : costCenterB();
    JsonNode foreignPage = notFound(resource, admin(tenantA), path(resource, foreign, ""));
    JsonNode missingPage =
        notFound(resource, admin(tenantA), path(resource, UUID.randomUUID(), ""));
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreignPage))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missingPage));
    assertThat(foreignPage.toString()).doesNotContain(foreign.toString());
    // A department id sent as a cost center (and the reverse) is not found either.
    notFound(resource.other(), admin(tenantA), path(resource.other(), parent(resource), ""));
  }

  private UUID costCenterB() throws Exception {
    UUID legal = Hierarchy.newLegalEntity(mvc, tenantB, code("lc"), "2026-01-01", null);
    UUID site = Hierarchy.newSite(mvc, tenantB, legal, code("sc"), "2026-01-01", null);
    return unit("/api/v1/cost-centers", tenantB, site, "cb");
  }

  @Test
  void exactlyOneParentFilterIsRequiredAfterFormatValidation() throws Exception {
    problem(PATH, "TEAM_PARENT_REQUIRED").andExpect(jsonPath("$.params").isEmpty());
    problem(PATH + "?departmentId=&costCenterId=", "TEAM_PARENT_REQUIRED");
    problem(
            PATH + "?departmentId=" + departmentA + "&costCenterId=" + costCenterA,
            "TEAM_PARENT_AMBIGUOUS")
        .andExpect(jsonPath("$.params").isEmpty())
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain(departmentA.toString())
                    .doesNotContain(costCenterA.toString()));
    // Format and limit are validated before cardinality.
    problem(PATH + "?departmentId=not-a-uuid&costCenterId=" + costCenterA, "VALIDATION_FAILED")
        .andExpect(jsonPath("$.params.fields[0].field").value("departmentId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"));
    problem(PATH + "?limit=0", "VALIDATION_FAILED")
        .andExpect(jsonPath("$.params.fields[0].field").value("limit"));
    for (String limit : List.of("0", "201", "abc", "-1")) {
      problem(
              path(TeamParentResource.DEPARTMENT, departmentA, "&limit=" + limit),
              "VALIDATION_FAILED")
          .andExpect(jsonPath("$.params.fields[0].constraint").value("RANGE"));
    }
    ok(admin(tenantA), path(TeamParentResource.DEPARTMENT, departmentA, "&limit=200"));
    // Cardinality precedes the cursor.
    problem(PATH + "?cursor=garbage", "TEAM_PARENT_REQUIRED");
  }

  @Test
  void defaultPageSizeIsFifty() throws Exception {
    for (int i = 0; i < 51; i++) {
      Hierarchy.newTeam(
          mvc, tenantA, "departmentId", departmentA, "T%02d".formatted(i), "2026-01-01", null);
    }
    JsonNode page = ok(admin(tenantA), path(TeamParentResource.DEPARTMENT, departmentA, ""));
    assertThat(page.get("data")).hasSize(50);
    assertThat(page.has("nextCursor")).isTrue();
  }

  @Test
  void cursorsAreBoundToTenantOperationParentTypeAndParentId(CapturedOutput output)
      throws Exception {
    Hierarchy.newTeam(mvc, tenantA, "departmentId", departmentA, "K-1", "2026-01-01", null);
    Hierarchy.newTeam(mvc, tenantA, "departmentId", departmentA, "K-2", "2026-01-01", null);
    Hierarchy.newTeam(mvc, tenantA, "departmentId", departmentA2, "K-3", "2026-01-01", null);
    Hierarchy.newTeam(mvc, tenantA, "costCenterId", costCenterA, "K-4", "2026-01-01", null);
    TeamParentResource department = TeamParentResource.DEPARTMENT;
    String cursor =
        ok(admin(tenantA), path(department, departmentA, "&limit=1")).get("nextCursor").asText();

    assertThat(
            ok(admin(tenantA), path(department, departmentA, "&limit=1&cursor=" + cursor))
                .get("data"))
        .hasSize(1);
    // Another parent id of the same type.
    cursorInvalid(admin(tenantA), path(department, departmentA2, "&cursor=" + cursor));
    // The other parent type.
    cursorInvalid(
        admin(tenantA), path(TeamParentResource.COST_CENTER, costCenterA, "&cursor=" + cursor));
    // Another tenant (its own department).
    cursorInvalid(admin(tenantB), path(department, departmentB, "&cursor=" + cursor));
    // Other operations, and their cursors here.
    cursorInvalid(admin(tenantA), "/api/v1/departments?siteId=" + siteA + "&cursor=" + cursor);
    Hierarchy.newSiteUnit(
        mvc, "/api/v1/departments", tenantA, siteA, code("zz"), "2026-01-01", null);
    String departmentCursor =
        ok(admin(tenantA), "/api/v1/departments?limit=1&siteId=" + siteA)
            .get("nextCursor")
            .asText();
    cursorInvalid(admin(tenantA), path(department, departmentA, "&cursor=" + departmentCursor));
    // Tampering, truncation, oversize and empty.
    char flipped = cursor.charAt(3) == 'A' ? 'B' : 'A';
    cursorInvalid(
        admin(tenantA),
        path(
            department,
            departmentA,
            "&cursor=" + cursor.substring(0, 3) + flipped + cursor.substring(4)));
    cursorInvalid(
        admin(tenantA),
        path(department, departmentA, "&cursor=" + cursor.substring(0, cursor.length() - 2)));
    cursorInvalid(
        admin(tenantA), path(department, departmentA, "&cursor=" + "a".repeat(600) + ".b"));
    cursorInvalid(admin(tenantA), path(department, departmentA, "&cursor="));
    assertThat(output.getAll()).doesNotContain(cursor);
  }

  @Test
  void onlyTenantAdministratorsMayList() throws Exception {
    String path = path(TeamParentResource.DEPARTMENT, departmentA, "");
    for (String role : List.of("employee", "platform-admin")) {
      mvc.perform(list(bearer(tenantA, "sub-" + role, role), path))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    mvc.perform(list(bearer(tenantA, "  ", "tenant-admin"), PATH + "?limit=abc"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(MockMvcRequestBuilders.get(path)).andExpect(status().isUnauthorized());
  }

  private JsonNode ok(String bearer, String path) throws Exception {
    return json(
        mvc.perform(list(bearer, path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private org.springframework.test.web.servlet.ResultActions problem(String path, String code)
      throws Exception {
    return mvc.perform(list(admin(tenantA), path))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(code));
  }

  private JsonNode notFound(TeamParentResource resource, String bearer, String path)
      throws Exception {
    return json(
        mvc.perform(list(bearer, path))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(resource.notFoundCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void cursorInvalid(String bearer, String path) throws Exception {
    mvc.perform(list(bearer, path))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CURSOR_INVALID"))
        .andExpect(jsonPath("$.params").isEmpty());
  }
}
