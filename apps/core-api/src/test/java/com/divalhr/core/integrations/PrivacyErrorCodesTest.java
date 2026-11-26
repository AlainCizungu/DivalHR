package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * LAUNCH-001: the twenty-three closed {@code PRIVACY_*} codes exist with their statuses, are the
 * last in the registry, are published in the problem schema and the API specification, and render
 * safe text in English and French. No other {@code ErrorCode} starts with {@code PRIVACY_}.
 */
class PrivacyErrorCodesTest {

  private static final Map<String, HttpStatus> CODES = new LinkedHashMap<>();

  static {
    CODES.put("PRIVACY_REQUEST_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PRIVACY_REFERRAL_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PRIVACY_REQUEST_DUPLICATE_OPEN", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_REQUEST_TRANSITION_INVALID", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_REQUEST_REASON_REQUIRED", HttpStatus.UNPROCESSABLE_CONTENT);
    CODES.put("PRIVACY_REQUEST_VERSION_STALE", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_EXPORT_NOT_AVAILABLE", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_POLICY_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PRIVACY_POLICY_INVALID", HttpStatus.UNPROCESSABLE_CONTENT);
    CODES.put("PRIVACY_POLICY_CHECKLIST_INCOMPLETE", HttpStatus.UNPROCESSABLE_CONTENT);
    CODES.put("PRIVACY_POLICY_TRANSITION_INVALID", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_POLICY_IMMUTABLE", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_POLICY_OVERLAP", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_POLICY_VERSION_STALE", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_HOLD_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PRIVACY_HOLD_ALREADY_RELEASED", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_PREVIEW_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PRIVACY_PREVIEW_EXPIRED", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_PREVIEW_STALE", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_PREVIEW_ALREADY_USED", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_PURGE_NOTHING_ELIGIBLE", HttpStatus.UNPROCESSABLE_CONTENT);
    CODES.put("PRIVACY_IDEMPOTENCY_REUSED", HttpStatus.CONFLICT);
    CODES.put("PRIVACY_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS);
  }

  private static final Path SCHEMA =
      Path.of("../../packages/shared-contracts/schemas/problem.schema.json");
  private static final Path SPEC = Path.of("../../docs/API-SPEC.yaml");
  private static final Path EN = Path.of("../../packages/localization/locales/en/common.json");
  private static final Path FR = Path.of("../../packages/localization/locales/fr/common.json");

  private static String read(Path path) throws IOException {
    assumeTrue(Files.exists(path), "repository document not available: " + path);
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  private static String message(String file, String code) {
    int errors = file.indexOf("\n  \"errors\": {");
    assertThat(errors).as("errors section").isGreaterThan(0);
    Matcher matcher =
        Pattern.compile("\n    \"" + code + "\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(file);
    assertThat(matcher.find(errors)).as("message of " + code).isTrue();
    return matcher.group(1);
  }

  @Test
  void theCatalogueIsClosedAndEveryCodeIsTheLastInTheRegistryWithItsStatus() {
    assertThat(CODES).hasSize(23);
    List<String> found = new ArrayList<>();
    for (ErrorCode code : ErrorCode.values()) {
      if (code.name().startsWith("PRIVACY_")) {
        found.add(code.name());
      }
    }
    assertThat(found).containsExactlyElementsOf(CODES.keySet());
    ErrorCode[] all = ErrorCode.values();
    int first = ErrorCode.PRIVACY_REQUEST_NOT_FOUND.ordinal();
    assertThat(first).isEqualTo(ErrorCode.PARTNER_REFERRAL_RATE_LIMITED.ordinal() + 1);
    int index = first;
    for (Map.Entry<String, HttpStatus> entry : CODES.entrySet()) {
      assertThat(all[index].name()).isEqualTo(entry.getKey());
      assertThat(all[index].status()).as(entry.getKey()).isEqualTo(entry.getValue());
      index++;
    }
    assertThat(index).as("the privacy codes are the last in the registry").isEqualTo(all.length);
  }

  @Test
  void everyCodeIsPublishedAndHasSafeEnglishAndFrenchText() throws IOException {
    String schema = read(SCHEMA);
    String spec = read(SPEC);
    String en = read(EN);
    String fr = read(FR);
    Pattern unsafe =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}|@|\\{|\\}|https?:|/api/|[A-Z]{3,}_[A-Z_]+");
    for (String code : CODES.keySet()) {
      assertThat(schema).contains("\"" + code + "\"");
      assertThat(spec).contains("- " + code + "\n");
      assertThat(message(en, code)).isNotBlank();
      assertThat(message(fr, code)).isNotBlank().isNotEqualTo(message(en, code));
      assertThat(unsafe.matcher(message(en, code)).find()).as("EN " + code).isFalse();
      assertThat(unsafe.matcher(message(fr, code)).find()).as("FR " + code).isFalse();
    }
  }
}
