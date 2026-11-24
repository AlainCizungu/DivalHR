package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.integrations.PartnerReferralWorld.Employee;
import com.divalhr.core.integrations.PartnerReferralWorld.Product;
import com.divalhr.core.integrations.partner.referral.PartnerReferralDelivery;
import com.divalhr.core.integrations.partner.referral.SyntheticReferralProvider;
import com.divalhr.core.integrations.partner.referral.SyntheticReferralProvider.Mode;
import com.divalhr.core.integrations.partner.referral.SyntheticReferralProvider.Received;
import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-120F (Issue #224, D120F-6/7): delivery to the controlled synthetic provider against real
 * PostgreSQL. The signed payload is exactly the previewed disclosure; a receipt, an uncertain
 * answer, a failure after sending or an expired lease is never sent again; a withdrawn referral is
 * never sent and a claimed attempt cannot be withdrawn; retries are bounded. No real partner, no
 * decision and no money movement exist anywhere in this flow. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-referral-read=600",
      "divalhr.request-limits.subject.partner-referral-preview=600",
      "divalhr.request-limits.subject.partner-referral-commit=600",
      "divalhr.request-limits.subject.partner-referral-withdraw=600"
    })
class PartnerReferralDeliveryIntegrationTest {

  private static final String ME = "/api/v1/me/partner-referrals";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PartnerReferralDelivery delivery;
  @Autowired private SyntheticReferralProvider provider;

  @BeforeEach
  void settle() {
    provider.reset();
    drain();
    provider.reset();
  }

  /** Runs delivery cycles until nothing is due. */
  private void drain() {
    for (int i = 0; i < 40 && delivery.runOnce() > 0; i++) {
      // keep going
    }
  }

  private record Fixture(Employee employee, Product product) {}

  private Fixture fixture() {
    UUID tenant =
        PartnerReferralWorld.organization(jdbc, "CD", "Synthetic Employer " + UUID.randomUUID());
    Product product = PartnerReferralWorld.product(jdbc, "ACTIVE", "ACTIVE", List.of("CD"));
    return new Fixture(
        PartnerReferralWorld.employee(jdbc, tenant, "Amina", "Kabila", "amina@example.test"),
        product);
  }

