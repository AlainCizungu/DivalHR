package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.AccountingManifest;
import com.divalhr.core.payroll.domain.AccountingManifest.Facts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** MVP-096: the manifest is canonical JSON with sorted keys and no amounts per employee. */
class AccountingManifestTest {

  private static Facts facts(String entityCode) {
    UUID id = UUID.fromString("00000000-0000-4000-8000-000000000001");
    return new Facts(
        id, 1, id, 2, id, 3, id, entityCode, "2026-01", "CDF", id, 1, "a".repeat(64), 5, 1100,
        1100, Instant.parse("2026-02-01T10:00:00Z"), "f.csv", 321, "b".repeat(64));
  }

  private static String text(Facts facts) {
    return new String(AccountingManifest.manifest(facts), StandardCharsets.UTF_8);
  }

  @Test
  void keysAreSortedAndTheTimestampIsFixedWidthUtc() {
    String json = text(facts("ENT-1"));
    List<String> keys = new ArrayList<>();
    Matcher matcher = Pattern.compile("\"([A-Za-z0-9]+)\":").matcher(json);
    while (matcher.find()) {
      keys.add(matcher.group(1));
    }
    assertThat(keys).hasSize(21);
    assertThat(keys).isSorted();
    assertThat(json).contains("\"generatedAt\":\"2026-02-01T10:00:00.000Z\"");
    assertThat(json).contains("\"totalDebitsMinor\":1100");
    assertThat(json).contains("\"schemaVersion\":1");
  }

  @Test
  void theSameFactsGiveTheSameBytes() {
    assertThat(AccountingManifest.manifest(facts("ENT-1")))
        .isEqualTo(AccountingManifest.manifest(facts("ENT-1")));
  }

  @Test
  void stringsAreEscaped() {
    assertThat(text(facts("A\"B\\C"))).contains("\"legalEntityCode\":\"A\\\"B\\\\C\"");
  }

  @Test
  void thereIsNoWhitespaceOutsideStrings() {
    String json = text(facts("ENT-1"));
    assertThat(json).startsWith("{\"calculationId\"");
    assertThat(json).endsWith("}");
    assertThat(json).doesNotContain("\n");
  }
}
