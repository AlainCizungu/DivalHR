package com.divalhr.core.integrations.messaging;

import static com.divalhr.core.integrations.messaging.MessagingFixture.CALLBACK;
import static com.divalhr.core.integrations.messaging.MessagingFixture.newNumber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.integrations.messaging.MessagingTestConfig.ScriptedProvider;
import com.divalhr.core.integrations.messaging.application.MessagingJobs;
import com.divalhr.core.integrations.messaging.domain.MetaSignature;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-111 (D111-10): the provider callback boundary. The only unauthenticated door authenticates
 * the provider by signature over the raw bytes, is bounded, and changes state only forward.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import(MessagingTestConfig.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.messaging-write=600",
      "divalhr.request-limits.subject.messaging-read=600",
      "divalhr.messaging.jitter=false",
      "divalhr.messaging.callback-max-bytes=4096",
      "divalhr.messaging.callback-requests-per-minute=1000"
    })
class MessagingCallbackIntegrationTest {

  private static final String SECRET = "test-only-messaging-app-secret-0001";
  private static final String VERIFY_TOKEN = "test-only-messaging-verify-token-01";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ScriptedProvider provider;
  @Autowired private MessagingJobs jobs;

  private MessagingFixture fixture;
  private UUID tenant;
  private String admin;
  private String phoneNumberId;
  private UUID delivery;
  private String wamid;

  @BeforeEach
  void acceptedDelivery() throws Exception {
    provider.reset();
    fixture = new MessagingFixture(mvc);
    tenant = Hierarchy.newTenant(mvc);
    admin = Hierarchy.bearer(tenant, "sub-cb-" + UUID.randomUUID(), "tenant-admin");
    fixture.activate(admin);
    phoneNumberId = fixture.connector(admin).get("phoneNumberId").asText();
    String recipient =
        fixture.recipient(admin, newNumber(), "en", "LEAVE_DECISION").get("id").asText();
    delivery = fixture.test(admin, recipient);
    jobs.deliverDue();
    wamid =
        jdbc.queryForObject(
            "SELECT provider_message_id FROM integrations.messaging_delivery WHERE id = ?",
            String.class,
            delivery);
    assertThat(wamid).startsWith("wamid.");
  }

  private static String sign(String body) {
    return MetaSignature.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
  }

  private static String statusBody(
      String phone, String id, String status, Object timestamp, String extra) {
    return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"1\",\"changes\":[{\"field\":\"messages\",\"value\":{"
        + "\"messaging_product\":\"whatsapp\",\"metadata\":{\"display_phone_number\":\"243812000111\",\"phone_number_id\":\""
        + phone
        + "\"},\"statuses\":[{\"id\":\""
        + id
        + "\",\"status\":\""
        + status
        + "\",\"timestamp\":\""
        + timestamp
        + "\",\"recipient_id\":\"243812999888\""
        + extra
        + "}]}}]}]}";
  }

  private String body(String status) {
    return statusBody(phoneNumberId, wamid, status, Instant.now().getEpochSecond(), "");
  }

