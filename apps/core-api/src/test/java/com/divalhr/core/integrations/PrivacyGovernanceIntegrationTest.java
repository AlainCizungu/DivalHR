package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.integrations.PartnerReferralWorld.Employee;
import com.divalhr.core.integrations.PartnerReferralWorld.Product;
import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * LAUNCH-001 (Issue #226): the platform governance flow end to end through the HTTP boundary. A
 * referral stays POLICY_PENDING until an ACTIVE reviewed policy is applied; a hold blocks a purge;
 * a purge is a preview and an exact-MFA confirmation that revalidates, deletes only what the
 * stamped rule allows, and leaves value-free evidence. Every value is SYNTHETIC. The policy here is
 * a test policy for a synthetic country, never a legal period.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-referral-read=600",
      "divalhr.request-limits.subject.partner-referral-preview=600",
      "divalhr.request-limits.subject.partner-referral-commit=600",
      "divalhr.request-limits.subject.partner-referral-withdraw=600",
      "divalhr.request-limits.subject.privacy-employee-read=600",
      "divalhr.request-limits.subject.privacy-employee-write=600",
      "divalhr.request-limits.subject.privacy-platform-read=600",
      "divalhr.request-limits.subject.privacy-platform-write=600"
    })
class PrivacyGovernanceIntegrationTest {

  private static final String ME_REFERRALS = "/api/v1/me/partner-referrals";
  private static final String ME = "/api/v1/me/privacy/requests";
  private static final String PLATFORM = "/api/v1/platform/privacy";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private static String key() {
    return "synthetic-key-" + UUID.randomUUID();
  }

  private static String country() {
    Random random = new Random(UUID.randomUUID().getMostSignificantBits());
    return "" + (char) ('A' + random.nextInt(26)) + (char) ('A' + random.nextInt(26));
  }

