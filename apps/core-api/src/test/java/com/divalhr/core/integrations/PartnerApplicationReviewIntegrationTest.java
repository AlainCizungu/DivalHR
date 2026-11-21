package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.PLATFORM;
import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
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
 * MVP-120D (Issue #215, ADR 0043): the platform review. The queue and its filters, the detail with
 * decrypted contact data, the immutable history, the closed state machine, the approval gate (also
 * under concurrency), terminal immutability, safe 404s, and the proof that no decision creates
 * access of any kind. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.partner-application-read=600",
      "divalhr.request-limits.subject.partner-application-command=600",
      "divalhr.partner-application.email-attempts-per-hour=1000"
    })
class PartnerApplicationReviewIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private final String admin = PartnerWorld.admin();

  private String submitted(Map<String, Object> application) throws Exception {
    return body(PartnerWorld.submit(mvc, application)).path("reference").asText();
  }

  private String status(String reference) {
    return jdbc.queryForObject(
        "SELECT status FROM integrations.partner_application WHERE reference = ?",
        String.class,
        reference);
  }

  @Test
  void theQueueListsFiltersAndPagesWithoutConfidentialData() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    Map<String, Object> first = PartnerWorld.bank(PartnerWorld.uniqueName(), email);
    Map<String, Object> second =
        PartnerWorld.insurer(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail());
    String one = submitted(first);
    String two = submitted(second);

    MockHttpServletResponse page = PartnerWorld.read(mvc, admin, PLATFORM + "?limit=1");
    assertThat(page.getStatus()).isEqualTo(200);
    assertThat(page.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode list = body(page);
    assertThat(list.path("items")).hasSize(1);
    assertThat(list.path("nextCursor").isTextual()).isTrue();
    assertThat(list.path("statusCounts").path("SUBMITTED").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(page.getContentAsString())
        .doesNotContain(email)
        .doesNotContain(String.valueOf(first.get("registrationReference")))
        .doesNotContain(String.valueOf(first.get("licenceReference")))
        .doesNotContain("serviceSummary");
    String cursor = list.path("nextCursor").asText();
    String payload =
        new String(Base64.getUrlDecoder().decode(cursor.substring(0, cursor.indexOf('.'))));
    assertThat(payload)
        .doesNotContain(email)
        .doesNotContain(String.valueOf(first.get("legalName")));

    // The newest comes first; the second page differs and a changed filter refuses the cursor.
    assertThat(list.path("items").get(0).path("reference").asText()).isEqualTo(two);
    JsonNode next = body(PartnerWorld.read(mvc, admin, PLATFORM + "?limit=1&cursor=" + cursor));
    assertThat(next.path("items").get(0).path("reference").asText()).isEqualTo(one);
    MockHttpServletResponse rebound =
        PartnerWorld.read(mvc, admin, PLATFORM + "?limit=1&status=SUBMITTED&cursor=" + cursor);
    assertThat(rebound.getStatus()).isEqualTo(400);
    assertThat(code(rebound)).isEqualTo("CURSOR_INVALID");

    JsonNode insurance =
        body(PartnerWorld.read(mvc, admin, PLATFORM + "?category=INSURANCE&limit=50"));
    assertThat(references(insurance)).contains(two).doesNotContain(one);
    JsonNode capability =
        body(PartnerWorld.read(mvc, admin, PLATFORM + "?capability=HEALTH_INSURANCE&limit=50"));
    assertThat(references(capability)).contains(two).doesNotContain(one);
    JsonNode additional =
        body(PartnerWorld.read(mvc, admin, PLATFORM + "?category=TELECOM&limit=50"));
    assertThat(references(additional)).contains(two).doesNotContain(one);
    JsonNode country = body(PartnerWorld.read(mvc, admin, PLATFORM + "?country=CD&limit=50"));
    assertThat(references(country)).contains(one, two);
    JsonNode none = body(PartnerWorld.read(mvc, admin, PLATFORM + "?country=ZZ&limit=50"));
    assertThat(none.path("items")).isEmpty();
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "?status=NOPE").getStatus()).isEqualTo(400);
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "?limit=51").getStatus()).isEqualTo(400);
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "?submittedFrom=yesterday").getStatus())
        .isEqualTo(400);
  }

  @Test
  void theDetailDecryptsContactDataAndExplainsWhatBlocksApproval() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    Map<String, Object> application = PartnerWorld.bank(PartnerWorld.uniqueName(), email);
    String reference = submitted(application);

    MockHttpServletResponse response = PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference);
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode detail = body(response);
    assertThat(detail.path("status").asText()).isEqualTo("SUBMITTED");
    assertThat(detail.path("contact").path("businessEmail").asText()).isEqualTo(email);
    assertThat(detail.path("contact").path("registrationReference").asText())
        .isEqualTo(application.get("registrationReference"));
    assertThat(detail.path("consent").path("consentVersion").asText()).isEqualTo("2026-10");
    assertThat(detail.path("checklist")).hasSize(7);
    assertThat(detail.path("allowedTransitions"))
        .extracting(JsonNode::asText)
        .containsExactly("UNDER_REVIEW", "WITHDRAWN");
    assertThat(detail.path("approvalBlockedBy")).hasSize(6);
    assertThat(detail.has("id")).isFalse();
    assertThat(detail.path("possibleDuplicates").asInt()).isZero();

    MockHttpServletResponse history =
        PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference + "/history");
    assertThat(history.getStatus()).isEqualTo(200);
    JsonNode items = body(history).path("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).path("eventType").asText()).isEqualTo("SUBMITTED");
    assertThat(items.get(0).path("actorType").asText()).isEqualTo("APPLICANT");
    assertThat(history.getContentAsString()).doesNotContain(email).doesNotContain("contact");
  }

  @Test
  void unknownMalformedAndMisspelledTargetsAreTheSameNotFound() throws Exception {
    String reference =
        submitted(PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
    List<MockHttpServletResponse> answers =
        List.of(
            PartnerWorld.read(mvc, admin, PLATFORM + "/PA-AAAAAAAAAAAA"),
            PartnerWorld.read(mvc, admin, PLATFORM + "/not-a-reference"),
            PartnerWorld.read(mvc, admin, PLATFORM + "/PA-AAAAAAAAAAAA/history"),
            PartnerWorld.setCheck(mvc, admin, reference, "NO_SUCH_CHECK", "SATISFIED", 0, null),
            PartnerWorld.setCheck(
                mvc, admin, "PA-AAAAAAAAAAAA", "BUSINESS_IDENTITY", "SATISFIED", 0, null),
            PartnerWorld.transition(mvc, admin, "PA-AAAAAAAAAAAA", "UNDER_REVIEW", 0, null, false));
    for (MockHttpServletResponse answer : answers) {
      assertThat(answer.getStatus()).isEqualTo(404);
      assertThat(code(answer)).isEqualTo("PARTNER_APPLICATION_NOT_FOUND");
    }
  }

  @Test
  void theStatusMachineIsClosedAndEveryDecisionIsRecorded() throws Exception {
    String reference =
        submitted(PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
    int version = PartnerWorld.version(mvc, admin, reference);

    // SUBMITTED cannot be approved, declined or moved to more-information-required.
    assertThat(
            code(
                PartnerWorld.transition(
                    mvc, admin, reference, "APPROVED_FOR_ONBOARDING", version, null, true)))
        .isEqualTo("PARTNER_APPLICATION_TRANSITION_INVALID");
    assertThat(
            code(
                PartnerWorld.transition(
                    mvc, admin, reference, "DECLINED", version, "OUT_OF_SCOPE", true)))
        .isEqualTo("PARTNER_APPLICATION_TRANSITION_INVALID");
    // A stale version is refused.
    assertThat(
            code(
                PartnerWorld.transition(
                    mvc, admin, reference, "UNDER_REVIEW", version + 5, null, false)))
        .isEqualTo("PARTNER_APPLICATION_TRANSITION_INVALID");

    assertThat(
            PartnerWorld.transition(mvc, admin, reference, "UNDER_REVIEW", version, null, false)
                .getStatus())
        .isEqualTo(200);
    version = PartnerWorld.version(mvc, admin, reference);
    // A reason is required to request information and must be a clarification reason.
    assertThat(
            PartnerWorld.transition(
                    mvc, admin, reference, "MORE_INFORMATION_REQUIRED", version, null, false)
                .getStatus())
        .isEqualTo(400);
    assertThat(
            PartnerWorld.transition(
                    mvc,
                    admin,
                    reference,
                    "MORE_INFORMATION_REQUIRED",
                    version,
                    "OUT_OF_SCOPE",
                    false)
                .getStatus())
        .isEqualTo(400);
    MockHttpServletResponse info =
        PartnerWorld.transition(
            mvc,
            admin,
            reference,
            "MORE_INFORMATION_REQUIRED",
            version,
            "CLARIFY_SERVICE_SCOPE",
            false);
    assertThat(info.getStatus()).isEqualTo(200);
    assertThat(body(info).path("status").asText()).isEqualTo("MORE_INFORMATION_REQUIRED");
    assertThat(body(info).path("version").asInt()).isEqualTo(version + 1);
    version = PartnerWorld.version(mvc, admin, reference);
    assertThat(
            PartnerWorld.transition(mvc, admin, reference, "UNDER_REVIEW", version, null, false)
                .getStatus())
        .isEqualTo(200);
    version = PartnerWorld.version(mvc, admin, reference);

    // A terminal decision needs confirmation and a closed reason.
    assertThat(
            PartnerWorld.transition(
                    mvc, admin, reference, "DECLINED", version, "OUT_OF_SCOPE", false)
                .getStatus())
        .isEqualTo(400);
    assertThat(
            PartnerWorld.transition(
                    mvc, admin, reference, "DECLINED", version, "APPLICANT_REQUEST", true)
                .getStatus())
        .isEqualTo(400);
    assertThat(
            PartnerWorld.transition(
                    mvc, admin, reference, "WITHDRAWN", version, "OUT_OF_SCOPE", true)
                .getStatus())
        .isEqualTo(400);
    assertThat(
            PartnerWorld.transition(
                    mvc, admin, reference, "DECLINED", version, "OUT_OF_SCOPE", true)
                .getStatus())
        .isEqualTo(200);
    assertThat(status(reference)).isEqualTo("DECLINED");

    // Terminal: never changed again, whatever is asked.
    version = PartnerWorld.version(mvc, admin, reference);
    List<MockHttpServletResponse> closed =
        List.of(
            PartnerWorld.transition(mvc, admin, reference, "UNDER_REVIEW", version, null, false),
            PartnerWorld.transition(
                mvc, admin, reference, "APPROVED_FOR_ONBOARDING", version, null, true),
            PartnerWorld.transition(
                mvc, admin, reference, "WITHDRAWN", version, "APPLICANT_REQUEST", true));
    for (MockHttpServletResponse refused : closed) {
      assertThat(refused.getStatus()).isEqualTo(409);
      assertThat(code(refused)).isEqualTo("PARTNER_APPLICATION_TERMINAL");
    }
    MockHttpServletResponse check =
        PartnerWorld.setCheck(
            mvc,
            admin,
            reference,
            "BUSINESS_IDENTITY",
            "SATISFIED",
            PartnerWorld.checkVersion(mvc, admin, reference, "BUSINESS_IDENTITY"),
            null);
    assertThat(code(check)).isEqualTo("PARTNER_APPLICATION_TERMINAL");

    List<String> events =
        jdbc.queryForList(
            "SELECT e.event_type FROM integrations.partner_application_event e"
                + " JOIN integrations.partner_application a ON a.id = e.application_id"
                + " WHERE a.reference = ? ORDER BY e.sequence",
            String.class,
            reference);
    assertThat(events)
        .containsExactly(
            "SUBMITTED", "REVIEW_STARTED", "INFORMATION_REQUESTED", "REVIEW_STARTED", "DECLINED");
    JsonNode history = body(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference + "/history"));
    assertThat(history.path("items")).hasSize(5);
    assertThat(history.path("items").get(4).path("performedByCaller").asBoolean()).isTrue();
    assertThat(history.path("items").get(4).path("reasonCode").asText()).isEqualTo("OUT_OF_SCOPE");
  }

  @Test
  void aWithdrawalIsRecordedOnlyWithTheApplicantRequestReason() throws Exception {
    String reference =
        submitted(PartnerWorld.insurer(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
    int version = PartnerWorld.version(mvc, admin, reference);
    assertThat(
            PartnerWorld.transition(mvc, admin, reference, "WITHDRAWN", version, "OTHER", true)
                .getStatus())
        .isEqualTo(400);
    assertThat(
            PartnerWorld.transition(
                    mvc, admin, reference, "WITHDRAWN", version, "APPLICANT_REQUEST", true)
                .getStatus())
        .isEqualTo(200);
    assertThat(status(reference)).isEqualTo("WITHDRAWN");
  }

  @Test
  void approvalIsImpossibleUntilEveryApplicableMandatoryCheckIsSatisfied() throws Exception {
    String reference =
        PartnerWorld.underReview(
            mvc, admin, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));

    MockHttpServletResponse blocked = approve(reference);
    assertThat(blocked.getStatus()).isEqualTo(409);
    assertThat(code(blocked)).isEqualTo("PARTNER_APPLICATION_CHECKLIST_INCOMPLETE");
    assertThat(status(reference)).isEqualTo("UNDER_REVIEW");

    // Regulatory information can never be "not applicable" for a regulated primary category.
    MockHttpServletResponse notApplicable =
        PartnerWorld.setCheck(
            mvc,
            admin,
            reference,
            "REGULATORY_INFORMATION",
            "NOT_APPLICABLE",
            PartnerWorld.checkVersion(mvc, admin, reference, "REGULATORY_INFORMATION"),
            null);
    assertThat(notApplicable.getStatus()).isEqualTo(400);

    List<String> codes = PartnerWorld.mandatory();
    for (int i = 0; i < codes.size() - 1; i++) {
      MockHttpServletResponse set =
          PartnerWorld.setCheck(
              mvc,
              admin,
              reference,
              codes.get(i),
              "SATISFIED",
              PartnerWorld.checkVersion(mvc, admin, reference, codes.get(i)),
              "synthetic note " + i);
      assertThat(set.getStatus()).isEqualTo(200);
      assertThat(body(set).path("result").asText()).isEqualTo("SATISFIED");
      assertThat(body(set).path("performedByCaller").asBoolean()).isTrue();
      assertThat(approve(reference).getStatus()).isEqualTo(409);
    }
    // One concern keeps it blocked; the advisory commercial check never gates approval.
    String last = codes.get(codes.size() - 1);
    MockHttpServletResponse concern =
        PartnerWorld.setCheck(
            mvc,
            admin,
            reference,
            last,
            "CONCERN",
            PartnerWorld.checkVersion(mvc, admin, reference, last),
            null);
    assertThat(concern.getStatus()).isEqualTo(200);
    JsonNode blockedDetail = body(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference));
    assertThat(blockedDetail.path("approvalBlockedBy"))
        .extracting(JsonNode::asText)
        .containsExactly(last);
    assertThat(approve(reference).getStatus()).isEqualTo(409);
    assertThat(
            PartnerWorld.setCheck(
                    mvc,
                    admin,
                    reference,
                    last,
                    "SATISFIED",
                    PartnerWorld.checkVersion(mvc, admin, reference, last),
                    null)
                .getStatus())
        .isEqualTo(200);

    long footprint = PartnerWorld.accessFootprint(jdbc);
    MockHttpServletResponse approved = approve(reference);
    assertThat(approved.getStatus()).isEqualTo(200);
    assertThat(body(approved).path("status").asText()).isEqualTo("APPROVED_FOR_ONBOARDING");
    assertThat(status(reference)).isEqualTo("APPROVED_FOR_ONBOARDING");
    // Approval opens onboarding discussions only: no login, membership, tenant access,
    // integration configuration or listing of any kind is created.
    assertThat(PartnerWorld.accessFootprint(jdbc)).isEqualTo(footprint);
    assertThat(approved.getContentAsString())
        .doesNotContain("credential")
        .doesNotContain("password");
    JsonNode detail = body(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference));
    assertThat(detail.path("allowedTransitions")).isEmpty();
    assertThat(detail.path("approvalBlockedBy")).isEmpty();
  }

  @Test
  void aCheckNoteIsEncryptedAtRestAndReturnedOnlyToTheAdministrator() throws Exception {
    String reference =
        PartnerWorld.underReview(
            mvc, admin, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
    String note = "synthetic-confidential-note-" + PartnerWorld.token();
    MockHttpServletResponse set =
        PartnerWorld.setCheck(
            mvc,
            admin,
            reference,
            "SERVICE_SCOPE",
            "SATISFIED",
            PartnerWorld.checkVersion(mvc, admin, reference, "SERVICE_SCOPE"),
            note);
    assertThat(set.getStatus()).isEqualTo(200);
    assertThat(body(set).path("note").asText()).isEqualTo(note);

    String stored =
        jdbc.queryForObject(
            "SELECT encode(c.note_ciphertext, 'escape') FROM integrations.partner_application_check c"
                + " JOIN integrations.partner_application a ON a.id = c.application_id"
                + " WHERE a.reference = ? AND c.check_code = 'SERVICE_SCOPE'",
            String.class,
            reference);
    assertThat(stored).doesNotContain(note);
    JsonNode detail = body(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference));
    String shown = null;
    for (JsonNode check : detail.path("checklist")) {
      if ("SERVICE_SCOPE".equals(check.path("checkCode").asText())) {
        shown = check.path("note").asText();
      }
    }
    assertThat(shown).isEqualTo(note);

    // The note never reaches the audit trail, the history, the idempotency store or the outbox.
    String everything =
        jdbc.queryForObject(
                "SELECT coalesce(string_agg(metadata::text, ' '), '') FROM platform.audit_event",
                String.class)
            + jdbc.queryForObject(
                "SELECT coalesce(string_agg(response_body::text, ' '), '') FROM platform.idempotency_record",
                String.class)
            + jdbc.queryForObject(
                "SELECT coalesce(string_agg(envelope::text, ' '), '') FROM platform.outbox_event",
                String.class)
            + body(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference + "/history"))
                .toString();
    assertThat(everything).doesNotContain(note);
  }

  @Test
  void anIdenticalCheckRequestIsReplayedAndAStaleOneIsRefused() throws Exception {
    String reference =
        PartnerWorld.underReview(
            mvc, admin, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
    int version = PartnerWorld.checkVersion(mvc, admin, reference, "BUSINESS_IDENTITY");
    String key = "synthetic-check-" + UUID.randomUUID();
    String payload = "{\"result\":\"SATISFIED\",\"expectedVersion\":" + version + "}";
    MockHttpServletResponse first = putCheck(reference, key, payload);
    MockHttpServletResponse replay = putCheck(reference, key, payload);
    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(body(replay).path("version").asInt()).isEqualTo(version + 1);
    Integer events =
        jdbc.queryForObject(
            "SELECT count(*) FROM integrations.partner_application_event e"
                + " JOIN integrations.partner_application a ON a.id = e.application_id"
                + " WHERE a.reference = ? AND e.event_type = 'CHECKLIST_UPDATED'",
            Integer.class,
            reference);
    assertThat(events).isEqualTo(1);
    MockHttpServletResponse stale =
        putCheck(reference, "synthetic-check-" + UUID.randomUUID(), payload);
    assertThat(stale.getStatus()).isEqualTo(409);
    assertThat(code(stale)).isEqualTo("PARTNER_APPLICATION_TRANSITION_INVALID");
    MockHttpServletResponse strict =
        putCheck(
            reference,
            "synthetic-check-" + UUID.randomUUID(),
            "{\"result\":\"SATISFIED\",\"expectedVersion\":0,\"actor\":\"x\"}");
    assertThat(strict.getStatus()).isEqualTo(400);
    assertThat(code(strict)).isEqualTo("PARTNER_APPLICATION_INVALID");
  }

  @Test
  void aConcurrentCheckChangeAndApprovalCanNeverBypassTheGate() throws Exception {
    for (int round = 0; round < 6; round++) {
      String reference =
          PartnerWorld.underReview(
              mvc, admin, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
      PartnerWorld.satisfyAll(mvc, admin, reference);
      int applicationVersion = PartnerWorld.version(mvc, admin, reference);
      int checkVersion =
          PartnerWorld.checkVersion(mvc, admin, reference, "TECHNICAL_API_READINESS");
      ExecutorService pool = Executors.newFixedThreadPool(2);
      CountDownLatch start = new CountDownLatch(1);
      Future<MockHttpServletResponse> concern =
          pool.submit(
              () -> {
                start.await();
                return PartnerWorld.setCheck(
                    mvc,
                    admin,
                    reference,
                    "TECHNICAL_API_READINESS",
                    "CONCERN",
                    checkVersion,
                    null);
              });
      Future<MockHttpServletResponse> approval =
          pool.submit(
              () -> {
                start.await();
                return PartnerWorld.transition(
                    mvc,
                    admin,
                    reference,
                    "APPROVED_FOR_ONBOARDING",
                    applicationVersion,
                    null,
                    true);
              });
      start.countDown();
      MockHttpServletResponse concernResponse = concern.get();
      MockHttpServletResponse approvalResponse = approval.get();
      pool.shutdown();

      String result =
          jdbc.queryForObject(
              "SELECT c.result FROM integrations.partner_application_check c"
                  + " JOIN integrations.partner_application a ON a.id = c.application_id"
                  + " WHERE a.reference = ? AND c.check_code = 'TECHNICAL_API_READINESS'",
              String.class,
              reference);
      String status = status(reference);
      if ("APPROVED_FOR_ONBOARDING".equals(status)) {
        // Approval won: the check was satisfied at that moment and the late change is refused.
        assertThat(approvalResponse.getStatus()).isEqualTo(200);
        assertThat(result).isEqualTo("SATISFIED");
        assertThat(concernResponse.getStatus()).isEqualTo(409);
        assertThat(code(concernResponse)).isEqualTo("PARTNER_APPLICATION_TERMINAL");
      } else {
        // The concern won: approval is refused and the application stays under review.
        assertThat(status).isEqualTo("UNDER_REVIEW");
        assertThat(result).isEqualTo("CONCERN");
        assertThat(concernResponse.getStatus()).isEqualTo(200);
        assertThat(approvalResponse.getStatus()).isEqualTo(409);
        assertThat(code(approvalResponse)).isEqualTo("PARTNER_APPLICATION_CHECKLIST_INCOMPLETE");
      }
    }
  }

  @Test
  void aReadWritesADisclosureAuditWithoutConfidentialData() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    String reference = submitted(PartnerWorld.bank(PartnerWorld.uniqueName(), email));
    assertThat(PartnerWorld.read(mvc, admin, PLATFORM + "/" + reference).getStatus())
        .isEqualTo(200);
    String audit =
        jdbc.queryForObject(
            "SELECT string_agg(action || ' ' || metadata::text, ' ') FROM platform.audit_event"
                + " WHERE action IN ('partner.application-view', 'partner.application-list')",
            String.class);
    assertThat(audit)
        .contains("partner.application-view")
        .contains(reference)
        .doesNotContain(email);
  }

  private MockHttpServletResponse approve(String reference) throws Exception {
    return PartnerWorld.transition(
        mvc,
        admin,
        reference,
        "APPROVED_FOR_ONBOARDING",
        PartnerWorld.version(mvc, admin, reference),
        null,
        true);
  }

  private MockHttpServletResponse putCheck(String reference, String key, String payload)
      throws Exception {
    return mvc.perform(
            put(PLATFORM + "/" + reference + "/checklist/BUSINESS_IDENTITY")
                .header("Authorization", admin)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andReturn()
        .getResponse();
  }

  private static List<String> references(JsonNode page) {
    List<String> references = new ArrayList<>();
    for (JsonNode item : page.path("items")) {
      references.add(item.path("reference").asText());
    }
    return references;
  }
}
