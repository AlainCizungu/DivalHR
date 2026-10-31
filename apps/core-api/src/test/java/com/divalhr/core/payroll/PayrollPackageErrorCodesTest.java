package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** MVP-094C: the twelve stable payroll package codes exist, are published and are safe text. */
class PayrollPackageErrorCodesTest {

  private static final Map<String, HttpStatus> CODES =
      Map.ofEntries(
          Map.entry("PAYROLL_PACKAGE_NOT_FOUND", HttpStatus.NOT_FOUND),
          Map.entry("PAYROLL_PACKAGE_BLOCKED", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_APPROVAL_PREVIEW_STALE", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_APPROVAL_NO_CHANGE", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_PACKAGE_VERSION_CONFLICT", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_PACKAGE_ALREADY_APPROVED", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_PACKAGE_VOIDED", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_VOID_PREVIEW_STALE", HttpStatus.CONFLICT),
          Map.entry("PAYROLL_VOID_NOT_ALLOWED", HttpStatus.CONFLICT),
          Map.entry("PAYSLIP_NOT_FOUND", HttpStatus.NOT_FOUND),
          Map.entry("PAYSLIP_RENDER_FAILED", HttpStatus.INTERNAL_SERVER_ERROR),
          Map.entry("PAYROLL_PACKAGE_LIMIT_REACHED", HttpStatus.CONFLICT));

  private static final Path SCHEMA =
      Path.of("../../packages/shared-contracts/schemas/problem.schema.json");
  private static final Path SPEC = Path.of("../../docs/API-SPEC.yaml");
  private static final Path EN = Path.of("../../packages/localization/locales/en/common.json");
  private static final Path FR = Path.of("../../packages/localization/locales/fr/common.json");

  private static String read(Path path) throws IOException {
    assumeTrue(Files.exists(path), "repository document not available: " + path);
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  private static boolean isPackageCode(String name) {
    return name.startsWith("PAYROLL_PACKAGE_")
        || name.startsWith("PAYROLL_APPROVAL_")
        || name.startsWith("PAYROLL_VOID_")
        || name.startsWith("PAYSLIP_");
  }

  @Test
  void everyCodeExistsWithItsStatus() {
    assertThat(CODES).hasSize(12);
    for (Map.Entry<String, HttpStatus> code : CODES.entrySet()) {
      ErrorCode found = ErrorCode.valueOf(code.getKey());
      assertThat(found.status()).as(code.getKey()).isEqualTo(code.getValue());
    }
  }

  @Test
  void noOtherPackageCodeExists() {
    List<String> found = new ArrayList<>();
    for (ErrorCode code : ErrorCode.values()) {
      if (isPackageCode(code.name())) {
        found.add(code.name());
      }
    }
    assertThat(found).containsExactlyInAnyOrderElementsOf(CODES.keySet());
  }

  @Test
  void everyCodeIsPublishedInTheSchemaAndTheSpecification() throws IOException {
    String schemaText = read(SCHEMA);
    String specText = read(SPEC);
    for (String code : CODES.keySet()) {
      assertThat(schemaText).as("problem schema " + code).contains("\"" + code + "\"");
      assertThat(specText).as("API specification " + code).contains(code);
    }
  }

  @Test
  void theProblemSchemaEnumEqualsTheErrorCodes() throws IOException {
    String schema = read(SCHEMA);
    int first = schema.indexOf("\"VALIDATION_FAILED\"");
    int open = schema.lastIndexOf('[', first);
    int close = schema.indexOf(']', first);
    List<String> published = new ArrayList<>();
    String enumeration = schema.substring(open, close);
    Matcher matcher = Pattern.compile("\"([A-Z][A-Z0-9_]+)\"").matcher(enumeration);
    while (matcher.find()) {
      published.add(matcher.group(1));
    }
    List<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
    assertThat(published).containsExactlyInAnyOrderElementsOf(codes);
  }

  @Test
  void theSpecificationErrorCodeEnumEqualsTheErrorCodesInOrder() throws IOException {
    String spec = read(SPEC);
    int start = spec.indexOf("\n    ErrorCode:\n");
    assertThat(start).isGreaterThan(0);
    List<String> published = new ArrayList<>();
    int end = spec.indexOf("\n    WhatsAppNotificationType:", start);
    String block = spec.substring(start + 1, end < 0 ? spec.length() : end);
    Matcher matcher = Pattern.compile("\n        - ([A-Z][A-Z0-9_]+)").matcher(block);
    while (matcher.find()) {
      published.add(matcher.group(1));
    }
    List<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
    assertThat(published).containsExactlyElementsOf(codes);
  }

  private static String message(String file, String code) {
    int errors = file.indexOf("\n  \"errors\": {");
    assertThat(errors).as("errors section").isGreaterThan(0);
    Matcher matcher =
        Pattern.compile("\n    \"" + code + "\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(file);
    boolean found = matcher.find(errors);
    assertThat(found).as("message of " + code).isTrue();
    return matcher.group(1);
  }

  @Test
  void everyCodeHasACompleteEnglishAndFrenchMessage() throws IOException {
    String en = read(EN);
    String fr = read(FR);
    for (String code : CODES.keySet()) {
      assertThat(message(en, code)).as("EN " + code).isNotBlank();
      assertThat(message(fr, code)).as("FR " + code).isNotBlank();
      assertThat(message(fr, code)).as("FR differs " + code).isNotEqualTo(message(en, code));
    }
  }

  @Test
  void noMessageCarriesAnAmountAnIdentifierADigestOrAHiddenSourceDetail() throws IOException {
    Pattern identifier =
        Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}|[0-9a-fA-F]{32,}|@|\\{|\\}|https?:|/api/"
                + "|[A-Z]{3,}_[A-Z_]+");
    for (String file : List.of(read(EN), read(FR))) {
      for (String code : CODES.keySet()) {
        String message = message(file, code);
        assertThat(identifier.matcher(message).find()).as(code + ": " + message).isFalse();
        if (code.equals("PAYROLL_PACKAGE_LIMIT_REACHED")) {
          // The limits are product constants, not data: 5,000 payslips and 25 versions.
          assertThat(message).containsPattern("5[ ,  ]?000").containsPattern("25");
          continue;
        }
        assertThat(message).as(code + ": " + message).doesNotContainPattern("[0-9]");
      }
    }
  }

