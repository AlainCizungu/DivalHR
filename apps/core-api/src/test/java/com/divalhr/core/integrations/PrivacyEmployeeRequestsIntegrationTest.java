package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
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
 * LAUNCH-001 (Issue #226): the employee's data-rights requests. An employee sees and downloads only
 * their own; another employee and a tenant administrator see nothing; the platform sees a masked
 * view and its internal note never reaches the employee. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-referral-read=600",
      "divalhr.request-limits.subject.partner-referral-preview=600",
      "divalhr.request-limits.subject.partner-referral-commit=600",
      "divalhr.request-limits.subject.privacy-employee-read=600",
      "divalhr.request-limits.subject.privacy-employee-write=600",
      "divalhr.request-limits.subject.privacy-platform-read=600",
      "divalhr.request-limits.subject.privacy-platform-write=600"
    })
class PrivacyEmployeeRequestsIntegrationTest {

  private static final String ME_REFERRALS = "/api/v1/me/partner-referrals";
  private static final String ME = "/api/v1/me/privacy/requests";
  private static final String PLATFORM = "/api/v1/platform/privacy/requests";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private MockHttpServletResponse call(String path, String bearer, Object body) throws Exception {
    return mvc.perform(
            post(path)
                .header("Authorization", bearer)
                .header("Idempotency-Key", "synthetic-key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(PartnerWorld.JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private String submitReferral(Employee who, Product product) throws Exception {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("productId", product.reference());
    in.put("language", "en");
    in.put("includePhone", false);
    in.put("phone", null);
    in.put("includeOrganizationName", false);
    JsonNode preview =
        body(
            mvc.perform(
                    post(ME_REFERRALS + "/preview")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PartnerWorld.JSON.writeValueAsString(in)))
                .andReturn()
                .getResponse());
    Map<String, Object> commit = new LinkedHashMap<>(in);
    commit.put("previewDigest", preview.path("previewDigest").asText());
    commit.put("expiresAt", preview.path("expiresAt").asText());
    commit.put(
        "consent",
        Map.of("version", preview.path("consent").path("version").asText(), "affirmed", true));
    MockHttpServletResponse created = call(ME_REFERRALS, who.bearer(), commit);
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    return body(created).path("referralId").asText();
  }

  private JsonNode transition(String admin, String id, int version, String to, String reason)
      throws Exception {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("expectedVersion", version);
    in.put("toState", to);
    in.put("reasonCode", reason);
    in.put("employeeMessage", "synthetic message to the employee");
    in.put("internalNote", "synthetic internal note");
    MockHttpServletResponse moved = call(PLATFORM + "/" + id + "/transition", admin, in);
    assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
    return body(moved);
  }

  @Test
  void anAccessRequestIsPrivateDecidedByThePlatformAndDownloadedOnlyByItsOwner() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Requests");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee amina =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    Employee other =
        PartnerReferralWorld.employee(jdbc, tenant, "Jean", "Mukendi", "jean@example.test");
    String reference = submitReferral(amina, product);
    String admin = PartnerWorld.admin();
    String tenantAdmin = PartnerReferralWorld.admin(jdbc, tenant);

    MockHttpServletResponse created =
        call(ME, amina.bearer(), Map.of("requestType", "ACCESS", "referralId", reference));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode request = body(created);
    String id = request.path("id").asText();
    assertThat(request.path("state").asText()).isEqualTo("SUBMITTED");
    assertThat(request.path("exportAvailable").asBoolean()).isFalse();

    MockHttpServletResponse duplicate =
        call(ME, amina.bearer(), Map.of("requestType", "ACCESS", "referralId", reference));
    assertThat(duplicate.getStatus()).isEqualTo(409);
    assertThat(code(duplicate)).isEqualTo("PRIVACY_REQUEST_DUPLICATE_OPEN");

    // Another employee and a tenant administrator see nothing of it.
    assertThat(PartnerWorld.read(mvc, other.bearer(), ME + "/" + id).getStatus()).isEqualTo(404);
    assertThat(PartnerWorld.read(mvc, other.bearer(), ME + "/" + id + "/export").getStatus())
        .isEqualTo(404);
    assertThat(PartnerWorld.read(mvc, other.bearer(), ME).getContentAsString()).doesNotContain(id);
    assertThat(PartnerWorld.read(mvc, tenantAdmin, ME).getStatus()).isEqualTo(403);
    assertThat(PartnerWorld.read(mvc, tenantAdmin, PLATFORM).getStatus()).isEqualTo(403);
    assertThat(PartnerWorld.read(mvc, tenantAdmin, PLATFORM + "/" + id).getStatus()).isEqualTo(403);

    // No package before approval.
    MockHttpServletResponse early =
        PartnerWorld.read(mvc, amina.bearer(), ME + "/" + id + "/export");
    assertThat(early.getStatus()).isEqualTo(409);
    assertThat(code(early)).isEqualTo("PRIVACY_EXPORT_NOT_AVAILABLE");

    // The platform sees a masked view and decides.
    MockHttpServletResponse seen = PartnerWorld.read(mvc, admin, PLATFORM + "/" + id);
    assertThat(seen.getStatus()).as(seen.getContentAsString()).isEqualTo(200);
    assertThat(seen.getContentAsString())
        .doesNotContain("amina@example.test")
        .doesNotContain("Amina")
        .doesNotContain("Kabila");
    JsonNode review = transition(admin, id, 1, "UNDER_REVIEW", null);
    JsonNode approved = transition(admin, id, review.path("version").asInt(), "APPROVED", null);
    assertThat(approved.path("state").asText()).isEqualTo("APPROVED");

    // The employee sees the decision, never the internal note, and downloads the package.
    MockHttpServletResponse own = PartnerWorld.read(mvc, amina.bearer(), ME + "/" + id);
    assertThat(own.getContentAsString()).doesNotContain("synthetic internal note");
    assertThat(body(own).path("state").asText()).isEqualTo("APPROVED");
    assertThat(body(own).path("exportAvailable").asBoolean()).isTrue();
    MockHttpServletResponse download =
        PartnerWorld.read(mvc, amina.bearer(), ME + "/" + id + "/export");
    assertThat(download.getStatus()).as(download.getContentAsString()).isEqualTo(200);
    assertThat(download.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(download.getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename=\"divalhr-privacy-access-package.json\"");
    String digest = download.getHeader("X-Content-SHA256");
    JsonNode pkg = body(download);
    assertThat(digest).matches("^[0-9a-f]{64}$");
    assertThat(pkg.path("manifest").path("sha256").asText()).isEqualTo(digest);
    assertThat(download.getContentAsString())
        .contains(reference)
        .doesNotContain("Mukendi")
        .doesNotContain("jean@example.test")
        .doesNotContain("synthetic internal note");
    // A second download of the same package is identical.
    assertThat(
            PartnerWorld.read(mvc, amina.bearer(), ME + "/" + id + "/export").getContentAsString())
        .isEqualTo(download.getContentAsString());
  }

  @Test
  void anEmployeeCancelsAnOpenRequestOnceAndOnlyTheirOwn() throws Exception {
    UUID tenant = PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer Cancel");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    Employee amina =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    Employee other =
        PartnerReferralWorld.employee(jdbc, tenant, "Jean", "Mukendi", "jean@example.test");
    String reference = submitReferral(amina, product);
    JsonNode request =
        body(call(ME, amina.bearer(), Map.of("requestType", "ERASURE", "referralId", reference)));
    String id = request.path("id").asText();

    assertThat(
            call(ME + "/" + id + "/cancel", other.bearer(), Map.of("expectedVersion", 1))
                .getStatus())
        .isEqualTo(404);
    MockHttpServletResponse stale =
        call(ME + "/" + id + "/cancel", amina.bearer(), Map.of("expectedVersion", 7));
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(code(stale)).isEqualTo("PRIVACY_REQUEST_VERSION_STALE");
    MockHttpServletResponse cancelled =
        call(ME + "/" + id + "/cancel", amina.bearer(), Map.of("expectedVersion", 1));
    assertThat(cancelled.getStatus()).as(cancelled.getContentAsString()).isEqualTo(200);
    assertThat(body(cancelled).path("state").asText()).isEqualTo("CANCELLED_BY_EMPLOYEE");
    MockHttpServletResponse again =
        call(ME + "/" + id + "/cancel", amina.bearer(), Map.of("expectedVersion", 2));
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(code(again)).isEqualTo("PRIVACY_REQUEST_TRANSITION_INVALID");
  }
}
