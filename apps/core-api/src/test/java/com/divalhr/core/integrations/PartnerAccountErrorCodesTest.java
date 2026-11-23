package com.divalhr.core.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * MVP-120E: the twelve partner-account and catalogue codes exist, are published and are safe text.
 */
class PartnerAccountErrorCodesTest {

  private static final Map<String, HttpStatus> CODES = new LinkedHashMap<>();

  static {
    CODES.put("PARTNER_SOURCE_APPLICATION_NOT_APPROVED", HttpStatus.CONFLICT);
    CODES.put("PARTNER_ALREADY_CREATED", HttpStatus.CONFLICT);
    CODES.put("PARTNER_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PARTNER_TRANSITION_INVALID", HttpStatus.CONFLICT);
    CODES.put("PARTNER_HAS_ACTIVE_PRODUCTS", HttpStatus.CONFLICT);
    CODES.put("PARTNER_PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND);
    CODES.put("PARTNER_PRODUCT_INVALID", HttpStatus.BAD_REQUEST);
    CODES.put("PARTNER_PRODUCT_SCOPE_INVALID", HttpStatus.BAD_REQUEST);
    CODES.put("PARTNER_PRODUCT_TRANSITION_INVALID", HttpStatus.CONFLICT);
    CODES.put("PARTNER_PRODUCT_PARTNER_INACTIVE", HttpStatus.CONFLICT);
    CODES.put("PARTNER_PRODUCT_VERSION_STALE", HttpStatus.CONFLICT);
    CODES.put("PARTNER_PRODUCT_NOT_AVAILABLE", HttpStatus.NOT_FOUND);
  }

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
  void everyCodeExistsWithItsStatusAndFollowsTheApplicationCodesInOrder() {
    assertThat(CODES).hasSize(12);
    ErrorCode[] all = ErrorCode.values();
    int first = ErrorCode.valueOf("PARTNER_SOURCE_APPLICATION_NOT_APPROVED").ordinal();
    assertThat(first).isEqualTo(ErrorCode.PARTNER_APPLICATION_CONSENT_REQUIRED.ordinal() + 1);
    int index = first;
    for (Map.Entry<String, HttpStatus> entry : CODES.entrySet()) {
      assertThat(all[index].name()).isEqualTo(entry.getKey());
      assertThat(all[index].status()).as(entry.getKey()).isEqualTo(entry.getValue());
      index++;
    }
    assertThat(index).as("the twelve codes are the last in the registry").isEqualTo(all.length);
  }

  @Test
  void everyCodeIsPublishedAndHasSafeEnglishAndFrenchText() throws IOException {
    String schema = read(SCHEMA);
    String en = read(EN);
    String fr = read(FR);
    Pattern unsafe =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}|@|\\{|\\}|https?:|/api/|[A-Z]{3,}_[A-Z_]+");
    for (String code : CODES.keySet()) {
      assertThat(schema).contains("\"" + code + "\"");
      assertThat(message(en, code)).isNotBlank();
      assertThat(message(fr, code)).isNotBlank().isNotEqualTo(message(en, code));
      assertThat(unsafe.matcher(message(en, code)).find()).as("EN " + code).isFalse();
      assertThat(unsafe.matcher(message(fr, code)).find()).as("FR " + code).isFalse();
    }
  }
}
