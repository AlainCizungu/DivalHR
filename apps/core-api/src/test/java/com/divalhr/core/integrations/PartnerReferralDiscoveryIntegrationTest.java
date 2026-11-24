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
import java.util.ArrayList;
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
 * MVP-120F (Issue #224, ADR 0045): the employee's partner-product discovery and the stateless
 * disclosure preview against real PostgreSQL. Eligibility and the uniform 404, role boundaries, the
 * exact allow-listed disclosure, blockers, strict input, the hourly preview quota and privacy.
 * Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-referral-read=600",
      "divalhr.request-limits.subject.partner-referral-preview=600",
      "divalhr.request-limits.subject.catalogue-read=600"
    })
class PartnerReferralDiscoveryIntegrationTest {

  private static final String PRODUCTS = "/api/v1/me/partner-products";
  private static final String PREVIEW = "/api/v1/me/partner-referrals/preview";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private Employee employee(UUID tenant, String email) {
    return PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", email);
  }

  private MockHttpServletResponse preview(Employee who, Map<String, Object> body) throws Exception {
    return mvc.perform(
            post(PREVIEW)
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(PartnerWorld.JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private static Map<String, Object> inputs(
      String product, String language, String phone, boolean org) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("productId", product);
    body.put("language", language);
    body.put("includePhone", phone != null);
    body.put("phone", phone);
    body.put("includeOrganizationName", org);
    return body;
  }

  private static List<String> names(JsonNode fields) {
    List<String> names = new ArrayList<>();
    fields.forEach(field -> names.add(field.path("field").asText()));
    return names;
  }

  @Test
  void anEmployeeSeesOnlyTheProductsAvailableInTheirOrganizationsCountry() throws Exception {
    UUID cd = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer CD");
    UUID rw = PartnerReferralWorld.organization(jdbc, "RW", "Synthetic Employer RW");
    Product offered = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Product paused = PartnerReferralWorld.product(jdbc, "ACTIVE", "PAUSED", List.of("CD"));
    Product suspended = PartnerReferralWorld.product(jdbc, "SUSPENDED", "ACTIVE", List.of("CD"));
    Employee congolese = employee(cd, "amina@example.test");
    Employee rwandan = employee(rw, "amina@example.test");

    MockHttpServletResponse list = PartnerWorld.read(mvc, congolese.bearer(), PRODUCTS);
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(list.getHeader("Cache-Control")).isEqualTo("private, no-store");
    List<String> references = new ArrayList<>();
    body(list)
        .path("items")
        .forEach(item -> references.add(item.path("product").path("publicReference").asText()));
    assertThat(references).contains(offered.reference());
    assertThat(references).doesNotContain(paused.reference(), suspended.reference());
    JsonNode item = body(list).path("items").get(references.indexOf(offered.reference()));
    assertThat(item.path("referralOpen").asBoolean()).isTrue();
    assertThat(item.path("categories").get(0).asText()).isEqualTo("BANK");
    assertThat(item.path("categories").get(1).asText()).isEqualTo("MICROFINANCE");

    assertThat(
            PartnerWorld.read(mvc, congolese.bearer(), PRODUCTS + "/" + offered.reference())
                .getStatus())
        .isEqualTo(200);
    // unknown, malformed, paused, suspended partner and foreign country are one and the same 404
    List<String> unavailable =
        List.of(
            PartnerReferralWorld.ref("PP-"),
            "not-a-reference",
            paused.reference(),
            suspended.reference());
    String first = null;
    for (String reference : unavailable) {
      MockHttpServletResponse response =
          PartnerWorld.read(mvc, congolese.bearer(), PRODUCTS + "/" + reference);
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(code(response)).isEqualTo("PARTNER_REFERRAL_PRODUCT_UNAVAILABLE");
      first = first == null ? status(response) : first;
      assertThat(status(response)).isEqualTo(first);
    }
    MockHttpServletResponse foreign =
        PartnerWorld.read(mvc, rwandan.bearer(), PRODUCTS + "/" + offered.reference());
    assertThat(foreign.getStatus()).isEqualTo(404);
    assertThat(code(foreign)).isEqualTo("PARTNER_REFERRAL_PRODUCT_UNAVAILABLE");
  }

  private static String status(MockHttpServletResponse response) throws Exception {
    JsonNode problem = body(response);
    return problem.path("status") + ":" + problem.path("code").asText();
  }

  @Test
  void onlyAnEmployeeHasARouteHere() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Roles");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    String admin = PartnerReferralWorld.admin(jdbc, tenant);
    assertThat(PartnerWorld.read(mvc, admin, PRODUCTS).getStatus()).isEqualTo(403);
    assertThat(PartnerWorld.read(mvc, admin, PRODUCTS + "/" + product.reference()).getStatus())
        .isEqualTo(403);
    MockHttpServletResponse previewByAdmin =
        mvc.perform(
                post(PREVIEW)
                    .header("Authorization", admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        PartnerWorld.JSON.writeValueAsString(
                            inputs(product.reference(), "en", null, false))))
            .andReturn()
            .getResponse();
    assertThat(previewByAdmin.getStatus()).isEqualTo(403);
    assertThat(mvc.perform(get(PRODUCTS)).andReturn().getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void theAllowListedDisclosureIsExactAndShowsWhatIsNeverShared() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Preview");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee employee = employee(tenant, "amina@example.test");

    MockHttpServletResponse minimal =
        preview(employee, inputs(product.reference(), "en", null, false));
    assertThat(minimal.getStatus()).as(minimal.getContentAsString()).isEqualTo(200);
    assertThat(minimal.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode preview = body(minimal);
    assertThat(names(preview.path("fields")))
        .containsExactly(
            "REFERRAL_REFERENCE",
            "PRODUCT_REFERENCE",
            "PRODUCT_NAME",
            "PARTNER_NAME",
            "EMPLOYEE_LEGAL_NAME",
            "EMPLOYEE_EMAIL",
            "EMPLOYEE_COUNTRY",
            "PREFERRED_LANGUAGE",
            "CONSENT_TIMESTAMP",
            "CONSENT_VERSION");
    Map<String, String> values = new LinkedHashMap<>();
    preview
        .path("fields")
        .forEach(
            f ->
                values.put(
                    f.path("field").asText(),
                    f.path("value").isNull() ? null : f.path("value").asText()));
    assertThat(values.get("EMPLOYEE_LEGAL_NAME")).isEqualTo("Amina Kabila");
    assertThat(values.get("EMPLOYEE_EMAIL")).isEqualTo("amina@example.test");
    assertThat(values.get("EMPLOYEE_COUNTRY")).isEqualTo("CD");
    assertThat(values.get("PREFERRED_LANGUAGE")).isEqualTo("en");
    assertThat(values.get("PRODUCT_NAME")).isEqualTo("Synthetic benefit");
    assertThat(values.get("PARTNER_NAME")).isEqualTo("Synthetic Partner");
    assertThat(values.get("CONSENT_VERSION")).isEqualTo("PR-REFERRAL-1");
    assertThat(values.get("REFERRAL_REFERENCE")).isNull();
    assertThat(values.get("CONSENT_TIMESTAMP")).isNull();
    assertThat(preview.path("neverShared")).hasSize(17);
    assertThat(preview.path("blockers")).isEmpty();
    assertThat(preview.path("previewDigest").asText()).matches("^[0-9a-f]{64}$");
    assertThat(preview.path("expiresAt").asText()).isNotBlank();
    assertThat(preview.path("notice").path("noDecision").asBoolean()).isTrue();
    assertThat(preview.path("notice").path("noMoneyMovement").asBoolean()).isTrue();
    assertThat(minimal.getContentAsString()).doesNotContain("Synthetic Employer Preview");

    JsonNode full =
        body(preview(employee, inputs(product.reference(), "fr", "+243810000000", true)));
    assertThat(names(full.path("fields"))).contains("EMPLOYEE_PHONE", "ORGANIZATION_LEGAL_NAME");
    full.path("fields")
        .forEach(
            f -> {
              if (f.path("field").asText().equals("PRODUCT_NAME")) {
                assertThat(f.path("value").asText()).isEqualTo("Avantage synthetique");
              }
              if (f.path("field").asText().equals("ORGANIZATION_LEGAL_NAME")) {
                assertThat(f.path("value").asText()).isEqualTo("Synthetic Employer Preview");
              }
            });
    assertThat(full.path("previewDigest").asText())
        .isNotEqualTo(preview.path("previewDigest").asText());
  }

  @Test
  void aMissingVerifiedEmailIsABlockerAndCarriesNoDigest() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Blocked");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee employee = employee(tenant, null);
    JsonNode preview = body(preview(employee, inputs(product.reference(), "en", null, false)));
    assertThat(preview.path("blockers").get(0).asText()).isEqualTo("MEMBERSHIP_EMAIL_UNAVAILABLE");
    assertThat(preview.path("previewDigest").isNull()).isTrue();
    assertThat(preview.path("expiresAt").isNull()).isTrue();
  }

  @Test
  void theBodyIsStrictAndRefusalsEchoNothing() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Strict");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee employee = employee(tenant, "amina@example.test");

    Map<String, Object> unknown = inputs(product.reference(), "en", null, false);
    unknown.put("salary", "1000");
    MockHttpServletResponse unknownProperty = preview(employee, unknown);
    assertThat(unknownProperty.getStatus()).isEqualTo(400);
    assertThat(code(unknownProperty)).isEqualTo("VALIDATION_FAILED");
    assertThat(unknownProperty.getContentAsString())
        .doesNotContain("salary")
        .doesNotContain("1000");

    Map<String, Object> badLanguage = inputs(product.reference(), "de", null, false);
    assertThat(code(preview(employee, badLanguage))).isEqualTo("VALIDATION_FAILED");

    Map<String, Object> phoneWithoutFlag = inputs(product.reference(), "en", null, false);
    phoneWithoutFlag.put("phone", "+243810000000");
    MockHttpServletResponse mismatch = preview(employee, phoneWithoutFlag);
    assertThat(mismatch.getStatus()).isEqualTo(400);
    assertThat(code(mismatch)).isEqualTo("PARTNER_REFERRAL_DISCLOSURE_INVALID");

    Map<String, Object> flagWithoutPhone = inputs(product.reference(), "en", null, false);
    flagWithoutPhone.put("includePhone", true);
    assertThat(code(preview(employee, flagWithoutPhone)))
        .isEqualTo("PARTNER_REFERRAL_DISCLOSURE_INVALID");

    MockHttpServletResponse badPhone =
        preview(employee, inputs(product.reference(), "en", "0810000000", false));
    assertThat(code(badPhone)).isEqualTo("PARTNER_REFERRAL_DISCLOSURE_INVALID");
    assertThat(badPhone.getContentAsString()).doesNotContain("0810000000");

    MockHttpServletResponse unavailable =
        preview(employee, inputs(PartnerReferralWorld.ref("PP-"), "en", null, false));
    assertThat(unavailable.getStatus()).isEqualTo(404);
    assertThat(code(unavailable)).isEqualTo("PARTNER_REFERRAL_PRODUCT_UNAVAILABLE");

    MockHttpServletResponse notJson =
        mvc.perform(
                post(PREVIEW)
                    .header("Authorization", employee.bearer())
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("x"))
            .andReturn()
            .getResponse();
    assertThat(notJson.getStatus()).isIn(400, 415);
  }
}
