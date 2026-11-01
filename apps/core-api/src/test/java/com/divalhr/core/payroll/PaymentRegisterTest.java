package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.PaymentMethod;
import com.divalhr.core.payroll.domain.PaymentRegister;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-095A (D95A-7): the register is deterministic, formula-safe, CRLF UTF-8 and masked only. */
class PaymentRegisterTest {

  private static final UUID ENTITY = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PACKAGE = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID BATCH = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String APPROVAL = "a".repeat(64);

  private static PaymentRegister.Header header(int lines, long total) {
    return new PaymentRegister.Header(
        ENTITY, PACKAGE, 2, APPROVAL, BATCH, 1, "PREPARED", "2026-10", "CDF", lines, 1, 1, 1, 1, total);
  }

  private static List<PaymentRegister.Row> rows() {
    return List.of(
        new PaymentRegister.Row("E-001", PaymentMethod.BANK_ACCOUNT, "RAWBANK", "****8901", 149040),
        new PaymentRegister.Row("E-002", PaymentMethod.MOBILE_MONEY, "MPESA", "******123", 80000),
        new PaymentRegister.Row("E-003", PaymentMethod.MANUAL, null, null, 1200));
  }

  @Test
  void theCsvIsDeterministicCrlfUtf8WithAFixedHeader() {
    byte[] first = PaymentRegister.csv(header(3, 230240), rows());
    byte[] second = PaymentRegister.csv(header(3, 230240), rows());
    assertThat(first).isEqualTo(second);
    String text = new String(first, StandardCharsets.UTF_8);
    assertThat(text).startsWith(PaymentRegister.HEADER + "\r\n");
    assertThat(text.split("\r\n")).hasSize(4);
    assertThat(text.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    assertThat(first[0]).isNotEqualTo((byte) 0xEF);
    assertThat(text)
        .contains("E-001,BANK_ACCOUNT,RAWBANK,****8901,149040")
        .contains("E-003,MANUAL,,,1200");
  }

  @Test
  void cellsThatCouldBeFormulasAreNeutralised() {
    for (String hostile : List.of("=1+1", "+1", "-1", "@SUM(A1)", "\tcmd", "\rcmd")) {
      byte[] csv =
          PaymentRegister.csv(
              header(1, 1),
              List.of(new PaymentRegister.Row(hostile, PaymentMethod.MANUAL, null, null, 1)));
      String line = new String(csv, StandardCharsets.UTF_8).split("\r\n")[1];
      assertThat(line).as(hostile).doesNotContain("," + hostile).doesNotContain(",\"" + hostile);
      assertThat(line).as(hostile).contains("'" + hostile);
    }
  }

  @Test
  void aCommaOrQuoteInACellIsQuoted() {
    byte[] csv =
        PaymentRegister.csv(
            header(1, 1),
            List.of(new PaymentRegister.Row("A,\"B\"", PaymentMethod.MANUAL, null, null, 1)));
    assertThat(new String(csv, StandardCharsets.UTF_8)).contains("\"A,\"\"B\"\"\"");
  }

  @Test
  void theManifestStatesCountsTotalsAndTheCsvDigestOnly() {
    String sha = "b".repeat(64);
    String manifest =
        new String(PaymentRegister.manifest(header(3, 230240), sha), StandardCharsets.UTF_8);
    assertThat(manifest)
        .contains("\"csvSha256\":\"" + sha + "\"")
        .contains("\"totalNetMinor\":230240")
        .contains("\"lineCount\":3")
        .contains("\"packageApprovalDigest\":\"" + APPROVAL + "\"")
        .contains("PAYMENT_PREPARATION_ONLY")
        .doesNotContain("E-001")
        .doesNotContain("8901");
    assertThat(PaymentRegister.manifest(header(3, 230240), sha))
        .isEqualTo(PaymentRegister.manifest(header(3, 230240), sha));
  }
}
