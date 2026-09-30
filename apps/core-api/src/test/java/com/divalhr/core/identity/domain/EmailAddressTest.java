package com.divalhr.core.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

  @ParameterizedTest
  @CsvSource({
    "Ana.Mbuyi@Example.CD,ana.mbuyi@example.cd",
    "'  ana@example.cd  ',ana@example.cd",
    "o'brien+hr@sub.example.org,o'brien+hr@sub.example.org",
    "ana@exämple.cd,ana@xn--exmple-cua.cd",
    "ANA@ÉCOLE.CD,ana@xn--cole-9oa.cd"
  })
  void normalizesCaseWhitespaceAndInternationalDomains(String raw, String expected) {
    assertThat(EmailAddress.parse(raw)).map(EmailAddress::value).contains(expected);
    assertThat(EmailAddress.defectOf(raw)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "no-at-sign",
        "two@@example.cd",
        "a@b@example.cd",
        "@example.cd",
        "ana@",
        "ana@localhost",
        "ana@-bad.cd",
        "ana..double@example.cd",
        ".lead@example.cd",
        "trail.@example.cd",
        "ana smith@example.cd",
        "\"quoted\"@example.cd",
        "Ana <ana@example.cd>",
        "anä@example.cd",
        "ana@exa_mple.cd",
        "ana\u0000@example.cd",
        "ana@example.cd\nBcc: x@example.cd"
      })
  void rejectsUnsupportedShapes(String raw) {
    assertThat(EmailAddress.parse(raw)).isEmpty();
    assertThat(EmailAddress.defectOf(raw)).isPresent();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void blankIsRequired(String raw) {
    assertThat(EmailAddress.defectOf(raw)).contains(EmailAddress.Defect.REQUIRED);
  }

  @org.junit.jupiter.api.Test
  void lengthLimitsAndRedaction() {
    String local65 = "a".repeat(65) + "@example.cd";
    assertThat(EmailAddress.parse(local65)).isEmpty();
    assertThat(EmailAddress.defectOf("a@" + "b".repeat(250) + ".cd"))
        .contains(EmailAddress.Defect.LENGTH);
    assertThat(EmailAddress.defectOf("a@")).contains(EmailAddress.Defect.LENGTH);
    assertThat(EmailAddress.parse("ana@example.cd").orElseThrow().toString())
        .doesNotContain("ana")
        .contains("redacted");
  }
}
