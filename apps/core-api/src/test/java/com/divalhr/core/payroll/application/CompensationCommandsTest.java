package com.divalhr.core.payroll.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.api.CancelCompensationRequest;
import com.divalhr.core.payroll.api.CommitCompensationRequest;
import com.divalhr.core.payroll.api.CompensationCommandRequest;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The validated compensation requests (MVP-093A): shapes, amounts, the correction fields, the
 * restricted-text grammar and the confirmation, with no value echoed and no amount or explanation
 * printed.
 */
class CompensationCommandsTest {

  private static final String KEY = "0123456789abcdef0123";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TARGET = "00000000-0000-0000-0000-0000000000c1";
  private static final String DIGEST = "a".repeat(64);
  private static final String EXPIRES = "2026-11-02T09:30:00Z";

  private static final String ESTABLISH =
      "{\"action\":\"ESTABLISHED\",\"effectiveFrom\":\"2026-11-01\",\"currency\":\"USD\","
          + "\"amount\":\"1250.5\"}";
  private static final String CORRECT =
      "{\"action\":\"CORRECTED\",\"effectiveFrom\":\"2026-11-01\",\"currency\":\"CDF\","
          + "\"amount\":\"135000\",\"targetChangeId\":\""
          + TARGET
          + "\",\"reasonCode\":\"WRONG_CURRENCY\","
          + "\"explanation\":\"Devise saisie par erreur\"}";
  private static final String COMMIT_TAIL =
      ",\"expectedVersion\":3,\"employmentDigest\":\""
          + DIGEST
          + "\",\"previewDigest\":\""
          + DIGEST
          + "\",\"expiresAt\":\""
          + EXPIRES
          + "\",\"acknowledgeRetroactive\":false,\"confirmed\":true}";

  private static CompensationCommandRequest command(String json) {
    return JSON.readValue(json, CompensationCommandRequest.class);
  }

  private static CommitCompensationRequest commit(String json) {
    return JSON.readValue(json, CommitCompensationRequest.class);
  }

