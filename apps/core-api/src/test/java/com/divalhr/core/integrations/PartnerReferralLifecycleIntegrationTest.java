package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-120F (Issue #224, ADR 0045): the consented submission, my referrals and withdrawal against
 * real PostgreSQL. A referral discloses exactly the previewed fields after an explicit consent,
 * stores values encrypted, records identifiers only, replays exactly, refuses a reused key with
 * different input, creates one referral per preview even under concurrency, is invisible to every
 * other principal and can be withdrawn only before delivery. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-referral-read=600",
      "divalhr.request-limits.subject.partner-referral-preview=600",
      "divalhr.request-limits.subject.partner-referral-commit=600",
      "divalhr.request-limits.subject.partner-referral-withdraw=600"
    })
class PartnerReferralLifecycleIntegrationTest {

  private static final String BASE = "/api/v1/me/partner-referrals";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private record Fixture(Employee employee, Product product) {}

  private Fixture fixture(String email) {
    UUID tenant =
        PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer " + UUID.randomUUID());
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    return new Fixture(
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", email), product);
  }

  private static Map<String, Object> inputs(Product product, String language, String phone) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("productId", product.reference());
    body.put("language", language);
    body.put("includePhone", phone != null);
    body.put("phone", phone);
    body.put("includeOrganizationName", false);
    return body;
  }

  private JsonNode preview(Employee who, Map<String, Object> inputs) throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                post(BASE + "/preview")
                    .header("Authorization", who.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PartnerWorld.JSON.writeValueAsString(inputs)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return body(response);
  }

  private static Map<String, Object> commitBody(
      Map<String, Object> inputs, JsonNode preview, boolean affirmed) {
    Map<String, Object> body = new LinkedHashMap<>(inputs);
    body.put("previewDigest", preview.path("previewDigest").asText());
    body.put("expiresAt", preview.path("expiresAt").asText());
    Map<String, Object> consent = new LinkedHashMap<>();
    consent.put("version", preview.path("consent").path("version").asText());
    consent.put("affirmed", affirmed);
    body.put("consent", consent);
    return body;
  }

