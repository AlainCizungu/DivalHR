package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollInputCalls.JSON;
import static com.divalhr.core.payroll.PayrollInputCalls.generate;
import static com.divalhr.core.payroll.PayrollInputCalls.generating;
import static com.divalhr.core.payroll.PayrollInputCalls.preview;
import static com.divalhr.core.payroll.PayrollInputCalls.scope;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.createExport;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.csv;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.export;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.exporting;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.exports;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.lock;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.lockPreview;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.locking;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.manifest;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.supersede;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.supersedePreview;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.superseding;
import static com.divalhr.core.payroll.PayrollLifecycleCalls.versions;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.people.schedule.LockedWeeks;
import com.divalhr.core.people.schedule.LockedWeeks.Week;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * MVP-092 (Issue #149) against real PostgreSQL through the public API: replacement versions after
 * corrected upstream evidence, the final lock, the deterministic worked-time export and its
 * integrity; every guard fails closed with a stable code and without disclosure; nothing is
 * deleted, edited or hidden; audits, events, logs and errors carry no name, employee number,
 * subject, explanation, digest or key.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class PayrollLifecycleIntegrationTest {

  private static final String EXPLANATION = "Scope corrected after the site review";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  @AfterEach
  void reset() {
    clock.reset();
  }

  private static String key() {
    return Organizations.newKey();
  }

  private LockedWeeks weeks() throws Exception {
    return new LockedWeeks(mvc, jdbc, lookups, clock);
  }

  private static Set<String> fields(JsonNode node) {
    Set<String> names = new TreeSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** Generates version 1 of the week and returns its run id. */
  private String generated(LockedWeeks f, Week week) throws Exception {
    Map<String, Object> period = scope(f.legalEntity(), week.monday(), week.sunday());
    JsonNode preview = f.expect(preview(f.admin(), period), 200);
    JsonNode result = f.expect(generate(f.admin(), key(), generating(period, preview)), 200);
    return result.get("run").get("runId").asText();
  }

  /** A corrected, reapproved and relocked week: the upstream evidence changed. */
  private void changeUpstream(LockedWeeks f, Week week) throws Exception {
    f.unlock(week);
    assertThat(f.correctAna(week, 20).getStatus()).isEqualTo(201);
    f.refresh(week);
    f.approveCycle(week, f.ana());
    f.lock(week);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(v -> values.add(v.asText()));
    return values;
  }

  // ------------------------------------------------------------------------------------------
  // Versions, lock and export: the whole lifecycle
  // ------------------------------------------------------------------------------------------

  @Test
  void aCorrectedWeekGetsAReplacementVersionThatIsLockedAndExportedWithItsIntegrity()
      throws Exception {
    LockedWeeks f = weeks();
    Week week = f.locked(f.nextMonday());
    String owner = f.administrator();
    String v1 = generated(f, week);

    // Unchanged upstream evidence: nothing to supersede.
    JsonNode same = f.expect(supersedePreview(f.administrator(), v1), 200);
    assertThat(same.get("ready").asBoolean()).isFalse();
    assertThat(strings(same.get("blockers"))).containsExactly("NO_CHANGED_EVIDENCE");
    assertThat(same.get("current").get("lifecycle").get("state").asText()).isEqualTo("OPEN");
    f.problem(
        supersede(
            f.administrator(),
            key(),
            v1,
            superseding("UPSTREAM_CORRECTION", null, same.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_NOT_READY");
    // Only a locked run can be exported.
    f.problem(
        createExport(f.administrator(), key(), exporting(v1)), 409, "PAYROLL_INPUT_NOT_LOCKED");
    f.problem(
        createExport(f.administrator(), key(), exporting(UUID.randomUUID().toString())),
        404,
        "PAYROLL_INPUT_TARGET_NOT_FOUND");

    // The upstream evidence is corrected: version 1 can no longer be locked, only replaced.
    changeUpstream(f, week);
    JsonNode stale = f.expect(lockPreview(f.administrator(), v1), 200);
    assertThat(stale.get("ready").asBoolean()).isFalse();
    assertThat(strings(stale.get("blockers"))).containsExactly("UPSTREAM_EVIDENCE_CHANGED");
    f.problem(
        lock(f.administrator(), key(), v1, locking(stale.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_NOT_READY");
    // The digest of the unchanged preview is stale now.
    f.problem(
        supersede(
            f.administrator(),
            key(),
            v1,
            superseding("UPSTREAM_CORRECTION", null, same.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_PREVIEW_STALE");

    JsonNode preview = f.expect(supersedePreview(f.administrator(), v1), 200);
    assertThat(preview.get("ready").asBoolean()).isTrue();
    assertThat(preview.get("blockers")).isEmpty();
    assertThat(preview.get("previewDigest").asText()).matches("[0-9a-f]{64}");
    String key = key();
    Map<String, Object> body =
        superseding("UPSTREAM_CORRECTION", null, preview.get("previewDigest").asText());
    MockHttpServletResponse created = f.call(supersede(owner, key, v1, body));
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);
    assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode result = JSON.readTree(created.getContentAsString());
    assertThat(result.get("outcome").asText()).isEqualTo("SUPERSEDED");
    assertThat(result.get("supersedesRunId").asText()).isEqualTo(v1);
    JsonNode second = result.get("run");
    String v2 = second.get("runId").asText();
    assertThat(second.get("version").asInt()).isEqualTo(2);
    assertThat(second.get("lifecycle").get("state").asText()).isEqualTo("OPEN");
    assertThat(second.get("lifecycle").get("latest").asBoolean()).isTrue();
    assertThat(second.get("lifecycle").get("supersedesRunId").asText()).isEqualTo(v1);
    assertThat(second.get("lifecycle").get("reasonCode").asText()).isEqualTo("UPSTREAM_CORRECTION");
    // The identical command replays; the replaced version is replaced only once.
    MockHttpServletResponse replay = f.call(supersede(owner, key, v1, body));
    assertThat(replay.getStatus()).isEqualTo(200);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(JSON.readTree(replay.getContentAsString())).isEqualTo(result);
    f.problem(supersede(f.administrator(), key(), v1, body), 409, "PAYROLL_INPUT_NOT_LATEST");
    assertThat(count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(2);

    // The chain keeps both versions, oldest first, from either id; nothing was edited.
    JsonNode chain = f.expect(versions(f.administrator(), v2), 200);
    assertThat(chain.get("items")).hasSize(2);
    assertThat(chain.get("items").get(0).get("run").get("runId").asText()).isEqualTo(v1);
    assertThat(chain.get("items").get(0).get("run").get("lifecycle").get("latest").asBoolean())
        .isFalse();
    assertThat(chain.get("items").get(1).get("run").get("runId").asText()).isEqualTo(v2);
    assertThat(f.expect(versions(f.administrator(), v1), 200)).isEqualTo(chain);
    assertThat(
            count(
                "SELECT count(*) FROM payroll.input_run WHERE tenant_id = ? AND id = ?::uuid AND"
                    + " version = 1 AND worked_minutes > ?",
                f.tenant(),
                v1,
                second.get("counts").get("workedMinutes").asInt()))
        .as("version 1 keeps its original, higher minutes")
        .isEqualTo(1);

    // Only the latest version is locked.
    JsonNode oldLock = f.expect(lockPreview(f.administrator(), v1), 200);
    assertThat(oldLock.get("ready").asBoolean()).isFalse();
    assertThat(strings(oldLock.get("blockers"))).contains("NOT_LATEST_VERSION");
    f.problem(
        lock(f.administrator(), key(), v1, locking(oldLock.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_NOT_LATEST");
    JsonNode lockReview = f.expect(lockPreview(f.administrator(), v2), 200);
    assertThat(lockReview.get("ready").asBoolean()).isTrue();
    assertThat(lockReview.get("supersedesVersion").asInt()).isEqualTo(1);
    assertThat(lockReview.get("supersedesRunId").asText()).isEqualTo(v1);
    assertThat(lockReview.get("workedMinuteBand").asText()).isEqualTo("1-2400");
    assertThat(lockReview.get("weeks")).hasSize(1);
    String lockKey = key();
    Map<String, Object> locking = locking(lockReview.get("previewDigest").asText());
    JsonNode locked = f.expect(lock(owner, lockKey, v2, locking), 200);
    assertThat(locked.get("outcome").asText()).isEqualTo("LOCKED");
    assertThat(locked.get("run").get("lifecycle").get("state").asText()).isEqualTo("LOCKED");
    assertThat(locked.get("run").get("lifecycle").get("lockActionId").asText())
        .matches("[0-9a-f-]{36}");
    MockHttpServletResponse lockReplay = f.call(lock(owner, lockKey, v2, locking));
    assertThat(lockReplay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    f.problem(lock(f.administrator(), key(), v2, locking), 409, "PAYROLL_INPUT_RUN_LOCKED");
    f.problem(
        supersede(
            f.administrator(),
            key(),
            v2,
            superseding("UPSTREAM_REAPPROVAL", null, preview.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_RUN_LOCKED");
    assertThat(strings(f.expect(supersedePreview(f.administrator(), v2), 200).get("blockers")))
        .contains("ALREADY_LOCKED");
    assertThat(count("SELECT count(*) FROM payroll.input_run_lock WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(1);

    // Identical evidence generates to the latest version (the locked one).
    Map<String, Object> period = scope(f.legalEntity(), week.monday(), week.sunday());
    JsonNode again = f.expect(preview(f.administrator(), period), 200);
    assertThat(again.get("existingRunId").asText()).isEqualTo(v2);
    assertThat(again.get("existingRunUnchanged").asBoolean()).isTrue();
    JsonNode existing =
        f.expect(generate(f.administrator(), key(), generating(period, again)), 200);
    assertThat(existing.get("outcome").asText()).isEqualTo("EXISTING");
    assertThat(existing.get("run").get("runId").asText()).isEqualTo(v2);

    // The export: one per locked run, identical commands return it.
    String exportKey = key();
    MockHttpServletResponse made = f.call(createExport(owner, exportKey, exporting(v2)));
    assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(200);
    JsonNode result2 = JSON.readTree(made.getContentAsString());
    assertThat(result2.get("outcome").asText()).isEqualTo("CREATED");
    JsonNode exported = result2.get("export");
    String exportId = exported.get("exportId").asText();
    assertThat(exported.get("runVersion").asInt()).isEqualTo(2);
    assertThat(exported.get("rows").asInt()).isEqualTo(second.get("counts").get("rows").asInt());
    assertThat(exported.get("workedMinutes").asInt())
        .isEqualTo(second.get("counts").get("workedMinutes").asInt());
    assertThat(exported.get("lockVersion").asInt()).isEqualTo(1);
    assertThat(
            f.call(createExport(owner, exportKey, exporting(v2))).getHeader("Idempotent-Replayed"))
        .isEqualTo("true");
    JsonNode twin = f.expect(createExport(f.administrator(), key(), exporting(v2)), 200);
    assertThat(twin.get("outcome").asText()).isEqualTo("EXISTING");
    assertThat(twin.get("export").get("exportId").asText()).isEqualTo(exportId);
    assertThat(count("SELECT count(*) FROM payroll.input_export WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(1);
    assertThat(f.expect(export(f.administrator(), exportId), 200).get("csvSha256").asText())
        .isEqualTo(exported.get("csvSha256").asText());
    assertThat(f.expect(exports(f.administrator(), null), 200).get("items")).hasSize(1);
    assertThat(
            f.expect(exports(f.administrator(), "legalEntityId=" + f.legalEntity()), 200)
                .get("items"))
        .hasSize(1);
    assertThat(
            f.expect(exports(f.administrator(), "legalEntityId=" + UUID.randomUUID()), 200)
                .get("items"))
        .isEmpty();

    // The CSV: exact columns, CRLF, every source row once, deterministic order, no personal text.
    MockHttpServletResponse file = f.call(csv(f.administrator(), exportId));
    assertThat(file.getStatus()).isEqualTo(200);
    assertThat(file.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(file.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(file.getContentType()).startsWith("text/csv");
    assertThat(file.getHeader("Content-Disposition"))
        .contains("attachment")
        .contains(
            "divalhr-worked-time-inputs-"
                + f.legalEntity()
                + "-"
                + week.monday()
                + "-"
                + week.sunday()
                + "-v2.csv");
    byte[] bytes = file.getContentAsByteArray();
    String text = new String(bytes, StandardCharsets.UTF_8);
    assertThat(text).endsWith("\r\n").doesNotContain("\n\n");
    String[] lines = text.split("\r\n");
    assertThat(lines[0].replace("\"", "")).isEqualTo(String.join(",", PayrollLifecycle.COLUMNS));
    assertThat(lines).hasSize(1 + exported.get("rows").asInt());
    List<String> order = new ArrayList<>();
    int minutes = 0;
    for (int i = 1; i < lines.length; i++) {
      String[] cells = lines[i].replace("\"", "").split(",", -1);
      assertThat(cells).hasSize(18);
      assertThat(cells[16]).isEqualTo(v2);
      assertThat(cells[17]).isEqualTo("2");
      minutes += Integer.parseInt(cells[6]);
      order.add(cells[0] + "|" + cells[1] + "|" + cells[5] + "|" + cells[3]);
    }
    assertThat(minutes).isEqualTo(exported.get("workedMinutes").asInt());
    assertThat(order).isSorted();
    assertThat(text)
        .doesNotContain("Kalala")
        .doesNotContain(f.ana().number())
        .doesNotContain(f.ana().subject())
        .doesNotContain("@");
    assertThat(
            count(
                "SELECT count(*) FROM payroll.input_source_row s WHERE s.run_id = ?::uuid AND"
                    + " EXISTS (SELECT 1 FROM payroll.input_export_row x WHERE x.export_id ="
                    + " ?::uuid AND x.source_line = s.line)",
                v2,
                exportId))
        .isEqualTo(exported.get("rows").asInt());

    // The manifest: the notices, the identity and the SHA-256 of the exact CSV bytes.
    MockHttpServletResponse manifestFile = f.call(manifest(f.administrator(), exportId));
    assertThat(manifestFile.getStatus()).isEqualTo(200);
    assertThat(manifestFile.getHeader("Content-Disposition")).contains("-v2.manifest.json");
    JsonNode manifest = JSON.readTree(manifestFile.getContentAsByteArray());
    assertThat(manifest.get("csvSha256").asText())
        .isEqualTo(PayrollLifecycle.sha256(bytes))
        .isEqualTo(exported.get("csvSha256").asText());
    assertThat(manifest.get("notice").get("en").asText()).isEqualTo(PayrollLifecycle.NOTICE_EN);
    assertThat(manifest.get("notice").get("fr").asText()).isEqualTo(PayrollLifecycle.NOTICE_FR);
    assertThat(manifest.get("purpose").asText())
        .isEqualTo("worked-time inputs for payroll preparation");
    assertThat(manifest.get("tenantId").asText()).isEqualTo(f.tenant().toString());
    assertThat(manifest.get("payrollInputRunId").asText()).isEqualTo(v2);
    assertThat(manifest.get("payrollInputRunVersion").asInt()).isEqualTo(2);
    assertThat(manifest.get("counts").get("rows").asInt()).isEqualTo(exported.get("rows").asInt());
    assertThat(manifest.get("workedMinutes").asInt()).isEqualTo(minutes);
    assertThat(manifest.get("lockVersion").asInt()).isEqualTo(1);
    assertThat(manifest.get("columns")).hasSize(18);
    assertThat(manifestFile.getContentAsString())
        .doesNotContain("Kalala")
        .doesNotContain(f.ana().number())
        .doesNotContain("sub-pay90-");

    // The export is immutable evidence: a later upstream change alters neither the bytes nor the
    // run, and the same bytes come back.
    f.unlock(week);
    assertThat(f.call(csv(f.administrator(), exportId)).getContentAsByteArray()).isEqualTo(bytes);
    assertThat(JSON.readTree(f.call(manifest(f.administrator(), exportId)).getContentAsByteArray()))
        .isEqualTo(manifest);

    // Audits and events: bounded metadata, identifiers only in the state digest.
    List<String> actions =
        jdbc.queryForList(
            "SELECT action FROM platform.audit_event WHERE tenant_id = ? AND action IN"
                + " ('payroll-input.supersede', 'payroll-input.lock',"
                + " 'payroll-input-export.create', 'payroll-input-export.download') ORDER BY"
                + " occurred_at, action",
            String.class,
            f.tenant());
    assertThat(actions)
        .containsExactlyInAnyOrder(
            "payroll-input.supersede",
            "payroll-input.lock",
            "payroll-input-export.create",
            "payroll-input-export.download",
            "payroll-input-export.download",
            "payroll-input-export.download",
            "payroll-input-export.download");
    List<String> eventTypes =
        jdbc.queryForList(
            "SELECT envelope ->> 'eventType' FROM platform.outbox_event WHERE envelope ->>"
                + " 'tenantId' = ? AND envelope ->> 'eventType' LIKE 'payroll.%' ORDER BY"
                + " created_at",
            String.class, f.tenant().toString());
    assertThat(eventTypes)
        .containsExactly(
            "payroll.payroll-input.generated.v1",
            "payroll.payroll-input.superseded.v1",
            "payroll.payroll-input.locked.v1",
            "payroll.payroll-input-export.created.v1");
    JsonNode supersedeAudit =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ? AND action ="
                    + " 'payroll-input.supersede'",
                String.class,
                f.tenant()));
    assertThat(fields(supersedeAudit))
        .containsExactly(
            "employeeCount",
            "periodEnd",
            "periodStart",
            "reasonCode",
            "rowCount",
            "runVersion",
            "schemaVersion",
            "supersedesRunVersion",
            "timesheetCount",
            "weekCount",
            "workedMinuteBand");
    String evidence =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text || ' ' || coalesce(after_state_sha256, '') FROM"
                    + " platform.audit_event WHERE tenant_id = ? AND action LIKE 'payroll-input%'",
                String.class, f.tenant()));
    String events =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT envelope::text FROM platform.outbox_event WHERE envelope ->> 'tenantId' ="
                    + " ? AND envelope ->> 'eventType' LIKE 'payroll.payroll-input%'",
                String.class, f.tenant().toString()));
    for (String disclosed : List.of(evidence, events)) {
      assertThat(disclosed)
          .doesNotContain("Kalala")
          .doesNotContain(f.ana().number())
          .doesNotContain(f.ana().subject())
          .doesNotContain(exported.get("csvSha256").asText())
          .doesNotContain(preview.get("previewDigest").asText())
          .doesNotContain(key)
          .doesNotContain(exportKey)
          .doesNotContain("sub-pay90-");
    }
  }

  // ------------------------------------------------------------------------------------------
  // Guards: validation, explanation, expiry, stale previews, no disclosure
  // ------------------------------------------------------------------------------------------

  @Test
  void everyGuardFailsClosedWithAStableCodeAndWithoutDisclosure(CapturedOutput output)
      throws Exception {
    // Another tenant first (its evidence must never be reachable), then the clock starts over.
    LockedWeeks other = weeks();
    String foreign = other.administrator();
    String foreignRun = generated(other, other.locked(other.nextMonday()));
    clock.reset();
    LockedWeeks f = weeks();
    Week week = f.locked(f.nextMonday());
    String v1 = generated(f, week);
    changeUpstream(f, week);
    JsonNode review = f.expect(supersedePreview(f.administrator(), v1), 200);
    String digest = review.get("previewDigest").asText();

    // The explanation of OTHER: required, bounded plain text; no other reason carries one.
    f.problem(
        supersede(f.administrator(), key(), v1, superseding("OTHER", null, digest)),
        400,
        "VALIDATION_FAILED");
    for (String bad :
        List.of(
            "see https://example.com/page",
            "mail me at someone",
            "<b>bold</b>",
            "call 243 811 222 333",
            "a".repeat(201),
            " padded ")) {
      String text = bad.contains("someone") ? bad.replace("someone", "x@y") : bad;
      JsonNode problem =
          f.problem(
              supersede(f.administrator(), key(), v1, superseding("OTHER", text, digest)),
              400,
              "VALIDATION_FAILED");
      assertThat(problem.toString()).doesNotContain(text).doesNotContain(digest);
    }
    f.problem(
        supersede(
            f.administrator(), key(), v1, superseding("SCOPE_CORRECTION", EXPLANATION, digest)),
        400,
        "VALIDATION_FAILED");
    f.problem(
        supersede(f.administrator(), key(), v1, superseding("PAYROLL", null, digest)),
        400,
        "VALIDATION_FAILED");
    f.problem(
        supersede(f.administrator(), null, v1, superseding("UPSTREAM_CORRECTION", null, digest)),
        400,
        "VALIDATION_FAILED");
    f.problem(
        supersede(f.administrator(), key(), v1, superseding("UPSTREAM_CORRECTION", null, "nope")),
        400,
        "VALIDATION_FAILED");
    f.problem(
        supersede(
            f.administrator(),
            key(),
            v1,
            "{\"reasonCode\":\"UPSTREAM_CORRECTION\","
                + "\"previewDigest\":\""
                + digest
                + "\",\"confirm\":false}"),
        400,
        "VALIDATION_FAILED");
    f.problem(
        supersede(
            f.administrator(),
            key(),
            v1,
            "{\"reasonCode\":\"UPSTREAM_CORRECTION\","
                + "\"previewDigest\":\""
                + digest
                + "\",\"confirm\":true,\"tenantId\":\""
                + f.tenant()
                + "\"}"),
        400,
        "VALIDATION_FAILED");
    assertThat(count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", f.tenant()))
        .isEqualTo(1);

    // An expired preview is stale; a fresh one with OTHER succeeds and shows its explanation
    // only in the version chain.
    Instant now = clock.instant();
    clock.set(Instant.parse(review.get("expiresAt").asText()));
    f.problem(
        supersede(f.administrator(), key(), v1, superseding("UPSTREAM_REAPPROVAL", null, digest)),
        409,
        "PAYROLL_INPUT_PREVIEW_STALE");
    clock.set(now);
    JsonNode fresh = f.expect(supersedePreview(f.administrator(), v1), 200);
    JsonNode result =
        f.expect(
            supersede(
                f.administrator(),
                key(),
                v1,
                superseding("OTHER", EXPLANATION, fresh.get("previewDigest").asText())),
            200);
    String v2 = result.get("run").get("runId").asText();
    assertThat(result.toString()).doesNotContain(EXPLANATION);
    JsonNode chain = f.expect(versions(f.administrator(), v1), 200);
    assertThat(chain.get("items").get(0).get("explanation").isNull()).isTrue();
    assertThat(chain.get("items").get(1).get("explanation").asText()).isEqualTo(EXPLANATION);
    assertThat(chain.get("items").get(1).get("run").get("lifecycle").get("reasonCode").asText())
        .isEqualTo("OTHER");
    assertThat(
            jdbc.queryForList(
                "SELECT metadata::text || envelope::text FROM platform.audit_event a, "
                    + "platform.outbox_event o WHERE a.tenant_id = ? AND o.envelope ->> 'tenantId'"
                    + " = ?",
                String.class,
                f.tenant(),
                f.tenant().toString()))
        .allMatch(row -> !row.contains(EXPLANATION));

    // A lock preview goes stale when the upstream evidence changes after it; an unlocked source
    // week blocks the lock with its own stable code.
    JsonNode lockReview = f.expect(lockPreview(f.administrator(), v2), 200);
    assertThat(lockReview.get("ready").asBoolean()).isTrue();
    f.unlock(week);
    JsonNode open = f.expect(lockPreview(f.administrator(), v2), 200);
    assertThat(open.get("ready").asBoolean()).isFalse();
    assertThat(strings(open.get("blockers"))).containsExactly("SOURCE_PERIOD_NOT_LOCKED");
    f.problem(
        lock(f.administrator(), key(), v2, locking(lockReview.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_PREVIEW_STALE");
    f.problem(
        lock(f.administrator(), key(), v2, locking(open.get("previewDigest").asText())),
        409,
        "PAYROLL_INPUT_NOT_READY");
    f.lock(week);
    JsonNode relocked = f.expect(lockPreview(f.administrator(), v2), 200);
    assertThat(relocked.get("ready").asBoolean()).isFalse();
    assertThat(strings(relocked.get("blockers"))).containsExactly("UPSTREAM_EVIDENCE_CHANGED");
    assertThat(count("SELECT count(*) FROM payroll.input_run_lock WHERE tenant_id = ?", f.tenant()))
        .isZero();

    // Malformed, unknown and foreign targets are one safe 404 with nothing disclosed.
    for (String id : List.of("not-an-id", UUID.randomUUID().toString(), foreignRun)) {
      f.problem(supersedePreview(f.administrator(), id), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(lockPreview(f.administrator(), id), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(versions(f.administrator(), id), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(
          supersede(f.administrator(), key(), id, superseding("UPSTREAM_CORRECTION", null, digest)),
          404,
          "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(
          lock(f.administrator(), key(), id, locking(digest)),
          404,
          "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(
          createExport(f.administrator(), key(), exporting(id)),
          404,
          "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(export(f.administrator(), id), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(csv(f.administrator(), id), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
      f.problem(manifest(f.administrator(), id), 404, "PAYROLL_INPUT_TARGET_NOT_FOUND");
    }
    f.problem(
        createExport(f.administrator(), key(), "{\"runId\":\"nope\",\"confirm\":true}"),
        404,
        "PAYROLL_INPUT_TARGET_NOT_FOUND");
    f.problem(
        createExport(f.administrator(), key(), "{\"confirm\":true}"), 400, "VALIDATION_FAILED");
    f.problem(
        createExport(f.administrator(), key(), "{\"runId\":\"" + v1 + "\",\"confirm\":false}"),
        400,
        "VALIDATION_FAILED");
    assertThat(f.expect(versions(foreign, foreignRun), 200).get("items")).hasSize(1);
    // The foreign administrator's lifecycle is untouched by all of the above.
    assertThat(count("SELECT count(*) FROM payroll.input_run WHERE tenant_id = ?", other.tenant()))
        .isEqualTo(1);

    // Logs: operation, outcome, reason and bands only.
    String application =
        String.join(
            "\n",
            output
                .getAll()
                .lines()
                .filter(line -> line.contains("\"logger_name\":\"divalhr.payroll-input\""))
                .toList());
    assertThat(application)
        .contains("payroll_input_superseded")
        .doesNotContain(v1)
        .doesNotContain(v2)
        .doesNotContain(digest)
        .doesNotContain(EXPLANATION)
        .doesNotContain("sub-pay90-")
        .doesNotContain("Kalala");
  }
}
