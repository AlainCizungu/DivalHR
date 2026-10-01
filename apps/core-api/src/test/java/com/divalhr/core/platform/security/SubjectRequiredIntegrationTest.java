package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
 * Issue #17: for every scoped handler, a caller without a verified, non-blank JWT {@code sub} is
 * denied with 403 {@code ACCESS_DENIED} before role, tenant, query, argument or body processing.
 * Public endpoints and the precedence for callers with a subject are unchanged.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SubjectRequiredIntegrationTest {

  private static final String SECRET_BODY_MARKER = "Body Marker Never Logged";
  private static final String MALFORMED_JSON = "{\"name\": \"" + SECRET_BODY_MARKER + "\",";
  private static final List<String> TABLES =
      List.of(
          "tenant.organization",
          "tenant.legal_entity",
          "tenant.site",
          "platform.idempotency_record",
          "platform.audit_event",
          "platform.outbox_event");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private UUID tenant;

  @BeforeEach
  void newTenant() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
  }

  /** Missing subject and blank or whitespace-only subjects. */
  private List<String> subjectlessTokens(String... roles) {
    return Arrays.asList(null, "", "   ", "\t").stream()
        .map(subject -> Hierarchy.bearer(tenant, subject, roles))
        .toList();
  }

  private static MockHttpServletRequestBuilder postJson(String path, String bearer, String key) {
    MockHttpServletRequestBuilder request =
        post(path)
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(MALFORMED_JSON);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private static void expectSubjectDenial(ResultActions result) throws Exception {
    result
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        .andExpect(jsonPath("$.params").isEmpty());
  }

  private Map<String, Integer> counts() {
    return TABLES.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                Function.identity(),
                table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)));
  }

  private double denied(String operation) {
    var counter =
        meters
            .find(OperationMetrics.METRIC)
            .tags("operation", operation, "outcome", "denied")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void platformScopedPostDeniesSubjectlessTokensBeforeParsingTheBody(CapturedOutput output)
      throws Exception {
    Map<String, Integer> before = counts();
    for (String bearer : subjectlessTokens("platform-admin")) {
      double deniedBefore = denied("organization.create");
      expectSubjectDenial(
          mvc.perform(postJson("/api/v1/organizations", bearer, Organizations.newKey())));
      assertThat(denied("organization.create")).isEqualTo(deniedBefore + 1);
      // A well-formed body is refused the same way and writes nothing.
      expectSubjectDenial(
          mvc.perform(
              post("/api/v1/organizations")
                  .header("Authorization", bearer)
                  .header("Idempotency-Key", Organizations.newKey())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(Organizations.body(Organizations.uniqueName()))));
    }
    assertThat(counts()).isEqualTo(before);
    assertSafeLogs(output, subjectlessTokens("platform-admin"));
  }

  @Test
  void tenantScopedPostsDenySubjectlessTokensBeforeBodyAndKeyValidation(CapturedOutput output)
      throws Exception {
    Map<String, Integer> before = counts();
    for (String path : List.of("/api/v1/legal-entities", "/api/v1/sites")) {
      String operation = path.endsWith("sites") ? "site.create" : "legal-entity.create";
      for (String bearer : subjectlessTokens("tenant-admin")) {
        double deniedBefore = denied(operation);
        expectSubjectDenial(mvc.perform(postJson(path, bearer, "short")));
        expectSubjectDenial(mvc.perform(postJson(path, bearer, null)));
        assertThat(denied(operation)).as(path).isEqualTo(deniedBefore + 2);
      }
    }
    assertThat(counts()).isEqualTo(before);
    assertSafeLogs(output, subjectlessTokens("tenant-admin"));
  }

  @Test
  void tenantScopedGetsDenySubjectlessTokensBeforeQueryValidation() throws Exception {
    for (String bearer : subjectlessTokens("tenant-admin")) {
      expectSubjectDenial(
          mvc.perform(
              get("/api/v1/legal-entities?limit=abc&cursor=!!").header("Authorization", bearer)));
      expectSubjectDenial(
          mvc.perform(
              get("/api/v1/sites?legalEntityId=not-a-uuid&limit=0")
                  .header("Authorization", bearer)));
    }
  }

  @Test
  void nonJwtAuthenticationFollowsTheSameDenial(CapturedOutput output) throws Exception {
    expectSubjectDenial(
        mvc.perform(
            get("/api/v1/legal-entities?limit=abc").with(user("not-a-jwt").roles("tenant-admin"))));
    assertThat(output.getAll())
        .contains("subject_missing")
        .doesNotContain("not-a-jwt")
        .doesNotContain("has already been written");
  }

  @Test
  void callersWithASubjectKeepTheExistingPrecedence(CapturedOutput output) throws Exception {
    String admin = Hierarchy.bearer(tenant, "sub-precedence-" + tenant, "tenant-admin");
    // Role before body: an employee still gets ACCESS_DENIED for a malformed body.
    expectSubjectDenial(
        mvc.perform(
            postJson(
                "/api/v1/legal-entities",
                Hierarchy.bearer(tenant, "sub-employee", "employee"),
                Organizations.newKey())));
    // The role denial is actually written (one JSON line, no duplicate correlationId key).
    assertThat(output.getAll())
        .contains("\"requiredRole\":\"tenant-admin\"")
        .doesNotContain("has already been written");
    // Tenant before body: a tenant-admin token without a tenant claim.
    mvc.perform(
            postJson(
                "/api/v1/legal-entities",
                Hierarchy.bearer(null, "sub-no-tenant", "tenant-admin"),
                Organizations.newKey()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    // With subject, role and tenant in place, request validation answers as before.
    mvc.perform(postJson("/api/v1/legal-entities", admin, Organizations.newKey()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(
            post("/api/v1/legal-entities")
                .header("Authorization", admin)
                .header("Idempotency-Key", "short")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Hierarchy.legalEntity("OK-01", "Valide", "2026-01-01", null)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Idempotency-Key"));
    mvc.perform(get("/api/v1/legal-entities?limit=abc").header("Authorization", admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("limit"));
  }

  @Test
  void publicEndpointsAreUnchanged() throws Exception {
    mvc.perform(get("/api/v1/system/status")).andExpect(status().isOk());
  }

  private static void assertSafeLogs(CapturedOutput output, List<String> bearers) {
    String logs = output.getAll();
    assertThat(logs).contains("subject_missing").doesNotContain(SECRET_BODY_MARKER);
    for (String bearer : bearers) {
      assertThat(logs).doesNotContain(bearer.substring("Bearer ".length()));
    }
  }
}
