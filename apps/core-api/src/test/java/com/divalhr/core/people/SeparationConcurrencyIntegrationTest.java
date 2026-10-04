package com.divalhr.core.people;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.identity.application.AccessRevocationJobs;
import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.FakeIdentityDirectory;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * A22-4: every pair of paths that lock the same rows (separation, its cancellation, MVP-021
 * changes, link and unlink, the revocation worker) completes without deadlock and never commits
 * partial state. Each round starts both requests together; whichever wins, the loser gets a stable
 * 4xx (or a retryable 503 where a NOWAIT lock met a worker), never a 500, and the database
 * invariants hold afterwards.
 */
@IntegrationTest
class SeparationConcurrencyIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BASE = "/api/v1/employees";
  private static final int ROUNDS = 3;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private EmailLookup lookups;
  @Autowired private FakeIdentityDirectory provider;
  @Autowired private AccessRevocationJobs revocations;

  @BeforeEach
  void reset() {
    provider.reset();
  }

  private record World(Org org, String admin, LocalDate today) {}

  private World world() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    return new World(
        org,
        Hierarchy.bearer(org.tenant(), "sub-sepc-" + UUID.randomUUID(), "tenant-admin"),
        Employees.today(jdbc, org.tenant()));
  }

  private UUID hire(World w) throws Exception {
    return Employees.hire(
        mvc,
        jdbc,
        w.org(),
        w.admin(),
        Employees.number(),
        "Ana",
        "Mbuyi",
        w.today().minusDays(100));
  }

  private UUID member(World w) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        id,
        w.org().tenant(),
        UUID.randomUUID().toString(),
        lookups.of(
            EmailAddress.parse("c-" + UUID.randomUUID().toString().substring(0, 8) + "@x.cd")
                .orElseThrow()));
    return id;
  }

  private RequestBuilder link(World w, UUID employee, UUID membership) {
    return postJson(
            w.admin(),
            BASE + "/" + employee + "/access-link",
            "{\"membershipId\":\"" + membership + "\"}")
        .header("Idempotency-Key", Organizations.newKey());
  }

  private static Map<String, Object> command(LocalDate lastDay, Map<String, Object> plan) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("lastDay", lastDay.toString());
    command.put("reasonCode", "MUTUAL_AGREEMENT");
    command.put("accessTiming", "END_OF_LAST_DAY");
    if (plan != null) {
      command.put("reportPlan", plan);
    }
    return command;
  }

  /** Previews now and returns the commit request built from that preview. */
  private RequestBuilder separation(World w, UUID employee, Map<String, Object> command)
      throws Exception {
    MvcResult previewed =
        mvc.perform(
                postJson(
                    w.admin(),
                    BASE + "/" + employee + "/separations/preview",
                    JSON.writeValueAsString(command)))
            .andReturn();
    assertThat(previewed.getResponse().getStatus())
        .as(previewed.getResponse().getContentAsString())
        .isEqualTo(200);
    JsonNode preview = Employees.json(previewed.getResponse().getContentAsByteArray());
    List<String> acks = new ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgements", acks);
    return postJson(
            w.admin(), BASE + "/" + employee + "/separations", JSON.writeValueAsString(body))
        .header("Idempotency-Key", Organizations.newKey());
  }

  private RequestBuilder managerChange(World w, UUID employee, LocalDate from, UUID manager)
      throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("manager", Map.of("employeeId", manager.toString()));
    MvcResult previewed =
        mvc.perform(
                postJson(
                    w.admin(),
                    BASE + "/" + employee + "/employment-changes/preview",
                    JSON.writeValueAsString(command)))
            .andReturn();
    assertThat(previewed.getResponse().getStatus())
        .as(previewed.getResponse().getContentAsString())
        .isEqualTo(200);
    JsonNode preview = Employees.json(previewed.getResponse().getContentAsByteArray());
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", false);
    return postJson(
            w.admin(), BASE + "/" + employee + "/employment-changes", JSON.writeValueAsString(body))
        .header("Idempotency-Key", Organizations.newKey());
  }

  /** Runs requests together; returns "status code" per request. */
  private List<String> race(RequestBuilder... requests) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(requests.length);
    try {
      List<Future<String>> outcomes = new ArrayList<>();
      for (RequestBuilder request : requests) {
        Callable<String> run =
            () -> {
              go.await();
              MvcResult result = mvc.perform(request).andReturn();
              int status = result.getResponse().getStatus();
              String body = result.getResponse().getContentAsString();
              String code =
                  body.isEmpty() || status < 400
                      ? ""
                      : Employees.json(result.getResponse().getContentAsByteArray())
                          .path("code")
                          .asText();
              return status + " " + code;
            };
        outcomes.add(pool.submit(run));
      }
      go.countDown();
      List<String> results = new ArrayList<>();
      for (Future<String> outcome : outcomes) {
        results.add(outcome.get(60, TimeUnit.SECONDS));
      }
      assertThat(results).as("no failure, no deadlock").noneMatch(r -> r.startsWith("5"));
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private void invariants(World w) {
    // No active row outside its employment; no reporting line to a manager not employed then.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employment e WHERE e.tenant_id = ?"
                    + " AND NOT people.employment_rows_within(e.id)",
                Integer.class,
                w.org().tenant()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employment_assignment a WHERE a.tenant_id = ?"
                    + " AND a.kind = 'MANAGER' AND a.superseded_by_change_id IS NULL AND NOT"
                    + " people.employment_manager_covered(a.tenant_id, a.manager_employee_id,"
                    + " a.period)",
                Integer.class,
                w.org().tenant()))
        .isZero();
    // A separation exists with its tasks, or not at all (never partial).
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employment_separation s WHERE s.tenant_id = ?"
                    + " AND (SELECT count(*) FROM people.separation_task t"
                    + " WHERE t.separation_id = s.id) <> 2",
                Integer.class,
                w.org().tenant()))
        .isZero();
  }

  @Test
  void twoSeparationsOfOneEmployee() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      World w = world();
      UUID employee = hire(w);
      List<String> results =
          race(
              separation(w, employee, command(w.today().plusDays(5), null)),
              separation(w, employee, command(w.today().plusDays(6), null)));
      assertThat(results).filteredOn(r -> r.startsWith("201")).hasSize(1);
      assertThat(results)
          .filteredOn(r -> !r.startsWith("201"))
          .singleElement()
          .satisfies(
              r ->
                  assertThat(r)
                      .isIn(
                          "409 EMPLOYMENT_VERSION_CONFLICT",
                          "409 SEPARATION_EXISTS",
                          "409 SEPARATION_PREVIEW_CHANGED"));
      invariants(w);
    }
  }

  @Test
  void aSeparationAndAChangeOfAReport() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      World w = world();
      UUID manager = hire(w);
      UUID report = hire(w);
      UUID replacement = hire(w);
      // The report is assigned to the manager later, while the manager is being separated.
      List<String> results =
          race(
              separation(
                  w,
                  manager,
                  command(
                      w.today().plusDays(5),
                      Map.of("action", "REASSIGN", "managerEmployeeId", replacement.toString()))),
              managerChange(w, report, w.today().plusDays(20), manager));
      assertThat(results).anyMatch(r -> r.startsWith("201"));
      invariants(w);
    }
  }

  @Test
  void aSeparationAndALinkOrUnlink() throws Exception {
    for (int round = 0; round < ROUNDS; round++) {
      World w = world();
      UUID employee = hire(w);
      List<String> results =
          race(
              separation(w, employee, command(w.today().plusDays(5), null)),
              link(w, employee, member(w)));
      assertThat(results).filteredOn(r -> r.startsWith("201")).hasSize(1);
      assertThat(results)
          .anyMatch(
              r ->
                  r.equals("409 SEPARATION_PREVIEW_CHANGED") || r.equals("409 ACCESS_LINK_LOCKED"));

      UUID linked = hire(w);
      UUID membership = member(w);
      mvc.perform(link(w, linked, membership)).andReturn();
      String linkId =
          jdbc.queryForObject(
              "SELECT id::text FROM identity.employee_access_link WHERE employee_id = ?",
              String.class,
              linked);
      List<String> unlinkRace =
          race(
              separation(w, linked, command(w.today().plusDays(5), null)),
              postJson(
                      w.admin(),
                      BASE + "/" + linked + "/access-link/remove",
                      "{\"linkId\":\"" + linkId + "\",\"expectedVersion\":0}")
                  .header("Idempotency-Key", Organizations.newKey()));
      assertThat(unlinkRace)
          .anyMatch(
              r ->
                  r.equals("409 SEPARATION_PREVIEW_CHANGED") || r.equals("409 ACCESS_LINK_LOCKED"));
      // Never both: a separated employee's revocation always names an active link.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM identity.access_revocation r JOIN"
                      + " identity.employee_access_link l ON l.id = r.link_id"
                      + " WHERE r.employee_id = ? AND l.unlinked_at IS NOT NULL",
                  Integer.class,
                  linked))
          .isZero();
      invariants(w);
    }
  }

  @Test
  void aCancellationNeverWaitsOnAWorkerAndAWorkerNeverWaitsOnACoordinator() throws Exception {
    World w = world();
    UUID employee = hire(w);
    UUID membership = member(w);
    mvc.perform(link(w, employee, membership)).andReturn();
    MvcResult recorded =
        mvc.perform(separation(w, employee, command(w.today().plusDays(5), null))).andReturn();
    assertThat(recorded.getResponse().getStatus()).isEqualTo(201);
    String sid =
        Employees.json(recorded.getResponse().getContentAsByteArray())
            .get("separation")
            .get("id")
            .asText();
    MvcResult previewed =
        mvc.perform(
                postJson(
                    w.admin(),
                    BASE + "/" + employee + "/separations/" + sid + "/cancel/preview",
                    ""))
            .andReturn();
    JsonNode preview = Employees.json(previewed.getResponse().getContentAsByteArray());
    RequestBuilder cancel =
        postJson(
                w.admin(),
                BASE + "/" + employee + "/separations/" + sid + "/cancel",
                "{\"expectedVersion\":"
                    + preview.get("expectedVersion").asLong()
                    + ",\"cancellationDigest\":\""
                    + preview.get("cancellationDigest").asText()
                    + "\"}")
            .header("Idempotency-Key", Organizations.newKey());

    // A worker holds the revocation row: the cancellation answers a retryable 503 at once.
    try (Connection worker = dataSource.getConnection()) {
      worker.setAutoCommit(false);
      try (PreparedStatement lock =
          worker.prepareStatement(
              "SELECT id FROM identity.access_revocation WHERE separation_id = ?::uuid"
                  + " FOR UPDATE")) {
        lock.setString(1, sid);
        lock.executeQuery().close();
      }
      long started = System.nanoTime();
      MvcResult busy = mvc.perform(cancel).andReturn();
      assertThat(busy.getResponse().getStatus()).isEqualTo(503);
      assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);
      worker.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM people.employment_separation WHERE id = ?::uuid",
                String.class,
                sid))
        .as("nothing partial")
        .isEqualTo("SCHEDULED");
    MvcResult done =
        mvc.perform(
                postJson(
                        w.admin(),
                        BASE + "/" + employee + "/separations/" + sid + "/cancel",
                        "{\"expectedVersion\":"
                            + preview.get("expectedVersion").asLong()
                            + ",\"cancellationDigest\":\""
                            + preview.get("cancellationDigest").asText()
                            + "\"}")
                    .header("Idempotency-Key", Organizations.newKey()))
            .andReturn();
    assertThat(done.getResponse().getStatus())
        .as(done.getResponse().getContentAsString())
        .isEqualTo(200);

    // A coordinator holds a link: the worker skips the revocation (NOWAIT) and calls nobody.
    UUID other = hire(w);
    UUID otherMembership = member(w);
    mvc.perform(link(w, other, otherMembership)).andReturn();
    Map<String, Object> immediate = command(w.today(), null);
    immediate.put("accessTiming", "IMMEDIATELY");
    provider.mode(FakeIdentityDirectory.Mode.DOWN);
    assertThat(mvc.perform(separation(w, other, immediate)).andReturn().getResponse().getStatus())
        .isEqualTo(201);
    provider.mode(FakeIdentityDirectory.Mode.UP);
    String subject =
        jdbc.queryForObject(
            "SELECT subject FROM identity.tenant_membership WHERE id = ?",
            String.class,
            otherMembership);
    try (Connection coordinator = dataSource.getConnection()) {
      coordinator.setAutoCommit(false);
      try (PreparedStatement lock =
          coordinator.prepareStatement(
              "SELECT id FROM identity.employee_access_link WHERE employee_id = ? FOR UPDATE")) {
        lock.setObject(1, other);
        lock.executeQuery().close();
      }
      revocations.revokePending();
      assertThat(provider.revocations()).noneMatch(r -> r.subject().equals(subject));
      coordinator.rollback();
    }
    // Two workers at once: one provider call.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Integer>> runs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        runs.add(
            pool.submit(
                () -> {
                  go.await();
                  return revocations.revokePending();
                }));
      }
      go.countDown();
      for (Future<Integer> run : runs) {
        run.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(provider.revocations()).filteredOn(r -> r.subject().equals(subject)).hasSize(1);
    invariants(w);
  }
}