  private MockHttpServletResponse commit(Employee who, Map<String, Object> body, String key)
      throws Exception {
    var request =
        post(BASE)
            .header("Authorization", who.bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(PartnerWorld.JSON.writeValueAsString(body));
    if (key != null) {
      request = request.header("Idempotency-Key", key);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse withdraw(
      Employee who, String reference, int version, boolean confirmed, String key) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    body.put("confirmed", confirmed);
    return mvc.perform(
            post(BASE + "/" + reference + "/withdraw")
                .header("Authorization", who.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PartnerWorld.JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private static String key() {
    return "synthetic-key-" + UUID.randomUUID();
  }

  private JsonNode submitOk(Fixture f, String phone) throws Exception {
    Map<String, Object> in = inputs(f.product(), "en", phone);
    MockHttpServletResponse response =
        commit(f.employee(), commitBody(in, preview(f.employee(), in), true), key());
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    return body(response);
  }

  @Test
  void aConsentedSubmissionDisclosesExactlyThePreviewAndStoresNothingInTheClear() throws Exception {
    Fixture f = fixture("amina@example.test");
    Map<String, Object> in = inputs(f.product(), "en", "+243810000000");
    JsonNode preview = preview(f.employee(), in);
    MockHttpServletResponse response = commit(f.employee(), commitBody(in, preview, true), key());
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode view = body(response);
    assertThat(view.path("referralId").asText()).matches("^PR-[0-9A-HJKMNP-TV-Z]{16}$");
    assertThat(view.path("state").asText()).isEqualTo("SUBMITTED");
    assertThat(view.path("version").asInt()).isEqualTo(1);
    assertThat(view.path("withdrawable").asBoolean()).isTrue();
    assertThat(view.path("notice").path("noDecision").asBoolean()).isTrue();
    assertThat(view.path("notice").path("noMoneyMovement").asBoolean()).isTrue();
    List<String> previewed = new ArrayList<>();
    preview.path("fields").forEach(x -> previewed.add(x.path("field").asText()));
    List<String> disclosed = new ArrayList<>();
    view.path("disclosedFields").forEach(x -> disclosed.add(x.asText()));
    assertThat(disclosed).containsExactlyElementsOf(previewed);
    assertThat(view.path("events")).hasSize(1);
    assertThat(response.getContentAsString())
        .doesNotContain("amina@example.test")
        .doesNotContain("Kabila")
        .doesNotContain("+243810000000");

    // stored encrypted; audit and outbox carry identifiers and field names only
    String reference = view.path("referralId").asText();
    byte[] cipher =
        jdbc.queryForObject(
            "SELECT d.values_ciphertext FROM integrations.partner_referral_disclosure d"
                + " JOIN integrations.partner_referral r ON r.id = d.referral_id AND r.tenant_id = d.tenant_id"
                + " WHERE r.reference = ?",
            byte[].class,
            reference);
    String raw = new String(cipher, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertThat(raw).doesNotContain("amina@example.test").doesNotContain("Kabila");
    String trail =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg(metadata::text, ' '), '') FROM platform.audit_event"
                + " WHERE action = 'partner-referral.submit' AND resource_id ="
                + " (SELECT id FROM integrations.partner_referral WHERE reference = ?)",
            String.class,
            reference);
    assertThat(trail).isNotBlank().contains("EMPLOYEE_EMAIL");
    assertThat(trail)
        .doesNotContain("amina@example.test")
        .doesNotContain("Kabila")
        .doesNotContain("+243810000000");
    String outbox =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg(envelope::text, ' '), '') FROM platform.outbox_event"
                + " WHERE event_type = 'integrations.partner-referral.submitted.v1'"
                + " AND envelope::text LIKE '%' || ? || '%'",
            String.class,
            jdbc.queryForObject(
                "SELECT id::text FROM integrations.partner_referral WHERE reference = ?",
                String.class,
                reference));
    assertThat(outbox).isNotBlank();
    assertThat(outbox).doesNotContain("amina@example.test").doesNotContain("Kabila");
  }

  @Test
  void anIdenticalRetryReplaysExactlyAndAReusedKeyWithDifferentInputIsRefused() throws Exception {
    Fixture f = fixture("amina@example.test");
    Map<String, Object> in = inputs(f.product(), "en", null);
    Map<String, Object> body = commitBody(in, preview(f.employee(), in), true);
    String key = key();
    MockHttpServletResponse first = commit(f.employee(), body, key);
    MockHttpServletResponse replay = commit(f.employee(), body, key);
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.partner_referral WHERE employee_id = ?",
                Long.class,
                f.employee().employee()))
        .isEqualTo(1L);

    Map<String, Object> other = inputs(f.product(), "fr", null);
    MockHttpServletResponse reused =
        commit(f.employee(), commitBody(other, preview(f.employee(), other), true), key);
    assertThat(reused.getStatus()).isEqualTo(409);
    assertThat(code(reused)).isEqualTo("PARTNER_REFERRAL_IDEMPOTENCY_REUSED");

    MockHttpServletResponse missing = commit(f.employee(), body, null);
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(code(missing)).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void withoutAnExplicitConsentNothingIsCreated() throws Exception {
    Fixture f = fixture("amina@example.test");
    Map<String, Object> in = inputs(f.product(), "en", null);
    JsonNode preview = preview(f.employee(), in);
    MockHttpServletResponse unchecked = commit(f.employee(), commitBody(in, preview, false), key());
    assertThat(unchecked.getStatus()).isEqualTo(422);
    assertThat(code(unchecked)).isEqualTo("PARTNER_REFERRAL_CONSENT_REQUIRED");
    Map<String, Object> absent = commitBody(in, preview, true);
    absent.remove("consent");
    assertThat(code(commit(f.employee(), absent, key()))).isEqualTo("VALIDATION_FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.partner_referral WHERE employee_id = ?",
                Long.class,
                f.employee().employee()))
        .isZero();
  }

