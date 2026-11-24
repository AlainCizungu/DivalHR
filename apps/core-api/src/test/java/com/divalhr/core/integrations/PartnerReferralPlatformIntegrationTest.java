package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.integrations.PartnerReferralWorld.Employee;
import com.divalhr.core.integrations.PartnerReferralWorld.Product;
import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-120F (Issue #224, D120F-5): the read-only platform view. Only a platform administrator can
 * read it; contact fields are masked, no name or disclosed value appears, a detail read is audited
 * by reference, and nothing here writes. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-referral-read=600",
      "divalhr.request-limits.subject.partner-referral-preview=600",
      "divalhr.request-limits.subject.partner-referral-commit=600",
      "divalhr.request-limits.subject.partner-referral-platform-read=600"
    })
class PartnerReferralPlatformIntegrationTest {

  private static final String ME = "/api/v1/me/partner-referrals";
  private static final String PLATFORM = "/api/v1/platform/partner-referrals";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private String submit(Employee who, Product product, String phone, boolean org) throws Exception {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("productId", product.reference());
    in.put("language", "en");
    in.put("includePhone", phone != null);
    in.put("phone", phone);
    in.put("includeOrganizationName", org);
    MockHttpServletResponse preview =
        mvc.perform(
                post(ME + "/preview")
                    .header("Authorization", who.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PartnerWorld.JSON.writeValueAsString(in)))
            .andReturn()
            .getResponse();
    JsonNode p = body(preview);
    Map<String, Object> commit = new LinkedHashMap<>(in);
    commit.put("previewDigest", p.path("previewDigest").asText());
    commit.put("expiresAt", p.path("expiresAt").asText());
    commit.put(
        "consent", Map.of("version", p.path("consent").path("version").asText(), "affirmed", true));
    MockHttpServletResponse created =
        mvc.perform(
                post(ME)
                    .header("Authorization", who.bearer())
                    .header("Idempotency-Key", "synthetic-key-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PartnerWorld.JSON.writeValueAsString(commit)))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    return body(created).path("referralId").asText();
  }

  @Test
  void aPlatformAdministratorSeesTheMaskedOperationalViewAndNothingPersonal() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Platform");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee employee =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    String reference = submit(employee, product, "+243810000000", true);
    String admin = PartnerWorld.admin();

    MockHttpServletResponse detail = PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference);
    assertThat(detail.getStatus()).as(detail.getContentAsString()).isEqualTo(200);
    assertThat(detail.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode view = body(detail);
    assertThat(view.path("referralId").asText()).isEqualTo(reference);
    assertThat(view.path("organizationId").asText()).isEqualTo(tenant.toString());
    assertThat(view.path("state").asText()).isEqualTo("SUBMITTED");
    assertThat(view.path("attemptCount").asInt()).isZero();
    assertThat(view.path("contact").path("emailMasked").asText()).isEqualTo("a***@example.test");
    assertThat(view.path("contact").path("phoneMasked").asText()).isEqualTo("+243*****000");
    assertThat(detail.getContentAsString())
        .doesNotContain("amina@example.test")
        .doesNotContain("Amina")
        .doesNotContain("Kabila")
        .doesNotContain("+243810000000")
        .doesNotContain("Synthetic Employer Platform");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'partner-referral.platform-read'"
                    + " AND resource_id = (SELECT id FROM integrations.partner_referral WHERE reference = ?)",
                Long.class,
                reference))
        .isEqualTo(1L);

    JsonNode history = body(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference + "/history"));
    assertThat(history.path("referralId").asText()).isEqualTo(reference);
    assertThat(history.path("events")).hasSize(1);
    assertThat(history.path("events").get(0).path("toState").asText()).isEqualTo("SUBMITTED");

    MockHttpServletResponse list = PartnerWorld.read(mvc, admin, PLATFORM + "?limit=50");
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(list.getContentAsString()).contains(reference).doesNotContain("Kabila");
    assertThat(
            PartnerWorld.read(mvc, admin, PLATFORM + "?state=WITHDRAWN&limit=50")
                .getContentAsString())
        .doesNotContain(reference);
    assertThat(
            PartnerWorld.read(mvc, admin, PLATFORM + "?category=BANK&limit=50")
                .getContentAsString())
        .contains(reference);
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "?category=BOGUS").getStatus())
        .isEqualTo(400);
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "?state=BOGUS").getStatus()).isEqualTo(400);
  }

  @Test
  void theQueuePagesWithAFilterBoundCursor() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Paging");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee employee =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    submit(employee, product, null, false);
    submit(employee, product, null, true);
    String admin = PartnerWorld.admin();
    JsonNode first = body(PartnerWorld.read(mvc, admin, PLATFORM + "?limit=1"));
    assertThat(first.path("items")).hasSize(1);
    String cursor = first.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "?limit=1&cursor=" + cursor).getStatus())
        .isEqualTo(200);
    MockHttpServletResponse rebound =
        PartnerWorld.read(mvc, admin, PLATFORM + "?limit=1&state=WITHDRAWN&cursor=" + cursor);
    assertThat(rebound.getStatus()).isEqualTo(400);
  }

  @Test
  void everyoneElseIsRefusedAndAnUnknownReferenceIsAnUnrevealingNotFound() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Boundary");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee employee =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    String reference = submit(employee, product, null, false);
    String tenantAdmin = PartnerReferralWorld.admin(jdbc, tenant);
    for (String path :
        List.of(PLATFORM, PLATFORM + "/" + reference, PLATFORM + "/" + reference + "/history")) {
      assertThat(PartnerWorld.read(mvc, tenantAdmin, path).getStatus()).as(path).isEqualTo(403);
      assertThat(PartnerWorld.read(mvc, employee.bearer(), path).getStatus())
          .as(path)
          .isEqualTo(403);
      assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
          .as(path)
          .isEqualTo(401);
    }
    String admin = PartnerWorld.admin();
    for (String bad : List.of(PartnerReferralWorld.ref("PR-"), "not-a-reference")) {
      MockHttpServletResponse unknown = PartnerWorld.read(mvc, admin, PLATFORM + "/" + bad);
      assertThat(unknown.getStatus()).isEqualTo(404);
      assertThat(code(unknown)).isEqualTo("PARTNER_REFERRAL_NOT_FOUND");
      assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "/" + bad + "/history").getStatus())
          .isEqualTo(404);
    }
    // read-only: no write verb has a route
    assertThat(
            mvc.perform(
                    post(PLATFORM + "/" + reference)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isIn(404, 405);
  }
}