  private MockHttpServletResponse send(
      MockHttpServletRequestBuilder builder, String bearer, Object body) throws Exception {
    return mvc.perform(
            builder
                .header("Authorization", bearer)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(PartnerWorld.JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse call(String path, String bearer, Object body) throws Exception {
    return send(post(path), bearer, body);
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

  /** Withdraws the referral (it closes) and moves its closing event {@code days} back. */
  private void closeLongAgo(Employee who, String reference, int days) throws Exception {
    MockHttpServletResponse done =
        call(
            ME_REFERRALS + "/" + reference + "/withdraw",
            who.bearer(),
            Map.of("expectedVersion", 1, "confirmed", true));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    PartnerReferralWorld.withoutTriggers(
        jdbc,
        "UPDATE integrations.partner_referral_event SET occurred_at = now() - interval '"
            + days
            + " days' WHERE to_state = 'WITHDRAWN' AND referral_id = (SELECT id FROM"
            + " integrations.partner_referral WHERE reference = '"
            + reference
            + "');");
  }

  private static List<Map<String, Object>> rules() {
    List<Map<String, Object>> rules = new ArrayList<>();
    rules.add(rule("PARTNER_REFERRAL_DISCLOSURE", "CONSENTED_INTRODUCTION", "MINIMIZE", 1));
    rules.add(rule("PARTNER_REFERRAL_CONSENT", "CONSENT_EVIDENCE", "MINIMIZE", 5));
    rules.add(rule("PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS", "DELIVERY_TRUTH", "MINIMIZE", 1));
    rules.add(rule("PARTNER_REFERRAL_LIFECYCLE_EVIDENCE", "LIFECYCLE_EVIDENCE", "HOLD", null));
    return rules;
  }

  private static Map<String, Object> rule(
      String dataClass, String purpose, String action, Integer days) {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("dataClass", dataClass);
    rule.put("purpose", purpose);
    rule.put("action", action);
    rule.put("trigger", "REFERRAL_CLOSED");
    rule.put("durationDays", days);
    return rule;
  }

  /** Creates, completes the checklist of and activates a SYNTHETIC test policy for a country. */
  private String activePolicy(String admin, String country) throws Exception {
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("countryCode", country);
    draft.put("wordingVersion", "synthetic-1");
    draft.put("sourceReference", "SYNTHETIC test policy, not a legal period");
    draft.put("reviewDate", today);
    draft.put("effectiveFrom", today);
    draft.put("effectiveTo", null);
    draft.put("rules", rules());
    MockHttpServletResponse created = call(PLATFORM + "/policies", admin, draft);
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    JsonNode policy = body(created);
    String id = policy.path("id").asText();
    assertThat(policy.path("state").asText()).isEqualTo("DRAFT");

    Map<String, Object> checklist = new LinkedHashMap<>();
    for (String item :
        List.of(
            "jurisdictionIdentified",
            "sourceReferenceRecorded",
            "counselReviewRecorded",
            "dataClassesCovered",
            "lifecycleTriggerDefined",
            "durationsOrHoldSet",
            "employeeWordingVersionRecorded",
            "rollbackReviewComplete")) {
      checklist.put(item, true);
    }
    checklist.put("reviewReference", "SYNTHETIC-REVIEW-1");
    MockHttpServletResponse listed =
        send(
            put(PLATFORM + "/policies/" + id + "/checklist"),
            admin,
            Map.of("expectedVersion", policy.path("version").asInt(), "checklist", checklist));
    assertThat(listed.getStatus()).as(listed.getContentAsString()).isEqualTo(200);
    int version = body(listed).path("version").asInt();
    for (String to : List.of("APPROVED", "ACTIVE")) {
      MockHttpServletResponse moved =
          call(
              PLATFORM + "/policies/" + id + "/transition",
              admin,
              Map.of("expectedVersion", version, "toState", to));
      assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
      version = body(moved).path("version").asInt();
    }
    return id;
  }

  private long rows(String table, String reference) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM integrations."
                + table
                + " x JOIN integrations.partner_referral r ON r.tenant_id = x.tenant_id AND"
                + " r.id = x.referral_id WHERE r.reference = ?",
            Long.class,
            reference);
    return count == null ? -1 : count;
  }

  private JsonNode draft(String admin, String country) throws Exception {
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("countryCode", country);
    draft.put("wordingVersion", "synthetic-1");
    draft.put("sourceReference", "SYNTHETIC test policy, not a legal period");
    draft.put("reviewDate", today);
    draft.put("effectiveFrom", today);
    draft.put("effectiveTo", null);
    draft.put("rules", rules());
    MockHttpServletResponse created = call(PLATFORM + "/policies", admin, draft);
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    return body(created);
  }

  private JsonNode applyPolicy(String admin, String country) throws Exception {
    JsonNode preview =
        body(call(PLATFORM + "/policy-application/preview", admin, Map.of("countryCode", country)));
    MockHttpServletResponse done =
        call(
            PLATFORM + "/policy-application",
            admin,
            Map.of(
                "previewId", preview.path("previewId").asText(),
                "previewDigest", preview.path("previewDigest").asText()));
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    return body(done);
  }

  private JsonNode purgePreview(String admin, String... references) throws Exception {
    return body(
        call(
            PLATFORM + "/purge/preview",
            admin,
            Map.of("referralIds", List.of(references), "action", "MINIMIZE")));
  }

  private MockHttpServletResponse purgeCommit(String admin, JsonNode preview) throws Exception {
    return call(
        PLATFORM + "/purge",
        admin,
        Map.of(
            "previewId", preview.path("previewId").asText(),
            "previewDigest", preview.path("previewDigest").asText()));
  }

  @Test
  void aReferralIsPendingUntilAnActivePolicyIsAppliedAndAHoldBlocksItsPurge() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Governance");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
    Employee employee =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    String reference = submitReferral(employee, product);
    closeLongAgo(employee, reference, 10);
    String admin = PartnerWorld.admin();

    // Without an active reviewed policy nothing is eligible: POLICY_PENDING, no invented duration.
    JsonNode pending =
        body(
            call(
                PLATFORM + "/purge/preview",
                admin,
                Map.of("referralIds", List.of(reference), "action", "MINIMIZE")));
    assertThat(pending.path("eligibleClasses").asInt()).isZero();
    assertThat(pending.toString()).contains("POLICY_PENDING");

    activePolicy(admin, country);
    // An ACTIVE policy does not touch an existing referral by itself.
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM integrations.privacy_referral_stamp s JOIN"
                    + " integrations.partner_referral r ON r.tenant_id = s.tenant_id AND r.id ="
                    + " s.referral_id WHERE r.reference = ?",
                String.class,
                reference))
        .isEqualTo("POLICY_PENDING");

