package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.api.CommitPayrollPolicyRequest;
import com.divalhr.core.payroll.api.CommitPayrollRunRequest;
import com.divalhr.core.payroll.api.PayrollPolicyCommandRequest;
import com.divalhr.core.payroll.api.PayrollRunCommandRequest;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.PayrollPayDay;
import com.divalhr.core.payroll.domain.PayrollProration;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The validated payroll requests (MVP-094A): shapes, the pay day, the period, the confirmation and
 * the identifiers, with no value echoed.
 */
class PayrollCommandsTest {

  private static final String KEY = "0123456789abcdef0123";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DIGEST = "a".repeat(64);
  private static final String EXPIRES = "2026-11-02T09:30:00Z";
  private static final TenantId TENANT = TenantId.parse("11111111-1111-4111-8111-111111111111");
  private static final UUID ENTITY = UUID.fromString("22222222-2222-4222-8222-222222222222");

  private static final String POLICY =
      "{\"effectiveFrom\":\"2026-01-01\",\"payDay\":{\"kind\":\"DAY\",\"day\":25},"
          + "\"prorationMethod\":\"CALENDAR_DAYS\"}";
  private static final String RUN = "{\"period\":\"2026-05\",\"currency\":\"USD\"}";

  private static PayrollPolicyCommandRequest policy(String json) {
    return JSON.readValue(json, PayrollPolicyCommandRequest.class);
  }

  private static CommitPayrollPolicyRequest commitPolicy(String json) {
    return JSON.readValue(json, CommitPayrollPolicyRequest.class);
  }

  private static PayrollRunCommandRequest run(String json) {
    return JSON.readValue(json, PayrollRunCommandRequest.class);
  }

  private static CommitPayrollRunRequest commitRun(String json) {
    return JSON.readValue(json, CommitPayrollRunRequest.class);
  }

  private static String committingPolicy() {
    return POLICY.substring(0, POLICY.length() - 1)
        + ",\"expectedVersion\":2,\"previewDigest\":\""
        + DIGEST
        + "\",\"expiresAt\":\""
        + EXPIRES
        + "\",\"confirmed\":true}";
  }

  private static String committingRun() {
    return "{\"period\":\"2026-05\",\"currency\":\"USD\",\"expectedVersion\":1,"
        + "\"inputDigest\":\""
        + DIGEST
        + "\",\"previewDigest\":\""
        + DIGEST
        + "\",\"expiresAt\":\""
        + EXPIRES
        + "\",\"confirmed\":true}";
  }

