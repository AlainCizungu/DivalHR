package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
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

/**
 * MVP-002 keyset listing: stable order, tenant predicates on every page, and cursors bound to
 * operation, tenant and parent.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class HierarchyListingIntegrationTest {

  private static final String LEGAL_ENTITIES = "/api/v1/legal-entities";

  @Autowired private MockMvc mvc;

  private UUID tenantA;
  private UUID tenantB;

  @BeforeEach
  void tenants() throws Exception {
    tenantA = Hierarchy.newTenant(mvc);
    tenantB = Hierarchy.newTenant(mvc);
  }

  @Test
  void legalEntitiesArePagedInCodeOrderWithinTheTokenTenantOnly() throws Exception {
    // Byte order: '-' (0x2D) < '1' (0x31) < 'B' (0x42) < '_' (0x5F).
    for (String code : List.of("A_1", "AB", "A1", "A-1")) {
      Hierarchy.newLegalEntity(mvc, tenantA, code, "2026-01-01", null);
    }
    Hierarchy.newLegalEntity(mvc, tenantB, "A-0", "2026-01-01", null);

    List<String> codes = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String path = LEGAL_ENTITIES + "?limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
      JsonNode page = ok(admin(tenantA), path);
      page.get("data").forEach(item -> codes.add(item.get("code").asText()));
      assertThat(page.get("data").size()).isLessThanOrEqualTo(3);
      cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null);

    assertThat(codes).containsExactly("A-1", "A1", "AB", "A_1");
    assertThat(pages).isEqualTo(2);

    JsonNode other = ok(admin(tenantB), LEGAL_ENTITIES);
    assertThat(other.get("data")).hasSize(1);
    assertThat(other.get("data").get(0).get("code").asText()).isEqualTo("A-0");
    assertThat(other.has("nextCursor")).isFalse();
    assertThat(other.get("data").get(0).has("tenantId")).isFalse();
  }

  @Test
  void tenantParametersAndHeadersAreIgnored() throws Exception {
    Hierarchy.newLegalEntity(mvc, tenantB, "B-SECRET", "2026-01-01", null);
    JsonNode page =
        json(
            mvc.perform(
                    list(admin(tenantA), LEGAL_ENTITIES + "?tenantId=" + tenantB)
                        .header("X-Tenant-Id", tenantB.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("data")).isEmpty();
  }

  @Test
  void limitIsValidated() throws Exception {
    for (String limit : List.of("0", "201", "abc", "-1", "1000")) {
      mvc.perform(list(admin(tenantA), LEGAL_ENTITIES + "?limit=" + limit))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.params.fields[0].field").value("limit"))
          .andExpect(jsonPath("$.params.fields[0].constraint").value("RANGE"));
    }
    mvc.perform(list(admin(tenantA), LEGAL_ENTITIES + "?limit=200")).andExpect(status().isOk());
  }

  @Test
  void cursorsAreBoundToTenantOperationAndParent(CapturedOutput output) throws Exception {
    UUID first = Hierarchy.newLegalEntity(mvc, tenantA, "P-1", "2026-01-01", null);
    UUID second = Hierarchy.newLegalEntity(mvc, tenantA, "P-2", "2026-01-01", null);
    Hierarchy.newSite(mvc, tenantA, first, Hierarchy.code("S1"), "2026-01-01", null);
    Hierarchy.newSite(mvc, tenantA, first, Hierarchy.code("S2"), "2026-01-01", null);
    Hierarchy.newSite(mvc, tenantA, second, Hierarchy.code("S3"), "2026-01-01", null);

    String legalCursor = ok(admin(tenantA), LEGAL_ENTITIES + "?limit=1").get("nextCursor").asText();
    String siteCursor =
        ok(admin(tenantA), "/api/v1/sites?limit=1&legalEntityId=" + first)
            .get("nextCursor")
            .asText();

    // Continuation works for the issuing scope.
    ok(admin(tenantA), LEGAL_ENTITIES + "?limit=1&cursor=" + legalCursor);
    JsonNode rest =
        ok(
            admin(tenantA),
            "/api/v1/sites?limit=1&legalEntityId=" + first + "&cursor=" + siteCursor);
    assertThat(rest.get("data")).hasSize(1);

    // Cross-tenant: tenant B replays tenant A's cursor.
    cursorInvalid(admin(tenantB), LEGAL_ENTITIES + "?cursor=" + legalCursor);
    // Cross-operation: a legal-entity cursor on the site list.
    cursorInvalid(
        admin(tenantA), "/api/v1/sites?legalEntityId=" + first + "&cursor=" + legalCursor);
    // Cross-parent: a site cursor for one parent used with another.
    cursorInvalid(
        admin(tenantA), "/api/v1/sites?legalEntityId=" + second + "&cursor=" + siteCursor);
    // Tampering and truncation.
    char flipped = legalCursor.charAt(3) == 'A' ? 'B' : 'A';
    cursorInvalid(
        admin(tenantA),
        LEGAL_ENTITIES
            + "?cursor="
            + legalCursor.substring(0, 3)
            + flipped
            + legalCursor.substring(4));
    cursorInvalid(
        admin(tenantA),
        LEGAL_ENTITIES + "?cursor=" + legalCursor.substring(0, legalCursor.length() - 2));
    cursorInvalid(admin(tenantA), LEGAL_ENTITIES + "?cursor=" + "a".repeat(600) + ".b");
    cursorInvalid(admin(tenantA), LEGAL_ENTITIES + "?cursor=");

    assertThat(output.getAll()).doesNotContain(legalCursor).doesNotContain(siteCursor);
  }

  @Test
  void siteListRequiresOwnParentAndHidesForeignParents() throws Exception {
    UUID foreign = Hierarchy.newLegalEntity(mvc, tenantB, Hierarchy.code("F"), "2026-01-01", null);
    Hierarchy.newSite(mvc, tenantB, foreign, Hierarchy.code("FS"), "2026-01-01", null);

    mvc.perform(list(admin(tenantA), "/api/v1/sites"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("legalEntityId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("REQUIRED"));

    JsonNode foreignBody = notFound(admin(tenantA), "/api/v1/sites?legalEntityId=" + foreign);
    JsonNode missingBody =
        notFound(admin(tenantA), "/api/v1/sites?legalEntityId=" + UUID.randomUUID());
    assertThat(SiteApiIntegrationTest.withoutCorrelationId(foreignBody))
        .isEqualTo(SiteApiIntegrationTest.withoutCorrelationId(missingBody));
    assertThat(foreignBody.toString()).doesNotContain(foreign.toString());

    JsonNode own = ok(admin(tenantB), "/api/v1/sites?legalEntityId=" + foreign);
    assertThat(own.get("data")).hasSize(1);
  }

  @Test
  void onlyTenantAdministratorsMayList() throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenantA, Hierarchy.code("L"), "2026-01-01", null);
    for (String role : List.of("employee", "platform-admin")) {
      String caller = bearer(tenantA, "sub-" + role, role);
      mvc.perform(list(caller, LEGAL_ENTITIES))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(list(caller, "/api/v1/sites?legalEntityId=" + parent))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(LEGAL_ENTITIES))
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