  private static String committing(String command) {
    return command.substring(0, command.length() - 1) + COMMIT_TAIL;
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

  private static List<String> draftProblems(String json) {
    return problems(() -> CompensationCommands.draft(command(json)));
  }

  private static String with(String json, String field, String replacement) {
    return json.replaceAll(
        "\"" + field + "\":(\"[^\"]*\"|[^,}]*)",
        java.util.regex.Matcher.quoteReplacement("\"" + field + "\":" + replacement));
  }

  @Test
  void aWellFormedEstablishmentIsParsedExactly() {
    CompensationCommands.Draft draft = CompensationCommands.draft(command(ESTABLISH));
    assertThat(draft.action().name()).isEqualTo("ESTABLISHED");
    assertThat(draft.effectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(draft.amount().status()).isEqualTo(CompensationMoney.Status.OK);
    assertThat(draft.amount().money().amount()).isEqualTo("1250.50");
    assertThat(draft.target()).isNull();
    assertThat(draft.reason()).isNull();
  }

  @Test
  void aMalformedAmountIsTheStableAmountErrorAndAnOutOfRangeOneIsKept() {
    for (String bad : List.of("12,50", "-5", "1e3", " 10", "10.001", "", "0x10", "٣")) {
      assertThatThrownBy(
              () ->
                  CompensationCommands.draft(
                      command(with(ESTABLISH, "amount", "\"" + bad + "\""))))
          .as("amount %s", bad)
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.COMPENSATION_INVALID_AMOUNT));
    }
    // Well-formed but outside the bounds: a preview answers with a blocker.
    CompensationCommands.Draft zero =
        CompensationCommands.draft(command(with(ESTABLISH, "amount", "\"0.00\"")));
    assertThat(zero.amount().status()).isEqualTo(CompensationMoney.Status.OUT_OF_RANGE);
    assertThat(zero.amountText()).isEqualTo("0.00");
    // CDF has no fraction.
    assertThatThrownBy(
            () ->
                CompensationCommands.draft(
                    command(with(with(ESTABLISH, "currency", "\"CDF\""), "amount", "\"10.5\""))))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.COMPENSATION_INVALID_AMOUNT));
  }

  @Test
  void shapeErrorsAreFieldErrorsAndNeverEchoTheValue() {
    assertThat(draftProblems(with(ESTABLISH, "amount", "12.5"))).containsExactly("amount:FORMAT");
    assertThat(draftProblems(with(ESTABLISH, "amount", "null"))).containsExactly("amount:REQUIRED");
    assertThat(draftProblems(with(ESTABLISH, "currency", "\"EUR\"")))
        .containsExactly("currency:FORMAT");
    assertThat(draftProblems(with(ESTABLISH, "action", "\"CANCELLED\"")))
        .containsExactly("action:FORMAT");
    assertThat(draftProblems(with(ESTABLISH, "action", "\"SCHEDULED_CHANGE_CANCELLED\"")))
        .containsExactly("action:FORMAT");
    assertThat(draftProblems(with(ESTABLISH, "effectiveFrom", "\"2026-13-01\"")))
        .containsExactly("effectiveFrom:FORMAT");
    assertThat(draftProblems(with(ESTABLISH, "effectiveFrom", "\"1899-01-01\"")))
        .containsExactly("effectiveFrom:RANGE");
    assertThat(draftProblems(ESTABLISH.replace("}", ",\"tenantId\":\"x\"}")))
        .containsExactly("body:UNKNOWN_PROPERTY");
    assertThat(draftProblems("{}"))
        .containsExactly("action:REQUIRED", "effectiveFrom:REQUIRED", "currency:REQUIRED",
            "amount:REQUIRED");
    assertThatThrownBy(() -> CompensationCommands.draft(command(with(ESTABLISH, "amount", "9.9"))))
        .satisfies(e -> assertThat(e.toString()).doesNotContain("9.9"));
  }

  @Test
  void aCorrectionNeedsItsTargetReasonAndExplanationAndOthersMustNotCarryThem() {
    CompensationCommands.Draft draft = CompensationCommands.draft(command(CORRECT));
    assertThat(draft.target()).isEqualTo(UUID.fromString(TARGET));
    assertThat(draft.reason().name()).isEqualTo("WRONG_CURRENCY");
    assertThat(draft.explanation()).isEqualTo("Devise saisie par erreur");

    String bare = with(with(with(CORRECT, "targetChangeId", "null"), "reasonCode", "null"),
        "explanation", "null");
    assertThat(draftProblems(bare))
        .containsExactly("targetChangeId:REQUIRED", "reasonCode:REQUIRED", "explanation:REQUIRED");
    assertThat(draftProblems(with(CORRECT, "targetChangeId", "\"nope\"")))
        .containsExactly("targetChangeId:FORMAT");
    assertThat(draftProblems(with(CORRECT, "reasonCode", "\"WHATEVER\"")))
        .containsExactly("reasonCode:FORMAT");
    assertThat(draftProblems(with(CORRECT, "explanation", "\"court\"")))
        .containsExactly("explanation:LENGTH");
    assertThat(draftProblems(with(CORRECT, "explanation", "\"" + "x".repeat(501) + "\"")))
        .containsExactly("explanation:LENGTH");
    assertThat(draftProblems(with(CORRECT, "explanation", "\"" + "x".repeat(500) + "\"")))
        .isEmpty();
    assertThat(draftProblems(with(CORRECT, "explanation", "\"<b>explication</b>\"")))
        .containsExactly("explanation:FORMAT");

    String establishWithReason =
        ESTABLISH.replace("}", ",\"reasonCode\":\"DATA_ENTRY_ERROR\",\"targetChangeId\":\""
            + TARGET + "\",\"explanation\":\"explication suffisante\"}");
    assertThat(draftProblems(establishWithReason))
        .containsExactly("targetChangeId:FORMAT", "reasonCode:FORMAT", "explanation:FORMAT");
  }

  @Test
  void theRestrictedTextGrammarMatchesTheDatabaseFunction() {
    assertThat(CompensationCommands.restricted("Montant saisi par erreur")).isTrue();
    assertThat(CompensationCommands.restricted("Écart de saisie : é à ç")).isTrue();
    assertThat(CompensationCommands.restricted(" commence par une espace")).isFalse();
    assertThat(CompensationCommands.restricted("finit par une espace ")).isFalse();
    assertThat(CompensationCommands.restricted("ligne\nsuivante inutile")).isFalse();
    assertThat(CompensationCommands.restricted("zero​width space here")).isFalse();
    assertThat(CompensationCommands.restricted("soft­hyphen inside")).isFalse();
    assertThat(CompensationCommands.restricted("bidi ‮override here")).isFalse();
    assertThat(CompensationCommands.restricted("private  use character")).isFalse();
    assertThat(CompensationCommands.restricted("a < b > c backslash \\ x")).isFalse();
    assertThat(CompensationCommands.restricted("voir https://exemple.cd/x")).isFalse();
    assertThat(CompensationCommands.restricted("voir HTTP://exemple.cd")).isFalse();
    assertThat(CompensationCommands.restricted("voir www.exemple.cd ici")).isFalse();
    assertThat(CompensationCommands.restricted("encoded %2F slash here")).isFalse();
    assertThat(CompensationCommands.restricted("entity &amp; inside text")).isFalse();
    assertThat(CompensationCommands.restricted("numeric &#65; entity text")).isFalse();
    // Not NFC: "e" followed by a combining acute accent.
    assertThat(CompensationCommands.restricted("café non normalise")).isFalse();
  }

  @Test
  void aCommitCarriesTheCommandAndWhatThePreviewReturned() {
    CompensationCommands.Commit commit =
        CompensationCommands.commit(KEY, commit(committing(CORRECT)));
    assertThat(commit.expectedVersion()).isEqualTo(3);
    assertThat(commit.employmentDigest()).isEqualTo(DIGEST);
    assertThat(commit.previewDigest()).isEqualTo(DIGEST);
    assertThat(commit.expiresAt().toString()).isEqualTo(EXPIRES);
    assertThat(commit.acknowledgeRetroactive()).isFalse();
    assertThat(commit.draft().reason().name()).isEqualTo("WRONG_CURRENCY");

    String json = committing(ESTABLISH);
    assertThat(problems(() -> CompensationCommands.commit(null, commit(json))))
        .containsExactly("Idempotency-Key:REQUIRED");
    assertThat(problems(() -> CompensationCommands.commit("short", commit(json))))
        .containsExactly("Idempotency-Key:FORMAT");
    assertThat(commitProblems(json, "confirmed", "false"))
        .containsExactly("confirmed:REQUIRED");
    assertThat(commitProblems(json, "confirmed", "\"yes\""))
        .containsExactly("confirmed:FORMAT");
    assertThat(commitProblems(json, "expectedVersion", "-1"))
        .containsExactly("expectedVersion:RANGE");
    assertThat(commitProblems(json, "expectedVersion", "1.5"))
        .containsExactly("expectedVersion:FORMAT");
    assertThat(commitProblems(json, "expectedVersion", "\"3\""))
        .containsExactly("expectedVersion:FORMAT");
    assertThat(commitProblems(json, "previewDigest", "\"ABC\""))
        .containsExactly("previewDigest:FORMAT");
    assertThat(commitProblems(json, "expiresAt", "\"demain\""))
        .containsExactly("expiresAt:FORMAT");
    assertThat(commitProblems(json, "acknowledgeRetroactive", "null"))
        .containsExactly("acknowledgeRetroactive:REQUIRED");
  }

  @Test
  void aCancellationCarriesTheDigestsOfItsPreview() {
    String json =
        "{\"expectedVersion\":2,\"employmentDigest\":\"" + DIGEST
            + "\",\"cancellationDigest\":\"" + DIGEST + "\",\"expiresAt\":\"" + EXPIRES
            + "\",\"confirmed\":true}";
    UUID change = UUID.fromString(TARGET);
    CompensationCommands.Cancel cancel =
        CompensationCommands.cancel(
            KEY, change, JSON.readValue(json, CancelCompensationRequest.class));
    assertThat(cancel.changeId()).isEqualTo(change);
    assertThat(cancel.expectedVersion()).isEqualTo(2);
    assertThat(cancel.cancellationDigest()).isEqualTo(DIGEST);
    String unknown = json.replace("}", ",\"amount\":\"1\"}");
    assertThat(
            problems(
                () ->
                    CompensationCommands.cancel(
                        KEY, change, JSON.readValue(unknown, CancelCompensationRequest.class))))
        .containsExactly("body:UNKNOWN_PROPERTY");
  }

  @Test
  void aMalformedPathIdentifierIsTheSameNotFoundAsAnUnknownOne() {
    for (String raw : new String[] {null, "", "not-a-uuid", "0000", TARGET + "0", "../x"}) {
      assertThatThrownBy(() -> CompensationCommands.id(raw))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.code()).isEqualTo(ErrorCode.COMPENSATION_NOT_FOUND));
    }
    assertThat(CompensationCommands.id(TARGET.toUpperCase(java.util.Locale.ROOT)))
        .isEqualTo(UUID.fromString(TARGET));
  }

  @Test
  void theCanonicalFormBindsTheTenantTheEmployeeAndTheAmountButIsNeverPrinted() {
    CompensationCommands.Commit commit =
        CompensationCommands.commit(KEY, commit(committing(ESTABLISH)));
    UUID employee = UUID.fromString(TARGET);
    com.divalhr.core.platform.tenancy.TenantId tenant =
        new com.divalhr.core.platform.tenancy.TenantId(UUID.randomUUID());
    Map<String, Object> canonical = commit.canonical(tenant, employee);
    assertThat(canonical)
        .containsEntry("tenantId", tenant.toString())
        .containsEntry("employeeId", employee.toString())
        .containsEntry("amount", "1250.5")
        .containsEntry("confirmed", true);
    assertThat(commit.toString()).doesNotContain("1250").doesNotContain(DIGEST);
    assertThat(commit.draft().toString()).doesNotContain("1250").doesNotContain("Devise");
    assertThat(CompensationCommands.draft(command(CORRECT)).toString())
        .doesNotContain("135000")
        .doesNotContain("Devise saisie");
  }

  private static List<String> commitProblems(String json, String field, String value) {
    return problems(() -> CompensationCommands.commit(KEY, commit(with(json, field, value))));
  }
}
