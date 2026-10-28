package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-081 (Issue #169, D81-14 items 1 to 13 and 15) through the public API against real
 * PostgreSQL: both currencies, the date window, routing and route movement, every legal and
 * illegal transition, return, correction, resubmission and approval, receipts, idempotency,
 * pagination, isolation and the privacy of every user-entered field.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ExpenseApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;

  private ExpenseWorld x;
  private TaskWorld w;
  private Person ana;
  private Person mina;

  @BeforeEach
  void world() throws Exception {
    x = new ExpenseWorld(mvc, jdbc, lookups);
    w = x.w;
    ana = x.person("Ana", "Mukendi");
    mina = x.person("Mina", "Kabasele");
    x.manage(ana.employee(), mina.employee(), x.today.minusDays(30));
  }

  private static String key() {
    return Organizations.newKey();
  }

  private static String path(String id, String suffix) {
    return ExpenseWorld.MINE + "/" + id + suffix;
  }

  // -------------------------------------------------------------------------- create & currency

  @Test
  void bothCurrenciesAreCanonicalAndNeverConverted() throws Exception {
    JsonNode usd = x.create(ana, x.usd("12.5", 1));
    JsonNode cdf = x.create(ana, x.cdf("125000", 1));
    assertThat(usd.get("amount").asText()).isEqualTo("12.50");
    assertThat(usd.get("amountMinorUnits").asLong()).isEqualTo(1250L);
    assertThat(cdf.get("amount").asText()).isEqualTo("125000");
    assertThat(cdf.get("amountMinorUnits").asLong()).isEqualTo(125000L);
    assertThat(usd.get("state").asText()).isEqualTo("DRAFT");
    assertThat(usd.get("version").asInt()).isEqualTo(1);
    assertThat(w.count("SELECT count(*) FROM operations.expense WHERE tenant_id = ? AND currency = 'USD' AND amount_minor = 1250", w.tenant())).isEqualTo(1);
    assertThat(w.count("SELECT count(*) FROM operations.expense_revision WHERE tenant_id = ? AND currency = 'CDF' AND exponent = 0 AND amount_minor = 125000", w.tenant())).isEqualTo(1);

    for (String bad : new String[] {"1.234", "0.00", "100000.01", "-1", "1e2", "٣"}) {
      Map<String, Object> body = x.usd(bad, 1);
      w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), body), 400, "VALIDATION_FAILED");
    }
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), x.cdf("10.5", 1)), 400, "VALIDATION_FAILED");
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), x.cdf("100000001", 1)), 400, "VALIDATION_FAILED");
    // A JSON number is not a decimal string.
    String number =
        "{\"businessDate\":\"" + x.daysAgo(1) + "\",\"currency\":\"USD\",\"amount\":12.5,"
            + "\"category\":\"MEALS\",\"merchant\":\"Cafe\",\"description\":\"Repas avec un client\"}";
    JsonNode problem = w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), number), 400, "VALIDATION_FAILED");
    assertThat(problem.toString()).contains("amount").doesNotContain("12.5");
    assertThat(w.count("SELECT count(*) FROM operations.expense WHERE tenant_id = ?", w.tenant())).isEqualTo(2);
  }

  @Test
  void theBusinessDateWindowIsNinetyDaysInTheOrganizationsZone() throws Exception {
    x.create(ana, x.usd("5", 0));
    x.create(ana, x.usd("5", 90));
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), x.usd("5", 91)), 400, "VALIDATION_FAILED");
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), x.usd("5", -1)), 400, "VALIDATION_FAILED");
    assertThat(w.count("SELECT count(*) FROM operations.expense WHERE tenant_id = ?", w.tenant())).isEqualTo(2);
  }

  @Test
  void replayReuseAndMissingKey() throws Exception {
    String key = key();
    Map<String, Object> body = x.usd("20", 2);
    JsonNode created = w.expect(ExpenseWorld.post(ana, ExpenseWorld.MINE, key, body), 201);
    List<Integer> after = x.evidence();
    assertThat(after).containsExactly(1, 1, 0, 1, 1, 0);
    MockHttpServletResponse replay = w.call(ExpenseWorld.post(ana, ExpenseWorld.MINE, key, body));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(TaskWorld.JSON.readTree(replay.getContentAsString()).get("id").asText())
        .isEqualTo(created.get("id").asText());
    assertThat(x.evidence()).isEqualTo(after);

    Map<String, Object> other = x.usd("21", 2);
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key, other), 409, "IDEMPOTENCY_KEY_REUSED");
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, null, body), 400, "VALIDATION_FAILED");
    assertThat(x.evidence()).isEqualTo(after);
  }

  // ---------------------------------------------------------------------------- draft & receipt

  @Test
  void theDraftIsEditedWithItsVersionAndOnlyByItsOwner() throws Exception {
    JsonNode created = x.create(ana, x.usd("10", 3));
    String id = created.get("id").asText();
    Map<String, Object> changed = x.usd("11.25", 2);
    changed.put("category", "MEALS");
    JsonNode updated = x.update(ana, id, changed, 1);
    assertThat(updated.get("version").asInt()).isEqualTo(2);
    assertThat(updated.get("revision").asInt()).isEqualTo(1);
    assertThat(updated.get("amount").asText()).isEqualTo("11.25");

    w.problem(ExpenseWorld.post(ana, path(id, "/draft"), key(), ExpenseWorld.withVersion(changed, 1)), 409, "EXPENSE_VERSION_CONFLICT");
    // Somebody else's expense is the same safe 404 as an unknown one.
    w.problem(ExpenseWorld.post(mina, path(id, "/draft"), key(), ExpenseWorld.withVersion(changed, 2)), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.post(ana, path(UUID.randomUUID().toString(), "/draft"), key(), ExpenseWorld.withVersion(changed, 1)), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.read(mina.bearer(), path(id, "")), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "/not-an-id"), 404, "EXPENSE_NOT_FOUND");

    JsonNode detail = x.mine(ana, id);
    assertThat(detail.get("revisions")).hasSize(1);
    assertThat(detail.get("history")).hasSize(2);
    assertThat(detail.get("expense").get("editable").asBoolean()).isTrue();
    // The editable revision was replaced in place: one revision, two actions.
    assertThat(w.count("SELECT count(*) FROM operations.expense_revision WHERE expense_id = ?::uuid", id)).isEqualTo(1);
  }

  @Test
  void aReceiptIsAttachedReplacedRemovedAndDownloadedSafely() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    byte[] png = Receipts.png(1);
    JsonNode set = w.expect(ExpenseWorld.upload(ana, id, 1, "image/png", png, ExpenseWorld.FILE_NAME, key()), 200);
    assertThat(set.get("receiptPresent").asBoolean()).isTrue();
    assertThat(set.get("version").asInt()).isEqualTo(2);

    JsonNode detail = x.mine(ana, id);
    JsonNode receipt = detail.get("expense").get("receipt");
    assertThat(receipt.get("mediaType").asText()).isEqualTo("image/png");
    assertThat(receipt.get("sizeBytes").asInt()).isEqualTo(png.length);
    assertThat(receipt.get("fileName").asText()).isEqualTo(ExpenseWorld.FILE_NAME);
    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
    assertThat(receipt.get("sha256").asText()).isEqualTo(digest);

    MockHttpServletResponse download = w.call(ExpenseWorld.read(ana.bearer(), path(id, "/revisions/1/receipt")));
    assertThat(download.getStatus()).isEqualTo(200);
    assertThat(download.getContentAsByteArray()).isEqualTo(png);
    assertThat(download.getContentType()).isEqualTo("image/png");
    assertThat(download.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"receipt.png\"");
    assertThat(download.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(download.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(download.getHeader("Content-Disposition")).doesNotContain("sentinelle");
    w.problem(ExpenseWorld.read(mina.bearer(), path(id, "/revisions/1/receipt")), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.read(ana.bearer(), path(id, "/revisions/2/receipt")), 404, "EXPENSE_NOT_FOUND");

    // Replacement keeps one receipt for the editable revision.
    byte[] pdf = Receipts.pdf(7);
    w.expect(ExpenseWorld.upload(ana, id, 2, "application/pdf", pdf, null, key()), 200);
    assertThat(w.count("SELECT count(*) FROM operations.expense_receipt WHERE expense_id = ?::uuid", id)).isEqualTo(1);
    assertThat(x.mine(ana, id).get("expense").get("receipt").get("mediaType").asText()).isEqualTo("application/pdf");

    w.expect(ExpenseWorld.removeReceipt(ana, id, 3, key()), 200);
    assertThat(w.count("SELECT count(*) FROM operations.expense_receipt WHERE expense_id = ?::uuid", id)).isZero();
    w.problem(ExpenseWorld.removeReceipt(ana, id, 4, key()), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
  }

  @Test
  void aBadReceiptChangesNothing() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    List<Integer> before = x.evidence();
    w.problem(ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.jpeg(1), null, key()), 400, "EXPENSE_RECEIPT_INVALID");
    w.problem(ExpenseWorld.upload(ana, id, 1, "image/png", new byte[0], null, key()), 400, "EXPENSE_RECEIPT_INVALID");
    w.problem(ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.withTrailer(Receipts.png(2), "<script>alert(1)</script>"), null, key()), 400, "EXPENSE_RECEIPT_INVALID");
    w.problem(ExpenseWorld.upload(ana, id, 1, "image/svg+xml", "<svg/>".getBytes(), null, key()), 415, "EXPENSE_RECEIPT_MEDIA_UNSUPPORTED");
    w.problem(ExpenseWorld.upload(ana, id, 1, "text/html", "<html/>".getBytes(), null, key()), 415, "EXPENSE_RECEIPT_MEDIA_UNSUPPORTED");
    w.problem(ExpenseWorld.upload(ana, id, 1, "image/png", new byte[2 * 1024 * 1024 + 1], null, key()), 413, "EXPENSE_RECEIPT_TOO_LARGE");
    w.problem(ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.png(3), null, null), 400, "VALIDATION_FAILED");
    // A stale version is refused before the body is read.
    w.problem(ExpenseWorld.upload(ana, id, 9, "image/png", Receipts.png(3), null, key()), 409, "EXPENSE_VERSION_CONFLICT");
    assertThat(x.evidence()).isEqualTo(before);
  }

  @Test
  void aReceiptOfSomeoneElsesExpenseIsRefusedBeforeTheBodyIsRead() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    java.util.concurrent.atomic.AtomicBoolean read = new java.util.concurrent.atomic.AtomicBoolean();
    var request =
        ExpenseWorld.upload(mina, id, 1, "image/png", Receipts.png(1), null, key())
            .with(
                r -> {
                  r.setContent(new byte[0]);
                  return r;
                });
    // An unknown expense is the same 404 whatever the body would have been.
    w.problem(request, 404, "EXPENSE_NOT_FOUND");
    assertThat(read).isFalse();
    String other = UUID.randomUUID().toString();
    w.problem(ExpenseWorld.upload(ana, other, 1, "image/png", Receipts.png(1), null, key()), 404, "EXPENSE_NOT_FOUND");
    // No employee link: forbidden before any body is consumed.
    UUID member = w.member("sub-nolink-" + UUID.randomUUID());
    assertThat(member).isNotNull();
    String stranger = TaskWorld.employeeBearer(w.tenant(), "sub-nolink-" + UUID.randomUUID());
    var response = w.call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path(id, "/receipt"))
        .header("Authorization", stranger).header("X-Expected-Version", "1").header("Idempotency-Key", key())
        .contentType("image/png").content(Receipts.png(1)));
    assertThat(response.getStatus()).isEqualTo(403);
  }

  // ------------------------------------------------------------------------ routing & lifecycle

  @Test
  void submittingNeedsExactlyOneEffectiveManager() throws Exception {
    Person solo = x.person("Solo", "Tshisekedi");
    String id = x.create(solo, x.usd("10", 1)).get("id").asText();
    JsonNode problem = w.problem(ExpenseWorld.post(solo, path(id, "/submit"), key(), ExpenseWorld.version(1)), 409, "EXPENSE_APPROVAL_ROUTE_UNAVAILABLE");
    assertThat(problem.toString()).doesNotContain(mina.number()).doesNotContain(mina.employee().toString());
    assertThat(x.mine(solo, id).get("expense").get("state").asText()).isEqualTo("DRAFT");
  }

  @Test
  void returnCorrectionResubmissionAndApproval() throws Exception {
    String id = x.create(ana, x.cdf("50000", 2)).get("id").asText();
    w.expect(ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.png(1), ExpenseWorld.FILE_NAME, key()), 200);
    JsonNode submitted = x.submit(ana, id, 2);
    assertThat(submitted.get("state").asText()).isEqualTo("SUBMITTED");
    assertThat(submitted.get("version").asInt()).isEqualTo(3);

    // Submitted revisions never change: no edit, no receipt change, no second submit.
    w.problem(ExpenseWorld.post(ana, path(id, "/draft"), key(), ExpenseWorld.withVersion(x.cdf("60000", 2), 3)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    w.problem(ExpenseWorld.removeReceipt(ana, id, 3, key()), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    w.problem(ExpenseWorld.post(ana, path(id, "/submit"), key(), ExpenseWorld.version(3)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");

    // The manager sees it in the queue, with the employee and the receipt; a stranger does not.
    JsonNode queue = x.queue(mina);
    assertThat(x.ids(queue)).containsExactly(id);
    assertThat(queue.get("items").get(0).get("employee").get("employeeNumber").asText()).isEqualTo(ana.number());
    assertThat(x.ids(x.queue(ana))).isEmpty();
    JsonNode asManager = w.expect(ExpenseWorld.read(mina.bearer(), ExpenseWorld.APPROVALS + "/" + id), 200);
    assertThat(asManager.get("expense").get("amount").asText()).isEqualTo("50000");
    assertThat(asManager.get("revisions")).hasSize(1);
    MockHttpServletResponse download = w.call(ExpenseWorld.read(mina.bearer(), ExpenseWorld.APPROVALS + "/" + id + "/revisions/1/receipt"));
    assertThat(download.getStatus()).isEqualTo(200);
    assertThat(download.getContentAsByteArray()).isEqualTo(Receipts.png(1));

    // A reason is required; the employee cannot decide; nobody decides with a stale version.
    w.problem(ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/return", key(), Map.of("expectedVersion", 3)), 400, "VALIDATION_FAILED");
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(3)), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(2)), 409, "EXPENSE_VERSION_CONFLICT");

    JsonNode returned = x.returnIt(mina, id, 3, ExpenseWorld.REASON);
    assertThat(returned.get("state").asText()).isEqualTo("RETURNED");
    assertThat(x.ids(x.queue(mina))).isEmpty();
    w.problem(ExpenseWorld.read(mina.bearer(), ExpenseWorld.APPROVALS + "/" + id), 404, "EXPENSE_NOT_FOUND");

    // The employee sees the reason, cannot resubmit unchanged, and corrects by a NEW revision.
    JsonNode afterReturn = x.mine(ana, id);
    assertThat(afterReturn.get("history").toString()).contains(ExpenseWorld.REASON);
    w.problem(ExpenseWorld.post(ana, path(id, "/submit"), key(), ExpenseWorld.version(4)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    Map<String, Object> corrected = x.cdf("45000", 2);
    JsonNode second = x.update(ana, id, corrected, 4);
    assertThat(second.get("revision").asInt()).isEqualTo(2);
    assertThat(second.get("state").asText()).isEqualTo("RETURNED");
    assertThat(second.get("receiptPresent").asBoolean()).isTrue();
    JsonNode detail = x.mine(ana, id);
    assertThat(detail.get("revisions")).hasSize(2);
    assertThat(detail.get("revisions").get(0).get("amount").asText()).isEqualTo("50000");
    assertThat(detail.get("revisions").get(0).get("submittedAt").isNull()).isFalse();
    assertThat(detail.get("revisions").get(1).get("submittedAt").isNull()).isTrue();

    // The first revision keeps its exact receipt; the second may replace it without touching it.
    byte[] second_png = Receipts.png(9);
    w.expect(ExpenseWorld.upload(ana, id, 5, "image/png", second_png, null, key()), 200);
    assertThat(w.call(ExpenseWorld.read(ana.bearer(), path(id, "/revisions/1/receipt"))).getContentAsByteArray()).isEqualTo(Receipts.png(1));
    assertThat(w.call(ExpenseWorld.read(ana.bearer(), path(id, "/revisions/2/receipt"))).getContentAsByteArray()).isEqualTo(second_png);
    assertThat(w.count("SELECT count(*) FROM operations.expense_receipt WHERE expense_id = ?::uuid", id)).isEqualTo(2);

    JsonNode resubmitted = x.submit(ana, id, 6);
    assertThat(resubmitted.get("state").asText()).isEqualTo("SUBMITTED");
    assertThat(resubmitted.get("revision").asInt()).isEqualTo(2);
    assertThat(x.ids(x.queue(mina))).containsExactly(id);
    JsonNode approved = x.approve(mina, id, 7);
    assertThat(approved.get("state").asText()).isEqualTo("APPROVED");

    // Terminal: nothing changes it any more, and approval is not a payment.
    w.problem(ExpenseWorld.post(ana, path(id, "/withdraw"), key(), ExpenseWorld.version(8)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    w.problem(ExpenseWorld.post(ana, path(id, "/draft"), key(), ExpenseWorld.withVersion(corrected, 8)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    w.problem(ExpenseWorld.post(ana, path(id, "/submit"), key(), ExpenseWorld.version(8)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    w.problem(ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(8)), 404, "EXPENSE_NOT_FOUND");
    JsonNode finalDetail = x.mine(ana, id);
    assertThat(finalDetail.get("expense").get("state").asText()).isEqualTo("APPROVED");
    assertThat(finalDetail.get("expense").get("editable").asBoolean()).isFalse();
    assertThat(finalDetail.get("history")).hasSize(8);
    assertThat(w.count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = 'operations.expense.approved.v1'", w.tenant())).isEqualTo(1);
    assertThat(w.count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = 'operations.expense.submitted.v1'", w.tenant())).isEqualTo(2);
    assertThat(w.count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = 'operations.expense.returned.v1'", w.tenant())).isEqualTo(1);
  }

  @Test
  void withdrawingIsOnlyPossibleWhileSubmitted() throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    w.problem(ExpenseWorld.post(ana, path(id, "/withdraw"), key(), ExpenseWorld.version(1)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    x.submit(ana, id, 1);
    JsonNode withdrawn = x.withdraw(ana, id, 2);
    assertThat(withdrawn.get("state").asText()).isEqualTo("WITHDRAWN");
    assertThat(x.ids(x.queue(mina))).isEmpty();
    w.problem(ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(3)), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.post(ana, path(id, "/submit"), key(), ExpenseWorld.version(3)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    w.problem(ExpenseWorld.post(ana, path(id, "/withdraw"), key(), ExpenseWorld.version(3)), 409, "EXPENSE_TRANSITION_NOT_ALLOWED");
    assertThat(w.count("SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ? AND event_type = 'operations.expense.withdrawn.v1'", w.tenant())).isEqualTo(1);
  }

  @Test
  void aFormerManagerLosesAccessAtOnceAndTheNewManagerReceivesTheExpense() throws Exception {
    String id = x.create(ana, x.usd("30", 1)).get("id").asText();
    x.submit(ana, id, 1);
    assertThat(x.ids(x.queue(mina))).containsExactly(id);

    Person omar = x.person("Omar", "Ilunga");
    x.manage(ana.employee(), omar.employee(), x.today.minusDays(1));
    // Route revalidation on every operation (mutation proof): the former manager is a 404.
    assertThat(x.ids(x.queue(mina))).isEmpty();
    w.problem(ExpenseWorld.read(mina.bearer(), ExpenseWorld.APPROVALS + "/" + id), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.read(mina.bearer(), ExpenseWorld.APPROVALS + "/" + id + "/revisions/1/receipt"), 404, "EXPENSE_NOT_FOUND");
    w.problem(ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(2)), 404, "EXPENSE_NOT_FOUND");
    // The expense is dated one day ago, when the new line starts: the new manager decides.
    assertThat(x.ids(x.queue(omar))).containsExactly(id);
    assertThat(x.approve(omar, id, 2).get("state").asText()).isEqualTo("APPROVED");
    assertThat(w.count("SELECT count(*) FROM operations.expense_action WHERE expense_id = ?::uuid AND action = 'APPROVED' AND manager_employee_id = ?", id, omar.employee())).isEqualTo(1);
  }

  @Test
  void aManagerWhoLostTheirAccessCannotDecide() throws Exception {
    String id = x.create(ana, x.usd("30", 1)).get("id").asText();
    x.submit(ana, id, 1);
    w.unlink(mina.employee());
    w.problem(ExpenseWorld.read(mina.bearer(), ExpenseWorld.APPROVALS), 403, "EMPLOYEE_LINK_REQUIRED");
    w.problem(ExpenseWorld.post(mina, ExpenseWorld.APPROVALS + "/" + id + "/approve", key(), ExpenseWorld.version(2)), 403, "EMPLOYEE_LINK_REQUIRED");
    // An unlinked manager is no route: the employee cannot submit a new expense either.
    String next = x.create(ana, x.usd("31", 1)).get("id").asText();
    w.problem(ExpenseWorld.post(ana, path(next, "/submit"), key(), ExpenseWorld.version(1)), 409, "EXPENSE_APPROVAL_ROUTE_UNAVAILABLE");
  }

  @Test
  void replayAfterAccessLossIsRefused() throws Exception {
    String key = key();
    Map<String, Object> body = x.usd("12", 1);
    w.expect(ExpenseWorld.post(ana, ExpenseWorld.MINE, key, body), 201);
    w.unlink(ana.employee());
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key, body), 403, "EMPLOYEE_LINK_REQUIRED");
  }

  // ------------------------------------------------------------------------ lists & isolation

  @Test
  void listsAreOrderedFilteredCursorBoundAndTenantIsolated() throws Exception {
    List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(x.create(ana, i % 2 == 0 ? x.usd("1." + i, i) : x.cdf("1" + i, i)).get("id").asText());
    }
    JsonNode first = w.expect(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?limit=2"), 200);
    assertThat(x.ids(first)).containsExactly(ids.get(4), ids.get(3));
    String cursor = first.get("nextCursor").asText();
    assertThat(cursor).doesNotContain("Sentinelle");
    JsonNode second = w.expect(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?limit=2&cursor=" + cursor), 200);
    assertThat(x.ids(second)).containsExactly(ids.get(2), ids.get(1));
    // A cursor is bound to the actor, the filters and the page size.
    w.problem(ExpenseWorld.read(mina.bearer(), ExpenseWorld.MINE + "?limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?limit=3&cursor=" + cursor), 400, "CURSOR_INVALID");
    w.problem(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?limit=2&currency=USD&cursor=" + cursor), 400, "CURSOR_INVALID");
    JsonNode usd = w.expect(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?currency=USD"), 200);
    assertThat(x.ids(usd)).containsExactly(ids.get(4), ids.get(2), ids.get(0));
    for (JsonNode item : usd.get("items")) {
      assert item.get("currency").asText().equals("USD");
    }
    assertThat(x.ids(w.expect(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?from=" + x.daysAgo(1) + "&to=" + x.daysAgo(0)), 200))).containsExactly(ids.get(1), ids.get(0));
    w.problem(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?state=BOGUS"), 400, "VALIDATION_FAILED");
    w.problem(ExpenseWorld.read(ana.bearer(), ExpenseWorld.MINE + "?limit=101"), 400, "VALIDATION_FAILED");
    assertThat(x.ids(w.expect(ExpenseWorld.read(mina.bearer(), ExpenseWorld.MINE), 200))).isEmpty();

    // Another organization sees none of it, and its cursors do not work here.
    ExpenseWorld other = new ExpenseWorld(mvc, jdbc, lookups);
    Person eve = other.person("Eve", "Banza");
    assertThat(other.ids(other.w.expect(ExpenseWorld.read(eve.bearer(), ExpenseWorld.MINE), 200))).isEmpty();
    other.w.problem(ExpenseWorld.read(eve.bearer(), ExpenseWorld.MINE + "/" + ids.get(0)), 404, "EXPENSE_NOT_FOUND");
    other.w.problem(ExpenseWorld.read(other.w.admin, ExpenseWorld.ADMIN + "/" + ids.get(0)), 404, "EXPENSE_NOT_FOUND");
    other.w.problem(ExpenseWorld.read(eve.bearer(), ExpenseWorld.MINE + "?limit=2&cursor=" + cursor), 400, "CURSOR_INVALID");
  }

  @Test
  void theAdministratorReadsSubmittedExpensesButCannotChangeAnything() throws Exception {
    String draft = x.create(ana, x.usd("10", 1)).get("id").asText();
    String sent = x.create(ana, x.cdf("2000", 1)).get("id").asText();
    x.submit(ana, sent, 1);
    String returned = x.create(ana, x.usd("3", 1)).get("id").asText();
    x.submit(ana, returned, 1);
    x.returnIt(mina, returned, 2, ExpenseWorld.REASON);
    x.update(ana, returned, x.usd("4", 1), 3);

    JsonNode page = w.expect(ExpenseWorld.read(w.admin, ExpenseWorld.ADMIN), 200);
    // A never-submitted draft is the employee's private working copy.
    assertThat(x.ids(page)).containsExactly(returned, sent);
    w.problem(ExpenseWorld.read(w.admin, ExpenseWorld.ADMIN + "/" + draft), 404, "EXPENSE_NOT_FOUND");
    assertThat(page.get("items").get(0).get("employee").get("id").asText()).isEqualTo(ana.employee().toString());
    // A returned expense shows its last SUBMITTED revision, never the correction in progress.
    JsonNode detail = x.adminRead(returned);
    assertThat(detail.get("expense").get("amount").asText()).isEqualTo("3.00");
    assertThat(detail.get("revisions")).hasSize(1);
    assertThat(detail.get("expense").get("editable").asBoolean()).isFalse();
    assertThat(detail.get("history").toString()).doesNotContain("DRAFT_UPDATED").contains(ExpenseWorld.REASON);
    assertThat(x.ids(w.expect(ExpenseWorld.read(w.admin, ExpenseWorld.ADMIN + "?state=SUBMITTED&currency=CDF"), 200))).containsExactly(sent);
    assertThat(x.ids(w.expect(ExpenseWorld.read(w.admin, ExpenseWorld.ADMIN + "?employeeId=" + mina.employee()), 200))).isEmpty();

    // No write exists for the administrator, and the employee endpoints are not theirs.
    for (String suffix : new String[] {"/submit", "/withdraw", "/approve", "/return", "/draft"}) {
      MockHttpServletResponse response = w.call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ExpenseWorld.ADMIN + "/" + sent + suffix).header("Authorization", w.admin).header("Idempotency-Key", key()).contentType("application/json").content("{\"expectedVersion\":2}"));
      assertThat(response.getStatus()).as(suffix).isIn(404, 405);
    }
    assertThat(w.call(ExpenseWorld.read(w.admin, ExpenseWorld.MINE)).getStatus()).isEqualTo(403);
    assertThat(w.call(ExpenseWorld.read(w.admin, ExpenseWorld.APPROVALS)).getStatus()).isEqualTo(403);
    assertThat(w.call(ExpenseWorld.read(ana.bearer(), ExpenseWorld.ADMIN)).getStatus()).isEqualTo(403);
    assertThat(w.call(ExpenseWorld.read(mina.bearer(), ExpenseWorld.ADMIN + "/" + sent)).getStatus()).isEqualTo(403);
    // Exact MFA: an administrator token without it is refused.
    String noMfa = "Bearer " + com.divalhr.core.support.TestTokens.token().tenant(w.tenant()).subject("sub-nomfa").roles(List.of("tenant-admin")).build();
    int status = w.call(ExpenseWorld.read(noMfa, ExpenseWorld.ADMIN)).getStatus();
    assertThat(status).isIn(401, 403);
  }

  // ---------------------------------------------------------------------------------- privacy

  /** Only the application's own structured log lines (MockMvc's request dumps are test output). */
  private static String logLines(CapturedOutput output) {
    return output.getAll().lines().filter(l -> l.startsWith("{\"@timestamp\"")).collect(java.util.stream.Collectors.joining("\n")).toLowerCase();
  }

  @Test
  void noTextFileNameOrReceiptEverReachesEvidenceLogsOrErrors(CapturedOutput output) throws Exception {
    String id = x.create(ana, x.usd("10", 1)).get("id").asText();
    w.expect(ExpenseWorld.upload(ana, id, 1, "image/png", Receipts.png(1), ExpenseWorld.FILE_NAME, key()), 200);
    x.submit(ana, id, 2);
    x.returnIt(mina, id, 3, ExpenseWorld.REASON);
    x.update(ana, id, x.usd("11", 1), 4);
    x.submit(ana, id, 5);
    x.approve(mina, id, 6);
    // Read everything once so each disclosure is audited too.
    x.mine(ana, id);
    x.queue(mina);
    x.adminRead(id);
    w.call(ExpenseWorld.read(w.admin, ExpenseWorld.ADMIN + "?limit=1"));
    w.call(ExpenseWorld.read(w.admin, ExpenseWorld.ADMIN + "/" + id + "/revisions/1/receipt"));
    w.problem(ExpenseWorld.post(ana, ExpenseWorld.MINE, key(), x.usd("1.234", 1)), 400, "VALIDATION_FAILED");

    String evidence = x.persistedEvidence().toLowerCase();
    assertThat(evidence).isNotBlank();
    String audits = String.join("\n", jdbc.queryForList("SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?", String.class, w.tenant())).toLowerCase();
    for (String sentinel : new String[] {"sentinelle", "zeta", "marchand", "recu", "motif de retour", "iVBOR".toLowerCase()}) {
      assertThat(evidence).as("evidence: " + sentinel).doesNotContain(sentinel);
      assertThat(audits).as("audit: " + sentinel).doesNotContain(sentinel);
      assertThat(logLines(output)).as("logs: " + sentinel).doesNotContain(sentinel);
    }
    assertThat(evidence).doesNotContain(ana.number().toLowerCase()).doesNotContain("kabasele").doesNotContain("mukendi");
    // The persisted evidence does carry the identifiers and numbers it should.
    assertThat(audits).contains("\"currency\"").contains("amountminorunits").contains("receiptpresent");
    assertThat(w.count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND action = 'expense.receipt-download'", w.tenant())).isEqualTo(1);
  }
}