  private String submit(Fixture f, String phone) throws Exception {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("productId", f.product().reference());
    in.put("language", "en");
    in.put("includePhone", phone != null);
    in.put("phone", phone);
    in.put("includeOrganizationName", false);
    JsonNode preview =
        body(
            mvc.perform(
                    post(ME + "/preview")
                        .header("Authorization", f.employee().bearer())
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
    MockHttpServletResponse created =
        mvc.perform(
                post(ME)
                    .header("Authorization", f.employee().bearer())
                    .header("Idempotency-Key", "synthetic-key-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PartnerWorld.JSON.writeValueAsString(commit)))
            .andReturn()
            .getResponse();
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    return body(created).path("referralId").asText();
  }

  private JsonNode view(Fixture f, String reference) throws Exception {
    return body(PartnerWorld.read(mvc, f.employee().bearer(), ME + "/" + reference));
  }

  private MockHttpServletResponse withdraw(Fixture f, String reference, int version)
      throws Exception {
    return mvc.perform(
            post(ME + "/" + reference + "/withdraw")
                .header("Authorization", f.employee().bearer())
                .header("Idempotency-Key", "synthetic-key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    PartnerWorld.JSON.writeValueAsString(
                        Map.of("expectedVersion", version, "confirmed", true))))
        .andReturn()
        .getResponse();
  }

  private List<Received> receivedFor(String reference) {
    return provider.received().stream().filter(r -> r.reference().equals(reference)).toList();
  }

  private List<String> states(JsonNode view) {
    List<String> states = new ArrayList<>();
    view.path("events").forEach(e -> states.add(e.path("toState").asText()));
    return states;
  }

  private void makeDue(String reference) {
    PartnerReferralWorld.withoutTriggers(
        jdbc,
        "UPDATE integrations.partner_referral SET next_attempt_at = now() - interval '1 minute'"
            + " WHERE reference = '"
            + reference
            + "' AND state = 'DELIVERY_PENDING' AND lease_until IS NULL;");
  }

  private List<String> attemptOutcomes(String reference) {
    return jdbc.queryForList(
        "SELECT a.outcome FROM integrations.partner_referral_attempt a"
            + " JOIN integrations.partner_referral r ON r.id = a.referral_id AND r.tenant_id = a.tenant_id"
            + " WHERE r.reference = ? ORDER BY a.attempt_number",
        String.class,
        reference);
  }

  @Test
  void aReferralIsDeliveredOnceWithExactlyThePreviewedDisclosureAndNeverResent() throws Exception {
    Fixture f = fixture();
    String reference = submit(f, "+243810000000");
    JsonNode before = view(f, reference);
    drain();

    JsonNode after = view(f, reference);
    assertThat(after.path("state").asText()).isEqualTo("DELIVERED");
    assertThat(states(after)).containsExactly("SUBMITTED", "DELIVERY_PENDING", "DELIVERED");
    assertThat(after.path("lastOutcome").asText()).isEqualTo("PROVIDER_ACKNOWLEDGED");
    assertThat(after.path("withdrawable").asBoolean()).isFalse();
    assertThat(attemptOutcomes(reference)).containsExactly("PROVIDER_ACKNOWLEDGED");
    assertThat(
            jdbc.queryForObject(
                "SELECT a.provider_reference FROM integrations.partner_referral_ack a"
                    + " JOIN integrations.partner_referral r ON r.id = a.referral_id AND r.tenant_id = a.tenant_id"
                    + " WHERE r.reference = ?",
                String.class,
                reference))
        .startsWith("SYN-");

    List<Received> received = receivedFor(reference);
    assertThat(received).hasSize(1);
    assertThat(received.get(0).signatureValid()).isTrue();
    JsonNode payload = PartnerWorld.JSON.readTree(received.get(0).payload());
    List<String> sent = new ArrayList<>();
    payload.fieldNames().forEachRemaining(sent::add);
    List<String> disclosed = new ArrayList<>();
    before.path("disclosedFields").forEach(x -> disclosed.add(x.asText()));
    assertThat(sent).containsExactlyElementsOf(disclosed);
    assertThat(payload.path("REFERRAL_REFERENCE").asText()).isEqualTo(reference);
    assertThat(payload.path("EMPLOYEE_EMAIL").asText()).isEqualTo("amina@example.test");
    assertThat(payload.path("EMPLOYEE_PHONE").asText()).isEqualTo("+243810000000");
    assertThat(received.get(0).payload()).doesNotContain("Synthetic Employer");

    MockHttpServletResponse refused = withdraw(f, reference, after.path("version").asInt());
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(code(refused)).isEqualTo("PARTNER_REFERRAL_NOT_WITHDRAWABLE");
    drain();
    assertThat(receivedFor(reference)).hasSize(1);

    String trail =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg(metadata::text, ' '), '') FROM platform.audit_event"
                + " WHERE action = 'partner-referral.delivery' AND resource_id ="
                + " (SELECT id FROM integrations.partner_referral WHERE reference = ?)",
            String.class,
            reference);
    assertThat(trail).isNotBlank();
    assertThat(trail).doesNotContain("amina@example.test").doesNotContain("+243810000000");
  }

  @Test
  void aReferralThatWasNotSentIsRetriedLaterAndStaysWithdrawableMeanwhile() throws Exception {
    Fixture f = fixture();
    String reference = submit(f, null);
    provider.mode(Mode.NOT_SENT);
    drain();
    JsonNode waiting = view(f, reference);
    assertThat(waiting.path("state").asText()).isEqualTo("DELIVERY_PENDING");
    assertThat(waiting.path("lastOutcome").asText()).isEqualTo("NOT_SENT_RETRYING");
    assertThat(waiting.path("withdrawable").asBoolean()).isTrue();
    assertThat(receivedFor(reference)).isEmpty();

    provider.mode(Mode.ACCEPT);
    drain(); // not due yet: nothing is claimed
    assertThat(view(f, reference).path("state").asText()).isEqualTo("DELIVERY_PENDING");

    makeDue(reference);
    drain();
    JsonNode delivered = view(f, reference);
    assertThat(delivered.path("state").asText()).isEqualTo("DELIVERED");
    assertThat(attemptOutcomes(reference))
        .containsExactly("NOT_SENT_RETRYING", "PROVIDER_ACKNOWLEDGED");
    assertThat(receivedFor(reference)).hasSize(1);
  }

  @Test
  void retriesAreBoundedAndAFailedReferralCanStillBeWithdrawn() throws Exception {
    Fixture f = fixture();
    String reference = submit(f, null);
    provider.mode(Mode.NOT_SENT);
    for (int attempt = 1; attempt <= 6; attempt++) {
      drain();
      makeDue(reference);
    }
    JsonNode failed = view(f, reference);
    assertThat(failed.path("state").asText()).isEqualTo("DELIVERY_FAILED");
    assertThat(failed.path("lastOutcome").asText()).isEqualTo("RETRIES_EXHAUSTED");
    assertThat(attemptOutcomes(reference)).hasSize(6).last().isEqualTo("RETRIES_EXHAUSTED");
    assertThat(failed.path("withdrawable").asBoolean()).isTrue();

    MockHttpServletResponse withdrawn = withdraw(f, reference, failed.path("version").asInt());
    assertThat(withdrawn.getStatus()).as(withdrawn.getContentAsString()).isEqualTo(200);
    provider.mode(Mode.ACCEPT);
    drain();
    assertThat(receivedFor(reference)).isEmpty();
    assertThat(view(f, reference).path("state").asText()).isEqualTo("WITHDRAWN");
  }

  @Test
  void anUncertainOrFailedSendIsNeverRepeatedAndCannotBeWithdrawn() throws Exception {
    for (Mode mode : List.of(Mode.UNCERTAIN, Mode.FAIL)) {
      Fixture f = fixture();
      String reference = submit(f, null);
      provider.mode(mode);
      drain();
      JsonNode unknown = view(f, reference);
      assertThat(unknown.path("state").asText()).as(mode.name()).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(unknown.path("lastOutcome").asText()).isEqualTo("UNCERTAIN_NOT_RESENT");
      assertThat(unknown.path("withdrawable").asBoolean()).isFalse();
      assertThat(receivedFor(reference)).hasSize(1);

      provider.mode(Mode.ACCEPT);
      makeDue(reference); // no effect: nothing is due for an uncertain referral
      drain();
      assertThat(receivedFor(reference)).as(mode.name()).hasSize(1);
      assertThat(view(f, reference).path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
      MockHttpServletResponse refused = withdraw(f, reference, unknown.path("version").asInt());
      assertThat(refused.getStatus()).isEqualTo(409);
      assertThat(code(refused)).isEqualTo("PARTNER_REFERRAL_NOT_WITHDRAWABLE");
    }
  }

  @Test
  void aRejectedReferralFailsWithoutResendAndMayBeWithdrawn() throws Exception {
    Fixture f = fixture();
    String reference = submit(f, null);
    provider.mode(Mode.REJECT);
    drain();
    JsonNode failed = view(f, reference);
    assertThat(failed.path("state").asText()).isEqualTo("DELIVERY_FAILED");
    assertThat(failed.path("lastOutcome").asText()).isEqualTo("REJECTED_BY_PROVIDER");
    provider.mode(Mode.ACCEPT);
    drain();
    assertThat(receivedFor(reference)).hasSize(1);
    assertThat(withdraw(f, reference, failed.path("version").asInt()).getStatus()).isEqualTo(200);
  }

  @Test
  void aWithdrawnReferralIsNeverSent() throws Exception {
    Fixture f = fixture();
    String reference = submit(f, null);
    assertThat(withdraw(f, reference, 1).getStatus()).isEqualTo(200);
    drain();
    assertThat(receivedFor(reference)).isEmpty();
    JsonNode view = view(f, reference);
    assertThat(view.path("state").asText()).isEqualTo("WITHDRAWN");
    assertThat(attemptOutcomes(reference)).isEmpty();
  }

  @Test
  void aClaimedAttemptCannotBeWithdrawnAndAnExpiredLeaseIsUncertainAndNeverSent() throws Exception {
    Fixture f = fixture();
    String reference = submit(f, null);
    PartnerReferralDelivery.Claimed claimed = delivery.claim();
    assertThat(claimed).isNotNull();
    assertThat(claimed.row().reference()).isEqualTo(reference);
    JsonNode inFlight = view(f, reference);
    assertThat(inFlight.path("state").asText()).isEqualTo("DELIVERY_PENDING");
    assertThat(inFlight.path("withdrawable").asBoolean()).isFalse();
    MockHttpServletResponse refused = withdraw(f, reference, inFlight.path("version").asInt());
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(code(refused)).isEqualTo("PARTNER_REFERRAL_NOT_WITHDRAWABLE");

    PartnerReferralWorld.withoutTriggers(
        jdbc,
        "UPDATE integrations.partner_referral SET lease_until = now() - interval '1 minute'"
            + " WHERE reference = '"
            + reference
            + "';");
    drain();
    JsonNode unknown = view(f, reference);
    assertThat(unknown.path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
    assertThat(attemptOutcomes(reference)).containsExactly("UNCERTAIN_NOT_RESENT");

    delivery.deliver(claimed); // a slow worker finds its attempt already resolved and sends nothing
    assertThat(receivedFor(reference)).isEmpty();
    assertThat(view(f, reference).path("state").asText()).isEqualTo("DELIVERY_UNKNOWN");
  }
}
