package com.divalhr.core.operations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.operations.TaskWorld.Person;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP-081 test fixtures over the public API: an organization with a tenant administrator, an
 * employee, the employee's effective manager and other linked employees; expenses created, changed,
 * submitted and decided through the real endpoints. Sentinel texts make leaks searchable.
 */
final class ExpenseWorld {

  static final String MINE = "/api/v1/me/expenses";
  static final String APPROVALS = "/api/v1/me/expense-approvals";
  static final String ADMIN = "/api/v1/expenses";
  static final String MERCHANT = "Sentinelle Marchand Zeta";
  static final String DESCRIPTION = "Sentinelle description des frais zeta";
  static final String FILE_NAME = "sentinelle-recu-zeta.png";
  static final String REASON = "Sentinelle motif de retour zeta";

  final TaskWorld w;
  final LocalDate today;

  ExpenseWorld(MockMvc mvc, JdbcTemplate jdbc, EmailLookup lookups) throws Exception {
    this.w = new TaskWorld(mvc, jdbc, lookups);
    this.today = Employees.today(jdbc, w.tenant());
  }

  Person person(String given, String family) throws Exception {
    return w.person(given, family);
  }

  /** The report's MANAGER line from {@code from} on, through the employment-change API. */
  void manage(UUID report, UUID manager, LocalDate from) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("manager", Map.of("employeeId", manager.toString()));
    if (from.isBefore(today)) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    change(report, command);
  }

  /** Clears the report's MANAGER line from {@code from} on. */
  void unmanage(UUID report, LocalDate from) throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    Map<String, Object> none = new LinkedHashMap<>();
    none.put("employeeId", null);
    command.put("manager", none);
    if (from.isBefore(today)) {
      command.put("reasonCode", "LATE_NOTIFICATION");
    }
    change(report, command);
  }

  private void change(UUID report, Map<String, Object> command) throws Exception {
    JsonNode previewed =
        w.expect(
            Employees.postJson(
                w.admin,
                "/api/v1/employees/" + report + "/employment-changes/preview",
                TaskWorld.JSON.writeValueAsString(command)),
            200);
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", previewed.get("expectedVersion").asLong());
    body.put("previewDigest", previewed.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", previewed.get("requiresAcknowledgement").asBoolean());
    w.expect(
        Employees.postJson(
                w.admin,
                "/api/v1/employees/" + report + "/employment-changes",
                TaskWorld.JSON.writeValueAsString(body))
            .header("Idempotency-Key", Organizations.newKey()),
        201);
  }

  // ------------------------------------------------------------------------------------------
  // Bodies and requests
  // ------------------------------------------------------------------------------------------

  String daysAgo(int days) {
    return today.minusDays(days).toString();
  }

  /** A valid USD draft body dated {@code days} ago. */
  Map<String, Object> usd(String amount, int days) {
    return body("USD", amount, days);
  }

  /** A valid CDF draft body dated {@code days} ago. */
  Map<String, Object> cdf(String amount, int days) {
    return body("CDF", amount, days);
  }

  private Map<String, Object> body(String currency, String amount, int days) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("businessDate", daysAgo(days));
    body.put("currency", currency);
    body.put("amount", amount);
    body.put("category", "TRANSPORT");
    body.put("merchant", MERCHANT);
    body.put("description", DESCRIPTION);
    return body;
  }

  static MockHttpServletRequestBuilder post(Person p, String path, String key, Object body)
      throws Exception {
    return TaskWorld.json(path, p.bearer(), key, body);
  }

  static Map<String, Object> version(int version) {
    return Map.of("expectedVersion", version);
  }

  static Map<String, Object> withVersion(Map<String, Object> body, int version) {
    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.put("expectedVersion", version);
    return copy;
  }

  static MockHttpServletRequestBuilder upload(
      Person p, String id, int version, String contentType, byte[] bytes, String name, String key) {
    MockHttpServletRequestBuilder request =
        put(MINE + "/" + id + "/receipt")
            .header("Authorization", p.bearer())
            .header("X-Expected-Version", Integer.toString(version))
            .contentType(contentType)
            .content(bytes);
    if (name != null) {
      request.header("X-Receipt-File-Name", URLEncoder.encode(name, StandardCharsets.UTF_8));
    }
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder removeReceipt(Person p, String id, int version, String key) {
    MockHttpServletRequestBuilder request =
        delete(MINE + "/" + id + "/receipt")
            .header("Authorization", p.bearer())
            .header("X-Expected-Version", Integer.toString(version));
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  static MockHttpServletRequestBuilder read(String bearer, String path) {
    return get(path).header("Authorization", bearer);
  }

  // ------------------------------------------------------------------------------------------
  // Steps
  // ------------------------------------------------------------------------------------------

  /** Creates a draft; its command receipt. */
  JsonNode create(Person p, Map<String, Object> body) throws Exception {
    return w.expect(post(p, MINE, Organizations.newKey(), body), 201);
  }

  JsonNode submit(Person p, String id, int version) throws Exception {
    return w.expect(
        post(p, MINE + "/" + id + "/submit", Organizations.newKey(), version(version)), 200);
  }

  JsonNode withdraw(Person p, String id, int version) throws Exception {
    return w.expect(
        post(p, MINE + "/" + id + "/withdraw", Organizations.newKey(), version(version)), 200);
  }

  JsonNode approve(Person manager, String id, int version) throws Exception {
    return w.expect(
        post(manager, APPROVALS + "/" + id + "/approve", Organizations.newKey(), version(version)),
        200);
  }

  JsonNode returnIt(Person manager, String id, int version, String reason) throws Exception {
    return w.expect(
        post(
            manager,
            APPROVALS + "/" + id + "/return",
            Organizations.newKey(),
            Map.of("expectedVersion", version, "reason", reason)),
        200);
  }

  JsonNode update(Person p, String id, Map<String, Object> body, int version) throws Exception {
    return w.expect(
        post(p, MINE + "/" + id + "/draft", Organizations.newKey(), withVersion(body, version)),
        200);
  }

  JsonNode mine(Person p, String id) throws Exception {
    return w.expect(read(p.bearer(), MINE + "/" + id), 200);
  }

  JsonNode queue(Person manager) throws Exception {
    return w.expect(read(manager.bearer(), APPROVALS), 200);
  }

  JsonNode adminRead(String id) throws Exception {
    return w.expect(read(w.admin, ADMIN + "/" + id), 200);
  }

  List<String> ids(JsonNode page) {
    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
    page.get("items").forEach(item -> ids.add(item.get("id").asText()));
    return ids;
  }

  /** The audit metadata, outbox envelopes and idempotency bodies of the tenant, as one text. */
  String persistedEvidence() {
    List<String> rows =
        w.jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'expense.%'",
            String.class, w.tenant());
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'operations.expense.%'",
            String.class, w.tenant()));
    rows.addAll(
        w.jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record"
                + " WHERE operation LIKE 'expense.%' AND response_body IS NOT NULL",
            String.class));
    return String.join("\n", rows);
  }

  /** Expenses, revisions, receipts, actions, audit rows and events of the tenant. */
  List<Integer> evidence() {
    return List.of(
        w.count("SELECT count(*) FROM operations.expense WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM operations.expense_revision WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM operations.expense_receipt WHERE tenant_id = ?", w.tenant()),
        w.count("SELECT count(*) FROM operations.expense_action WHERE tenant_id = ?", w.tenant()),
        w.count(
            "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?"
                + " AND action LIKE 'expense.%' AND action NOT IN ('expense.list', 'expense.read',"
                + " 'expense.receipt-download')",
            w.tenant()),
        w.count(
            "SELECT count(*) FROM platform.outbox_event WHERE tenant_id = ?"
                + " AND event_type LIKE 'operations.expense.%'",
            w.tenant()));
  }
}
