package com.divalhr.core.documents;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-031A (Issue #73) end to end against PostgreSQL with a settable clock: the coverage-head rule
 * (A31A-1), the separation rule (A31A-2), category boundaries on the organization's business date
 * in both supported time zones, pagination guarantees (A31A-4), summary and counts consistency,
 * tenant isolation, authorization, fail-closed audit with payload-only summary digests (A31A-6),
 * and privacy of logs.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
@ExtendWith(OutputCaptureExtension.class)
class ContractExpirationIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SEARCH = "/api/v1/contract-expirations/search";
  private static final String SUMMARY = "/api/v1/contract-expirations/summary";
  private static final String EMPLOYEES = "/api/v1/employees";
  private static final LocalDate HIRED = LocalDate.of(2026, 1, 5);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int BATCH = 30;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SettableClock clock;
  @Autowired private EmailLookup lookups;
  @Autowired private PlatformTransactionManager transactions;

  @AfterEach
  void realTime() {
    clock.reset();
  }

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private record Template(UUID templateId, UUID versionId, String type) {}

  private record World(
      Org org, String admin, LocalDate today, Template fixed, Template open, Deque<UUID> pool) {}

  private World world() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String admin =
        Hierarchy.bearer(org.tenant(), "sub-expiry-admin-" + UUID.randomUUID(), "tenant-admin");
    LocalDate today = Employees.today(jdbc, org.tenant());
    return new World(
        org,
        admin,
        today,
        template(org, admin, "FIXED_TERM"),
        template(org, admin, "PERMANENT"),
        new ArrayDeque<>());
  }

  private Template template(Org org, String admin, String type) throws Exception {
    JsonNode template =
        expect(
            keyed(
                admin,
                "/api/v1/contract-templates",
                Map.of(
                    "code", Hierarchy.code("CT"), "name", "Modèle " + type, "contractType", type)),
            201);
    String id = template.get("id").asText();
    JsonNode draft =
        expect(
            keyed(
                admin,
                "/api/v1/contract-templates/" + id + "/versions",
                Map.of("locale", "fr", "title", "Contrat", "body", "# Contrat\nTexte du contrat.")),
            201);
    JsonNode approved =
        expect(
            keyed(
                admin,
                "/api/v1/contract-templates/"
                    + id
                    + "/versions/"
                    + draft.get("id").asText()
                    + "/approve",
                Map.of(
                    "expectedVersion", draft.get("version").asLong(),
                    "acknowledgements", List.of("TEXT_VERIFIED"))),
            200);
    return new Template(UUID.fromString(id), UUID.fromString(approved.get("id").asText()), type);
  }

  private UUID hire(World w, String given, String family) throws Exception {
    return Employees.hire(mvc, jdbc, w.org(), w.admin(), Employees.number(), given, family, HIRED);
  }

  /**
   * A synthetic employee, hired in batches of {@value #BATCH} through one import (the import's
   * subject limit admits a few uploads per minute).
   */
  private UUID hire(World w) throws Exception {
    if (w.pool().isEmpty()) {
      List<String> lines = new ArrayList<>();
      lines.add(EmployeeImports.FRENCH_HEADER);
      List<String> numbers = new ArrayList<>();
      for (int i = 0; i < BATCH; i++) {
        String number = Employees.number();
        numbers.add(number);
        StringBuilder suffix = new StringBuilder();
        for (int c = 0; c < 6; c++) {
          suffix.append((char) ('a' + RANDOM.nextInt(26)));
        }
        lines.add(
            EmployeeImports.semi(
                number,
                "Synthétique",
                "Employé-" + suffix,
                HIRED.toString(),
                w.org().legalEntity(),
                w.org().site(),
                w.org().department(),
                "",
                ""));
      }
      JsonNode summary = EmployeeImports.uploaded(mvc, w.admin(), EmployeeImports.csv(lines));
      expect(
          call(
              EmployeeImports.commit(
                  w.admin(),
                  summary.get("id").asText(),
                  Organizations.newKey(),
                  summary.get("previewDigest").asText(),
                  BATCH,
                  false)),
          200);
      for (String number : numbers) {
        w.pool()
            .add(
                jdbc.queryForObject(
                    "SELECT id FROM people.employee WHERE tenant_id = ? AND employee_number = ?",
                    UUID.class,
                    w.org().tenant(),
                    number));
      }
    }
    return w.pool().removeFirst();
  }

  private UUID employment(World w, UUID employee) {
    return jdbc.queryForObject(
        "SELECT id FROM people.employment WHERE tenant_id = ? AND employee_id = ?"
            + " ORDER BY effective_from DESC LIMIT 1",
        UUID.class,
        w.org().tenant(),
        employee);
  }

  /**
   * Inserts an issued contract with a valid snapshot digest (the queue reads stored contracts;
   * issuing is MVP-030's test, and the daily integrity check must still pass).
   */
  private UUID contract(World w, UUID employee, UUID employment, LocalDate start, LocalDate end) {
    Template template = end == null ? w.open() : w.fixed();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents.contract_employment_guard (tenant_id, employment_id) VALUES (?, ?)"
            + " ON CONFLICT DO NOTHING",
        w.org().tenant(),
        employment);
    jdbc.update(
        "INSERT INTO documents.contract (id, tenant_id, employee_id, employment_id, template_id,"
            + " template_version_id, contract_type, locale, start_date, end_date, snapshot,"
            + " snapshot_canonical, snapshot_sha256, digest_version, grammar_version,"
            + " renderer_version, state, issued_at, issued_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'fr',"
            + " ?, ?, '{}'::jsonb, '{}', ?, 1, 1, 1, 'ISSUED', now(), 'sub-test')",
        id,
        w.org().tenant(),
        employee,
        employment,
        template.templateId(),
        template.versionId(),
        template.type(),
        start,
        end,
        ContractDigests.snapshot("{}"));
    return id;
  }

  private UUID contract(World w, UUID employee, LocalDate start, LocalDate end) {
    return contract(w, employee, employment(w, employee), start, end);
  }

  private void voidContract(UUID contract) {
    jdbc.update(
        "UPDATE documents.contract SET state = 'VOID', void_reason = 'ISSUED_IN_ERROR',"
            + " voided_at = now(), voided_by = 'sub-test', version = version + 1 WHERE id = ?",
        contract);
  }

  /** Acknowledges a contract with stored evidence bound to the employee's own access link. */
  private void acknowledge(World w, UUID employee, UUID contract) throws Exception {
    UUID membership = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        membership,
        w.org().tenant(),
        UUID.randomUUID().toString(),
        lookups.of(
            EmailAddress.parse(
                    "expiry-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd")
                .orElseThrow()));
    expect(
        call(
            postJson(
                    w.admin(),
                    EMPLOYEES + "/" + employee + "/access-link",
                    "{\"membershipId\":\"" + membership + "\"}")
                .header("Idempotency-Key", Organizations.newKey())),
        201);
    UUID link =
        jdbc.queryForObject(
            "SELECT id FROM identity.employee_access_link WHERE tenant_id = ? AND employee_id = ?"
                + " AND unlinked_at IS NULL",
            UUID.class,
            w.org().tenant(),
            employee);
    String sha =
        jdbc.queryForObject(
            "SELECT snapshot_sha256 FROM documents.contract WHERE id = ?", String.class, contract);
    Instant at = Instant.now();
    jdbc.update(
        "INSERT INTO documents.contract_acknowledgement (id, tenant_id, contract_id, employee_id,"
            + " membership_id, link_id, snapshot_sha256, snapshot_digest_version, grammar_version,"
            + " renderer_version, statement_code, statement_version, statement_locale,"
            + " statement_sha256, evidence_sha256, acknowledged_at, correlation_id) VALUES (?, ?,"
            + " ?, ?, ?, ?, ?, 1, 1, 1, 'RECEIVED_AND_REVIEWED', 1, 'fr', ?, ?, ?, 'test-ack')",
        UUID.randomUUID(),
        w.org().tenant(),
        contract,
        employee,
        membership,
        link,
        sha,
        "b".repeat(64),
        "c".repeat(64),
        java.sql.Timestamp.from(at));
    jdbc.update(
        "UPDATE documents.contract SET state = 'ACKNOWLEDGED', acknowledged_at = ?,"
            + " version = version + 1 WHERE id = ?",
        java.sql.Timestamp.from(at),
        contract);
  }

  /** A second employment of the same employee (rehire), placed like the first. */
  private UUID rehire(World w, UUID employee, LocalDate start) {
    UUID employment = UUID.randomUUID();
    // The placement-coverage constraint is checked at commit: insert all three rows together.
    new TransactionTemplate(transactions)
        .executeWithoutResult(status -> insertRehire(w, employee, start, employment));
    return employment;
  }

  private void insertRehire(World w, UUID employee, LocalDate start, UUID employment) {
    UUID change = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from, created_at,"
            + " created_by) VALUES (?, ?, ?, ?, now(), 'sub-test')",
        employment,
        w.org().tenant(),
        employee,
        start);
    jdbc.update(
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, recorded_at, recorded_by, version_after) VALUES (?, ?, ?,"
            + " ?, 'HIRE', ?, ARRAY['PLACEMENT'], now(), 'sub-test', 0)",
        change,
        w.org().tenant(),
        employee,
        employment,
        start);
    jdbc.update(
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, department_id,"
            + " created_by_change_id, origin_change_id) VALUES (?, ?, ?, ?, 'PLACEMENT', ?, ?, ?,"
            + " ?, ?, ?)",
        UUID.randomUUID(),
        w.org().tenant(),
        employee,
        employment,
        start,
        w.org().legalEntityId(),
        w.org().siteId(),
        w.org().departmentId(),
        change,
        change);
  }

  private JsonNode separate(World w, UUID employee, LocalDate lastDay) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("lastDay", lastDay.toString());
    command.put("reasonCode", "END_OF_FIXED_TERM");
    command.put("accessTiming", lastDay.isBefore(w.today()) ? "IMMEDIATELY" : "END_OF_LAST_DAY");
    JsonNode preview =
        expect(
            call(
                postJson(
                    w.admin(),
                    EMPLOYEES + "/" + employee + "/separations/preview",
                    JSON.writeValueAsString(command))),
            200);
    List<String> acks = new ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgements", acks);
    return expect(
            call(
                postJson(
                        w.admin(),
                        EMPLOYEES + "/" + employee + "/separations",
                        JSON.writeValueAsString(body))
                    .header("Idempotency-Key", Organizations.newKey())),
            201)
        .get("separation");
  }

  private void cancelSeparation(World w, UUID employee, String separationId) throws Exception {
    JsonNode preview =
        expect(
            call(
                postJson(
                    w.admin(),
                    EMPLOYEES + "/" + employee + "/separations/" + separationId + "/cancel/preview",
                    "")),
            200);
    expect(
        call(
            postJson(
                    w.admin(),
                    EMPLOYEES + "/" + employee + "/separations/" + separationId + "/cancel",
                    "{\"expectedVersion\":"
                        + preview.get("expectedVersion").asLong()
                        + ",\"cancellationDigest\":\""
                        + preview.get("cancellationDigest").asText()
                        + "\"}")
                .header("Idempotency-Key", Organizations.newKey())),
        200);
  }

  private MvcResult call(RequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn();
  }

  private MvcResult keyed(String bearer, String path, Object body) throws Exception {
    return call(
        postJson(bearer, path, JSON.writeValueAsString(body))
            .header("Idempotency-Key", Organizations.newKey()));
  }

  private static JsonNode expect(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(status);
    return result.getResponse().getContentAsByteArray().length == 0
        ? null
        : Employees.json(result.getResponse().getContentAsByteArray());
  }

  private static void problem(MvcResult result, int status, String code) throws Exception {
    assertThat(expect(result, status).get("code").asText()).isEqualTo(code);
  }

  private MvcResult search(String bearer, Map<String, Object> body) throws Exception {
    return call(postJson(bearer, SEARCH, JSON.writeValueAsString(body)));
  }

  private JsonNode page(World w, Map<String, Object> body) throws Exception {
    return expect(search(w.admin(), body), 200);
  }

  private JsonNode summary(World w) throws Exception {
    return expect(call(get(SUMMARY).header("Authorization", w.admin())), 200);
  }

  /** Every item of a search, following cursors. */
  private List<JsonNode> all(World w, Map<String, Object> filters, int limit) throws Exception {
    List<JsonNode> items = new ArrayList<>();
    Map<String, Object> body = new LinkedHashMap<>(filters);
    body.put("limit", limit);
    while (true) {
      JsonNode page = page(w, body);
      page.get("items").forEach(items::add);
      if (page.get("nextCursor").isNull()) {
        return items;
      }
      body.put("cursor", page.get("nextCursor").asText());
    }
  }

  private static Map<String, String> byContract(JsonNode page) {
    Map<String, String> found = new LinkedHashMap<>();
    page.get("items")
        .forEach(
            item ->
                found.put(
                    item.get("contractId").asText(),
                    item.get("category").asText() + ":" + item.get("daysUntilEnd").asLong()));
    return found;
  }

  // ------------------------------------------------------------------------------------------
  // A31A-1: the coverage head
  // ------------------------------------------------------------------------------------------

  @Test
  void theCoverageHeadDecidesEveryEmploymentsSingleWarning() throws Exception {
    World w = world();
    LocalDate t = w.today();
    Map<String, String> expected = new LinkedHashMap<>();

    // Seamless fixed-term renewal: the predecessor is suppressed, the renewal warns.
    UUID renewed = hire(w);
    contract(w, renewed, t.minusDays(200), t.minusDays(10));
    expected.put(
        contract(w, renewed, t.minusDays(9), t.plusDays(20)).toString(), "NEXT_30_DAYS:20");

    // Seamless open-ended renewal clears the warning.
    UUID permanent = hire(w);
    contract(w, permanent, t.minusDays(100), t.plusDays(10));
    contract(w, permanent, t.plusDays(11), null);

    // A future renewal after a gap does not hide the current contract's expiration.
    UUID gapAhead = hire(w);
    expected.put(
        contract(w, gapAhead, t.minusDays(100), t.plusDays(15)).toString(), "NEXT_30_DAYS:15");
    contract(w, gapAhead, t.plusDays(20), t.plusDays(300));

    // An expired contract followed by a future contract after a gap stays visible.
    UUID lapsed = hire(w);
    expected.put(contract(w, lapsed, t.minusDays(200), t.minusDays(5)).toString(), "EXPIRED:-5");
    contract(w, lapsed, t.plusDays(10), null);

    // Once a later contract has started, the older gap does not reappear.
    UUID started = hire(w);
    contract(w, started, t.minusDays(250), t.minusDays(100));
    contract(w, started, t.minusDays(50), t.plusDays(200));

    // A future-only employment contract.
    UUID future = hire(w);
    expected.put(contract(w, future, t.plusDays(5), t.plusDays(40)).toString(), "DAYS_31_TO_60:40");

    // Multiple contiguous renewals: the last of the chain is the head.
    UUID chain = hire(w);
    contract(w, chain, t.minusDays(90), t.minusDays(61));
    contract(w, chain, t.minusDays(60), t.minusDays(31));
    contract(w, chain, t.minusDays(30), t.plusDays(45));
    expected.put(contract(w, chain, t.plusDays(46), t.plusDays(80)).toString(), "DAYS_61_TO_90:80");

    // A voided successor is ignored.
    UUID voided = hire(w);
    expected.put(
        contract(w, voided, t.minusDays(100), t.plusDays(10)).toString(), "NEXT_30_DAYS:10");
    voidContract(contract(w, voided, t.plusDays(11), null));

    // A voided current contract: the previous non-void contract is the head again.
    UUID replaced = hire(w);
    expected.put(contract(w, replaced, t.minusDays(200), t.minusDays(3)).toString(), "EXPIRED:-3");
    voidContract(contract(w, replaced, t.minusDays(2), t.plusDays(400)));

    // Acknowledgement does not change eligibility.
    UUID acknowledged = hire(w);
    UUID ack = contract(w, acknowledged, t.minusDays(30), t.plusDays(60));
    acknowledge(w, acknowledged, ack);
    expected.put(ack.toString(), "DAYS_31_TO_60:60");

    // Open-ended, beyond the window, and no contract at all: no warning.
    contract(w, hire(w), t.minusDays(10), null);
    contract(w, hire(w), t.minusDays(10), t.plusDays(91));
    hire(w);
    UUID edge = hire(w);
    expected.put(contract(w, edge, t.minusDays(10), t.plusDays(90)).toString(), "DAYS_61_TO_90:90");

    // Rehire: the ended first employment raises nothing; the second employment warns once.
    UUID rehired = hire(w);
    contract(w, rehired, t.minusDays(200), t.minusDays(31));
    separate(w, rehired, t.minusDays(31));
    UUID second = rehire(w, rehired, t.minusDays(20));
    expected.put(
        contract(w, rehired, second, t.minusDays(20), t.plusDays(30)).toString(),
        "NEXT_30_DAYS:30");

    JsonNode page = page(w, Map.of("limit", 50));
    assertThat(byContract(page)).isEqualTo(expected);
    assertThat(page.get("asOf").asText()).isEqualTo(t.toString());
    assertThat(page.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    // At most one row per employment, most urgent first.
    List<String> employees = new ArrayList<>();
    List<LocalDate> ends = new ArrayList<>();
    page.get("items")
        .forEach(
            item -> {
              employees.add(item.get("employeeId").asText());
              ends.add(LocalDate.parse(item.get("endDate").asText()));
            });
    assertThat(Set.copyOf(employees)).hasSize(employees.size());
    assertThat(ends).isSorted();
    JsonNode counts = page.get("counts");
    assertThat(counts.get("expired").asLong()).isEqualTo(2);
    assertThat(counts.get("next30Days").asLong()).isEqualTo(4);
    assertThat(counts.get("days31To60").asLong()).isEqualTo(2);
    assertThat(counts.get("days61To90").asLong()).isEqualTo(2);
    assertThat(counts.get("total").asLong()).isEqualTo(10);
    assertThat(summary(w).get("counts")).isEqualTo(counts);

    // A row carries the unit and the employee's label, nothing else.
    JsonNode item = page.get("items").get(0);
    assertThat(item.get("unit").get("id").asText()).isEqualTo(w.org().departmentId().toString());
    assertThat(item.get("unit").get("kind").asText()).isEqualTo("DEPARTMENT");
    assertThat(item.properties().stream().map(Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder(
            "contractId",
            "employeeId",
            "employeeNumber",
            "givenNames",
            "familyName",
            "unit",
            "endDate",
            "category",
            "daysUntilEnd");
  }

  // ------------------------------------------------------------------------------------------
  // A31A-2: separations
  // ------------------------------------------------------------------------------------------

  @Test
  void aSeparationOnOrBeforeTheHeadsEndSuppressesItAndItsCancellationRestoresIt() throws Exception {
    World w = world();
    LocalDate t = w.today();
    UUID onEnd = hire(w);
    contract(w, onEnd, t.minusDays(100), t.plusDays(20));
    UUID before = hire(w);
    contract(w, before, t.minusDays(100), t.plusDays(20));
    UUID after = hire(w);
    UUID afterContract = contract(w, after, t.minusDays(100), t.plusDays(20));
    UUID cancelled = hire(w);
    UUID cancelledContract = contract(w, cancelled, t.minusDays(100), t.plusDays(20));
    UUID past = hire(w);
    contract(w, past, t.minusDays(100), t.plusDays(20));

    separate(w, onEnd, t.plusDays(20));
    separate(w, before, t.plusDays(10));
    separate(w, after, t.plusDays(30));
    String scheduled = separate(w, cancelled, t.plusDays(5)).get("id").asText();
    separate(w, past, t.minusDays(1));

    assertThat(byContract(page(w, Map.of())).keySet()).containsExactly(afterContract.toString());

    cancelSeparation(w, cancelled, scheduled);
    assertThat(byContract(page(w, Map.of())).keySet())
        .containsExactlyInAnyOrder(afterContract.toString(), cancelledContract.toString());
  }

  // ------------------------------------------------------------------------------------------
  // Business date: boundaries and time zones
  // ------------------------------------------------------------------------------------------

  @Test
  void categoriesFollowTheOrganizationsBusinessDateBoundariesExactly() throws Exception {
    World w = world();
    // 10:00 in Kinshasa on a fixed day relative to the real date.
    LocalDate t = w.today().minusDays(3);
    clock.set(t.atTime(10, 0).atZone(ZoneId.of("Africa/Kinshasa")).toInstant());
    Map<String, String> expected = new LinkedHashMap<>();
    int[] days = {-1, 0, 30, 31, 60, 61, 90};
    String[] categories = {
      "EXPIRED",
      "NEXT_30_DAYS",
      "NEXT_30_DAYS",
      "DAYS_31_TO_60",
      "DAYS_31_TO_60",
      "DAYS_61_TO_90",
      "DAYS_61_TO_90"
    };
    for (int i = 0; i < days.length; i++) {
      UUID employee = hire(w);
      UUID id = contract(w, employee, t.minusDays(150), t.plusDays(days[i]));
      expected.put(id.toString(), categories[i] + ":" + days[i]);
    }
    contract(w, hire(w), t.minusDays(150), t.plusDays(91));
    JsonNode page = page(w, Map.of());
    assertThat(page.get("asOf").asText()).isEqualTo(t.toString());
    assertThat(byContract(page)).isEqualTo(expected);
  }

  @Test
  void theSameInstantFallsOnDifferentBusinessDatesInTheTwoSupportedZones() throws Exception {
    World kinshasa = world();
    World lubumbashi = world();
    jdbc.update(
        "UPDATE tenant.organization SET timezone = 'Africa/Lubumbashi' WHERE id = ?",
        lubumbashi.org().tenant());
    LocalDate day = kinshasa.today().minusDays(2);
    // 22:30 UTC: 23:30 in Kinshasa (UTC+1, still `day`), 00:30 in Lubumbashi (UTC+2, `day + 1`).
    clock.set(day.atTime(22, 30).toInstant(java.time.ZoneOffset.UTC));
    contract(kinshasa, hire(kinshasa), day.minusDays(100), day);
    contract(lubumbashi, hire(lubumbashi), day.minusDays(100), day);

    JsonNode west = page(kinshasa, Map.of());
    assertThat(west.get("asOf").asText()).isEqualTo(day.toString());
    assertThat(west.get("timezone").asText()).isEqualTo("Africa/Kinshasa");
    assertThat(west.get("items").get(0).get("category").asText()).isEqualTo("NEXT_30_DAYS");
    assertThat(west.get("items").get(0).get("daysUntilEnd").asLong()).isZero();

    JsonNode east = page(lubumbashi, Map.of());
    assertThat(east.get("asOf").asText()).isEqualTo(day.plusDays(1).toString());
    assertThat(east.get("timezone").asText()).isEqualTo("Africa/Lubumbashi");
    assertThat(east.get("items").get(0).get("category").asText()).isEqualTo("EXPIRED");
    assertThat(east.get("items").get(0).get("daysUntilEnd").asLong()).isEqualTo(-1);

    // One minute before local midnight in Lubumbashi the contract ends today there too.
    clock.set(day.atTime(21, 59).toInstant(java.time.ZoneOffset.UTC));
    JsonNode before = page(lubumbashi, Map.of());
    assertThat(before.get("asOf").asText()).isEqualTo(day.toString());
    assertThat(before.get("items").get(0).get("category").asText()).isEqualTo("NEXT_30_DAYS");
  }

  // ------------------------------------------------------------------------------------------
  // A31A-4: pagination
  // ------------------------------------------------------------------------------------------

  @Test
  void pagesNeverRepeatARowKeepTheirBusinessDateAndAdmitNewRowsAfterTheCursor() throws Exception {
    World w = world();
    LocalDate t = w.today();
    List<String> order = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      UUID employee = hire(w);
      order.add(contract(w, employee, t.minusDays(100), t.plusDays(i * 10L)).toString());
    }
    // Exhaustive paging returns the full queue once, in order.
    List<String> paged = new ArrayList<>();
    all(w, Map.of(), 3).forEach(item -> paged.add(item.get("contractId").asText()));
    assertThat(paged).isEqualTo(order);

    JsonNode first = page(w, Map.of("limit", 3));
    String cursor = first.get("nextCursor").asText();
    assertThat(first.get("counts").get("total").asLong()).isEqualTo(7);

    // A new contract sorting after the cursor appears; one sorting before it is not returned
    // later and nothing is repeated.
    UUID late = hire(w);
    String afterCursor = contract(w, late, t.minusDays(100), t.plusDays(65)).toString();
    UUID early = hire(w);
    String beforeCursor = contract(w, early, t.minusDays(100), t.minusDays(1)).toString();

    // The business date and the categories stay pinned even after local midnight.
    clock.set(Instant.now().plusSeconds(86_400));
    List<String> rest = new ArrayList<>();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("limit", 3);
    body.put("cursor", cursor);
    while (true) {
      JsonNode page = page(w, body);
      assertThat(page.get("asOf").asText()).isEqualTo(t.toString());
      page.get("items").forEach(item -> rest.add(item.get("contractId").asText()));
      if (page.get("nextCursor").isNull()) {
        break;
      }
      body.put("cursor", page.get("nextCursor").asText());
    }
    List<String> seen = new ArrayList<>(first.get("items").size());
    first.get("items").forEach(item -> seen.add(item.get("contractId").asText()));
    assertThat(rest).doesNotContainAnyElementsOf(seen).contains(afterCursor);
    assertThat(rest).doesNotContain(beforeCursor);
    assertThat(Set.copyOf(rest)).hasSize(rest.size());
    // A fresh first page uses the new date.
    assertThat(page(w, Map.of()).get("asOf").asText()).isEqualTo(t.plusDays(1).toString());
  }

  @Test
  void aCursorIsBoundToItsTenantAndEveryFilter() throws Exception {
    World w = world();
    LocalDate t = w.today();
    for (int i = 0; i < 3; i++) {
      contract(w, hire(w), t.minusDays(100), t.plusDays(i));
    }
    String cursor = page(w, Map.of("limit", 1)).get("nextCursor").asText();
    problem(search(w.admin(), Map.of("limit", 2, "cursor", cursor)), 400, "CURSOR_INVALID");
    problem(
        search(
            w.admin(),
            Map.of("limit", 1, "cursor", cursor, "unitId", w.org().departmentId().toString())),
        400,
        "CURSOR_INVALID");
    problem(
        search(w.admin(), Map.of("limit", 1, "cursor", cursor, "categories", List.of("EXPIRED"))),
        400,
        "CURSOR_INVALID");
    problem(
        search(w.admin(), Map.of("limit", 1, "cursor", cursor, "query", "Synth")),
        400,
        "CURSOR_INVALID");
    World other = world();
    problem(search(other.admin(), Map.of("limit", 1, "cursor", cursor)), 400, "CURSOR_INVALID");
    problem(search(w.admin(), Map.of("limit", 1, "cursor", cursor + "x")), 400, "CURSOR_INVALID");
    assertThat(expect(search(w.admin(), Map.of("limit", 1, "cursor", cursor)), 200)).isNotNull();
  }

  // ------------------------------------------------------------------------------------------
  // Filters and counts
  // ------------------------------------------------------------------------------------------

  @Test
  void aQueryWithNoSearchableTermMatchesNobodyWhileRealNamesAndNumbersStillMatch()
      throws Exception {
    World w = world();
    LocalDate t = w.today();
    UUID hyphen = hire(w, "Marie-Ève", "N'Dongala");
    UUID period = hire(w, "Jean-Pierre", "Mbuyi-St.Clair");
    UUID apostrophe = hire(w, "Zoé", "D’Almeida");
    String hyphenContract = contract(w, hyphen, t.minusDays(100), t.plusDays(5)).toString();
    String periodContract = contract(w, period, t.minusDays(100), t.plusDays(40)).toString();
    String apostropheContract =
        contract(w, apostrophe, t.minusDays(100), t.minusDays(3)).toString();
    assertThat(summary(w).get("counts").get("total").asLong()).isEqualTo(3);

    // R74-1: punctuation only is a supplied query with no searchable term, never "no query".
    for (String query : List.of("--", "''", "..", "-.'", "’’")) {
      JsonNode page = page(w, Map.of("query", query));
      assertThat(page.get("items")).as(query).isEmpty();
      assertThat(page.get("nextCursor").isNull()).as(query).isTrue();
      JsonNode counts = page.get("counts");
      for (String category : List.of("expired", "next30Days", "days31To60", "days61To90")) {
        assertThat(counts.get(category).asLong()).as(query + " " + category).isZero();
      }
      assertThat(counts.get("total").asLong()).as(query).isZero();
    }

    // Valid searches keep their meaning: number prefixes, accents, apostrophes, periods, hyphens.
    String number =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT employee_number FROM people.employee WHERE id = ?", String.class, hyphen),
            "employee_number");
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(number, hyphenContract);
    expected.put(number.substring(0, number.length() - 2).toLowerCase(Locale.ROOT), null);
    expected.put("marie-ève", hyphenContract);
    expected.put("Marie Eve", hyphenContract);
    expected.put("N'Dongala", hyphenContract);
    expected.put("n’dongala", hyphenContract);
    expected.put("St.Clair", periodContract);
    expected.put("mbuyi-st.clair", periodContract);
    expected.put("zoé d'almeida", apostropheContract);
    expected.put("D’Almeida", apostropheContract);
    for (Map.Entry<String, String> search : expected.entrySet()) {
      JsonNode page = page(w, Map.of("query", search.getKey()));
      if (search.getValue() == null) {
        // A shorter prefix of a random number may match others too; it must include this one.
        assertThat(byContract(page).keySet()).as(search.getKey()).contains(hyphenContract);
      } else {
        assertThat(byContract(page).keySet())
            .as(search.getKey())
            .containsExactly(search.getValue());
        assertThat(page.get("counts").get("total").asLong()).as(search.getKey()).isEqualTo(1);
      }
    }
  }

  @Test
  void countsHonorTheSearchAndUnitAndEqualTheItemsPagedThrough() throws Exception {
    World w = world();
    LocalDate t = w.today();
    UUID named = hire(w, "Zébulon", "Kalala-Mutombo");
    String namedContract = contract(w, named, t.minusDays(100), t.plusDays(5)).toString();
    // Placed in the cost center only (no department).
    String number = Employees.number();
    JsonNode imported =
        EmployeeImports.uploaded(
            mvc,
            w.admin(),
            EmployeeImports.csv(
                List.of(
                    EmployeeImports.FRENCH_HEADER,
                    EmployeeImports.semi(
                        number,
                        "Synthétique",
                        "Centre",
                        HIRED.toString(),
                        w.org().legalEntity(),
                        w.org().site(),
                        "",
                        w.org().costCenter(),
                        ""))));
    expect(
        call(
            EmployeeImports.commit(
                w.admin(),
                imported.get("id").asText(),
                Organizations.newKey(),
                imported.get("previewDigest").asText(),
                1,
                false)),
        200);
    UUID costCenter =
        jdbc.queryForObject(
            "SELECT id FROM people.employee WHERE tenant_id = ? AND employee_number = ?",
            UUID.class,
            w.org().tenant(),
            number);
    String costCenterContract =
        contract(w, costCenter, t.minusDays(100), t.minusDays(2)).toString();
    for (int i = 0; i < 4; i++) {
      contract(w, hire(w), t.minusDays(100), t.plusDays(70 + i));
    }

    JsonNode bySearch = page(w, Map.of("query", "zebulon kalala"));
    assertThat(byContract(bySearch).keySet()).containsExactly(namedContract);
    assertThat(bySearch.get("counts").get("total").asLong()).isEqualTo(1);
    JsonNode byNumber =
        page(
            w,
            Map.of(
                "query",
                jdbc.queryForObject(
                    "SELECT employee_number FROM people.employee WHERE id = ?",
                    String.class,
                    named)));
    assertThat(byContract(byNumber).keySet()).containsExactly(namedContract);

    JsonNode byUnit = page(w, Map.of("unitId", w.org().costCenterId().toString()));
    assertThat(byContract(byUnit).keySet()).containsExactly(costCenterContract);
    assertThat(byUnit.get("items").get(0).get("unit").get("kind").asText())
        .isEqualTo("COST_CENTER");
    assertThat(byUnit.get("counts").get("expired").asLong()).isEqualTo(1);
    assertThat(byUnit.get("counts").get("total").asLong()).isEqualTo(1);

    JsonNode byDepartment = page(w, Map.of("unitId", w.org().departmentId().toString()));
    assertThat(byDepartment.get("counts").get("total").asLong()).isEqualTo(5);

    // The category filter narrows the items; counts keep all four categories.
    JsonNode later = page(w, Map.of("categories", List.of("DAYS_61_TO_90")));
    assertThat(later.get("items")).hasSize(4);
    assertThat(later.get("counts").get("total").asLong()).isEqualTo(6);

    // Unfiltered counts equal the summary and the number of items paged through.
    JsonNode summary = summary(w);
    assertThat(summary.get("counts").get("total").asLong()).isEqualTo(6);
    assertThat(all(w, Map.of(), 2)).hasSize(6);
    assertThat(all(w, Map.of("unitId", w.org().departmentId().toString()), 2)).hasSize(5);

    // A unit of no placement, or of another tenant, matches nothing.
    World other = world();
    JsonNode foreign = page(w, Map.of("unitId", other.org().departmentId().toString()));
    assertThat(foreign.get("items")).isEmpty();
    assertThat(foreign.get("counts").get("total").asLong()).isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Isolation and authorization
  // ------------------------------------------------------------------------------------------

  @Test
  void anotherTenantNeverSeesTheRecordsOrTheCounts() throws Exception {
    World a = world();
    World b = world();
    contract(a, hire(a), a.today().minusDays(100), a.today().plusDays(3));
    contract(a, hire(a), a.today().minusDays(100), a.today().minusDays(3));
    JsonNode seenByB = page(b, Map.of());
    assertThat(seenByB.get("items")).isEmpty();
    assertThat(seenByB.get("counts").get("total").asLong()).isZero();
    assertThat(summary(b).get("counts").get("total").asLong()).isZero();
    assertThat(summary(a).get("counts").get("total").asLong()).isEqualTo(2);
  }

  @Test
  void onlyAVerifiedTenantAdministratorWithMfaAndMembershipIsServed() throws Exception {
    World w = world();
    contract(w, hire(w), w.today().minusDays(10), w.today().plusDays(3));
    UUID tenant = w.org().tenant();
    String employee =
        Hierarchy.bearer(tenant, "sub-expiry-employee-" + UUID.randomUUID(), "employee");
    problem(search(employee, Map.of()), 403, "ACCESS_DENIED");
    problem(call(get(SUMMARY).header("Authorization", employee)), 403, "ACCESS_DENIED");

    String subject = "sub-expiry-nomfa-" + UUID.randomUUID();
    Hierarchy.bearer(tenant, subject, "tenant-admin");
    String noMfa =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject(subject)
                .roles(List.of("tenant-admin"))
                .acr("urn:divalhr:loa:password")
                .build();
    problem(search(noMfa, Map.of()), 403, "MFA_REQUIRED");
    problem(call(get(SUMMARY).header("Authorization", noMfa)), 403, "MFA_REQUIRED");

    String nonMember =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject("sub-expiry-stranger-" + UUID.randomUUID())
                .roles(List.of("tenant-admin"))
                .build();
    problem(search(nonMember, Map.of()), 403, "ACCESS_DENIED");

    String platform =
        "Bearer "
            + TestTokens.token()
                .tenant(tenant)
                .subject("sub-expiry-platform")
                .roles(List.of("platform-admin"))
                .build();
    problem(search(platform, Map.of()), 403, "ACCESS_DENIED");
    problem(call(get(SUMMARY).header("Authorization", platform)), 403, "ACCESS_DENIED");

    assertThat(call(get(SUMMARY)).getResponse().getStatus()).isEqualTo(401);
  }

  // ------------------------------------------------------------------------------------------
  // Validation
  // ------------------------------------------------------------------------------------------

  @Test
  void malformedFiltersAreRejectedFieldByField() throws Exception {
    World w = world();
    Map<Map<String, Object>, String> cases = new LinkedHashMap<>();
    cases.put(Map.of("query", "a"), "query");
    cases.put(Map.of("query", 12), "query");
    cases.put(Map.of("unitId", "not-a-uuid"), "unitId");
    cases.put(Map.of("categories", List.of()), "categories");
    cases.put(Map.of("categories", List.of("SOON")), "categories");
    cases.put(Map.of("categories", List.of("EXPIRED", "EXPIRED")), "categories");
    cases.put(Map.of("limit", 0), "limit");
    cases.put(Map.of("limit", 51), "limit");
    cases.put(Map.of("tenantId", w.org().tenant().toString()), "tenantId");
    for (Map.Entry<Map<String, Object>, String> entry : cases.entrySet()) {
      JsonNode problem = expect(search(w.admin(), entry.getKey()), 400);
      assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
      assertThat(problem.get("params").get("fields").get(0).get("field").asText())
          .as(entry.getKey().toString())
          .isEqualTo(entry.getValue());
    }
  }

  // ------------------------------------------------------------------------------------------
  // A31A-6: audit and privacy
  // ------------------------------------------------------------------------------------------

  @Test
  void readsAreFailClosedDisclosuresAndTheSummaryHashesOnlyItsPayload(CapturedOutput output)
      throws Exception {
    World w = world();
    UUID employee = hire(w, "Clémentine", "Secrète-Mbala");
    String number =
        jdbc.queryForObject(
            "SELECT employee_number FROM people.employee WHERE id = ?", String.class, employee);
    UUID contract = contract(w, employee, w.today().minusDays(10), w.today().plusDays(3));

    JsonNode summary = summary(w);
    JsonNode found = page(w, Map.of("query", "Clementine Secrete"));
    assertThat(found.get("items")).hasSize(1);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT action, resource_type, resource_id, metadata::text AS metadata,"
                + " after_state_sha256 FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action = 'contract-expiration.read' ORDER BY occurred_at",
            w.org().tenant());
    assertThat(rows).hasSize(2);
    for (Map<String, Object> row : rows) {
      assertThat(row.get("resource_type")).isEqualTo("organization");
      assertThat(row.get("resource_id")).isEqualTo(w.org().tenant());
      JsonNode metadata = JSON.readTree((String) row.get("metadata"));
      assertThat(metadata.properties().stream().map(Map.Entry::getKey).toList())
          .containsExactlyInAnyOrder("schemaVersion", "view", "page", "resultCount", "filterKinds");
      String text = row.get("metadata").toString();
      assertThat(text).doesNotContain("Clémentine", "Secrète", number, contract.toString());
    }
    JsonNode summaryAudit = JSON.readTree((String) rows.get(0).get("metadata"));
    assertThat(summaryAudit.get("view").asText()).isEqualTo("summary");
    assertThat(summaryAudit.get("resultCount").asInt()).isZero();
    JsonNode counts = summary.get("counts");
    String payload =
        "DIVALHR-CONTRACT-EXPIRATION-DISCLOSURE\nversion=1\nview=summary\nasOf="
            + summary.get("asOf").asText()
            + "\ntimezone=Africa/Kinshasa\ncounts="
            + counts.get("expired").asLong()
            + ","
            + counts.get("next30Days").asLong()
            + ","
            + counts.get("days31To60").asLong()
            + ","
            + counts.get("days61To90").asLong()
            + "\n";
    assertThat(rows.get(0).get("after_state_sha256")).isEqualTo(Fingerprints.sha256(payload));
    JsonNode searchAudit = JSON.readTree((String) rows.get(1).get("metadata"));
    assertThat(searchAudit.get("view").asText()).isEqualTo("search");
    assertThat(searchAudit.get("resultCount").asInt()).isEqualTo(1);
    assertThat(searchAudit.get("filterKinds").get(0).asText()).isEqualTo("query");

    assertThat(output.getAll())
        .contains("contract_disclosure")
        .doesNotContain("Clémentine")
        .doesNotContain("Secrète")
        .doesNotContain("Clementine Secrete")
        .doesNotContain(number)
        .doesNotContain(contract.toString());

    // Fail closed: when the audit record cannot be written, nothing is returned.
    jdbc.execute(
        "CREATE FUNCTION pg_temp.no_expiration_audit() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN IF NEW.action = 'contract-expiration.read' THEN"
            + " RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER no_expiration_audit BEFORE INSERT ON platform.audit_event"
            + " FOR EACH ROW EXECUTE FUNCTION pg_temp.no_expiration_audit()");
    try {
      MvcResult failed = call(get(SUMMARY).header("Authorization", w.admin()));
      assertThat(failed.getResponse().getStatus()).isEqualTo(500);
      assertThat(failed.getResponse().getContentAsString()).doesNotContain("\"counts\"");
      MvcResult failedSearch = search(w.admin(), Map.of());
      assertThat(failedSearch.getResponse().getStatus()).isEqualTo(500);
      assertThat(failedSearch.getResponse().getContentAsString())
          .doesNotContain(contract.toString());
    } finally {
      jdbc.execute("DROP TRIGGER no_expiration_audit ON platform.audit_event");
    }
  }
}
