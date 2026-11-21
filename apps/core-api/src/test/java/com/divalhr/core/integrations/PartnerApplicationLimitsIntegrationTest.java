package com.divalhr.core.integrations;

import static com.divalhr.core.integrations.PartnerWorld.body;
import static com.divalhr.core.integrations.PartnerWorld.code;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-120D (Issue #215, ADR 0043 D120D-10): the public limits. A per-client limit and a
 * per-business-email limit, both answering the dedicated code with {@code Retry-After}; an exact
 * replay is never refused; the email limit counts attempts and never consults stored applications,
 * so it cannot reveal whether an applicant exists. Every value is SYNTHETIC.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.rate-limit.per-client-requests=7",
      "divalhr.partner-application.email-attempts-per-hour=2"
    })
class PartnerApplicationLimitsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void theEmailLimitAndTheClientLimitAnswerWithTheDedicatedCode() throws Exception {
    String email = PartnerWorld.uniqueEmail();
    String replayKey = "synthetic-key-" + UUID.randomUUID();
    Map<String, Object> first = PartnerWorld.bank(PartnerWorld.uniqueName(), email);

    assertThat(PartnerWorld.submit(mvc, first, replayKey).getStatus()).isEqualTo(201);
    assertThat(
            PartnerWorld.submit(mvc, PartnerWorld.bank(PartnerWorld.uniqueName(), email))
                .getStatus())
        .isEqualTo(201);

    // Third attempt with the same business email: refused, nothing stored.
    Map<String, Object> third = PartnerWorld.bank(PartnerWorld.uniqueName(), email);
    MockHttpServletResponse limited = PartnerWorld.submit(mvc, third);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(code(limited)).isEqualTo("PARTNER_APPLICATION_RATE_LIMITED");
    assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isPositive();
    assertThat(limited.getContentAsString()).doesNotContain(email);
    assertThat(count(third.get("legalName"))).isZero();

    // An exact replay is a lookup, not a new attempt.
    MockHttpServletResponse replay = PartnerWorld.submit(mvc, first, replayKey);
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");

    // Other emails still pass until the client limit (7 requests per window) is reached.
    int sent = 4;
    MockHttpServletResponse last = null;
    while (sent < 8) {
      last =
          PartnerWorld.submit(
              mvc, PartnerWorld.bank(PartnerWorld.uniqueName(), PartnerWorld.uniqueEmail()));
      sent++;
      if (last.getStatus() == 429) {
        break;
      }
    }
    assertThat(last.getStatus()).isEqualTo(429);
    assertThat(code(last)).isEqualTo("PARTNER_APPLICATION_RATE_LIMITED");
    assertThat(last.getHeader("Retry-After")).isNotBlank();
    assertThat(body(last).path("code").asText()).isEqualTo("PARTNER_APPLICATION_RATE_LIMITED");
  }

  private int count(Object legalName) {
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM integrations.partner_application WHERE legal_name = ?",
            Integer.class,
            legalName);
    return total == null ? 0 : total;
  }
}
