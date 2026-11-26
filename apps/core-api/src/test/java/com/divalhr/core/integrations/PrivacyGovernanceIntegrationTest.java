package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

  private static final Random COUNTRY_RANDOM =
      new Random(UUID.randomUUID().getMostSignificantBits());

  private static String country() {
    Random random = COUNTRY_RANDOM;
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
    return activePolicy(admin, country, rules());
  }

  private String activePolicy(String admin, String country, List<Map<String, Object>> ruleSet)
      throws Exception {
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("countryCode", country);
    draft.put("wordingVersion", "synthetic-1");
    draft.put("sourceReference", "SYNTHETIC test policy, not a legal period");
    draft.put("reviewDate", today);
    draft.put("effectiveFrom", today);
    draft.put("effectiveTo", null);
    draft.put("rules", ruleSet);
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
    return purgePreviewFor(admin, "MINIMIZE", references);
  }

  private JsonNode purgePreviewFor(String admin, String action, String... references)
      throws Exception {
    return body(
        call(
            PLATFORM + "/purge/preview",
            admin,
            Map.of("referralIds", List.of(references), "action", action)));
  }

  /**
   * Every class governed by the action: disclosure, consent and diagnostics first, lifecycle last.
   */
  private static List<Map<String, Object>> everyClass(String action) {
    List<Map<String, Object>> rules = new ArrayList<>();
    rules.add(rule("PARTNER_REFERRAL_DISCLOSURE", "CONSENTED_INTRODUCTION", action, 1));
    rules.add(rule("PARTNER_REFERRAL_CONSENT", "CONSENT_EVIDENCE", action, 5));
    rules.add(rule("PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS", "DELIVERY_TRUTH", action, 1));
    rules.add(rule("PARTNER_REFERRAL_LIFECYCLE_EVIDENCE", "LIFECYCLE_EVIDENCE", action, 5));
    return rules;
  }

  private long count(String sql, Object... args) {
    Long rows = jdbc.queryForObject(sql, Long.class, args);
    return rows == null ? -1 : rows;
  }

  private long events(String reference) {
    return count(
        "SELECT count(*) FROM integrations.partner_referral_event e JOIN"
            + " integrations.partner_referral r ON r.tenant_id = e.tenant_id AND r.id ="
            + " e.referral_id WHERE r.reference = ?",
        reference);
  }

  private boolean tombstone(String reference) {
    return count(
            "SELECT count(*) FROM integrations.partner_referral WHERE reference = ? AND employee_id"
                + " IS NULL AND partner_id IS NULL AND product_id IS NULL AND version_id IS NULL"
                + " AND partner_code IS NULL AND language IS NULL AND preview_sha256 IS NULL AND"
                + " submitted_by IS NULL",
            reference)
        == 1;
  }

  /** Every stored byte of a referral, as text: the header, its evidence and its stamp. */
  private String snapshot(String reference) {
    StringBuilder text = new StringBuilder();
    for (String table :
        List.of(
            "partner_referral_disclosure",
            "partner_referral_consent",
            "partner_referral_event",
            "partner_referral_attempt",
            "partner_referral_ack",
            "privacy_referral_stamp")) {
      text.append(table)
          .append('=')
          .append(
              jdbc.queryForObject(
                  "SELECT coalesce(string_agg(row_to_json(x)::text, '|' ORDER BY"
                      + " row_to_json(x)::text), '') FROM integrations."
                      + table
                      + " x JOIN integrations.partner_referral r ON r.tenant_id = x.tenant_id AND"
                      + " r.id = x.referral_id WHERE r.reference = ?",
                  String.class,
                  reference))
          .append(';');
    }
    text.append(
        jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM integrations.partner_referral r WHERE r.reference ="
                + " ?",
            String.class,
            reference));
    return text.toString();
  }

  private int status(String path, String bearer) throws Exception {
    return mvc.perform(get(path).header("Authorization", bearer))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String text(String path, String bearer) throws Exception {
    return mvc.perform(get(path).header("Authorization", bearer))
        .andReturn()
        .getResponse()
        .getContentAsString();
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

  // R227-1: lifecycle MINIMIZE and ERASE genuinely remove identifying and product-choice detail.

  private static final String PLATFORM_REFERRALS = "/api/v1/platform/partner-referrals";

  @Test
  void lifecycleMinimizeLeavesAnOpaqueTombstoneAndNoReadCanRecoverTheDetail() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Tombstone");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
    Employee amina =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    Employee jean =
        PartnerReferralWorld.employee(jdbc, tenant, "Jean", "Mukendi", "jean@example.test");
    String first = submitReferral(amina, product);
    String other = submitReferral(jean, product);
    closeLongAgo(amina, first, 10);
    closeLongAgo(jean, other, 10);
    String admin = PartnerWorld.admin();
    activePolicy(admin, country, everyClass("MINIMIZE"));
    applyPolicy(admin, country);

    Map<String, Object> links =
        jdbc.queryForMap(
            "SELECT employee_id::text AS employee, partner_id::text AS partner, product_id::text"
                + " AS product, version_id::text AS version, preview_sha256 AS preview FROM"
                + " integrations.partner_referral WHERE reference = ?",
            first);
    long eventsBefore = events(first);
    assertThat(eventsBefore).isGreaterThan(1L);
    String untouched = snapshot(other);
    assertThat(status(ME_REFERRALS + "/" + first, amina.bearer())).isEqualTo(200);

    // Nothing but an authorized, executing purge item may scrub the header or delete the history.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE integrations.partner_referral SET employee_id = NULL, partner_id ="
                        + " NULL, product_id = NULL, version_id = NULL, partner_code = NULL,"
                        + " language = NULL, preview_sha256 = NULL, submitted_by = NULL WHERE"
                        + " reference = ?",
                    first))
        .hasMessageContaining("authorized lifecycle purge item");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM integrations.partner_referral_event WHERE referral_id ="
                        + " (SELECT id FROM integrations.partner_referral WHERE reference = ?)",
                    first))
        .hasMessageContaining("authorized purge item");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE integrations.partner_referral SET employee_id = NULL WHERE reference"
                        + " = ?",
                    first))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(events(first)).isEqualTo(eventsBefore);

    JsonNode preview = purgePreviewFor(admin, "MINIMIZE", first);
    assertThat(preview.path("eligibleClasses").asInt()).isEqualTo(4);
    MockHttpServletResponse purged = purgeCommit(admin, preview);
    assertThat(purged.getStatus()).as(purged.getContentAsString()).isEqualTo(200);
    assertThat(purged.getContentAsString())
        .doesNotContain("amina@example.test")
        .doesNotContain("Amina")
        .doesNotContain("Kabila")
        .doesNotContain(other);

    // The detail is gone and the row is an opaque tombstone that links to no employee, partner or
    // product.
    assertThat(events(first)).isZero();
    assertThat(tombstone(first)).isTrue();
    assertThat(rows("partner_referral_disclosure", first)).isZero();
    assertThat(rows("partner_referral_consent", first)).isZero();
    String row =
        jdbc.queryForObject(
            "SELECT row_to_json(r)::text FROM integrations.partner_referral r WHERE reference = ?",
            String.class,
            first);
    String residue =
        jdbc.queryForObject(
            "SELECT x.details::text FROM integrations.privacy_referral_residue x JOIN"
                + " integrations.partner_referral r ON r.tenant_id = x.tenant_id AND r.id ="
                + " x.referral_id WHERE r.reference = ? AND x.data_class ="
                + " 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE'",
            String.class,
            first);
    for (Object link : links.values()) {
      assertThat(row).doesNotContain(String.valueOf(link));
      assertThat(residue).doesNotContain(String.valueOf(link));
    }
    // Only the counts and the two lifecycle timestamps are kept as proof.
    assertThat(
            jdbc.queryForList(
                "SELECT k FROM integrations.privacy_referral_residue x, jsonb_object_keys(x.details)"
                    + " k WHERE x.data_class = 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE' AND"
                    + " x.referral_id = (SELECT id FROM integrations.partner_referral WHERE"
                    + " reference = ?) ORDER BY k",
                String.class,
                first))
        .containsExactly("closed_at", "event_count", "opened_at");
    assertThat(residue).contains("\"event_count\": " + eventsBefore);
    assertThat(
            count(
                "SELECT count(*) FROM integrations.privacy_purge_item i JOIN"
                    + " integrations.partner_referral r ON r.tenant_id = i.tenant_id AND r.id ="
                    + " i.referral_id WHERE r.reference = ? AND i.action = 'MINIMIZE'",
                first))
        .isEqualTo(4L);
    // A tombstone never changes again.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE integrations.partner_referral SET updated_at = now() WHERE reference"
                        + " = ?",
                    first))
        .hasMessageContaining("tombstone never changes");

    // No employee and no platform read recovers the referral, its partner, its product or its
    // history.
    assertThat(status(ME_REFERRALS + "/" + first, amina.bearer())).isEqualTo(404);
    assertThat(text(ME_REFERRALS + "?limit=50", amina.bearer())).doesNotContain(first);
    assertThat(status(PLATFORM_REFERRALS + "/" + first, admin)).isEqualTo(404);
    assertThat(status(PLATFORM_REFERRALS + "/" + first + "/history", admin)).isEqualTo(404);
    assertThat(text(PLATFORM_REFERRALS + "?limit=50", admin)).doesNotContain(first);
    assertThat(status(ME_REFERRALS + "/" + first, jean.bearer())).isEqualTo(404);

    // Another referral outside the preview stays byte-identical and readable.
    assertThat(snapshot(other)).isEqualTo(untouched);
    assertThat(status(ME_REFERRALS + "/" + other, jean.bearer())).isEqualTo(200);
  }

  @Test
  void lifecycleEraseRemovesTheGovernedDataAndLeavesNoResidue() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Erase");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
    Employee amina =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    Employee jean =
        PartnerReferralWorld.employee(jdbc, tenant, "Jean", "Mukendi", "jean@example.test");
    String first = submitReferral(amina, product);
    String other = submitReferral(jean, product);
    closeLongAgo(amina, first, 10);
    closeLongAgo(jean, other, 10);
    String admin = PartnerWorld.admin();
    // An ACTIVE policy may now name ERASE for the lifecycle evidence, ordered after every other
    // class.
    activePolicy(admin, country, everyClass("ERASE"));
    applyPolicy(admin, country);
    String untouched = snapshot(other);

    // A MINIMIZE preview never applies an ERASE rule.
    assertThat(purgePreviewFor(admin, "MINIMIZE", first).path("eligibleClasses").asInt()).isZero();
    JsonNode preview = purgePreviewFor(admin, "ERASE", first);
    assertThat(preview.path("eligibleClasses").asInt()).isEqualTo(4);
    MockHttpServletResponse purged = purgeCommit(admin, preview);
    assertThat(purged.getStatus()).as(purged.getContentAsString()).isEqualTo(200);

    assertThat(events(first)).isZero();
    assertThat(tombstone(first)).isTrue();
    assertThat(rows("partner_referral_disclosure", first)).isZero();
    assertThat(rows("partner_referral_consent", first)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM integrations.privacy_referral_residue x JOIN"
                    + " integrations.partner_referral r ON r.tenant_id = x.tenant_id AND r.id ="
                    + " x.referral_id WHERE r.reference = ?",
                first))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM integrations.privacy_purge_item i JOIN"
                    + " integrations.partner_referral r ON r.tenant_id = i.tenant_id AND r.id ="
                    + " i.referral_id WHERE r.reference = ? AND i.action = 'ERASE'",
                first))
        .isEqualTo(4L);
    assertThat(status(ME_REFERRALS + "/" + first, amina.bearer())).isEqualTo(404);
    assertThat(text(ME_REFERRALS + "?limit=50", amina.bearer())).doesNotContain(first);
    assertThat(status(PLATFORM_REFERRALS + "/" + first, admin)).isEqualTo(404);
    assertThat(status(PLATFORM_REFERRALS + "/" + first + "/history", admin)).isEqualTo(404);
    assertThat(snapshot(other)).isEqualTo(untouched);
  }

  @Test
  void anActiveHoldBlocksBothLifecycleActionsAndAStalePreviewChangesNothing() throws Exception {
    for (String action : List.of("MINIMIZE", "ERASE")) {
      String country = country();
      UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Hold");
      Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
      Employee amina =
          PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
      String reference = submitReferral(amina, product);
      closeLongAgo(amina, reference, 10);
      String admin = PartnerWorld.admin();
      activePolicy(admin, country, everyClass(action));
      applyPolicy(admin, country);

      Map<String, Object> hold = new LinkedHashMap<>();
      hold.put("reasonCode", "DISPUTE");
      hold.put("reviewDate", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
      hold.put("externalReference", null);
      hold.put("internalNote", null);

      // A hold placed after the preview makes the confirmation stale: every class, lifecycle
      // included, stays.
      JsonNode eligible = purgePreviewFor(admin, action, reference);
      assertThat(eligible.path("eligibleClasses").asInt()).isEqualTo(4);
      MockHttpServletResponse placed =
          call(PLATFORM + "/referrals/" + reference + "/holds", admin, hold);
      assertThat(placed.getStatus()).as(placed.getContentAsString()).isEqualTo(201);
      String before = snapshot(reference);
      MockHttpServletResponse stale = purgeCommit(admin, eligible);
      assertThat(stale.getStatus()).isEqualTo(409);
      assertThat(code(stale)).isEqualTo("PRIVACY_PREVIEW_STALE");
      assertThat(snapshot(reference)).isEqualTo(before);
      assertThat(tombstone(reference)).isFalse();

      // A fresh preview shows nothing eligible while the hold is active, and nothing can be
      // confirmed.
      JsonNode held = purgePreviewFor(admin, action, reference);
      assertThat(held.path("eligibleClasses").asInt()).isZero();
      assertThat(held.toString()).contains("HOLD_ACTIVE");
      MockHttpServletResponse nothing = purgeCommit(admin, held);
      assertThat(nothing.getStatus()).isEqualTo(422);
      assertThat(code(nothing)).isEqualTo("PRIVACY_PURGE_NOTHING_ELIGIBLE");
      assertThat(snapshot(reference)).isEqualTo(before);
      assertThat(events(reference)).isGreaterThan(1L);
    }
  }

  @Test
  void aReplayedPurgeConfirmationMutatesNothingASecondTime() throws Exception {
    String country = country();
    UUID tenant = PartnerReferralWorld.organization(jdbc, country, "Synthetic Employer Replay");
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of(country));
    Employee amina =
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test");
    String reference = submitReferral(amina, product);
    closeLongAgo(amina, reference, 10);
    String admin = PartnerWorld.admin();
    activePolicy(admin, country, everyClass("MINIMIZE"));
    applyPolicy(admin, country);

    JsonNode preview = purgePreviewFor(admin, "MINIMIZE", reference);
    Map<String, Object> commit =
        Map.of(
            "previewId", preview.path("previewId").asText(),
            "previewDigest", preview.path("previewDigest").asText());
    String key = key();
    MockHttpServletResponse first =
        mvc.perform(
                post(PLATFORM + "/purge")
                    .header("Authorization", admin)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PartnerWorld.JSON.writeValueAsString(commit)))
            .andReturn()
            .getResponse();
    assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
    String afterFirst = snapshot(reference);
    long purges = count("SELECT count(*) FROM integrations.privacy_purge");
    long items = count("SELECT count(*) FROM integrations.privacy_purge_item");
    long residues = count("SELECT count(*) FROM integrations.privacy_referral_residue");

    MockHttpServletResponse replay =
        mvc.perform(
                post(PLATFORM + "/purge")
                    .header("Authorization", admin)
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PartnerWorld.JSON.writeValueAsString(commit)))
            .andReturn()
            .getResponse();
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(body(replay).path("reference").asText())
        .isEqualTo(body(first).path("reference").asText());
    // A new key for the same used preview is refused; either way nothing is mutated again.
    MockHttpServletResponse reused = purgeCommit(admin, preview);
    assertThat(reused.getStatus()).isEqualTo(409);
    assertThat(code(reused)).isEqualTo("PRIVACY_PREVIEW_ALREADY_USED");
    assertThat(snapshot(reference)).isEqualTo(afterFirst);
    assertThat(count("SELECT count(*) FROM integrations.privacy_purge")).isEqualTo(purges);
    assertThat(count("SELECT count(*) FROM integrations.privacy_purge_item")).isEqualTo(items);
    assertThat(count("SELECT count(*) FROM integrations.privacy_referral_residue"))
        .isEqualTo(residues);
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
