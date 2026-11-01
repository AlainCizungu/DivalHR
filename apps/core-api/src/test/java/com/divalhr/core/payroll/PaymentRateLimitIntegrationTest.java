package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.SettableClock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095A (Issue #183, ADR 0036, D95A-13): the payment buckets (60 reads, 10 destination previews
 * and 30 downloads per minute and subject by default) end in a stable 429 that discloses nothing and
 * writes nothing. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PaymentRateLimitIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private UUID employee;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    employee = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS).employee();
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  /** Calls until the limit answers; the answer must come within {@code limit} more calls. */
  private interface Call {
    MockHttpServletRequestBuilder build() throws Exception;
  }

  private void untilLimited(int limit, Call call) throws Exception {
    List<Integer> before = p.evidence();
    MockHttpServletResponse last = null;
    for (int i = 0; i <= limit; i++) {
      last = w.c.call(call.build());
      if (last.getStatus() == 429) {
        break;
      }
    }
    assertThat(last).isNotNull();
    assertThat(last.getStatus()).isEqualTo(429);
    assertThat(CompensationWorld.JSON.readTree(last.getContentAsString()).get("code").asText())
        .isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(last.getHeader("Retry-After"))).isBetween(1, 60);
    assertThat(last.getContentAsString()).doesNotContain(PaymentWorld.ACCOUNT);
    assertThat(p.evidence()).isEqualTo(before);
  }

  private MockHttpServletRequestBuilder read() throws Exception {
    return PayrollRunWorld.get(w.admin, PaymentWorld.destinationPath(employee));
  }

  @Test
  void theSixtyFirstReadOfAMinuteIsRateLimited() throws Exception {
    untilLimited(60, this::read);
  }

  @Test
  void theEleventhDestinationPreviewOfAMinuteIsRateLimitedAndWritesNothing() throws Exception {
    untilLimited(
        10,
        () ->
            p.destinationPreview(
                w.admin, employee, p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP")));
  }
}
