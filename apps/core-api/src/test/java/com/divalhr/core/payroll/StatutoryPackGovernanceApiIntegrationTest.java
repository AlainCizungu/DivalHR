package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.divalhr.core.support.Bootstraps;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-097A (Issue #200, ADR 0040): the platform lifecycle API. Platform authority with exact MFA,
 * the closed lifecycle, independent four-eyes approval, stale versions, idempotent replay, a
 * serialised concurrent approval, and the privacy of audit, events and stored receipts. Every pack
 * and every rule value here is SYNTHETIC: it proves the mechanism and states no law.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.statutory-pack-read=600",
      "divalhr.request-limits.subject.statutory-pack-command=600"
    })
class StatutoryPackGovernanceApiIntegrationTest {

  private static final String RULES =
      """
      {"schemaVersion":1,"components":[
       {"id":"SYN_EMPLOYEE_SOCIAL","party":"EMPLOYEE","category":"SOCIAL_CONTRIBUTION","order":1,
        "requires":"CNSS_SUBJECT","base":"GROSS","sourceKey":"CNSS_RATES",
        "method":{"type":"RATE","rateBp":500},"rounding":"HALF_UP"},
       {"id":"SYN_EMPLOYER_SOCIAL","party":"EMPLOYER","category":"SOCIAL_CONTRIBUTION","order":2,
        "requires":"CNSS_SUBJECT","base":"GROSS","sourceKey":"CNSS_RATES",
        "method":{"type":"RATE","rateBp":500},"rounding":"HALF_UP"},
       {"id":"SYN_TAX","party":"EMPLOYEE","category":"INCOME_TAX","order":3,
        "requires":"IRPP_SUBJECT","base":"GROSS","sourceKey":"DGI_IRPP",
        "method":{"type":"PROGRESSIVE","periodsPerYear":12,"capBp":3000,
                  "brackets":[{"upToAnnual":1944000,"rateBp":300},{"upToAnnual":null,"rateBp":1500}]},
        "rounding":"HALF_UP"}]}
      """;

  private static final String NOTE = "synthetic-note-must-not-leak";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  private final ObjectMapper json = CompensationWorld.JSON;

  private String submitter;
  private String reviewer;
  private String other;

  @BeforeEach
  void world() {
    GovernedPackFixtures.retireAll(jdbc);
    submitter = Bootstraps.platform(Bootstraps.platformSubject());
    reviewer = Bootstraps.platform(Bootstraps.platformSubject());
    other = Bootstraps.platform(Bootstraps.platformSubject());
  }

  // -- helpers --------------------------------------------------------------------------------

  private record Pack(UUID id, String code) {
    String path() {
      return "/api/v1/platform/statutory-packs/" + code + "/versions/1";
    }
  }

  /** A SYNTHETIC DRAFT pack in 2090 with three reference cases, with or without evidence. */
  private Pack draft(boolean evidence) {
    UUID id = StatutoryWorld.draftPack(jdbc, RULES, "2090-01-01", "2090-12-31");
    String code =
        jdbc.queryForObject(
            "SELECT code FROM payroll.statutory_rule_pack WHERE id = ?", String.class, id);
    jdbc.update(
        "INSERT INTO payroll.statutory_reference_case (pack_id, seq, case_key, inputs, expected) VALUES"
            + " (?, 1, 'BOTH', '{\"cnssSubject\":true,\"irppSubject\":true,\"grossMinor\":100000}'::jsonb,"
            + " '{\"employeeDeductionsMinor\":8000,\"employerContributionsMinor\":5000,\"components\":"
            + "{\"SYN_EMPLOYEE_SOCIAL\":5000,\"SYN_EMPLOYER_SOCIAL\":5000,\"SYN_TAX\":3000}}'::jsonb),"
            + " (?, 2, 'NEITHER', '{\"cnssSubject\":false,\"irppSubject\":false,\"grossMinor\":100000}'::jsonb,"
            + " '{\"employeeDeductionsMinor\":0,\"employerContributionsMinor\":0,\"components\":{}}'::jsonb),"
            + " (?, 3, 'SOCIAL_ONLY', '{\"cnssSubject\":true,\"irppSubject\":false,\"grossMinor\":200000}'::jsonb,"
            + " '{\"employeeDeductionsMinor\":10000,\"employerContributionsMinor\":10000,\"components\":"
            + "{\"SYN_EMPLOYEE_SOCIAL\":10000,\"SYN_EMPLOYER_SOCIAL\":10000,\"SYN_TAX\":0}}'::jsonb)",
        id,
        id,
        id);
    if (evidence) {
      jdbc.update(
          "UPDATE payroll.statutory_pack_governance SET source_authority = 'Synthetic test authority',"
              + " source_title = 'Synthetic test source bundle', source_published_on = DATE '2026-10-01',"
              + " source_reference = 'https://example.test/synthetic-source',"
              + " source_bundle_sha256 = repeat('d', 64), evidence_schema_version = 1,"
              + " row_version = row_version + 1, transition_actor = 'synthetic-fixture',"
              + " transition_at = now() WHERE pack_id = ?",
          id);
    }
    return new Pack(id, code);
  }

