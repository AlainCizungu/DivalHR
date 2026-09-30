package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Invitations;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-011: privileged operations require a verified {@code acr} exactly equal to {@code
 * urn:divalhr:loa:mfa}. Anything else gets the same safe 403 {@code MFA_REQUIRED} with an RFC 9470
 * challenge, after subject, role and tenant checks and before argument, query or body processing.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MfaAssuranceIntegrationTest {

  private static final String CHALLENGE =
      "Bearer error=\"insufficient_user_authentication\", acr_values=\"urn:divalhr:loa:mfa\"";
  private static final String BODY_MARKER = "Mfa Body Marker Never Logged";
  private static final String MALFORMED_JSON = "{\"name\": \"" + BODY_MARKER + "\",";
  private static final String MARKER_ROLE = "divalhr-privileged-mfa";
  private static final List<String> TABLES =
      List.of(
          "tenant.organization",
          "tenant.legal_entity",
          "tenant.site",
          "identity.invitation",
          "platform.idempotency_record",
          "platform.audit_event",
          "platform.outbox_event");

  /** Every {@code acr} shape that must not count as MFA; {@code null} omits the claim. */
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
  @Autowired private MeterRegistry meters;

  private UUID tenant;

  @BeforeEach
  void newTenant() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
  }

  private String bearer(String subject, Object acr, String... roles) {
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(List.of(roles)).acr(acr).build();
  }

  private static MockHttpServletRequestBuilder malformedPost(String path, String bearer) {
    return post(path)
        .header("Authorization", bearer)
        .header("Idempotency-Key", "short")
        .contentType(MediaType.APPLICATION_JSON)
        .content(MALFORMED_JSON);
  }

  private static JsonNode expectMfaRequired(ResultActions result) throws Exception {
    String body =
        result
            .andExpect(status().isForbidden())
            .andExpect(header().string("WWW-Authenticate", CHALLENGE))
            .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
            .andExpect(jsonPath("$.params").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode problem = Hierarchy.json(body);
    // The route template and correlation ID vary by request; everything else must be identical.
    ((ObjectNode) problem).remove(List.of("instance", "correlationId"));
    return problem;
  }

  private Map<String, Integer> counts() {
    return TABLES.stream()
        .collect(
            Collectors.toMap(
                Function.identity(),
                table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)));
  }

  private double outcome(String operation, String outcome) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", operation, "outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void privilegedRequestsWithoutExactMfaAcrAreRejectedBeforeAnyProcessing(CapturedOutput output)
      throws Exception {
    Map<String, Integer> before = counts();
    List<JsonNode> problems = new ArrayList<>();
    List<String> bearers = new ArrayList<>();
    for (Object acr : INSUFFICIENT) {
      String platform = bearer("sub-mfa-platform", acr, "platform-admin");
      String admin = bearer("sub-mfa-tenant", acr, "tenant-admin");
      bearers.add(platform);
      bearers.add(admin);

      double orgBefore = outcome("organization.create", "mfa_required");
      problems.add(
          expectMfaRequired(mvc.perform(malformedPost("/api/v1/organizations", platform))));
      // A valid body is refused the same way and nothing is written.
      problems.add(
          expectMfaRequired(
              mvc.perform(
                  post("/api/v1/organizations")
                      .header("Authorization", platform)
                      .header("Idempotency-Key", Organizations.newKey())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(Organizations.body(Organizations.uniqueName())))));
      assertThat(outcome("organization.create", "mfa_required")).isEqualTo(orgBefore + 2);

      double leBefore = outcome("legal-entity.create", "mfa_required");
      problems.add(expectMfaRequired(mvc.perform(malformedPost("/api/v1/legal-entities", admin))));
      problems.add(
          expectMfaRequired(
              mvc.perform(
                  Hierarchy.create(
                      "/api/v1/legal-entities",
                      admin,
                      Organizations.newKey(),
                      Hierarchy.legalEntity(
                          Hierarchy.code("MFA"), "Refusée", "2026-01-01", null)))));
      assertThat(outcome("legal-entity.create", "mfa_required")).isEqualTo(leBefore + 2);
      // Query validation is not reached either.
      problems.add(
          expectMfaRequired(
              mvc.perform(
                  get("/api/v1/legal-entities?limit=abc&cursor=!!")
                      .header("Authorization", admin))));
      problems.add(
          expectMfaRequired(
              mvc.perform(
                  get("/api/v1/sites?legalEntityId=not-a-uuid").header("Authorization", admin))));
      problems.add(expectMfaRequired(mvc.perform(Invitations.list(admin, "status=NOT_A_STATUS"))));
      problems.add(
          expectMfaRequired(
              mvc.perform(
                  post("/api/v1/invitations/not-a-uuid/revoke").header("Authorization", admin))));
    }
    // Platform and tenant denials are indistinguishable apart from the route.
    assertThat(problems).allSatisfy(problem -> assertThat(problem).isEqualTo(problems.get(0)));
    assertThat(counts()).isEqualTo(before);
    assertSafeLogs(output, bearers, List.of("sub-mfa-platform", "sub-mfa-tenant"));
  }

  @Test
  void theMarkerRoleAndAmrNeverStandInForTheAcr(CapturedOutput output) throws Exception {
    String subject = "sub-mfa-marker";
    String marked =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(subject)
                .roles(List.of("tenant-admin", MARKER_ROLE))
                .acr(TestTokens.PASSWORD_ACR)
                .claim("amr", List.of("pwd", "otp"))
                .build();
    expectMfaRequired(mvc.perform(Hierarchy.list(marked, "/api/v1/legal-entities")));
    String platform =
        "Bearer "
            + TestTokens.token()
                .subject(subject)
                .tenant(null)
                .roles(List.of("platform-admin", MARKER_ROLE))
                .acr(TestTokens.PASSWORD_ACR)
                .claim("amr", List.of("otp"))
                .build();
    expectMfaRequired(mvc.perform(malformedPost("/api/v1/organizations", platform)));
    // The marker role alone is not a role for anything (an ordinary role denial).
    mvc.perform(
            Hierarchy.list(
                bearer("marker-only-caller", TestTokens.MFA_ACR, MARKER_ROLE),
                "/api/v1/legal-entities"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    assertSafeLogs(output, List.of(marked, platform), List.of(subject));
  }

  @Test
  void roleAndTenantChecksStillComeFirst() throws Exception {
    // An employee is denied by role whatever its acr; no step-up challenge is offered.
    for (Object acr : List.of(TestTokens.PASSWORD_ACR, TestTokens.MFA_ACR)) {
      String employee = bearer("sub-mfa-employee", acr, "employee");
      mvc.perform(malformedPost("/api/v1/legal-entities", employee))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist("WWW-Authenticate"))
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
      mvc.perform(malformedPost("/api/v1/organizations", employee))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    // A password-level platform administrator on a tenant-admin operation: role first.
    mvc.perform(
            Hierarchy.list(
                bearer("sub-mfa-wrong-role", TestTokens.PASSWORD_ACR, "platform-admin"),
                "/api/v1/legal-entities"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    // A password-level tenant administrator without a tenant claim: tenant before assurance.
    String noTenant =
        "Bearer "
            + TestTokens.token()
                .tenant(null)
                .subject("sub-mfa-no-tenant")
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    mvc.perform(malformedPost("/api/v1/legal-entities", noTenant))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("WWW-Authenticate"))
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    // A missing subject is still denied before anything else.
    mvc.perform(
            malformedPost(
                "/api/v1/legal-entities", bearer(null, TestTokens.PASSWORD_ACR, "tenant-admin")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void theExactMfaAcrIsAccepted() throws Exception {
    String admin = bearer("sub-mfa-ok", TestTokens.MFA_ACR, "tenant-admin");
    mvc.perform(Hierarchy.list(admin, "/api/v1/legal-entities")).andExpect(status().isOk());
    mvc.perform(malformedPost("/api/v1/legal-entities", admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            post("/api/v1/organizations")
                .header(
                    "Authorization", bearer("sub-mfa-ok-p", TestTokens.MFA_ACR, "platform-admin"))
                .header("Idempotency-Key", Organizations.newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(Organizations.body(Organizations.uniqueName())))
        .andExpect(status().isCreated());
  }

  @Test
  void theSessionEndpointNeedsNoMfaAndNeverShowsAssurance() throws Exception {
    for (Object acr : List.of(TestTokens.PASSWORD_ACR, TestTokens.MFA_ACR)) {
      String body =
          mvc.perform(
                  get("/api/v1/session")
                      .header(
                          "Authorization",
                          bearer("sub-mfa-session", acr, "tenant-admin", MARKER_ROLE)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.roles[0]").value("tenant-admin"))
              .andExpect(jsonPath("$.roles.length()").value(1))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body)
          .doesNotContain(MARKER_ROLE)
          .doesNotContain(AssuranceEvidence.MFA_AUTHORITY)
          .doesNotContain("urn:divalhr");
    }
  }

  @Test
  void nonPrivilegedAndPublicEndpointsAreUnaffected() throws Exception {
    mvc.perform(get("/api/v1/system/status")).andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/session")
                .header(
                    "Authorization", bearer("sub-mfa-emp", TestTokens.PASSWORD_ACR, "employee")))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/public/invitations/inspect")
                .header(
                    "Authorization", bearer("sub-mfa-pub", TestTokens.PASSWORD_ACR, "tenant-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + "A".repeat(43) + "\"}"))
        .andExpect(status().is4xxClientError())
        .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("MFA_REQUIRED")));
  }

  private static void assertSafeLogs(
      CapturedOutput output, List<String> bearers, List<String> subjects) {
    String logs = output.getAll();
    assertThat(logs)
        .contains("\"reason\":\"mfa_required\"")
        .doesNotContain(BODY_MARKER)
        .doesNotContain("urn:divalhr:loa")
        .doesNotContain("has already been written");
    for (String subject : subjects) {
      assertThat(logs).doesNotContain(subject);
    }
    for (String bearer : bearers) {
      assertThat(logs).doesNotContain(bearer.substring("Bearer ".length()));
    }
  }
}
