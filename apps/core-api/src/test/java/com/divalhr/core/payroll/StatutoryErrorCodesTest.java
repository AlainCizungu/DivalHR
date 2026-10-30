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
 * MVP-094B: the stable codes of the statutory preview exist, carry the status the contract promises
 * and are published in the shared problem schema and the API specification.
 */
class StatutoryErrorCodesTest {

  private static final Map<String, HttpStatus> CODES =
      Map.ofEntries(
          Map.entry("STATUTORY_RULE_PACK_NOT_FOUND", HttpStatus.NOT_FOUND),
          Map.entry("STATUTORY_RULE_PACK_NOT_VERIFIED", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_RULE_PACK_NOT_APPLICABLE", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_PROFILE_MISSING", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_PROFILE_UNSUPPORTED", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_CALCULATION_BLOCKED", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_PREVIEW_STALE", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_CALCULATION_NO_CHANGE", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_VERSION_CONFLICT", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_LIMIT_REACHED", HttpStatus.CONFLICT),
          Map.entry("STATUTORY_CALCULATION_NOT_FOUND", HttpStatus.NOT_FOUND));

  @Test
  void everyCodeExistsWithItsStatus() {
    for (Map.Entry<String, HttpStatus> code : CODES.entrySet()) {
      ErrorCode found = ErrorCode.valueOf(code.getKey());
      assertThat(found.status()).as(code.getKey()).isEqualTo(code.getValue());
    }
  }

  @Test
  void noOtherStatutoryCodeExists() {
    long statutory =
        java.util.Arrays.stream(ErrorCode.values())
            .filter(code -> code.name().startsWith("STATUTORY_"))
            .count();
    assertThat(statutory).isEqualTo(CODES.size());
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
