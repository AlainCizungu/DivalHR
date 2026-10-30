package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollRunWorld.runCommand;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-094A (D94A-13) with the default buckets: five run previews and two run creations per minute
 * and subject, then a stable 429 that discloses nothing and writes nothing.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class PayrollRunRateLimitIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollRunWorld w;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollRunWorld(mvc, jdbc, lookups, clock);
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", w.month.atDay(1).minusDays(5), "USD", "1250.00");
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
  void theSixthPreviewOfAMinuteIsRateLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      w.c.expect(w.runPreviewRequest(runCommand(w.month, "USD")), 200);
    }
    limited(w.c.call(w.runPreviewRequest(runCommand(w.month, "USD"))));
    assertThat(w.evidence().get(0)).isZero();
  }

  @Test
  void theThirdCreationOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    Map<String, Object> forged = new LinkedHashMap<>(runCommand(w.month, "USD"));
    forged.put("expectedVersion", 0);
    forged.put("inputDigest", "a".repeat(64));
    forged.put("previewDigest", "b".repeat(64));
    forged.put("expiresAt", "2999-01-01T00:00:00Z");
    forged.put("confirmed", true);
    for (int i = 0; i < 2; i++) {
      JsonNode problem = w.c.expect(w.runCommitRequest(Organizations.newKey(), forged), 409);
      assertThat(problem.get("code").asText()).isEqualTo("PAYROLL_RUN_PREVIEW_STALE");
    }
    limited(w.c.call(w.runCommitRequest(Organizations.newKey(), forged)));
    assertThat(w.evidence().get(0)).isZero();
  }
}
