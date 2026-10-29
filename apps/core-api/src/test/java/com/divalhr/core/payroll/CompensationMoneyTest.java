package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationMoney.Parsed;
import com.divalhr.core.payroll.domain.CompensationMoney.Status;
import org.junit.jupiter.api.Test;

/**
 * MVP-093A (D93-4): exact CDF and USD parsing, canonical text and bounds, with no floating point,
 * no conversion and no amount in {@code toString}, {@code hashCode} or an exception message.
 */
class CompensationMoneyTest {

  private static Status status(CompensationCurrency currency, String text) {
    return CompensationMoney.parse(currency, text).status();
  }

  @Test
  void congoleseFrancsAreWholeAndBoundedAtBothEnds() {
    assertThat(status(CompensationCurrency.CDF, "1")).isEqualTo(Status.OK);
    assertThat(status(CompensationCurrency.CDF, "1000000000")).isEqualTo(Status.OK);
    assertThat(status(CompensationCurrency.CDF, "0")).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(status(CompensationCurrency.CDF, "1000000001")).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(CompensationMoney.parse(CompensationCurrency.CDF, "1").money().minor())
        .isEqualTo(1L);
    assertThat(CompensationMoney.parse(CompensationCurrency.CDF, "1000000000").money().minor())
        .isEqualTo(1_000_000_000L);
  }

