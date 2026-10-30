package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
 * MVP-094B (Issue #179) authority: the eleven statutory operations are tenant-administrator only
 * with an exact MFA assurance; employees, managers, platform administrators and every insufficient
 * {@code acr} are refused before any processing; a foreign tenant's legal entity, run, employee or
 * calculation is one safe 404 (the platform rule packs are shared reference data and stay
 * readable); a cursor is bound to its caller. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.statutory-read=600",
      "divalhr.request-limits.subject.statutory-calculation-preview=600",
      "divalhr.request-limits.subject.statutory-calculation-create=600",
      "divalhr.request-limits.subject.statutory-profile-preview=600",
      "divalhr.request-limits.subject.statutory-profile-write=600",
      "divalhr.request-limits.subject.statutory-activation-write=600",
      "divalhr.request-limits.subject.payroll-run-preview=600",
      "divalhr.request-limits.subject.payroll-run-create=600",
      "divalhr.request-limits.subject.payroll-run-read=600",
      "divalhr.request-limits.subject.payroll-policy-preview=600",
      "divalhr.request-limits.subject.payroll-policy-write=600",
      "divalhr.request-limits.subject.payroll-policy-read=600"
    })
class StatutoryAuthorityIntegrationTest {

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

  private StatutoryWorld w;
  private UUID pack;
  private UUID employee;
  private String run;
  private String calculation;

  /** One operation for a given bearer, with the answer a foreign tenant's administrator gets. */
  private record Operation(
      String name,
      MockHttpServletRequestBuilder request,
      Integer foreignStatus,
      String foreignCode) {}

