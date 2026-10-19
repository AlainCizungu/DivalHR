package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PayrollInputs;
import com.divalhr.core.payroll.domain.PayrollInputs.Blocker;
import com.divalhr.core.payroll.domain.PayrollInputs.Evaluation;
import com.divalhr.core.payroll.domain.PayrollInputs.Run;
import com.divalhr.core.payroll.domain.PayrollInputs.Scope;
import com.divalhr.core.payroll.domain.PayrollInputs.Totals;
import com.divalhr.core.payroll.domain.PayrollLifecycle;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Export;
import com.divalhr.core.payroll.domain.PayrollLifecycle.Line;
import com.divalhr.core.payroll.domain.PayrollLifecycle.State;
import com.divalhr.core.platform.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-092 (Issue #149): the pure lifecycle rules, the CSV, the manifest and the notices. */
class PayrollLifecycleTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Scope SCOPE =
      new Scope(UUID.randomUUID(), MONDAY, MONDAY.plusDays(6), "Africa/Kinshasa");
  private static final Instant AT = Instant.parse("2026-11-09T09:00:00Z");

  private static Run run(int rows, String digest) {
    return new Run(
        UUID.randomUUID(), SCOPE, 1, 1, new Totals(1, 1, rows, rows * 15), Map.of(), digest, AT);
  }

  private static Evaluation evaluation(Map<Blocker, Integer> blockers, String digest) {
    return new Evaluation(
        SCOPE,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Totals(1, 1, 2, 30),
        Map.of(),
        blockers,
        digest);
  }

  private static State state(boolean latest, boolean locked) {
    UUID id = UUID.randomUUID();
    return new State(
        id,
        1,
        null,
        null,
        null,
        locked ? new PayrollLifecycle.Lock(UUID.randomUUID(), id, AT) : null,
        latest,
        null);
  }

  private static Line line(int source, UUID employee, UUID site, int dayOffset) {
    return new Line(
        source,
        employee,
        UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
        SCOPE.legalEntityId(),
        site,
        null,
        MONDAY.plusDays(dayOffset),
        15,
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        1,
        MONDAY,
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        1,
        UUID.randomUUID(),
        2);
  }

  @Test
  void theNoticesAreTheFixedBilingualMonthlySalaryNotices() {
    assertThat(PayrollLifecycle.NOTICE_EN)
        .isEqualTo(
            "Worked-time inputs only. Monthly salary is not calculated from these minutes. This"
                + " export does not calculate or authorize pay, tax, deductions or payment.");
    assertThat(PayrollLifecycle.NOTICE_FR)
        .isEqualTo(
            "Données de temps travaillé uniquement. Le salaire mensuel n’est pas calculé à partir"
                + " de ces minutes. Cet export ne calcule ni n’autorise le salaire, les impôts, les"
                + " retenues ou le paiement.");
  }

  @Test
  void supersessionIsOfferedOnlyForTheLatestOpenRunWhenTheEvidenceChanged() {
    Run run = run(2, "a".repeat(64));
    assertThat(
            PayrollLifecycle.supersedeBlockers(
                state(true, false), run, evaluation(Map.of(), "b".repeat(64))))
        .isEmpty();
    assertThat(
            PayrollLifecycle.supersedeBlockers(
                state(true, false), run, evaluation(Map.of(), "a".repeat(64))))
        .containsExactly("NO_CHANGED_EVIDENCE");
    assertThat(
            PayrollLifecycle.supersedeBlockers(
                state(false, true), run, evaluation(Map.of(), "b".repeat(64))))
        .containsExactly("ALREADY_LOCKED", "NOT_LATEST_VERSION");
    assertThat(
            PayrollLifecycle.supersedeBlockers(
                state(true, false),
                run,
                evaluation(Map.of(Blocker.WEEK_NOT_LOCKED, 1), "b".repeat(64))))
        .containsExactly("WEEK_NOT_LOCKED");
  }

  @Test
  void aLockNeedsTheLatestOpenRunWithUnchangedLockedUpstreamEvidenceAndRows() {
    Run run = run(2, "a".repeat(64));
    assertThat(
            PayrollLifecycle.lockBlockers(
                state(true, false), run, evaluation(Map.of(), "a".repeat(64))))
        .isEmpty();
    assertThat(
            PayrollLifecycle.lockBlockers(
                state(true, false), run, evaluation(Map.of(), "b".repeat(64))))
        .containsExactly("UPSTREAM_EVIDENCE_CHANGED");
    assertThat(
            PayrollLifecycle.lockBlockers(
                state(false, true), run, evaluation(Map.of(), "a".repeat(64))))
        .containsExactly("ALREADY_LOCKED", "NOT_LATEST_VERSION");
    assertThat(
            PayrollLifecycle.lockBlockers(
                state(true, false),
                run,
                evaluation(
                    Map.of(Blocker.WEEK_NOT_LOCKED, 1, Blocker.WEEK_NOT_GENERATED, 1),
                    "a".repeat(64))))
        .containsExactly("SOURCE_PERIOD_NOT_LOCKED");
    assertThat(
            PayrollLifecycle.lockBlockers(
                state(true, false),
                run,
                evaluation(Map.of(Blocker.EVIDENCE_CHANGED, 1), "a".repeat(64))))
        .containsExactly("UPSTREAM_EVIDENCE_CHANGED");
    assertThat(
            PayrollLifecycle.lockBlockers(
                state(true, false), run(0, "a".repeat(64)), evaluation(Map.of(), "a".repeat(64))))
        .contains("NO_EXPORTABLE_ROWS");
  }

  @Test
  void theExplanationIsBoundedPlainTextWithoutLinksMarkupOrPersonalData() {
    assertThat(PayrollLifecycle.plainExplanation("Scope corrected after the site review")).isTrue();
    assertThat(PayrollLifecycle.plainExplanation("Périmètre corrigé après la revue")).isTrue();
    assertThat(PayrollLifecycle.plainExplanation("x".repeat(200))).isTrue();
    for (String bad :
        new String[] {
          null,
          "",
          " ",
          " padded",
          "x".repeat(201),
          "<b>x</b>",
          "a & b",
          "say \"hi\"",
          "back\\slash",
          "mail a@b.cd",
          "see https://example.com",
          "see www.example.com",
          "call 243 811 222 333",
          "id 123456",
          "line\nbreak",
          "tab\there"
        }) {
      assertThat(PayrollLifecycle.plainExplanation(bad)).as(String.valueOf(bad)).isFalse();
    }
  }

  @Test
  void theCsvIsDeterministicQuotedCrlfUtf8WithTheExactColumnsAndNoPersonalData() {
    UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    UUID siteOne = UUID.fromString("00000000-0000-0000-0000-000000000051");
    UUID siteTwo = UUID.fromString("00000000-0000-0000-0000-000000000052");
    List<Line> shuffled =
        List.of(
            line(4, b, siteOne, 0),
            line(3, a, siteTwo, 1),
            line(2, a, siteOne, 1),
            line(1, a, siteOne, 0));
    List<Line> ordered = PayrollLifecycle.ordered(shuffled);
    assertThat(ordered.stream().map(Line::sourceLine)).containsExactly(1, 2, 3, 4);
    assertThat(PayrollLifecycle.ordered(ordered)).isEqualTo(ordered);
    byte[] bytes = PayrollLifecycle.csv(ordered);
    assertThat(PayrollLifecycle.csv(PayrollLifecycle.ordered(shuffled))).isEqualTo(bytes);
    String text = new String(bytes, StandardCharsets.UTF_8);
    String[] lines = text.split("\r\n");
    assertThat(text).endsWith("\r\n");
    assertThat(lines).hasSize(5);
    assertThat(lines[0].replace("\"", "")).isEqualTo(String.join(",", PayrollLifecycle.COLUMNS));
    assertThat(PayrollLifecycle.COLUMNS)
        .containsExactly(
            "employee_id",
            "employment_id",
            "legal_entity_id",
            "site_id",
            "cost_center_id",
            "work_date",
            "approved_worked_minutes",
            "timesheet_id",
            "timesheet_revision",
            "approval_action_id",
            "approval_cycle",
            "source_week_start",
            "source_generation_id",
            "source_generation_version",
            "source_lock_action_id",
            "source_lock_version",
            "payroll_input_run_id",
            "payroll_input_run_version");
    for (int i = 1; i < lines.length; i++) {
      assertThat(lines[i].split(",", -1)).hasSize(18);
      assertThat(lines[i]).startsWith("\"00000000-0000-0000-0000-0000000000");
    }
    assertThat(PayrollLifecycle.sha256(bytes)).matches("[0-9a-f]{64}");
    assertThat(PayrollLifecycle.sha256("x".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881");
  }

  @Test
  void theManifestCarriesTheNoticesTheIdentityAndTheCsvHashButNeverAPerson() {
    TenantId tenant = new TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"));
    Export export =
        new Export(
            UUID.fromString("00000000-0000-0000-0000-0000000000e0"),
            UUID.fromString("00000000-0000-0000-0000-0000000000d0"),
            2,
            SCOPE,
            UUID.fromString("00000000-0000-0000-0000-0000000000c0"),
            1,
            3,
            8,
            2500,
            "f".repeat(64),
            AT);
    String manifest = new String(PayrollLifecycle.manifest(tenant, export), StandardCharsets.UTF_8);
    assertThat(manifest)
        .startsWith("{\"schemaVersion\":1,\"exportId\":\"00000000-0000-0000-0000-0000000000e0\"")
        .contains(PayrollLifecycle.NOTICE_EN)
        .contains(PayrollLifecycle.NOTICE_FR)
        .contains("worked-time inputs for payroll preparation")
        .contains("\"payrollInputRunVersion\":2")
        .contains("\"lockVersion\":1")
        .contains("\"counts\":{\"employees\":3,\"rows\":8}")
        .contains("\"workedMinutes\":2500")
        .contains("\"workedMinuteBand\":\"" + PayrollInputs.minuteBand(2500) + "\"")
        .contains("\"csvSha256\":\"" + "f".repeat(64) + "\"")
        .contains("\"createdAt\":\"2026-11-09T09:00:00Z\"")
        .endsWith("}\n")
        .doesNotContain("amount");
    assertThat(PayrollLifecycle.manifest(tenant, export))
        .isEqualTo(PayrollLifecycle.manifest(tenant, export));
    assertThat(PayrollLifecycle.filename(export, "manifest.json"))
        .isEqualTo(
            "divalhr-worked-time-inputs-"
                + SCOPE.legalEntityId()
                + "-2026-11-02-2026-11-08-v2.manifest.json");
  }
}
