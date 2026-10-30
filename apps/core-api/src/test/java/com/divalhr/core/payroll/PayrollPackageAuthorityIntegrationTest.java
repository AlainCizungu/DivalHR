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
import java.util.Set;
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
 * MVP-094C (Issue #181, ADR 0035) authority: the nine administrator operations of payroll approval,
 * void, package and payslip reads need the tenant-administrator role and an exact MFA assurance;
 * employees, managers and platform administrators are refused before any processing; a foreign
 * tenant's administrator reaches nothing of this tenant; the three employee operations serve an
 * employee only their own payslips (a linked employee, never a colleague's, never without a link)
 * and a cursor is bound to its caller. Synthetic data only.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@PayrollPackageWorld.HighLimits
class PayrollPackageAuthorityIntegrationTest {

  private static final String EXPLANATION = "The statutory profile was entered for the wrong month";

  private static final Set<String> NOT_FOUND =
      Set.of(
          "LEGAL_ENTITY_NOT_FOUND",
          "PAYROLL_RUN_NOT_FOUND",
          "STATUTORY_CALCULATION_NOT_FOUND",
          "PAYROLL_PACKAGE_NOT_FOUND",
          "PAYSLIP_NOT_FOUND");

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
  private Chain chain;
  private CompensationWorld.Person ana;
  private CompensationWorld.Person bob;
  private String pkg;
  private String anaSlip;
  private String bobSlip;

  /** One administrator operation for a given bearer. */
  private record Operation(String name, MockHttpServletRequestBuilder request) {}

  @BeforeEach
  void world() throws Exception {
    w = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    UUID pack = StatutoryWorld.verifiedPack(jdbc);
    w.s.activate(pack, w.month);
    ana = w.linkedEmployee("Mukendi", PayrollPackageWorld.GROSS);
    bob = w.linkedEmployee("Kalala", "90000");
    String run = w.s.cdfRun();
    String calculation = w.s.create(run).get("calculationId").asText();
    chain = new Chain(pack, ana.employee(), run, calculation);
    pkg = w.approve(chain).get("packageId").asText();
    anaSlip = w.slipOf(pkg, ana.employee());
    bobSlip = w.slipOf(pkg, bob.employee());
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

  private static Map<String, Object> approvalCommit() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", 0);
    body.put("sourceRunDigest", "0".repeat(64));
    body.put("calculationDigest", "0".repeat(64));
    body.put("profileDigest", "0".repeat(64));
    body.put("rulePackDigest", "0".repeat(64));
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("reviewedTotals", true);
    body.put("reviewedExceptions", true);
    body.put("understandsNoPayment", true);
    return body;
  }

  private static Map<String, Object> voidCommit() {
    Map<String, Object> body = PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", EXPLANATION);
    body.put("packageVersion", 1);
    body.put("approvalDigest", "0".repeat(64));
    body.put("previewDigest", "0".repeat(64));
    body.put("expiresAt", Instant.now().plusSeconds(600).toString());
    body.put("noPaymentAcknowledged", true);
    return body;
  }

  /** The nine administrator operations, well formed so that a 404 is never a 400. */
  private List<Operation> operations(String bearer, Object entity) throws Exception {
    String approval = PayrollPackageWorld.approvalPath(entity, chain.run(), chain.calculation());
    String packages = PayrollPackageWorld.packagesPath(entity);
    String one = packages + "/" + pkg;
    String slips = one + "/payslips";
    List<Operation> calls = new ArrayList<>();
    calls.add(
        new Operation(
            "previewPayrollApproval",
            CompensationWorld.json(approval + "/preview", bearer, null, Map.of())));
    calls.add(
        new Operation(
            "approvePayroll", CompensationWorld.json(approval, bearer, key(), approvalCommit())));
    calls.add(new Operation("listPayrollPackages", PayrollRunWorld.get(bearer, packages)));
    calls.add(new Operation("getPayrollPackage", PayrollRunWorld.get(bearer, one)));
    calls.add(
        new Operation(
            "previewPayrollVoid",
            CompensationWorld.json(
                one + "/void/preview",
                bearer,
                null,
                PayrollPackageWorld.voidCommand("OTHER_CONTROLLED", EXPLANATION))));
    calls.add(
        new Operation(
            "voidPayrollPackage",
            CompensationWorld.json(one + "/void", bearer, key(), voidCommit())));
    calls.add(new Operation("listPackagePayslips", PayrollRunWorld.get(bearer, slips)));
    calls.add(
        new Operation("getPackagePayslip", PayrollRunWorld.get(bearer, slips + "/" + anaSlip)));
    calls.add(
        new Operation(
            "downloadPackagePayslipPdf", PayrollRunWorld.get(bearer,
                slips + "/" + anaSlip + ".pdf")));
    return calls;
  }

  private List<Operation> mine(String bearer, String slip) {
    return List.of(
        new Operation("listMyPayslips", PayrollRunWorld.get(bearer, PayrollPackageWorld.ME)),
        new Operation(
            "getMyPayslip", PayrollRunWorld.get(bearer, PayrollPackageWorld.minePath(slip))),
        new Operation(
            "downloadMyPayslipPdf",
            PayrollRunWorld.get(bearer, PayrollPackageWorld.minePdfPath(slip))));
  }

  @Test
  void everyAdministratorOperationNeedsTheExactMfaAssurance() throws Exception {
    List<Integer> before = w.evidence();
    int audits =
        w.c.count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", w.tenant);
    for (Object acr : INSUFFICIENT) {
      String admin = bearer(w.tenant, "sub-pkg94-mfa", acr, "tenant-admin");
      for (Operation call : operations(admin, w.entity)) {
        MockHttpServletResponse response = w.c.call(call.request());
        assertThat(response.getStatus()).as(call.name() + " " + acr).isEqualTo(403);
        JsonNode problem = CompensationWorld.JSON.readTree(response.getContentAsString());
        assertThat(problem.get("code").asText()).as(call.name()).isEqualTo("MFA_REQUIRED");
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
        String caller = bearer(w.tenant, "sub-pkg94-" + role, acr, role);
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
    // A linked employee reaches nothing of the administrator paths either, not even their own.
    for (Operation call : operations(ana.bearer(), w.entity)) {
      assertThat(w.c.call(call.request()).getStatus()).as(call.name()).isEqualTo(403);
    }
    assertThat(w.evidence()).isEqualTo(before);
  }

  @Test
  void anAdministratorOfAnotherTenantReachesNothingOfThisOne() throws Exception {
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    List<Integer> before = w.evidence();
    for (Operation call : operations(other.admin, w.entity)) {
      MockHttpServletResponse response = w.c.call(call.request());
      assertThat(response.getStatus()).as(call.name()).isEqualTo(404);
      JsonNode problem = CompensationWorld.JSON.readTree(response.getContentAsString());
      assertThat(problem.get("code").asText()).as(call.name()).isIn(NOT_FOUND);
      assertThat(response.getContentAsString()).doesNotContain("149040").doesNotContain("Mukendi");
    }
    // The foreign administrator's own entity does not hold this tenant's package or payslip.
    for (String path :
        List.of(
            other.packagePath(pkg),
            other.slipsPath(pkg),
            other.slipPath(pkg, anaSlip),
            other.slipPdfPath(pkg, anaSlip))) {
      JsonNode problem = w.c.expect(PayrollRunWorld.get(other.admin, path), 404);
      assertThat(problem.get("code").asText()).as(path).isIn(NOT_FOUND);
    }
    JsonNode theirs = w.c.expect(PayrollRunWorld.get(other.admin, other.packagesPath()), 200);
    assertThat(theirs.get("items")).isEmpty();
    // This tenant's administrator cannot reach the other tenant's entity either.
    JsonNode problem =
        w.c.expect(PayrollRunWorld.get(w.admin, PayrollPackageWorld.packagesPath(other.entity)),
            404);
    assertThat(problem.get("code").asText()).isIn(NOT_FOUND);
    assertThat(w.evidence()).isEqualTo(before);
  }

  @Test
  void aSecondLegalEntityOfTheTenantDoesNotShareThePackage() throws Exception {
    UUID second =
        w.r.moveElsewhere(
            w.r.hire("Elsewhere", w.month.atDay(1).minusDays(3)), w.r.today.minusDays(1));
    JsonNode problem =
        w.c.expect(
            PayrollRunWorld.get(
                w.admin, PayrollPackageWorld.packagesPath(second) + "/" + pkg),
            404);
    assertThat(problem.get("code").asText()).isEqualTo("PAYROLL_PACKAGE_NOT_FOUND");
    w.c.problem(
        PayrollRunWorld.get(
            w.admin,
            PayrollPackageWorld.packagesPath(second) + "/" + pkg + "/payslips/" + anaSlip),
        404,
        "PAYSLIP_NOT_FOUND");
    JsonNode listed =
        w.c.expect(PayrollRunWorld.get(w.admin, PayrollPackageWorld.packagesPath(second)), 200);
    assertThat(listed.get("items")).isEmpty();
  }

  @Test
  void aLinkedEmployeeSeesOnlyTheirOwnPayslipsAndNeverAColleaguesOrAnAmountOfAnother()
      throws Exception {
    JsonNode page = w.c.expect(PayrollRunWorld.get(ana.bearer(), PayrollPackageWorld.ME), 200);
    assertThat(page.get("items")).hasSize(1);
    JsonNode item = page.get("items").get(0);
    assertThat(item.get("payslipId").asText()).isEqualTo(anaSlip);
    assertThat(item.get("employeeId").asText()).isEqualTo(ana.employee().toString());
    assertThat(PayrollPackageWorld.amount(item.get("approvedNet"))).isEqualTo("149040");

    JsonNode own =
        w.c.expect(PayrollRunWorld.get(ana.bearer(), PayrollPackageWorld.minePath(anaSlip)), 200);
    assertThat(own.get("snapshot").get("employeeId").asText())
        .isEqualTo(ana.employee().toString());
    assertThat(w.pdf(ana.bearer(), PayrollPackageWorld.minePdfPath(anaSlip))
            .getContentAsByteArray())
        .isNotEmpty();

    int downloads = w.audits("payslip.download");
    int reads = w.audits("payslip.read");
    for (Operation call : mine(bob.bearer(), anaSlip).subList(1, 3)) {
      JsonNode problem = w.c.expect(call.request(), 404);
      assertThat(problem.get("code").asText()).as(call.name()).isEqualTo("PAYSLIP_NOT_FOUND");
      assertThat(problem.toString()).doesNotContain("149040").doesNotContain("Mukendi");
    }
    assertThat(w.audits("payslip.download")).isEqualTo(downloads);
    assertThat(w.audits("payslip.read")).isEqualTo(reads);
    JsonNode theirs = w.c.expect(PayrollRunWorld.get(bob.bearer(), PayrollPackageWorld.ME), 200);
    assertThat(theirs.get("items")).hasSize(1);
    assertThat(theirs.get("items").get(0).get("payslipId").asText()).isEqualTo(bobSlip);
  }

  @Test
  void anEmployeeWithoutAnActiveLinkAndAnEmployeeOfAnotherTenantAreRefused() throws Exception {
    String unlinked = w.c.unlinkedBearer();
    for (Operation call : mine(unlinked, anaSlip)) {
      w.c.problem(call.request(), 403, "EMPLOYEE_LINK_REQUIRED");
    }
    PayrollPackageWorld other = new PayrollPackageWorld(mvc, jdbc, lookups, clock);
    CompensationWorld.Person foreign = other.linkedEmployee("Foreign", "70000");
    for (Operation call : mine(foreign.bearer(), anaSlip).subList(1, 3)) {
      w.c.problem(call.request(), 404, "PAYSLIP_NOT_FOUND");
    }
    JsonNode none = w.c.expect(PayrollRunWorld.get(foreign.bearer(), PayrollPackageWorld.ME), 200);
    assertThat(none.get("items")).isEmpty();
  }

  @Test
  void administratorsAndOtherRolesCannotUseTheEmployeeOperations() throws Exception {
    int reads = w.audits("payslip.read");
    int downloads = w.audits("payslip.download");
    String manager = bearer(w.tenant, "sub-pkg94-manager", TestTokens.MFA_ACR, "manager");
    String platform = bearer(w.tenant, "sub-pkg94-platform", TestTokens.MFA_ACR, "platform-admin");
    for (String caller : List.of(w.admin, manager, platform)) {
      for (Operation call : mine(caller, anaSlip)) {
        assertThat(w.c.call(call.request()).getStatus()).as(call.name()).isEqualTo(403);
      }
    }
    assertThat(w.audits("payslip.read")).isEqualTo(reads);
    assertThat(w.audits("payslip.download")).isEqualTo(downloads);
  }

  @Test
  void aVoidedPackageStaysReadableByItsEmployeeAsVoided() throws Exception {
    w.voidPackage(pkg, "OTHER_CONTROLLED", EXPLANATION);
    JsonNode view =
        w.c.expect(PayrollRunWorld.get(ana.bearer(), PayrollPackageWorld.minePath(anaSlip)), 200);
    assertThat(view.get("status").asText()).isEqualTo("VOIDED");
    JsonNode page = w.c.expect(PayrollRunWorld.get(ana.bearer(), PayrollPackageWorld.ME), 200);
    assertThat(page.get("items").get(0).get("status").asText()).isEqualTo("VOIDED");
    assertThat(view.toString()).doesNotContain(EXPLANATION).doesNotContain("wrong month");
  }

  @Test
  void aCursorIsBoundToItsCaller() throws Exception {
    JsonNode page =
        w.c.expect(PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=1"), 200);
    String cursor = page.get("nextCursor").asText();
    String colleague = bearer(w.tenant, "sub-pkg94-colleague", TestTokens.MFA_ACR, "tenant-admin");
    w.c.problem(
        PayrollRunWorld.get(colleague, w.slipsPath(pkg) + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    // An employee cannot use an administrator cursor on their own list either.
    w.c.problem(
        PayrollRunWorld.get(ana.bearer(), PayrollPackageWorld.ME + "?limit=1&cursor=" + cursor),
        400,
        "CURSOR_INVALID");
    // The cursor works for its own caller.
    w.c.expect(PayrollRunWorld.get(w.admin, w.slipsPath(pkg) + "?limit=1&cursor=" + cursor), 200);
  }
}