  private int rowVersion(Pack pack) {
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT row_version FROM payroll.statutory_pack_governance WHERE pack_id = ?",
            Integer.class,
            pack.id()));
  }

  private String state(Pack pack) {
    return jdbc.queryForObject(
        "SELECT state FROM payroll.statutory_pack_governance WHERE pack_id = ?",
        String.class,
        pack.id());
  }

  private int transitions(Pack pack) {
    return java.util.Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM payroll.statutory_pack_transition WHERE pack_id = ?",
            Integer.class,
            pack.id()));
  }

  private static Map<String, Object> command(int expected, Object... extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", expected);
    for (int i = 0; i < extra.length; i += 2) {
      body.put((String) extra[i], extra[i + 1]);
    }
    return body;
  }

  private MockHttpServletResponse call(
      String bearer, Pack pack, String verb, Map<String, Object> body, String key)
      throws Exception {
    return mvc.perform(
            post(pack.path() + ":" + verb)
                .header("Authorization", bearer)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse call(
      String bearer, Pack pack, String verb, Map<String, Object> body) throws Exception {
    return call(bearer, pack, verb, body, "key-" + UUID.randomUUID());
  }

  private JsonNode body(MockHttpServletResponse response) throws Exception {
    return json.readTree(response.getContentAsString());
  }

  private String code(MockHttpServletResponse response) throws Exception {
    return body(response).path("code").asText();
  }

  /** DRAFT to VALIDATED and submitted, by the submitter. */
  private void submitted(Pack pack) throws Exception {
    assertThat(call(submitter, pack, "validate", command(rowVersion(pack))).getStatus())
        .isEqualTo(200);
    assertThat(call(submitter, pack, "submit-review", command(rowVersion(pack))).getStatus())
        .isEqualTo(200);
  }

  // -- authority ------------------------------------------------------------------------------

  @Test
  void onlyAPlatformAdministratorWithExactMfaReachesAnyOperation() throws Exception {
    Pack pack = draft(true);
    String tenantAdmin =
        "Bearer "
            + TestTokens.token()
                .subject("sub-tenant-admin")
                .tenant(TestTokens.TENANT_A)
                .roles(List.of("tenant-admin"))
                .build();
    String employee =
        "Bearer "
            + TestTokens.token()
                .subject("sub-employee")
                .tenant(TestTokens.TENANT_A)
                .roles(List.of("employee"))
                .build();
    String passwordOnly =
        "Bearer "
            + TestTokens.token()
                .subject("sub-platform-pwd")
                .roles(List.of("platform-admin"))
                .acr(TestTokens.PASSWORD_ACR)
                .build();
    for (String denied : List.of(tenantAdmin, employee, passwordOnly)) {
      assertThat(
              mvc.perform(get("/api/v1/platform/statutory-packs").header("Authorization", denied))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
      assertThat(
              mvc.perform(get(pack.path()).header("Authorization", denied))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
      for (String verb :
          List.of(
              "validate", "submit-review", "approve", "reject", "schedule", "activate", "retire")) {
        // The body is not even bound: an empty object is refused for authority, not as a field
        // error.
        assertThat(call(denied, pack, verb, Map.of()).getStatus()).as(verb).isEqualTo(403);
      }
    }
    assertThat(state(pack)).isEqualTo("DRAFT");
    assertThat(transitions(pack)).isZero();
  }

  @Test
  void responsesAreNeverCached() throws Exception {
    Pack pack = draft(true);
    MockHttpServletResponse list =
        mvc.perform(get("/api/v1/platform/statutory-packs").header("Authorization", submitter))
            .andReturn()
            .getResponse();
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat(list.getHeader("Cache-Control")).isEqualTo("private, no-store");
    MockHttpServletResponse one =
        mvc.perform(get(pack.path()).header("Authorization", submitter)).andReturn().getResponse();
    assertThat(one.getHeader("Cache-Control")).isEqualTo("private, no-store");
    JsonNode detail = body(one);
    assertThat(detail.path("summary").path("state").asText()).isEqualTo("DRAFT");
    assertThat(detail.path("summary").path("approvedForOperationalPayroll").asBoolean()).isFalse();
    // No rule value, formula or fixture reaches the response.
    assertThat(one.getContentAsString()).doesNotContain("rateBp").doesNotContain("SYN_TAX");
  }

  // -- lifecycle ------------------------------------------------------------------------------

  @Test
  void theFullLifecycleRunsWithTwoIndependentSubjects() throws Exception {
    Pack pack = draft(true);
    submitted(pack);
    assertThat(state(pack)).isEqualTo("VALIDATED");

    MockHttpServletResponse approved =
        call(reviewer, pack, "approve", command(rowVersion(pack), "confirmed", true));
    assertThat(approved.getStatus()).isEqualTo(200);
    assertThat(body(approved).path("state").asText()).isEqualTo("REVIEWED");

    MockHttpServletResponse scheduled =
        call(
            reviewer,
            pack,
            "schedule",
            command(rowVersion(pack), "confirmed", true, "effectiveFrom", "2090-02-01"));
    assertThat(scheduled.getStatus()).isEqualTo(200);
    assertThat(body(scheduled).path("state").asText()).isEqualTo("SCHEDULED");

    // A scheduled pack whose date is in the future cannot be activated early.
    assertThat(code(call(reviewer, pack, "activate", command(rowVersion(pack), "confirmed", true))))
        .isEqualTo("STATUTORY_PACK_TRANSITION_NOT_ALLOWED");

    MockHttpServletResponse retired =
        call(
            reviewer,
            pack,
            "retire",
            command(rowVersion(pack), "confirmed", true, "reasonCode", "WITHDRAWN"));
    assertThat(retired.getStatus()).isEqualTo(200);
    assertThat(state(pack)).isEqualTo("RETIRED");

    List<String> kinds =
        jdbc.queryForList(
            "SELECT kind FROM payroll.statutory_pack_transition WHERE pack_id = ? ORDER BY seq",
            String.class,
            pack.id());
    assertThat(kinds).containsExactly("VALIDATE", "SUBMIT", "APPROVE", "SCHEDULE", "RETIRE");
    // RETIRED never moves again.
    assertThat(code(call(reviewer, pack, "approve", command(rowVersion(pack), "confirmed", true))))
        .isEqualTo("STATUTORY_PACK_TRANSITION_NOT_ALLOWED");
  }

  @Test
  void aSubmitterCannotApproveTheirOwnSubmissionAndNothingChanges() throws Exception {
    Pack pack = draft(true);
    submitted(pack);
    int version = rowVersion(pack);
    int history = transitions(pack);
    MockHttpServletResponse refused =
        call(submitter, pack, "approve", command(version, "confirmed", true));
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(code(refused)).isEqualTo("STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN");
    assertThat(state(pack)).isEqualTo("VALIDATED");
    assertThat(rowVersion(pack)).isEqualTo(version);
    assertThat(transitions(pack)).isEqualTo(history);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_pack_review WHERE pack_id = ?",
                Integer.class,
                pack.id()))
        .isZero();
  }

  @Test
  void invalidTransitionsAreRefusedWithStableCodes() throws Exception {
    Pack pack = draft(true);
    int version = rowVersion(pack);
    // A DRAFT cannot be submitted, approved, scheduled, activated or retired.
    assertThat(code(call(submitter, pack, "submit-review", command(version))))
        .isEqualTo("STATUTORY_PACK_VALIDATION_FAILED");
    assertThat(code(call(reviewer, pack, "approve", command(version, "confirmed", true))))
        .isEqualTo("STATUTORY_PACK_VALIDATION_FAILED");
    assertThat(
            code(
                call(
                    reviewer,
                    pack,
                    "schedule",
                    command(version, "confirmed", true, "effectiveFrom", "2090-02-01"))))
        .isEqualTo("STATUTORY_PACK_REVIEW_REQUIRED");
    assertThat(code(call(reviewer, pack, "activate", command(version, "confirmed", true))))
        .isEqualTo("STATUTORY_PACK_REVIEW_REQUIRED");
    assertThat(
            code(
                call(
                    reviewer,
                    pack,
                    "retire",
                    command(version, "confirmed", true, "reasonCode", "ERROR"))))
        .isEqualTo("STATUTORY_PACK_TRANSITION_NOT_ALLOWED");
    assertThat(state(pack)).isEqualTo("DRAFT");
    assertThat(rowVersion(pack)).isEqualTo(version);
  }

  @Test
  void aSubmissionNeedsCompleteEvidence() throws Exception {
    Pack pack = draft(false);
    assertThat(call(submitter, pack, "validate", command(rowVersion(pack))).getStatus())
        .isEqualTo(200);
    MockHttpServletResponse refused =
        call(submitter, pack, "submit-review", command(rowVersion(pack)));
    assertThat(refused.getStatus()).isEqualTo(422);
    assertThat(code(refused)).isEqualTo("STATUTORY_PACK_EVIDENCE_INCOMPLETE");
    assertThat(
            jdbc.queryForObject(
                "SELECT submitted_by FROM payroll.statutory_pack_governance WHERE pack_id = ?",
                String.class,
                pack.id()))
        .isNull();
  }

  @Test
  void aStaleExpectedVersionIsAConflictBeforeAnyChange() throws Exception {
    Pack pack = draft(true);
    int stale = rowVersion(pack) - 1;
    MockHttpServletResponse refused = call(submitter, pack, "validate", command(stale));
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(code(refused)).isEqualTo("STATUTORY_PACK_VERSION_CONFLICT");
    assertThat(state(pack)).isEqualTo("DRAFT");
    assertThat(transitions(pack)).isZero();
  }

  @Test
  void confirmationAndReasonAreRequiredWhereTheCommandIsConsequential() throws Exception {
    Pack pack = draft(true);
    submitted(pack);
    int version = rowVersion(pack);
    assertThat(call(reviewer, pack, "approve", command(version)).getStatus()).isEqualTo(400);
    assertThat(call(reviewer, pack, "approve", command(version, "confirmed", false)).getStatus())
        .isEqualTo(400);
    assertThat(call(reviewer, pack, "reject", command(version, "confirmed", true)).getStatus())
        .isEqualTo(400);
    assertThat(
            call(
                    reviewer,
                    pack,
                    "reject",
                    command(version, "confirmed", true, "reasonCode", "NOT_A_REASON"))
                .getStatus())
        .isEqualTo(400);
    assertThat(state(pack)).isEqualTo("VALIDATED");
    assertThat(rowVersion(pack)).isEqualTo(version);
  }

  @Test
  void aRejectionRetiresTheVersionAndCreatesANewDraftRevision() throws Exception {
    Pack pack = draft(true);
    submitted(pack);
    MockHttpServletResponse rejected =
        call(
            reviewer,
            pack,
            "reject",
            command(
                rowVersion(pack),
                "confirmed",
                true,
                "reasonCode",
                "SOURCE_MISMATCH",
                "note",
                NOTE));
    assertThat(rejected.getStatus()).isEqualTo(200);
    assertThat(state(pack)).isEqualTo("RETIRED");
    int revision = body(rejected).path("revisionVersion").asInt();
    assertThat(revision).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT g.state FROM payroll.statutory_pack_governance g JOIN payroll.statutory_rule_pack p"
                    + " ON p.id = g.pack_id WHERE p.code = ? AND p.version = ?",
                String.class,
                pack.code(),
                revision))
        .isEqualTo("DRAFT");
  }

  // -- idempotency and concurrency ------------------------------------------------------------

  @Test
  void aReplayedCommandReturnsTheStoredReceiptAndTransitionsOnce() throws Exception {
    Pack pack = draft(true);
    String key = "key-" + UUID.randomUUID();
    Map<String, Object> command = command(rowVersion(pack));
    MockHttpServletResponse first = call(submitter, pack, "validate", command, key);
    MockHttpServletResponse second = call(submitter, pack, "validate", command, key);
    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(second.getStatus()).isEqualTo(200);
    assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
    assertThat(transitions(pack)).isEqualTo(1);
    // The same key with a different command is refused, never executed.
    MockHttpServletResponse changed =
        call(submitter, pack, "validate", command(rowVersion(pack) + 1), key);
    assertThat(changed.getStatus()).isEqualTo(409);
    assertThat(transitions(pack)).isEqualTo(1);
  }

  @Test
  void twoReviewersApprovingTogetherYieldExactlyOneApproval() throws Exception {
    Pack pack = draft(true);
    submitted(pack);
    int version = rowVersion(pack);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Callable<Integer>> calls = new ArrayList<>();
      for (String bearer : List.of(reviewer, other)) {
        calls.add(
            () -> {
              go.await();
              return call(bearer, pack, "approve", command(version, "confirmed", true)).getStatus();
            });
      }
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> call : calls) {
        futures.add(pool.submit(call));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : futures) {
        statuses.add(future.get());
      }
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(state(pack)).isEqualTo("REVIEWED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_pack_review WHERE pack_id = ?",
                Integer.class,
                pack.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payroll.statutory_pack_transition WHERE pack_id = ? AND kind = 'APPROVE'",
                Integer.class,
                pack.id()))
        .isEqualTo(1);
  }

  // -- privacy --------------------------------------------------------------------------------

  @Test
  void auditEventsAndStoredReceiptsCarryNoRuleValueNoteOrFixture() throws Exception {
    Pack pack = draft(true);
    submitted(pack);
    assertThat(
            call(
                    reviewer,
                    pack,
                    "reject",
                    command(
                        rowVersion(pack),
                        "confirmed",
                        true,
                        "reasonCode",
                        "SOURCE_MISMATCH",
                        "note",
                        NOTE))
                .getStatus())
        .isEqualTo(200);
    List<String> audit =
        jdbc.queryForList(
            "SELECT metadata::text FROM platform.audit_event WHERE action LIKE 'statutory-pack.%'"
                + " AND resource_id = ?",
            String.class, pack.id());
    assertThat(audit).isNotEmpty();
    List<String> events =
        jdbc.queryForList(
            "SELECT envelope::text FROM platform.outbox_event WHERE event_type LIKE 'payroll.statutory-pack.%'"
                + " AND envelope::text LIKE ?",
            String.class, "%" + pack.id() + "%");
    List<String> receipts =
        jdbc.queryForList(
            "SELECT response_body::text FROM platform.idempotency_record", String.class);
    for (String text : concat(audit, events, receipts)) {
      assertThat(text)
          .doesNotContain(NOTE)
          .doesNotContain("rateBp")
          .doesNotContain("SYN_TAX")
          .doesNotContain("grossMinor");
    }
    // A platform action has no tenant: it uses the nil-UUID platform scope.
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT tenant_id::text FROM platform.audit_event WHERE action = 'statutory-pack.reject'"
                    + " AND resource_id = ?",
                String.class,
                pack.id()))
        .containsExactly("00000000-0000-0000-0000-000000000000");
  }

  @SafeVarargs
  private static List<String> concat(List<String>... lists) {
    List<String> all = new ArrayList<>();
    for (List<String> list : lists) {
      all.addAll(list);
    }
    return all;
  }

  // -- the real candidate pack ----------------------------------------------------------------

  @Test
  void theSeededDrcPreviewPackStaysDraftWithoutEvidenceOrCases() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT g.state, g.source_authority, p.status,"
                + " (SELECT count(*) FROM payroll.statutory_reference_case c WHERE c.pack_id = p.id) AS cases"
                + " FROM payroll.statutory_rule_pack p JOIN payroll.statutory_pack_governance g"
                + " ON g.pack_id = p.id WHERE p.code = 'CD-SALARY-PREVIEW'");
    if (rows.isEmpty()) {
      return; // the development seed is not loaded in this database
    }
    for (Map<String, Object> row : rows) {
      assertThat(row.get("state")).isEqualTo("DRAFT");
      assertThat(row.get("status")).isEqualTo("DRAFT");
      assertThat(row.get("source_authority")).isNull();
      assertThat(((Number) row.get("cases")).intValue()).isZero();
    }
  }
}
