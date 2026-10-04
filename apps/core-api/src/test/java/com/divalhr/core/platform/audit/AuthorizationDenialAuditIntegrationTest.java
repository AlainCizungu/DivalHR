package com.divalhr.core.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.platform.audit.AuthorizationDenial.Scope;
import com.divalhr.core.platform.audit.AuthorizationDenial.Stage;
import com.divalhr.core.platform.error.GlobalExceptionHandler;
import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.security.TenantScoped;
import com.divalhr.core.platform.web.CorrelationId;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.divalhr.probe.DenialProbeTestController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * MVP-013 (Issue #43, D1-D11, A13-1..A13-6): durable, append-only evidence of privileged
 * authorization denials, with unchanged public responses.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import(DenialProbeTestController.class)
class AuthorizationDenialAuditIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BODY_MARKER = "Denial Body Marker Never Read";
  private static final String QUERY_MARKER = "denialquerymarker";
  private static final String MALFORMED_JSON = "{\"name\": \"" + BODY_MARKER + "\",";
  private static final String STEP_UP =
      "Bearer error=\"insufficient_user_authentication\", acr_values=\"urn:divalhr:loa:mfa\"";

  /** Every privileged production operation on {@code main} (MVP-013 inventory). */
  private static final Set<String> PLATFORM_OPERATIONS =
      Set.of(
          "organization.create",
          "tenant-admin-bootstrap.read",
          "tenant-admin-bootstrap.create",
          "tenant-admin-bootstrap.revoke",
          "tenant-admin-bootstrap.resend");

  private static final Set<String> TENANT_OPERATIONS =
      Set.of(
          "legal-entity.list",
          "legal-entity.create",
          "region.list",
          "region.create",
          "site.list",
          "site.create",
          "site.region.assign",
          "department.list",
          "department.create",
          "cost-center.list",
          "cost-center.create",
          "team.list",
          "team.create",
          "invitation.list",
          "invitation.create",
          "invitation.revoke",
          "invitation.resend",
          "access-review.list",
          "access-review.lookup",
          "access-review.summary",
          "employee-import.template",
          "employee-import.create",
          "employee-import.read",
          "employee-import.rows",
          "employee-import.commit",
          "employee-import.discard",
          "employee.list",
          "employee.search",
          "employee.read",
          "employee.timeline",
          "employment-change.list",
          "employment-change.preview",
          "employment-change.create",
          "employment-change.cancel-preview",
          "employment-change.cancel",
          "employee-access-link.read",
          "employee-access-link.lookup",
          "employee-access-link.create",
          "employee-access-link.remove",
          "employee-separation.read",
          "employee-separation.preview",
          "employee-separation.create",
          "employee-separation.cancel-preview",
          "employee-separation.cancel",
          "separation-task.update",
          "access-revocation.retry");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private DenialAuditStore store;
  @Autowired private AuthorizationDenialAudit audit;
  @Autowired private AuditRecorder auditRecorder;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private DataSource dataSource;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  private UUID tenant;
  private UUID otherTenant;

  @BeforeEach
  void tenants() throws Exception {
    tenant = Hierarchy.newTenant(mvc);
    otherTenant = Hierarchy.newTenant(mvc);
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  private static String subject(String prefix) {
    return "sub-denial-" + prefix + "-" + UUID.randomUUID();
  }

  private static String correlation() {
    return "mvp013-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  /** A token without granting any membership. */
  private static String token(UUID tokenTenant, String subject, Object acr, String... roles) {
    TestTokens.Builder builder =
        TestTokens.token().tenant(tokenTenant).subject(subject).roles(List.of(roles));
    if (acr != null) {
      builder = builder.acr(acr);
    }
    return "Bearer " + builder.build();
  }

  private MvcResult perform(MockHttpServletRequestBuilder request, String correlationId)
      throws Exception {
    return mvc.perform(request.header(CorrelationId.HEADER, correlationId)).andReturn();
  }

  private List<Map<String, Object>> rows(String correlationId) {
    return jdbc.queryForList(
        "SELECT * FROM platform.authorization_denial WHERE correlation_id = ?", correlationId);
  }

  private Integer rowsOf(String actor) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM platform.authorization_denial WHERE actor_subject = ?",
        Integer.class,
        actor);
  }

  private double outcome(String outcome, String stage, String scope) {
    Counter counter =
        meters
            .find(AuthorizationDenialAudit.METRIC)
            .tags("outcome", outcome, "stage", stage, "scope", scope)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private double drift() {
    return meters.find(GlobalExceptionHandler.DRIFT_METRIC).counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private static JsonNode withoutCorrelation(MvcResult result) throws Exception {
    JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
    ((ObjectNode) body).remove("correlationId");
    return body;
  }

  /** Asserts exactly one row for the request and returns it. */
  private Map<String, Object> single(
      String correlationId,
      String actor,
      String operation,
      Scope scope,
      Stage stage,
      UUID expectedTenant) {
    List<Map<String, Object>> found = rows(correlationId);
    assertThat(found).as("rows for %s", correlationId).hasSize(1);
    Map<String, Object> row = found.getFirst();
    assertThat(row.get("actor_subject")).isEqualTo(actor);
    assertThat(row.get("action")).isEqualTo("authorization.denied");
    assertThat(row.get("operation")).isEqualTo(operation);
    assertThat(row.get("scope")).isEqualTo(scope.value());
    assertThat(row.get("stage")).isEqualTo(stage.value());
    assertThat(row.get("tenant_id")).isEqualTo(expectedTenant);
    assertThat(row.keySet())
        .containsExactlyInAnyOrder(
            "id",
            "occurred_at",
            "actor_subject",
            "action",
            "operation",
            "scope",
            "stage",
            "tenant_id",
            "correlation_id");
    return row;
  }

  private static MockHttpServletRequestBuilder malformedCreate(String path, String bearer) {
    return post(path)
        .header("Authorization", bearer)
        .header("Idempotency-Key", "short")
        .contentType(MediaType.APPLICATION_JSON)
        .content(MALFORMED_JSON);
  }

  // ------------------------------------------------------------------------------------------
  // Stages, tenant rule and unchanged responses (D2, D8, D10, D11)
  // ------------------------------------------------------------------------------------------

  @Test
  void eachDurableStageWritesOneRowWithTheEffectiveTenantRule(CapturedOutput output)
      throws Exception {
    String employee = subject("employee");
    Memberships.grant(tenant, employee, "employee");
    String noTenant = subject("no-tenant");
    String badTenant = subject("bad-tenant");
    String passwordAdmin = subject("password");
    Memberships.grant(tenant, passwordAdmin, "tenant-admin");
    String foreign = subject("foreign");
    Memberships.grant(otherTenant, foreign, "tenant-admin");
    String tenantAdminOnPlatform = subject("tenant-on-platform");
    String passwordPlatform = subject("password-platform");

    // Stage 2, tenant scope: role missing.
    String c1 = correlation();
    MvcResult role =
        perform(
            malformedCreate("/api/v1/legal-entities", token(tenant, employee, null, "employee")),
            c1);
    assertThat(role.getResponse().getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(role.getResponse().getContentAsString()).get("code").asText())
        .isEqualTo("ACCESS_DENIED");
    single(c1, employee, "legal-entity.create", Scope.TENANT, Stage.ROLE, null);

    // Stage 3: tenant-admin role but no tenant, or an invalid one. Response unchanged (D10).
    String c2 = correlation();
    mvc.perform(
            malformedCreate("/api/v1/legal-entities", token(null, noTenant, null, "tenant-admin"))
                .header(CorrelationId.HEADER, c2))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    single(c2, noTenant, "legal-entity.create", Scope.TENANT, Stage.TENANT_CONTEXT, null);
    String c3 = correlation();
    String invalidTenant =
        "Bearer "
            + TestTokens.token()
                .subject(badTenant)
                .roles(List.of("tenant-admin"))
                .claim("tenant_id", "not-a-tenant")
                .build();
    mvc.perform(
            get("/api/v1/legal-entities")
                .header("Authorization", invalidTenant)
                .header(CorrelationId.HEADER, c3))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TENANT_CONTEXT_MISSING"));
    single(c3, badTenant, "legal-entity.list", Scope.TENANT, Stage.TENANT_CONTEXT, null);

    // Stage 4: password-level session; the RFC 9470 challenge is unchanged.
    String c4 = correlation();
    mvc.perform(
            malformedCreate(
                    "/api/v1/legal-entities",
                    token(tenant, passwordAdmin, TestTokens.PASSWORD_ACR, "tenant-admin"))
                .header(CorrelationId.HEADER, c4))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
        .andExpect(header().string("WWW-Authenticate", STEP_UP));
    single(c4, passwordAdmin, "legal-entity.create", Scope.TENANT, Stage.MFA, null);

    // Stage 5: membership in another tenant. The token's (foreign) tenant is never recorded.
    String c5 = correlation();
    MvcResult membership =
        perform(
            malformedCreate("/api/v1/legal-entities", token(tenant, foreign, null, "tenant-admin")),
            c5);
    single(c5, foreign, "legal-entity.create", Scope.TENANT, Stage.MEMBERSHIP, null);
    // Membership and role denials stay byte-identical apart from the correlation ID.
    assertThat(withoutCorrelation(membership)).isEqualTo(withoutCorrelation(role));

    // Platform scope: the incidental token tenant is never recorded.
    String c6 = correlation();
    mvc.perform(
            post("/api/v1/organizations")
                .header("Authorization", token(tenant, tenantAdminOnPlatform, null, "tenant-admin"))
                .header("Idempotency-Key", Organizations.newKey())
                .header(CorrelationId.HEADER, c6)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MALFORMED_JSON))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    single(c6, tenantAdminOnPlatform, "organization.create", Scope.PLATFORM, Stage.ROLE, null);
    String c7 = correlation();
    mvc.perform(
            post("/api/v1/organizations")
                .header(
                    "Authorization",
                    token(tenant, passwordPlatform, TestTokens.PASSWORD_ACR, "platform-admin"))
                .header("Idempotency-Key", Organizations.newKey())
                .header(CorrelationId.HEADER, c7)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MALFORMED_JSON))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
        .andExpect(header().string("WWW-Authenticate", STEP_UP));
    single(c7, passwordPlatform, "organization.create", Scope.PLATFORM, Stage.MFA, null);

    // Logs: the denial and its evidence outcome, never the actor or the request (D11).
    String logs = output.getAll();
    assertThat(logs)
        .contains("role_missing")
        .contains("tenant_context_missing")
        .contains("\"audit\":\"written\"")
        .doesNotContain("actorSubject")
        .doesNotContain(BODY_MARKER);
    for (String actor :
        List.of(
            employee,
            noTenant,
            badTenant,
            passwordAdmin,
            foreign,
            tenantAdminOnPlatform,
            passwordPlatform)) {
      assertThat(logs).doesNotContain(actor);
    }
    assertThat(logs).doesNotContain(otherTenant.toString()).doesNotContain("not-a-tenant");
  }

  // ------------------------------------------------------------------------------------------
  // Ineligible traffic and allowed requests (A13-3, A13-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void anonymousInvalidAndSubjectlessTrafficIsNeverAttributedAndAllowedCallsNeverWrite()
      throws Exception {
    long attempts = store.writeAttempts();
    Integer total =
        jdbc.queryForObject("SELECT count(*) FROM platform.authorization_denial", Integer.class);
    double ineligible = outcome("ineligible", "subject_missing", "tenant");

    // No token, an invalid signature and an expired token: 401, no row.
    mvc.perform(get("/api/v1/legal-entities")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/legal-entities").header("Authorization", "Bearer not.a.jwt"))
        .andExpect(status().isUnauthorized());
    String expired =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(subject("expired"))
                .roles(List.of("tenant-admin"))
                .expiresAt(Instant.now().minusSeconds(600))
                .build();
    mvc.perform(get("/api/v1/legal-entities").header("Authorization", expired))
        .andExpect(status().isUnauthorized());

    // A valid token without a subject, or with a blank one: denied, counted, never durable.
    mvc.perform(
            get("/api/v1/legal-entities")
                .header("Authorization", token(tenant, null, null, "tenant-admin")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            get("/api/v1/legal-entities")
                .header("Authorization", token(tenant, "   ", null, "tenant-admin")))
        .andExpect(status().isForbidden());
    assertThat(outcome("ineligible", "subject_missing", "tenant")).isEqualTo(ineligible + 2);

    // An allowed privileged request never touches the denial store.
    mvc.perform(get("/api/v1/legal-entities").header("Authorization", Hierarchy.admin(tenant)))
        .andExpect(status().isOk());

    assertThat(store.writeAttempts()).isEqualTo(attempts);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.authorization_denial", Integer.class))
        .isEqualTo(total);
  }

  // ------------------------------------------------------------------------------------------
  // Before binding: request data never changes the stage nor enters the evidence
  // ------------------------------------------------------------------------------------------

  @Test
  void malformedBodiesQueriesAndPathTargetsNeitherChangeTheStageNorEnterTheEvidence(
      CapturedOutput output) throws Exception {
    String employee = subject("binding");
    Memberships.grant(tenant, employee, "employee");
    String bearer = token(tenant, employee, null, "employee");
    UUID target = UUID.randomUUID();
    List<MockHttpServletRequestBuilder> requests =
        List.of(
            malformedCreate("/api/v1/legal-entities", bearer),
            get("/api/v1/legal-entities?limit=" + QUERY_MARKER + "&cursor=" + QUERY_MARKER)
                .header("Authorization", bearer),
            post("/api/v1/invitations/" + target + "/revoke").header("Authorization", bearer),
            post("/api/v1/access-review/lookup")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + QUERY_MARKER + "@example.cd\"}"));
    List<String> operations =
        List.of(
            "legal-entity.create",
            "legal-entity.list",
            "invitation.revoke",
            "access-review.lookup");
    for (int i = 0; i < requests.size(); i++) {
      String c = correlation();
      MvcResult result = perform(requests.get(i), c);
      assertThat(result.getResponse().getStatus()).isEqualTo(403);
      single(c, employee, operations.get(i), Scope.TENANT, Stage.ROLE, null);
    }
    String evidence =
        jdbc.queryForList(
                "SELECT * FROM platform.authorization_denial WHERE actor_subject = ?", employee)
            .toString();
    assertThat(evidence)
        .doesNotContain(BODY_MARKER)
        .doesNotContain(QUERY_MARKER)
        .doesNotContain(target.toString())
        .doesNotContain(tenant.toString());
    assertThat(output.getAll())
        .doesNotContain(BODY_MARKER)
        .doesNotContain(QUERY_MARKER)
        .doesNotContain(target.toString());
  }

  // ------------------------------------------------------------------------------------------
  // Reflective coverage of every privileged handler and applicable stage
  // ------------------------------------------------------------------------------------------

  private record Privileged(String operation, Scope scope, RequestMethod method, String path) {}

  private List<Privileged> privilegedHandlers() {
    List<Privileged> found = new ArrayList<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        mappings.getHandlerMethods().entrySet()) {
      HandlerMethod handler = entry.getValue();
      if (!handler.getBeanType().getPackageName().startsWith("com.divalhr.core.")) {
        continue;
      }
      PlatformScoped platform = handler.getMethodAnnotation(PlatformScoped.class);
      TenantScoped scoped =
          AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), TenantScoped.class);
      if (platform == null && (scoped == null || scoped.role().isEmpty())) {
        continue;
      }
      RequestMethod method = entry.getKey().getMethodsCondition().getMethods().iterator().next();
      String path =
          entry
              .getKey()
              .getPatternValues()
              .iterator()
              .next()
              .replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
      found.add(
          platform != null
              ? new Privileged(platform.operation(), Scope.PLATFORM, method, path)
              : new Privileged(scoped.operation(), Scope.TENANT, method, path));
    }
    return found;
  }

  private static MockHttpServletRequestBuilder call(Privileged handler, String bearer) {
    MockHttpServletRequestBuilder builder =
        MockMvcRequestBuilders.request(HttpMethod.valueOf(handler.method().name()), handler.path())
            .header("Authorization", bearer);
    if (handler.method() != RequestMethod.GET) {
      builder =
          builder
              .header("Idempotency-Key", "short")
              .contentType(MediaType.APPLICATION_JSON)
              .content(MALFORMED_JSON);
    }
    return builder;
  }

  @Test
  void everyPrivilegedHandlerRecordsEveryApplicableStage() throws Exception {
    List<Privileged> handlers = privilegedHandlers();
    Set<String> platform = new TreeSet<>();
    Set<String> tenantScoped = new TreeSet<>();
    for (Privileged handler : handlers) {
      assertThat(handler.operation()).as("operation name of %s", handler.path()).isNotEmpty();
      (handler.scope() == Scope.PLATFORM ? platform : tenantScoped).add(handler.operation());
    }
    assertThat(platform).isEqualTo(new TreeSet<>(PLATFORM_OPERATIONS));
    assertThat(tenantScoped).isEqualTo(new TreeSet<>(TENANT_OPERATIONS));
    assertThat(handlers).hasSize(40);

    for (Privileged handler : handlers) {
      // Role: an employee member (tenant) or a tenant administrator (platform).
      String roleActor = subject("reflect-role");
      String roleToken;
      if (handler.scope() == Scope.TENANT) {
        Memberships.grant(tenant, roleActor, "employee");
        roleToken = token(tenant, roleActor, null, "employee");
      } else {
        roleToken = token(tenant, roleActor, null, "tenant-admin");
      }
      String c = correlation();
      assertThat(perform(call(handler, roleToken), c).getResponse().getStatus()).isEqualTo(403);
      single(c, roleActor, handler.operation(), handler.scope(), Stage.ROLE, null);

      // MFA: the privileged role at password level.
      String mfaActor = subject("reflect-mfa");
      String privileged = handler.scope() == Scope.TENANT ? "tenant-admin" : "platform-admin";
      if (handler.scope() == Scope.TENANT) {
        Memberships.grant(tenant, mfaActor, "tenant-admin");
      }
      c = correlation();
      MvcResult mfa =
          perform(call(handler, token(tenant, mfaActor, TestTokens.PASSWORD_ACR, privileged)), c);
      assertThat(mfa.getResponse().getStatus()).isEqualTo(403);
      assertThat(mfa.getResponse().getHeader("WWW-Authenticate")).isEqualTo(STEP_UP);
      single(c, mfaActor, handler.operation(), handler.scope(), Stage.MFA, null);

      if (handler.scope() == Scope.TENANT) {
        String noTenant = subject("reflect-tenant");
        c = correlation();
        perform(call(handler, token(null, noTenant, null, "tenant-admin")), c);
        single(c, noTenant, handler.operation(), Scope.TENANT, Stage.TENANT_CONTEXT, null);

        String nonMember = subject("reflect-membership");
        c = correlation();
        perform(call(handler, token(tenant, nonMember, null, "tenant-admin")), c);
        single(c, nonMember, handler.operation(), Scope.TENANT, Stage.MEMBERSHIP, null);
      }
    }
  }

  // ------------------------------------------------------------------------------------------
  // Method-security provenance (A13-1, D5)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAProvenMethodSecurityDenialIsDriftAndAPlainDenialIsNot(CapturedOutput output)
      throws Exception {
    String admin = subject("drift");
    String bearer = Hierarchy.bearer(tenant, admin, "tenant-admin");
    double driftBefore = drift();

    String c1 = correlation();
    MvcResult drifted =
        perform(get("/test-support/denial-probes/drift").header("Authorization", bearer), c1);
    assertThat(drifted.getResponse().getStatus()).isEqualTo(403);
    assertThat(JSON.readTree(drifted.getResponse().getContentAsString()).get("code").asText())
        .isEqualTo("ACCESS_DENIED");
    // After the membership gate: the effective tenant is recorded.
    single(c1, admin, DenialProbeTestController.DRIFT, Scope.TENANT, Stage.METHOD_SECURITY, tenant);
    assertThat(drift()).isEqualTo(driftBefore + 1);
    assertThat(output.getAll()).contains("authorization_drift").doesNotContain(admin);

    // An authorized handler that throws a plain AccessDeniedException is not drift.
    long attempts = store.writeAttempts();
    String c2 = correlation();
    MvcResult plain =
        perform(get("/test-support/denial-probes/plain").header("Authorization", bearer), c2);
    assertThat(plain.getResponse().getStatus()).isEqualTo(403);
    JsonNode plainBody = withoutCorrelation(plain);
    JsonNode driftBody = withoutCorrelation(drifted);
    ((ObjectNode) plainBody).remove("instance");
    ((ObjectNode) driftBody).remove("instance");
    assertThat(plainBody).isEqualTo(driftBody);
    assertThat(rows(c2)).isEmpty();
    assertThat(drift()).isEqualTo(driftBefore + 1);
    assertThat(store.writeAttempts()).isEqualTo(attempts);
  }

  // ------------------------------------------------------------------------------------------
  // Independent transaction (D3, A13-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void theDenialRowSurvivesARolledBackOuterTransactionAndBusinessAuditStillNeedsOne() {
    String actor = subject("outer");
    String c = correlation();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, c);
    Jwt jwt =
        Jwt.withTokenValue("unused")
            .header("alg", "RS256")
            .subject(actor)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
    TransactionTemplate outer = new TransactionTemplate(transactions);
    outer.executeWithoutResult(
        status -> {
          assertThat(
                  audit.record(
                      request, authentication, Scope.TENANT, Stage.ROLE, "site.list", null))
              .isEqualTo(AuthorizationDenialAudit.Outcome.WRITTEN);
          // A second call for the same request is a no-op: one attempt per request at most.
          assertThat(
                  audit.record(request, authentication, Scope.TENANT, Stage.MFA, "site.list", null))
              .isEqualTo(AuthorizationDenialAudit.Outcome.ALREADY_RECORDED);
          status.setRollbackOnly();
        });
    single(c, actor, "site.list", Scope.TENANT, Stage.ROLE, null);

    AuditEvent event =
        new AuditEvent(
            UUID.randomUUID(),
            Instant.now(),
            actor,
            "site.list",
            "site",
            UUID.randomUUID(),
            tenant,
            "SUCCESS",
            c,
            Map.of(),
            "0".repeat(64));
    assertThatThrownBy(() -> auditRecorder.record(event))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  // ------------------------------------------------------------------------------------------
  // Storage failure (D4, A13-6): denied, unchanged response, bounded, signalled, no leak
  // ------------------------------------------------------------------------------------------

  @Test
  void aStorageFailureKeepsTheNormalDenialAndOnlySignalsTheGap(CapturedOutput output)
      throws Exception {
    String employee = subject("outage");
    Memberships.grant(tenant, employee, "employee");
    String passwordAdmin = subject("outage-mfa");
    Memberships.grant(tenant, passwordAdmin, "tenant-admin");
    double failedRole = outcome("failed", "role", "tenant");
    double failedMfa = outcome("failed", "mfa", "tenant");
    String baselineCorrelation = correlation();
    MvcResult baseline =
        perform(
            get("/api/v1/legal-entities")
                .header("Authorization", token(tenant, subject("baseline"), null, "employee")),
            baselineCorrelation);

    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute("LOCK TABLE platform.authorization_denial IN ACCESS EXCLUSIVE MODE");
      }
      try {
        // Role denial: same body as without the outage, within the bulkhead bounds.
        Instant start = Instant.now();
        String c1 = correlation();
        MvcResult denied =
            perform(
                get("/api/v1/legal-entities")
                    .header("Authorization", token(tenant, employee, null, "employee")),
                c1);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(6));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(withoutCorrelation(denied)).isEqualTo(withoutCorrelation(baseline));

        // MFA challenge unchanged.
        String c2 = correlation();
        mvc.perform(
                get("/api/v1/legal-entities")
                    .header(
                        "Authorization",
                        token(tenant, passwordAdmin, TestTokens.PASSWORD_ACR, "tenant-admin"))
                    .header(CorrelationId.HEADER, c2))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MFA_REQUIRED"))
            .andExpect(header().string("WWW-Authenticate", STEP_UP));

        // Pool exhaustion: more concurrent denials than bulkhead connections, all still denied.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
          List<Callable<Integer>> calls = new ArrayList<>();
          for (int i = 0; i < 4; i++) {
            String actor = subject("outage-burst");
            Memberships.grant(tenant, actor, "employee");
            calls.add(
                () ->
                    mvc.perform(
                            get("/api/v1/legal-entities")
                                .header("Authorization", token(tenant, actor, null, "employee")))
                        .andReturn()
                        .getResponse()
                        .getStatus());
          }
          for (Future<Integer> result : pool.invokeAll(calls)) {
            assertThat(result.get()).isEqualTo(403);
          }
        } finally {
          pool.shutdownNow();
        }

        // The application pool is unaffected: allowed requests still work during the outage.
        mvc.perform(get("/api/v1/legal-entities").header("Authorization", Hierarchy.admin(tenant)))
            .andExpect(status().isOk());
      } finally {
        lock.rollback();
      }
    }
    assertThat(outcome("failed", "role", "tenant")).isEqualTo(failedRole + 5);
    assertThat(outcome("failed", "mfa", "tenant")).isEqualTo(failedMfa + 1);
    assertThat(rowsOf(employee)).isZero();
    String logs = output.getAll();
    assertThat(logs)
        .contains("denial_audit_failed")
        .contains("errorType")
        .doesNotContain(employee)
        .doesNotContain(passwordAdmin)
        .doesNotContain("canceling statement")
        .doesNotContain("lock timeout")
        .doesNotContain("Connection is not available");

    // Recovery: the next denial is recorded again.
    String c3 = correlation();
    perform(
        get("/api/v1/legal-entities")
            .header("Authorization", token(tenant, employee, null, "employee")),
        c3);
    single(c3, employee, "legal-entity.list", Scope.TENANT, Stage.ROLE, null);
  }

  // ------------------------------------------------------------------------------------------
  // Privacy of telemetry (B4-style allow-list, A13-3)
  // ------------------------------------------------------------------------------------------

  @Test
  void denialTelemetryCarriesOnlyBoundedServerOwnedLabels() throws Exception {
    String employee = subject("telemetry");
    Memberships.grant(tenant, employee, "employee");
    perform(
        get("/api/v1/legal-entities")
            .header("Authorization", token(tenant, employee, null, "employee")),
        correlation());
    Set<String> stages =
        Set.of(
            "role",
            "tenant_context",
            "mfa",
            "membership",
            "rate_limit",
            "method_security",
            "subject_missing");
    Set<String> outcomes = Set.of("written", "suppressed", "failed", "ineligible");
    for (Meter meter : meters.find(AuthorizationDenialAudit.METRIC).meters()) {
      assertThat(meter.getId().getTags())
          .extracting(Tag::getKey)
          .containsExactlyInAnyOrder("outcome", "scope", "stage");
      assertThat(stages).contains(meter.getId().getTag("stage"));
      assertThat(outcomes).contains(meter.getId().getTag("outcome"));
      assertThat(Set.of("platform", "tenant")).contains(meter.getId().getTag("scope"));
    }
    for (Meter meter : meters.getMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertThat(tag.getValue()).doesNotContain("sub-denial-").doesNotContain(tenant.toString());
      }
    }
  }
}
