package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.JSON;
import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * MVP-120E (Issue #217, ADR 0044): partner accounts and the controlled product catalogue through
 * the public API against real PostgreSQL. Approved-only and idempotent creation (also under
 * concurrency), the partner and product lifecycles, versioning, scope validation, role and
 * assurance boundaries, the safe tenant and employee catalogue (eligibility, suspension, dates,
 * isolation, uniform 404), and privacy. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-application-read=600",
      "divalhr.request-limits.subject.partner-application-command=600",
      "divalhr.request-limits.subject.partner-account-read=600",
      "divalhr.request-limits.subject.partner-account-command=600",
      "divalhr.request-limits.subject.catalogue-read=600",
      "divalhr.partner-application.email-attempts-per-hour=1000"
    })
class PartnerAccountIntegrationTest {

  private static final String PARTNERS = "/api/v1/platform/partners";
  private static final String CATALOGUE = "/api/v1/catalogue";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private final String admin = PartnerWorld.admin();

  // ------------------------------------------------------------------ helpers

  private String approvedApplication() throws Exception {
    String reference =
        PartnerWorld.underReview(
            mvc, admin, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
    PartnerWorld.satisfyAll(mvc, admin, reference);
    MockHttpServletResponse approved =
        PartnerWorld.transition(
            mvc,
            admin,
            reference,
            "APPROVED_FOR_ONBOARDING",
            PartnerWorld.version(mvc, admin, reference),
            null,
            true);
    assertThat(approved.getStatus()).isEqualTo(200);
    return reference;
  }

  private MockHttpServletResponse send(
      String method, String bearer, String path, Object body, String key) throws Exception {
    var builder =
        switch (method) {
          case "PUT" -> put(path);
          default -> post(path);
        };
    builder.header("Authorization", bearer).header("Idempotency-Key", key);
    return mvc.perform(
            builder.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse create(String applicationReference) throws Exception {
    return send(
        "POST",
        admin,
        PARTNERS + "/from-application/" + applicationReference,
        Map.of("confirmed", true),
        Organizations.newKey());
  }

  private JsonNode partnerOf(String applicationReference) throws Exception {
    MockHttpServletResponse response = create(applicationReference);
    assertThat(response.getStatus()).isEqualTo(200);
    return body(response).path("partner");
  }

  private MockHttpServletResponse partnerTransition(
      String partner, String action, int version, String reason, String note, boolean confirmed)
      throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("action", action);
    command.put("expectedVersion", version);
    if (reason != null) {
      command.put("reasonCode", reason);
    }
    if (note != null) {
      command.put("note", note);
    }
    command.put("confirmed", confirmed);
    return send(
        "POST", admin, PARTNERS + "/" + partner + "/transitions", command, Organizations.newKey());
  }

  private Map<String, Object> content(String name, List<String> countries, LocalDate effective) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("name", Map.of("en", name, "fr", name + " FR"));
    content.put(
        "summary", Map.of("en", "A synthetic advance product.", "fr", "Un produit synthétique."));
    content.put(
        "eligibility",
        Map.of("en", "Open to permanent employees.", "fr", "Pour les employés permanents."));
    content.put(
        "disclosure",
        Map.of(
            "en", "Availability and final terms are determined by the provider.",
            "fr", "La disponibilité et les conditions finales sont fixées par le prestataire."));
    content.put("infoUrl", "https://provider.example.test/product");
    content.put("effectiveDate", effective == null ? null : effective.toString());
    content.put("endDate", null);
    content.put("minMonths", 1);
    content.put("maxMonths", 12);
    content.put("countries", countries);
    content.put(
        "currencies",
        List.of(
            Map.of("currency", "CDF", "minAmount", "10000", "maxAmount", "500000"),
            Map.of("currency", "USD", "minAmount", "10", "maxAmount", "500")));
    return content;
  }

  private JsonNode createProduct(String partner, String code, Map<String, Object> content)
      throws Exception {
    MockHttpServletResponse response =
        send(
            "POST",
            admin,
            PARTNERS + "/" + partner + "/products",
            Map.of("productCode", code, "productType", "SALARY_ADVANCE", "content", content),
            Organizations.newKey());
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return body(response);
  }

  private MockHttpServletResponse productTransition(
      String partner, String code, String action, int version, int rowVersion, String reason)
      throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("action", action);
    command.put("version", version);
    command.put("expectedRowVersion", rowVersion);
    if (reason != null) {
      command.put("reasonCode", reason);
    }
    command.put("confirmed", true);
    return send(
        "POST",
        admin,
        PARTNERS + "/" + partner + "/products/" + code + "/transitions",
        command,
        Organizations.newKey());
  }