    JsonNode applicationPreview =
        body(call(PLATFORM + "/policy-application/preview", admin, Map.of("countryCode", country)));
    assertThat(applicationPreview.path("totalPending").asInt()).isEqualTo(1);
    MockHttpServletResponse stale =
        call(
            PLATFORM + "/policy-application",
            admin,
            Map.of(
                "previewId",
                applicationPreview.path("previewId").asText(),
                "previewDigest",
                "0".repeat(64)));
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(code(stale)).isEqualTo("PRIVACY_PREVIEW_STALE");
    MockHttpServletResponse applied =
        call(
            PLATFORM + "/policy-application",
            admin,
            Map.of(
                "previewId",
                applicationPreview.path("previewId").asText(),
                "previewDigest",
                applicationPreview.path("previewDigest").asText()));
    assertThat(applied.getStatus()).as(applied.getContentAsString()).isEqualTo(200);
    assertThat(body(applied).path("applied").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT s.status FROM integrations.privacy_referral_stamp s JOIN"
                    + " integrations.partner_referral r ON r.tenant_id = s.tenant_id AND r.id ="
                    + " s.referral_id WHERE r.reference = ?",
                String.class,
                reference))
        .isEqualTo("STAMPED");
    MockHttpServletResponse reused =
        call(
            PLATFORM + "/policy-application",
            admin,
            Map.of(
                "previewId",
                applicationPreview.path("previewId").asText(),
                "previewDigest",
                applicationPreview.path("previewDigest").asText()));
    assertThat(reused.getStatus()).isEqualTo(409);
    assertThat(code(reused)).isEqualTo("PRIVACY_PREVIEW_ALREADY_USED");

    // A hold blocks the purge; releasing it makes the classes eligible.
    Map<String, Object> hold = new LinkedHashMap<>();
    hold.put("reasonCode", "DISPUTE");
    hold.put("reviewDate", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
    hold.put("externalReference", "SYNTHETIC-HOLD-1");
    hold.put("internalNote", "synthetic internal note");
    MockHttpServletResponse placed =
        call(PLATFORM + "/referrals/" + reference + "/holds", admin, hold);
    assertThat(placed.getStatus()).as(placed.getContentAsString()).isEqualTo(201);
    assertThat(placed.getContentAsString()).doesNotContain("synthetic internal note");
    String holdId = body(placed).path("id").asText();
    JsonNode held =
        body(
            call(
                PLATFORM + "/purge/preview",
                admin,
                Map.of("referralIds", List.of(reference), "action", "MINIMIZE")));
    assertThat(held.path("eligibleClasses").asInt()).isZero();
    assertThat(held.toString()).contains("HOLD_ACTIVE");
    MockHttpServletResponse released =
        call(
            PLATFORM + "/referrals/" + reference + "/holds/" + holdId + "/release",
            admin,
            Map.of("internalNote", "synthetic release"));
    assertThat(released.getStatus()).as(released.getContentAsString()).isEqualTo(200);

    JsonNode preview =
        body(
            call(
                PLATFORM + "/purge/preview",
                admin,
                Map.of("referralIds", List.of(reference), "action", "MINIMIZE")));
    assertThat(preview.path("eligibleClasses").asInt()).isEqualTo(3);
    MockHttpServletResponse wrong =
        call(
            PLATFORM + "/purge",
            admin,
            Map.of(
                "previewId", preview.path("previewId").asText(), "previewDigest", "f".repeat(64)));
    assertThat(wrong.getStatus()).isEqualTo(409);
    assertThat(code(wrong)).isEqualTo("PRIVACY_PREVIEW_STALE");
    MockHttpServletResponse purged =
        call(
            PLATFORM + "/purge",
            admin,
            Map.of(
                "previewId",
                preview.path("previewId").asText(),
                "previewDigest",
                preview.path("previewDigest").asText()));
    assertThat(purged.getStatus()).as(purged.getContentAsString()).isEqualTo(200);
    assertThat(purged.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(purged.getContentAsString())
        .doesNotContain("amina@example.test")
        .doesNotContain("Amina")
        .doesNotContain("Kabila");
    assertThat(body(purged).path("action").asText()).isEqualTo("MINIMIZE");
    assertThat(rows("partner_referral_disclosure", reference)).isZero();
    assertThat(rows("partner_referral_consent", reference)).isZero();
    MockHttpServletResponse again =
        call(
            PLATFORM + "/purge",
            admin,
            Map.of(
                "previewId",
                preview.path("previewId").asText(),
                "previewDigest",
                preview.path("previewDigest").asText()));
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(code(again)).isEqualTo("PRIVACY_PREVIEW_ALREADY_USED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'privacy.purge.commit'",
                Long.class))
        .isGreaterThanOrEqualTo(1L);
  }

  // Mutation kills (LAUNCH-001): each test fails if the guard it names is removed.

  @Test
  void theChecklistGatesApprovalAndTheLifecycleCannotBeSkipped() throws Exception {
    String admin = PartnerWorld.admin();
    JsonNode policy = draft(admin, country());
    String id = policy.path("id").asText();
    int version = policy.path("version").asInt();

    MockHttpServletResponse unreviewed =
        call(
            PLATFORM + "/policies/" + id + "/transition",
            admin,
            Map.of("expectedVersion", version, "toState", "APPROVED"));
    assertThat(unreviewed.getStatus()).isEqualTo(422);
    assertThat(code(unreviewed)).isEqualTo("PRIVACY_POLICY_CHECKLIST_INCOMPLETE");
    MockHttpServletResponse skipped =
        call(
            PLATFORM + "/policies/" + id + "/transition",
            admin,
            Map.of("expectedVersion", version, "toState", "ACTIVE"));
    assertThat(skipped.getStatus()).isEqualTo(409);
    assertThat(code(skipped)).isEqualTo("PRIVACY_POLICY_TRANSITION_INVALID");

    Map<String, Object> checklist = new LinkedHashMap<>();
    for (String item :
        List.of(
            "jurisdictionIdentified",
            "sourceReferenceRecorded",
            "dataClassesCovered",
            "lifecycleTriggerDefined",
            "durationsOrHoldSet",
            "employeeWordingVersionRecorded",
            "rollbackReviewComplete")) {
      checklist.put(item, true);
    }
    checklist.put("counselReviewRecorded", false);
    checklist.put("reviewReference", "SYNTHETIC-REVIEW-1");
    MockHttpServletResponse saved =
        send(
            put(PLATFORM + "/policies/" + id + "/checklist"),
            admin,
            Map.of("expectedVersion", version, "checklist", checklist));
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    MockHttpServletResponse stillUnreviewed =
        call(
            PLATFORM + "/policies/" + id + "/transition",
            admin,
            Map.of("expectedVersion", body(saved).path("version").asInt(), "toState", "APPROVED"));
    assertThat(code(stillUnreviewed)).isEqualTo("PRIVACY_POLICY_CHECKLIST_INCOMPLETE");

    // A rule set that contradicts itself is refused: consent erased before the disclosure it
    // proves.
    List<Map<String, Object>> bad = rules();
    bad.set(0, rule("PARTNER_REFERRAL_DISCLOSURE", "CONSENTED_INTRODUCTION", "MINIMIZE", 90));
    bad.set(1, rule("PARTNER_REFERRAL_CONSENT", "CONSENT_EVIDENCE", "ERASE", 30));
    Map<String, Object> contradiction = new LinkedHashMap<>();
    contradiction.put("countryCode", country());
    contradiction.put("wordingVersion", "synthetic-1");
    contradiction.put("sourceReference", "SYNTHETIC test policy, not a legal period");
    contradiction.put("reviewDate", LocalDate.now(ZoneOffset.UTC).toString());
    contradiction.put("effectiveFrom", LocalDate.now(ZoneOffset.UTC).toString());
    contradiction.put("effectiveTo", null);
    contradiction.put("rules", bad);
    MockHttpServletResponse refused = call(PLATFORM + "/policies", admin, contradiction);
    assertThat(refused.getStatus()).isEqualTo(422);
    assertThat(code(refused)).isEqualTo("PRIVACY_POLICY_INVALID");
  }

  @Test
  void pendingAndHeldDataIsNeverPurgedEvenWhenAPreviewIsConfirmed() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Kill");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
    Employee employee =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    String reference = submitReferral(employee, product);
    closeLongAgo(employee, reference, 10);
    String admin = PartnerWorld.admin();

    // POLICY_PENDING: nothing is eligible, the confirmation is refused and nothing is deleted.
    JsonNode pending = purgePreview(admin, reference);
    MockHttpServletResponse refusedPending = purgeCommit(admin, pending);
    assertThat(refusedPending.getStatus()).isEqualTo(422);
    assertThat(code(refusedPending)).isEqualTo("PRIVACY_PURGE_NOTHING_ELIGIBLE");
    assertThat(rows("partner_referral_disclosure", reference)).isEqualTo(1L);
    assertThat(rows("partner_referral_consent", reference)).isEqualTo(1L);

    // A hold placed between preview and confirmation makes the preview stale: nothing is deleted.
    activePolicy(admin, country);
    applyPolicy(admin, country);
    JsonNode eligible = purgePreview(admin, reference);
    assertThat(eligible.path("eligibleClasses").asInt()).isEqualTo(3);
    Map<String, Object> hold = new LinkedHashMap<>();
    hold.put("reasonCode", "INVESTIGATION");
    hold.put("reviewDate", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
    hold.put("externalReference", null);
    hold.put("internalNote", null);
    assertThat(call(PLATFORM + "/referrals/" + reference + "/holds", admin, hold).getStatus())
        .isEqualTo(201);
    MockHttpServletResponse stale = purgeCommit(admin, eligible);
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(code(stale)).isEqualTo("PRIVACY_PREVIEW_STALE");
    assertThat(rows("partner_referral_disclosure", reference)).isEqualTo(1L);
    assertThat(rows("partner_referral_consent", reference)).isEqualTo(1L);
  }

  @Test
  void aPurgeNeverTouchesAReferralOutsideItsPreview() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Scope");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
    Employee amina =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    Employee jean =
        PartnerReferralWorld.employee(jdbc, tenant, "Jean", "Mukendi", "jean@example.test");
    String first = submitReferral(amina, product);
    String second = submitReferral(jean, product);
    closeLongAgo(amina, first, 10);
    closeLongAgo(jean, second, 10);
    String admin = PartnerWorld.admin();
    activePolicy(admin, country);
    applyPolicy(admin, country);

    JsonNode preview = purgePreview(admin, first);
    MockHttpServletResponse purged = purgeCommit(admin, preview);
    assertThat(purged.getStatus()).as(purged.getContentAsString()).isEqualTo(200);
    assertThat(rows("partner_referral_disclosure", first)).isZero();
    assertThat(rows("partner_referral_disclosure", second)).isEqualTo(1L);
    assertThat(rows("partner_referral_consent", second)).isEqualTo(1L);
    assertThat(purged.getContentAsString()).doesNotContain(second);
  }

  @Test
  void everyoneButAPlatformAdministratorIsRefusedBeforeAnyArgumentIsRead() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Refusal");
    Employee employee =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    String tenantAdmin = PartnerReferralWorld.admin(jdbc, tenant);
    UUID id = UUID.randomUUID();
    List<String> reads =
        List.of(
            PLATFORM + "/policies",
            PLATFORM + "/policies/PP-0000000000000000",
            PLATFORM + "/policies/PP-0000000000000000/history",
            PLATFORM + "/requests",
            PLATFORM + "/requests/PRQ-0000000000000000");
    List<String> writes =
        List.of(
            PLATFORM + "/policies",
            PLATFORM + "/referrals/PR-0000000000000000/holds",
            PLATFORM + "/policy-application/preview",
            PLATFORM + "/policy-application",
            PLATFORM + "/purge/preview",
            PLATFORM + "/purge");
    for (String path : reads) {
      assertThat(PartnerWorld.read(mvc, tenantAdmin, path).getStatus()).as(path).isEqualTo(403);
      assertThat(PartnerWorld.read(mvc, employee.bearer(), path).getStatus())
          .as(path)
          .isEqualTo(403);
      assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
          .as(path)
          .isEqualTo(401);
    }
    for (String path : writes) {
      assertThat(call(path, tenantAdmin, Map.of()).getStatus()).as(path).isEqualTo(403);
      assertThat(call(path, employee.bearer(), Map.of()).getStatus()).as(path).isEqualTo(403);
    }
    assertThat(id).isNotNull();
  }
}