  private static List<String> problems(Runnable validation) {
    try {
      validation.run();
      return List.of();
    } catch (ApiException invalid) {
      assertThat(invalid.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
      @SuppressWarnings("unchecked")
      List<Map<String, String>> fields = (List<Map<String, String>>) invalid.params().get("fields");
      return fields.stream().map(f -> f.get("field") + ":" + f.get("constraint")).toList();
    }
  }

  private static String with(String json, String field, String replacement) {
    return json.replaceAll(
        "\"" + field + "\":(\"[^\"]*\"|\\{[^}]*\\}|[^,}]*)",
        java.util.regex.Matcher.quoteReplacement("\"" + field + "\":" + replacement));
  }

  // ------------------------------------------------------------------------------------------
  // Policy
  // ------------------------------------------------------------------------------------------

  @Test
  void aWellFormedPolicyIsParsedExactly() {
    PayrollCommands.PolicyDraft draft = PayrollCommands.policyDraft(policy(POLICY));
    assertThat(draft.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(draft.payDay()).isEqualTo(PayrollPayDay.ofDay(25));
    assertThat(draft.method()).isEqualTo(PayrollProration.CALENDAR_DAYS);
    PayrollCommands.PolicyDraft last =
        PayrollCommands.policyDraft(
            policy(with(POLICY, "payDay", "{\"kind\":\"LAST_DAY\",\"day\":null}")));
    assertThat(last.payDay()).isEqualTo(PayrollPayDay.lastDay());
  }

  @Test
  void payDayShapesAreExact() {
    for (String bad :
        List.of(
            "{\"kind\":\"DAY\",\"day\":0}",
            "{\"kind\":\"DAY\",\"day\":29}",
            "{\"kind\":\"DAY\",\"day\":\"5\"}",
            "{\"kind\":\"DAY\",\"day\":null}",
            "{\"kind\":\"LAST_DAY\",\"day\":5}",
            "{\"kind\":\"LAST_DAY\"}",
            "{\"kind\":\"WEEKLY\",\"day\":1}",
            "{\"kind\":\"DAY\",\"day\":5,\"extra\":1}",
            "\"DAY\"",
            "5")) {
      assertThat(
              problems(() -> PayrollCommands.policyDraft(policy(with(POLICY, "payDay", bad)))))
          .as(bad)
          .hasSize(1)
          .allMatch(problem -> problem.startsWith("payDay:"));
    }
    for (int day : List.of(1, 15, 28)) {
      String ok = "{\"kind\":\"DAY\",\"day\":" + day + "}";
      assertThat(PayrollCommands.policyDraft(policy(with(POLICY, "payDay", ok))).payDay())
          .isEqualTo(PayrollPayDay.ofDay(day));
    }
  }

  @Test
  void policyFieldErrorsAreOnePerProblemAndUnknownPropertiesAreNamed() {
    assertThat(problems(() -> PayrollCommands.policyDraft(policy("{}"))))
        .containsExactlyInAnyOrder(
            "effectiveFrom:REQUIRED", "payDay:REQUIRED", "prorationMethod:REQUIRED");
    assertThat(
            problems(
                () ->
                    PayrollCommands.policyDraft(
                        policy(with(POLICY, "prorationMethod", "\"WEEKLY\"")))))
        .containsExactly("prorationMethod:FORMAT");
    assertThat(
            problems(
                () ->
                    PayrollCommands.policyDraft(
                        policy(with(POLICY, "effectiveFrom", "\"2026-02-30\"")))))
        .containsExactly("effectiveFrom:FORMAT");
    assertThat(
            problems(
                () ->
                    PayrollCommands.policyDraft(
                        policy(POLICY.substring(0, POLICY.length() - 1) + ",\"tenantId\":\"x\"}"))))
        .containsExactly("body:UNKNOWN_PROPERTY");
  }

  @Test
  void aPolicyCommitNeedsTheKeyTheVersionTheDigestTheExpiryAndTheConfirmation() {
    PayrollCommands.PolicyCommit commit =
        PayrollCommands.policyCommit(KEY, commitPolicy(committingPolicy()));
    assertThat(commit.expectedVersion()).isEqualTo(2);
    assertThat(commit.previewDigest()).isEqualTo(DIGEST);
    assertThat(commit.expiresAt()).isEqualTo(Instant.parse(EXPIRES));
    assertThat(commit.canonical(TENANT, ENTITY).get("confirmed")).isEqualTo(true);
    assertThat(commit.canonical(TENANT, ENTITY).get("tenantId")).isEqualTo(TENANT.toString());

    assertThat(problems(() -> PayrollCommands.policyCommit(null, commitPolicy(committingPolicy()))))
        .containsExactly("Idempotency-Key:REQUIRED");
    assertThat(
            problems(
                () ->
                    PayrollCommands.policyCommit(
                        KEY, commitPolicy(with(committingPolicy(), "confirmed", "false")))))
        .containsExactly("confirmed:REQUIRED");
    assertThat(
            problems(
                () ->
                    PayrollCommands.policyCommit(
                        KEY, commitPolicy(with(committingPolicy(), "expectedVersion", "61")))))
        .containsExactly("expectedVersion:RANGE");
    assertThat(
            problems(
                () ->
                    PayrollCommands.policyCommit(
                        KEY,
                        commitPolicy(
                            with(committingPolicy(), "previewDigest", "\"" + "A".repeat(64) + "\"")))))
        .containsExactly("previewDigest:FORMAT");
  }

  // ------------------------------------------------------------------------------------------
  // Run
  // ------------------------------------------------------------------------------------------

  @Test
  void aWellFormedRunCommandIsParsed() {
    PayrollCommands.RunDraft draft = PayrollCommands.runDraft(run(RUN));
    assertThat(draft.period().text()).isEqualTo("2026-05");
    assertThat(draft.currency()).isEqualTo(CompensationCurrency.USD);
  }

  @Test
  void periodAndCurrencyShapesAreExact() {
    for (String bad :
        List.of("\"2026-13\"", "\"2026-5\"", "\"2026-05-01\"", "\"\"", "202605", "\"1999-12\"")) {
      assertThat(problems(() -> PayrollCommands.runDraft(run(with(RUN, "period", bad)))))
          .as(bad)
          .containsExactly("period:FORMAT");
    }
    assertThat(problems(() -> PayrollCommands.runDraft(run(with(RUN, "currency", "\"EUR\"")))))
        .containsExactly("currency:FORMAT");
    assertThat(problems(() -> PayrollCommands.runDraft(run(with(RUN, "currency", "\"usd\"")))))
        .containsExactly("currency:FORMAT");
    assertThat(problems(() -> PayrollCommands.runDraft(run("{}"))))
        .containsExactlyInAnyOrder("period:REQUIRED", "currency:REQUIRED");
    assertThat(
            problems(
                () ->
                    PayrollCommands.runDraft(
                        run("{\"period\":\"2026-05\",\"currency\":\"USD\",\"tenantId\":\"x\"}"))))
        .containsExactly("body:UNKNOWN_PROPERTY");
  }

  @Test
  void aRunCommitRepeatsThePreviewAndIsCanonical() {
    PayrollCommands.RunCommit commit =
        PayrollCommands.runCommit(KEY, commitRun(committingRun()));
    assertThat(commit.expectedVersion()).isEqualTo(1);
    assertThat(commit.inputDigest()).isEqualTo(DIGEST);
    Map<String, Object> canonical = commit.canonical(TENANT, ENTITY);
    assertThat(canonical)
        .containsEntry("tenantId", TENANT.toString())
        .containsEntry("legalEntityId", ENTITY.toString())
        .containsEntry("period", "2026-05")
        .containsEntry("currency", "USD")
        .containsEntry("confirmed", true);
    assertThat(problems(() -> PayrollCommands.runCommit(KEY, commitRun(with(committingRun(), "expectedVersion", "26")))))
        .containsExactly("expectedVersion:RANGE");
    assertThat(problems(() -> PayrollCommands.runCommit(KEY, commitRun(with(committingRun(), "expectedVersion", "\"1\"")))))
        .containsExactly("expectedVersion:FORMAT");
    assertThat(problems(() -> PayrollCommands.runCommit("short", commitRun(committingRun()))))
        .containsExactly("Idempotency-Key:FORMAT");
    assertThat(problems(() -> PayrollCommands.runCommit(KEY, commitRun(with(committingRun(), "confirmed", "\"true\"")))))
        .containsExactly("confirmed:FORMAT");
    assertThat(problems(() -> PayrollCommands.runCommit(KEY, commitRun(with(committingRun(), "inputDigest", "null")))))
        .containsExactly("inputDigest:REQUIRED");
  }

  // ------------------------------------------------------------------------------------------
  // Identifiers and pages
  // ------------------------------------------------------------------------------------------

  @Test
  void aMalformedIdentifierIsTheSameNotFoundAsAnUnknownOne() {
    for (String bad : List.of("not-a-uuid", "", " ", "1234", UUID.randomUUID() + "x")) {
      assertThatThrownBy(() -> PayrollCommands.id(bad, ErrorCode.PAYROLL_RUN_NOT_FOUND))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYROLL_RUN_NOT_FOUND));
    }
    assertThatThrownBy(() -> PayrollCommands.id(null, ErrorCode.LEGAL_ENTITY_NOT_FOUND))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.LEGAL_ENTITY_NOT_FOUND));
    assertThat(PayrollCommands.id(ENTITY.toString().toUpperCase(java.util.Locale.ROOT), ErrorCode.NOT_FOUND))
        .isEqualTo(ENTITY);
  }

  @Test
  void pageSizesAreBoundedAndDefaulted() {
    FieldErrors errors = new FieldErrors();
    assertThat(PayrollCommands.limit(null, 50, 200, errors)).isEqualTo(50);
    assertThat(PayrollCommands.limit("200", 50, 200, errors)).isEqualTo(200);
    assertThat(PayrollCommands.limit("1", 50, 200, errors)).isEqualTo(1);
    assertThat(errors.isEmpty()).isTrue();
    for (String bad : List.of("0", "201", "-1", "x", "1.5", "1000")) {
      FieldErrors each = new FieldErrors();
      PayrollCommands.limit(bad, 50, 200, each);
      assertThat(each.isEmpty()).as(bad).isFalse();
    }
  }
}
