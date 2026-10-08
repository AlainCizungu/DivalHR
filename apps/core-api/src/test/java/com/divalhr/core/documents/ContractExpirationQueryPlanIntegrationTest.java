package com.divalhr.core.documents;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.documents.domain.ContractDigests;
import com.divalhr.core.documents.domain.ExpirationCategory;
import com.divalhr.core.documents.internal.ContractExpirationStatements;
import com.divalhr.core.documents.internal.JdbcContractExpirationRepository;
import com.divalhr.core.platform.access.EmploymentExpirationScope;
import com.divalhr.core.platform.access.EmploymentExpirationScope.RelevantEmployment;
import com.divalhr.core.platform.tenancy.TenantId;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MVP-031A (Issue #73, A31A-5): the executed plans of the queue's real statements over a realistic
 * tenant (5,000 employments, about 12,000 contracts, analyzed). The coverage index is used, {@code
 * documents.contract} is never scanned sequentially, and no sort spills to disk. The coverage head
 * is one pass of windows over the index order, so contract rows are sorted only by the final
 * bounded top-N of a page (the counts sort none); the one other sort orders the parameter list of
 * employment IDs, in memory, for the merge join.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractExpirationQueryPlanIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int EMPLOYMENTS = 5_000;
  private static final String INDEX = "contract_coverage_order";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NamedParameterJdbcTemplate named;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EmploymentExpirationScope people;

  private TenantId tenant;
  private LocalDate today;
  private List<RelevantEmployment> scope;

  @BeforeAll
  void seed() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String admin =
        Hierarchy.bearer(org.tenant(), "sub-plan-admin-" + UUID.randomUUID(), "tenant-admin");
    tenant = new TenantId(org.tenant());
    today = Employees.today(jdbc, org.tenant());
    UUID[] fixed = template(admin, "FIXED_TERM");
    UUID[] open = template(admin, "PERMANENT");
    LocalDate hired = today.minusDays(800);
    new TransactionTemplate(transactions).executeWithoutResult(status -> seedPeople(org, hired));
    seedContracts(org.tenant(), fixed, open);
    jdbc.execute("VACUUM ANALYZE documents.contract");
    jdbc.execute("VACUUM ANALYZE documents.contract_employment_guard");
    scope = people.relevant(tenant, today, EmploymentExpirationScope.Filter.NONE);
    assertThat(scope).hasSize(EMPLOYMENTS);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents.contract WHERE tenant_id = ?",
                Long.class,
                org.tenant()))
        .isEqualTo(12_000L);
  }

  private UUID[] template(String admin, String type) throws Exception {
    JsonNode template =
        post(
            admin,
            "/api/v1/contract-templates",
            Map.of("code", Hierarchy.code("CT"), "name", "Plan " + type, "contractType", type),
            201);
    String id = template.get("id").asText();
    JsonNode draft =
        post(
            admin,
            "/api/v1/contract-templates/" + id + "/versions",
            Map.of("locale", "fr", "title", "Contrat", "body", "# Contrat\nTexte."),
            201);
    JsonNode approved =
        post(
            admin,
            "/api/v1/contract-templates/"
                + id
                + "/versions/"
                + draft.get("id").asText()
                + "/approve",
            Map.of(
                "expectedVersion",
                draft.get("version").asLong(),
                "acknowledgements",
                List.of("TEXT_VERIFIED")),
            200);
    return new UUID[] {UUID.fromString(id), UUID.fromString(approved.get("id").asText())};
  }

  private JsonNode post(String bearer, String path, Object body, int status) throws Exception {
    var response =
        mvc.perform(
                postJson(bearer, path, JSON.writeValueAsString(body))
                    .header("Idempotency-Key", Organizations.newKey()))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    return Employees.json(response.getContentAsByteArray());
  }

  /** People rows set-based, in one transaction (placement coverage is checked at commit). */
  private void seedPeople(Org org, LocalDate hired) {
    jdbc.execute(
        "CREATE TEMP TABLE plan_seed ON COMMIT DROP AS SELECT g, gen_random_uuid() AS employee,"
            + " gen_random_uuid() AS employment, gen_random_uuid() AS change"
            + " FROM generate_series(1, "
            + EMPLOYMENTS
            + ") g");
    jdbc.update(
        "INSERT INTO people.employee (id, tenant_id, employee_number, given_names, family_name,"
            + " created_at, created_by, search_key) SELECT employee, ?, 'PLAN' || lpad(g::text, 6,"
            + " '0'), 'Plan', 'Employé', now(), 'sub-test', ' plan employe ' FROM plan_seed",
        org.tenant());
    jdbc.update(
        "INSERT INTO people.employment (id, tenant_id, employee_id, effective_from, created_at,"
            + " created_by) SELECT employment, ?, employee, ?, now(), 'sub-test' FROM plan_seed",
        org.tenant(),
        hired);
    jdbc.update(
        "INSERT INTO people.employment_change (id, tenant_id, employee_id, employment_id, type,"
            + " effective_from, kinds, recorded_at, recorded_by, version_after) SELECT change, ?,"
            + " employee, employment, 'HIRE', ?, ARRAY['PLACEMENT'], now(), 'sub-test', 0"
            + " FROM plan_seed",
        org.tenant(),
        hired);
    jdbc.update(
        "INSERT INTO people.employment_assignment (id, tenant_id, employee_id, employment_id,"
            + " kind, effective_from, legal_entity_id, site_id, department_id,"
            + " created_by_change_id, origin_change_id) SELECT gen_random_uuid(), ?, employee,"
            + " employment, 'PLACEMENT', ?, ?, ?, ?, change, change FROM plan_seed",
        org.tenant(),
        hired,
        org.legalEntityId(),
        org.siteId(),
        org.departmentId());
  }

  /**
   * Contracts per employment, by {@code g % 5}: a fixed term then an open-ended one after a gap; a
   * chain of three fixed terms; an expired term with a future one after a gap; two fixed terms with
   * a gap; a voided term then a contiguous chain of two (12,000 contracts in all).
   */
  private void seedContracts(UUID tenantId, UUID[] fixed, UUID[] open) {
    jdbc.update(
        "INSERT INTO documents.contract_employment_guard (tenant_id, employment_id)"
            + " SELECT tenant_id, id FROM people.employment WHERE tenant_id = ?",
        tenantId);
    String insert =
        "INSERT INTO documents.contract (id, tenant_id, employee_id, employment_id, template_id,"
            + " template_version_id, contract_type, locale, start_date, end_date, snapshot,"
            + " snapshot_canonical, snapshot_sha256, digest_version, grammar_version,"
            + " renderer_version, state, issued_at, issued_by)"
            + " SELECT gen_random_uuid(), e.tenant_id, e.employee_id, e.id, ?, ?, ?, 'fr',"
            + " CAST(? AS date) + s.start_offset, CAST(? AS date) + s.end_offset, '{}'::jsonb,"
            + " '{}', ?, 1, 1,"
            + " 1, 'ISSUED', now(), 'sub-test'"
            + " FROM (SELECT em.*, row_number() OVER (ORDER BY em.id) AS g"
            + " FROM people.employment em WHERE em.tenant_id = ?) e"
            + " CROSS JOIN LATERAL (VALUES %s) AS s(start_offset, end_offset)"
            + " WHERE e.g %% 5 = ?";
    shape(insert, tenantId, fixed, 0, "(-600, -401)");
    shape(insert, tenantId, open, 0, "(-400, NULL::int)");
    shape(insert, tenantId, fixed, 1, "(-600, -401), (-400, -200), (-199, (e.g % 120)::int)");
    shape(insert, tenantId, fixed, 2, "(-300, -1 - (e.g % 60)::int), (20, 200)");
    shape(insert, tenantId, fixed, 3, "(-300, -150), (-100, (e.g % 200)::int - 20)");
    shape(insert, tenantId, fixed, 4, "(-500, -301), (-300, -101), (-100, (e.g % 95)::int)");
    // Contracts are issued, then voided (as the void command does).
    jdbc.update(
        "UPDATE documents.contract SET state = 'VOID', void_reason = 'ISSUED_IN_ERROR',"
            + " voided_at = now(), voided_by = 'sub-test', version = version + 1"
            + " WHERE tenant_id = ? AND start_date = ?",
        tenantId,
        today.minusDays(500));
  }

  private void shape(String insert, UUID tenantId, UUID[] template, int remainder, String rows) {
    jdbc.update(
        String.format(insert, rows),
        template[0],
        template[1],
        contractType(template),
        today,
        today,
        ContractDigests.snapshot("{}"),
        tenantId,
        remainder);
  }

  private String contractType(UUID[] template) {
    return jdbc.queryForObject(
        "SELECT contract_type FROM documents.contract_template WHERE id = ?",
        String.class,
        template[0]);
  }

  private MapSqlParameterSource params() {
    String[] ids = new String[scope.size()];
    String[] lastDays = new String[scope.size()];
    for (int i = 0; i < scope.size(); i++) {
      ids[i] = scope.get(i).employmentId().toString();
      lastDays[i] = scope.get(i).lastDay() == null ? null : scope.get(i).lastDay().toString();
    }
    return new MapSqlParameterSource()
        .addValue("tenant", tenant.value())
        .addValue("asOf", today)
        .addValue("employmentIds", ids)
        .addValue("lastDays", lastDays)
        .addValue("limit", 51);
  }

  private JsonNode plan(String sql, MapSqlParameterSource params) throws Exception {
    String json =
        named.queryForObject(
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, params, String.class);
    if (System.getProperty("plan.dump") != null) {
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(System.getProperty("plan.dump")),
          json + "\n",
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    }
    return JSON.readTree(json).get(0).get("Plan");
  }

  private static void nodes(JsonNode plan, List<JsonNode> out) {
    out.add(plan);
    if (plan.has("Plans")) {
      plan.get("Plans").forEach(child -> nodes(child, out));
    }
  }

  private static boolean readsContracts(JsonNode plan) {
    List<JsonNode> below = new ArrayList<>();
    nodes(plan, below);
    return below.stream().anyMatch(n -> n.path("Relation Name").asText().equals("contract"));
  }

  /** The A31A-5 assertions over one executed plan. */
  private static void assertBounded(JsonNode plan) {
    List<JsonNode> all = new ArrayList<>();
    nodes(plan, all);
    assertThat(all)
        .as("the coverage index is used")
        .anyMatch(n -> INDEX.equals(n.path("Index Name").asText()));
    assertThat(all)
        .as("documents.contract is never read sequentially")
        .noneMatch(
            n ->
                n.path("Node Type").asText().equals("Seq Scan")
                    && n.path("Relation Name").asText().equals("contract"));
    assertThat(all)
        .as("no sort spills to disk")
        .noneMatch(n -> n.path("Sort Space Type").asText().equals("Disk"));
    assertThat(all)
        .as("contract rows are sorted only by the final bounded top-N")
        .filteredOn(n -> n.path("Node Type").asText().equals("Sort") && readsContracts(n))
        .allMatch(n -> n.path("Sort Method").asText().equals("top-N heapsort"));
    assertThat(all)
        .as("the coverage windows read the index in order: no sort feeds a window")
        .noneMatch(
            n ->
                n.path("Node Type").asText().equals("WindowAgg")
                    && n.path("Plans").get(0).path("Node Type").asText().equals("Sort"));
  }

  @Test
  void theFirstPageUsesTheCoverageIndexWithoutSequentialScansOrSpills() throws Exception {
    assertBounded(
        plan(
            ContractExpirationStatements.page(EnumSet.allOf(ExpirationCategory.class), false),
            params()));
  }

  @Test
  void aContinuedFilteredPageIsBoundedTheSameWay() throws Exception {
    MapSqlParameterSource params =
        params().addValue("afterEnd", today.minusDays(5)).addValue("afterId", new UUID(0, 0));
    assertBounded(
        plan(
            ContractExpirationStatements.page(
                EnumSet.of(ExpirationCategory.EXPIRED, ExpirationCategory.DAYS_61_TO_90), true),
            params));
  }

  @Test
  void theCountsAreBoundedTheSameWay() throws Exception {
    assertBounded(plan(ContractExpirationStatements.COUNTS, params()));
  }

  @Test
  void theSeedExercisesEveryCategory() {
    var counts = new JdbcContractExpirationRepository(named).counts(tenant, scope, today);
    assertThat(counts.expired()).isPositive();
    assertThat(counts.next30Days()).isPositive();
    assertThat(counts.days31To60()).isPositive();
    assertThat(counts.days61To90()).isPositive();
  }
}
