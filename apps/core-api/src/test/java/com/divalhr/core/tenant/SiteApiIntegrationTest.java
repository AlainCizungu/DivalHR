package com.divalhr.core.tenant;

import static com.divalhr.core.support.Hierarchy.admin;
import static com.divalhr.core.support.Hierarchy.bearer;
import static com.divalhr.core.support.Hierarchy.code;
import static com.divalhr.core.support.Hierarchy.create;
import static com.divalhr.core.support.Hierarchy.json;
import static com.divalhr.core.support.Hierarchy.site;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** MVP-002 site creation: parent lookup, tenant isolation, time zone and period containment. */
@IntegrationTest
class SiteApiIntegrationTest {

  private static final String PATH = "/api/v1/sites";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private UUID tenant;

  @BeforeEach
  void newTenant() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
  }

  @Test
  void tenantAdminCreatesSiteBeneathOwnLegalEntity() throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenant, code("le"), "2026-01-01", null);
    String code = code("kin");
    String name = "Siège de Kinshasa-Gombe " + code;
    String body =
        mvc.perform(
                create(
                        PATH,
                        bearer(tenant, "sub-site-create-" + tenant, "tenant-admin"),
                        Organizations.newKey(),
                        site(parent, code, name, "Africa/Lubumbashi", "2026-02-01", "2026-12-31"))
                    .header("X-Correlation-Id", "mvp002-site-00001"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.legalEntityId").value(parent.toString()))
            .andExpect(jsonPath("$.code").value(code))
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.timezone").value("Africa/Lubumbashi"))
            .andExpect(jsonPath("$.effectiveFrom").value("2026-02-01"))
            .andExpect(jsonPath("$.effectiveTo").value("2026-12-31"))
            .andExpect(jsonPath("$.tenantId").doesNotExist())
            .andExpect(jsonPath("$.createdBy").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(json(body).get("id").asText());
    // Issue #21: the nullable region is always serialized, like effectiveTo.
    assertThat(json(body).has("regionId")).isTrue();
    assertThat(json(body).get("regionId").isNull()).isTrue();

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM tenant.site WHERE id = ?", id);
    assertThat(row.get("region_id")).isNull();
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(row.get("legal_entity_id")).isEqualTo(parent);
    assertThat(row.get("created_by")).isEqualTo("sub-site-create-" + tenant);

    Map<String, Object> audit =
        jdbc.queryForMap("SELECT * FROM platform.audit_event WHERE resource_id = ?", id);
    assertThat(audit.get("action")).isEqualTo("site.create");
    assertThat(audit.get("resource_type")).isEqualTo("site");
    assertThat(audit.get("tenant_id")).isEqualTo(tenant);
    assertThat(audit.get("metadata").toString()).doesNotContain(name);

    String envelope =
        jdbc.queryForObject(
            "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'subject' = ?",
            String.class,
            id.toString());
    CreateOrganizationApiIntegrationTest.assertEnvelopeValid(envelope);
    JsonNode event = json(envelope);
    assertThat(event.get("eventType").asText()).isEqualTo("tenant.site-created.v1");
    assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(event.get("data").get("siteId").asText()).isEqualTo(id.toString());
    assertThat(event.get("data").get("legalEntityId").asText()).isEqualTo(parent.toString());
    assertThat(event.get("data").has("name")).isFalse();
    assertThat(event.get("data").has("regionId")).isFalse();
  }

  @Test
  void foreignAndMissingParentsAreIndistinguishable() throws Exception {
    UUID otherTenant = Hierarchy.newTenant(mvc);
    UUID foreignParent = Hierarchy.newLegalEntity(mvc, otherTenant, code("fx"), "2026-01-01", null);
    UUID missingParent = UUID.randomUUID();
    String code = code("iso");

    JsonNode foreign =
        notFound(site(foreignParent, code, "Intrusion", "Africa/Kinshasa", "2026-01-01", null));
    JsonNode missing =
        notFound(site(missingParent, code, "Intrusion", "Africa/Kinshasa", "2026-01-01", null));

    assertThat(withoutCorrelationId(foreign)).isEqualTo(withoutCorrelationId(missing));
    assertThat(foreign.toString())
        .doesNotContain(foreignParent.toString())
        .doesNotContain(otherTenant.toString());
    Integer sites =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenant.site WHERE legal_entity_id = ?",
            Integer.class,
            foreignParent);
    assertThat(sites).isZero();
  }

  @Test
  void timeZoneMustBelongToTheParentsCountry() throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenant, code("tz"), "2026-01-01", null);
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                site(parent, code("par"), "Paris", "Europe/Paris", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TIMEZONE_NOT_SUPPORTED"))
        .andExpect(jsonPath("$.params.field").value("timezone"))
        .andExpect(jsonPath("$.params.supported[0]").value("Africa/Kinshasa"));
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                site(parent, code("bad"), "Nowhere", "Mars/Olympus", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.params.fields[0].field").value("timezone"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"));
  }

  @ParameterizedTest(name = "parent {0}..{1}, site {2}..{3} -> {4}")
  @CsvSource(
      nullValues = "open",
      value = {
        // Inclusive boundaries and same-day periods.
        "2026-01-01, 2026-12-31, 2026-01-01, 2026-12-31, 201",
        "2026-01-01, 2026-12-31, 2026-01-01, 2026-01-01, 201",
        "2026-01-01, 2026-12-31, 2026-12-31, 2026-12-31, 201",
        "2026-06-15, 2026-06-15, 2026-06-15, 2026-06-15, 201",
        // Open and closed combinations.
        "2026-01-01, open, 2026-01-01, open, 201",
        "2026-01-01, open, 2030-01-01, 2031-12-31, 201",
        "2026-01-01, 2026-12-31, 2026-01-01, open, effectiveTo",
        // One day outside either end.
        "2026-01-01, 2026-12-31, 2025-12-31, 2026-06-30, effectiveFrom",
        "2026-01-01, 2026-12-31, 2026-06-01, 2027-01-01, effectiveTo",
        "2026-01-01, open, 2025-12-31, open, effectiveFrom",
      })
  void sitePeriodMustLieWithinTheLegalEntity(
      String parentFrom, String parentTo, String from, String to, String expected)
      throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenant, code("per"), parentFrom, parentTo);
    var result =
        mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                site(parent, code("s"), "Période", "Africa/Kinshasa", from, to)));
    if ("201".equals(expected)) {
      result.andExpect(status().isCreated());
    } else {
      result
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("SITE_PERIOD_OUTSIDE_LEGAL_ENTITY"))
          .andExpect(jsonPath("$.params.field").value(expected));
    }
  }

  @Test
  void siteCodesAreUniquePerTenantRegardlessOfCase() throws Exception {
    UUID first = Hierarchy.newLegalEntity(mvc, tenant, code("a"), "2026-01-01", null);
    UUID second = Hierarchy.newLegalEntity(mvc, tenant, code("b"), "2026-01-01", null);
    String code = code("dup");
    Hierarchy.newSite(mvc, tenant, first, code, "2026-01-01", null);
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                site(
                    second,
                    code.toLowerCase(java.util.Locale.ROOT),
                    "Doublon",
                    "Africa/Kinshasa",
                    "2026-01-01",
                    null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SITE_CODE"))
        .andExpect(jsonPath("$.params.field").value("code"));
  }

  @Test
  void validationPrecedesParentLookupAndNeverEchoesValues() throws Exception {
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                site("not-a-uuid", "OK-01", "Nom", "Africa/Kinshasa", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("legalEntityId"))
        .andExpect(jsonPath("$.params.fields[0].constraint").value("FORMAT"))
        .andExpect(
            content ->
                assertThat(content.getResponse().getContentAsString())
                    .doesNotContain("not-a-uuid"));
    mvc.perform(
            create(
                PATH,
                admin(tenant),
                Organizations.newKey(),
                site(
                    UUID.randomUUID(),
                    "OK-02",
                    "Nom",
                    "Africa/Kinshasa",
                    "2026-05-01",
                    "2026-04-30")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_INVALID"));
  }

  @Test
  void onlyTenantAdministratorsMayCreateSites() throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenant, code("rol"), "2026-01-01", null);
    for (String role : List.of("employee", "platform-admin")) {
      mvc.perform(
              create(
                  PATH,
                  bearer(tenant, "sub-" + role, role),
                  Organizations.newKey(),
                  site(parent, code("den"), "Refusé", "Africa/Kinshasa", "2026-01-01", null)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    Integer sites =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenant.site WHERE legal_entity_id = ?", Integer.class, parent);
    assertThat(sites).isZero();
  }

  @Test
  void replayReturnsTheOriginalSite() throws Exception {
    UUID parent = Hierarchy.newLegalEntity(mvc, tenant, code("rp"), "2026-01-01", null);
    String key = Organizations.newKey();
    String body = site(parent, code("rp"), "Rejeu", "Africa/Kinshasa", "2026-01-01", null);
    String first =
        mvc.perform(create(PATH, admin(tenant), key, body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc.perform(create(PATH, admin(tenant), key, body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json(second)).isEqualTo(json(first));
  }

  private JsonNode notFound(String body) throws Exception {
    return json(
        mvc.perform(create(PATH, admin(tenant), Organizations.newKey(), body))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LEGAL_ENTITY_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  static JsonNode withoutCorrelationId(JsonNode problem) {
    ObjectNode copy = problem.deepCopy();
    copy.remove("correlationId");
    return copy;
  }
}
