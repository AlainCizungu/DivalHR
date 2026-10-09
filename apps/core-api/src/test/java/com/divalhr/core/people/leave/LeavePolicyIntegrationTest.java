package com.divalhr.core.people.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-040A end to end against PostgreSQL with a settable clock: creation and listing, bilingual
 * names, the tracked/untracked rules, safe validation, statuses on the organization's business date
 * (including local-midnight boundaries in both supported time zones), per-tenant codes, tenant
 * isolation and cursor binding, idempotent retries, the audit and outbox records, the authorization
 * and MFA matrix, request limits, headers and log privacy.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class LeavePolicyIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PATH = "/api/v1/leave-policies";
  private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SettableClock clock;

  @AfterEach
  void realTime() {
    clock.reset();
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private record Tenant(UUID id, String admin) {}

  private Tenant tenant() throws Exception {
    UUID id = Hierarchy.newTenant(mvc);
    return new Tenant(
        id, Hierarchy.bearer(id, "sub-leave-admin-" + UUID.randomUUID(), "tenant-admin"));
  }

  private static Map<String, Object> policy(String code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("names", Map.of("en", "Annual leave", "fr", "Congé annuel"));
    body.put("unit", "DAYS");
    body.put("balanceMode", "TRACKED");
    body.put("annualEntitlement", 12.5);
    body.put("minimumServiceDays", 90);
    body.put("approvalRoute", "MANAGER");
    body.put("payrollEffect", "PAID");
    body.put("effectiveFrom", "2026-01-01");
    return body;
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    if (value == null) {
      copy.remove(key);
    } else {
      copy.put(key, value);
    }
    return copy;
  }

  private static MockHttpServletRequestBuilder create(String bearer, String key, Object body)
      throws Exception {
    MockHttpServletRequestBuilder request =
        post(PATH)
            .header("Authorization", bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String text ? text : JSON.writeValueAsString(body));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
    MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    return response;
  }

  private JsonNode expect(MockHttpServletRequestBuilder request, int status) throws Exception {
    MockHttpServletResponse response = call(request);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    return JSON.readTree(response.getContentAsString());
  }

  private JsonNode created(Tenant t, Map<String, Object> body) throws Exception {
    return expect(create(t.admin(), Organizations.newKey(), body), 201);
  }

  private JsonNode list(String bearer, String query) throws Exception {
    return expect(
        get(PATH + (query == null ? "" : "?" + query)).header("Authorization", bearer), 200);
  }

  private static List<String> fields(JsonNode problem) {
    List<String> fields = new ArrayList<>();
    problem
        .path("params")
        .path("fields")
        .forEach(f -> fields.add(f.get("field").asText() + ":" + f.get("constraint").asText()));
    return fields;
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  // ------------------------------------------------------------------------------------------
  // Create and list (AC1, AC2, AC3)
  // ------------------------------------------------------------------------------------------

  @Test
  void anAdministratorCreatesPoliciesAndSeesThemListedWithAccentsIntact() throws Exception {
    Tenant t = tenant();
    clock.set(LocalDate.of(2026, 6, 15).atTime(10, 0).atZone(KINSHASA).toInstant());

    MockHttpServletResponse response =
        call(create(t.admin(), Organizations.newKey(), policy("  annual-1 ")));
    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(response.getHeader("Idempotent-Replayed")).isNull();
    JsonNode result = JSON.readTree(response.getContentAsString());
    assertThat(result.get("asOf").asText()).isEqualTo("2026-06-15");
    assertThat(result.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    JsonNode created = result.get("policy");
    assertThat(created.get("code").asText()).isEqualTo("ANNUAL-1");
    assertThat(created.get("versionNumber").asInt()).isEqualTo(1);
    assertThat(created.get("names").get("en").asText()).isEqualTo("Annual leave");
    assertThat(created.get("names").get("fr").asText()).isEqualTo("Congé annuel");
    assertThat(created.get("unit").asText()).isEqualTo("DAYS");
    assertThat(created.get("balanceMode").asText()).isEqualTo("TRACKED");
    assertThat(created.get("annualEntitlement").decimalValue()).isEqualByComparingTo("12.50");
    assertThat(response.getContentAsString()).contains("\"annualEntitlement\":12.50,");
    assertThat(created.get("minimumServiceDays").asInt()).isEqualTo(90);
    assertThat(created.get("approvalRoute").asText()).isEqualTo("MANAGER");
    assertThat(created.get("payrollEffect").asText()).isEqualTo("PAID");
    assertThat(created.get("effectiveFrom").asText()).isEqualTo("2026-01-01");
    assertThat(created.get("effectiveTo").isNull()).isTrue();
    assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

    // Untracked: no entitlement; accents, NFC normalization and trimming of both names.
    Map<String, Object> sick =
        with(
            with(
                with(policy("sick"), "balanceMode", "UNTRACKED"),
                "names",
                Map.of("en", "  Sick leave ", "fr", "Conge\u0301 maladie — Ébène")),
            "annualEntitlement",
            null);
    sick.put("unit", "HOURS");
    sick.put("approvalRoute", "TENANT_ADMIN");
    sick.put("payrollEffect", "UNPAID");
    sick.put("minimumServiceDays", 0);
    sick.put("effectiveFrom", "2026-07-01");
    sick.put("effectiveTo", "2026-12-31");
    JsonNode second = created(t, sick).get("policy");
    assertThat(second.get("names").get("en").asText()).isEqualTo("Sick leave");
    assertThat(second.get("names").get("fr").asText()).isEqualTo("Congé maladie — Ébène");
    assertThat(second.get("annualEntitlement").isNull()).isTrue();
    assertThat(second.get("status").asText()).isEqualTo("PLANNED");

    JsonNode page = list(t.admin(), null);
    assertThat(page.get("asOf").asText()).isEqualTo("2026-06-15");
    assertThat(page.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    assertThat(page.get("nextCursor").isNull()).isTrue();
    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("items").get(0).get("code").asText()).isEqualTo("ANNUAL-1");
    assertThat(page.get("items").get(1).get("code").asText()).isEqualTo("SICK");
    assertThat(page.get("items").get(1).get("names").get("fr").asText())
        .isEqualTo("Congé maladie — Ébène");
    assertThat(page.get("items").get(1).get("unit").asText()).isEqualTo("HOURS");

    // The stored row matches (NFC, trimmed, two decimals).
    assertThat(
            jdbc.queryForObject(
                "SELECT name_fr FROM people.leave_policy_version WHERE tenant_id = ?"
                    + " AND policy_id = ?",
                String.class,
                t.id(),
                UUID.fromString(second.get("id").asText())))
        .isEqualTo("Congé maladie — Ébène");
    assertThat(
            jdbc.queryForObject(
                "SELECT annual_entitlement::text FROM people.leave_policy_version"
                    + " WHERE tenant_id = ? AND policy_id = ?",
                String.class,
                t.id(),
                UUID.fromString(created.get("id").asText())))
        .isEqualTo("12.50");
  }

  @Test
  void trackedPoliciesNeedAValidEntitlementAndUntrackedOnesRejectIt() throws Exception {
    Tenant t = tenant();
    String code = Hierarchy.code("TR");
    JsonNode missing =
        expect(
            create(
                t.admin(), Organizations.newKey(), with(policy(code), "annualEntitlement", null)),
            400);
    assertThat(fields(missing)).containsExactly("annualEntitlement:REQUIRED");
    for (Object invalid : List.of(0, -1, 10000.01, 1.005)) {
      JsonNode problem =
          expect(
              create(
                  t.admin(),
                  Organizations.newKey(),
                  with(policy(code), "annualEntitlement", invalid)),
              400);
      assertThat(fields(problem))
          .as("%s", invalid)
          .containsExactly("annualEntitlement:" + (invalid.equals(1.005) ? "FORMAT" : "RANGE"));
    }
    JsonNode untracked =
        expect(
            create(
                t.admin(), Organizations.newKey(), with(policy(code), "balanceMode", "UNTRACKED")),
            400);
    assertThat(fields(untracked)).containsExactly("annualEntitlement:RANGE");
    MockHttpServletResponse max =
        call(
            create(
                t.admin(), Organizations.newKey(), with(policy(code), "annualEntitlement", 10000)));
    assertThat(max.getStatus()).isEqualTo(201);
    assertThat(max.getContentAsString()).contains("\"annualEntitlement\":10000.00,");
    assertThat(count("SELECT count(*) FROM people.leave_policy WHERE tenant_id = ?", t.id()))
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------
  // Safe validation (AC4, AC12)
  // ------------------------------------------------------------------------------------------

  @Test
  void invalidInputFailsSafelyWithoutEchoingAnySubmittedValue(CapturedOutput output)
      throws Exception {
    Tenant t = tenant();
    String marker = "Marqueur Privé Zèbre";
    Map<String, Object> body = policy("MARK-" + UUID.randomUUID().toString().substring(0, 4));
    body.put("names", Map.of("en", marker, "fr", marker, "de", marker));
    body.put("unit", "WEEKS");
    body.put("balanceMode", "tracked");
    body.put("minimumServiceDays", 3651);
    body.put("approvalRoute", "HR");
    body.put("payrollEffect", 1);
    body.put("effectiveFrom", "2026-02-30");
    body.put("effectiveTo", "1899-12-31");
    body.put("comment", marker);
    MockHttpServletResponse response = call(create(t.admin(), Organizations.newKey(), body));
    assertThat(response.getStatus()).isEqualTo(400);
    String text = response.getContentAsString();
    JsonNode problem = JSON.readTree(text);
    assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(fields(problem))
        .containsExactlyInAnyOrder(
            "body:UNKNOWN_PROPERTY",
            "names:UNKNOWN_PROPERTY",
            "unit:FORMAT",
            "balanceMode:FORMAT",
            "minimumServiceDays:RANGE",
            "approvalRoute:FORMAT",
            "payrollEffect:FORMAT",
            "effectiveFrom:FORMAT",
            "effectiveTo:RANGE");
    assertThat(text).doesNotContain("Marqueur").doesNotContain("comment").doesNotContain("WEEKS");

    // Malformed JSON, wrong types, bad dates, codes and keys.
    JsonNode malformed = expect(create(t.admin(), Organizations.newKey(), "{\"code\": "), 400);
    assertThat(fields(malformed)).containsExactly("body:FORMAT");
    JsonNode types =
        expect(
            create(
                t.admin(),
                null,
                with(
                    with(with(policy("x"), "names", "Congé"), "minimumServiceDays", 1.5),
                    "effectiveFrom",
                    null)),
            400);
    assertThat(fields(types))
        .containsExactlyInAnyOrder(
            "Idempotency-Key:REQUIRED",
            "code:LENGTH",
            "names:FORMAT",
            "minimumServiceDays:FORMAT",
            "effectiveFrom:REQUIRED");
    JsonNode period =
        expect(
            create(
                t.admin(),
                Organizations.newKey(),
                with(
                    with(policy("A_" + "B".repeat(19)), "effectiveTo", "2025-12-31"),
                    "names",
                    Map.of("en", "x", "fr", "a\u0000b"))),
            400);
    assertThat(fields(period))
        .containsExactlyInAnyOrder(
            "code:LENGTH", "effectiveTo:RANGE", "names.en:LENGTH", "names.fr:FORMAT");
    assertThat(count("SELECT count(*) FROM people.leave_policy WHERE tenant_id = ?", t.id()))
        .isZero();
    assertThat(logs(output)).doesNotContain("Marqueur").doesNotContain("Zèbre");
  }

  // ------------------------------------------------------------------------------------------
  // Status on the business date (AC5)
  // ------------------------------------------------------------------------------------------

  @Test
  void statusFollowsTheOrganizationsBusinessDateAcrossLocalMidnight() throws Exception {
    Tenant t = tenant();
    LocalDate day = LocalDate.of(2026, 9, 1);
    created(t, with(policy("STARTS"), "effectiveFrom", day.toString()));
    created(
        t,
        with(with(policy("ENDS"), "effectiveFrom", "2026-01-01"), "effectiveTo", day.toString()));

    // 22:59Z on the eve is 23:59 in Kinshasa (UTC+1): still the eve.
    clock.set(day.minusDays(1).atTime(22, 59).toInstant(ZoneOffset.UTC));
    JsonNode eve = list(t.admin(), null);
    assertThat(eve.get("asOf").asText()).isEqualTo("2026-08-31");
    assertThat(statuses(eve)).containsExactly("ACTIVE", "PLANNED");

    // 23:00Z is local midnight in Kinshasa: the first day starts.
    clock.set(day.minusDays(1).atTime(23, 0).toInstant(ZoneOffset.UTC));
    JsonNode first = list(t.admin(), null);
    assertThat(first.get("asOf").asText()).isEqualTo("2026-09-01");
    assertThat(statuses(first)).containsExactly("ACTIVE", "ACTIVE");

    // The last day is inclusive; the next local midnight ends it.
    clock.set(day.atTime(22, 59).toInstant(ZoneOffset.UTC));
    assertThat(statuses(list(t.admin(), null))).containsExactly("ACTIVE", "ACTIVE");
    clock.set(day.atTime(23, 0).toInstant(ZoneOffset.UTC));
    JsonNode after = list(t.admin(), null);
    assertThat(after.get("asOf").asText()).isEqualTo("2026-09-02");
    assertThat(statuses(after)).containsExactly("ENDED", "ACTIVE");

    // Lubumbashi (UTC+2): local midnight is one hour earlier for the same instant.
    jdbc.update(
        "UPDATE tenant.organization SET timezone = 'Africa/Lubumbashi' WHERE id = ?", t.id());
    clock.set(day.minusDays(1).atTime(22, 0).toInstant(ZoneOffset.UTC));
    JsonNode east = list(t.admin(), null);
    assertThat(east.get("timezone").asText()).isEqualTo("Africa/Lubumbashi");
    assertThat(east.get("asOf").asText()).isEqualTo("2026-09-01");
    assertThat(statuses(east)).containsExactly("ACTIVE", "ACTIVE");
    clock.set(day.minusDays(1).atTime(21, 59).toInstant(ZoneOffset.UTC));
    assertThat(statuses(list(t.admin(), null))).containsExactly("ACTIVE", "PLANNED");
  }

  private static List<String> statuses(JsonNode page) {
    List<String> statuses = new ArrayList<>();
    page.get("items").forEach(item -> statuses.add(item.get("status").asText()));
    return statuses;
  }

  // ------------------------------------------------------------------------------------------
  // Codes per tenant, isolation and cursors (AC6, AC7)
  // ------------------------------------------------------------------------------------------

  @Test
  void codesAreUniquePerTenantAndNothingCrossesTenants() throws Exception {
    Tenant a = tenant();
    Tenant b = tenant();
    created(a, policy("SHARED"));
    JsonNode duplicate = expect(create(a.admin(), Organizations.newKey(), policy(" shared ")), 409);
    assertThat(duplicate.get("code").asText()).isEqualTo("LEAVE_POLICY_CODE_EXISTS");
    assertThat(duplicate.get("params").size()).isZero();
    assertThat(duplicate.toString()).doesNotContain("SHARED").doesNotContain("Congé");
    created(b, policy("SHARED"));
    for (String code : List.of("A-1", "A-2", "A-3")) {
      created(a, policy(code));
    }

    JsonNode first = list(a.admin(), "limit=2");
    assertThat(codes(first)).containsExactly("A-1", "A-2");
    String cursor = first.get("nextCursor").asText();
    JsonNode second = list(a.admin(), "limit=2&cursor=" + cursor);
    assertThat(codes(second)).containsExactly("A-3", "SHARED");
    assertThat(second.get("nextCursor").isNull()).isTrue();

    JsonNode other = list(b.admin(), null);
    assertThat(codes(other)).containsExactly("SHARED");
    assertThat(other.get("items").get(0).get("id").asText())
        .isNotEqualTo(second.get("items").get(1).get("id").asText());

    // A cursor is bound to its tenant, operation and page size.
    for (String query :
        List.of(
            "limit=2&cursor=" + cursor,
            "cursor=" + cursor,
            "limit=2&cursor=" + cursor.substring(0, cursor.length() - 2) + "AA")) {
      JsonNode rejected =
          expect(
              get(PATH + "?" + query)
                  .header(
                      "Authorization",
                      query.startsWith("limit=2&cursor=" + cursor) ? b.admin() : a.admin()),
              400);
      assertThat(rejected.get("code").asText()).as(query).isEqualTo("CURSOR_INVALID");
    }
    for (String limit : List.of("0", "51", "abc", "-1")) {
      JsonNode rejected =
          expect(get(PATH + "?limit=" + limit).header("Authorization", a.admin()), 400);
      assertThat(fields(rejected)).containsExactly("limit:RANGE");
    }
    assertThat(list(a.admin(), "limit=50").get("items")).hasSize(4);
  }

  @Test
  void laterPagesKeepTheBusinessDateTheirCursorPinned() throws Exception {
    Tenant t = tenant();
    LocalDate day = LocalDate.of(2026, 10, 1);
    for (String code : List.of("P-1", "P-2", "P-3")) {
      created(t, with(policy(code), "effectiveFrom", day.plusDays(1).toString()));
    }
    clock.set(day.atTime(12, 0).atZone(KINSHASA).toInstant());
    JsonNode first = list(t.admin(), "limit=2");
    assertThat(first.get("asOf").asText()).isEqualTo("2026-10-01");
    clock.set(day.plusDays(1).atTime(12, 0).atZone(KINSHASA).toInstant());
    JsonNode second = list(t.admin(), "limit=2&cursor=" + first.get("nextCursor").asText());
    assertThat(second.get("asOf").asText()).isEqualTo("2026-10-01");
    assertThat(statuses(second)).containsExactly("PLANNED");
    assertThat(list(t.admin(), null).get("asOf").asText()).isEqualTo("2026-10-02");
  }

  private static List<String> codes(JsonNode page) {
    List<String> codes = new ArrayList<>();
    page.get("items").forEach(item -> codes.add(item.get("code").asText()));
    return codes;
  }

  // ------------------------------------------------------------------------------------------
  // Idempotency, audit and outbox (AC8, AC9)
  // ------------------------------------------------------------------------------------------

  @Test
  void retriesReplayAndChangedPayloadsConflictWithOneAuditAndOneEvent(CapturedOutput output)
      throws Exception {
    Tenant t = tenant();
    String key = Organizations.newKey();
    String code = Hierarchy.code("ID");
    MockHttpServletResponse first = call(create(t.admin(), key, policy(code)));
    assertThat(first.getStatus()).isEqualTo(201);
    MockHttpServletResponse replay = call(create(t.admin(), key, policy(code)));
    assertThat(replay.getStatus()).isEqualTo(201);
    assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(JSON.readTree(replay.getContentAsString()))
        .isEqualTo(JSON.readTree(first.getContentAsString()));
    JsonNode mismatch =
        expect(create(t.admin(), key, with(policy(code), "minimumServiceDays", 91)), 409);
    assertThat(mismatch.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    UUID id =
        UUID.fromString(JSON.readTree(first.getContentAsString()).get("policy").get("id").asText());
    assertThat(count("SELECT count(*) FROM people.leave_policy WHERE tenant_id = ?", t.id()))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM people.leave_policy_version WHERE tenant_id = ?", t.id()))
        .isEqualTo(1);

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT action, resource_type, result, metadata::text AS metadata,"
                + " after_state_sha256 FROM platform.audit_event WHERE resource_id = ?",
            id);
    assertThat(audit.get("action")).isEqualTo("leave-policy.create");
    assertThat(audit.get("resource_type")).isEqualTo("leave-policy");
    assertThat(audit.get("result")).isEqualTo("SUCCESS");
    assertThat(String.valueOf(audit.get("after_state_sha256"))).matches("^[0-9a-f]{64}$");
    JsonNode metadata = JSON.readTree((String) audit.get("metadata"));
    assertThat(fieldNames(metadata))
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "policyId",
            "versionId",
            "code",
            "versionNumber",
            "unit",
            "balanceMode",
            "approvalRoute",
            "payrollEffect");
    assertThat(metadata.toString())
        .doesNotContain("Annual leave")
        .doesNotContain("Congé")
        .doesNotContain("12.5")
        .doesNotContain("2026-01-01");

    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT event_type, envelope::text AS envelope FROM platform.outbox_event"
                + " WHERE envelope ->> 'subject' = ?",
            id.toString());
    assertThat(event.get("event_type")).isEqualTo("people.leave-policy.created.v1");
    JsonNode envelope = JSON.readTree((String) event.get("envelope"));
    assertThat(envelope.get("tenantId").asText()).isEqualTo(t.id().toString());
    assertThat(envelope.get("source").asText()).isEqualTo("core-api/people");
    assertThat(fieldNames(envelope.get("data"))).isEqualTo(fieldNames(metadata));
    assertThat(envelope.toString()).doesNotContain("Annual leave").doesNotContain("Congé");

    assertThat(logs(output))
        .contains("leave_policy_created")
        .contains("leave_policy_create_replayed")
        .doesNotContain("Annual leave")
        .doesNotContain("Congé")
        .doesNotContain(code)
        .doesNotContain(key);
  }

  /** The application's structured log lines (MockMvc's own failure dump is not a log). */
  private static String logs(CapturedOutput output) {
    return String.join(
        "\n", output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  // ------------------------------------------------------------------------------------------
  // Authorization and limits (AC10)
  // ------------------------------------------------------------------------------------------

  @Test
  void onlyAnMfaTenantAdministratorWithAnActiveMembershipIsAdmitted() throws Exception {
    Tenant t = tenant();
    String employee = "sub-leave-employee-" + UUID.randomUUID();
    Memberships.grant(t.id(), employee, "employee");
    String password = "sub-leave-pwd-" + UUID.randomUUID();
    Memberships.grant(t.id(), password, "tenant-admin");
    String stranger = "sub-leave-stranger-" + UUID.randomUUID();
    Map<String, String> callers = new LinkedHashMap<>();
    callers.put(
        "employee",
        "Bearer "
            + TestTokens.token()
                .tenant(t.id())
                .subject(employee)
                .roles(List.of("employee"))
                .build());
    callers.put(
        "password",
        "Bearer "
            + TestTokens.token()
                .tenant(t.id())
                .subject(password)
                .roles(List.of("tenant-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build());
    callers.put(
        "no-membership",
        "Bearer "
            + TestTokens.token()
                .tenant(t.id())
                .subject(stranger)
                .roles(List.of("tenant-admin"))
                .build());
    callers.put(
        "platform-admin",
        "Bearer "
            + TestTokens.token()
                .tenant(t.id())
                .subject("sub-leave-platform-" + UUID.randomUUID())
                .roles(List.of("platform-admin"))
                .build());
    callers.put(
        "no-tenant",
        "Bearer "
            + TestTokens.token()
                .tenant(null)
                .subject("sub-leave-notenant-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .build());
    Map<String, String> expected =
        Map.of(
            "employee", "ACCESS_DENIED",
            "password", "MFA_REQUIRED",
            "no-membership", "ACCESS_DENIED",
            "platform-admin", "ACCESS_DENIED",
            "no-tenant", "TENANT_CONTEXT_MISSING");
    for (Map.Entry<String, String> caller : callers.entrySet()) {
      for (MockHttpServletRequestBuilder request :
          List.of(
              create(caller.getValue(), Organizations.newKey(), policy("DENIED")),
              get(PATH).header("Authorization", caller.getValue()))) {
        MockHttpServletResponse response = call(request);
        assertThat(response.getStatus()).as(caller.getKey()).isEqualTo(403);
        assertThat(JSON.readTree(response.getContentAsString()).get("code").asText())
            .as(caller.getKey())
            .isEqualTo(expected.get(caller.getKey()));
      }
    }
    assertThat(call(get(PATH)).getStatus()).isEqualTo(401);
    assertThat(call(create("Bearer not-a-token", Organizations.newKey(), policy("X1"))).getStatus())
        .isEqualTo(401);
    assertThat(count("SELECT count(*) FROM people.leave_policy WHERE tenant_id = ?", t.id()))
        .isZero();
    assertThat(
            count(
                "SELECT count(DISTINCT actor_subject || operation) FROM"
                    + " platform.authorization_denial WHERE operation IN ('leave-policy.create',"
                    + " 'leave-policy.list') AND actor_subject IN (?, ?, ?)",
                employee,
                password,
                stranger))
        .isEqualTo(6);
  }

  @Test
  void writesAreRateLimitedPerSubject() throws Exception {
    Tenant t = tenant();
    String invalid = "{\"code\": 1}";
    int limited = 0;
    for (int i = 0; i < 21; i++) {
      MockHttpServletResponse response = call(create(t.admin(), Organizations.newKey(), invalid));
      if (response.getStatus() == 429) {
        limited++;
        assertThat(JSON.readTree(response.getContentAsString()).get("code").asText())
            .isEqualTo("RATE_LIMITED");
      } else {
        assertThat(response.getStatus()).isEqualTo(400);
      }
    }
    assertThat(limited).isEqualTo(1);
  }
}
