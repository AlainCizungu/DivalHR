package com.divalhr.core.people;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-021 (issue #47, H1-H20, M21-1..M21-5): the employee directory and employment history end to
 * end against PostgreSQL. Marker names, numbers, dates, unit and manager IDs, reason codes and
 * categories are seeded in every flow and must never reach audit metadata, outbox data or logs.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class EmploymentHistoryIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String GIVEN = "Élodie Marie";
  private static final String FAMILY = "N’Kanza-Mbuyi";
  private static final String GIVEN_2 = "Jean-Pierre";
  private static final String FAMILY_2 = "O'Neil";
  private static final String CACHE = "private, no-store";
  private static final String BASE = "/api/v1/employees";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;

  private static String admin(Org org, String suffix) {
    return Hierarchy.bearer(
        org.tenant(), "sub-history-" + suffix + "-" + UUID.randomUUID(), "tenant-admin");
  }

  private static JsonNode json(MvcResult result) throws Exception {
    return Employees.json(result.getResponse().getContentAsByteArray());
  }

  private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
    return json(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", CACHE))
            .andReturn());
  }

  private static MockHttpServletRequestBuilder read(String bearer, String path) {
    return get(path).header("Authorization", bearer);
  }

  private static Map<String, Object> placement(Org org, String unitField, UUID unitId) {
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("legalEntityId", org.legalEntityId().toString());
    placement.put("siteId", org.siteId().toString());
    if (unitField != null) {
      placement.put(unitField, unitId.toString());
    }
    return placement;
  }

  private static Map<String, Object> change(LocalDate from) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    return command;
  }

  private MvcResult preview(String bearer, UUID employee, Map<String, Object> command)
      throws Exception {
    return mvc.perform(
            postJson(
                bearer,
                BASE + "/" + employee + "/employment-changes/preview",
                JSON.writeValueAsString(command)))
        .andReturn();
  }

  private static MockHttpServletRequestBuilder commit(
      String bearer,
      UUID employee,
      String key,
      Map<String, Object> command,
      JsonNode preview,
      boolean acknowledge)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", acknowledge);
    return postJson(
            bearer, BASE + "/" + employee + "/employment-changes", JSON.writeValueAsString(body))
        .header("Idempotency-Key", key);
  }

  /** Previews then records a change as previewed; returns the recorded change. */
  private JsonNode record(String bearer, UUID employee, Map<String, Object> command)
      throws Exception {
    MvcResult previewed = preview(bearer, employee, command);
    assertThat(previewed.getResponse().getStatus())
        .as(previewed.getResponse().getContentAsString())
        .isEqualTo(200);
    JsonNode preview = json(previewed);
    MvcResult recorded =
        mvc.perform(
                commit(
                    bearer,
                    employee,
                    Organizations.newKey(),
                    command,
                    preview,
                    preview.get("requiresAcknowledgement").asBoolean()))
            .andReturn();
    assertThat(recorded.getResponse().getStatus())
        .as(recorded.getResponse().getContentAsString())
        .isEqualTo(201);
    return json(recorded).get("change");
  }

  private JsonNode cancelPreview(String bearer, UUID employee, String changeId) throws Exception {
    return ok(
        postJson(
            bearer,
            BASE + "/" + employee + "/employment-changes/" + changeId + "/cancel/preview",
            ""));
  }

  private MvcResult cancel(String bearer, UUID employee, String changeId, JsonNode preview)
      throws Exception {
    return mvc.perform(
            postJson(
                    bearer,
                    BASE + "/" + employee + "/employment-changes/" + changeId + "/cancel",
                    "{\"expectedVersion\":"
                        + preview.get("expectedVersion").asLong()
                        + ",\"cancellationDigest\":\""
                        + preview.get("cancellationDigest").asText()
                        + "\"}")
                .header("Idempotency-Key", Organizations.newKey()))
        .andReturn();
  }

  /** Active rows of one kind: [from, to|null, value summary] per row. */
  private List<List<String>> active(String bearer, UUID employee, String kind) throws Exception {
    JsonNode page = ok(read(bearer, BASE + "/" + employee + "/timeline?kind=" + kind));
    List<List<String>> rows = new ArrayList<>();
    for (JsonNode row : page.get("items")) {
      String value =
          switch (kind) {
            case "PLACEMENT" -> placementOf(row.get("placement"));
            case "MANAGER" -> row.get("manager").get("employeeId").asText();
            case "CONTRACT" -> row.get("contractClassification").asText();
            default -> row.get("compensationBasis").asText();
          };
      rows.add(
          List.of(
              row.get("effectiveFrom").asText(),
              row.get("effectiveTo").isNull() ? "-" : row.get("effectiveTo").asText(),
              value));
    }
    return rows;
  }

  private static String placementOf(JsonNode placement) {
    for (String unit : List.of("team", "department", "costCenter")) {
      if (!placement.get(unit).isNull()) {
        return unit + ":" + placement.get(unit).get("id").asText();
      }
    }
    return "site";
  }

  private static void assertProblem(MvcResult result, int status, String code) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(status);
    assertThat(json(result).get("code").asText()).isEqualTo(code);
  }

  private List<Map<String, Object>> audits(UUID tenant, String action) {
    return jdbc.queryForList(
        "SELECT resource_type, resource_id, metadata::text AS metadata, after_state_sha256"
            + " FROM platform.audit_event WHERE tenant_id = ? AND action = ? ORDER BY occurred_at",
        tenant,
        action);
  }

  private TreeSet<String> keys(Object metadata) throws Exception {
    TreeSet<String> keys = new TreeSet<>();
    JSON.readTree(metadata.toString()).fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  // ------------------------------------------------------------------------------------------
  // Directory, search, profile, timeline and changes (H13-H16, M21-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void readsAreAuditedDisclosuresWithoutSearchTextOrEmployeeData(CapturedOutput output)
      throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "directory");
    LocalDate today = Employees.today(jdbc, org.tenant());
    String n1 = Employees.number();
    String n2 = Employees.number();
    UUID e1 = Employees.hire(mvc, jdbc, org, bearer, n1, GIVEN, FAMILY, today.minusDays(30));
    UUID e2 = Employees.hire(mvc, jdbc, org, bearer, n2, GIVEN_2, FAMILY_2, today.plusDays(5));

    // Directory, by employee number, one per page.
    JsonNode first = ok(read(bearer, BASE + "?limit=1"));
    assertThat(first.get("items")).hasSize(1);
    String cursor = first.get("nextCursor").asText();
    JsonNode second = ok(read(bearer, BASE + "?limit=1&cursor=" + cursor));
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    List<String> listed =
        List.of(
            first.get("items").get(0).get("id").asText(),
            second.get("items").get(0).get("id").asText());
    assertThat(listed).containsExactlyInAnyOrder(e1.toString(), e2.toString());
    // A cursor is bound to its page size.
    assertProblem(
        mvc.perform(read(bearer, BASE + "?limit=2&cursor=" + cursor)).andReturn(),
        400,
        "CURSOR_INVALID");

    // Search: accents, apostrophes and hyphens ignored; word prefixes; employee-number prefix.
    for (String query : List.of("elodie nkanza", "ÉLO N'KAN", "nkanza mbu")) {
      JsonNode found = ok(postJson(bearer, BASE + "/search", "{\"query\":\"" + query + "\"}"));
      assertThat(found.get("items"))
          .singleElement()
          .satisfies(item -> assertThat(item.get("id").asText()).isEqualTo(e1.toString()));
    }
    JsonNode byNumber =
        ok(
            postJson(
                bearer,
                BASE + "/search",
                "{\"query\":\"" + n2.substring(0, 6).toLowerCase() + "\"}"));
    assertThat(byNumber.get("items"))
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.get("id").asText()).isEqualTo(e2.toString());
              assertThat(item.get("employmentStatus").asText()).isEqualTo("NOT_STARTED");
            });
    assertThat(ok(postJson(bearer, BASE + "/search", "{\"query\":\"oneil\"}")).get("items"))
        .hasSize(1);
    MvcResult tooShort =
        mvc.perform(postJson(bearer, BASE + "/search", "{\"query\":\" a \"}")).andReturn();
    assertProblem(tooShort, 400, "VALIDATION_FAILED");
    assertThat(tooShort.getResponse().getContentAsString()).doesNotContain("\" a \"");

    // Profile on the business date.
    JsonNode profile = ok(read(bearer, BASE + "/" + e1));
    assertThat(profile.get("businessDate").asText()).isEqualTo(today.toString());
    assertThat(profile.get("givenNames").asText()).isEqualTo(GIVEN);
    assertThat(profile.get("employment").get("status").asText()).isEqualTo("CURRENT");
    assertThat(profile.get("employment").get("version").asLong()).isZero();
    JsonNode placed = profile.get("current").get("placement");
    assertThat(placed.get("placement").get("department").get("code").asText())
        .isEqualTo(org.department());
    assertThat(placed.get("status").asText()).isEqualTo("CURRENT");
    assertThat(profile.get("current").get("manager").isNull()).isTrue();

    // Timeline and changes: the hire.
    JsonNode timeline = ok(read(bearer, BASE + "/" + e1 + "/timeline"));
    assertThat(timeline.get("items")).hasSize(1);
    JsonNode changes = ok(read(bearer, BASE + "/" + e1 + "/employment-changes"));
    assertThat(changes.get("items"))
        .singleElement()
        .satisfies(
            hire -> {
              assertThat(hire.get("type").asText()).isEqualTo("HIRE");
              assertThat(hire.get("timing").isNull()).isTrue();
              assertThat(hire.get("state").asText()).isEqualTo("ACTIVE");
            });

    // Unknown, malformed and foreign employees are indistinguishable.
    Org other = EmployeeImports.newOrg(mvc);
    for (MockHttpServletRequestBuilder missing :
        List.of(
            read(bearer, BASE + "/" + UUID.randomUUID()),
            read(bearer, BASE + "/not-a-uuid"),
            read(admin(other, "foreign"), BASE + "/" + e1),
            read(admin(other, "foreign"), BASE + "/" + e1 + "/timeline"))) {
      assertProblem(mvc.perform(missing).andReturn(), 404, "EMPLOYEE_NOT_FOUND");
    }
    assertThat(
            ok(postJson(admin(other, "foreign"), BASE + "/search", "{\"query\":\"elodie\"}"))
                .get("items"))
        .isEmpty();

    // M21-1: disclosure metadata is exactly {view, page, resultCount, schemaVersion}.
    for (String action :
        List.of(
            "employee.list",
            "employee.search",
            "employee.read",
            "employee.timeline",
            "employment-change.list")) {
      List<Map<String, Object>> rows = audits(org.tenant(), action);
      assertThat(rows).as(action).isNotEmpty();
      for (Map<String, Object> row : rows) {
        assertThat(keys(row.get("metadata")))
            .containsExactly("page", "resultCount", "schemaVersion", "view");
        assertThat(row.get("after_state_sha256").toString()).matches("^[0-9a-f]{64}$");
      }
    }
    assertThat(audits(org.tenant(), "employee.search").get(0).get("resource_type"))
        .isEqualTo("organization");
    String evidence =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND (action LIKE 'employee.%' OR action LIKE 'employment-change.%')",
                String.class, org.tenant()));
    for (String text : List.of(evidence, output.getAll())) {
      assertThat(text)
          .doesNotContain(GIVEN)
          .doesNotContain("Élodie")
          .doesNotContain("elodie")
          .doesNotContain("nkanza")
          .doesNotContain(FAMILY)
          .doesNotContain(n1)
          .doesNotContain(n2)
          .doesNotContain(org.departmentId().toString())
          .doesNotContain(org.siteId().toString());
    }
    assertThat(evidence).doesNotContain(e1.toString()).doesNotContain(e2.toString());
  }

  // ------------------------------------------------------------------------------------------
  // Scheduled change: preview, version, digest, replay, audit and outbox (H2, H9, H12, M21-1)
  // ------------------------------------------------------------------------------------------

  @Test
  void aScheduledChangeIsBoundToItsPreviewAndPublishedWithIdentifiersOnly(CapturedOutput output)
      throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "schedule");
    LocalDate today = Employees.today(jdbc, org.tenant());
    String number = Employees.number();
    LocalDate start = today.minusDays(30);
    UUID employee = Employees.hire(mvc, jdbc, org, bearer, number, GIVEN, FAMILY, start);
    LocalDate day = today.plusDays(17);

    Map<String, Object> command = change(day);
    command.put("placement", placement(org, "costCenterId", org.costCenterId()));
    command.put("contractClassification", "FIXED_TERM");
    command.put("reasonCode", "REORGANIZATION");
    JsonNode preview = json(preview(bearer, employee, command));
    assertThat(preview.get("timing").asText()).isEqualTo("SCHEDULED");
    assertThat(preview.get("requiresReason").asBoolean()).isFalse();
    assertThat(preview.get("requiresAcknowledgement").asBoolean()).isFalse();
    assertThat(preview.get("expectedVersion").asLong()).isZero();
    assertThat(preview.get("kinds")).hasSize(2);
    JsonNode placementKind = preview.get("kinds").get(0);
    assertThat(placementKind.get("kind").asText()).isEqualTo("PLACEMENT");
    assertThat(placementKind.get("before")).hasSize(1);
    assertThat(placementKind.get("after")).hasSize(2);
    assertThat(placementKind.get("after").get(0).get("effectiveTo").asText())
        .isEqualTo(day.minusDays(1).toString());
    assertThat(
            placementKind
                .get("after")
                .get(1)
                .get("placement")
                .get("costCenter")
                .get("code")
                .asText())
        .isEqualTo(org.costCenter());

    // Stale version and a digest that is not the preview's: nothing is written.
    Map<String, Object> stale = new LinkedHashMap<>(command);
    ObjectMapper copy = new ObjectMapper();
    JsonNode staleVersion =
        copy.readTree(
            "{\"expectedVersion\":7,\"previewDigest\":\""
                + preview.get("previewDigest").asText()
                + "\"}");
    assertProblem(
        mvc.perform(commit(bearer, employee, Organizations.newKey(), stale, staleVersion, false))
            .andReturn(),
        409,
        "EMPLOYMENT_VERSION_CONFLICT");
    JsonNode wrongDigest =
        copy.readTree("{\"expectedVersion\":0,\"previewDigest\":\"" + "0".repeat(64) + "\"}");
    assertProblem(
        mvc.perform(commit(bearer, employee, Organizations.newKey(), stale, wrongDigest, false))
            .andReturn(),
        409,
        "EMPLOYMENT_PREVIEW_CHANGED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employment_change WHERE employee_id = ?",
                Integer.class,
                employee))
        .isEqualTo(1);

    // Commit, then replay with the same key.
    String key = Organizations.newKey();
    MvcResult created =
        mvc.perform(commit(bearer, employee, key, command, preview, false))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", CACHE))
            .andReturn();
    JsonNode result = json(created);
    String changeId = result.get("change").get("id").asText();
    assertThat(result.get("employmentVersion").asLong()).isEqualTo(1);
    assertThat(result.get("change").get("kinds").toString())
        .isEqualTo("[\"PLACEMENT\",\"CONTRACT\"]");
    MvcResult replayed =
        mvc.perform(commit(bearer, employee, key, command, preview, false))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn();
    assertThat(json(replayed)).isEqualTo(result);

    // The earlier part is kept as a copy; nothing is updated in place.
    assertThat(active(bearer, employee, "PLACEMENT"))
        .containsExactly(
            List.of(
                start.toString(), day.minusDays(1).toString(), "department:" + org.departmentId()),
            List.of(day.toString(), "-", "costCenter:" + org.costCenterId()));
    assertThat(active(bearer, employee, "CONTRACT"))
        .containsExactly(List.of(day.toString(), "-", "FIXED_TERM"));
    JsonNode all = ok(read(bearer, BASE + "/" + employee + "/timeline?includeSuperseded=true"));
    assertThat(all.get("items")).hasSize(4);
    // The same command again changes nothing any more.
    assertProblem(preview(bearer, employee, command), 422, "EMPLOYMENT_CHANGE_NO_EFFECT");

    // M21-1: write audit and outbox carry counts and identifiers only.
    List<Map<String, Object>> audits = audits(org.tenant(), "employment-change.schedule");
    assertThat(audits)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("resource_type")).isEqualTo("employment-change");
              assertThat(row.get("resource_id").toString()).isEqualTo(changeId);
            });
    JsonNode metadata = JSON.readTree(audits.get(0).get("metadata").toString());
    assertThat(metadata)
        .isEqualTo(
            JSON.readTree(
                "{\"schemaVersion\":1,\"rowsSuperseded\":1,\"rowsCreated\":3,"
                    + "\"employmentVersion\":1}"));
    List<String> outbox =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type = 'people.employment.changed.v1'",
            String.class,
            org.tenant());
    assertThat(outbox).hasSize(1);
    JsonNode envelope = JSON.readTree(outbox.get(0));
    assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
    TreeSet<String> dataKeys = new TreeSet<>();
    envelope.get("data").fieldNames().forEachRemaining(dataKeys::add);
    assertThat(dataKeys).containsExactly("changeId", "employeeId", "employmentId");
    assertThat(envelope.get("data").get("changeId").asText()).isEqualTo(changeId);

    String evidence =
        String.join(
            "\n",
            jdbc.queryForList(
                "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND (action LIKE 'employee.%' OR action LIKE 'employment-change.%')",
                String.class, org.tenant()));
    for (String text : List.of(evidence, envelope.get("data").toString(), output.getAll())) {
      assertThat(text)
          .doesNotContain(GIVEN)
          .doesNotContain(FAMILY)
          .doesNotContain(number)
          .doesNotContain(day.toString())
          .doesNotContain(start.toString())
          .doesNotContain("FIXED_TERM")
          .doesNotContain("REORGANIZATION")
          .doesNotContain(org.costCenterId().toString())
          .doesNotContain(org.departmentId().toString());
    }
  }

  // ------------------------------------------------------------------------------------------
  // Retroactive changes and corrections (H7, H8, H11)
  // ------------------------------------------------------------------------------------------

  @Test
  void retroactiveChangesNeedAReasonAnAcknowledgementAndTheWindow() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "retro");
    LocalDate today = Employees.today(jdbc, org.tenant());
    UUID employee =
        Employees.hire(
            mvc, jdbc, org, bearer, Employees.number(), GIVEN, FAMILY, today.minusDays(90));
    LocalDate day = today.minusDays(10);

    Map<String, Object> command = change(day);
    command.put("contractClassification", "PERMANENT");
    MvcResult noReason = preview(bearer, employee, command);
    assertProblem(noReason, 400, "VALIDATION_FAILED");
    assertThat(noReason.getResponse().getContentAsString()).contains("reasonCode");

    command.put("reasonCode", "LATE_NOTIFICATION");
    JsonNode preview = json(preview(bearer, employee, command));
    assertThat(preview.get("timing").asText()).isEqualTo("RETROACTIVE");
    assertThat(preview.get("requiresReason").asBoolean()).isTrue();
    assertThat(preview.get("requiresAcknowledgement").asBoolean()).isTrue();
    MvcResult unacknowledged =
        mvc.perform(commit(bearer, employee, Organizations.newKey(), command, preview, false))
            .andReturn();
    assertProblem(unacknowledged, 400, "VALIDATION_FAILED");
    assertThat(unacknowledged.getResponse().getContentAsString())
        .contains("acknowledgeRetroactive");
    mvc.perform(commit(bearer, employee, Organizations.newKey(), command, preview, true))
        .andExpect(status().isCreated());
    assertThat(audits(org.tenant(), "employment-change.apply")).hasSize(1);

    // The window (60 days by default), the date already taken, and an unchanged value.
    Map<String, Object> tooOld = change(today.minusDays(61));
    tooOld.put("contractClassification", "INTERNSHIP");
    tooOld.put("reasonCode", "LATE_NOTIFICATION");
    assertProblem(preview(bearer, employee, tooOld), 422, "RETROACTIVE_WINDOW_EXCEEDED");
    Map<String, Object> taken = change(day);
    taken.put("contractClassification", "INTERNSHIP");
    taken.put("reasonCode", "LATE_NOTIFICATION");
    assertProblem(preview(bearer, employee, taken), 409, "EMPLOYMENT_CHANGE_DATE_TAKEN");
    Map<String, Object> same = change(today.plusDays(3));
    same.put("contractClassification", "PERMANENT");
    MvcResult noEffect = preview(bearer, employee, same);
    assertProblem(noEffect, 422, "EMPLOYMENT_CHANGE_NO_EFFECT");
    assertThat(json(noEffect).get("params").get("field").asText()).isEqualTo("CONTRACT");
    Map<String, Object> before = change(today.minusDays(91));
    before.put("contractClassification", "INTERNSHIP");
    before.put("reasonCode", "LATE_NOTIFICATION");
    assertProblem(preview(bearer, employee, before), 422, "RETROACTIVE_WINDOW_EXCEEDED");

    // A correction replaces one row's value for the same dates and keeps the replaced row.
    JsonNode contract =
        ok(read(bearer, BASE + "/" + employee + "/timeline?kind=CONTRACT")).get("items").get(0);
    Map<String, Object> correction = new LinkedHashMap<>();
    correction.put("type", "CORRECTION");
    correction.put("effectiveFrom", day.toString());
    correction.put("correctsAssignmentId", contract.get("id").asText());
    correction.put("contractClassification", "APPRENTICESHIP");
    correction.put("reasonCode", "DOCUMENT_RECEIVED");
    JsonNode corrected = record(bearer, employee, correction);
    assertThat(corrected.get("type").asText()).isEqualTo("CORRECTION");
    assertThat(active(bearer, employee, "CONTRACT"))
        .containsExactly(List.of(day.toString(), "-", "APPRENTICESHIP"));
    JsonNode all =
        ok(read(bearer, BASE + "/" + employee + "/timeline?kind=CONTRACT&includeSuperseded=true"));
    assertThat(all.get("items")).hasSize(2);
    assertThat(all.get("items"))
        .filteredOn(row -> !row.get("supersededAt").isNull())
        .singleElement()
        .satisfies(
            row -> assertThat(row.get("id").asText()).isEqualTo(contract.get("id").asText()));
    assertThat(audits(org.tenant(), "employment-change.correct")).hasSize(1);
    // A correction carries a correction reason; a wrong start date is refused.
    correction.put("correctsAssignmentId", contract.get("id").asText());
    assertProblem(preview(bearer, employee, correction), 400, "VALIDATION_FAILED");
    // Corrections are never cancellable.
    assertProblem(
        mvc.perform(
                postJson(
                    bearer,
                    BASE
                        + "/"
                        + employee
                        + "/employment-changes/"
                        + corrected.get("id").asText()
                        + "/cancel/preview",
                    ""))
            .andReturn(),
        409,
        "EMPLOYMENT_CHANGE_NOT_CANCELLABLE");
  }

  // ------------------------------------------------------------------------------------------
  // Managers (H5, H6)
  // ------------------------------------------------------------------------------------------

  @Test
  void managersAreSameTenantEmployeesWithoutLoops() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "manager");
    LocalDate today = Employees.today(jdbc, org.tenant());
    LocalDate start = today.minusDays(20);
    UUID e1 = Employees.hire(mvc, jdbc, org, bearer, Employees.number(), GIVEN, FAMILY, start);
    UUID e2 = Employees.hire(mvc, jdbc, org, bearer, Employees.number(), GIVEN_2, FAMILY_2, start);
    Org other = EmployeeImports.newOrg(mvc);
    UUID foreign =
        Employees.hire(
            mvc, jdbc, other, admin(other, "x"), Employees.number(), GIVEN, FAMILY, start);

    for (Map.Entry<UUID, String> invalid :
        Map.of(e1, "SELF", UUID.randomUUID(), "NOT_FOUND", foreign, "NOT_FOUND").entrySet()) {
      Map<String, Object> command = change(today);
      command.put("manager", Map.of("employeeId", invalid.getKey().toString()));
      MvcResult refused = preview(bearer, e1, command);
      assertProblem(refused, 422, "MANAGER_INVALID");
      assertThat(json(refused).get("params").get("reason").asText()).isEqualTo(invalid.getValue());
    }

    Map<String, Object> reportsTo = change(today);
    reportsTo.put("manager", Map.of("employeeId", e2.toString()));
    assertThat(record(bearer, e1, reportsTo).get("timing").asText()).isEqualTo("CURRENT");
    Map<String, Object> loop = change(today.plusDays(1));
    loop.put("manager", Map.of("employeeId", e1.toString()));
    MvcResult cycle = preview(bearer, e2, loop);
    assertProblem(cycle, 422, "MANAGER_INVALID");
    assertThat(json(cycle).get("params").get("reason").asText()).isEqualTo("CYCLE");

    JsonNode profile = ok(read(bearer, BASE + "/" + e1));
    JsonNode manager = profile.get("current").get("manager").get("manager");
    assertThat(manager.get("employeeId").asText()).isEqualTo(e2.toString());
    assertThat(manager.get("familyName").asText()).isEqualTo(FAMILY_2);

    // Ending the reporting line from a date.
    Map<String, Object> end = change(today.plusDays(5));
    Map<String, Object> cleared = new LinkedHashMap<>();
    cleared.put("employeeId", null);
    end.put("manager", cleared);
    record(bearer, e1, end);
    assertThat(active(bearer, e1, "MANAGER"))
        .containsExactly(List.of(today.toString(), today.plusDays(4).toString(), e2.toString()));
    // After the line ended, the loop no longer exists from that date.
    Map<String, Object> later = change(today.plusDays(5));
    later.put("manager", Map.of("employeeId", e1.toString()));
    assertThat(preview(bearer, e2, later).getResponse().getStatus()).isEqualTo(200);
  }

  // ------------------------------------------------------------------------------------------
  // Cancellation (M21-2)
  // ------------------------------------------------------------------------------------------

  @Test
  void cancellingRestoresTheReplacedValueAndNeverAltersLaterChanges() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "cancel");
    LocalDate today = Employees.today(jdbc, org.tenant());
    LocalDate start = today.minusDays(30);
    UUID employee =
        Employees.hire(mvc, jdbc, org, bearer, Employees.number(), GIVEN, FAMILY, start);
    String a = "department:" + org.departmentId();
    String c = "team:" + org.teamId();
    LocalDate dayB = today.plusDays(10);
    LocalDate dayC = today.plusDays(20);

    Map<String, Object> toB = change(dayB);
    toB.put("placement", placement(org, "costCenterId", org.costCenterId()));
    String changeB = record(bearer, employee, toB).get("id").asText();
    Map<String, Object> toC = change(dayC);
    Map<String, Object> team = placement(org, "teamId", org.teamId());
    toC.put("placement", team);
    String changeC = record(bearer, employee, toC).get("id").asText();
    String rowC =
        ok(read(bearer, BASE + "/" + employee + "/timeline?kind=PLACEMENT"))
            .get("items")
            .get(2)
            .get("id")
            .asText();

    // Cancelling B: A is restored up to the day before C; C is untouched.
    JsonNode preview = cancelPreview(bearer, employee, changeB);
    assertThat(preview.get("kinds"))
        .singleElement()
        .satisfies(
            kind -> {
              assertThat(kind.get("before")).hasSize(2);
              assertThat(kind.get("after"))
                  .singleElement()
                  .satisfies(
                      row -> {
                        assertThat(row.get("effectiveFrom").asText()).isEqualTo(start.toString());
                        assertThat(row.get("effectiveTo").asText())
                            .isEqualTo(dayC.minusDays(1).toString());
                        assertThat(row.get("placement").get("department").get("id").asText())
                            .isEqualTo(org.departmentId().toString());
                      });
            });
    MvcResult cancelled = cancel(bearer, employee, changeB, preview);
    assertThat(cancelled.getResponse().getStatus())
        .as(cancelled.getResponse().getContentAsString())
        .isEqualTo(200);
    JsonNode cancellation = json(cancelled).get("change");
    assertThat(cancellation.get("type").asText()).isEqualTo("CANCELLATION");
    assertThat(cancellation.get("cancelsChangeId").asText()).isEqualTo(changeB);
    assertThat(cancellation.get("timing").asText()).isEqualTo("SCHEDULED");
    assertThat(active(bearer, employee, "PLACEMENT"))
        .containsExactly(
            List.of(start.toString(), dayC.minusDays(1).toString(), a),
            List.of(dayC.toString(), "-", c));
    assertThat(
            ok(read(bearer, BASE + "/" + employee + "/timeline?kind=PLACEMENT"))
                .get("items")
                .get(1)
                .get("id")
                .asText())
        .isEqualTo(rowC);
    JsonNode changes = ok(read(bearer, BASE + "/" + employee + "/employment-changes"));
    for (JsonNode item : changes.get("items")) {
      if (item.get("id").asText().equals(changeB)) {
        assertThat(item.get("state").asText()).isEqualTo("CANCELLED");
      }
    }
    // The same change again, the hire, and an unknown change.
    assertProblem(
        mvc.perform(
                postJson(
                    bearer,
                    BASE + "/" + employee + "/employment-changes/" + changeB + "/cancel/preview",
                    ""))
            .andReturn(),
        409,
        "EMPLOYMENT_CHANGE_NOT_CANCELLABLE");
    String hire =
        jdbc.queryForObject(
            "SELECT id::text FROM people.employment_change WHERE employee_id = ? AND type ="
                + " 'HIRE'",
            String.class,
            employee);
    for (String id : List.of(hire)) {
      assertProblem(
          mvc.perform(
                  postJson(
                      bearer,
                      BASE + "/" + employee + "/employment-changes/" + id + "/cancel/preview",
                      ""))
              .andReturn(),
          409,
          "EMPLOYMENT_CHANGE_NOT_CANCELLABLE");
    }
    assertProblem(
        mvc.perform(
                postJson(
                    bearer,
                    BASE
                        + "/"
                        + employee
                        + "/employment-changes/"
                        + UUID.randomUUID()
                        + "/cancel/preview",
                    ""))
            .andReturn(),
        404,
        "EMPLOYMENT_CHANGE_NOT_FOUND");

    // Cancelling C restores A to the end.
    JsonNode previewC = cancelPreview(bearer, employee, changeC);
    assertThat(cancel(bearer, employee, changeC, previewC).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(active(bearer, employee, "PLACEMENT"))
        .containsExactly(List.of(start.toString(), "-", a));

    // Audit and outbox: the cancelled change is named; nothing else.
    List<Map<String, Object>> audits = audits(org.tenant(), "employment-change.cancel");
    assertThat(audits).hasSize(2);
    for (Map<String, Object> row : audits) {
      assertThat(keys(row.get("metadata")))
          .containsExactly("employmentVersion", "rowsCreated", "rowsSuperseded", "schemaVersion");
    }
    List<String> published =
        jdbc.queryForList(
            "SELECT envelope -> 'data' ->> 'changeId' FROM platform.outbox_event"
                + " WHERE tenant_id = ? AND event_type = 'people.employment.change-cancelled.v1'"
                + " ORDER BY created_at",
            String.class,
            org.tenant());
    assertThat(published).containsExactly(changeB, changeC);
    // Every row any change wrote is kept (lineage).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employment_assignment WHERE employee_id = ?",
                Integer.class,
                employee))
        .isEqualTo(7);
  }

  @Test
  void aChangeWithDependentsIsNotCancelledAndSeveralKindsCancelTogether() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "dependents");
    LocalDate today = Employees.today(jdbc, org.tenant());
    LocalDate start = today.minusDays(30);
    UUID employee =
        Employees.hire(mvc, jdbc, org, bearer, Employees.number(), GIVEN, FAMILY, start);

    Map<String, Object> later = change(today.plusDays(20));
    later.put("placement", placement(org, "costCenterId", org.costCenterId()));
    String laterId = record(bearer, employee, later).get("id").asText();
    Map<String, Object> earlier = change(today.plusDays(10));
    earlier.put("placement", placement(org, "teamId", org.teamId()));
    record(bearer, employee, earlier);
    assertProblem(
        mvc.perform(
                postJson(
                    bearer,
                    BASE + "/" + employee + "/employment-changes/" + laterId + "/cancel/preview",
                    ""))
            .andReturn(),
        409,
        "EMPLOYMENT_CHANGE_HAS_DEPENDENTS");

    Map<String, Object> both = change(today.plusDays(30));
    both.put("placement", placement(org, "departmentId", org.departmentId()));
    both.put("compensationBasis", "MONTHLY");
    String bothId = record(bearer, employee, both).get("id").asText();
    JsonNode preview = cancelPreview(bearer, employee, bothId);
    assertThat(preview.get("kinds")).hasSize(2);
    assertThat(cancel(bearer, employee, bothId, preview).getResponse().getStatus()).isEqualTo(200);
    assertThat(active(bearer, employee, "COMPENSATION")).isEmpty();
    assertThat(active(bearer, employee, "PLACEMENT")).hasSize(3);
  }

  @Test
  void aConcurrentCancellationAndChangeCommitAtMostOne() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "race");
    LocalDate today = Employees.today(jdbc, org.tenant());
    UUID employee =
        Employees.hire(
            mvc, jdbc, org, bearer, Employees.number(), GIVEN, FAMILY, today.minusDays(30));
    Map<String, Object> scheduled = change(today.plusDays(10));
    scheduled.put("placement", placement(org, "costCenterId", org.costCenterId()));
    String scheduledId = record(bearer, employee, scheduled).get("id").asText();

    JsonNode cancelPreview = cancelPreview(bearer, employee, scheduledId);
    Map<String, Object> next = change(today.plusDays(15));
    next.put("contractClassification", "PERMANENT");
    JsonNode changePreview = json(preview(bearer, employee, next));

    CountDownLatch ready = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Integer>> calls =
          List.of(
              () -> {
                ready.await();
                return cancel(bearer, employee, scheduledId, cancelPreview)
                    .getResponse()
                    .getStatus();
              },
              () -> {
                ready.await();
                return mvc.perform(
                        commit(
                            bearer, employee, Organizations.newKey(), next, changePreview, false))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> call : calls) {
        futures.add(pool.submit(call));
      }
      ready.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : futures) {
        statuses.add(future.get());
      }
      assertThat(statuses.stream().filter(s -> s / 100 == 2)).hasSize(1);
      assertThat(statuses.stream().filter(s -> s == 409)).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM people.employment WHERE employee_id = ?",
                Long.class,
                employee))
        .isEqualTo(2);
    mvc.perform(read(bearer, BASE + "/" + employee))
        .andExpect(jsonPath("$.employment.version").value(2));
  }
}