  private int send(String body, String signature) throws Exception {
    var request = post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content(body);
    if (signature != null) {
      request = request.header("X-Hub-Signature-256", signature);
    }
    return mvc.perform(request)
        .andExpect(header().string("Cache-Control", "no-store"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String state() {
    return jdbc.queryForObject(
        "SELECT state FROM integrations.messaging_delivery WHERE id = ?", String.class, delivery);
  }

  private int callbacks() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM integrations.messaging_status_callback WHERE delivery_id = ?",
            Integer.class,
            delivery);
    return count == null ? 0 : count;
  }

  @Test
  void theHandshakeEchoesTheChallengeOnlyForTheRightModeAndToken() throws Exception {
    mvc.perform(
            get(CALLBACK)
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", VERIFY_TOKEN)
                .param("hub.challenge", "1158201444"))
        .andExpect(status().isOk())
        .andExpect(content().string("1158201444"))
        .andExpect(header().string("Cache-Control", "no-store"));
    for (String[] bad :
        new String[][] {
          {"subscribe", "wrong-token", "1158201444"},
          {"unsubscribe", VERIFY_TOKEN, "1158201444"},
          {"subscribe", VERIFY_TOKEN, "<script>alert(1)</script>"},
          {"subscribe", VERIFY_TOKEN, "x".repeat(300)}
        }) {
      mvc.perform(
              get(CALLBACK)
                  .param("hub.mode", bad[0])
                  .param("hub.verify_token", bad[1])
                  .param("hub.challenge", bad[2]))
          .andExpect(status().isForbidden())
          .andExpect(content().string(""));
    }
    mvc.perform(get(CALLBACK)).andExpect(status().isForbidden());
  }

  @Test
  void aMissingMalformedOrWrongSignatureIsRefusedAndChangesNothing() throws Exception {
    String body = body("DELIVERED");
    assertThat(send(body, null)).isEqualTo(403);
    assertThat(send(body, "sha256=")).isEqualTo(403);
    assertThat(send(body, "sha256=" + "0".repeat(64))).isEqualTo(403);
    assertThat(send(body, sign(body).substring(7))).isEqualTo(403);
    assertThat(send(body, sign(body + " "))).isEqualTo(403);
    assertThat(
            send(
                body,
                MetaSignature.sign(
                    "another-secret-another-secret-0000", body.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo(403);
    assertThat(state()).isEqualTo("ACCEPTED");
    assertThat(callbacks()).isZero();
  }

  @Test
  void anOversizeBodyIsRefusedBeforeItsSignatureOrContentIsRead() throws Exception {
    String body =
        statusBody(phoneNumberId, wamid, "DELIVERED", 1, ",\"pad\":\"" + "a".repeat(5000) + "\"");
    assertThat(send(body, sign(body))).isEqualTo(413);
    assertThat(state()).isEqualTo("ACCEPTED");
  }

  @Test
  void aSignedStatusMovesTheDeliveryForwardOnly() throws Exception {
    String delivered = body("delivered");
    assertThat(send(delivered, sign(delivered))).isEqualTo(200);
    assertThat(state()).isEqualTo("DELIVERED");
    assertThat(
            jdbc.queryForObject(
                "SELECT delivered_at IS NOT NULL FROM integrations.messaging_delivery WHERE id = ?",
                Boolean.class,
                delivery))
        .isTrue();
    // The same status again is a duplicate: acknowledged, recorded once.
    assertThat(send(delivered, sign(delivered))).isEqualTo(200);
    assertThat(callbacks()).isEqualTo(1);
    // A late "sent" never moves it backward.
    String sent = body("sent");
    assertThat(send(sent, sign(sent))).isEqualTo(200);
    assertThat(state()).isEqualTo("DELIVERED");
    String read = body("read");
    assertThat(send(read, sign(read))).isEqualTo(200);
    assertThat(state()).isEqualTo("READ");
    // A late "delivered" after READ is also recorded without effect.
    String again =
        statusBody(phoneNumberId, wamid, "DELIVERED", Instant.now().getEpochSecond() + 1, "");
    assertThat(send(again, sign(again))).isEqualTo(200);
    assertThat(state()).isEqualTo("READ");
  }

  @Test
  void readImpliesDeliveredEvenWhenTheDeliveredCallbackNeverArrives() throws Exception {
    String read = body("read");
    assertThat(send(read, sign(read))).isEqualTo(200);
    assertThat(state()).isEqualTo("READ");
    assertThat(
            jdbc.queryForObject(
                "SELECT delivered_at IS NOT NULL AND read_at IS NOT NULL FROM"
                    + " integrations.messaging_delivery WHERE id = ?",
                Boolean.class,
                delivery))
        .isTrue();
  }

  @Test
  void aFailedCallbackFailsAnAcceptedDeliveryWithAClosedClassAndNoProviderText() throws Exception {
    String failed =
        statusBody(
            phoneNumberId,
            wamid,
            "failed",
            Instant.now().getEpochSecond(),
            ",\"errors\":[{\"code\":131026,\"title\":\"Undeliverable secret"
                + " title\",\"message\":\"raw provider text\"}]");
    assertThat(send(failed, sign(failed))).isEqualTo(200);
    assertThat(state()).isEqualTo("FAILED");
    String stored =
        jdbc.queryForObject(
            "SELECT s::text FROM integrations.messaging_status_callback s WHERE delivery_id = ?",
            String.class,
            delivery);
    assertThat(stored)
        .doesNotContain("secret title")
        .doesNotContain("raw provider text")
        .contains("RECIPIENT_REJECTED");
  }

  @Test
  void aFailedCallbackWithAnUnlistedOrThrottleCodeStillFailsTheDeliveryAsAProviderFailure()
      throws Exception {
    String failed =
        statusBody(
            phoneNumberId,
            wamid,
            "failed",
            Instant.now().getEpochSecond(),
            ",\"errors\":[{\"code\":999999}]");
    assertThat(send(failed, sign(failed))).isEqualTo(200);
    assertThat(state()).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT error_class FROM integrations.messaging_status_callback WHERE delivery_id ="
                    + " ?",
                String.class,
                delivery))
        .isEqualTo("PROVIDER_FAILED");
  }

  @Test
  void anUnknownMessageOrAWrongPhoneNumberBindingIsAcknowledgedWithoutEffect() throws Exception {
    String unknown =
        statusBody(phoneNumberId, "wamid.unknown-" + UUID.randomUUID(), "DELIVERED", 1, "");
    assertThat(send(unknown, sign(unknown))).isEqualTo(200);
    String wrongBinding =
        statusBody("55555555555", wamid, "DELIVERED", Instant.now().getEpochSecond(), "");
    assertThat(send(wrongBinding, sign(wrongBinding))).isEqualTo(200);
    assertThat(state()).isEqualTo("ACCEPTED");
    assertThat(callbacks()).isZero();
  }

  @Test
  void aMessageOfAnotherTenantsConnectorCannotBeMovedThroughThisOnesBinding() throws Exception {
    UUID other = Hierarchy.newTenant(mvc);
    String otherAdmin = Hierarchy.bearer(other, "sub-cb2-" + UUID.randomUUID(), "tenant-admin");
    fixture.activate(otherAdmin);
    String otherPhone = fixture.connector(otherAdmin).get("phoneNumberId").asText();
    assertThat(otherPhone).isNotEqualTo(phoneNumberId);
    String crossed = statusBody(otherPhone, wamid, "READ", Instant.now().getEpochSecond(), "");
    assertThat(send(crossed, sign(crossed))).isEqualTo(200);
    assertThat(state()).isEqualTo("ACCEPTED");
  }

  @Test
  void inboundCustomerMessagesAreIgnoredWithoutBeingReadOrStored(CapturedOutput output)
      throws Exception {
    String inbound =
        "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"field\":\"messages\",\"value\":{"
            + "\"metadata\":{\"phone_number_id\":\""
            + phoneNumberId
            + "\"},\"contacts\":[{\"profile\":{\"name\":\"Inbound Person"
            + " Name\"},\"wa_id\":\"243812999888\"}],\"messages\":[{\"from\":\"243812999888\",\"id\":\"wamid.inbound-1234\",\"text\":{\"body\":\"private"
            + " customer text\"}}]}}]}]}";
    assertThat(send(inbound, sign(inbound))).isEqualTo(200);
    assertThat(callbacks()).isZero();
    assertThat(output.getAll())
        .doesNotContain("private customer text")
        .doesNotContain("Inbound Person Name");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM integrations.messaging_delivery WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isEqualTo(1);
  }

  @Test
  void aSignedButUnusableBodyIsAcknowledgedSoTheProviderDoesNotRetryForever() throws Exception {
    for (String garbage :
        new String[] {
          "not json", "[]", "{}", "{\"entry\":\"x\"}", "{\"entry\":[1,null,{\"changes\":5}]}"
        }) {
      assertThat(send(garbage, sign(garbage))).isEqualTo(200);
    }
    assertThat(state()).isEqualTo("ACCEPTED");
  }

  @Test
  void aTimestampFromTheFutureIsReplacedByTheReceiptTime() throws Exception {
    long future = Instant.now().plusSeconds(86_400L * 365).getEpochSecond();
    String delivered = statusBody(phoneNumberId, wamid, "DELIVERED", future, "");
    assertThat(send(delivered, sign(delivered))).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT occurred_at < now() + interval '10 minutes' FROM"
                    + " integrations.messaging_status_callback WHERE delivery_id = ?",
                Boolean.class,
                delivery))
        .isTrue();
  }

  @Test
  void nothingOfTheRequestIsLogged(CapturedOutput output) throws Exception {
    String delivered = body("delivered");
    send(delivered, sign(delivered));
    assertThat(output.getAll())
        .doesNotContain(wamid)
        .doesNotContain(SECRET)
        .doesNotContain(VERIFY_TOKEN)
        .doesNotContain("243812999888");
  }

  @Test
  void aCallbackNeedsNoCredentialAndNoOtherEndpointIsOpenWithoutOne() throws Exception {
    mvc.perform(post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get(MessagingFixture.PATH)).andExpect(status().isUnauthorized());
  }
}
