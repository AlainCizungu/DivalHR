package com.divalhr.core.payroll;

import static com.divalhr.core.payroll.PayrollRunWorld.policyCommand;
import static com.divalhr.core.payroll.PayrollRunWorld.runCommand;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-094A (Issue #177) authority: the seven payroll operations are tenant-administrator only with
 * an exact MFA assurance; employees, managers, platform administrators and every insufficient
 * {@code acr} are refused before any processing; a foreign tenant or legal entity is one safe 404;
 * a cursor is bound to its caller. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class PayrollRunAuthorityIntegrationTest {

  private static final List<Object> INSUFFICIENT =
      Arrays.asList(
          null,
          TestTokens.PASSWORD_ACR,
          "0",
          "1",
          "2",
          "URN:DIVALHR:LOA:MFA",
          "urn:divalhr:loa:mfa ",
          "",
          2,
          true,
          List.of(TestTokens.MFA_ACR),
          Map.of("value", TestTokens.MFA_ACR));

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private SettableClock clock;

  private PayrollRunWorld w;
  private String runId;

  @BeforeEach
  void world() throws Exception {
    w = new PayrollRunWorld(mvc, jdbc, lookups, clock);
    w.policy("CALENDAR_DAYS");
    w.paid("Mukendi", w.month.atDay(1).minusDays(5), "USD", "1250.00");
    runId = w.createRun("USD").get("runId").asText();
  }

  @AfterEach
  void reset() {
    clock.reset();
  }

  private String bearer(UUID tenant, String subject, Object acr, String... roles) {
    List<String> claimed = List.of(roles);
    if (claimed.contains("tenant-admin")) {
      Memberships.grant(tenant, subject, "tenant-admin");
    } else if (claimed.contains("employee")) {
      Memberships.grant(tenant, subject, "employee");
    }
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(claimed).acr(acr).build();
  }

  private static String body(Map<String, Object> value) {
    try {
      return CompensationWorld.JSON.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** The seven operations, each as a request builder for the given bearer and legal entity. */
  private List<MockHttpServletRequestBuilder> operations(String bearer, Object entity)
      throws Exception {
    String policy = PayrollRunWorld.policyPath(entity);
    String runs = PayrollRunWorld.runsPath(entity);
    Map<String, Object> policyBody = policyCommand(w.today.minusMonths(1), "DAY", 5, "NONE");
    Map<String, Object> runBody = runCommand(w.month, "USD");
    List<MockHttpServletRequestBuilder> calls = new ArrayList<>();
    calls.add(PayrollRunWorld.get(bearer, policy));
    calls.add(CompensationWorld.json(policy + "/preview", bearer, null, policyBody));
    calls.add(CompensationWorld.json(policy, bearer, "key-" + UUID.randomUUID(), policyBody));
    calls.add(CompensationWorld.json(runs + "/preview", bearer, null, runBody));
    calls.add(PayrollRunWorld.get(bearer, runs));
    calls.add(CompensationWorld.json(runs, bearer, "key-" + UUID.randomUUID(), runBody));
    calls.add(PayrollRunWorld.get(bearer, runs + "/" + runId));
    return calls;
  }

  @Test
  void everyOperationNeedsTheExactMfaAssurance() throws Exception {
    List<Integer> before = w.evidence();
    int audits =
        w.c.count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", w.tenant);
    for (Object acr : INSUFFICIENT) {
      String admin = bearer(w.tenant, "sub-pay94-mfa", acr, "tenant-admin");
      for (MockHttpServletRequestBuilder call : operations(admin, w.entity)) {
        MockHttpServletResponse response = w.c.call(call);
        assertThat(response.getStatus()).as(String.valueOf(acr)).isEqualTo(403);
        JsonNode problem = CompensationWorld.JSON.readTree(response.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(response.getHeader("WWW-Authenticate"))
            .contains("insufficient_user_authentication");
      }
    }
    assertThat(w.evidence()).isEqualTo(before);
    assertThat(w.c.count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", w.tenant))
        .isEqualTo(audits);
  }

  @Test
  void employeesManagersAndPlatformAdministratorsAreDeniedByRole() throws Exception {
    List<Integer> before = w.evidence();
    for (String role : List.of("employee", "manager", "platform-admin")) {
      for (Object acr : List.of(TestTokens.PASSWORD_ACR, TestTokens.MFA_ACR)) {
        String caller = bearer(w.tenant, "sub-pay94-" + role, acr, role);
        for (MockHttpServletRequestBuilder call : operations(caller, w.entity)) {
          MockHttpServletResponse response = w.c.call(call);
          assertThat(response.getStatus()).as(role).isEqualTo(403);
          assertThat(
                  CompensationWorld.JSON
                      .readTree(response.getContentAsString())
                      .get("code")
                      .asText())
              .isEqualTo("ACCESS_DENIED");
          assertThat(response.getHeader("WWW-Authenticate")).isNull();
        }
      }
    }
    assertThat(w.evidence()).isEqualTo(before);
  }

  @Test
  void anAdministratorOfAnotherTenantCannotReachTheEntityOrItsRuns() throws Exception {
    PayrollRunWorld other = new PayrollRunWorld(mvc, jdbc, lookups, clock);
    List<Integer> before = w.evidence();
    for (MockHttpServletRequestBuilder call : operations(other.admin, w.entity)) {
      MockHttpServletResponse response = w.c.call(call);
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(
              CompensationWorld.JSON.readTree(response.getContentAsString()).get("code").asText())
          .isIn("LEGAL_ENTITY_NOT_FOUND", "PAYROLL_RUN_NOT_FOUND");
    }
    assertThat(w.evidence()).isEqualTo(before);
    // The foreign run id through the caller's own entity is a run that does not exist.
    w.c.problem(
        PayrollRunWorld.get(other.admin, other.runsPath() + "/" + runId),
        404,
        "PAYROLL_RUN_NOT_FOUND");
    // The administrator of this tenant cannot reach the other tenant's entity.
    w.c.problem(
        PayrollRunWorld.get(w.admin, PayrollRunWorld.policyPath(other.entity)),
        404,
        "LEGAL_ENTITY_NOT_FOUND");
  }

  @Test
  void aSecondLegalEntityOfTheTenantDoesNotShareRunsOrPolicies() throws Exception {
    UUID second =
        w.moveElsewhere(w.hire("Elsewhere", w.month.atDay(1).minusDays(3)), w.today.minusDays(1));
    w.c.problem(
        PayrollRunWorld.get(w.admin, PayrollRunWorld.runsPath(second) + "/" + runId),
        404,
        "PAYROLL_RUN_NOT_FOUND");
    JsonNode otherPolicy =
        w.c.expect(PayrollRunWorld.get(w.admin, PayrollRunWorld.policyPath(second)), 200);
    assertThat(otherPolicy.get("version").asInt()).isZero();
    JsonNode otherRuns =
        w.c.expect(PayrollRunWorld.get(w.admin, PayrollRunWorld.runsPath(second)), 200);
    assertThat(otherRuns.get("items")).isEmpty();
    // A malformed or random entity is the same safe answer.
    w.c.problem(
        PayrollRunWorld.get(w.admin, PayrollRunWorld.runsPath(UUID.randomUUID())),
        404,
        "LEGAL_ENTITY_NOT_FOUND");
  }

  @Test
  void aCursorIsBoundToItsCallerAndEntity() throws Exception {
    w.paid("Kalala", w.month.atDay(1).minusDays(5), "USD", "900.00");
    w.createRun("USD");
    JsonNode page = w.c.expect(PayrollRunWorld.get(w.admin, w.runsPath() + "?limit=1"), 200);
    String cursor = page.get("nextCursor").asText();
    String colleague = bearer(w.tenant, "sub-pay94-colleague", TestTokens.MFA_ACR, "tenant-admin");
    w.c.problem(
        PayrollRunWorld.get(colleague, w.runsPath() + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    UUID second =
        w.moveElsewhere(w.hire("Elsewhere", w.month.atDay(1).minusDays(3)), w.today.minusDays(1));
    w.c.problem(
        PayrollRunWorld.get(
            w.admin, PayrollRunWorld.runsPath(second) + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
  }
}