  @Test
  void aChangedExpiredOrReusedPreviewIsRefused() throws Exception {
    Fixture f = fixture("amina@example.test");
    Map<String, Object> in = inputs(f.product(), "en", null);
    JsonNode preview = preview(f.employee(), in);

    // widening the disclosure after the preview invalidates its digest
    Map<String, Object> widened = commitBody(in, preview, true);
    widened.put("includeOrganizationName", true);
    MockHttpServletResponse stale = commit(f.employee(), widened, key());
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(code(stale)).isEqualTo("PARTNER_REFERRAL_PREVIEW_STALE");

    Map<String, Object> forged = commitBody(in, preview, true);
    forged.put("previewDigest", "0".repeat(64));
    assertThat(code(commit(f.employee(), forged, key())))
        .isEqualTo("PARTNER_REFERRAL_PREVIEW_STALE");

    // an expiry in the past cannot be made valid by editing it (the digest binds it)
    Map<String, Object> expired = commitBody(in, preview, true);
    expired.put("expiresAt", "2020-01-01T00:00:00Z");
    MockHttpServletResponse old = commit(f.employee(), expired, key());
    assertThat(old.getStatus()).isEqualTo(409);
    assertThat(code(old))
        .isIn("PARTNER_REFERRAL_PREVIEW_EXPIRED", "PARTNER_REFERRAL_PREVIEW_STALE");

    Map<String, Object> good = commitBody(in, preview, true);
    assertThat(commit(f.employee(), good, key()).getStatus()).isEqualTo(201);
    MockHttpServletResponse again = commit(f.employee(), good, key());
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(code(again)).isEqualTo("PARTNER_REFERRAL_PREVIEW_ALREADY_USED");
  }

