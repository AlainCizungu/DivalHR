package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-097A (Issue #200, ADR 0040): the tenant read of the applicable statutory pack. Only a tenant
 * administrator reads it; the pack is selected by the legal entity's country and the payroll period
 * and never by a client-supplied pack; a period with no ACTIVE pack says so; only public provenance
 * is returned. Synthetic packs only: they prove the mechanism and state no law.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.statutory-pack-read=600",
      "divalhr.request-limits.subject.statutory-read=600"
    })
class StatutoryPackApplicabilityIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;
  private final ObjectMapper json = CompensationWorld.JSON;

  private StatutoryWorld w;

  @BeforeEach
  void world() throws Exception {
    GovernedPackFixtures.retireAll(jdbc);
    w = new StatutoryWorld(mvc, jdbc, lookups, clock);
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private String path(String period) {
    return "/api/v1/legal-entities/" + w.entity + "/statutory-pack-applicability"
        + (period == null ? "" : "?payrollPeriod=" + period);
  }

  private MockHttpServletResponse read(String bearer, String period) throws Exception {
    return mvc.perform(PayrollRunWorld.get(bearer, path(period))).andReturn().getResponse();
  }

  private JsonNode body(MockHttpServletResponse response) throws Exception {
    return json.readTree(response.getContentAsString());
  }

  @Test
  void noActivePackIsAClearAnswerNotAnError() throws Exception {
    MockHttpServletResponse response = read(w.admin, w.month.toString());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode answer = body(response);
    assertThat(answer.path("applicable").asBoolean()).isFalse();
    assertThat(answer.path("packCode").isNull()).isTrue();
  }

  @Test
  void theActivePackOfTheCountryAndPeriodIsReturnedWithPublicProvenanceOnly() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    String code =
        jdbc.queryForObject("SELECT code FROM payroll.statutory_rule_pack WHERE id = ?", String.class, pack);
    MockHttpServletResponse response = read(w.admin, w.month.toString());
    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode answer = body(response);
    assertThat(answer.path("applicable").asBoolean()).isTrue();
    assertThat(answer.path("packCode").asText()).isEqualTo(code);
    assertThat(answer.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(answer.path("country").asText()).isEqualTo("CD");
    List<String> names = new ArrayList<>();
    answer.fieldNames().forEachRemaining(names::add);
    assertThat(names)
        .containsExactlyInAnyOrder(
            "legalEntityId",
            "payrollPeriod",
            "country",
            "applicable",
            "packCode",
            "version",
            "semanticVersion",
            "state",
            "effectiveFrom",
            "effectiveTo",
            "sourceAuthority",
            "sourceTitle",
            "sourcePublishedOn",
            "sourceReference");
    assertThat(response.getContentAsString())
        .doesNotContain("rateBp")
        .doesNotContain("reviewer")
        .doesNotContain("sha256")
        .doesNotContain(GovernedPackFixtures.REVIEWER)
        .doesNotContain(GovernedPackFixtures.SUBMITTER);
  }

  @Test
  void aPeriodOutsideTheActiveIntervalHasNoApplicablePack() throws Exception {
    StatutoryWorld.verifiedPack(
        jdbc, StatutoryFixtures.SEED_JSON, "2090-01-01", "2090-12-31");
    JsonNode answer = body(read(w.admin, "2089-12"));
    assertThat(answer.path("applicable").asBoolean()).isFalse();
    assertThat(body(read(w.admin, "2090-06")).path("applicable").asBoolean()).isTrue();
    assertThat(body(read(w.admin, "2091-01")).path("applicable").asBoolean()).isFalse();
  }

  @Test
  void aRetiredPackNeverApplies() throws Exception {
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    assertThat(body(read(w.admin, w.month.toString())).path("applicable").asBoolean()).isTrue();
    GovernedPackFixtures.retire(jdbc, pack);
    assertThat(body(read(w.admin, w.month.toString())).path("applicable").asBoolean()).isFalse();
  }

  @Test
  void aMalformedOrMissingPeriodIsAFieldError() throws Exception {
    for (String period : new String[] {null, "", "2026-13", "2026-1", "abcd-ef", "2026-01-01"}) {
      assertThat(read(w.admin, period).getStatus()).as("period " + period).isEqualTo(400);
    }
  }

  @Test
  void employeesAndManagersAndPlatformAdministratorsAreRefused() throws Exception {
    for (String role : List.of("employee", "manager", "platform-admin")) {
      String subject = "sub-pack-applicability-" + role;
      if (role.equals("employee")) {
        Memberships.grant(w.tenant, subject, role);
      }
      String bearer =
          "Bearer "
              + TestTokens.token().tenant(w.tenant).subject(subject).roles(List.of(role)).build();
      assertThat(read(bearer, w.month.toString()).getStatus()).as(role).isEqualTo(403);
    }
  }

  @Test
  void aForeignLegalEntityIsNotFoundAndTheReadIsAudited() throws Exception {
    MockHttpServletResponse foreign =
        mvc.perform(
                PayrollRunWorld.get(
                    w.admin,
                    "/api/v1/legal-entities/" + UUID.randomUUID()
                        + "/statutory-pack-applicability?payrollPeriod=" + w.month))
            .andReturn()
            .getResponse();
    assertThat(foreign.getStatus()).isEqualTo(404);

    assertThat(read(w.admin, w.month.toString()).getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'statutory-pack.applicability-read'"
                    + " AND tenant_id = ? AND resource_id = ?",
                Integer.class,
                w.tenant,
                w.entity))
        .isGreaterThanOrEqualTo(1);
  }
}
