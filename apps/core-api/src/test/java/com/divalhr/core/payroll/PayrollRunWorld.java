package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-094A test fixtures over the public API: an organization (through {@link CompensationWorld}),
 * hired employees with an established compensation, a payroll policy, and the preview, create and
 * read calls of payroll runs. All data is synthetic.
 */
final class PayrollRunWorld {

  final CompensationWorld c;
  final MockMvc mvc;
  final JdbcTemplate jdbc;
  final String admin;
  final UUID tenant;
  final UUID entity;
  final LocalDate today;
  final YearMonth month;

  /**
   * Freezes the clock on the 15th of the current month, so that the previous month is always a
   * closed month whose first day is at most 46 days back (inside the compensation back-dating
   * window of 60 days).
   */
  PayrollRunWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups, SettableClock clock)
      throws Exception {
    clock.set(YearMonth.now(ZoneOffset.UTC).atDay(15).atTime(10, 0).toInstant(ZoneOffset.UTC));
    this.c = new CompensationWorld(mvc, jdbc, lookups);
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.admin = c.admin;
    this.tenant = c.tenant();
    this.entity = c.org.legalEntityId();
    this.today = YearMonth.now(ZoneOffset.UTC).atDay(15);
    this.month = YearMonth.from(today).minusMonths(1);
  }

  // ------------------------------------------------------------------------------------------
  // Paths and requests
  // ------------------------------------------------------------------------------------------

  static String policyPath(Object entity) {
    return "/api/v1/legal-entities/" + entity + "/payroll-policy";
  }

  static String runsPath(Object entity) {
    return "/api/v1/legal-entities/" + entity + "/payroll-runs";
  }

  String policyPath() {
    return policyPath(entity);
  }

  String runsPath() {
    return runsPath(entity);
  }

  static Map<String, Object> policyCommand(
      LocalDate from, String payDayKind, Integer payDay, String method) {
    Map<String, Object> day = new LinkedHashMap<>();
    day.put("kind", payDayKind);
    if (payDay != null) {
      day.put("day", payDay);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("effectiveFrom", from.toString());
    body.put("payDay", day);
    body.put("prorationMethod", method);
    return body;
  }

  static Map<String, Object> policyCommitting(Map<String, Object> command, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("version").asInt());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("confirmed", true);
    return body;
  }

  static Map<String, Object> runCommand(YearMonth period, String currency) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("period", period.toString());
    body.put("currency", currency);
    return body;
  }

  static Map<String, Object> runCommitting(Map<String, Object> command, JsonNode preview) {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asInt());
    body.put("inputDigest", preview.get("inputDigest").asText());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("expiresAt", preview.get("expiresAt").asText());
    body.put("confirmed", true);
    return body;
  }

  static MockHttpServletRequestBuilder get(String bearer, String path) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
        .header("Authorization", bearer);
  }

  MockHttpServletRequestBuilder policyPreviewRequest(Map<String, Object> command) throws Exception {
    return CompensationWorld.json(policyPath() + "/preview", admin, null, command);
  }

  MockHttpServletRequestBuilder policyCommitRequest(String key, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(policyPath(), admin, key, body);
  }

  MockHttpServletRequestBuilder runPreviewRequest(Map<String, Object> command) throws Exception {
    return CompensationWorld.json(runsPath() + "/preview", admin, null, command);
  }

  MockHttpServletRequestBuilder runCommitRequest(String key, Map<String, Object> body)
      throws Exception {
    return CompensationWorld.json(runsPath(), admin, key, body);
  }

  // ------------------------------------------------------------------------------------------
  // Flows
  // ------------------------------------------------------------------------------------------

  /** Records a policy effective from the first day of the previous-previous month. */
  JsonNode policy(String method) throws Exception {
    return policy(month.minusMonths(1).atDay(1), "LAST_DAY", null, method);
  }

  JsonNode policy(LocalDate from, String kind, Integer day, String method) throws Exception {
    Map<String, Object> command = policyCommand(from, kind, day, method);
    JsonNode preview = c.expect(policyPreviewRequest(command), 200);
    assertThat(preview.get("blockers")).isEmpty();
    return c.expect(
        policyCommitRequest(Organizations.newKey(), policyCommitting(command, preview)), 201);
  }

  /** A hired employee; the start is the given date. */
  UUID hire(String family, LocalDate start) throws Exception {
    return Employees.hire(mvc, jdbc, c.org, admin, Employees.number(), "Test", family, start);
  }

  /** Establishes a compensation effective from {@code from}, acknowledging when asked. */
  JsonNode compensate(UUID employee, LocalDate from, String currency, String amount)
      throws Exception {
    Map<String, Object> command = CompensationWorld.command("ESTABLISHED", from, currency, amount);
    JsonNode preview = c.preview(employee, command);
    assertThat(preview.get("blockers")).isEmpty();
    return c.expect(
        CompensationWorld.commitRequest(
            admin,
            employee,
            Organizations.newKey(),
            CompensationWorld.committing(
                command, preview, preview.get("requiresAcknowledgement").asBoolean())),
        201);
  }

  /** Corrects the established compensation (same date, other amount). */
  JsonNode correct(UUID employee, String changeId, LocalDate from, String currency, String amount)
      throws Exception {
    Map<String, Object> command =
        CompensationWorld.correction(from, currency, amount, UUID.fromString(changeId));
    JsonNode preview = c.preview(employee, command);
    return c.expect(
        CompensationWorld.commitRequest(
            admin,
            employee,
            Organizations.newKey(),
            CompensationWorld.committing(command, preview, true)),
        201);
  }

  /** A hired employee with a compensation from the hire date. */
  UUID paid(String family, LocalDate start, String currency, String amount) throws Exception {
    UUID employee = hire(family, start);
    compensate(employee, start, currency, amount);
    return employee;
  }

  JsonNode runPreview(String currency) throws Exception {
    return c.expect(runPreviewRequest(runCommand(month, currency)), 200);
  }

  /** Previews and creates a run of the month; returns the receipt. */
  JsonNode createRun(String currency) throws Exception {
    Map<String, Object> command = runCommand(month, currency);
    JsonNode preview = c.expect(runPreviewRequest(command), 200);
    return c.expect(runCommitRequest(Organizations.newKey(), runCommitting(command, preview)), 201);
  }

  JsonNode line(JsonNode preview, UUID employee) {
    for (JsonNode line : preview.get("lines")) {
      if (line.get("employeeId").asText().equals(employee.toString())) {
        return line;
      }
    }
    throw new AssertionError("no line for the employee");
  }

  /** Moves the employee to another legal entity of the tenant from {@code from}. */
  UUID moveElsewhere(UUID employee, LocalDate from) throws Exception {
    UUID other = Hierarchy.newLegalEntity(mvc, tenant, Hierarchy.code("LE"), "2020-01-01", null);
    UUID site = Hierarchy.newSite(mvc, tenant, other, Hierarchy.code("ST"), "2020-01-01", null);
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("legalEntityId", other.toString());
    placement.put("siteId", site.toString());
    changePlacement(employee, from, placement);
    return other;
  }

  private void changePlacement(UUID employee, LocalDate from, Map<String, Object> placement)
      throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("placement", placement);
    command.put("reasonCode", "REORGANIZATION");
    JsonNode preview =
        c.expect(
            Employees.postJson(
                admin,
                CompensationWorld.EMPLOYEES + "/" + employee + "/employment-changes/preview",
                CompensationWorld.JSON.writeValueAsString(command)),
            200);
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", preview.get("requiresAcknowledgement").asBoolean());
    c.expect(
        Employees.postJson(
                admin,
                CompensationWorld.EMPLOYEES + "/" + employee + "/employment-changes",
                CompensationWorld.JSON.writeValueAsString(body))
            .header("Idempotency-Key", Organizations.newKey()),
        201);
  }

  /** A retroactive separation (the employment ends on {@code lastDay}, access immediately). */
  JsonNode separate(UUID employee, LocalDate lastDay) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("lastDay", lastDay.toString());
    command.put("reasonCode", "RESIGNATION");
    command.put("accessTiming", "IMMEDIATELY");
    String path = CompensationWorld.EMPLOYEES + "/" + employee + "/separations";
    JsonNode preview =
        c.expect(
            Employees.postJson(
                admin, path + "/preview", CompensationWorld.JSON.writeValueAsString(command)),
            200);
    List<String> acknowledgements = new java.util.ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acknowledgements.add(a.asText()));
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgements", acknowledgements);
    return c.expect(
        Employees.postJson(admin, path, CompensationWorld.JSON.writeValueAsString(body))
            .header("Idempotency-Key", Organizations.newKey()),
        201);
  }

  /** Moves the employee to a cost center of the same legal entity from {@code from}. */
  void regroup(UUID employee, LocalDate from) throws Exception {
    Map<String, Object> placement = new LinkedHashMap<>();
    placement.put("legalEntityId", c.org.legalEntityId().toString());
    placement.put("siteId", c.org.siteId().toString());
    placement.put("costCenterId", c.org.costCenterId().toString());
    changePlacement(employee, from, placement);
  }

  // ------------------------------------------------------------------------------------------
  // Evidence
  // ------------------------------------------------------------------------------------------

  /** Runs, lines, policies, audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        c.count("SELECT count(*) FROM payroll.pay_run WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.pay_run_line WHERE tenant_id = ?", tenant),
        c.count("SELECT count(*) FROM payroll.run_policy WHERE tenant_id = ?", tenant),
        c.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action IN ('payroll-policy.change', 'payroll-run.create')",
            tenant),
        c.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payroll-%'",
            tenant));
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        jdbc.queryForList(
            "SELECT metadata::text || coalesce(after_state_sha256, '')"
                + " FROM platform.audit_event WHERE tenant_id = ?"
                + " AND (action LIKE 'payroll-policy.%' OR action LIKE 'payroll-run.%')",
            String.class, tenant);
    rows.addAll(
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'payroll.payroll-%'",
            String.class, tenant));
    rows.addAll(
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE (operation LIKE 'payroll-policy.%' OR operation LIKE 'payroll-run.%')"
                + " AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }
}
