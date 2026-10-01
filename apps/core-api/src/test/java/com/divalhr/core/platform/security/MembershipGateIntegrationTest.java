package com.divalhr.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.identity.application.MembershipAuthorityAdapter;
import com.divalhr.core.platform.observability.OperationMetrics;
import com.divalhr.core.platform.tenancy.MembershipAuthority;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * MVP-012A (architect decision on #37, A1, A2, M1-M5, M11 and A12A-1): the membership gate and the
 * effective session. Effective tenant access is the intersection of the verified token and the
 * caller's active membership; the gate runs after subject, role, tenant and MFA, before binding,
 * and every mismatch is an ordinary {@code ACCESS_DENIED}.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import(MembershipGateIntegrationTest.CountingAuthority.class)
class MembershipGateIntegrationTest {

  private static final String BODY_MARKER = "Gate Body Marker Never Read";
  private static final String MALFORMED_JSON = "{\"name\": \"" + BODY_MARKER + "\",";
  private static final String MARKER_ROLE = "divalhr-privileged-mfa";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> TABLES =
      List.of(
          "tenant.legal_entity",
          "platform.idempotency_record",
          "platform.audit_event",
          "platform.outbox_event");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private Counting lookups;
  @Autowired private RequestMappingHandlerMapping mappings;
  @Autowired private DataSource dataSource;

  private UUID tenant;
  private UUID otherTenant;

  @BeforeEach
  void tenants() throws Exception {
    lookups.failing.set(false);
    tenant = Hierarchy.newTenant(mvc);
    otherTenant = Hierarchy.newTenant(mvc);
  }

  @AfterEach
  void restore() {
    lookups.failing.set(false);
  }

  // ------------------------------------------------------------------------------------------
  // Test-only decorator: counts lookups and can simulate a database failure (M5).
  // ------------------------------------------------------------------------------------------

  /** Wraps the real adapter; no production code is replaced or bypassed. */
  static final class Counting implements MembershipAuthority {
    private final MembershipAuthority delegate;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean failing = new AtomicBoolean();

    Counting(MembershipAuthority delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<ActiveMembership> find(String subject) {
      calls.incrementAndGet();
      if (failing.get()) {
        throw new DataAccessResourceFailureException("simulated outage");
      }
      return delegate.find(subject);
    }

    int calls() {
      return calls.get();
    }
  }

  /** Registers {@link Counting} as the primary authority. */
  @TestConfiguration(proxyBeanMethods = false)
  static class CountingAuthority {
    @Bean
    @Primary
    Counting countingMembershipAuthority(MembershipAuthorityAdapter adapter) {
      return new Counting(adapter);
    }
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  private static String token(UUID tokenTenant, String subject, Object acr, String... roles) {
    TestTokens.Builder builder =
        TestTokens.token().tenant(tokenTenant).subject(subject).roles(List.of(roles));
    if (acr != null) {
      builder = builder.acr(acr);
    }
    return "Bearer " + builder.build();
  }

  private static String subject() {
    return "sub-gate-" + UUID.randomUUID();
  }

  private static MockHttpServletRequestBuilder malformedCreate(String bearer) {
    return post("/api/v1/legal-entities")
        .header("Authorization", bearer)
        .header("Idempotency-Key", "short")
        .contentType(MediaType.APPLICATION_JSON)
        .content(MALFORMED_JSON);
  }

  private Map<String, Integer> counts() {
    return TABLES.stream()
        .collect(
            Collectors.toMap(
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

  private static JsonNode withoutCorrelation(MvcResult result) throws Exception {
    JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("correlationId");
    return body;
  }

  // ------------------------------------------------------------------------------------------
  // Ordering and matching (A1, M2)
  // ------------------------------------------------------------------------------------------

  @Test
  void mfaIsCheckedBeforeTheMembershipEvenWithAnInvalidBody() throws Exception {
    String nonMember = subject();
    int before = lookups.calls();
    mvc.perform(malformedCreate(token(tenant, nonMember, TestTokens.PASSWORD_ACR, "tenant-admin")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
    mvc.perform(
            get("/api/v1/legal-entities?limit=abc")
                .header(
                    "Authorization",
                    token(tenant, nonMember, TestTokens.PASSWORD_ACR, "tenant-admin")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
    // A password-level session never reaches the membership lookup.
    assertThat(lookups.calls()).isEqualTo(before);
  }

  @Test
  void everyMembershipMismatchIsAnOrdinaryAccessDeniedBeforeTheBodyIsRead(CapturedOutput output)
      throws Exception {
    String missing = subject();
    String foreign = subject();
    Memberships.grant(otherTenant, foreign, "tenant-admin");
    String wrongRole = subject();
    Memberships.grant(tenant, wrongRole, "employee");
    String employeeTokenAdminMembership = subject();
    Memberships.grant(tenant, employeeTokenAdminMembership, "tenant-admin");

    Map<String, Integer> before = counts();
    double deniedBefore = denied("legal-entity.create");
    List<JsonNode> bodies = new ArrayList<>();
    for (String subject : List.of(missing, foreign, wrongRole)) {
      MvcResult result =
          mvc.perform(malformedCreate(token(tenant, subject, null, "tenant-admin")))
              .andExpect(status().isForbidden())
              .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
              .andExpect(jsonPath("$.params").isEmpty())
              .andReturn();
      bodies.add(withoutCorrelation(result));
    }
    // Token employee with a tenant-admin membership: the token's role is required too.
    MvcResult roleDenial =
        mvc.perform(malformedCreate(token(tenant, employeeTokenAdminMembership, null, "employee")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andReturn();
    bodies.add(withoutCorrelation(roleDenial));
    // All four denials are byte-identical apart from the correlation ID.
    assertThat(Set.copyOf(bodies)).hasSize(1);
    assertThat(denied("legal-entity.create")).isEqualTo(deniedBefore + 4);
    assertThat(counts()).isEqualTo(before);

    String logs = output.getAll();
    assertThat(logs)
        .contains("membership_mismatch")
        .doesNotContain(BODY_MARKER)
        .doesNotContain(missing)
        .doesNotContain(foreign)
        .doesNotContain(wrongRole)
        .doesNotContain(otherTenant.toString());
  }

  @Test
  void anExactMembershipReachesTheHandler() throws Exception {
    String member = subject();
    Memberships.grant(tenant, member, "tenant-admin");
    String bearer = token(tenant, member, null, "tenant-admin");
    mvc.perform(malformedCreate(bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(Hierarchy.list(bearer, "/api/v1/legal-entities")).andExpect(status().isOk());
    mvc.perform(
            Hierarchy.create(
                "/api/v1/legal-entities",
                bearer,
                Organizations.newKey(),
                Hierarchy.legalEntity(Hierarchy.code("gate"), "Gate SA", "2026-01-01", null)))
        .andExpect(status().isCreated());
  }

  @Test
  void aMemberOfAnotherTenantCannotUseAForgedTenantClaim() throws Exception {
    String member = subject();
    Memberships.grant(otherTenant, member, "tenant-admin");
    // The subject's only membership is in the other tenant; a token claiming this tenant fails.
    mvc.perform(
            Hierarchy.list(token(tenant, member, null, "tenant-admin"), "/api/v1/legal-entities"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    // The same subject in its own tenant succeeds.
    mvc.perform(
            Hierarchy.list(
                token(otherTenant, member, null, "tenant-admin"), "/api/v1/legal-entities"))
        .andExpect(status().isOk());
    // A subject belongs to at most one tenant.
    Memberships.grant(tenant, member, "tenant-admin");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM identity.tenant_membership WHERE subject = ?",
                Integer.class,
                member))
        .isEqualTo(1);
  }

  @Test
  void aPlatformAdministratorWithATenantClaimGainsNoTenantAccess() throws Exception {
    String platform = subject();
    int before = lookups.calls();
    mvc.perform(
            Hierarchy.list(
                token(tenant, platform, null, "platform-admin"), "/api/v1/legal-entities"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    // Denied at the role step: no lookup.
    assertThat(lookups.calls()).isEqualTo(before);
  }

  @Test
  void roleLessTenantOperationsAcceptOnlyAMembershipRoleTheTokenAlsoHolds() throws Exception {
    // Test-only role-less handler (support.TenantProbeTestController): no production endpoint.
    String employee = subject();
    Memberships.grant(tenant, employee, "employee");
    String admin = subject();
    Memberships.grant(tenant, admin, "tenant-admin");
    String body = "{\"tenantId\":\"" + tenant + "\"}";
    // Member with the token role: passes the gate.
    mvc.perform(probe(token(tenant, employee, null, "employee"), body)).andExpect(status().isOk());
    mvc.perform(probe(token(tenant, admin, null, "employee", "tenant-admin"), body))
        .andExpect(status().isOk());
    // Membership role not carried by the token: denied.
    mvc.perform(probe(token(tenant, admin, null, "employee"), body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    // No membership, or a membership elsewhere: denied.
    mvc.perform(probe(token(tenant, subject(), null, "employee"), body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(probe(token(otherTenant, employee, null, "employee"), body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    // A platform role is never a tenant membership role.
    mvc.perform(probe(token(tenant, subject(), null, "platform-admin"), body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private static MockHttpServletRequestBuilder probe(String bearer, String body) {
    return post("/test-support/probes")
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  // ------------------------------------------------------------------------------------------
  // Coverage (guardrail: every current and future @TenantScoped handler is gated; platform,
  // public, session (platform-only) and status paths perform zero lookups)
  // ------------------------------------------------------------------------------------------

  @Test
  void everyTenantScopedHandlerIsGatedAndNoOtherHandlerLooksUpMemberships() throws Exception {
    String nonMember = subject();
    String adminWithoutMembership = token(tenant, nonMember, null, "tenant-admin");
    String employeeWithoutMembership = token(tenant, nonMember, null, "employee");
    String platform = token(null, subject(), null, "platform-admin");
    int tenantScoped = 0;
    int others = 0;
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        mappings.getHandlerMethods().entrySet()) {
      HandlerMethod method = entry.getValue();
      RequestMappingInfo info = entry.getKey();
      String path = concretePath(info);
      HttpMethod verb = verb(info);
      if (path == null || verb == null) {
        continue;
      }
      TenantScoped scoped =
          AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), TenantScoped.class);
      boolean platformScoped = method.getMethodAnnotation(PlatformScoped.class) != null;
      if (scoped != null && !platformScoped) {
        tenantScoped++;
        String bearer =
            scoped.role().isEmpty() ? employeeWithoutMembership : adminWithoutMembership;
        int before = lookups.calls();
        mvc.perform(request(verb, path, bearer))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(lookups.calls()).as("%s %s is gated", verb, path).isEqualTo(before + 1);
      } else {
        others++;
        int before = lookups.calls();
        mvc.perform(request(verb, path, platform));
        mvc.perform(request(verb, path, null));
        assertThat(lookups.calls())
            .as("%s %s looks up no membership", verb, path)
            .isEqualTo(before);
      }
    }
    // The 17 MVP-002/MVP-010 tenant-admin handlers plus the test-only role-less probe.
    assertThat(tenantScoped).isGreaterThanOrEqualTo(18);
    assertThat(others).isGreaterThanOrEqualTo(5);
  }

  private static String concretePath(RequestMappingInfo info) {
    var patterns = info.getPathPatternsCondition();
    if (patterns == null) {
      return null;
    }
    String pattern = patterns.getFirstPattern().getPatternString();
    if (pattern.contains("**") || pattern.startsWith("/error")) {
      return null;
    }
    return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
  }

  private static HttpMethod verb(RequestMappingInfo info) {
    var methods = info.getMethodsCondition().getMethods();
    if (methods.isEmpty()) {
      return null;
    }
    return HttpMethod.valueOf(methods.iterator().next().name());
  }

  private static MockHttpServletRequestBuilder request(
      HttpMethod verb, String path, String bearer) {
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(verb, path);
    if (bearer != null) {
      request = request.header("Authorization", bearer);
    }
    if (verb != HttpMethod.GET) {
      request =
          request
              .header("Idempotency-Key", Organizations.newKey())
              .contentType(MediaType.APPLICATION_JSON)
              .content(MALFORMED_JSON);
    }
    return request;
  }

  // ------------------------------------------------------------------------------------------
  // Fail closed (M5)
  // ------------------------------------------------------------------------------------------

  @Test
  void aLookupFailureFailsClosedBeforeTheHandler() throws Exception {
    String member = subject();
    Memberships.grant(tenant, member, "tenant-admin");
    String bearer = token(tenant, member, null, "tenant-admin");
    Map<String, Integer> before = counts();
    lookups.failing.set(true);
    mvc.perform(
            Hierarchy.create(
                "/api/v1/legal-entities",
                bearer,
                Organizations.newKey(),
                Hierarchy.legalEntity(Hierarchy.code("down"), "Down SA", "2026-01-01", null)))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    mvc.perform(get("/api/v1/session").header("Authorization", bearer))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    assertThat(counts()).isEqualTo(before);
    lookups.failing.set(false);
    mvc.perform(Hierarchy.list(bearer, "/api/v1/legal-entities")).andExpect(status().isOk());
  }

  // ------------------------------------------------------------------------------------------
  // Freshness and concurrency (M4)
  // ------------------------------------------------------------------------------------------

  @Test
  void aMembershipTakesEffectAtTheNextRequestWithoutAnyCache() throws Exception {
    String subject = subject();
    String bearer = token(tenant, subject, null, "tenant-admin");
    mvc.perform(Hierarchy.list(bearer, "/api/v1/legal-entities")).andExpect(status().isForbidden());
    Memberships.grant(tenant, subject, "tenant-admin");
    mvc.perform(Hierarchy.list(bearer, "/api/v1/legal-entities")).andExpect(status().isOk());
    // Removal (not a supported operation; simulated here only to prove there is no cache).
    jdbc.update("DELETE FROM identity.tenant_membership WHERE subject = ?", subject);
    mvc.perform(Hierarchy.list(bearer, "/api/v1/legal-entities")).andExpect(status().isForbidden());
  }

  @Test
  void parallelCallersAreDecidedIndependently() throws Exception {
    List<String> members = new ArrayList<>();
    List<String> strangers = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      String member = subject();
      Memberships.grant(i % 2 == 0 ? tenant : otherTenant, member, "tenant-admin");
      members.add(member);
      strangers.add(subject());
    }
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        UUID own = i % 2 == 0 ? tenant : otherTenant;
        String member = members.get(i);
        String stranger = strangers.get(i);
        calls.add(() -> statusOf(token(own, member, null, "tenant-admin")));
        calls.add(() -> statusOf(token(own, stranger, null, "tenant-admin")));
      }
      List<Future<Integer>> results = pool.invokeAll(calls, 60, TimeUnit.SECONDS);
      for (int i = 0; i < results.size(); i++) {
        assertThat(results.get(i).get()).isEqualTo(i % 2 == 0 ? 200 : 403);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private int statusOf(String bearer) throws Exception {
    return mvc.perform(Hierarchy.list(bearer, "/api/v1/legal-entities"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void theGateNeverWaitsForTheTenantAdministrationLock() throws Exception {
    String member = subject();
    Memberships.grant(tenant, member, "tenant-admin");
    try (Connection held = dataSource.getConnection()) {
      held.setAutoCommit(false);
      try (PreparedStatement lock =
          held.prepareStatement(
              "SELECT 1 FROM tenant.organization WHERE id = ? FOR NO KEY UPDATE")) {
        lock.setObject(1, tenant);
        try (ResultSet locked = lock.executeQuery()) {
          assertThat(locked.next()).isTrue();
        }
      }
      long started = System.nanoTime();
      mvc.perform(
              Hierarchy.list(token(tenant, member, null, "tenant-admin"), "/api/v1/legal-entities"))
          .andExpect(status().isOk());
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000);
      held.rollback();
    }
  }

  // ------------------------------------------------------------------------------------------
  // Session (A2, M3, A12A-1)
  // ------------------------------------------------------------------------------------------

  private JsonNode session(String bearer) throws Exception {
    return JSON.readTree(
        mvc.perform(get("/api/v1/session").header("Authorization", bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static List<String> roles(JsonNode session) {
    List<String> roles = new ArrayList<>();
    session.get("roles").forEach(role -> roles.add(role.asText()));
    return roles;
  }

  @Test
  void theSessionReportsOnlyEffectiveTenantRolesWithoutAReason() throws Exception {
    String exact = subject();
    Memberships.grant(tenant, exact, "tenant-admin");
    String employeeMember = subject();
    Memberships.grant(tenant, employeeMember, "employee");
    String foreign = subject();
    Memberships.grant(otherTenant, foreign, "tenant-admin");
    String none = subject();

    // Exact match; internal and marker roles excluded; works without MFA.
    JsonNode match =
        session(
            token(
                tenant,
                exact,
                TestTokens.PASSWORD_ACR,
                "tenant-admin",
                MARKER_ROLE,
                "offline_access",
                "default-roles-divalhr-dev"));
    assertThat(match.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(roles(match)).containsExactly("tenant-admin");
    // Token role without a membership.
    assertThat(roles(session(token(tenant, none, null, "tenant-admin")))).isEmpty();
    // Membership role without the token role.
    assertThat(roles(session(token(tenant, exact, null, "employee")))).isEmpty();
    // Foreign-tenant membership.
    assertThat(roles(session(token(tenant, foreign, null, "tenant-admin")))).isEmpty();
    // Both tenant roles in the token: only the membership's.
    assertThat(roles(session(token(tenant, employeeMember, null, "employee", "tenant-admin"))))
        .containsExactly("employee");
    // The same response shape in every case: no reason field, no subject.
    JsonNode denied = session(token(tenant, none, null, "tenant-admin"));
    assertThat(denied.fieldNames()).toIterable().containsExactlyInAnyOrder("tenantId", "roles");
    assertThat(denied.toString()).doesNotContain(none);
  }

  @Test
  void aPlatformAdministratorWithoutATenantClaimHasASessionWithANullTenant() throws Exception {
    int before = lookups.calls();
    String body =
        mvc.perform(
                get("/api/v1/session")
                    .header("Authorization", token(null, subject(), null, "platform-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenantId").isEmpty())
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0]").value("platform-admin"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    // The contract field is present and explicitly null; no tenant is synthesized.
    assertThat(JSON.readTree(body).has("tenantId")).isTrue();
    assertThat(JSON.readTree(body).get("tenantId").isNull()).isTrue();
    // A platform administrator that also claims a tenant role but no tenant: platform only.
    assertThat(roles(session(token(null, subject(), null, "platform-admin", "tenant-admin"))))
        .containsExactly("platform-admin");
    // A malformed tenant claim is treated like a missing one for a platform administrator.
    String malformed =
        "Bearer "
            + TestTokens.token()
                .tenant(null)
                .claim("tenant_id", "not-a-uuid")
                .subject(subject())
                .roles(List.of("platform-admin"))
                .build();
    assertThat(session(malformed).get("tenantId").isNull()).isTrue();
    // Platform-only tokens never look up a membership.
    assertThat(lookups.calls()).isEqualTo(before);
  }

  @Test
  void aPlatformAdministratorsIncidentalTenantClaimGrantsNoTenantRole() throws Exception {
    String platform = subject();
    // Even a membership for the same subject does not help without the token's tenant role.
    Memberships.grant(tenant, platform, "tenant-admin");
    int before = lookups.calls();
    JsonNode session = session(token(tenant, platform, null, "platform-admin"));
    assertThat(session.get("tenantId").asText()).isEqualTo(tenant.toString());
    assertThat(roles(session)).containsExactly("platform-admin");
    assertThat(lookups.calls()).isEqualTo(before);
    // With the token's tenant role and the matching membership, both roles are effective.
    assertThat(roles(session(token(tenant, platform, null, "platform-admin", "tenant-admin"))))
        .containsExactly("platform-admin", "tenant-admin");
  }

  @Test
  void tenantRolesWithoutAValidTenantStillGetTenantContextMissing() throws Exception {
    for (String bearer :
        List.of(
            token(null, subject(), null, "tenant-admin"),
            token(null, subject(), null, "employee"),
            token(null, subject(), null))) {
      mvc.perform(get("/api/v1/session").header("Authorization", bearer))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    }
  }
}
