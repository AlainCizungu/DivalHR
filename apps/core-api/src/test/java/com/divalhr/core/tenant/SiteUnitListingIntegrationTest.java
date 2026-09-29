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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-002 Increment 2 listing: stable byte order, tenant predicates on every page, explicit parent
 * checks, and cursors bound to operation, tenant and site.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SiteUnitListingIntegrationTest {

  @Autowired private MockMvc mvc;

  private UUID tenantA;
  private UUID tenantB;
  private UUID siteA;
  private UUID siteA2;
  private UUID siteB;

  @BeforeEach
  void hierarchy() throws Exception {
    tenantA = Hierarchy.newTenant(mvc);
    tenantB = Hierarchy.newTenant(mvc);
    UUID legalA = Hierarchy.newLegalEntity(mvc, tenantA, code("la"), "2026-01-01", null);
    UUID legalB = Hierarchy.newLegalEntity(mvc, tenantB, code("lb"), "2026-01-01", null);
    siteA = Hierarchy.newSite(mvc, tenantA, legalA, code("sa"), "2026-01-01", null);
    siteA2 = Hierarchy.newSite(mvc, tenantA, legalA, code("sa2"), "2026-01-01", null);
    siteB = Hierarchy.newSite(mvc, tenantB, legalB, code("sb"), "2026-01-01", null);
  }

  private String path(SiteUnitResource resource, UUID site, String extra) {
    return resource.path + "?siteId=" + site + extra;
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void emptyListUnderAnExistingSiteIsOk(SiteUnitResource resource) throws Exception {
    JsonNode page = ok(admin(tenantA), path(resource, siteA, ""));
    assertThat(page.get("data")).isEmpty();
    assertThat(page.has("nextCursor")).isFalse();
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void pagesInCodeOrderWithinTheTokenTenantAndSiteOnly(SiteUnitResource resource) throws Exception {
    // Byte order: '-' (0x2D) < '1' (0x31) < 'B' (0x42) < '_' (0x5F).
    for (String code : List.of("A_1", "AB", "A1", "A-1")) {
      Hierarchy.newSiteUnit(mvc, resource.path, tenantA, siteA, code, "2026-01-01", null);
    }
    Hierarchy.newSiteUnit(mvc, resource.path, tenantA, siteA2, "A-0", "2026-01-01", null);
    Hierarchy.newSiteUnit(mvc, resource.path, tenantB, siteB, "A-00", "2026-01-01", null);

    List<String> codes = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page =
          ok(
              admin(tenantA),
              path(resource, siteA, "&limit=3" + (cursor == null ? "" : "&cursor=" + cursor)));
      page.get("data").forEach(item -> codes.add(item.get("code").asText()));
      assertThat(page.get("data").size()).isLessThanOrEqualTo(3);
      assertThat(page.get("data").get(0).has("tenantId")).isFalse();
      cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null);
    assertThat(codes).containsExactly("A-1", "A1", "AB", "A_1");
    assertThat(pages).isEqualTo(2);

    // Tenant and site parameters in headers or query are ignored.
    JsonNode other =
        json(
            mvc.perform(
                    list(admin(tenantA), path(resource, siteA2, "&tenantId=" + tenantB))
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(other.get("data")).hasSize(1);
    assertThat(other.get("data").get(0).get("code").asText()).isEqualTo("A-0");
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void missingAndForeignSitesAreIndistinguishable(SiteUnitResource resource) throws Exception {
    Hierarchy.newSiteUnit(mvc, resource.path, tenantB, siteB, code("fb"), "2026-01-01", null);
    JsonNode foreign = notFound(admin(tenantA), path(resource, siteB, ""));
    JsonNode missing = notFound(admin(tenantA), path(resource, UUID.randomUUID(), ""));
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreign))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missing));
    assertThat(foreign.toString()).doesNotContain(siteB.toString());
    assertThat(ok(admin(tenantB), path(resource, siteB, "")).get("data")).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void parametersAreValidated(SiteUnitResource resource) throws Exception {
    mvc.perform(list(admin(tenantA), resource.path))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("siteId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
    mvc.perform(list(admin(tenantA), resource.path + "?siteId=not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("siteId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"));
    for (String limit : List.of("0", "201", "abc", "-1")) {
      mvc.perform(list(admin(tenantA), path(resource, siteA, "&limit=" + limit)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.fields[0].field").value("limit"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("RANGE"));
    }
    mvc.perform(list(admin(tenantA), path(resource, siteA, "&limit=200")))
        .andExpect(status().isOk());
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void cursorsAreBoundToTenantOperationAndSite(SiteUnitResource resource, CapturedOutput output)
      throws Exception {
    Hierarchy.newSiteUnit(mvc, resource.path, tenantA, siteA, "C-1", "2026-01-01", null);
    Hierarchy.newSiteUnit(mvc, resource.path, tenantA, siteA, "C-2", "2026-01-01", null);
    Hierarchy.newSiteUnit(mvc, resource.path, tenantA, siteA2, "C-3", "2026-01-01", null);
    String cursor =
        ok(admin(tenantA), path(resource, siteA, "&limit=1")).get("nextCursor").asText();

    assertThat(ok(admin(tenantA), path(resource, siteA, "&limit=1&cursor=" + cursor)).get("data"))
        .hasSize(1);
    // Cross-site.
    cursorInvalid(admin(tenantA), path(resource, siteA2, "&cursor=" + cursor));
    // Cross-tenant (tenant B's own site).
    cursorInvalid(admin(tenantB), path(resource, siteB, "&cursor=" + cursor));
    // Cross-operation: the other resource type and the site list.
    SiteUnitResource other =
        resource == SiteUnitResource.DEPARTMENT
            ? SiteUnitResource.COST_CENTER
            : SiteUnitResource.DEPARTMENT;
    cursorInvalid(admin(tenantA), path(other, siteA, "&cursor=" + cursor));
    cursorInvalid(admin(tenantA), "/api/v1/legal-entities?cursor=" + cursor);
    // Tampering, truncation, oversize and empty.
    char flipped = cursor.charAt(3) == 'A' ? 'B' : 'A';
    cursorInvalid(
        admin(tenantA),
        path(resource, siteA, "&cursor=" + cursor.substring(0, 3) + flipped + cursor.substring(4)));
    cursorInvalid(
        admin(tenantA),
        path(resource, siteA, "&cursor=" + cursor.substring(0, cursor.length() - 2)));
    cursorInvalid(admin(tenantA), path(resource, siteA, "&cursor=" + "a".repeat(600) + ".b"));
    cursorInvalid(admin(tenantA), path(resource, siteA, "&cursor="));
    assertThat(output.getAll()).doesNotContain(cursor);
  }

  @ParameterizedTest
  @EnumSource(SiteUnitResource.class)
  void onlyTenantAdministratorsMayList(SiteUnitResource resource) throws Exception {
    for (String role : List.of("employee", "platform-admin")) {
      mvc.perform(list(bearer(tenantA, "sub-" + role, role), path(resource, siteA, "")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    mvc.perform(list(bearer(tenantA, "  ", "tenant-admin"), path(resource, siteA, "&limit=abc")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                path(resource, siteA, "")))
        .andExpect(status().isUnauthorized());
  }

  private JsonNode ok(String bearer, String path) throws Exception {
    return json(
        mvc.perform(list(bearer, path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode notFound(String bearer, String path) throws Exception {
    return json(
        mvc.perform(list(bearer, path))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SITE_NOT_FOUND"))
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