  private JsonNode product(String partner, String code) throws Exception {
    return body(PartnerWorld.read(mvc, admin, PARTNERS + "/" + partner + "/products/" + code));
  }

  /**
   * Creates, activates and publishes one product; returns {partner reference, product public
   * reference}.
   */
  private long count(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? -1L : value;
  }

  private String[] published(String code, LocalDate effective) throws Exception {
    JsonNode partner = partnerOf(approvedApplication());
    String ref = partner.path("reference").asText();
    assertThat(
            partnerTransition(ref, "ACTIVATE", partner.path("version").asInt(), null, null, true)
                .getStatus())
        .isEqualTo(200);
    JsonNode created =
        createProduct(ref, code, content("Advance " + code, List.of("CD"), effective));
    JsonNode v1 = created.path("versions").get(0);
    assertThat(
            productTransition(ref, code, "SUBMIT", 1, v1.path("rowVersion").asInt(), null)
                .getStatus())
        .isEqualTo(200);
    JsonNode reviewed = product(ref, code).path("versions").get(0);
    MockHttpServletResponse publish =
        productTransition(ref, code, "PUBLISH", 1, reviewed.path("rowVersion").asInt(), null);
    assertThat(publish.getStatus()).as(publish.getContentAsString()).isEqualTo(200);
    return new String[] {ref, product(ref, code).path("publicReference").asText()};
  }