  @Test
  void noMessageUsesAForbiddenProductPhrase() throws IOException {
    Pattern english =
        Pattern.compile(
            "(?<!\\p{L})paid(?!\\p{L})|payment\\s+completed|(?<!\\p{L})filed(?!\\p{L})"
                + "|tax[\\s-]+compliant|government[\\s-]+approved",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    String french =
        "(?<!\\p{L})payée?s?(?!\\p{L})|paiement\\s+effectué|(?<!\\p{L})déclarée?s?(?!\\p{L})"
            + "|conforme\\s+fiscalement|approuvée?s?\\s+par\\s+(le\\s+gouvernement|l.État)";
    int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    Pattern frenchPattern = Pattern.compile(french, flags);
    String en = read(EN);
    String fr = read(FR);
    for (String code : CODES.keySet()) {
      assertThat(english.matcher(message(en, code)).find()).as("EN " + code).isFalse();
      assertThat(frenchPattern.matcher(message(fr, code)).find()).as("FR " + code).isFalse();
    }
  }

  @Test
  void idempotencyMapsEveryCodeToAnOutcomeWithoutLeakingTheBusinessMeaning() throws Exception {
    Method outcomeOf = IdempotentOperation.class.getDeclaredMethod("outcomeOf", ErrorCode.class);
    outcomeOf.setAccessible(true);
    for (Map.Entry<String, HttpStatus> code : CODES.entrySet()) {
      Object outcome = outcomeOf.invoke(null, ErrorCode.valueOf(code.getKey()));
      Outcome expected =
          switch (code.getValue()) {
            case NOT_FOUND -> Outcome.NOT_FOUND;
            case INTERNAL_SERVER_ERROR -> Outcome.FAILURE;
            default -> Outcome.STATE_CONFLICT;
          };
      assertThat(outcome).as(code.getKey()).isEqualTo(expected);
    }
  }
}
