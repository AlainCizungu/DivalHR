package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.CompensationWorld.cancelPreviewRequest;
import static com.divalhr.core.payroll.CompensationWorld.cancelRequest;
import static com.divalhr.core.payroll.CompensationWorld.cancelling;
import static com.divalhr.core.payroll.CompensationWorld.command;
import static com.divalhr.core.payroll.CompensationWorld.commitRequest;
import static com.divalhr.core.payroll.CompensationWorld.committing;
import static com.divalhr.core.payroll.CompensationWorld.correction;
import static com.divalhr.core.payroll.CompensationWorld.mineRequest;
import static com.divalhr.core.payroll.CompensationWorld.previewRequest;
import static com.divalhr.core.payroll.CompensationWorld.readRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.CompensationWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-093A (Issue #175) against real PostgreSQL through the public API only: a compensation is
 * established, scheduled, corrected and cancelled with a preview before every command; the
 * administrator reads the immutable history and an employee reads only their own; stale, expired,
 * drifted and replayed commands are stable answers with nothing half-written; unknown, malformed
 * and foreign identifiers are one safe 404; and the audit, events, idempotency store, cursors, logs
 * and errors carry no amount and no explanation.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class CompensationApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private CompensationWorld w;
  private Person ana;

  @BeforeEach
  void world() throws Exception {
    w = new CompensationWorld(mvc, jdbc, lookups);
    ana = w.person("Ana", "Mukendi");
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String key() {
    return Organizations.newKey();
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** A commit that carries a made-up preview digest (it must be refused before the digest). */
  private static Map<String, Object> forced(
      Map<String, Object> command, JsonNode anchor, int version, boolean acknowledged) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", version);
    body.put("employmentDigest", anchor.get("employmentDigest").asText());
    body.put("previewDigest", "a".repeat(64));
    body.put("expiresAt", anchor.get("expiresAt").asText());
    body.put("acknowledgeRetroactive", acknowledged);
    body.put("confirmed", true);
    return body;
  }

  private JsonNode establish(Person person, String currency, String amount) throws Exception {
    return w.record(person.employee(), command("ESTABLISHED", w.today, currency, amount), false);
  }

  // ------------------------------------------------------------------------------------------
  // The lifecycle
  // ------------------------------------------------------------------------------------------

  @Test
  void establishScheduleCorrectAndCancelAreRecordedPreviewedAndRead() throws Exception {
    UUID employee = ana.employee();
    Map<String, Object> establish = command("ESTABLISHED", w.today, "USD", "1250.00");
    JsonNode preview = w.preview(employee, establish);
    assertThat(fields(preview))
        .containsExactlyInAnyOrder(
            "action",
            "money",
            "effectiveFrom",
            "before",
            "after",
            "warnings",
            "blockers",
            "requiresReason",
            "requiresAcknowledgement",
            "version",
            "employmentDigest",
            "previewDigest",
            "expiresAt");
    assertThat(preview.get("money").get("currency").asText()).isEqualTo("USD");
    assertThat(preview.get("money").get("amount").asText()).isEqualTo("1250.00");
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("version").asInt()).isZero();
    assertThat(preview.get("requiresAcknowledgement").asBoolean()).isFalse();
    assertThat(preview.get("before")).isEmpty();
    assertThat(preview.get("after")).hasSize(1);
    // A preview writes no change, no fact, no command audit and no event.
    assertThat(w.evidence().get(0)).isZero();
    assertThat(w.evidence().get(2)).isZero();
    assertThat(w.evidence().get(3)).isZero();

    JsonNode established =
        w.expect(
            commitRequest(w.admin, employee, key(), committing(establish, preview, false)), 201);
    assertThat(fields(established))
        .containsExactlyInAnyOrder("changeId", "sequence", "action", "effectiveFrom", "version");
    assertThat(established.get("sequence").asInt()).isEqualTo(1);
    assertThat(established.get("action").asText()).isEqualTo("ESTABLISHED");

    // Schedule a later change in the other currency.
    String laterText = w.today.plusDays(30).toString();
    JsonNode scheduled =
        w.record(employee, command("CHANGED", w.today.plusDays(30), "CDF", "135000"), false);
    assertThat(scheduled.get("sequence").asInt()).isEqualTo(2);
    JsonNode record = w.read(employee);
    assertThat(fields(record))
        .containsExactlyInAnyOrder(
            "version", "businessDate", "current", "scheduled", "changes", "nextCursor");
    assertThat(record.get("version").asInt()).isEqualTo(2);
    assertThat(record.get("current").get("originChangeId").asText())
        .isEqualTo(established.get("changeId").asText());
    assertThat(record.get("current").get("effectiveTo").asText())
        .isEqualTo(w.today.plusDays(29).toString());
    assertThat(record.get("current").get("cancellable").asBoolean()).isFalse();
    assertThat(record.get("scheduled")).hasSize(1);
    JsonNode next = record.get("scheduled").get(0);
    assertThat(next.get("effectiveFrom").asText()).isEqualTo(laterText);
    assertThat(next.get("money").get("currency").asText()).isEqualTo("CDF");
    assertThat(next.get("money").get("amount").asText()).isEqualTo("135000");
    assertThat(next.get("cancellable").asBoolean()).isTrue();
    assertThat(record.get("changes")).hasSize(2);
    assertThat(record.get("changes").get(0).get("sequence").asInt()).isEqualTo(2);
    assertThat(record.get("nextCursor").isNull()).isTrue();

    // Correct the established value: needs the acknowledgement, a reason and an explanation.
    UUID target = UUID.fromString(established.get("changeId").asText());
    Map<String, Object> correct = correction(w.today, "USD", "1300.00", target);
    JsonNode correctionPreview = w.preview(employee, correct);
    assertThat(correctionPreview.get("requiresReason").asBoolean()).isTrue();
    assertThat(correctionPreview.get("requiresAcknowledgement").asBoolean()).isTrue();
    JsonNode missing =
        w.problem(
            commitRequest(w.admin, employee, key(), committing(correct, correctionPreview, false)),
            400,
            "VALIDATION_FAILED");
    assertThat(missing.toString()).contains("acknowledgeRetroactive");
    assertThat(w.read(employee).get("version").asInt()).isEqualTo(2);
    JsonNode corrected =
        w.expect(
            commitRequest(w.admin, employee, key(), committing(correct, correctionPreview, true)),
            201);
    assertThat(corrected.get("action").asText()).isEqualTo("CORRECTED");
    assertThat(corrected.get("sequence").asInt()).isEqualTo(3);
    record = w.read(employee);
    assertThat(record.get("current").get("money").get("amount").asText()).isEqualTo("1300.00");
    JsonNode change = record.get("changes").get(0);
    assertThat(change.get("action").asText()).isEqualTo("CORRECTED");
    assertThat(change.get("reasonCode").asText()).isEqualTo("DATA_ENTRY_ERROR");
    assertThat(change.get("explanation").asText()).isEqualTo(CompensationWorld.EXPLANATION);
    assertThat(change.get("targetChangeId").asText()).isEqualTo(target.toString());

    // Cancel the scheduled change; the preceding interval is restored open-ended.
    String scheduledId = scheduled.get("changeId").asText();
    JsonNode cancelPreview = w.expect(cancelPreviewRequest(w.admin, employee, scheduledId), 200);
    assertThat(cancelPreview.get("blockers")).isEmpty();
    assertThat(cancelPreview.get("cancellationDigest").isNull()).isFalse();
    assertThat(cancelPreview.get("after")).hasSize(1);
    JsonNode cancelled =
        w.expect(
            cancelRequest(w.admin, employee, scheduledId, key(), cancelling(cancelPreview)), 201);
    assertThat(cancelled.get("action").asText()).isEqualTo("SCHEDULED_CHANGE_CANCELLED");
    assertThat(cancelled.get("sequence").asInt()).isEqualTo(4);
    record = w.read(employee);
    assertThat(record.get("scheduled")).isEmpty();
    assertThat(record.get("current").get("effectiveTo").isNull()).isTrue();
    assertThat(record.get("changes").get(0).get("targetChangeId").asText()).isEqualTo(scheduledId);

    // One change, audit record and internal event per command; disclosures are audited too.
    List<Integer> evidence = w.evidence();
    assertThat(evidence.get(0)).isEqualTo(4);
    assertThat(evidence.get(2)).isEqualTo(4);
    assertThat(evidence.get(3)).isEqualTo(4);
    assertThat(
            w.jdbc.queryForList(
                "SELECT DISTINCT event_type FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type LIKE 'payroll.compensation.%' ORDER BY 1",
                String.class, w.tenant()))
        .containsExactly(
            "payroll.compensation.changed.v1",
            "payroll.compensation.corrected.v1",
            "payroll.compensation.established.v1",
            "payroll.compensation.scheduled-change-cancelled.v1");
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'compensation.read'",
                w.tenant()))
        .isPositive();
  }

  @Test
  void responsesAreNeverCached() throws Exception {
    establish(ana, "USD", "10.00");
    MockHttpServletResponse read = w.call(readRequest(w.admin, ana.employee(), null));
    assertThat(read.getHeader("Cache-Control")).isEqualTo("private, no-store");
    MockHttpServletResponse mine = w.call(mineRequest(ana.bearer(), null));
    assertThat(mine.getHeader("Cache-Control")).isEqualTo("private, no-store");
  }

  // ------------------------------------------------------------------------------------------
  // Authority
  // ------------------------------------------------------------------------------------------

  @Test
  void anEmployeeCannotUseAnyAdministratorOperation() throws Exception {
    establish(ana, "USD", "10.00");
    UUID any = UUID.randomUUID();
    String bearer = ana.bearer();
    List<Integer> statuses = new ArrayList<>();
    statuses.add(w.call(readRequest(bearer, ana.employee(), null)).getStatus());
    statuses.add(
        w.call(previewRequest(bearer, ana.employee(), command("CHANGED", w.today, "USD", "1.00")))
            .getStatus());
    statuses.add(w.call(commitRequest(bearer, ana.employee(), key(), Map.of())).getStatus());
    statuses.add(w.call(cancelPreviewRequest(bearer, ana.employee(), any)).getStatus());
    statuses.add(w.call(cancelRequest(bearer, ana.employee(), any, key(), Map.of())).getStatus());
    assertThat(statuses).containsOnly(403);
    // The administrator has no self-service operation either.
    assertThat(w.call(mineRequest(w.admin, null)).getStatus()).isEqualTo(403);
  }

  @Test
  void anEmployeeReadsOnlyTheirOwnCompensationWithoutTheExplanation() throws Exception {
    JsonNode established = establish(ana, "USD", "1250.00");
    w.record(ana.employee(), command("CHANGED", w.today.plusDays(10), "USD", "1400.00"), false);
    UUID target = UUID.fromString(established.get("changeId").asText());
    Map<String, Object> correct = correction(w.today, "USD", "1260.00", target);
    JsonNode preview = w.preview(ana.employee(), correct);
    w.expect(
        commitRequest(w.admin, ana.employee(), key(), committing(correct, preview, true)), 201);

    JsonNode mine = w.expect(mineRequest(ana.bearer(), null), 200);
    assertThat(fields(mine))
        .containsExactlyInAnyOrder("businessDate", "current", "scheduled", "changes", "nextCursor");
    assertThat(mine.get("current").get("money").get("amount").asText()).isEqualTo("1260.00");
    assertThat(mine.get("current").get("cancellable").asBoolean()).isFalse();
    assertThat(mine.get("scheduled").get(0).get("cancellable").asBoolean()).isFalse();
    JsonNode corrected = mine.get("changes").get(0);
    assertThat(corrected.get("reasonCode").asText()).isEqualTo("DATA_ENTRY_ERROR");
    assertThat(corrected.get("explanation").isNull()).isTrue();
    assertThat(mine.toString()).doesNotContain(CompensationWorld.EXPLANATION);

    // Another employee sees only their own (nothing recorded), never Ana's.
    Person bob = w.person("Bob", "Kasongo");
    JsonNode theirs = w.expect(mineRequest(bob.bearer(), null), 200);
    assertThat(theirs.get("current").isNull()).isTrue();
    assertThat(theirs.get("changes")).isEmpty();
    // A caller without an active access link is refused.
    w.problem(mineRequest(w.unlinkedBearer(), null), 403, "EMPLOYEE_LINK_REQUIRED");
  }

  @Test
  void unknownMalformedAndForeignIdentifiersAreOneSafeNotFound() throws Exception {
    CompensationWorld other = new CompensationWorld(mvc, jdbc, lookups);
    Person foreign = other.person("Eve", "Banza");
    JsonNode own = establish(ana, "USD", "10.00");
    Person bob = w.person("Bob", "Kasongo");
    List<Object> employees = List.of(UUID.randomUUID(), "not-a-uuid", foreign.employee());
    List<JsonNode> bodies = new ArrayList<>();
    for (Object employee : employees) {
      bodies.add(w.problem(readRequest(w.admin, employee, null), 404, "COMPENSATION_NOT_FOUND"));
      bodies.add(
          w.problem(
              previewRequest(w.admin, employee, command("ESTABLISHED", w.today, "USD", "1.00")),
              404,
              "COMPENSATION_NOT_FOUND"));
      bodies.add(
          w.problem(
              cancelPreviewRequest(w.admin, employee, UUID.randomUUID()),
              404,
              "COMPENSATION_NOT_FOUND"));
    }
    // A change that does not exist, is malformed or belongs to another employee.
    for (Object change : List.of(UUID.randomUUID(), "not-a-uuid", own.get("changeId").asText())) {
      bodies.add(
          w.problem(
              cancelPreviewRequest(w.admin, bob.employee(), change),
              404,
              "COMPENSATION_NOT_FOUND"));
    }
    for (JsonNode body : bodies) {
      assertThat(body.get("params")).isEqualTo(bodies.get(0).get("params"));
      assertThat(fields(body)).isEqualTo(fields(bodies.get(0)));
    }
    // The foreign tenant's administrator cannot name Ana either.
    other.problem(readRequest(other.admin, ana.employee(), null), 404, "COMPENSATION_NOT_FOUND");
    assertThat(other.evidence()).containsOnly(0);
  }

  // ------------------------------------------------------------------------------------------
  // Staleness, versions and replays
  // ------------------------------------------------------------------------------------------

  @Test
  void aStaleExpiredOrAlteredPreviewIsOneStableConflictWithNothingWritten() throws Exception {
    UUID employee = ana.employee();
    Map<String, Object> establish = command("ESTABLISHED", w.today, "USD", "10.00");
    JsonNode first = w.preview(employee, establish);
    JsonNode second = w.preview(employee, establish);
    w.expect(commitRequest(w.admin, employee, key(), committing(establish, first, false)), 201);
    List<Integer> before = w.evidence();

    // The second preview was made at version 0; the timeline has moved.
    w.problem(
        commitRequest(w.admin, employee, key(), committing(establish, second, false)),
        409,
        "COMPENSATION_VERSION_CONFLICT");

    Map<String, Object> change = command("CHANGED", w.today.plusDays(5), "USD", "20.00");
    JsonNode preview = w.preview(employee, change);
    // A different value than the one previewed.
    Map<String, Object> altered = command("CHANGED", w.today.plusDays(5), "USD", "21.00");
    w.problem(
        commitRequest(w.admin, employee, key(), committing(altered, preview, false)),
        409,
        "COMPENSATION_PREVIEW_STALE");
    // A tampered employment digest and a tampered expiry.
    Map<String, Object> wrongEmployment = committing(change, preview, false);
    wrongEmployment.put("employmentDigest", "0".repeat(64));
    w.problem(
        commitRequest(w.admin, employee, key(), wrongEmployment),
        409,
        "COMPENSATION_PREVIEW_STALE");
    Map<String, Object> wrongExpiry = committing(change, preview, false);
    Instant expiry = Instant.parse(preview.get("expiresAt").asText());
    wrongExpiry.put("expiresAt", expiry.plusSeconds(1).toString());
    w.problem(
        commitRequest(w.admin, employee, key(), wrongExpiry), 409, "COMPENSATION_PREVIEW_STALE");
    // An expired preview.
    clock.set(Instant.now().plus(Duration.ofMinutes(31)));
    w.problem(
        commitRequest(w.admin, employee, key(), committing(change, preview, false)),
        409,
        "COMPENSATION_PREVIEW_STALE");
    clock.reset();
    assertThat(w.evidence()).isEqualTo(before);
    // The identical, fresh preview still commits.
    JsonNode fresh = w.preview(employee, change);
    w.expect(commitRequest(w.admin, employee, key(), committing(change, fresh, false)), 201);
  }

  @Test
  void aRealEmploymentChangeBetweenPreviewAndCommitIsStaleWithNothingWritten() throws Exception {
    UUID employee = ana.employee();
    establish(ana, "USD", "10.00");
    Map<String, Object> change = command("CHANGED", w.today.plusDays(5), "USD", "20.00");
    JsonNode preview = w.preview(employee, change);
    List<Integer> before = w.evidence();
    JsonNode versionBefore = w.read(employee);

    // The employment really changes through the people API: it now ends on a given day.
    w.separate(employee, w.today.plusDays(100));

    JsonNode problem =
        w.problem(
            commitRequest(w.admin, employee, key(), committing(change, preview, false)),
            409,
            "COMPENSATION_PREVIEW_STALE");
    // No compensation side effect: no change, no fact, no audit record, no event, same history.
    assertThat(w.evidence()).isEqualTo(before);
    assertThat(w.read(employee)).isEqualTo(versionBefore);
    // The refusal names neither the amount nor the dates it protected.
    assertThat(problem.toString()).doesNotContain("20.00").doesNotContain("employmentDigest");

    // A fresh preview sees the new employment and commits.
    JsonNode fresh = w.preview(employee, change);
    assertThat(fresh.get("employmentDigest")).isNotEqualTo(preview.get("employmentDigest"));
    w.expect(commitRequest(w.admin, employee, key(), committing(change, fresh, false)), 201);
  }

  @Test
  void theSameKeyAndPayloadReplaysTheReceiptAndAnotherPayloadIsRefused() throws Exception {
    UUID employee = ana.employee();
    Map<String, Object> establish = command("ESTABLISHED", w.today, "USD", "10.00");
    JsonNode preview = w.preview(employee, establish);
    Map<String, Object> body = committing(establish, preview, false);
    String key = key();
    MockHttpServletResponse first = w.call(commitRequest(w.admin, employee, key, body));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(first.getHeader("Idempotent-Replayed")).isNull();
    List<Integer> after = w.evidence();
    MockHttpServletResponse replay = w.call(commitRequest(w.admin, employee, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(w.evidence()).isEqualTo(after);

    Map<String, Object> different = new LinkedHashMap<>(body);
    different.put("amount", "11.00");
    w.problem(commitRequest(w.admin, employee, key, different), 409, "IDEMPOTENCY_KEY_REUSED");
    assertThat(w.evidence()).isEqualTo(after);

    JsonNode noKey = w.expect(commitRequest(w.admin, employee, null, body), 400);
    assertThat(noKey.get("code").asText()).isEqualTo("VALIDATION_FAILED");
  }

  // ------------------------------------------------------------------------------------------
  // Rules: blockers in a preview, codes in a commit
  // ------------------------------------------------------------------------------------------

  @Test
  void ruleViolationsAreBlockersInAPreviewAndStableCodesInACommit() throws Exception {
    UUID employee = ana.employee();
    // One day beyond the window in each direction.
    Map<String, Object> tooFar = command("ESTABLISHED", w.today.plusDays(367), "USD", "10.00");
    JsonNode farPreview = w.preview(employee, tooFar);
    assertThat(farPreview.get("blockers"))
        .extracting(JsonNode::asText)
        .contains("DATE_OUT_OF_RANGE");
    assertThat(farPreview.get("previewDigest").isNull()).isTrue();
    assertThat(farPreview.get("expiresAt").isNull()).isTrue();
    JsonNode anchor = w.preview(employee, command("ESTABLISHED", w.today, "USD", "10.00"));
    Map<String, Object> blocked = forced(tooFar, anchor, 0, false);
    w.problem(
        commitRequest(w.admin, employee, key(), blocked), 422, "COMPENSATION_DATE_OUT_OF_RANGE");
    assertThat(
            w.preview(employee, command("ESTABLISHED", w.today.minusDays(61), "USD", "10.00"))
                .get("blockers"))
        .extracting(JsonNode::asText)
        .contains("DATE_OUT_OF_RANGE");
    // The exact boundaries are accepted (the earlier one needs the acknowledgement).
    JsonNode backEdge =
        w.preview(employee, command("ESTABLISHED", w.today.minusDays(60), "USD", "10.00"));
    assertThat(backEdge.get("blockers")).isEmpty();
    assertThat(backEdge.get("requiresAcknowledgement").asBoolean()).isTrue();
    assertThat(backEdge.get("warnings")).extracting(JsonNode::asText).contains("RETROACTIVE");
    assertThat(
            w.preview(employee, command("ESTABLISHED", w.today.plusDays(366), "USD", "10.00"))
                .get("blockers"))
        .isEmpty();

    // Amounts: out of range is a blocker; a malformed amount is a 400.
    assertThat(w.preview(employee, command("ESTABLISHED", w.today, "USD", "0.00")).get("blockers"))
        .extracting(JsonNode::asText)
        .contains("AMOUNT_OUT_OF_RANGE");
    assertThat(
            w.preview(employee, command("ESTABLISHED", w.today, "CDF", "1000000001"))
                .get("blockers"))
        .extracting(JsonNode::asText)
        .contains("AMOUNT_OUT_OF_RANGE");
    for (String malformed : List.of("12,50", "-5.00", "1e3", " 10.00", "10.001", "")) {
      w.problem(
          previewRequest(w.admin, employee, command("ESTABLISHED", w.today, "USD", malformed)),
          400,
          "COMPENSATION_INVALID_AMOUNT");
    }
    w.problem(
        previewRequest(w.admin, employee, command("ESTABLISHED", w.today, "CDF", "10.5")),
        400,
        "COMPENSATION_INVALID_AMOUNT");

    // Shapes: a number is not an amount; an unknown property is refused, as is a tenant.
    Map<String, Object> number = command("ESTABLISHED", w.today, "USD", "10.00");
    number.put("amount", 10.0);
    w.problem(previewRequest(w.admin, employee, number), 400, "VALIDATION_FAILED");
    Map<String, Object> unknown = command("ESTABLISHED", w.today, "USD", "10.00");
    unknown.put("tenantId", UUID.randomUUID().toString());
    w.problem(previewRequest(w.admin, employee, unknown), 400, "VALIDATION_FAILED");
    Map<String, Object> noCurrency = command("ESTABLISHED", w.today, "EUR", "10.00");
    w.problem(previewRequest(w.admin, employee, noCurrency), 400, "VALIDATION_FAILED");
    Map<String, Object> notConfirmed = new LinkedHashMap<>(blocked);
    notConfirmed.put("confirmed", false);
    w.problem(commitRequest(w.admin, employee, key(), notConfirmed), 400, "VALIDATION_FAILED");

    // Nothing was ever written by a refused or previewed command.
    assertThat(w.evidence().get(0)).isZero();
  }

  @Test
  void identicalValuesAndASecondEstablishmentRecordNothing() throws Exception {
    UUID employee = ana.employee();
    establish(ana, "USD", "10.00");
    Map<String, Object> again = command("ESTABLISHED", w.today, "USD", "10.00");
    assertThat(w.preview(employee, again).get("blockers"))
        .extracting(JsonNode::asText)
        .contains("TRANSITION_NOT_ALLOWED");
    Map<String, Object> same = command("CHANGED", w.today.plusDays(1), "USD", "10.00");
    assertThat(w.preview(employee, same).get("blockers"))
        .extracting(JsonNode::asText)
        .contains("NO_CHANGE");
    assertThat(
            w.preview(employee, correction(w.today, "USD", "10.00", firstChange(employee)))
                .get("blockers"))
        .extracting(JsonNode::asText)
        .contains("NO_CHANGE");
    JsonNode anchor = w.preview(employee, command("CHANGED", w.today.plusDays(2), "USD", "11.00"));
    w.problem(
        commitRequest(w.admin, employee, key(), forced(same, anchor, 1, false)),
        422,
        "COMPENSATION_TRANSITION_NOT_ALLOWED");
    w.problem(
        commitRequest(w.admin, employee, key(), forced(again, anchor, 1, false)),
        422,
        "COMPENSATION_TRANSITION_NOT_ALLOWED");
    assertThat(w.evidence().get(0)).isEqualTo(1);
  }

  private UUID firstChange(UUID employee) throws Exception {
    JsonNode changes = w.read(employee).get("changes");
    return UUID.fromString(changes.get(changes.size() - 1).get("changeId").asText());
  }

  @Test
  void aDateBeforeEveryEmploymentIsABlockerAndAConflict() throws Exception {
    UUID newcomer = w.hire("Nina", "Tshimanga", w.today);
    Map<String, Object> early = command("ESTABLISHED", w.today.minusDays(1), "USD", "10.00");
    assertThat(w.preview(newcomer, early).get("blockers"))
        .extracting(JsonNode::asText)
        .contains("EMPLOYMENT_CONFLICT");
    JsonNode anchor = w.preview(newcomer, command("ESTABLISHED", w.today, "USD", "10.00"));
    assertThat(anchor.get("blockers")).isEmpty();
    w.problem(
        commitRequest(w.admin, newcomer, key(), forced(early, anchor, 0, true)),
        409,
        "COMPENSATION_EMPLOYMENT_CONFLICT");
    assertThat(w.evidence().get(0)).isZero();
  }

  @Test
  void aScheduledChangeCanBeCancelledButAStartedOneCannot() throws Exception {
    UUID employee = ana.employee();
    JsonNode established = establish(ana, "USD", "10.00");
    String establishedId = established.get("changeId").asText();
    JsonNode started = w.expect(cancelPreviewRequest(w.admin, employee, establishedId), 200);
    assertThat(started.get("blockers")).extracting(JsonNode::asText).contains("NOT_CANCELLABLE");
    assertThat(started.get("cancellationDigest").isNull()).isTrue();
    JsonNode scheduled =
        w.record(employee, command("CHANGED", w.today.plusDays(3), "USD", "12.00"), false);
    String scheduledId = scheduled.get("changeId").asText();
    JsonNode preview = w.expect(cancelPreviewRequest(w.admin, employee, scheduledId), 200);
    Map<String, Object> body = cancelling(preview);
    w.expect(cancelRequest(w.admin, employee, scheduledId, key(), body), 201);
    // The cancelled change is no longer an active scheduled fact.
    w.problem(
        cancelRequest(w.admin, employee, scheduledId, key(), body),
        409,
        "COMPENSATION_VERSION_CONFLICT");
    JsonNode again = w.expect(cancelPreviewRequest(w.admin, employee, scheduledId), 200);
    assertThat(again.get("blockers")).extracting(JsonNode::asText).contains("NOT_CANCELLABLE");
  }

  // ------------------------------------------------------------------------------------------
  // History pages
  // ------------------------------------------------------------------------------------------

  @Test
  void theHistoryIsAKeysetPageBoundToItsEmployeeRoleAndSize() throws Exception {
    UUID employee = ana.employee();
    establish(ana, "USD", "10.00");
    w.record(employee, command("CHANGED", w.today.plusDays(10), "USD", "11.00"), false);
    w.record(employee, command("CHANGED", w.today.plusDays(20), "USD", "12.00"), false);

    JsonNode page = w.expect(readRequest(w.admin, employee, "limit=2"), 200);
    assertThat(page.get("changes")).hasSize(2);
    assertThat(page.get("changes").get(0).get("sequence").asInt()).isEqualTo(3);
    String cursor = page.get("nextCursor").asText();
    JsonNode last = w.expect(readRequest(w.admin, employee, "limit=2&cursor=" + cursor), 200);
    assertThat(last.get("changes")).hasSize(1);
    assertThat(last.get("changes").get(0).get("sequence").asInt()).isEqualTo(1);
    assertThat(last.get("nextCursor").isNull()).isTrue();
    assertThat(
            w.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'compensation.history-read'",
                w.tenant()))
        .isEqualTo(1);

    // The cursor carries no amount and is refused for another size, employee or role.
    String encoded = cursor.substring(0, cursor.indexOf('.'));
    String payload = new String(java.util.Base64.getUrlDecoder().decode(encoded));
    assertThat(payload).doesNotContain("10.00", "11.00", "12.00", "1200");
    w.problem(readRequest(w.admin, employee, "limit=3&cursor=" + cursor), 400, "CURSOR_INVALID");
    Person bob = w.person("Bob", "Kasongo");
    w.problem(
        readRequest(w.admin, bob.employee(), "limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(mineRequest(ana.bearer(), "limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(readRequest(w.admin, employee, "cursor=garbage"), 400, "CURSOR_INVALID");
    for (String limit : List.of("0", "101", "-1", "x", "1.5")) {
      w.problem(readRequest(w.admin, employee, "limit=" + limit), 400, "VALIDATION_FAILED");
    }
    // The employee pages through their own history the same way.
    JsonNode mine = w.expect(mineRequest(ana.bearer(), "limit=2"), 200);
    assertThat(mine.get("changes")).hasSize(2);
    JsonNode mineLast =
        w.expect(
            mineRequest(ana.bearer(), "limit=2&cursor=" + mine.get("nextCursor").asText()), 200);
    assertThat(mineLast.get("changes")).hasSize(1);
  }

  // ------------------------------------------------------------------------------------------
  // Employment and separation
  // ------------------------------------------------------------------------------------------

  @Test
  void aSeparationBeforeAnActiveCompensationStartsIsAConflictUntilItIsCorrected() throws Exception {
    UUID employee = ana.employee();
    establish(ana, "USD", "10.00");
    w.record(employee, command("CHANGED", w.today.plusDays(30), "USD", "12.00"), false);
    String path = "/api/v1/employees/" + employee + "/separations/preview";
    Map<String, Object> before =
        Map.of(
            "lastDay", w.today.plusDays(29).toString(),
            "reasonCode", "RESIGNATION",
            "accessTiming", "END_OF_LAST_DAY");
    w.problem(
        CompensationWorld.json(path, w.admin, null, before),
        409,
        "COMPENSATION_EMPLOYMENT_CONFLICT");
    // On the start day itself the compensation no longer prevents it.
    Map<String, Object> onStart =
        Map.of(
            "lastDay", w.today.plusDays(30).toString(),
            "reasonCode", "RESIGNATION",
            "accessTiming", "END_OF_LAST_DAY");
    MockHttpServletResponse response = w.call(CompensationWorld.json(path, w.admin, null, onStart));
    assertThat(response.getContentAsString()).doesNotContain("COMPENSATION_EMPLOYMENT_CONFLICT");
    // Nothing about compensation changed.
    assertThat(w.evidence().get(0)).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------
  // Privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void noAmountOrExplanationReachesAuditEventsIdempotencyCursorsLogsOrErrors(CapturedOutput output)
      throws Exception {
    UUID employee = ana.employee();
    JsonNode established = establish(ana, "CDF", "7654321");
    w.record(employee, command("CHANGED", w.today.plusDays(9), "USD", "8765.43"), false);
    String explanation = "Sentinel-explication-9f3a7c1d";
    Map<String, Object> correct =
        new LinkedHashMap<>(
            correction(
                w.today, "CDF", "7654322", UUID.fromString(established.get("changeId").asText())));
    correct.put("explanation", explanation);
    JsonNode preview = w.preview(employee, correct);
    w.expect(commitRequest(w.admin, employee, key(), committing(correct, preview, true)), 201);
    // A refusal that names its input must not echo it.
    JsonNode refusal =
        w.problem(
            previewRequest(w.admin, employee, command("ESTABLISHED", w.today, "USD", "76,54")),
            400,
            "COMPENSATION_INVALID_AMOUNT");
    assertThat(refusal.toString()).doesNotContain("76,54");
    JsonNode page = w.expect(readRequest(w.admin, employee, "limit=1"), 200);
    w.expect(mineRequest(ana.bearer(), "limit=1"), 200);

    String evidence = w.persistedEvidence();
    assertThat(evidence).isNotBlank();
    for (String secret : List.of("7654321", "7654322", "8765.43", "876543", explanation)) {
      assertThat(evidence).as("persisted evidence").doesNotContain(secret);
      assertThat(page.get("nextCursor").asText()).doesNotContain(secret);
      assertThat(output.getAll()).as("logs").doesNotContain(secret);
    }
    // No name, employee number or subject either.
    assertThat(evidence).doesNotContain("Mukendi", ana.subject());
    assertThat(output.getAll()).doesNotContain("Mukendi");
    // The request key and digests are not logged.
    assertThat(output.getAll()).doesNotContain(preview.get("previewDigest").asText());
  }
}