  @BeforeEach
  void world() throws Exception {
    w = new StatutoryWorld(mvc, jdbc, lookups, clock);
    pack = StatutoryWorld.verifiedPack(jdbc);
    w.activate(pack, w.month);
    employee = w.paidInCdf("Mukendi", StatutoryWorld.GROSS);
    w.standard(employee);
    run = w.cdfRun();
    calculation = w.create(run).get("calculationId").asText();
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

  private static String key() {
    return "key-" + UUID.randomUUID();
  }

  /** The eleven operations, well formed (forged digests) so that a 404 is never a 400. */
  private List<Operation> operations(String bearer, Object entity) throws Exception {
    String activation = StatutoryWorld.activationPath(entity);
    String calculations = StatutoryWorld.calculationsPath(entity, run);
    String profile = StatutoryWorld.profilePath(employee);
    Map<String, Object> profileCommand =
        StatutoryWorld.profileCommand(
            w.month.atDay(1).minusDays(5), "STANDARD_RESIDENT", true, true, "EMPLOYMENT_CONTRACT");
    Map<String, Object> profileCommit = new LinkedHashMap<>(profileCommand);
    profileCommit.put("expectedVersion", 0);
    profileCommit.put("previewDigest", "0".repeat(64));
    profileCommit.put("expiresAt", Instant.now().plusSeconds(600).toString());
    profileCommit.put("confirmed", true);
    Map<String, Object> calculationCommit = new LinkedHashMap<>();
    calculationCommit.put("expectedVersion", 0);
    calculationCommit.put("sourceRunDigest", "0".repeat(64));
    calculationCommit.put("profileDigest", "0".repeat(64));
    calculationCommit.put("rulePackDigest", "0".repeat(64));
    calculationCommit.put("calculationDigest", "0".repeat(64));
    calculationCommit.put("expiresAt", Instant.now().plusSeconds(600).toString());
    calculationCommit.put("confirmed", true);
    List<Operation> calls = new ArrayList<>();
    calls.add(
        new Operation(
            "listStatutoryRulePacks",
            PayrollRunWorld.get(bearer, StatutoryWorld.PACKS),
            200,
            null));
    calls.add(
        new Operation(
            "getStatutoryRulePack",
            PayrollRunWorld.get(bearer, StatutoryWorld.PACKS + "/" + pack),
            200,
            null));
    calls.add(
        new Operation(
            "getStatutoryActivation",
            PayrollRunWorld.get(bearer, activation + "?month=" + w.month),
            404,
            "LEGAL_ENTITY_NOT_FOUND"));
    calls.add(
        new Operation(
            "activateStatutoryRulePack",
            CompensationWorld.json(
                activation + "/activation",
                bearer,
                key(),
                StatutoryWorld.activation(pack, w.month.plusMonths(3), 1)),
            404,
            "LEGAL_ENTITY_NOT_FOUND"));
    calls.add(
        new Operation(
            "getStatutoryProfile",
            PayrollRunWorld.get(bearer, profile),
            404,
            "COMPENSATION_NOT_FOUND"));
    calls.add(
        new Operation(
            "previewStatutoryProfile",
            CompensationWorld.json(profile + "/preview", bearer, null, profileCommand),
            404,
            "COMPENSATION_NOT_FOUND"));
    calls.add(
        new Operation(
            "commitStatutoryProfile",
            CompensationWorld.json(profile, bearer, key(), profileCommit),
            404,
            "COMPENSATION_NOT_FOUND"));
    calls.add(
        new Operation(
            "previewStatutoryCalculation",
            CompensationWorld.json(calculations + "/preview", bearer, null, Map.of()),
            404,
            "LEGAL_ENTITY_NOT_FOUND"));
    calls.add(
        new Operation(
            "commitStatutoryCalculation",
            CompensationWorld.json(calculations, bearer, key(), calculationCommit),
            404,
            "LEGAL_ENTITY_NOT_FOUND"));
    calls.add(
        new Operation(
            "listStatutoryCalculations",
            PayrollRunWorld.get(bearer, calculations),
            404,
            "LEGAL_ENTITY_NOT_FOUND"));
    calls.add(
        new Operation(
            "getStatutoryCalculation",
            PayrollRunWorld.get(bearer, calculations + "/" + calculation),
            404,
            "STATUTORY_CALCULATION_NOT_FOUND"));
    return calls;
  }

  @Test
  void everyOperationNeedsTheExactMfaAssurance() throws Exception {
    List<Integer> before = w.evidence();
    int audits =
        w.c.count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", w.tenant);
    for (Object acr : INSUFFICIENT) {
      String admin = bearer(w.tenant, "sub-stat94-mfa", acr, "tenant-admin");
      for (Operation call : operations(admin, w.entity)) {
        MockHttpServletResponse response = w.c.call(call.request());
        assertThat(response.getStatus()).as(call.name() + " " + acr).isEqualTo(403);
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
        String caller = bearer(w.tenant, "sub-stat94-" + role, acr, role);
        for (Operation call : operations(caller, w.entity)) {
          MockHttpServletResponse response = w.c.call(call.request());
          assertThat(response.getStatus()).as(call.name() + " " + role).isEqualTo(403);
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
  void anAdministratorOfAnotherTenantReachesNothingOfThisOne() throws Exception {
    StatutoryWorld other = new StatutoryWorld(mvc, jdbc, lookups, clock);
    List<Integer> before = w.evidence();
    for (Operation call : operations(other.admin, w.entity)) {
      MockHttpServletResponse response = w.c.call(call.request());
      assertThat(response.getStatus()).as(call.name()).isEqualTo(call.foreignStatus());
      if (call.foreignCode() != null) {
        assertThat(
                CompensationWorld.JSON.readTree(response.getContentAsString()).get("code").asText())
            .as(call.name())
            .isEqualTo(call.foreignCode());
      }
    }
    assertThat(w.evidence()).isEqualTo(before);
    // The foreign run id and calculation id through the caller's own entity do not exist.
    w.c.problem(
        PayrollRunWorld.get(other.admin, other.calculationsPath(run)),
        404,
        "PAYROLL_RUN_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(other.admin, other.calculationsPath(run) + "/" + calculation),
        404,
        "STATUTORY_CALCULATION_NOT_FOUND");
    // This tenant's administrator cannot reach the other tenant's entity either.
    w.c.problem(
        PayrollRunWorld.get(
            w.admin, StatutoryWorld.activationPath(other.entity) + "?month=" + w.month),
        404,
        "LEGAL_ENTITY_NOT_FOUND");
  }

  @Test
  void aSecondLegalEntityOfTheTenantDoesNotShareActivationsOrCalculations() throws Exception {
    UUID second =
        w.r.moveElsewhere(
            w.r.hire("Elsewhere", w.month.atDay(1).minusDays(3)), w.r.today.minusDays(1));
    w.c.problem(
        PayrollRunWorld.get(w.admin, StatutoryWorld.calculationsPath(second, run)),
        404,
        "PAYROLL_RUN_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(
            w.admin, StatutoryWorld.calculationsPath(second, run) + "/" + calculation),
        404,
        "STATUTORY_CALCULATION_NOT_FOUND");
    JsonNode view =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin, StatutoryWorld.activationPath(second) + "?month=" + w.month),
            200);
    assertThat(view.get("version").asInt()).isZero();
    assertThat(view.get("verificationRequired").asBoolean()).isTrue();
  }

  @Test
  void aCursorIsBoundToItsCaller() throws Exception {
    StatutoryWorld.verifiedPack(jdbc);
    StatutoryWorld.verifiedPack(jdbc);
    JsonNode page =
        w.c.expect(PayrollRunWorld.get(w.admin, StatutoryWorld.PACKS + "?limit=1"), 200);
    String cursor = page.get("nextCursor").asText();
    String colleague = bearer(w.tenant, "sub-stat94-colleague", TestTokens.MFA_ACR, "tenant-admin");
    w.c.problem(
        PayrollRunWorld.get(colleague, StatutoryWorld.PACKS + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
  }
}