  @Test
  void usDollarsHaveOneOrTwoFractionDigitsAndAreBoundedAtBothEnds() {
    assertThat(status(CompensationCurrency.USD, "0.01")).isEqualTo(Status.OK);
    assertThat(status(CompensationCurrency.USD, "100000.00")).isEqualTo(Status.OK);
    assertThat(status(CompensationCurrency.USD, "100000")).isEqualTo(Status.OK);
    assertThat(status(CompensationCurrency.USD, "0.00")).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(status(CompensationCurrency.USD, "0")).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(status(CompensationCurrency.USD, "100000.01")).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "0.01").money().minor())
        .isEqualTo(1L);
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "100000.00").money().minor())
        .isEqualTo(10_000_000L);
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "12.5").money().minor())
        .isEqualTo(1250L);
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "0.05").money().minor())
        .isEqualTo(5L);
  }

  @Test
  void signExponentGroupingScaleAndShapeAreRefusedNeverRounded() {
    String[] bad = {
      "1,000",
      "+5",
      "1e3",
      "1E3",
      "-1",
      "-0",
      "1.234",
      "01",
      "00",
      "",
      " 5",
      "5 ",
      "5.",
      ".5",
      "5.0.0",
      "NaN",
      "Infinity",
      "0x10",
      "٣",
      "5_000",
      "1 000",
      "\n5",
      "5\n"
    };
    for (String text : bad) {
      Status usd = status(CompensationCurrency.USD, text);
      Status cdf = status(CompensationCurrency.CDF, text);
      assertThat(usd).as("USD %s", text).isEqualTo(Status.MALFORMED);
      assertThat(cdf).as("CDF %s", text).isEqualTo(Status.MALFORMED);
    }
    assertThat(status(CompensationCurrency.CDF, null)).isEqualTo(Status.MALFORMED);
    assertThat(status(CompensationCurrency.USD, null)).isEqualTo(Status.MALFORMED);
    // CDF has no fraction at all, not even a zero one.
    assertThat(status(CompensationCurrency.CDF, "5.0")).isEqualTo(Status.MALFORMED);
    assertThat(status(CompensationCurrency.CDF, "5.00")).isEqualTo(Status.MALFORMED);
    assertThat(status(CompensationCurrency.CDF, "0.5")).isEqualTo(Status.MALFORMED);
    // A single zero before the fraction is the only legal leading zero.
    assertThat(status(CompensationCurrency.USD, "0.5")).isEqualTo(Status.OK);
    assertThat(status(CompensationCurrency.USD, "00.50")).isEqualTo(Status.MALFORMED);
  }

  @Test
  void aVeryLongNumberIsOutOfRangeNotAnOverflow() {
    assertThat(status(CompensationCurrency.CDF, "9".repeat(15))).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(status(CompensationCurrency.CDF, "9".repeat(16))).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(status(CompensationCurrency.USD, "9".repeat(25))).isEqualTo(Status.OUT_OF_RANGE);
    assertThat(status(CompensationCurrency.USD, "9".repeat(40))).isEqualTo(Status.MALFORMED);
  }

  @Test
  void theCanonicalTextHasTheCurrencysOwnScale() {
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "5").money().amount())
        .isEqualTo("5.00");
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "5.5").money().amount())
        .isEqualTo("5.50");
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "0.07").money().amount())
        .isEqualTo("0.07");
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "1250.00").money().amount())
        .isEqualTo("1250.00");
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "100000").money().amount())
        .isEqualTo("100000.00");
    assertThat(CompensationMoney.parse(CompensationCurrency.CDF, "135000").money().amount())
        .isEqualTo("135000");
    assertThat(CompensationMoney.ofMinor(CompensationCurrency.USD, 1L).amount()).isEqualTo("0.01");
    assertThat(CompensationMoney.ofMinor(CompensationCurrency.CDF, 1250L).amount())
        .isEqualTo("1250");
  }

  @Test
  void parsingIsExactForEveryUsdCentInARange() {
    for (long cents = 1; cents <= 3000; cents++) {
      CompensationMoney money = CompensationMoney.ofMinor(CompensationCurrency.USD, cents);
      Parsed back = CompensationMoney.parse(CompensationCurrency.USD, money.amount());
      assertThat(back.status()).isEqualTo(Status.OK);
      assertThat(back.money().minor()).isEqualTo(cents);
      assertThat(back.money().amount()).isEqualTo(money.amount());
    }
  }

  @Test
  void currenciesNeverCompareAsEqualOrConvert() {
    CompensationMoney francs = CompensationMoney.ofMinor(CompensationCurrency.CDF, 1250L);
    CompensationMoney dollars = CompensationMoney.ofMinor(CompensationCurrency.USD, 1250L);
    assertThat(francs.sameAs(dollars)).isFalse();
    assertThat(francs.equals(dollars)).isFalse();
    assertThat(francs.amount()).isEqualTo("1250");
    assertThat(dollars.amount()).isEqualTo("12.50");
    assertThat(francs.sameAs(CompensationMoney.ofMinor(CompensationCurrency.CDF, 1250L))).isTrue();
  }

  @Test
  void ofMinorRefusesAnIllegalAmountWithoutShowingIt() {
    try {
      CompensationMoney.ofMinor(CompensationCurrency.USD, 987_654_321L);
      throw new AssertionError("an illegal amount must be refused");
    } catch (IllegalArgumentException refused) {
      assertThat(refused.getMessage()).doesNotContain("987654321");
      assertThat(refused.getMessage()).doesNotContain("9876543");
    }
  }

  @Test
  void theAmountNeverAppearsInToStringHashCodeOrParsed() {
    CompensationMoney money = CompensationMoney.ofMinor(CompensationCurrency.USD, 123_456L);
    assertThat(money.toString()).doesNotContain("1234");
    assertThat(money.toString()).doesNotContain("56");
    assertThat(money.toString()).isEqualTo("CompensationMoney[USD]");
    CompensationMoney other = CompensationMoney.ofMinor(CompensationCurrency.USD, 7L);
    assertThat(money.hashCode()).isEqualTo(other.hashCode());
    Parsed parsed = CompensationMoney.parse(CompensationCurrency.USD, "1234.56");
    assertThat(parsed.toString()).doesNotContain("1234");
    assertThat(parsed.toString()).isEqualTo("Parsed[OK]");
    assertThat(CompensationMoney.parse(CompensationCurrency.USD, "1234.567").toString())
        .isEqualTo("Parsed[MALFORMED]");
  }
}
