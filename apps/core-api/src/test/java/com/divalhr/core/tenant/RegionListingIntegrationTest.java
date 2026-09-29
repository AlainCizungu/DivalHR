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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MVP-002 Increment 3A region listing: stable byte order, tenant predicates on every page, explicit
 * parent checks, and cursors bound to operation, tenant and legal entity.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RegionListingIntegrationTest {

  private static final String PATH = "/api/v1/regions";

  @Autowired private MockMvc mvc;

  private UUID tenantA;
  private UUID tenantB;
  private UUID legalA;
  private UUID legalA2;
  private UUID legalB;

  @BeforeEach
  void hierarchy() throws Exception {
    tenantA = Hierarchy.newTenant(mvc);
    tenantB = Hierarchy.newTenant(mvc);
    legalA = Hierarchy.newLegalEntity(mvc, tenantA, code("la"), "2026-01-01", null);
    legalA2 = Hierarchy.newLegalEntity(mvc, tenantA, code("la2"), "2026-01-01", null);
    legalB = Hierarchy.newLegalEntity(mvc, tenantB, code("lb"), "2026-01-01", null);
  }

  private static String path(UUID legalEntity, String extra) {
    return PATH + "?legalEntityId=" + legalEntity + extra;
  }

  @Test
  void emptyListUnderAnExistingLegalEntityIsOk() throws Exception {
    JsonNode page = ok(admin(tenantA), path(legalA, ""));
    assertThat(page.get("data")).isEmpty();
    assertThat(page.has("nextCursor")).isFalse();
  }

  @Test
  void pagesInCodeOrderWithinTheTokenTenantAndLegalEntityOnly() throws Exception {
    // Byte order: '-' (0x2D) < '1' (0x31) < 'B' (0x42) < '_' (0x5F).
    for (String code : List.of("A_1", "AB", "A1", "A-1")) {
      Hierarchy.newRegion(mvc, tenantA, legalA, code, "2026-01-01", null);
    }
    Hierarchy.newRegion(mvc, tenantA, legalA2, "A-0", "2026-01-01", null);
    Hierarchy.newRegion(mvc, tenantB, legalB, "A-00", "2026-01-01", null);

    List<String> codes = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page =
          ok(
              admin(tenantA),
              path(legalA, "&limit=3" + (cursor == null ? "" : "&cursor=" + cursor)));
      page.get("data").forEach(item -> codes.add(item.get("code").asText()));
      assertThat(page.get("data").size()).isLessThanOrEqualTo(3);
      JsonNode first = page.get("data").get(0);
      assertThat(first.has("tenantId")).isFalse();
      assertThat(first.has("createdBy")).isFalse();
      assertThat(first.get("legalEntityId").asText()).isEqualTo(legalA.toString());
      assertThat(page.has("total")).isFalse();
      cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null);
    assertThat(codes).containsExactly("A-1", "A1", "AB", "A_1");
    assertThat(pages).isEqualTo(2);

    // Tenant parameters in headers or query are ignored.
    JsonNode other =
        json(
            mvc.perform(
                    list(admin(tenantA), path(legalA2, "&tenantId=" + tenantB))
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(other.get("data")).hasSize(1);
    assertThat(other.get("data").get(0).get("code").asText()).isEqualTo("A-0");
  }

  @Test
  void missingAndForeignLegalEntitiesAreIndistinguishable() throws Exception {
    Hierarchy.newRegion(mvc, tenantB, legalB, code("fb"), "2026-01-01", null);
    JsonNode foreign = notFound(admin(tenantA), path(legalB, ""));
    JsonNode missing = notFound(admin(tenantA), path(UUID.randomUUID(), ""));
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreign))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missing));
    assertThat(foreign.toString()).doesNotContain(legalB.toString());
    assertThat(ok(admin(tenantB), path(legalB, "")).get("data")).hasSize(1);
  }

  @Test
  void parametersAreValidated() throws Exception {
    mvc.perform(list(admin(tenantA), PATH))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("legalEntityId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));
    mvc.perform(list(admin(tenantA), PATH + "?legalEntityId=not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("legalEntityId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"));
    for (String limit : List.of("0", "201", "abc", "-1")) {
      mvc.perform(list(admin(tenantA), path(legalA, "&limit=" + limit)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.fields[0].field").value("limit"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("RANGE"));
    }
    mvc.perform(list(admin(tenantA), path(legalA, "&limit=200"))).andExpect(status().isOk());
  }

  @Test
  void defaultPageSizeIsFifty() throws Exception {
    for (int i = 0; i < 51; i++) {
      Hierarchy.newRegion(mvc, tenantA, legalA, "R%02d".formatted(i), "2026-01-01", null);
    }
    JsonNode page = ok(admin(tenantA), path(legalA, ""));
    assertThat(page.get("data")).hasSize(50);
    assertThat(page.has("nextCursor")).isTrue();
  }

  @Test
  void cursorsAreBoundToTenantOperationAndLegalEntity(CapturedOutput output) throws Exception {
    Hierarchy.newRegion(mvc, tenantA, legalA, "C-1", "2026-01-01", null);
    Hierarchy.newRegion(mvc, tenantA, legalA, "C-2", "2026-01-01", null);
    Hierarchy.newRegion(mvc, tenantA, legalA2, "C-3", "2026-01-01", null);
    Hierarchy.newSite(mvc, tenantA, legalA, "S-1", "2026-01-01", null);
    Hierarchy.newSite(mvc, tenantA, legalA, "S-2", "2026-01-01", null);
    String cursor = ok(admin(tenantA), path(legalA, "&limit=1")).get("nextCursor").asText();

    assertThat(ok(admin(tenantA), path(legalA, "&limit=1&cursor=" + cursor)).get("data"))
        .hasSize(1);
    // Cross-legal-entity.
    cursorInvalid(admin(tenantA), path(legalA2, "&cursor=" + cursor));
    // Cross-tenant (tenant B's own legal entity).
    cursorInvalid(admin(tenantB), path(legalB, "&cursor=" + cursor));
    // Cross-operation, with the same legalEntityId filter: site list and back.
    cursorInvalid(admin(tenantA), "/api/v1/sites?legalEntityId=" + legalA + "&cursor=" + cursor);
    String siteCursor =
        ok(admin(tenantA), "/api/v1/sites?limit=1&legalEntityId=" + legalA)
            .get("nextCursor")
            .asText();
    cursorInvalid(admin(tenantA), path(legalA, "&cursor=" + siteCursor));
    cursorInvalid(admin(tenantA), "/api/v1/legal-entities?cursor=" + cursor);
    // Tampering, truncation, oversize and empty.
    char flipped = cursor.charAt(3) == 'A' ? 'B' : 'A';
    cursorInvalid(
        admin(tenantA),
        path(legalA, "&cursor=" + cursor.substring(0, 3) + flipped + cursor.substring(4)));
    cursorInvalid(
        admin(tenantA), path(legalA, "&cursor=" + cursor.substring(0, cursor.length() - 2)));
    cursorInvalid(admin(tenantA), path(legalA, "&cursor=" + "a".repeat(600) + ".b"));
    cursorInvalid(admin(tenantA), path(legalA, "&cursor="));
    assertThat(output.getAll()).doesNotContain(cursor);
  }

  @Test
  void onlyTenantAdministratorsMayList() throws Exception {
    for (String role : List.of("employee", "platform-admin")) {
      mvc.perform(list(bearer(tenantA, "sub-" + role, role), path(legalA, "")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    mvc.perform(list(bearer(tenantA, "  ", "tenant-admin"), path(legalA, "&limit=abc")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(MockMvcRequestBuilders.get(path(legalA, ""))).andExpect(status().isUnauthorized());
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
            .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"))
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
