package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-4): strict E.164, and only a masked form is ever displayed. */
class PhoneNumberTest {

  @Test
  void acceptsStrictE164() {
    for (String ok : List.of("+243812345678", "+33612345678", "+14155550123", "+1234567")) {
      assertThat(PhoneNumber.parse(ok)).as(ok).isPresent();
    }
    assertThat(PhoneNumber.parse("+243812345678").orElseThrow().digits()).isEqualTo("243812345678");
  }

  @Test
  void refusesEverythingElse() {
    for (Object bad :
        new Object[] {
          null,
          42,
          "",
          "243812345678",
          "+0123456789",
          "+12345",
          "+1234567890123456",
          "+243 81 234 5678",
          "+243-812-345-678",
          "00243812345678",
          "+243812345678 ",
          " +243812345678",
          "+243812345678\n",
          "+٢٤٣٨١٢٣٤٥٦٧٨",
          "tel:+243812345678",
          "+24381234567a"
        }) {
      assertThat(PhoneNumber.parse(bad)).as(String.valueOf(bad)).isEmpty();
    }
  }

  @Test
  void theMaskedFormShowsOnlyTheFirstDigitAndTheLastTwo() {
    PhoneNumber number = PhoneNumber.parse("+243812345678").orElseThrow();
    assertThat(number.masked()).isEqualTo("+2 *** ** 78");
    assertThat(PhoneNumber.mask("+14155550123")).isEqualTo("+1 *** ** 23");
    assertThat(number.masked()).doesNotContain("8123456");
  }

  @Test
  void toStringIsMaskedSoALogLineNeverCarriesTheNumber() {
    PhoneNumber number = PhoneNumber.parse("+243812345678").orElseThrow();
    assertThat(number.toString()).isEqualTo(number.masked());
    assertThat("sending to " + number).doesNotContain("243812345678");
  }

  @Test
  void equalityFollowsTheNumber() {
    assertThat(PhoneNumber.parse("+243812345678")).isEqualTo(PhoneNumber.parse("+243812345678"));
    assertThat(PhoneNumber.parse("+243812345678").orElseThrow().hashCode())
        .isEqualTo(PhoneNumber.parse("+243812345678").orElseThrow().hashCode());
    assertThat(PhoneNumber.parse("+243812345678")).isNotEqualTo(PhoneNumber.parse("+243812345679"));
  }
}