  @Test
  void concurrentCommitsOfOnePreviewCreateOneReferral() throws Exception {
    Fixture f = fixture("amina@example.test");
    Map<String, Object> in = inputs(f.product(), "en", null);
    Map<String, Object> body = commitBody(in, preview(f.employee(), in), true);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        String key = key();
        Callable<Integer> call = () -> commit(f.employee(), body, key).getStatus();
        results.add(pool.submit(call));
      }
      int created = 0;
      for (Future<Integer> result : results) {
        int status = result.get();
        assertThat(status).isIn(201, 409);
        created += status == 201 ? 1 : 0;
      }
      assertThat(created).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.partner_referral WHERE employee_id = ?",
                Long.class,
                f.employee().employee()))
        .isEqualTo(1L);
  }

  @Test
  void onlyTheOwningEmployeeCanReadAReferralAndEveryoneElseGetsTheSameNotFound() throws Exception {
    Fixture f = fixture("amina@example.test");
    String reference = submitOk(f, null).path("referralId").asText();
    Employee colleague =
        PartnerReferralWorld.employee(
            jdbc, f.employee().tenant(), "Jean", "Mbala", "jean@example.test");
    Fixture elsewhere = fixture("zoe@example.test");

    MockHttpServletResponse own =
        PartnerWorld.read(mvc, f.employee().bearer(), BASE + "/" + reference);
    assertThat(own.getStatus()).isEqualTo(200);
    MockHttpServletResponse peer =
        PartnerWorld.read(mvc, colleague.bearer(), BASE + "/" + reference);
    MockHttpServletResponse foreign =
        PartnerWorld.read(mvc, elsewhere.employee().bearer(), BASE + "/" + reference);
    MockHttpServletResponse unknown =
        PartnerWorld.read(mvc, f.employee().bearer(), BASE + "/" + PartnerReferralWorld.ref("PR-"));
    MockHttpServletResponse malformed =
        PartnerWorld.read(mvc, f.employee().bearer(), BASE + "/not-a-reference");
    for (MockHttpServletResponse response : List.of(peer, foreign, unknown, malformed)) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(code(response)).isEqualTo("PARTNER_REFERRAL_NOT_FOUND");
    }
    assertThat(withdraw(colleague, reference, 1, true, key()).getStatus()).isEqualTo(404);

    String admin = PartnerReferralWorld.admin(jdbc, f.employee().tenant());
    assertThat(PartnerWorld.read(mvc, admin, BASE).getStatus()).isEqualTo(403);
    assertThat(PartnerWorld.read(mvc, admin, BASE + "/" + reference).getStatus()).isEqualTo(403);
    assertThat(withdraw(f.employee(), reference, 1, true, key()).getStatus()).isEqualTo(200);
    assertThat(PartnerWorld.read(mvc, colleague.bearer(), BASE).getContentAsString())
        .doesNotContain(reference);
  }

  @Test
  void myReferralsAreListedNewestFirstWithAStatePagedCursor() throws Exception {
    Fixture f = fixture("amina@example.test");
    List<String> created = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Map<String, Object> in = inputs(f.product(), i % 2 == 0 ? "en" : "fr", null);
      in.put("includeOrganizationName", i != 0);
      MockHttpServletResponse response =
          commit(f.employee(), commitBody(in, preview(f.employee(), in), true), key());
      assertThat(response.getStatus()).isEqualTo(201);
      created.add(body(response).path("referralId").asText());
    }
    MockHttpServletResponse page = PartnerWorld.read(mvc, f.employee().bearer(), BASE + "?limit=2");
    assertThat(page.getStatus()).isEqualTo(200);
    JsonNode first = body(page);
    assertThat(first.path("items")).hasSize(2);
    assertThat(first.path("items").get(0).path("referralId").asText()).isEqualTo(created.get(2));
    String cursor = first.path("nextCursor").asText();
    assertThat(cursor).isNotBlank();
    JsonNode second =
        body(PartnerWorld.read(mvc, f.employee().bearer(), BASE + "?limit=2&cursor=" + cursor));
    assertThat(second.path("items")).hasSize(1);
    assertThat(second.path("items").get(0).path("referralId").asText()).isEqualTo(created.get(0));
    assertThat(second.path("nextCursor").isNull()).isTrue();
    // a cursor is bound to its filters
    MockHttpServletResponse rebound =
        PartnerWorld.read(
            mvc, f.employee().bearer(), BASE + "?limit=2&state=WITHDRAWN&cursor=" + cursor);
    assertThat(rebound.getStatus()).isEqualTo(400);
    assertThat(
            body(PartnerWorld.read(mvc, f.employee().bearer(), BASE + "?state=WITHDRAWN&limit=5"))
                .path("items"))
        .isEmpty();
    assertThat(PartnerWorld.read(mvc, f.employee().bearer(), BASE + "?state=BOGUS").getStatus())
        .isEqualTo(400);
  }

  @Test
  void aReferralCanBeWithdrawnOnceBeforeDeliveryAndNeverAfterwards() throws Exception {
    Fixture f = fixture("amina@example.test");
    String reference = submitOk(f, null).path("referralId").asText();

    MockHttpServletResponse unconfirmed = withdraw(f.employee(), reference, 1, false, key());
    assertThat(unconfirmed.getStatus()).isEqualTo(400);
    MockHttpServletResponse stale = withdraw(f.employee(), reference, 2, true, key());
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(code(stale)).isEqualTo("PARTNER_REFERRAL_VERSION_STALE");

    String key = key();
    MockHttpServletResponse done = withdraw(f.employee(), reference, 1, true, key);
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    JsonNode view = body(done);
    assertThat(view.path("state").asText()).isEqualTo("WITHDRAWN");
    assertThat(view.path("version").asInt()).isEqualTo(2);
    assertThat(view.path("withdrawable").asBoolean()).isFalse();
    assertThat(view.path("events")).hasSize(2);
    MockHttpServletResponse replay = withdraw(f.employee(), reference, 1, true, key);
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(done.getContentAsString());

    MockHttpServletResponse second = withdraw(f.employee(), reference, 2, true, key());
    assertThat(second.getStatus()).isEqualTo(409);
    assertThat(code(second)).isEqualTo("PARTNER_REFERRAL_NOT_WITHDRAWABLE");

    // a delivered or uncertain referral is never withdrawable (state set with triggers off)
    for (String state : List.of("DELIVERED", "DELIVERY_UNKNOWN")) {
      Fixture g = fixture("amina@example.test");
      String delivered = submitOk(g, null).path("referralId").asText();
      PartnerReferralWorld.withoutTriggers(
          jdbc,
          "UPDATE integrations.partner_referral SET state = '"
              + state
              + "', row_version = 2, next_attempt_at = NULL WHERE reference = '"
              + delivered
              + "';");
      MockHttpServletResponse refused = withdraw(g.employee(), delivered, 2, true, key());
      assertThat(refused.getStatus()).as(state).isEqualTo(409);
      assertThat(code(refused)).isEqualTo("PARTNER_REFERRAL_NOT_WITHDRAWABLE");
      JsonNode shown = body(PartnerWorld.read(mvc, g.employee().bearer(), BASE + "/" + delivered));
      assertThat(shown.path("withdrawable").asBoolean()).isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'partner-referral.withdraw'",
                Long.class))
        .isGreaterThanOrEqualTo(1L);
  }
}
