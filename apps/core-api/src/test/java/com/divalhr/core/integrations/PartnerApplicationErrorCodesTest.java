package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** MVP-120D: the nine partner-application codes exist, are published and are safe text. */
class PartnerApplicationErrorCodesTest {

  private static final Map<String, HttpStatus> CODES =
      Map.of(
          "PARTNER_APPLICATION_INVALID", HttpStatus.BAD_REQUEST,
          "PARTNER_APPLICATION_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
          "PARTNER_APPLICATION_IDEMPOTENCY_REUSED", HttpStatus.CONFLICT,
          "PARTNER_APPLICATION_NOT_FOUND", HttpStatus.NOT_FOUND,
          "PARTNER_APPLICATION_TRANSITION_INVALID", HttpStatus.CONFLICT,
          "PARTNER_APPLICATION_TERMINAL", HttpStatus.CONFLICT,
          "PARTNER_APPLICATION_CHECKLIST_INCOMPLETE", HttpStatus.CONFLICT,
          "PARTNER_APPLICATION_DUPLICATE", HttpStatus.CONFLICT,
          "PARTNER_APPLICATION_CONSENT_REQUIRED", HttpStatus.BAD_REQUEST);

  private static final Path SCHEMA =
      Path.of("../../packages/shared-contracts/schemas/problem.schema.json");
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
  void everyCodeExistsWithItsStatusAndNoOtherPartnerApplicationCodeDoes() {
    assertThat(CODES).hasSize(9);
    List<String> found = new ArrayList<>();
    for (ErrorCode code : ErrorCode.values()) {
      if (code.name().startsWith("PARTNER_APPLICATION_")) {
        found.add(code.name());
      }
    }
    assertThat(found).containsExactlyInAnyOrderElementsOf(CODES.keySet());
    CODES.forEach((name, status) -> assertThat(ErrorCode.valueOf(name).status()).as(name).isEqualTo(status));
  }

  @Test
  void everyCodeIsPublishedAndHasSafeEnglishAndFrenchText() throws IOException {
    String schema = read(SCHEMA);
    String en = read(EN);
    String fr = read(FR);
    Pattern unsafe = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}|@|\\{|\\}|https?:|/api/|[A-Z]{3,}_[A-Z_]+");
    for (String code : CODES.keySet()) {
      assertThat(schema).contains("\"" + code + "\"");
      assertThat(message(en, code)).isNotBlank();
      assertThat(message(fr, code)).isNotBlank().isNotEqualTo(message(en, code));
      assertThat(unsafe.matcher(message(en, code)).find()).as("EN " + code).isFalse();
      assertThat(unsafe.matcher(message(fr, code)).find()).as("FR " + code).isFalse();
    }
  }
}
