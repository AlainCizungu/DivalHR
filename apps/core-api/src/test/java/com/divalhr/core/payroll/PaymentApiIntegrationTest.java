package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-095A (Issue #183, ADR 0036) against real PostgreSQL through the public API: encrypted,
 * append-only payment destinations that answer only masked values; a payment batch prepared from an
 * approved package with the exact net amounts and currency copied and no partial batch; the payroll
 * void guard; append-only cancellation that releases it; deterministic downloads; stale evidence;
 * privacy sentinels and tenant isolation. Synthetic data only: nothing is sent and no money moves.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
class PaymentApiIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private Chain chain;
  private CompensationWorld.Person ana;
  private String pkg;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    chain = new Chain(pack, ana.employee(), run, calculation);
    pkg = w.approve(chain).get("packageId").asText();
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private JsonNode recordBank() throws Exception {
    return p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
  }

  // ------------------------------------------------------------------------------------------
  // Destinations
  // ------------------------------------------------------------------------------------------

  @Test
  void aDestinationIsStoredEncryptedAndOnlyMaskedValuesAreReturned() throws Exception {
    JsonNode receipt = recordBank();
    assertThat(receipt.get("maskedValue").asText()).isEqualTo("****7766");
    assertThat(receipt.get("status").asText()).isEqualTo("RECORDED");
    assertThat(receipt.get("version").asInt()).isEqualTo(1);
    assertThat(receipt.toString())
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);

    MockHttpServletResponse read =
        w.c.call(PayrollRunWorld.get(w.admin, PaymentWorld.destinationPath(ana.employee())));
    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(read.getHeader("Cache-Control")).contains("no-store").contains("private");
    assertThat(read.getContentAsString())
        .contains("****7766")
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT masked_value, fingerprint, key_version,"
                + " encode(sealed, 'escape') AS raw FROM payroll.payment_destination"
                + " WHERE tenant_id = ? AND employee_id = ?",
            w.tenant,
            ana.employee());
    assertThat(row.get("masked_value")).isEqualTo("****7766");
    assertThat((String) row.get("fingerprint")).matches("[0-9a-f]{64}");
    assertThat(row.get("key_version")).isEqualTo((short) 1);
    assertThat((String) row.get("raw"))
        .doesNotContain("SYNTHACCT")
        .doesNotContain("Sentinel")
        .doesNotContain("KIN-01");
    assertThat(p.persistedEvidence())
        .doesNotContain("SYNTHACCT")
        .doesNotContain("Sentinel")
        .doesNotContain("+24399");
  }

  @Test
  void mobileMoneyAndManualDestinationsAreRecordedWithTheirMasks() throws Exception {
    JsonNode mobile =
        p.record(ana.employee(), p.mobile(PaymentWorld.PHONE, p.early(), "INITIAL_SETUP"));
    assertThat(mobile.get("maskedValue").asText()).isEqualTo("******122");
    assertThat(mobile.toString()).doesNotContain(PaymentWorld.PHONE);
    JsonNode manual =
        p.record(ana.employee(), p.manual(p.early().plusDays(1), "EMPLOYEE_REQUEST"));
    assertThat(manual).isNotNull();
  }

  @Test
  void historyIsAppendOnlyAndRowsCannotBeChangedOrDeleted() throws Exception {
    recordBank();
    p.record(
        ana.employee(),
        p.mobile(PaymentWorld.PHONE, p.early().plusDays(2), "PROVIDER_CHANGE"));
    JsonNode history =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin, PaymentWorld.destinationPath(ana.employee()) + "/history"),
            200);
    assertThat(history.get("items")).hasSize(2);
    assertThat(history.toString()).doesNotContain(PaymentWorld.ACCOUNT);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE payroll.payment_destination SET masked_value = 'x' WHERE tenant_id = ?",
                    w.tenant))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM payroll.payment_destination WHERE tenant_id = ?", w.tenant))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void anUnchangedDestinationAndAStaleVersionAreRefused() throws Exception {
    recordBank();
    Map<String, Object> same =
        p.bank(PaymentWorld.ACCOUNT, p.early().plusDays(3), "ACCOUNT_CORRECTION");
    JsonNode preview = p.previewDestination(ana.employee(), same);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("blockers")).isNotEmpty();

    Map<String, Object> changed =
        p.bank(PaymentWorld.ACCOUNT_2, p.early().plusDays(3), "ACCOUNT_CORRECTION");
    JsonNode ready = p.previewDestination(ana.employee(), changed);
    Map<String, Object> body = PaymentWorld.committing(changed, ready);
    body.put("expectedVersion", 0);
    w.c.problem(
        p.destinationCommit(w.admin, ana.employee(), Organizations.newKey(), body),
        409,
        "PAYMENT_DESTINATION_VERSION_CONFLICT");
  }

  @Test
  void theSameIdempotencyKeyReplaysTheSameReceiptWithoutASecondRow() throws Exception {
    Map<String, Object> command = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(ana.employee(), command);
    Map<String, Object> body = PaymentWorld.committing(command, preview);
    String key = Organizations.newKey();
    JsonNode first = w.c.expect(p.destinationCommit(w.admin, ana.employee(), key, body), 201);
    JsonNode second = w.c.expect(p.destinationCommit(w.admin, ana.employee(), key, body), 201);
    assertThat(second).isEqualTo(first);
    assertThat(p.evidence().get(0)).isEqualTo(1);
  }

  @Test
  void aLinkedEmployeeReadsOnlyTheirOwnMaskedDestination() throws Exception {
    recordBank();
    JsonNode mine = w.c.expect(PayrollRunWorld.get(ana.bearer(), PaymentWorld.MINE), 200);
    assertThat(mine.get("maskedValue").asText()).isEqualTo("****7766");
    assertThat(mine.toString()).doesNotContain(PaymentWorld.ACCOUNT);
    CompensationWorld.Person colleague = w.linkedEmployee("Kalala", "90000");
    JsonNode none = w.c.expect(PayrollRunWorld.get(colleague.bearer(), PaymentWorld.MINE), 200);
    assertThat(none.get("status").asText()).isEqualTo("NOT_RECORDED");
    assertThat(none.toString()).doesNotContain("7766");
    // An employee cannot reach the administrator path.
    MockHttpServletResponse denied =
        w.c.call(PayrollRunWorld.get(ana.bearer(), PaymentWorld.destinationPath(ana.employee())));
    assertThat(denied.getStatus()).isIn(403, 404);
  }

  // ------------------------------------------------------------------------------------------
  // Batches
  // ------------------------------------------------------------------------------------------

  @Test
  void aBatchCopiesTheApprovedNetAmountExactlyAndMasksEveryDestination() throws Exception {
    recordBank();
    JsonNode preview = p.previewBatch(pkg);
    assertThat(preview.get("status").asText()).isEqualTo("READY");
    assertThat(preview.get("currency").asText()).isEqualTo("CDF");
    assertThat(preview.get("lineCount").asInt()).isEqualTo(1);
    assertThat(preview.get("zeroNetExcludedCount").asInt()).isZero();
    assertThat(preview.get("methodCounts").get("bankAccount").asInt()).isEqualTo(1);
    JsonNode line = preview.get("lines").get(0);
    assertThat(line.get("maskedValue").asText()).isEqualTo("****7766");
    assertThat(line.get("netMinor").asLong()).isEqualTo(preview.get("totalNetMinor").asLong());
    assertThat(preview.toString()).doesNotContain(PaymentWorld.ACCOUNT);

    JsonNode receipt =
        w.c.expect(
            p.prepareRequest(w.admin, pkg, Organizations.newKey(), PaymentWorld.preparing(preview)),
            201);
    assertThat(receipt.get("status").asText()).isEqualTo("PREPARED");
    assertThat(receipt.get("currency").asText()).isEqualTo("CDF");
    assertThat(receipt.get("lineCount").asInt()).isEqualTo(1);

    // The exact approved net (149040 CDF), never recalculated.
    assertThat(Long.toString(preview.get("totalNetMinor").asLong())).startsWith("149040");
    long stored =
        jdbc.queryForObject(
            "SELECT net_minor FROM payroll.payment_batch_line WHERE tenant_id = ?",
            Long.class,
            w.tenant);
    assertThat(stored).isEqualTo(line.get("netMinor").asLong());
  }

  @Test
  void aBatchWithAMissingDestinationIsBlockedAndNothingIsWritten() throws Exception {
    List<Integer> before = p.evidence();
    JsonNode preview = p.previewBatch(pkg);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(preview.get("blockers")).isNotEmpty();
    assertThat(p.evidence()).isEqualTo(before);
  }

  @Test
  void aSecondPreparedBatchForTheSamePackageIsRefused() throws Exception {
    recordBank();
    p.prepare(pkg);
    JsonNode again = p.previewBatch(pkg);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(p.evidence().get(1)).isEqualTo(1);
  }

  @Test
  void aStaleDestinationMakesTheBatchPreviewStale() throws Exception {
    recordBank();
    JsonNode preview = p.previewBatch(pkg);
    p.record(
        ana.employee(),
        p.bank(PaymentWorld.ACCOUNT_2, p.early().plusDays(1), "ACCOUNT_CORRECTION"));
    w.c.problem(
        p.prepareRequest(w.admin, pkg, Organizations.newKey(), PaymentWorld.preparing(preview)),
        409,
        "PAYMENT_BATCH_PREVIEW_STALE");
    assertThat(p.evidence().get(1)).isZero();
  }

  @Test
  void aPreparedBatchBlocksTheVoidAndCancellingReleasesIt() throws Exception {
    recordBank();
    String batch = p.prepare(pkg).get("batchId").asText();
    JsonNode blocked = w.voidPreview(pkg, "ADMINISTRATIVE_ERROR", null);
    assertThat(blocked.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(PayrollPackageWorld.texts(blocked.get("blockers"))).contains("PAYMENT_EXISTS");

    JsonNode cancelled = p.cancel(batch, "PREPARED_IN_ERROR");
    assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");

    // Cancellation is append-only: the batch stays, the lines stay, nothing is reactivated.
    assertThat(p.evidence().get(1)).isEqualTo(1);
    assertThat(p.evidence().get(3)).isEqualTo(1);
    JsonNode again = w.c.expect(p.cancelPreview(w.admin, batch, "PREPARED_IN_ERROR"), 200);
    assertThat(again.get("status").asText()).isEqualTo("BLOCKED");
    Map<String, Object> replay = PaymentWorld.cancelCommand("PREPARED_IN_ERROR");
    replay.put("batchVersion", 1);
    replay.put("batchDigest", "0".repeat(64));
    replay.put("previewDigest", "0".repeat(64));
    replay.put("expiresAt", clock.instant().plusSeconds(60).toString());
    replay.put("noMoneySentAcknowledged", true);
    w.c.problem(
        p.cancelRequest(w.admin, batch, Organizations.newKey(), replay),
        409,
        "PAYMENT_BATCH_CANCELLED");

    // The package can now be voided.
    JsonNode voided = w.voidPackage(pkg, "ADMINISTRATIVE_ERROR", null);
    assertThat(voided).isNotNull();
    assertThat(w.packageDetail(pkg).get("package").get("status").asText()).isEqualTo("VOIDED");
  }

  @Test
  void downloadsAreDeterministicSafeAndPrivate() throws Exception {
    recordBank();
    String batch = p.prepare(pkg).get("batchId").asText();
    MockHttpServletResponse csv = p.download(w.admin, batch, "register.csv");
    assertThat(csv.getStatus()).isEqualTo(200);
    assertThat(csv.getHeader("Cache-Control")).contains("no-store");
    assertThat(csv.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(csv.getHeader("Content-Disposition")).startsWith("attachment");
    String body = csv.getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
        .startsWith("batchId")
        .contains("****7766")
        .contains("\r\n")
        .doesNotContain(PaymentWorld.ACCOUNT)
        .doesNotContain(PaymentWorld.HOLDER);
    assertThat(p.download(w.admin, batch, "register.csv").getContentAsByteArray())
        .isEqualTo(csv.getContentAsByteArray());
    MockHttpServletResponse manifest = p.download(w.admin, batch, "manifest.json");
    assertThat(manifest.getStatus()).isEqualTo(200);
    assertThat(manifest.getContentAsString()).doesNotContain(PaymentWorld.ACCOUNT);
    assertThat(p.download(w.admin, batch, "manifest.json").getContentAsByteArray())
        .isEqualTo(manifest.getContentAsByteArray());
    assertThat(
            w.c.count(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action = 'payment-register.download'",
                w.tenant))
        .isGreaterThanOrEqualTo(4);
  }

  // ------------------------------------------------------------------------------------------
  // Authority and tenant isolation
  // ------------------------------------------------------------------------------------------

  @Test
  void aForeignTenantAdministratorAndANonAdministratorReachNothing() throws Exception {
    recordBank();
    String batch = p.prepare(pkg).get("batchId").asText();
    PayrollPackageWorld otherWorld = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    PaymentWorld other = new PaymentWorld(otherWorld);
    List<Integer> before = p.evidence();

    JsonNode a =
        w.c.expect(PayrollRunWorld.get(otherWorld.admin, PaymentWorld.destinationPath(ana.employee())), 404);
    assertThat(a.get("code").asText()).isEqualTo("PAYMENT_DESTINATION_NOT_FOUND");
    for (String path : List.of(other.batchPath(batch), p.batchPath(batch))) {
      JsonNode problem = w.c.expect(PayrollRunWorld.get(otherWorld.admin, path), 404);
      assertThat(problem.get("code").asText()).isIn("PAYMENT_BATCH_NOT_FOUND", "LEGAL_ENTITY_NOT_FOUND");
    }
    assertThat(
            w.c.call(PayrollRunWorld.get(otherWorld.admin, p.batchPath(batch) + "/register.csv"))
                .getStatus())
        .isEqualTo(404);
    for (String path :
        List.of(p.batchPath(batch), p.batchesPath(), p.batchPath(batch) + "/register.csv")) {
      assertThat(w.c.call(PayrollRunWorld.get(ana.bearer(), path)).getStatus())
          .as(path)
          .isIn(403, 404);
    }
    assertThat(p.evidence()).isEqualTo(before);
  }

  @Test
  void theCommandsAreRefusedWithoutAnIdempotencyKey() throws Exception {
    Map<String, Object> command = p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(ana.employee(), command);
    MockHttpServletResponse missing =
        w.c.call(p.destinationCommit(w.admin, ana.employee(), null, PaymentWorld.committing(command, preview)));
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(p.evidence().get(0)).isZero();
  }

  @Test
  void aWrongDateIsRefusedWithoutStoringAnything() throws Exception {
    Map<String, Object> command =
        p.bank(PaymentWorld.ACCOUNT, LocalDate.of(1999, 12, 31), "INITIAL_SETUP");
    JsonNode preview = p.previewDestination(ana.employee(), command);
    assertThat(preview.get("status").asText()).isEqualTo("BLOCKED");
    assertThat(p.evidence().get(0)).isZero();
  }
}
