package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094B (ADR 0034 section 9) with the default buckets: five statutory previews, two calculation
 * creations and two activations per minute and subject, ten profile previews and five profile
 * commits, 60 reads, then a stable 429 that discloses nothing and writes nothing.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class StatutoryRateLimitIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private StatutoryWorld w;
  private String run;
  private UUID employee;

  @BeforeEach
  void world() throws Exception {
    w = new StatutoryWorld(mvc, jdbc, lookups, clock);
    employee = w.paidInCdf("Mukendi", StatutoryWorld.GROSS);
    run = w.cdfRun();
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private void limited(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(CompensationWorld.JSON.readTree(response.getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(response.getHeader("Retry-After"))).isBetween(1, 60);
  }

  @Test
  void theSixthCalculationPreviewOfAMinuteIsRateLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      w.c.expect(w.previewRequest(run), 200);
    }
    limited(w.c.call(w.previewRequest(run)));
    assertThat(w.evidence().get(2)).isZero();
  }

  @Test
  void theThirdCalculationCreationOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    Map<String, Object> forged = new LinkedHashMap<>();
    forged.put("expectedVersion", 0);
    forged.put("sourceRunDigest", "a".repeat(64));
    forged.put("profileDigest", "a".repeat(64));
    forged.put("rulePackDigest", "a".repeat(64));
    forged.put("calculationDigest", "b".repeat(64));
    forged.put("expiresAt", "2999-01-01T00:00:00Z");
    forged.put("confirmed", true);
    for (int i = 0; i < 2; i++) {
      JsonNode problem = w.c.expect(w.createRequest(run, Organizations.newKey(), forged), 409);
      assertThat(problem.get("code").asText()).startsWith("STATUTORY_");
    }
    limited(w.c.call(w.createRequest(run, Organizations.newKey(), forged)));
    assertThat(w.evidence().get(2)).isZero();
  }

  @Test
  void theThirdActivationOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    for (int i = 0; i < 2; i++) {
      w.c.expect(
          w.activateRequest(
              Organizations.newKey(), StatutoryWorld.activation(UUID.randomUUID(), w.month, 0)),
          404);
    }
    limited(
        w.c.call(
            w.activateRequest(
                Organizations.newKey(), StatutoryWorld.activation(UUID.randomUUID(), w.month, 0))));
    assertThat(w.evidence().get(0)).isZero();
  }

  @Test
  void theEleventhProfilePreviewAndTheSixthProfileCommitOfAMinuteAreRateLimited() throws Exception {
    Map<String, Object> command =
        StatutoryWorld.profileCommand(
            w.month.atDay(1).minusDays(30), "STANDARD_RESIDENT", true, true, "EMPLOYMENT_CONTRACT");
    for (int i = 0; i < 10; i++) {
      w.c.expect(w.profilePreviewRequest(employee, command), 200);
    }
    limited(w.c.call(w.profilePreviewRequest(employee, command)));

    Map<String, Object> forged = new LinkedHashMap<>(command);
    forged.put("expectedVersion", 9);
    forged.put("previewDigest", "a".repeat(64));
    forged.put("expiresAt", "2999-01-01T00:00:00Z");
    forged.put("confirmed", true);
    for (int i = 0; i < 5; i++) {
      w.c.expect(w.profileCommitRequest(employee, Organizations.newKey(), forged), 409);
    }
    limited(w.c.call(w.profileCommitRequest(employee, Organizations.newKey(), forged)));
    assertThat(w.evidence().get(1)).isZero();
  }

  @Test
  void theSixtyFirstReadOfAMinuteIsRateLimited() throws Exception {
    for (int i = 0; i < 60; i++) {
      w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?limit=1"), 200);
    }
    limited(w.c.call(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?limit=1")));
  }
}
