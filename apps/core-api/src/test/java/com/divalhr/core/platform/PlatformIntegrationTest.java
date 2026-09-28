package com.divalhr.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PlatformIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void publicStatusIsAnonymousAndCarriesCorrelationId() throws Exception {
    mvc.perform(get("/api/v1/system/status").header("X-Correlation-Id", "smoke-test-0001"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-Id", "smoke-test-0001"))
        .andExpect(jsonPath("$.service").value("core-api"))
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void generatesCorrelationIdWhenAbsent() throws Exception {
    mvc.perform(get("/api/v1/system/status")).andExpect(header().exists("X-Correlation-Id"));
  }

  @Test
  void everyOtherRouteIsDeniedByDefaultWithProblemContract() throws Exception {
    mvc.perform(get("/api/v1/anything"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.correlationId").exists());
  }

  @Test
  void flywayCreatesOneSchemaPerModule() {
    List<String> schemas =
        jdbc.queryForList("select schema_name from information_schema.schemata", String.class);
    assertThat(schemas)
        .contains(
            "platform",
            "identity",
            "tenant",
            "people",
            "operations",
            "payroll",
            "documents",
            "integrations",
            "analytics",
            "finance");
  }
}
