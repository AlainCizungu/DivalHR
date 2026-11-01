package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-094C (R182-2, ADR 0035): the payroll approval and payslip reads keep the approved 10 s. */
class PayrollReadTimeoutTest {

  private static final String PACKAGE = "com.divalhr.core.payroll.application.";

  @Test
  void everyApprovalAndPayslipReadTransactionIsLimitedToTenSeconds() throws Exception {
    for (String name :
        List.of("PayrollApprovalService", "PayrollVoidService", "PayslipService", "PayrollPackageService")) {
      Field field = Class.forName(PACKAGE + name).getDeclaredField("READ_TIMEOUT");
      field.setAccessible(true);
      assertThat((Duration) field.get(null)).as(name).isEqualTo(Duration.ofSeconds(10));
    }
  }
}
