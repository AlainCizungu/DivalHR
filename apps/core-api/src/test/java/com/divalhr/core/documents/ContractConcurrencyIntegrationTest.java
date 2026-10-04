package com.divalhr.core.documents;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.divalhr.core.documents.application.ContractIntegrityJob;
import com.divalhr.core.identity.application.EmailLookup;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * MVP-030 concurrency (lock order step 5c) and the read-only integrity job: concurrent issues of
 * one employment never overlap, concurrent acknowledgements record exactly one evidence row, a void
 * racing an acknowledgement leaves one consistent outcome, and a tampered digest is reported.
 */
@IntegrationTest
class ContractConcurrencyIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String BODY =
      "# Contrat\nEntre {{organization.name}} et {{employee.fullName}}.\n- Début : "
          + "{{contract.startDate}}";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private ContractIntegrityJob integrity;
  @Autowired private DataSource dataSource;

  private record World(Org org, String admin, LocalDate today) {}

  private World world() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    return new World(
        org,
        Hierarchy.bearer(org.tenant(), "sub-contract-race-" + UUID.randomUUID(), "tenant-admin"),
        Employees.today(jdbc, org.tenant()));
  }

  private UUID hire(World w) throws Exception {
    return Employees.hire(
        mvc,
        jdbc,
        w.org(),
        w.admin(),
        Employees.number(),
        "Course",
        "Concurrente",
        w.today().minusDays(100));
  }

  private static JsonNode expect(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(status);
    return Employees.json(result.getResponse().getContentAsByteArray());
  }

  private RequestBuilder keyed(String bearer, String path, Object body) throws Exception {
    return postJson(bearer, path, JSON.writeValueAsString(body))
        .header("Idempotency-Key", Organizations.newKey());
  }

  private String approvedVersion(World w) throws Exception {
    JsonNode template =
        expect(
            mvc.perform(
                    keyed(
                        w.admin(),
                        "/api/v1/contract-templates",
                        Map.of(
                            "code",
                            Hierarchy.code("RC"),
                            "name",
                            "Course",
                            "contractType",
                            "PERMANENT")))
                .andReturn(),
            201);
    String id = template.get("id").asText();
    JsonNode draft =
        expect(
            mvc.perform(
                    keyed(
                        w.admin(),
                        "/api/v1/contract-templates/" + id + "/versions",
                        Map.of("locale", "en", "title", "Contract", "body", BODY)))
                .andReturn(),
            201);
    return expect(
            mvc.perform(
                    keyed(
                        w.admin(),
                        "/api/v1/contract-templates/"
                            + id
                            + "/versions/"
                            + draft.get("id").asText()
                            + "/approve",
                        Map.of("expectedVersion", 0, "acknowledgements", List.of("TEXT_VERIFIED"))))
                .andReturn(),
            200)
        .get("id")
        .asText();
  }

  private Map<String, Object> issueBody(World w, UUID employee, String version, LocalDate start)
      throws Exception {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("templateVersionId", version);
    command.put("startDate", start.toString());
    command.put("endDate", null);
    JsonNode preview =
        expect(
            mvc.perform(
                    postJson(
                        w.admin(),
                        "/api/v1/employees/" + employee + "/contracts/preview",
                        JSON.writeValueAsString(command)))
                .andReturn(),
            200);
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedEmploymentVersion", preview.get("employmentVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    return body;
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
              JsonNode body = Employees.json(result.getResponse().getContentAsByteArray());
              return status
                  + " "
                  + (status >= 400
                      ? body.path("code").asText()
                      : body.path("alreadyAcknowledged").asText(""));
            };
        outcomes.add(pool.submit(run));
      }
      go.countDown();
      List<String> results = new ArrayList<>();
      for (Future<String> outcome : outcomes) {
        results.add(outcome.get(60, TimeUnit.SECONDS).strip());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private String linked(World w, UUID employee) throws Exception {
    UUID membership = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, 'employee', ?, now())",
        membership,
        w.org().tenant(),
        subject,
        lookups.of(
            EmailAddress.parse("race-" + subject.substring(0, 8) + "@exemple.cd").orElseThrow()));
    expect(
        mvc.perform(
                keyed(
                    w.admin(),
                    "/api/v1/employees/" + employee + "/access-link",
                    Map.of("membershipId", membership.toString())))
            .andReturn(),
        201);
    return "Bearer "
        + TestTokens.token()
            .tenant(w.org().tenant())
            .subject(subject)
            .roles(List.of("employee"))
            .build();
  }

  @Test
  void concurrentIssuesOfOneEmploymentNeverOverlap() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String version = approvedVersion(w);
    Map<String, Object> body = issueBody(w, employee, version, w.today());
    String path = "/api/v1/employees/" + employee + "/contracts";
    List<String> outcomes =
        race(
            keyed(w.admin(), path, body),
            keyed(w.admin(), path, body),
            keyed(w.admin(), path, body),
            keyed(w.admin(), path, body));
    assertThat(outcomes).containsOnlyOnce("201");
    assertThat(outcomes.stream().filter(o -> !o.equals("201")))
        .allMatch(o -> o.equals("409 CONTRACT_PERIOD_OVERLAP"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents.contract WHERE employee_id = ?",
                Integer.class,
                employee))
        .isEqualTo(1);
  }

  @Test
  void concurrentAcknowledgementsRecordExactlyOneEvidence() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String bearer = linked(w, employee);
    String version = approvedVersion(w);
    String path = "/api/v1/employees/" + employee + "/contracts";
    String id =
        expect(
                mvc.perform(keyed(w.admin(), path, issueBody(w, employee, version, w.today())))
                    .andReturn(),
                201)
            .get("id")
            .asText();
    JsonNode mine =
        expect(
            mvc.perform(get("/api/v1/me/contracts/" + id).header("Authorization", bearer))
                .andReturn(),
            200);
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("snapshotSha256", mine.get("integrity").get("snapshotSha256").asText());
    ack.put("snapshotDigestVersion", 1);
    ack.put("grammarVersion", 1);
    ack.put("rendererVersion", 1);
    ack.put("statementCode", "RECEIVED_AND_REVIEWED");
    ack.put("statementVersion", 1);
    ack.put("statementLocale", "en");
    ack.put("statementSha256", mine.get("statements").get(1).get("sha256").asText());
    String ackPath = "/api/v1/me/contracts/" + id + "/acknowledgement";
    List<String> outcomes =
        race(
            keyed(bearer, ackPath, ack),
            keyed(bearer, ackPath, ack),
            keyed(bearer, ackPath, ack),
            keyed(bearer, ackPath, ack));
    assertThat(outcomes).containsOnlyOnce("200 false");
    assertThat(outcomes).filteredOn(o -> o.equals("200 true")).hasSize(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents.contract_acknowledgement WHERE contract_id = ?",
                Integer.class,
                UUID.fromString(id)))
        .isEqualTo(1);
  }

  @Test
  void aVoidRacingAnAcknowledgementLeavesOneConsistentOutcome() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String bearer = linked(w, employee);
    String version = approvedVersion(w);
    String path = "/api/v1/employees/" + employee + "/contracts";
    String id =
        expect(
                mvc.perform(keyed(w.admin(), path, issueBody(w, employee, version, w.today())))
                    .andReturn(),
                201)
            .get("id")
            .asText();
    JsonNode mine =
        expect(
            mvc.perform(get("/api/v1/me/contracts/" + id).header("Authorization", bearer))
                .andReturn(),
            200);
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("snapshotSha256", mine.get("integrity").get("snapshotSha256").asText());
    ack.put("snapshotDigestVersion", 1);
    ack.put("grammarVersion", 1);
    ack.put("rendererVersion", 1);
    ack.put("statementCode", "RECEIVED_AND_REVIEWED");
    ack.put("statementVersion", 1);
    ack.put("statementLocale", "fr");
    ack.put("statementSha256", mine.get("statements").get(0).get("sha256").asText());
    List<String> outcomes =
        race(
            keyed(bearer, "/api/v1/me/contracts/" + id + "/acknowledgement", ack),
            keyed(
                w.admin(),
                path + "/" + id + "/void",
                Map.of("expectedVersion", 0, "reasonCode", "OTHER")));
    String state =
        jdbc.queryForObject(
            "SELECT state FROM documents.contract WHERE id = ?", String.class, UUID.fromString(id));
    if ("VOID".equals(state)) {
      assertThat(outcomes).containsExactly("409 CONTRACT_NOT_ACKNOWLEDGEABLE", "200");
    } else {
      assertThat(state).isEqualTo("ACKNOWLEDGED");
      assertThat(outcomes).containsExactly("200 false", "409 CONTRACT_NOT_VOIDABLE");
    }
  }

  @Test
  void anIssueRacingASeparationNeitherDeadlocksNorIssuesAfterIt() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String version = approvedVersion(w);
    Map<String, Object> issue = issueBody(w, employee, version, w.today().minusDays(5));
    Map<String, Object> separation = new LinkedHashMap<>();
    separation.put("lastDay", w.today().toString());
    separation.put("reasonCode", "RESIGNATION");
    separation.put("accessTiming", "END_OF_LAST_DAY");
    JsonNode preview =
        expect(
            mvc.perform(
                    postJson(
                        w.admin(),
                        "/api/v1/employees/" + employee + "/separations/preview",
                        JSON.writeValueAsString(separation)))
                .andReturn(),
            200);
    Map<String, Object> commit = new LinkedHashMap<>(separation);
    commit.put("expectedVersion", preview.get("expectedVersion").asLong());
    commit.put("previewDigest", preview.get("previewDigest").asText());
    List<String> acks = new ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    commit.put("acknowledgements", acks);
    List<String> outcomes =
        race(
            keyed(w.admin(), "/api/v1/employees/" + employee + "/contracts", issue),
            keyed(w.admin(), "/api/v1/employees/" + employee + "/separations", commit));
    // The separation always wins or follows; an issue never lands after a recorded separation.
    assertThat(outcomes.get(1)).startsWith("201");
    assertThat(outcomes.get(0))
        .isIn("201", "409 CONTRACT_PREVIEW_CHANGED", "409 CONTRACT_EMPLOYMENT_ENDED");
    Integer issued =
        jdbc.queryForObject(
            "SELECT count(*) FROM documents.contract WHERE employee_id = ?",
            Integer.class,
            employee);
    assertThat(issued).isEqualTo(outcomes.get(0).equals("201") ? 1 : 0);
  }

  @Test
  void anAcknowledgementRacingAnUnlinkLeavesEvidenceOnlyFromAnActiveLink() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String bearer = linked(w, employee);
    String version = approvedVersion(w);
    String id =
        expect(
                mvc.perform(
                        keyed(
                            w.admin(),
                            "/api/v1/employees/" + employee + "/contracts",
                            issueBody(w, employee, version, w.today())))
                    .andReturn(),
                201)
            .get("id")
            .asText();
    JsonNode mine =
        expect(
            mvc.perform(get("/api/v1/me/contracts/" + id).header("Authorization", bearer))
                .andReturn(),
            200);
    JsonNode link =
        expect(
                mvc.perform(
                        get("/api/v1/employees/" + employee + "/access-link")
                            .header("Authorization", w.admin()))
                    .andReturn(),
                200)
            .get("link");
    Map<String, Object> ack = new LinkedHashMap<>();
    ack.put("snapshotSha256", mine.get("integrity").get("snapshotSha256").asText());
    ack.put("snapshotDigestVersion", 1);
    ack.put("grammarVersion", 1);
    ack.put("rendererVersion", 1);
    ack.put("statementCode", "RECEIVED_AND_REVIEWED");
    ack.put("statementVersion", 1);
    ack.put("statementLocale", "fr");
    ack.put("statementSha256", mine.get("statements").get(0).get("sha256").asText());
    List<String> outcomes =
        race(
            keyed(bearer, "/api/v1/me/contracts/" + id + "/acknowledgement", ack),
            keyed(
                w.admin(),
                "/api/v1/employees/" + employee + "/access-link/remove",
                Map.of(
                    "linkId",
                    link.get("id").asText(),
                    "expectedVersion",
                    link.get("version").asLong())));
    assertThat(outcomes.get(1)).isEqualTo("200");
    assertThat(outcomes.get(0)).isIn("200 false", "403 EMPLOYEE_LINK_REQUIRED");
    // Any evidence names the link that was active when it was recorded.
    Integer evidence =
        jdbc.queryForObject(
            "SELECT count(*) FROM documents.contract_acknowledgement a"
                + " JOIN identity.employee_access_link l ON l.tenant_id = a.tenant_id"
                + " AND l.id = a.link_id WHERE a.contract_id = ?"
                + " AND (l.unlinked_at IS NULL OR l.unlinked_at >= a.acknowledged_at)",
            Integer.class,
            UUID.fromString(id));
    assertThat(evidence).isEqualTo(outcomes.get(0).startsWith("200") ? 1 : 0);
  }

  @Test
  void theIntegrityJobReportsATamperedDigestAndRepairsNothing() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String version = approvedVersion(w);
    String id =
        expect(
                mvc.perform(
                        keyed(
                            w.admin(),
                            "/api/v1/employees/" + employee + "/contracts",
                            issueBody(w, employee, version, w.today())))
                    .andReturn(),
                201)
            .get("id")
            .asText();
    String original =
        jdbc.queryForObject(
            "SELECT snapshot_sha256 FROM documents.contract WHERE id = ?",
            String.class,
            UUID.fromString(id));
    String forged = "f".repeat(64);
    // Bypass the immutability trigger as an attacker with database access would.
    forceDigest(id, forged);
    ContractIntegrityJob.Run run = integrity.run();
    assertThat(run.mismatches()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.audit_event WHERE tenant_id = ? AND resource_id = ?"
                    + " AND action = 'contract-integrity.check' AND result = 'FAILURE'",
                Integer.class,
                w.org().tenant(),
                UUID.fromString(id)))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT snapshot_sha256 FROM documents.contract WHERE id = ?",
                String.class,
                UUID.fromString(id)))
        .isEqualTo(forged);
    // Restore the shared test database for other suites' integrity runs.
    forceDigest(id, original);
  }

  private void forceDigest(String id, String digest) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement replica = connection.createStatement()) {
      connection.setAutoCommit(false);
      replica.execute("SET LOCAL session_replication_role = replica");
      try (PreparedStatement update =
          connection.prepareStatement(
              "UPDATE documents.contract SET snapshot_sha256 = ? WHERE id = ?")) {
        update.setString(1, digest);
        update.setObject(2, UUID.fromString(id));
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      connection.commit();
    }
  }
}
