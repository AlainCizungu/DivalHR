package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.payroll.PayrollPackageWorld.Chain;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-095A (Issue #183, ADR 0036) authority: every administrator operation on payment destinations
 * and payment batches needs the tenant-administrator role and an exact MFA assurance; employees,
 * managers and platform administrators are refused before any processing; a foreign tenant's
 * administrator reaches nothing of this tenant; the employee operation serves a linked employee
 * only their own masked destination. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
@PaymentWorld.HighLimits
class PaymentAuthorityIntegrationTest {

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

  private PayrollPackageWorld w;
  private PaymentWorld p;
  private CompensationWorld.Person ana;
  private String pkg;
  private String batch;

  private record Operation(String name, MockHttpServletRequestBuilder request) {}

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    p = new PaymentWorld(w);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    pkg = w.approve(new Chain(pack, ana.employee(), run, calculation)).get("packageId").asText();
    p.record(ana.employee(), p.bank(PaymentWorld.ACCOUNT, p.early(), "INITIAL_SETUP"));
    batch = p.prepare(pkg).get("batchId").asText();
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

  private static Map<String, Object> forgedDestinationCommit(Map<String, Object> command) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", 0);
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("reviewedDetails", true);
    return body;
  }

  private static Map<String, Object> forgedBatchCommit() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", 0);
    body.put("packageVersion", 1);
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("reviewedTotalsAndDestinations", true);
    body.put("noMoneySentAcknowledged", true);
    return body;
  }

  /** The administrator operations, well formed so that a 404 is never a 400. */
  private List<Operation> operations(String bearer, PaymentWorld world, Object employee)
      throws Exception {
    Map<String, Object> command = world.bank(PaymentWorld.ACCOUNT_2, world.early(), "OTHER_CONTROLLED");
    String path = PaymentWorld.destinationPath(employee);
    List<Operation> calls = new ArrayList<>();
    calls.add(new Operation("getDestination", PayrollRunWorld.get(bearer, path)));
    calls.add(new Operation("getDestinationHistory", PayrollRunWorld.get(bearer, path + "/history")));
    calls.add(
        new Operation(
            "previewDestination", CompensationWorld.json(path + "/preview", bearer, null, command)));
    calls.add(
        new Operation(
            "commitDestination",
            CompensationWorld.json(path + "/commit", bearer, key(), forgedDestinationCommit(command))));
    calls.add(new Operation("listBatches", PayrollRunWorld.get(bearer, world.batchesPath())));
    calls.add(new Operation("getBatch", PayrollRunWorld.get(bearer, world.batchPath(batch))));
    calls.add(
        new Operation("getBatchLines", PayrollRunWorld.get(bearer, world.batchPath(batch) + "/lines")));
    calls.add(
        new Operation(
            "previewBatch",
            CompensationWorld.json(world.packageBatchesPath(pkg) + "/preview", bearer, null, Map.of())));
    calls.add(
        new Operation(
            "prepareBatch",
            CompensationWorld.json(world.packageBatchesPath(pkg), bearer, key(), forgedBatchCommit())));
    calls.add(
        new Operation(
            "previewCancel",
            CompensationWorld.json(
                world.batchPath(batch) + "/cancel/preview",
                bearer,
                null,
                PaymentWorld.cancelCommand("PREPARED_IN_ERROR"))));
    calls.add(
        new Operation(
            "downloadCsv", PayrollRunWorld.get(bearer, world.batchPath(batch) + "/register.csv")));
    calls.add(
        new Operation(
            "downloadManifest",
            PayrollRunWorld.get(bearer, world.batchPath(batch) + "/manifest.json")));
    return calls;
  }

  @Test
  void everyAdministratorOperationNeedsTheExactMfaAssurance() throws Exception {
    List<Integer> before = p.evidence();
    for (Object acr : INSUFFICIENT) {
      String admin = bearer(w.tenant, "sub-pay95-mfa", acr, "tenant-admin");
      for (Operation call : operations(admin, p, ana.employee())) {
        MockHttpServletResponse response = w.c.call(call.request());
        assertThat(response.getStatus()).as(call.name() + " " + acr).isEqualTo(403);
        JsonNode problem = CompensationWorld.JSON.readTree(response.getContentAsString());
        assertThat(problem.get("code").asText()).as(call.name()).isEqualTo("MFA_REQUIRED");
        assertThat(response.getHeader("WWW-Authenticate"))
            .contains("insufficient_user_authentication");
      }
    }
    assertThat(p.evidence()).isEqualTo(before);
  }

  @Test
  void employeesManagersAndPlatformAdministratorsAreDeniedByRole() throws Exception {
    List<Integer> before = p.evidence();
    for (String role : List.of("employee", "manager", "platform-admin")) {
      for (Object acr : List.of(TestTokens.PASSWORD_ACR, TestTokens.MFA_ACR)) {
        String caller = bearer(w.tenant, "sub-pay95-" + role, acr, role);
        for (Operation call : operations(caller, p, ana.employee())) {
          MockHttpServletResponse response = w.c.call(call.request());
          assertThat(response.getStatus()).as(call.name() + " " + role).isEqualTo(403);
          assertThat(
                  CompensationWorld.JSON
                      .readTree(response.getContentAsString())
                      .get("code")
                      .asText())
              .isEqualTo("ACCESS_DENIED");
        }
      }
    }
    // A linked employee reaches nothing of the administrator paths, not even their own.
    for (Operation call : operations(ana.bearer(), p, ana.employee())) {
      assertThat(w.c.call(call.request()).getStatus()).as(call.name()).isEqualTo(403);
    }
    assertThat(p.evidence()).isEqualTo(before);
  }

  @Test
  void anAdministratorOfAnotherTenantReachesNothingOfThisOne() throws Exception {
    PayrollPackageWorld otherWorld = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    PaymentWorld other = new PaymentWorld(otherWorld);
    List<Integer> before = p.evidence();
    // This tenant's ids through the foreign administrator's own entity and tenant.
    List<Operation> calls = new ArrayList<>();
    calls.add(
        new Operation(
            "foreignDestination",
            PayrollRunWorld.get(otherWorld.admin, PaymentWorld.destinationPath(ana.employee()))));
    calls.add(
        new Operation(
            "foreignHistory",
            PayrollRunWorld.get(
                otherWorld.admin, PaymentWorld.destinationPath(ana.employee()) + "/history")));
    calls.add(
        new Operation(
            "foreignBatch", PayrollRunWorld.get(otherWorld.admin, other.batchPath(batch))));
    calls.add(
        new Operation(
            "foreignLines",
            PayrollRunWorld.get(otherWorld.admin, other.batchPath(batch) + "/lines")));
    calls.add(
        new Operation(
            "foreignCsv",
            PayrollRunWorld.get(otherWorld.admin, other.batchPath(batch) + "/register.csv")));
    calls.add(
        new Operation(
            "foreignManifest",
            PayrollRunWorld.get(otherWorld.admin, other.batchPath(batch) + "/manifest.json")));
    calls.add(
        new Operation(
            "foreignPreview",
            CompensationWorld.json(
                other.packageBatchesPath(pkg) + "/preview", otherWorld.admin, null, Map.of())));
    calls.add(
        new Operation(
            "foreignCancelPreview",
            CompensationWorld.json(
                other.batchPath(batch) + "/cancel/preview",
                otherWorld.admin,
                null,
                PaymentWorld.cancelCommand("PREPARED_IN_ERROR"))));
    for (Operation call : calls) {
      MockHttpServletResponse response = w.c.call(call.request());
      assertThat(response.getStatus()).as(call.name()).isEqualTo(404);
      assertThat(response.getContentAsString())
          .as(call.name())
          .doesNotContain("7766")
          .doesNotContain(PaymentWorld.ACCOUNT)
          .doesNotContain("Mukendi");
    }
    // The foreign administrator sees an empty list of their own, and this tenant's administrator
    // cannot reach the other tenant's entity either.
    assertThat(
            w.c.expect(PayrollRunWorld.get(otherWorld.admin, other.batchesPath()), 200)
                .get("items"))
        .isEmpty();
    w.c.expect(PayrollRunWorld.get(w.admin, other.batchesPath()), 404);
    assertThat(p.evidence()).isEqualTo(before);
  }

  @Test
  void aLinkedEmployeeReadsOnlyTheirOwnMaskedDestinationAndOthersAreRefused() throws Exception {
    JsonNode own = w.c.expect(PayrollRunWorld.get(ana.bearer(), PaymentWorld.MINE), 200);
    assertThat(own.toString()).contains("****7766").doesNotContain(PaymentWorld.ACCOUNT);

    for (String caller :
        List.of(
            w.admin,
            bearer(w.tenant, "sub-pay95-manager", TestTokens.MFA_ACR, "manager"),
            bearer(w.tenant, "sub-pay95-platform", TestTokens.MFA_ACR, "platform-admin"))) {
      assertThat(w.c.call(PayrollRunWorld.get(caller, PaymentWorld.MINE)).getStatus())
          .isEqualTo(403);
    }
    w.c.problem(
        PayrollRunWorld.get(w.c.unlinkedBearer(), PaymentWorld.MINE),
        403,
        "EMPLOYEE_LINK_REQUIRED");

    CompensationWorld.Person bob = w.linkedEmployee("Kalala", "90000");
    MockHttpServletResponse bobRead = w.c.call(PayrollRunWorld.get(bob.bearer(), PaymentWorld.MINE));
    // Bob has no destination: never Ana's.
    assertThat(bobRead.getContentAsString()).doesNotContain("7766");

    PayrollPackageWorld otherWorld = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    CompensationWorld.Person foreign = otherWorld.linkedEmployee("Foreign", "70000");
    assertThat(
            w.c.call(PayrollRunWorld.get(foreign.bearer(), PaymentWorld.MINE)).getContentAsString())
        .doesNotContain("7766");
  }
}
