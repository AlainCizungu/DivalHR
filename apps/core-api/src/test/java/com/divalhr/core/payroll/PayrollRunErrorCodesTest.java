package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.divalhr.core.platform.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * MVP-094A: the stable codes of payroll policies and runs exist, carry the status the contract
 * promises and are published in the shared problem schema and the API specification.
 */
class PayrollRunErrorCodesTest {

  private static final Map<String, HttpStatus> CODES =
      Map.of(
          "PAYROLL_POLICY_NOT_FOUND", HttpStatus.CONFLICT,
          "PAYROLL_POLICY_CONFLICT", HttpStatus.CONFLICT,
          "PAYROLL_PERIOD_INVALID", HttpStatus.UNPROCESSABLE_CONTENT,
          "PAYROLL_RUN_NOT_FOUND", HttpStatus.NOT_FOUND,
          "PAYROLL_RUN_BLOCKED", HttpStatus.CONFLICT,
          "PAYROLL_RUN_PREVIEW_STALE", HttpStatus.CONFLICT,
          "PAYROLL_RUN_VERSION_CONFLICT", HttpStatus.CONFLICT,
          "PAYROLL_RUN_LIMIT_REACHED", HttpStatus.CONFLICT);

  @Test
  void everyCodeExistsWithItsStatus() {
    for (Map.Entry<String, HttpStatus> code : CODES.entrySet()) {
      ErrorCode found = ErrorCode.valueOf(code.getKey());
      assertThat(found.status()).as(code.getKey()).isEqualTo(code.getValue());
    }
  }

  @Test
  void everyCodeIsPublishedInTheSchemaAndTheSpecification() throws IOException {
    Path schema = Path.of("../../packages/shared-contracts/schemas/problem.schema.json");
    Path spec = Path.of("../../docs/API-SPEC.yaml");
    assumeTrue(Files.exists(schema) && Files.exists(spec), "repository documents not available");
    String schemaText = Files.readString(schema, StandardCharsets.UTF_8);
    String specText = Files.readString(spec, StandardCharsets.UTF_8);
    for (String code : CODES.keySet()) {
      assertThat(schemaText).as("problem schema " + code).contains("\"" + code + "\"");
      assertThat(specText).as("API specification " + code).contains(code);
    }
  }
}
