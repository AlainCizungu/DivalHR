package com.divalhr.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.operations.domain.ExpenseCurrency;
import org.junit.jupiter.api.Test;

/** The currency and amount rules of MVP-081 (D81-2): exact decimal strings, no conversion. */
class ExpenseCurrencyTest {

  @Test
  void congoleseFrancsAreWholeAndBounded() {
    assertThat(ExpenseCurrency.CDF.parse("1")).contains(1L);
    assertThat(ExpenseCurrency.CDF.parse("100000000")).contains(100_000_000L);
    assertThat(ExpenseCurrency.CDF.parse("100000001")).isEmpty();
    assertThat(ExpenseCurrency.CDF.parse("0")).isEmpty();
    for (String bad :
        new String[] {"10.5", "10.00", "1e3", "-5", "+5", "05", " 5", "5 ", "1,000", "", "٣"}) {
      assertThat(ExpenseCurrency.CDF.parse(bad)).as(bad).isEmpty();
    }
  }

  @Test
  void usDollarsHaveAtMostTwoDecimalsAndAreBounded() {
    assertThat(ExpenseCurrency.USD.parse("0.01")).contains(1L);
    assertThat(ExpenseCurrency.USD.parse("12.5")).contains(1250L);
    assertThat(ExpenseCurrency.USD.parse("12")).contains(1200L);
    assertThat(ExpenseCurrency.USD.parse("100000.00")).contains(10_000_000L);
    assertThat(ExpenseCurrency.USD.parse("100000.01")).isEmpty();
    assertThat(ExpenseCurrency.USD.parse("0")).isEmpty();
    assertThat(ExpenseCurrency.USD.parse("0.00")).isEmpty();
    for (String bad :
        new String[] {"1.234", "1.", ".5", "1e2", "-1", "01.00", "1,50", "NaN", "Infinity"}) {
      assertThat(ExpenseCurrency.USD.parse(bad)).as(bad).isEmpty();
    }
  }

  @Test
  void theCanonicalTextHasTheCurrencysOwnScaleAndNeverCrossesCurrencies() {
    assertThat(ExpenseCurrency.USD.format(1250)).isEqualTo("12.50");
    assertThat(ExpenseCurrency.USD.format(1)).isEqualTo("0.01");
    assertThat(ExpenseCurrency.CDF.format(125000)).isEqualTo("125000");
    // The same minor units mean different amounts: there is no conversion anywhere.
    assertThat(ExpenseCurrency.CDF.format(1250)).isNotEqualTo(ExpenseCurrency.USD.format(1250));
    assertThat(ExpenseCurrency.CDF.exponent()).isZero();
    assertThat(ExpenseCurrency.USD.exponent()).isEqualTo(2);
  }
}
