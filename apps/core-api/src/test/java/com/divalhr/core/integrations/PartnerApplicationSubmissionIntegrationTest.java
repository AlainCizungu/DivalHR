package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.JSON;
import static com.divalhr.core.integrations.PartnerWorld.PLATFORM;
import static com.divalhr.core.integrations.PartnerWorld.PUBLIC;
import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
 * MVP-120D (Issue #215, ADR 0043): the anonymous submission. A safe opaque receipt, encrypted
 * confidential data, idempotent replay, an identical answer for duplicates and the honeypot, strict
 * validation that never echoes input, and the exactness of the public allowlist. Every value is
 * SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-application-read=600",
      "divalhr.request-limits.subject.partner-application-command=600",
      "divalhr.partner-application.email-attempts-per-hour=1000"
    })
class PartnerApplicationSubmissionIntegrationTest {

  private static final String REFERENCE = "^PA-[0-9A-HJKMNP-TV-Z]{12}$";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private int count(String where, Object... args) {
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM integrations.partner_application WHERE " + where,
            Integer.class,
            args);
    return total == null ? 0 : total;
  }

  @Test
  void aSubmissionReturnsOnlyTheSafeReceiptAndStoresEncryptedContact() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    String name = PartnerWorld.uniqueName();
    Map<String, Object> application = PartnerWorld.bank(name, email);
    MockHttpServletResponse response = PartnerWorld.submit(mvc, application);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    JsonNode receipt = body(response);
    assertThat(new TreeSet<String>(fieldNames(receipt)))
        .containsExactly("nextStep", "reference", "status", "submittedAt");
    assertThat(receipt.path("reference").asText()).matches(REFERENCE);
    assertThat(receipt.path("status").asText()).isEqualTo("SUBMITTED");
    assertThat(response.getContentAsString()).doesNotContain(email).doesNotContain(name);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, row_version, email_digest, organization_fingerprint,"
                + " encode(confidential_ciphertext, 'escape') AS cipher, legal_name"
                + " FROM integrations.partner_application WHERE reference = ?",
            receipt.path("reference").asText());
    assertThat(row.get("status")).isEqualTo("SUBMITTED");
    assertThat(row.get("row_version")).isEqualTo(0);
    assertThat(String.valueOf(row.get("email_digest"))).matches("^[0-9a-f]{64}$").doesNotContain(email);
    assertThat(String.valueOf(row.get("cipher")))
        .doesNotContain(email)
        .doesNotContain(String.valueOf(application.get("registrationReference")))
        .doesNotContain(String.valueOf(application.get("licenceReference")))
        .doesNotContain(String.valueOf(application.get("contactName")));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.partner_application_check c"
                    + " JOIN integrations.partner_application a ON a.id = c.application_id"
                    + " WHERE a.reference = ? AND c.result = 'PENDING'",
                Integer.class,
                receipt.path("reference").asText()))
        .isEqualTo(7);
  }

  @Test
  void theAuditEntryAndHistoryCarryNoConfidentialData() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    Map<String, Object> application = PartnerWorld.bank(PartnerWorld.uniqueName(), email);
    String reference = body(PartnerWorld.submit(mvc, application)).path("reference").asText();

    String audit =
        jdbc.queryForObject(
            "SELECT string_agg(metadata::text || action || actor_subject || correlation_id, ' ')"
                + " FROM platform.audit_event WHERE action LIKE 'partner.%'",
            String.class);
    assertThat(audit).contains("partner.application-submit");
    for (Object sensitive :
        List.of(
            email,
            application.get("legalName"),
            application.get("registrationReference"),
            application.get("licenceReference"),
            application.get("contactName"),
            application.get("serviceSummary"),
            application.get("businessPhone"),
            application.get("privacyContactEmail"),
            "127.0.0.1")) {
      assertThat(audit).doesNotContain(String.valueOf(sensitive));
    }
    Integer events =
        jdbc.queryForObject(
            "SELECT count(*) FROM integrations.partner_application_event e"
                + " JOIN integrations.partner_application a ON a.id = e.application_id"
                + " WHERE a.reference = ? AND e.event_type = 'SUBMITTED'",
            Integer.class,
            reference);
    assertThat(events).isEqualTo(1);
    // MVP-120D sends no communication: nothing about partner applications reaches the outbox.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.outbox_event WHERE event_type ILIKE '%partner%application%'",
                Integer.class))
        .isZero();
  }

  @Test
  void anExactReplayReturnsTheOriginalReceiptAndChangedInputIsRefused() throws Exception {
    String key = "synthetic-key-" + UUID.randomUUID();
    Map<String, Object> application =
        PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    MockHttpServletResponse first = PartnerWorld.submit(mvc, application, key);
    MockHttpServletResponse replay = PartnerWorld.submit(mvc, application, key);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(first.getHeader("Idempotent-Replayed")).isNull();
    assertThat(body(replay)).isEqualTo(body(first));
    assertThat(count("legal_name = ?", application.get("legalName"))).isEqualTo(1);

    application.put("serviceSummary", "A different synthetic summary for the same key reuse test.");
    MockHttpServletResponse changed = PartnerWorld.submit(mvc, application, key);
    assertThat(changed.getStatus()).isEqualTo(409);
    assertThat(code(changed)).isEqualTo("PARTNER_APPLICATION_IDEMPOTENCY_REUSED");
    assertThat(count("legal_name = ?", application.get("legalName"))).isEqualTo(1);
  }

  @Test
  void aDuplicateAnswersExactlyLikeANewApplicationAndTheAdministratorSeesTheCount()
      throws Exception {
    String email = PartnerWorld.uniqueEmail();
    Map<String, Object> application = PartnerWorld.bank(PartnerWorld.uniqueName(), email);
    MockHttpServletResponse first = PartnerWorld.submit(mvc, application);
    MockHttpServletResponse second = PartnerWorld.submit(mvc, application);

    assertThat(second.getStatus()).isEqualTo(first.getStatus()).isEqualTo(201);
    assertThat(new TreeSet<String>(fieldNames(body(second))))
        .isEqualTo(new TreeSet<String>(fieldNames(body(first))));
    assertThat(body(second).path("status").asText()).isEqualTo(body(first).path("status").asText());
    assertThat(body(second).path("nextStep").asText())
        .isEqualTo(body(first).path("nextStep").asText());
    assertThat(second.getHeader("Idempotent-Replayed")).isNull();
    assertThat(body(second).path("reference").asText())
        .isNotEqualTo(body(first).path("reference").asText());
    assertThat(second.getContentAsString()).doesNotContain("DUPLICATE").doesNotContain("duplicate");
    assertThat(count("legal_name = ?", application.get("legalName"))).isEqualTo(2);

    String admin = PartnerWorld.admin();
    JsonNode detail =
        body(
            PartnerWorld.read(
                mvc, admin, PLATFORM + "/" + body(second).path("reference").asText()));
    assertThat(detail.path("possibleDuplicates").asInt()).isEqualTo(1);
  }

  @Test
  void theHoneypotAnswersLikeASuccessAndCreatesNothing() throws Exception {
    Map<String, Object> application =
        PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    application.put("faxNumber", "bot-filled-this");
    int auditBefore = auditCount();
    MockHttpServletResponse response = PartnerWorld.submit(mvc, application);

    assertThat(response.getStatus()).isEqualTo(201);
    JsonNode receipt = body(response);
    assertThat(new TreeSet<String>(fieldNames(receipt)))
        .containsExactly("nextStep", "reference", "status", "submittedAt");
    assertThat(receipt.path("reference").asText()).matches(REFERENCE);
    assertThat(receipt.path("status").asText()).isEqualTo("SUBMITTED");
    assertThat(count("legal_name = ?", application.get("legalName"))).isZero();
    assertThat(count("reference = ?", receipt.path("reference").asText())).isZero();
    assertThat(auditCount()).isEqualTo(auditBefore);
  }

  @Test
  void validationIsStrictAndNeverEchoesInput() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    String name = PartnerWorld.uniqueName();
    List<Map<String, Object>> bad = new ArrayList<>();
    Map<String, Object> unknown = PartnerWorld.bank(name, email);
    unknown.put("password", "synthetic-secret-value");
    bad.add(unknown);
    Map<String, Object> category = PartnerWorld.bank(name, email);
    category.put("primaryCategory", "CRYPTO_EXCHANGE");
    bad.add(category);
    Map<String, Object> regulated = PartnerWorld.bank(name, email);
    regulated.remove("regulatorName");
    bad.add(regulated);
    Map<String, Object> website = PartnerWorld.bank(name, email);
    website.put("website", "http://insecure.example.test/");
    bad.add(website);
    Map<String, Object> capability = PartnerWorld.bank(name, email);
    capability.put("requestedCapabilities", List.of());
    bad.add(capability);
    Map<String, Object> repeated = PartnerWorld.bank(name, email);
    repeated.put("additionalCategories", List.of("BANK"));
    bad.add(repeated);
    Map<String, Object> locale = PartnerWorld.bank(name, email);
    locale.put("locale", "de");
    bad.add(locale);
    Map<String, Object> mail = PartnerWorld.bank(name, email);
    mail.put("businessEmail", "not-an-email");
    bad.add(mail);
    for (Map<String, Object> application : bad) {
      MockHttpServletResponse response = PartnerWorld.submit(mvc, application);
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(code(response)).isEqualTo("PARTNER_APPLICATION_INVALID");
      assertThat(response.getContentAsString())
          .doesNotContain(email)
          .doesNotContain(name)
          .doesNotContain("synthetic-secret-value")
          .doesNotContain("CRYPTO_EXCHANGE")
          .doesNotContain("insecure.example.test");
    }
    assertThat(count("legal_name = ?", name)).isZero();
  }

  @Test
  void consentMustBeAcknowledged() throws Exception {
    Map<String, Object> application =
        PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    application.put("acknowledgment", false);
    MockHttpServletResponse response = PartnerWorld.submit(mvc, application);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(code(response)).isEqualTo("PARTNER_APPLICATION_CONSENT_REQUIRED");
    assertThat(count("legal_name = ?", application.get("legalName"))).isZero();
  }

  @Test
  void theIdempotencyKeyIsRequiredAndTheBodyIsBounded() throws Exception {
    Map<String, Object> application =
        PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    MockHttpServletResponse missing =
        mvc.perform(
                post(PUBLIC)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(application)))
            .andReturn()
            .getResponse();
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(code(missing)).isEqualTo("PARTNER_APPLICATION_INVALID");

    Map<String, Object> large = PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    large.put("serviceSummary", "x".repeat(20_000));
    MockHttpServletResponse tooLarge = PartnerWorld.submit(mvc, large);
    assertThat(tooLarge.getStatus()).isEqualTo(400);
    assertThat(code(tooLarge)).isEqualTo("PARTNER_APPLICATION_INVALID");

    MockHttpServletResponse notJson =
        mvc.perform(
                post(PUBLIC)
                    .header("Idempotency-Key", "synthetic-key-" + UUID.randomUUID())
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("hello"))
            .andReturn()
            .getResponse();
    assertThat(notJson.getStatus()).isEqualTo(400);
    assertThat(count("legal_name = ?", application.get("legalName"))).isZero();
  }

  @Test
  void concurrentSubmissionsWithOneKeyCreateOneApplication() throws Exception {
    String key = "synthetic-key-" + UUID.randomUUID();
    Map<String, Object> application =
        PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      Callable<MockHttpServletResponse> task =
          () -> {
            start.await();
            return PartnerWorld.submit(mvc, application, key);
          };
      futures.add(pool.submit(task));
    }
    start.countDown();
    TreeSet<String> references = new TreeSet<>();
    for (Future<MockHttpServletResponse> future : futures) {
      MockHttpServletResponse response = future.get();
      assertThat(response.getStatus()).isEqualTo(201);
      references.add(body(response).path("reference").asText());
    }
    pool.shutdown();
    assertThat(references).hasSize(1);
    assertThat(count("legal_name = ?", application.get("legalName"))).isEqualTo(1);
  }

  @Test
  void onlyTheExactSubmissionRouteIsPublic() throws Exception {
    assertThat(mvc.perform(get(PUBLIC)).andReturn().getResponse().getStatus())
        .isIn(401, 403, 405);
    assertThat(
            mvc.perform(
                    post(PUBLIC + "/PA-ABCDEFGHJKMN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isIn(401, 403, 404);
    for (String path :
        List.of(
            PLATFORM,
            PLATFORM + "/PA-ABCDEFGHJKMN",
            PLATFORM + "/PA-ABCDEFGHJKMN/history")) {
      assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
    }
    assertThat(
            mvc.perform(
                    post(PLATFORM + "/PA-ABCDEFGHJKMN:transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(401);
  }

  @Test
  void onlyAPlatformAdministratorWithExactMfaReachesTheReviewRoutes() throws Exception {
    String reference =
        body(
                PartnerWorld.submit(
                    mvc, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail())))
            .path("reference")
            .asText();
    String tenantAdmin = bearer("sub-tenant-admin", List.of("tenant-admin"), true);
    String manager = bearer("sub-manager", List.of("manager"), true);
    String employee = bearer("sub-employee", List.of("employee"), true);
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .subject("sub-platform-pwd")
                .roles(List.of("platform-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    String path = PLATFORM + "/" + reference;
    for (String denied : List.of(tenantAdmin, manager, employee, passwordOnly)) {
      assertThat(PartnerWorld.read(mvc, denied, PLATFORM).getStatus()).isEqualTo(403);
      assertThat(PartnerWorld.read(mvc, denied, path).getStatus()).isEqualTo(403);
      assertThat(PartnerWorld.read(mvc, denied, path + "/history").getStatus()).isEqualTo(403);
      assertThat(
              mvc.perform(
                      put(path + "/checklist/BUSINESS_IDENTITY")
                          .header("Authorization", denied)
                          .header("Idempotency-Key", "synthetic-check-" + UUID.randomUUID())
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{}"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
      assertThat(
              mvc.perform(
                      post(path + ":transition")
                          .header("Authorization", denied)
                          .header("Idempotency-Key", "synthetic-transition-" + UUID.randomUUID())
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{}"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM integrations.partner_application WHERE reference = ?",
                String.class,
                reference))
        .isEqualTo("SUBMITTED");
  }

  private static String bearer(String subject, List<String> roles, boolean tenant) {
    TestTokens.Builder token = TestTokens.token().subject(subject).roles(roles);
    if (tenant) {
      token = token.tenant(TestTokens.TENANT_A);
    }
    return "Bearer " + token.build();
  }

  private int auditCount() {
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM platform.audit_event WHERE action LIKE 'partner.%'",
            Integer.class);
    return total == null ? 0 : total;
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