  private UUID organization(String country) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenant.organization (id, name, country_code, default_locale, timezone, created_at, created_by)"
            + " VALUES (?, ?, ?, 'fr', 'Africa/Kinshasa', now(), 'sub-catalogue')",
        id,
        "Catalogue Org " + id,
        country);
    return id;
  }

  private String member(UUID tenant, String role) {
    String subject = "sub-" + role + "-" + UUID.randomUUID();
    Memberships.grant(tenant, subject, role);
    return "Bearer "
        + TestTokens.token().subject(subject).tenant(tenant).roles(List.of(role)).build();
  }

  // -------------------------------------------------------------------- tests

  @Test
  void aPartnerIsCreatedOnlyFromAnApprovedApplicationAndCopiesNoConfidentialData()
      throws Exception {
    String email = PartnerWorld.uniqueEmail();
    Map<String, Object> application = PartnerWorld.bank(PartnerWorld.uniqueName(), email);
    String pending = body(PartnerWorld.submit(mvc, application)).path("reference").asText();
    MockHttpServletResponse refused = create(pending);
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(code(refused)).isEqualTo("PARTNER_SOURCE_APPLICATION_NOT_APPROVED");
    assertThat(code(create("PA-AAAAAAAAAAAA"))).isEqualTo("PARTNER_APPLICATION_NOT_FOUND");
    long partnersBefore = count("SELECT count(*) FROM integrations.partner");

    String approved = approvedApplication();
    long invitations = count("SELECT count(*) FROM identity.invitation");
    long memberships = count("SELECT count(*) FROM identity.tenant_membership");
    long organizations = count("SELECT count(*) FROM tenant.organization");
    MockHttpServletResponse response = create(approved);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode receipt = body(response);
    assertThat(receipt.path("created").asBoolean()).isTrue();
    JsonNode partner = receipt.path("partner");
    assertThat(partner.path("status").asText()).isEqualTo("DRAFT");
    assertThat(partner.path("applicationReference").asText()).isEqualTo(approved);
    assertThat(partner.path("reference").asText()).matches("^PT-[0-9A-HJKMNP-TV-Z]{16}$");
    assertThat(partner.path("countries")).isNotEmpty();
    assertThat(response.getContentAsString())
        .doesNotContain("@")
        .doesNotContain("registrationReference")
        .doesNotContain("licenceReference")
        .doesNotContain("contact")
        .doesNotContain("serviceSummary");
    // Creation connects, invites and credentials nothing.
    assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.invitation", Long.class))
        .isEqualTo(invitations);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.tenant_membership", Long.class))
        .isEqualTo(memberships);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant.organization", Long.class))
        .isEqualTo(organizations);
    // A different key returns the existing partner and creates nothing.
    JsonNode again = body(create(approved));
    assertThat(again.path("created").asBoolean()).isFalse();
    assertThat(again.path("partner").path("reference").asText())
        .isEqualTo(partner.path("reference").asText());
    assertThat(count("SELECT count(*) FROM integrations.partner")).isEqualTo(partnersBefore + 1);
    // The same key replays the original response.
    String key = Organizations.newKey();
    String other = approvedApplication();
    MockHttpServletResponse first =
        send(
            "POST", admin, PARTNERS + "/from-application/" + other, Map.of("confirmed", true), key);
    MockHttpServletResponse replay =
        send(
            "POST", admin, PARTNERS + "/from-application/" + other, Map.of("confirmed", true), key);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    // Confirmation is required.
    assertThat(
            send(
                    "POST",
                    admin,
                    PARTNERS + "/from-application/" + other,
                    Map.of("confirmed", false),
                    Organizations.newKey())
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void concurrentActivationsCreateExactlyOnePartner() throws Exception {
    String approved = approvedApplication();
    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<MockHttpServletResponse>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          pool.submit(
              () -> {
                go.await();
                return create(approved);
              }));
    }
    go.countDown();
    int created = 0;
    for (Future<MockHttpServletResponse> result : results) {
      MockHttpServletResponse response = result.get();
      assertThat(response.getStatus()).isEqualTo(200);
      if (body(response).path("created").asBoolean()) {
        created++;
      }
    }
    pool.shutdown();
    assertThat(created).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.partner p JOIN integrations.partner_application a ON a.id = p.application_id"
                    + " WHERE a.reference = ?",
                Integer.class,
                approved))
        .isEqualTo(1);
  }

  @Test
  void thePartnerLifecycleIsClosedAndEveryChangeIsRecorded() throws Exception {
    JsonNode partner = partnerOf(approvedApplication());
    String ref = partner.path("reference").asText();
    int version = partner.path("version").asInt();
    // DRAFT cannot be suspended or reactivated; a stale version is refused.
    assertThat(code(partnerTransition(ref, "SUSPEND", version, "COMPLIANCE_REVIEW", null, false)))
        .isEqualTo("PARTNER_TRANSITION_INVALID");
    assertThat(code(partnerTransition(ref, "ACTIVATE", version + 3, null, null, true)))
        .isEqualTo("PARTNER_TRANSITION_INVALID");
    assertThat(partnerTransition(ref, "ACTIVATE", version, null, null, false).getStatus())
        .isEqualTo(400);
    MockHttpServletResponse activated =
        partnerTransition(ref, "ACTIVATE", version, null, null, true);
    assertThat(activated.getStatus()).isEqualTo(200);
    version = body(activated).path("partner").path("version").asInt();
    assertThat(body(activated).path("partner").path("status").asText()).isEqualTo("ACTIVE");
    // Suspension needs a reason; OTHER needs a private note that is never returned.
    assertThat(partnerTransition(ref, "SUSPEND", version, null, null, false).getStatus())
        .isEqualTo(400);
    assertThat(partnerTransition(ref, "SUSPEND", version, "OTHER", null, false).getStatus())
        .isEqualTo(400);
    String note = "synthetic private note " + UUID.randomUUID();
    MockHttpServletResponse suspended =
        partnerTransition(ref, "SUSPEND", version, "OTHER", note, false);
    assertThat(suspended.getStatus()).isEqualTo(200);
    assertThat(suspended.getContentAsString()).doesNotContain(note);
    version = body(suspended).path("partner").path("version").asInt();
    MockHttpServletResponse history =
        PartnerWorld.read(mvc, admin, PARTNERS + "/" + ref + "/history");
    assertThat(history.getContentAsString()).doesNotContain(note).doesNotContain("sub-platform");
    assertThat(body(history).path("items"))
        .extracting(n -> n.path("eventType").asText())
        .containsExactly("CREATED", "ACTIVATED", "SUSPENDED");
    assertThat(body(history).path("items").get(2).path("noteRecorded").asBoolean()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.partner_event WHERE note_ciphertext IS NOT NULL AND reason_code = 'OTHER'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
    MockHttpServletResponse reactivated =
        partnerTransition(ref, "REACTIVATE", version, null, null, true);
    assertThat(reactivated.getStatus()).isEqualTo(200);
    version = body(reactivated).path("partner").path("version").asInt();
    // Scope may only be extended; an unchanged scope is refused.
    Map<String, Object> amend = new LinkedHashMap<>();
    amend.put("action", "AMEND_SCOPE");
    amend.put("expectedVersion", version);
    amend.put("countries", List.of("CD"));
    assertThat(
            code(
                send(
                    "POST",
                    admin,
                    PARTNERS + "/" + ref + "/transitions",
                    amend,
                    Organizations.newKey())))
        .isEqualTo("PARTNER_TRANSITION_INVALID");
    amend.put("countries", List.of("RW"));
    MockHttpServletResponse amended =
        send("POST", admin, PARTNERS + "/" + ref + "/transitions", amend, Organizations.newKey());
    assertThat(amended.getStatus()).as(amended.getContentAsString()).isEqualTo(200);
    assertThat(body(amended).path("partner").path("countries"))
        .extracting(JsonNode::asText)
        .contains("CD", "RW");
    version = body(amended).path("partner").path("version").asInt();
    // Closure is terminal.
    MockHttpServletResponse closed =
        partnerTransition(ref, "CLOSE", version, "PROGRAM_ENDED", null, true);
    assertThat(closed.getStatus()).isEqualTo(200);
    version = body(closed).path("partner").path("version").asInt();
    assertThat(code(partnerTransition(ref, "REACTIVATE", version, null, null, true)))
        .isEqualTo("PARTNER_TRANSITION_INVALID");
    assertThat(body(PartnerWorld.read(mvc, admin, PARTNERS + "/" + ref)).path("allowedActions"))
        .isEmpty();
    assertThat(code(PartnerWorld.read(mvc, admin, PARTNERS + "/PT-AAAAAAAAAAAAAAAA")))
        .isEqualTo("PARTNER_NOT_FOUND");
    assertThat(code(PartnerWorld.read(mvc, admin, PARTNERS + "/not-a-reference")))
        .isEqualTo("PARTNER_NOT_FOUND");
  }

  @Test
  void productsAreValidatedScopedVersionedAndImmutableOncePublished() throws Exception {
    JsonNode partner = partnerOf(approvedApplication());
    String ref = partner.path("reference").asText();
    partnerTransition(ref, "ACTIVATE", partner.path("version").asInt(), null, null, true);
    LocalDate today = LocalDate.now();
    // Scope: a country outside the approved scope and a currency that is not offered.
    Map<String, Object> outside = content("Outside", List.of("JP"), today);
    MockHttpServletResponse scope =
        send(
            "POST",
            admin,
            PARTNERS + "/" + ref + "/products",
            Map.of("productCode", "OUT", "productType", "SALARY_ADVANCE", "content", outside),
            Organizations.newKey());
    assertThat(code(scope)).isEqualTo("PARTNER_PRODUCT_SCOPE_INVALID");
    Map<String, Object> euro = content("Euro", List.of("CD"), today);
    euro.put("currencies", List.of(Map.of("currency", "EUR", "minAmount", "1", "maxAmount", "2")));
    assertThat(
            code(
                send(
                    "POST",
                    admin,
                    PARTNERS + "/" + ref + "/products",
                    Map.of("productCode", "EUR", "productType", "SALARY_ADVANCE", "content", euro),
                    Organizations.newKey())))
        .isEqualTo("PARTNER_PRODUCT_SCOPE_INVALID");
    Map<String, Object> inverted = content("Inverted", List.of("CD"), today);
    inverted.put("minMonths", 12);
    inverted.put("maxMonths", 1);
    assertThat(
            code(
                send(
                    "POST",
                    admin,
                    PARTNERS + "/" + ref + "/products",
                    Map.of(
                        "productCode", "INV", "productType", "SALARY_ADVANCE", "content", inverted),
                    Organizations.newKey())))
        .isEqualTo("PARTNER_PRODUCT_INVALID");

    JsonNode created = createProduct(ref, "ADV-1", content("Advance", List.of("CD"), today));
    assertThat(created.path("publicReference").asText()).matches("^PP-[0-9A-HJKMNP-TV-Z]{16}$");
    assertThat(created.path("versions")).hasSize(1);
    // A duplicate code is refused.
    assertThat(
            code(
                send(
                    "POST",
                    admin,
                    PARTNERS + "/" + ref + "/products",
                    Map.of(
                        "productCode",
                        "ADV-1",
                        "productType",
                        "SALARY_ADVANCE",
                        "content",
                        content("Again", List.of("CD"), today)),
                    Organizations.newKey())))
        .isEqualTo("PARTNER_TRANSITION_INVALID");
    // An incomplete draft saves, but cannot be submitted.
    Map<String, Object> incomplete = content("Advance", List.of("CD"), today);
    incomplete.put("name", Map.of("en", "Advance", "fr", ""));
    MockHttpServletResponse saved =
        send(
            "PUT",
            admin,
            PARTNERS + "/" + ref + "/products/ADV-1/draft",
            Map.of("expectedVersion", 1, "expectedRowVersion", 0, "content", incomplete),
            Organizations.newKey());
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    int row = body(saved).path("versions").get(0).path("rowVersion").asInt();
    assertThat(code(productTransition(ref, "ADV-1", "SUBMIT", 1, row, null)))
        .isEqualTo("PARTNER_PRODUCT_INVALID");
    // A stale row version is refused.
    assertThat(
            code(
                send(
                    "PUT",
                    admin,
                    PARTNERS + "/" + ref + "/products/ADV-1/draft",
                    Map.of(
                        "expectedVersion",
                        1,
                        "expectedRowVersion",
                        0,
                        "content",
                        content("Advance", List.of("CD"), today)),
                    Organizations.newKey())))
        .isEqualTo("PARTNER_PRODUCT_VERSION_STALE");
    MockHttpServletResponse complete =
        send(
            "PUT",
            admin,
            PARTNERS + "/" + ref + "/products/ADV-1/draft",
            Map.of(
                "expectedVersion",
                1,
                "expectedRowVersion",
                row,
                "content",
                content("Advance", List.of("CD"), today)),
            Organizations.newKey());
    row = body(complete).path("versions").get(0).path("rowVersion").asInt();
    // Submit, return, edit, resubmit, publish.
    assertThat(productTransition(ref, "ADV-1", "SUBMIT", 1, row, null).getStatus()).isEqualTo(200);
    assertThat(
            code(
                send(
                    "PUT",
                    admin,
                    PARTNERS + "/" + ref + "/products/ADV-1/draft",
                    Map.of(
                        "expectedVersion",
                        1,
                        "expectedRowVersion",
                        row + 1,
                        "content",
                        content("Advance", List.of("CD"), today)),
                    Organizations.newKey())))
        .isEqualTo("PARTNER_PRODUCT_TRANSITION_INVALID");
    row = product(ref, "ADV-1").path("versions").get(0).path("rowVersion").asInt();
    assertThat(code(productTransition(ref, "ADV-1", "PAUSE", 1, row, "COMMERCIAL_HOLD")))
        .isEqualTo("PARTNER_PRODUCT_TRANSITION_INVALID");
    assertThat(productTransition(ref, "ADV-1", "PUBLISH", 1, row, null).getStatus()).isEqualTo(200);
    JsonNode v1 = product(ref, "ADV-1").path("versions").get(0);
    assertThat(v1.path("status").asText()).isEqualTo("ACTIVE");
    // Editing an active product creates a new draft version; the published one is untouched.
    MockHttpServletResponse edited =
        send(
            "PUT",
            admin,
            PARTNERS + "/" + ref + "/products/ADV-1/draft",
            Map.of(
                "expectedVersion",
                1,
                "expectedRowVersion",
                v1.path("rowVersion").asInt(),
                "content",
                content("Advance v2", List.of("CD"), today)),
            Organizations.newKey());
    assertThat(edited.getStatus()).as(edited.getContentAsString()).isEqualTo(200);
    JsonNode versions = body(edited).path("versions");
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).path("status").asText()).isEqualTo("DRAFT");
    assertThat(versions.get(1).path("status").asText()).isEqualTo("ACTIVE");
    assertThat(versions.get(1).path("content").path("name").path("en").asText())
        .isEqualTo("Advance");
    int v2Row = versions.get(0).path("rowVersion").asInt();
    assertThat(productTransition(ref, "ADV-1", "SUBMIT", 2, v2Row, null).getStatus())
        .isEqualTo(200);
    v2Row = product(ref, "ADV-1").path("versions").get(0).path("rowVersion").asInt();
    JsonNode receipt = body(productTransition(ref, "ADV-1", "PUBLISH", 2, v2Row, null));
    assertThat(receipt.path("supersededVersion").asInt()).isEqualTo(1);
    JsonNode after = product(ref, "ADV-1").path("versions");
    assertThat(after.get(0).path("status").asText()).isEqualTo("ACTIVE");
    assertThat(after.get(1).path("status").asText()).isEqualTo("RETIRED");
    // The database refuses content changes of published or retired versions.
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE integrations.partner_product_version SET name_en = 'tamper' WHERE status IN ('ACTIVE', 'RETIRED')"));
    // A partner with an active product cannot be closed.
    int partnerVersion =
        body(PartnerWorld.read(mvc, admin, PARTNERS + "/" + ref)).path("version").asInt();
    assertThat(code(partnerTransition(ref, "CLOSE", partnerVersion, "PROGRAM_ENDED", null, true)))
        .isEqualTo("PARTNER_HAS_ACTIVE_PRODUCTS");
    int active = after.get(0).path("rowVersion").asInt();
    assertThat(productTransition(ref, "ADV-1", "PAUSE", 2, active, null).getStatus())
        .isEqualTo(400);
    MockHttpServletResponse history =
        PartnerWorld.read(mvc, admin, PARTNERS + "/" + ref + "/products/ADV-1/history");
    assertThat(body(history).path("items"))
        .extracting(n -> n.path("eventType").asText())
        .contains("VERSION_CREATED", "SUBMITTED", "PUBLISHED", "SUPERSEDED");
  }

  @Test
  void platformRoutesRefuseEveryoneButAPlatformAdministratorWithMfa() throws Exception {
    UUID tenant = organization("CD");
    String tenantAdmin = member(tenant, "tenant-admin");
    String employee = member(tenant, "employee");
    for (String bearer : List.of(tenantAdmin, employee)) {
      assertThat(PartnerWorld.read(mvc, bearer, PARTNERS).getStatus()).isEqualTo(403);
      assertThat(PartnerWorld.read(mvc, bearer, PARTNERS + "/ready-applications").getStatus())
          .isEqualTo(403);
      assertThat(
              send(
                      "POST",
                      bearer,
                      PARTNERS + "/from-application/PA-AAAAAAAAAAAA",
                      Map.of("confirmed", true),
                      Organizations.newKey())
                  .getStatus())
          .isEqualTo(403);
    }
    assertThat(mvc.perform(get(PARTNERS)).andReturn().getResponse().getStatus()).isEqualTo(401);
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .subject("sub-platform-" + UUID.randomUUID())
                .tenant(TestTokens.TENANT_A)
                .roles(List.of("platform-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    assertThat(PartnerWorld.read(mvc, passwordOnly, PARTNERS).getStatus()).isEqualTo(403);
    MockHttpServletResponse list = PartnerWorld.read(mvc, admin, PARTNERS + "?limit=5");
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(list.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(body(list).path("statusCounts").has("ACTIVE")).isTrue();
    assertThat(PartnerWorld.read(mvc, admin, PARTNERS + "?status=NOPE").getStatus()).isEqualTo(400);
    assertThat(PartnerWorld.read(mvc, admin, PARTNERS + "/ready-applications").getStatus())
        .isEqualTo(200);
  }

  @Test
  void theCatalogueShowsOnlyEligibleProductsAndNothingInternal() throws Exception {
    String[] visible = published("VISIBLE-1", LocalDate.now().minusDays(1));
    String partner = visible[0];
    String reference = visible[1];
    String[] future = published("FUTURE-1", LocalDate.now().plusDays(10));
    UUID congo = organization("CD");
    UUID japan = organization("JP");
    String congoAdmin = member(congo, "tenant-admin");
    String congoEmployee = member(congo, "employee");
    String japanEmployee = member(japan, "employee");

    for (String bearer : List.of(congoAdmin, congoEmployee)) {
      MockHttpServletResponse products = PartnerWorld.read(mvc, bearer, CATALOGUE + "/products");
      assertThat(products.getStatus()).isEqualTo(200);
      assertThat(products.getHeader("Cache-Control")).isEqualTo("private, no-store");
      JsonNode page = body(products);
      assertThat(page.path("informationOnly").asBoolean()).isTrue();
      assertThat(page.path("availability").asText()).isEqualTo("AVAILABLE");
      List<String> references = new ArrayList<>();
      page.path("items").forEach(item -> references.add(item.path("publicReference").asText()));
      assertThat(references).contains(reference).doesNotContain(future[1]);
      String text = products.getContentAsString();
      assertThat(text)
          .doesNotContain(partner)
          .doesNotContain("PT-")
          .doesNotContain("applicationReference")
          .doesNotContain("partnerReference")
          .doesNotContain("productCode")
          .doesNotContain("\"code\"")
          .doesNotContain("rowVersion")
          .doesNotContain("@");
      assertThat(PartnerWorld.read(mvc, bearer, CATALOGUE + "/products/" + reference).getStatus())
          .isEqualTo(200);
      assertThat(PartnerWorld.read(mvc, bearer, CATALOGUE + "/partners").getStatus())
          .isEqualTo(200);
    }
    // Another country, an unknown country and a future date all look like an unknown product.
    MockHttpServletResponse foreign =
        PartnerWorld.read(mvc, japanEmployee, CATALOGUE + "/products/" + reference);
    MockHttpServletResponse notYet =
        PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/products/" + future[1]);
    MockHttpServletResponse nothing =
        PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/products/PP-AAAAAAAAAAAAAAAA");
    MockHttpServletResponse malformed =
        PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/products/garbage");
    for (MockHttpServletResponse answer : List.of(foreign, notYet, nothing, malformed)) {
      assertThat(answer.getStatus()).isEqualTo(404);
      assertThat(code(answer)).isEqualTo("PARTNER_PRODUCT_NOT_AVAILABLE");
    }
    assertThat(body(PartnerWorld.read(mvc, japanEmployee, CATALOGUE + "/products")).path("items"))
        .isEmpty();
    // Suspending the partner hides its products at once; reactivation restores them.
    int version =
        body(PartnerWorld.read(mvc, admin, PARTNERS + "/" + partner)).path("version").asInt();
    MockHttpServletResponse suspended =
        partnerTransition(partner, "SUSPEND", version, "COMPLIANCE_REVIEW", null, false);
    assertThat(suspended.getStatus()).isEqualTo(200);
    assertThat(code(PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/products/" + reference)))
        .isEqualTo("PARTNER_PRODUCT_NOT_AVAILABLE");
    JsonNode detail = body(PartnerWorld.read(mvc, admin, PARTNERS + "/" + partner));
    String displayName =
        detail.path("tradingName").isTextual()
            ? detail.path("tradingName").asText()
            : detail.path("legalName").asText();
    assertThat(PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/partners").getContentAsString())
        .doesNotContain(displayName);
    version = body(suspended).path("partner").path("version").asInt();
    assertThat(partnerTransition(partner, "REACTIVATE", version, null, null, true).getStatus())
        .isEqualTo(200);
    assertThat(
            PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/products/" + reference).getStatus())
        .isEqualTo(200);
    assertThat(PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/partners").getContentAsString())
        .contains(displayName);
    // Pausing the product hides it.
    JsonNode live = product(partner, "VISIBLE-1").path("versions").get(0);
    assertThat(
            productTransition(
                    partner,
                    "VISIBLE-1",
                    "PAUSE",
                    1,
                    live.path("rowVersion").asInt(),
                    "COMMERCIAL_HOLD")
                .getStatus())
        .isEqualTo(200);
    assertThat(code(PartnerWorld.read(mvc, congoEmployee, CATALOGUE + "/products/" + reference)))
        .isEqualTo("PARTNER_PRODUCT_NOT_AVAILABLE");
    // Platform administrators have no tenant, so the tenant catalogue refuses them.
    assertThat(PartnerWorld.read(mvc, admin, CATALOGUE + "/products").getStatus())
        .isNotEqualTo(200);
  }

  @Test
  void theV56RollbackRefusesOnceAnyPartnerRowExists() throws Exception {
    partnerOf(approvedApplication());
    String rollback =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/db/rollback/V56__rollback.sql"));
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                    connection -> {
                      boolean auto = connection.getAutoCommit();
                      connection.setAutoCommit(false);
                      try (var statement = connection.createStatement()) {
                        statement.execute(rollback.replace("BEGIN;", "").replace("COMMIT;", ""));
                      } finally {
                        connection.rollback();
                        connection.setAutoCommit(auto);
                      }
                      return null;
                    }));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM integrations.partner", Integer.class))
        .isGreaterThanOrEqualTo(1);
  }
}
