package com.divalhr.core.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.PostgresContainerConfig;
import com.divalhr.core.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Container probes must be anonymous (the orchestrator has no token) and must not reveal details.
 * The management port is folded into the main port here so MockMvc can reach it.
 */
@SpringBootTest(
    properties = {
      "divalhr.environment=test",
      "server.port=8080",
      "management.server.port=8080"
    })
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, PostgresContainerConfig.class})
class HealthProbeIntegrationTest {

  @Autowired private MockMvc mvc;

  @Test
  void readinessAndLivenessAreAnonymousAndHideDetails() throws Exception {
    for (String probe : new String[] {"/actuator/health/readiness", "/actuator/health/liveness"}) {
      mvc.perform(get(probe))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"))
          .andExpect(jsonPath("$.components").doesNotExist())
          .andExpect(jsonPath("$.details").doesNotExist());
    }
  }

  @Test
  void otherActuatorEndpointsAreNotExposed() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    mvc.perform(get("/actuator/beans")).andExpect(status().is4xxClientError());
  }
}
