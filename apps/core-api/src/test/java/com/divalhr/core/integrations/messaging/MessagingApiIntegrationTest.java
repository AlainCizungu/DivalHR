package com.divalhr.core.integrations.messaging;

import static com.divalhr.core.integrations.messaging.MessagingFixture.JSON;
import static com.divalhr.core.integrations.messaging.MessagingFixture.NO_STORE;
import static com.divalhr.core.integrations.messaging.MessagingFixture.PATH;
import static com.divalhr.core.integrations.messaging.MessagingFixture.connectorBody;
import static com.divalhr.core.integrations.messaging.MessagingFixture.digits;
import static com.divalhr.core.integrations.messaging.MessagingFixture.json;
import static com.divalhr.core.integrations.messaging.MessagingFixture.key;
import static com.divalhr.core.integrations.messaging.MessagingFixture.newNumber;
import static com.divalhr.core.integrations.messaging.MessagingFixture.newToken;
import static com.divalhr.core.integrations.messaging.MessagingFixture.recipientBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.integrations.messaging.MessagingTestConfig.ScriptedProvider;
import com.divalhr.core.integrations.messaging.application.MessagingJobs;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.CredentialResult;
import com.divalhr.core.integrations.messaging.application.MessagingProvider.TemplateResult;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-111 (D111-1..D111-5, D111-11..D111-14): authorization and tenant isolation, the connector and
 * recipient lifecycle, the secret and number handling, idempotency, the test-message limits, and
 * what is audited, published and stored.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import(MessagingTestConfig.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.messaging-write=600",
      "divalhr.request-limits.subject.messaging-read=600",
      "divalhr.messaging.jitter=false"
    })
class MessagingApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ScriptedProvider provider;
  @Autowired private MessagingJobs jobs;

  private MessagingFixture fixture;
  private UUID tenant;
  private UUID otherTenant;
  private String admin;
  private String otherAdmin;

  @BeforeEach
  void organizations() throws Exception {
    provider.reset();
    fixture = new MessagingFixture(mvc);
    tenant = Hierarchy.newTenant(mvc);
    otherTenant = Hierarchy.newTenant(mvc);
    admin = Hierarchy.bearer(tenant, "sub-msg-" + UUID.randomUUID(), "tenant-admin");
    otherAdmin = Hierarchy.bearer(otherTenant, "sub-msg-" + UUID.randomUUID(), "tenant-admin");
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String error(MockHttpServletRequestBuilder request, int status, String code)
      throws Exception {
    return mvc.perform(request)
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(header().string("Cache-Control", NO_STORE))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static String confirm(int version) {
    return "{\"configVersion\":" + version + ",\"confirm\":true}";
  }

  // ------------------------------------------------------------------------------------------
  // Authorization (D111-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAMfaTenantAdministratorMayUseTheSurface() throws Exception {
    String employee = Hierarchy.bearer(tenant, "sub-emp-" + UUID.randomUUID(), "employee");
    String password =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject("sub-pwd-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    mvc.perform(get(PATH).header("Authorization", employee)).andExpect(status().isForbidden());
    mvc.perform(get(PATH + "/recipients").header("Authorization", employee))
        .andExpect(status().isForbidden());
    mvc.perform(get(PATH).header("Authorization", password))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
        .andExpect(header().string("Cache-Control", NO_STORE));
    mvc.perform(json(post(PATH), employee, key(), connectorBody(digits(), digits(), newToken())))
        .andExpect(status().isForbidden());
    mvc.perform(json(post(PATH), password, key(), connectorBody(digits(), digits(), newToken())))
        .andExpect(status().isForbidden());
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_connector WHERE tenant_id = ?",
                tenant))
        .isZero();
  }

  @Test
  void everyResponseIncludingErrorsIsPrivateNoStore() throws Exception {
    // An unauthenticated answer is produced by the security chain, whose own cache policy is
    // equally non-cacheable.
    mvc.perform(get(PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Cache-Control", containsString("no-store")));
    error(get(PATH).header("Authorization", admin), 404, "MESSAGING_CONNECTOR_NOT_FOUND");
    JsonNode created = fixture.connect(admin, newToken());
    mvc.perform(get(PATH).header("Authorization", admin))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", NO_STORE));
    mvc.perform(get(PATH + "/recipients").header("Authorization", admin))
        .andExpect(header().string("Cache-Control", NO_STORE));
    mvc.perform(get(PATH + "/deliveries").header("Authorization", admin))
        .andExpect(header().string("Cache-Control", NO_STORE));
    assertThat(created.get("state").asText()).isEqualTo("DRAFT");
  }

  // ------------------------------------------------------------------------------------------
  // Tenant isolation: foreign, unknown and malformed ids are one indistinguishable 404 (D111-11)
  // ------------------------------------------------------------------------------------------

  @Test
  void foreignUnknownAndMalformedIdsAreIndistinguishable() throws Exception {
    fixture.activate(admin);
    String recipientId =
        fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    UUID deliveryId = fixture.test(admin, recipientId);
    fixture.connect(otherAdmin, newToken());

    for (String id : new String[] {recipientId, UUID.randomUUID().toString(), "not-a-uuid", "0"}) {
      String foreign =
          error(
              get(PATH + "/recipients/" + id).header("Authorization", otherAdmin),
              404,
              "MESSAGING_RECIPIENT_NOT_FOUND");
      String own =
          error(
              get(PATH + "/recipients/" + (id.equals(recipientId) ? UUID.randomUUID() : id))
                  .header("Authorization", admin),
              404,
              "MESSAGING_RECIPIENT_NOT_FOUND");
      assertThat(JSON.readTree(foreign).get("title")).isEqualTo(JSON.readTree(own).get("title"));
      assertThat(JSON.readTree(foreign).get("status")).isEqualTo(JSON.readTree(own).get("status"));
      error(
          json(
              patch(PATH + "/recipients/" + id),
              otherAdmin,
              key(),
              "{\"version\":1,\"label\":\"x\",\"language\":\"en\",\"notificationTypes\":[\"LEAVE_DECISION\"],\"confirm\":true}"),
          404,
          "MESSAGING_RECIPIENT_NOT_FOUND");
      error(
          json(
              post(PATH + "/recipients/" + id + "/disable"),
              otherAdmin,
              key(),
              "{\"version\":1,\"confirm\":true}"),
          404,
          "MESSAGING_RECIPIENT_NOT_FOUND");
      error(
          json(
              post(PATH + "/recipients/" + id + "/test-deliveries"),
              otherAdmin,
              key(),
              "{\"confirm\":true}"),
          404,
          "MESSAGING_RECIPIENT_NOT_FOUND");
      error(
          get(PATH + "/deliveries").param("recipientId", id).header("Authorization", otherAdmin),
          404,
          "MESSAGING_RECIPIENT_NOT_FOUND");
    }
    for (String id : new String[] {deliveryId.toString(), UUID.randomUUID().toString(), "not-a-uuid"}) {
      error(
          get(PATH + "/deliveries/" + id).header("Authorization", otherAdmin),
          404,
          "MESSAGING_DELIVERY_NOT_FOUND");
    }
    // The foreign administrator's actions changed nothing of the owner's.
    assertThat(fixture.connector(admin).get("state").asText()).isEqualTo("ACTIVE");
    assertThat(
            fixture
                .read(get(PATH + "/recipients").header("Authorization", otherAdmin))
                .get("items"))
        .isEmpty();
    assertThat(
            fixture
                .read(get(PATH + "/deliveries").header("Authorization", otherAdmin))
                .get("items"))
        .isEmpty();
  }

  @Test
  void aPhoneNumberIdOfAnotherTenantIsNeverRevealed() throws Exception {
    String phoneId = digits();
    mvc.perform(json(post(PATH), admin, key(), connectorBody(digits(), phoneId, newToken())))
        .andExpect(status().isCreated());
    String refused =
        error(
            json(post(PATH), otherAdmin, key(), connectorBody(digits(), phoneId, newToken())),
            422,
            "MESSAGING_PROVIDER_CREDENTIAL_INVALID");
    assertThat(refused).doesNotContain(phoneId).doesNotContain(tenant.toString());
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_connector WHERE tenant_id = ?",
                otherTenant))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Connect, secrets, idempotency (D111-2, D111-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void theTokenIsAcceptedOnceAndNeverReturnedStoredReplayedAuditedPublishedOrLogged(
      CapturedOutput output) throws Exception {
    String token = newToken();
    String idempotencyKey = key();
    String body = connectorBody(digits(), digits(), token);
    String first =
        mvc.perform(json(post(PATH), admin, idempotencyKey, body))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", NO_STORE))
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.provider").value("WHATSAPP"))
            .andExpect(jsonPath("$.credentialVersion").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(first).doesNotContain(token);
    assertThat(JSON.readTree(first).has("accessToken")).isFalse();
    assertThat(JSON.readTree(first).get("templates")).hasSize(12);

    String replay =
        mvc.perform(json(post(PATH), admin, idempotencyKey, body))
            .andExpect(status().is2xxSuccessful())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(replay).doesNotContain(token);
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_connector WHERE tenant_id = ?",
                tenant))
        .isEqualTo(1);
    // A different body under the same key is a conflict.
    mvc.perform(
            json(post(PATH), admin, idempotencyKey, connectorBody(digits(), digits(), newToken())))
        .andExpect(status().isConflict());

    String read =
        mvc.perform(get(PATH).header("Authorization", admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(read).doesNotContain(token);

    for (String table :
        List.of(
            "integrations.messaging_credential",
            "integrations.messaging_connector",
            "integrations.messaging_template",
            "platform.idempotency_record",
            "platform.audit_event",
            "platform.outbox_event")) {
      assertThat(
              count("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?", "%" + token + "%"))
          .as(table)
          .isZero();
    }
    assertThat(output.getAll()).doesNotContain(token);
    assertThat(
            count(
                "SELECT octet_length(ciphertext) FROM integrations.messaging_credential"
                    + " WHERE tenant_id = ? AND version = 1",
                tenant))
        .isBetween(40, 2048);
  }

  @Test
  void aSecondConnectorForTheSameTenantIsRefused() throws Exception {
    fixture.connect(admin, newToken());
    error(
        json(post(PATH), admin, key(), connectorBody(digits(), digits(), newToken())),
        409,
        "MESSAGING_CONNECTOR_EXISTS");
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_connector WHERE tenant_id = ?",
                tenant))
        .isEqualTo(1);
  }

  @Test
  void connectRequiresConfirmationAKeyAndWellFormedValues() throws Exception {
    String good = connectorBody(digits(), digits(), newToken());
    mvc.perform(json(post(PATH), admin, key(), good.replace(",\"confirm\":true", "")))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(PATH), admin, null, good)).andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH),
                admin,
                key(),
                good.replace("\"wabaId\":\"", "\"extra\":1,\"wabaId\":\"")))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(PATH), admin, key(), connectorBody("12", digits(), newToken())))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(PATH), admin, key(), connectorBody(digits(), "abc", newToken())))
        .andExpect(status().isBadRequest());
    mvc.perform(json(post(PATH), admin, key(), connectorBody(digits(), digits(), "short")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH),
                admin,
                key(),
                connectorBody(digits(), digits(), "has space and <markup> in it !!")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH),
                admin,
                key(),
                good.replace("divalhr_leave_decision_en", "Not A Valid Name")))
        .andExpect(status().isBadRequest());
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_connector WHERE tenant_id = ?",
                tenant))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Verify, enable, disable (D111-5, D111-8)
  // ------------------------------------------------------------------------------------------

  @Test
  void verifyReportsAndNeverActivatesAndEnableFailsClosed() throws Exception {
    JsonNode created = fixture.connect(admin, newToken());
    int version = created.get("configVersion").asInt();

    mvc.perform(json(post(PATH + "/verify"), admin, key(), confirm(version)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true))
        .andExpect(jsonPath("$.credential").value("VALID"))
        .andExpect(jsonPath("$.connector.state").value("DRAFT"));

    provider.template(TemplateResult.WRONG_SHAPE, 0);
    mvc.perform(json(post(PATH + "/verify"), admin, key(), confirm(version)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.templates[0].result").value("WRONG_SHAPE"));
    error(
        json(post(PATH + "/enable"), admin, key(), confirm(version)),
        422,
        "MESSAGING_TEMPLATE_INVALID");

    provider.credential(CredentialResult.REJECTED);
    error(
        json(post(PATH + "/enable"), admin, key(), confirm(version)),
        422,
        "MESSAGING_PROVIDER_CREDENTIAL_INVALID");

    provider.credential(CredentialResult.UNAVAILABLE);
    error(
        json(post(PATH + "/enable"), admin, key(), confirm(version)),
        503,
        "MESSAGING_PROVIDER_UNAVAILABLE");

    assertThat(fixture.connector(admin).get("state").asText()).isEqualTo("DRAFT");
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_delivery WHERE tenant_id = ?", tenant))
        .isZero();
  }

  @Test
  void aConnectorMissingTemplatesCannotBeActivated() throws Exception {
    String body =
        "{\"wabaId\":\""
            + digits()
            + "\",\"phoneNumberId\":\""
            + digits()
            + "\",\"displayNumber\":\"+243812000111\",\"accessToken\":\""
            + newToken()
            + "\",\"templates\":[{\"notificationType\":\"LEAVE_DECISION\",\"language\":\"en\","
            + "\"templateName\":\"divalhr_leave_decision_en\"}],\"confirm\":true}";
    mvc.perform(json(post(PATH), admin, key(), body)).andExpect(status().isCreated());
    error(
        json(post(PATH + "/enable"), admin, key(), confirm(1)), 422, "MESSAGING_TEMPLATE_INVALID");
    mvc.perform(json(post(PATH + "/verify"), admin, key(), confirm(1)))
        .andExpect(jsonPath("$.verified").value(false));
  }

  @Test
  void enableDisableAndUpdateFollowTheLifecycleWithOptimisticVersions() throws Exception {
    JsonNode active = fixture.activate(admin);
    int version = active.get("configVersion").asInt();
    assertThat(active.get("verifiedAt").isNull()).isFalse();

    error(
        json(post(PATH + "/enable"), admin, key(), confirm(version)),
        409,
        "MESSAGING_ALREADY_ENABLED");
    error(
        json(
            patch(PATH),
            admin,
            key(),
            "{\"configVersion\":" + version + ",\"wabaId\":\"" + digits() + "\",\"confirm\":true}"),
        409,
        "MESSAGING_CONNECTOR_ACTIVE");
    error(
        json(post(PATH + "/disable"), admin, key(), confirm(version + 5)),
        409,
        "MESSAGING_CONNECTOR_VERSION_STALE");

    mvc.perform(json(post(PATH + "/disable"), admin, key(), confirm(version)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DISABLED"));
    error(
        json(post(PATH + "/disable"), admin, key(), confirm(version)),
        409,
        "MESSAGING_ALREADY_DISABLED");

    String newWaba = digits();
    JsonNode updated =
        JSON.readTree(
            mvc.perform(
                    json(
                        patch(PATH),
                        admin,
                        key(),
                        "{\"configVersion\":"
                            + version
                            + ",\"wabaId\":\""
                            + newWaba
                            + "\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(updated.get("wabaId").asText()).isEqualTo(newWaba);
    assertThat(updated.get("configVersion").asInt()).isEqualTo(version + 1);
    // Stale after the change.
    error(
        json(
            patch(PATH),
            admin,
            key(),
            "{\"configVersion\":" + version + ",\"wabaId\":\"" + digits() + "\",\"confirm\":true}"),
        409,
        "MESSAGING_CONNECTOR_VERSION_STALE");

    mvc.perform(json(post(PATH + "/enable"), admin, key(), confirm(version + 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.consecutiveFailures").value(0));
  }

  // ------------------------------------------------------------------------------------------
  // Rotation (D111-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void rotationVerifiesTheNewTokenFirstAndErasesTheOldCiphertext() throws Exception {
    JsonNode active = fixture.activate(admin);
    int version = active.get("configVersion").asInt();
    String replacement = newToken();

    provider.credential(CredentialResult.REJECTED);
    error(
        json(
            post(PATH + "/rotate-token"),
            admin,
            key(),
            "{\"configVersion\":"
                + version
                + ",\"accessToken\":\""
                + replacement
                + "\",\"confirm\":true}"),
        422,
        "MESSAGING_PROVIDER_CREDENTIAL_INVALID");
    provider.credential(CredentialResult.UNAVAILABLE);
    error(
        json(
            post(PATH + "/rotate-token"),
            admin,
            key(),
            "{\"configVersion\":"
                + version
                + ",\"accessToken\":\""
                + replacement
                + "\",\"confirm\":true}"),
        503,
        "MESSAGING_PROVIDER_UNAVAILABLE");
    assertThat(fixture.connector(admin).get("credentialVersion").asInt()).isEqualTo(1);

    provider.credential(CredentialResult.VALID);
    String response =
        mvc.perform(
                json(
                    post(PATH + "/rotate-token"),
                    admin,
                    key(),
                    "{\"configVersion\":"
                        + version
                        + ",\"accessToken\":\""
                        + replacement
                        + "\",\"confirm\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialVersion").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(replacement);
    assertThat(provider.checkedTokens()).contains(replacement);
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_credential WHERE tenant_id = ? AND"
                    + " ciphertext IS NOT NULL",
                tenant))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_credential WHERE tenant_id = ? AND"
                    + " version = 1 AND ciphertext IS NULL AND erased_at IS NOT NULL",
                tenant))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event t WHERE tenant_id = ? AND t::text LIKE"
                    + " ?",
                tenant,
                "%" + replacement + "%"))
        .isZero();

    // New work uses only the new token.
    String recipient =
        fixture.recipient(admin, newNumber(), "fr", "LEAVE_DECISION").get("id").asText();
    fixture.test(admin, recipient);
    jobs.deliverDue();
    assertThat(provider.sent()).isNotEmpty();
    assertThat(provider.sent().get(provider.sent().size() - 1).token()).isEqualTo(replacement);
    // The same version cannot be reused.
    error(
        json(
            post(PATH + "/rotate-token"),
            admin,
            key(),
            "{\"configVersion\":"
                + version
                + 99
                + ",\"accessToken\":\""
                + newToken()
                + "\",\"confirm\":true}"),
        409,
        "MESSAGING_CONNECTOR_VERSION_STALE");
  }

  // ------------------------------------------------------------------------------------------
  // Recipients (D111-4)
  // ------------------------------------------------------------------------------------------

  @Test
  void aRecipientIsShownOnlyMaskedAndNeverStoredInPlaintext(CapturedOutput output)
      throws Exception {
    fixture.connect(admin, newToken());
    String number = newNumber();
    String created =
        mvc.perform(
                json(
                    post(PATH + "/recipients"),
                    admin,
                    key(),
                    recipientBody(
                        "Payroll lead", number, "fr", "LEAVE_DECISION", "PAYROLL_EXPORT_READY")))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", NO_STORE))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.language").value("fr"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String masked = JSON.readTree(created).get("numberMasked").asText();
    assertThat(masked)
        .matches("^\\+2 \\*\\*\\* \\*\\* [0-9]{2}$")
        .isEqualTo("+2 *** ** " + number.substring(number.length() - 2));
    assertThat(created).doesNotContain(number).doesNotContain(number.substring(1));
    String list = fixture.read(get(PATH + "/recipients").header("Authorization", admin)).toString();
    assertThat(list).doesNotContain(number).doesNotContain(number.substring(1));
    String id = JSON.readTree(created).get("id").asText();
    assertThat(
            fixture.read(get(PATH + "/recipients/" + id).header("Authorization", admin)).toString())
        .doesNotContain(number.substring(1));

    for (String table :
        List.of(
            "integrations.messaging_recipient",
            "platform.idempotency_record",
            "platform.audit_event",
            "platform.outbox_event")) {
      assertThat(
              count(
                  "SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?",
                  "%" + number.substring(1) + "%"))
          .as(table)
          .isZero();
    }
    assertThat(
            count(
                "SELECT octet_length(number_ciphertext) FROM integrations.messaging_recipient WHERE"
                    + " id = ?::uuid",
                id))
        .isBetween(40, 256);
    assertThat(output.getAll()).doesNotContain(number).doesNotContain(number.substring(1));
  }

  @Test
  void consentIsAnExplicitAttestationAndTheNumberMustBeE164() throws Exception {
    fixture.connect(admin, newToken());
    String good = recipientBody("Lead", newNumber(), "en", "LEAVE_DECISION");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            good.replaceAll(",\"consent\":\\{[^}]*\\}", "")),
        422,
        "MESSAGING_CONSENT_REQUIRED");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            good.replace("\"attested\":true", "\"attested\":false")),
        422,
        "MESSAGING_CONSENT_REQUIRED");
    error(
        json(post(PATH + "/recipients"), admin, key(), good.replace("WRITTEN", "SOMETHING_ELSE")),
        422,
        "MESSAGING_CONSENT_REQUIRED");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            good.replaceAll(
                "\"confirmedAt\":\"[^\"]*\"", "\"confirmedAt\":\"2999-01-01T00:00:00Z\"")),
        422,
        "MESSAGING_CONSENT_REQUIRED");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            good.replaceAll("\"confirmedAt\":\"[^\"]*\"", "\"confirmedAt\":\"yesterday\"")),
        422,
        "MESSAGING_CONSENT_REQUIRED");
    for (String bad :
        new String[] {"0812345678", "+0812345678", "+243 81 234 5678", "tel:+243812345678"}) {
      error(
          json(
              post(PATH + "/recipients"),
              admin,
              key(),
              recipientBody("Lead", bad, "en", "LEAVE_DECISION")),
          400,
          "MESSAGING_PHONE_INVALID");
    }
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            recipientBody("Lead", newNumber(), "en", "CONNECTOR_TEST")),
        400,
        "MESSAGING_NOTIFICATION_TYPE_INVALID");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            recipientBody("Lead", newNumber(), "en", "LEAVE_DECISION", "LEAVE_DECISION")),
        400,
        "MESSAGING_NOTIFICATION_TYPE_INVALID");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            recipientBody("Lead", newNumber(), "en", "NOT_A_TYPE")),
        400,
        "MESSAGING_NOTIFICATION_TYPE_INVALID");
    mvc.perform(
            json(
                post(PATH + "/recipients"),
                admin,
                key(),
                recipientBody("see https://x.example", newNumber(), "en", "LEAVE_DECISION")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH + "/recipients"),
                admin,
                key(),
                recipientBody("Lead", newNumber(), "de", "LEAVE_DECISION")))
        .andExpect(status().isBadRequest());
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_recipient WHERE tenant_id = ?",
                tenant))
        .isZero();
  }

  @Test
  void aRecipientNeedsAConnectorAndAnumberIsRegisteredOnce() throws Exception {
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            recipientBody("Lead", newNumber(), "en", "LEAVE_DECISION")),
        404,
        "MESSAGING_CONNECTOR_NOT_FOUND");
    fixture.connect(admin, newToken());
    String number = newNumber();
    fixture.recipient(admin, number, "en", "LEAVE_DECISION");
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            recipientBody("Same", number, "fr", "TIMESHEET_APPROVED")),
        409,
        "MESSAGING_RECIPIENT_EXISTS");
  }

  @Test
  void aTenantMayRegisterTwentyFiveRecipientsAndNeverMore() throws Exception {
    fixture.connect(admin, newToken());
    for (int i = 0; i < 25; i++) {
      fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    }
    error(
        json(
            post(PATH + "/recipients"),
            admin,
            key(),
            recipientBody("One too many", newNumber(), "en", "LEAVE_DECISION")),
        409,
        "MESSAGING_RECIPIENT_LIMIT_REACHED");
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_recipient WHERE tenant_id = ?",
                tenant))
        .isEqualTo(25);
    assertThat(fixture.read(get(PATH + "/recipients").header("Authorization", admin)).get("items"))
        .hasSize(25);
  }

  @Test
  void recipientsChangeWithOptimisticVersionsAndDisableIsExplicit() throws Exception {
    fixture.activate(admin);
    JsonNode created = fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION");
    String id = created.get("id").asText();
    String update =
        "{\"version\":1,\"label\":\"Payroll lead"
            + " 2\",\"language\":\"fr\",\"notificationTypes\":[\"LEAVE_DECISION\",\"TIMESHEET_APPROVED\"],\"confirm\":true}";
    JsonNode updated =
        JSON.readTree(
            mvc.perform(json(patch(PATH + "/recipients/" + id), admin, key(), update))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(updated.get("version").asInt()).isEqualTo(2);
    assertThat(updated.get("language").asText()).isEqualTo("fr");
    assertThat(updated.get("notificationTypes")).hasSize(2);
    error(
        json(patch(PATH + "/recipients/" + id), admin, key(), update),
        409,
        "MESSAGING_RECIPIENT_VERSION_STALE");

    error(
        json(
            post(PATH + "/recipients/" + id + "/enable"),
            admin,
            key(),
            "{\"version\":2,\"confirm\":true}"),
        409,
        "MESSAGING_ALREADY_ENABLED");
    error(
        json(
            post(PATH + "/recipients/" + id + "/disable"),
            admin,
            key(),
            "{\"version\":1,\"confirm\":true}"),
        409,
        "MESSAGING_RECIPIENT_VERSION_STALE");
    mvc.perform(
            json(
                post(PATH + "/recipients/" + id + "/disable"),
                admin,
                key(),
                "{\"version\":2,\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"))
        .andExpect(jsonPath("$.version").value(3));
    error(
        json(
            post(PATH + "/recipients/" + id + "/disable"),
            admin,
            key(),
            "{\"version\":3,\"confirm\":true}"),
        409,
        "MESSAGING_ALREADY_DISABLED");
    error(
        json(
            post(PATH + "/recipients/" + id + "/test-deliveries"),
            admin,
            key(),
            "{\"confirm\":true}"),
        409,
        "MESSAGING_RECIPIENT_DISABLED");
    mvc.perform(
            json(
                post(PATH + "/recipients/" + id + "/enable"),
                admin,
                key(),
                "{\"version\":3,\"confirm\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  // ------------------------------------------------------------------------------------------
  // Test messages (D111-13)
  // ------------------------------------------------------------------------------------------

  @Test
  void aTestMessageNeedsAnActiveConnector() throws Exception {
    JsonNode connector = fixture.connect(admin, newToken());
    String id = fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    error(
        json(
            post(PATH + "/recipients/" + id + "/test-deliveries"),
            admin,
            key(),
            "{\"confirm\":true}"),
        409,
        "MESSAGING_CONNECTOR_INCOMPLETE");
    mvc.perform(
            json(
                post(PATH + "/enable"),
                admin,
                key(),
                confirm(connector.get("configVersion").asInt())))
        .andExpect(status().isOk());
    fixture.test(admin, id);
    mvc.perform(
            json(
                post(PATH + "/disable"),
                admin,
                key(),
                confirm(connector.get("configVersion").asInt())))
        .andExpect(status().isOk());
    error(
        json(
            post(PATH + "/recipients/" + id + "/test-deliveries"),
            admin,
            key(),
            "{\"confirm\":true}"),
        409,
        "MESSAGING_CONNECTOR_DISABLED");
    mvc.perform(json(post(PATH + "/recipients/" + id + "/test-deliveries"), admin, key(), "{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aRecipientAcceptsThreeTestsAnHourAndReplaysDoNotCount() throws Exception {
    fixture.activate(admin);
    String id = fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    String replayed = key();
    String first = null;
    for (int i = 0; i < 3; i++) {
      String response =
          mvc.perform(
                  json(
                      post(PATH + "/recipients/" + id + "/test-deliveries"),
                      admin,
                      i == 0 ? replayed : key(),
                      "{\"confirm\":true}"))
              .andExpect(status().isAccepted())
              .andReturn()
              .getResponse()
              .getContentAsString();
      if (i == 0) {
        first = response;
      }
    }
    String again =
        mvc.perform(
                json(
                    post(PATH + "/recipients/" + id + "/test-deliveries"),
                    admin,
                    replayed,
                    "{\"confirm\":true}"))
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JSON.readTree(again).get("deliveryId"))
        .isEqualTo(JSON.readTree(first).get("deliveryId"));
    mvc.perform(json(post(PATH + "/recipients/" + id + "/test-deliveries"), admin, key(), "{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            json(
                post(PATH + "/recipients/" + id + "/test-deliveries"),
                otherAdmin,
                key(),
                "{\"confirm\":true}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            json(
                post(PATH + "/recipients/" + id + "/test-deliveries"),
                admin,
                key(),
                "{\"confirm\":true}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "3600"))
        .andExpect(jsonPath("$.code").value("MESSAGING_TEST_RATE_LIMITED"));
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_delivery WHERE tenant_id = ? AND kind"
                    + " = 'TEST'",
                tenant))
        .isEqualTo(3);
  }

  @Test
  void aTenantAcceptsTenTestsAnHourWithTheSameErrorCode() throws Exception {
    fixture.activate(admin);
    List<String> recipients = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      recipients.add(
          fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText());
    }
    int accepted = 0;
    int refused = 0;
    for (int i = 0; i < 12; i++) {
      var response =
          mvc.perform(
                  json(
                      post(PATH + "/recipients/" + recipients.get(i % 4) + "/test-deliveries"),
                      admin,
                      key(),
                      "{\"confirm\":true}"))
              .andReturn()
              .getResponse();
      if (response.getStatus() == 202) {
        accepted++;
      } else {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(JSON.readTree(response.getContentAsString()).get("code").asText())
            .isEqualTo("MESSAGING_TEST_RATE_LIMITED");
        refused++;
      }
    }
    assertThat(accepted).isEqualTo(10);
    assertThat(refused).isEqualTo(2);
    // Another tenant is unaffected.
    fixture.activate(otherAdmin);
    String other =
        fixture.recipient(otherAdmin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    fixture.test(otherAdmin, other);
  }

  // ------------------------------------------------------------------------------------------
  // History
  // ------------------------------------------------------------------------------------------

  @Test
  void historyShowsClosedFieldsAndPagesByKeyset() throws Exception {
    fixture.activate(admin);
    String id = fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    for (int i = 0; i < 3; i++) {
      fixture.test(admin, id);
    }
    jobs.deliverDue();
    JsonNode firstPage =
        fixture.read(get(PATH + "/deliveries").param("limit", "2").header("Authorization", admin));
    assertThat(firstPage.get("items")).hasSize(2);
    assertThat(firstPage.get("nextCursor").isNull()).isFalse();
    JsonNode second =
        fixture.read(
            get(PATH + "/deliveries")
                .param("limit", "2")
                .param("cursor", firstPage.get("nextCursor").asText())
                .header("Authorization", admin));
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    JsonNode row = firstPage.get("items").get(0);
    assertThat(row.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "id",
            "recipientId",
            "recipientLabel",
            "numberMasked",
            "notificationType",
            "test",
            "language",
            "state",
            "attemptCount",
            "createdAt",
            "lastAttemptAt",
            "nextAttemptAt",
            "acceptedAt",
            "deliveredAt",
            "readAt",
            "completedAt",
            "lastHttpStatus",
            "failureClass");
    mvc.perform(get(PATH + "/deliveries").param("limit", "0").header("Authorization", admin))
        .andExpect(status().isBadRequest());
    mvc.perform(get(PATH + "/deliveries").param("limit", "1000").header("Authorization", admin))
        .andExpect(status().isBadRequest());
    mvc.perform(get(PATH + "/deliveries").param("cursor", "garbage").header("Authorization", admin))
        .andExpect(status().is4xxClientError());
    // A cursor bound to one tenant is useless to another.
    mvc.perform(
            get(PATH + "/deliveries")
                .param("cursor", firstPage.get("nextCursor").asText())
                .header("Authorization", otherAdmin))
        .andExpect(status().is4xxClientError());
    String detail =
        fixture
            .read(
                get(PATH + "/deliveries/" + row.get("id").asText()).header("Authorization", admin))
            .toString();
    assertThat(detail)
        .doesNotContain("wamid")
        .doesNotContain("template")
        .doesNotContain("http:")
        .doesNotContain("https:");
  }

  // ------------------------------------------------------------------------------------------
  // Audit and lifecycle events (D111-14)
  // ------------------------------------------------------------------------------------------

  @Test
  void commandsAndDisclosureReadsAreAuditedWithClosedMetadataAndFailClosed() throws Exception {
    String token = newToken();
    String number = newNumber();
    JsonNode created = fixture.connect(admin, token);
    fixture.recipient(admin, number, "en", "LEAVE_DECISION");
    mvc.perform(get(PATH).header("Authorization", admin)).andExpect(status().isOk());
    mvc.perform(get(PATH + "/recipients").header("Authorization", admin))
        .andExpect(status().isOk());
    for (String action :
        new String[] {
          "messaging.whatsapp.create",
          "messaging.whatsapp.recipient-create",
          "messaging.whatsapp.read"
        }) {
      assertThat(
              count(
                  "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action = ?",
                  tenant,
                  action))
          .as(action)
          .isGreaterThanOrEqualTo(1);
    }
    String metadata =
        jdbc.queryForObject(
            "SELECT string_agg(metadata::text, ' ') FROM platform.audit_event WHERE tenant_id = ?",
            String.class,
            tenant);
    assertThat(metadata)
        .doesNotContain(token)
        .doesNotContain(number)
        .doesNotContain(created.get("wabaId").asText())
        .doesNotContain(created.get("phoneNumberId").asText())
        .doesNotContain("divalhr_leave_decision")
        .doesNotContain("http")
        .doesNotContain("Payroll lead");
    String events =
        jdbc.queryForObject(
            "SELECT string_agg(envelope::text, ' ') FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'integrations.messaging-%'",
            String.class, tenant);
    assertThat(events)
        .contains("integrations.messaging-connector.created.v1")
        .contains("integrations.messaging-recipient.created.v1");
    assertThat(events)
        .doesNotContain(token)
        .doesNotContain(number)
        .doesNotContain(created.get("wabaId").asText())
        .doesNotContain(created.get("phoneNumberId").asText())
        .doesNotContain("Payroll lead");

    jdbc.execute(
        "CREATE OR REPLACE FUNCTION platform.test_fail_msg_audit() RETURNS trigger LANGUAGE plpgsql"
            + " AS $$ BEGIN RAISE EXCEPTION 'audit down'; END $$");
    jdbc.execute(
        "CREATE TRIGGER test_fail_msg_audit BEFORE INSERT ON platform.audit_event FOR EACH ROW"
            + " EXECUTE FUNCTION platform.test_fail_msg_audit()");
    try {
      mvc.perform(get(PATH).header("Authorization", admin)).andExpect(status().is5xxServerError());
      mvc.perform(
              json(
                  post(PATH + "/recipients"),
                  admin,
                  key(),
                  recipientBody("Nope", newNumber(), "en", "LEAVE_DECISION")))
          .andExpect(status().is5xxServerError());
      assertThat(
              count(
                  "SELECT count(*) FROM integrations.messaging_recipient WHERE tenant_id = ?",
                  tenant))
          .isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS test_fail_msg_audit ON platform.audit_event");
      jdbc.execute("DROP FUNCTION IF EXISTS platform.test_fail_msg_audit()");
    }
  }

  @Test
  void disablingTheConnectorOrARecipientCancelsQueuedWorkAndStopsSending() throws Exception {
    JsonNode active = fixture.activate(admin);
    String keepNumber = newNumber();
    String dropNumber = newNumber();
    String keep = fixture.recipient(admin, keepNumber, "en", "LEAVE_DECISION").get("id").asText();
    String drop = fixture.recipient(admin, dropNumber, "en", "LEAVE_DECISION").get("id").asText();
    UUID kept = fixture.test(admin, keep);
    UUID dropped = fixture.test(admin, drop);
    mvc.perform(
            json(
                post(PATH + "/recipients/" + drop + "/disable"),
                admin,
                key(),
                "{\"version\":1,\"confirm\":true}"))
        .andExpect(status().isOk());
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_delivery WHERE id = ? AND state ="
                    + " 'CANCELLED'",
                dropped))
        .isEqualTo(1);
    mvc.perform(
            json(
                post(PATH + "/disable"),
                admin,
                key(),
                confirm(active.get("configVersion").asInt())))
        .andExpect(status().isOk());
    assertThat(
            count(
                "SELECT count(*) FROM integrations.messaging_delivery WHERE id = ? AND state ="
                    + " 'CANCELLED'",
                kept))
        .isEqualTo(1);
    jobs.deliverDue();
    // Other tests' queued work may be sent by the same worker; only this test's numbers matter.
    assertThat(provider.sent())
        .noneMatch(sent -> sent.to().equals(keepNumber) || sent.to().equals(dropNumber));
  }
}
