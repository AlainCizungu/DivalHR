package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.operations.api.CreateExpenseRequest;
import com.divalhr.core.operations.api.ExpenseVersionRequest;
import com.divalhr.core.operations.api.ReturnExpenseRequest;
import com.divalhr.core.operations.api.UpdateExpenseDraftRequest;
import com.divalhr.core.operations.application.ExpenseCommands;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.tenancy.TenantId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The validated expense commands (MVP-081): shape, amounts, window, restricted text, no echo. */
class ExpenseCommandsTest {

  private static final String KEY = "0123456789abcdef0123";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID EXPENSE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));

  private static final String VALID =
      "{\"businessDate\":\"2026-10-12\",\"currency\":\"USD\",\"amount\":\"12.50\","
          + "\"category\":\"TRANSPORT\",\"merchant\":\"Taxi Kinshasa\","
          + "\"description\":\"Course vers le client a Gombe\"}";

  private static CreateExpenseRequest create(String json) {
    return JSON.readValue(json, CreateExpenseRequest.class);
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

  private static List<String> createProblems(String json) {
    return problems(() -> ExpenseCommands.Create.from(KEY, create(json)));
  }

  private static String with(String field, String replacement) {
    return VALID.replaceAll(
        "\"" + field + "\":\"[^\"]*\"",
        java.util.regex.Matcher.quoteReplacement("\"" + field + "\":" + replacement));
  }

  @Test
  void aValidDraftIsNormalizedAndFingerprintedWithMinorUnits() {
    ExpenseCommands.Create command = ExpenseCommands.Create.from(KEY, create(VALID));
    assertThat(command.draft().fields().amountMinor()).isEqualTo(1250L);
    assertThat(command.canonical(TENANT))
        .containsEntry("currency", "USD")
        .containsEntry("amountMinor", 1250L)
        .containsEntry("tenantId", TENANT.toString())
        .doesNotContainKey("amount");
    assertThat(command.toString()).doesNotContain("Taxi").doesNotContain("Gombe");
  }

  @Test
  void theAmountIsADecimalStringInTheCurrencysOwnScale() {
    assertThat(createProblems(with("amount", "12.5"))).contains("amount:FORMAT");
    assertThat(createProblems(with("amount", "12.5").replace("12.5", "12.5"))).isNotEmpty();
    // A JSON number is never accepted, whatever its value.
    assertThat(createProblems(with("amount", "12.50"))).contains("amount:FORMAT");
    assertThat(createProblems(VALID.replace("\"12.50\"", "12.5"))).contains("amount:FORMAT");
    assertThat(createProblems(VALID.replace("\"12.50\"", "1e2"))).contains("amount:FORMAT");
    // Whole francs only: a decimal CDF amount is refused (mutation proof: exponent).
    String cdf = VALID.replace("\"USD\"", "\"CDF\"").replace("\"12.50\"", "\"1250\"");
    assertThat(createProblems(cdf)).isEmpty();
    assertThat(createProblems(cdf.replace("\"1250\"", "\"1250.50\""))).contains("amount:FORMAT");
    assertThat(createProblems(VALID.replace("\"12.50\"", "\"12.505\""))).contains("amount:FORMAT");
    assertThat(createProblems(VALID.replace("\"12.50\"", "\"0.00\""))).contains("amount:FORMAT");
    assertThat(createProblems(VALID.replace("\"12.50\"", "\"100000.01\"")))
        .contains("amount:FORMAT");
    assertThat(createProblems(VALID.replace("\"12.50\"", "\"100000.00\""))).isEmpty();
    assertThat(createProblems(VALID.replace("\"USD\"", "\"EUR\""))).contains("currency:FORMAT");
    assertThat(createProblems(VALID.replace("\"USD\"", "\"usd\""))).contains("currency:FORMAT");
  }

  @Test
  void theSameAmountTextMeansDifferentMinorUnitsPerCurrency() {
    ExpenseCommands.Create usd = ExpenseCommands.Create.from(KEY, create(VALID));
    ExpenseCommands.Create cdf =
        ExpenseCommands.Create.from(
            KEY, create(VALID.replace("\"USD\"", "\"CDF\"").replace("12.50", "1250")));
    assertThat(usd.draft().fields().amountMinor()).isEqualTo(cdf.draft().fields().amountMinor());
    assertThat(usd.canonical(TENANT)).isNotEqualTo(cdf.canonical(TENANT));
  }

  @Test
  void theBusinessDateWindowIsNinetyDaysBackToToday() {
    LocalDate today = LocalDate.of(2026, 10, 19);
    ExpenseCommands.Create command = ExpenseCommands.Create.from(KEY, create(VALID));
    assertThat(command.draft().fields().businessDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    ExpenseCommands.requireWindow(today, today);
    ExpenseCommands.requireWindow(today.minusDays(90), today);
    assertThat(problems(() -> ExpenseCommands.requireWindow(today.minusDays(91), today)))
        .containsExactly("businessDate:RANGE");
    assertThat(problems(() -> ExpenseCommands.requireWindow(today.plusDays(1), today)))
        .containsExactly("businessDate:RANGE");
    // The month-end and leap-day arithmetic is the calendar's.
    ExpenseCommands.requireWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));
    assertThat(
            problems(
                () ->
                    ExpenseCommands.requireWindow(
                        LocalDate.of(2025, 12, 31), LocalDate.of(2026, 4, 1))))
        .containsExactly("businessDate:RANGE");
  }

  @Test
  void dateShapeAndCategoryAreClosed() {
    assertThat(createProblems(with("businessDate", "\"2026-13-01\"")))
        .contains("businessDate:FORMAT");
    assertThat(createProblems(with("businessDate", "\"12/10/2026\"")))
        .contains("businessDate:FORMAT");
    assertThat(createProblems(with("businessDate", "\"2026-02-30\"")))
        .contains("businessDate:FORMAT");
    assertThat(createProblems(with("category", "\"TRAVEL\""))).contains("category:FORMAT");
    assertThat(createProblems("{}"))
        .contains(
            "businessDate:REQUIRED",
            "currency:REQUIRED",
            "amount:REQUIRED",
            "category:REQUIRED",
            "merchant:REQUIRED",
            "description:REQUIRED");
  }

  @Test
  void merchantAndDescriptionUseTheRestrictedTextGrammar() {
    assertThat(createProblems(with("merchant", "\"<b>Taxi</b>\""))).contains("merchant:FORMAT");
    assertThat(createProblems(with("description", "\"Voir https://exemple.cd pour le recu\"")))
        .contains("description:FORMAT");
    assertThat(createProblems(with("description", "\"trop bref\""))).contains("description:LENGTH");
    assertThat(createProblems(with("merchant", "\"" + "x".repeat(121) + "\"")))
        .contains("merchant:LENGTH");
    assertThat(createProblems(with("description", "\"" + "y".repeat(1001) + "\"")))
        .contains("description:LENGTH");
    assertThat(createProblems(with("merchant", "\"Cafe\\u0000\""))).contains("merchant:FORMAT");
    assertThat(createProblems(with("description", "\"Deux lignes\\nici bien\"")))
        .contains("description:FORMAT");
    assertThat(createProblems(with("merchant", "\"%3Cscript%3E\""))).contains("merchant:FORMAT");
  }

  @Test
  void problemsNeverEchoTheValue() {
    ApiException invalid =
        catchApi(
            () ->
                ExpenseCommands.Create.from(
                    KEY, create(with("merchant", "\"<secret-merchant>\""))));
    assertThat(invalid.params().toString()).doesNotContain("secret-merchant");
    assertThat(invalid.getMessage() == null ? "" : invalid.getMessage())
        .doesNotContain("secret-merchant");
  }

  @Test
  void unknownPropertiesAndKeysAreRefused() {
    assertThat(createProblems(VALID.replace("}", ",\"tenantId\":\"x\",\"employeeId\":\"y\"}")))
        .contains("body:UNKNOWN_PROPERTY");
    assertThat(problems(() -> ExpenseCommands.Create.from(null, create(VALID))))
        .contains("Idempotency-Key:REQUIRED");
    assertThat(problems(() -> ExpenseCommands.Create.from("short", create(VALID))))
        .contains("Idempotency-Key:FORMAT");
    assertThat(problems(() -> ExpenseCommands.Create.from(KEY, null))).contains("body:REQUIRED");
  }

  @Test
  void updateNeedsAVersionAndAKnownExpense() {
    UpdateExpenseDraftRequest body =
        JSON.readValue(
            VALID.replace("}", ",\"expectedVersion\":3}"), UpdateExpenseDraftRequest.class);
    ExpenseCommands.Save save = ExpenseCommands.Save.from(KEY, EXPENSE.toString(), body);
    assertThat(save.expectedVersion()).isEqualTo(3);
    assertThat(save.canonical(TENANT)).containsEntry("expectedVersion", 3);
    assertThat(
            problems(
                () ->
                    ExpenseCommands.Save.from(
                        KEY,
                        EXPENSE.toString(),
                        JSON.readValue(VALID, UpdateExpenseDraftRequest.class))))
        .contains("expectedVersion:REQUIRED");
    assertThat(
            problems(
                () ->
                    ExpenseCommands.Save.from(
                        KEY,
                        EXPENSE.toString(),
                        JSON.readValue(
                            VALID.replace("}", ",\"expectedVersion\":0}"),
                            UpdateExpenseDraftRequest.class))))
        .contains("expectedVersion:RANGE");
    assertThatThrownBy(() -> ExpenseCommands.Save.from(KEY, "not-an-id", body))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPENSE_NOT_FOUND));
  }

  @Test
  void returnNeedsAReasonOfTenToFiveHundredCharacters() {
    ReturnExpenseRequest ok =
        JSON.readValue(
            "{\"expectedVersion\":2,\"reason\":\"Le recu est illisible, merci de le rescanner\"}",
            ReturnExpenseRequest.class);
    ExpenseCommands.Return command = ExpenseCommands.Return.from(KEY, EXPENSE.toString(), ok);
    assertThat(command.reason()).startsWith("Le recu");
    assertThat(command.toString()).doesNotContain("recu");
    for (String reason : new String[] {"court", "<b>reason reason</b>", "x".repeat(501)}) {
      ReturnExpenseRequest bad =
          JSON.readValue(
              "{\"expectedVersion\":2,\"reason\":\"" + reason + "\"}", ReturnExpenseRequest.class);
      assertThat(problems(() -> ExpenseCommands.Return.from(KEY, EXPENSE.toString(), bad)))
          .as(reason)
          .isNotEmpty();
    }
    ReturnExpenseRequest none =
        JSON.readValue("{\"expectedVersion\":2}", ReturnExpenseRequest.class);
    assertThat(problems(() -> ExpenseCommands.Return.from(KEY, EXPENSE.toString(), none)))
        .contains("reason:REQUIRED");
  }

  @Test
  void approvalHasNoNoteAndWithdrawNeedsOnlyTheVersion() {
    ExpenseVersionRequest ok =
        JSON.readValue("{\"expectedVersion\":2}", ExpenseVersionRequest.class);
    assertThat(ExpenseCommands.Decision.from(KEY, EXPENSE.toString(), ok).expectedVersion())
        .isEqualTo(2);
    ExpenseVersionRequest note =
        JSON.readValue("{\"expectedVersion\":2,\"note\":\"fine\"}", ExpenseVersionRequest.class);
    assertThat(problems(() -> ExpenseCommands.Decision.from(KEY, EXPENSE.toString(), note)))
        .contains("body:UNKNOWN_PROPERTY");
  }

  @Test
  void receiptHeadersAreValidatedBeforeAnyBodyIsRead() {
    ExpenseCommands.ReceiptMutation set =
        ExpenseCommands.ReceiptMutation.set(
            KEY, EXPENSE.toString(), "4", "image/png", "re%C3%A7u%20caf%C3%A9.png");
    assertThat(set.declaredName()).isEqualTo("reçu café.png");
    assertThat(set.expectedVersion()).isEqualTo(4);
    assertThat(
            problems(
                () ->
                    ExpenseCommands.ReceiptMutation.set(
                        KEY, EXPENSE.toString(), null, "image/png", null)))
        .contains("X-Expected-Version:REQUIRED");
    assertThat(
            problems(
                () ->
                    ExpenseCommands.ReceiptMutation.set(
                        KEY, EXPENSE.toString(), "x", "image/png", null)))
        .contains("X-Expected-Version:FORMAT");
    assertThatThrownBy(
            () ->
                ExpenseCommands.ReceiptMutation.set(
                    KEY, EXPENSE.toString(), "4", "text/html", null))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPENSE_RECEIPT_MEDIA_UNSUPPORTED));
    assertThat(set.canonical(TENANT, null)).doesNotContainKey("sha256");
  }

  private static ApiException catchApi(Runnable work) {
    try {
      work.run();
    } catch (ApiException e) {
      return e;
    }
    throw new AssertionError("expected an ApiException");
  }
}
