package com.divalhr.core.documents;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.documents.application.ContractIntegrityJob;
import com.divalhr.core.documents.domain.AcknowledgementStatement;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * MVP-030 (Issue #51) end to end against PostgreSQL: templates and their lifecycle, grammar v1
 * rejections (A30-2), preview and issue with server-side digests (A30-4), void, the employee's own
 * contracts and their acknowledgement, isolation between employees and tenants, and privacy: names,
 * template text and dates never reach audit metadata, outbox data or logs.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ContractIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TEMPLATES = "/api/v1/contract-templates";
  private static final String EMPLOYEES = "/api/v1/employees";
  private static final String MINE = "/api/v1/me/contracts";
  private static final String GIVEN = "Bénédicte Contractuelle";
  private static final String FAMILY = "Mbuyi-Témoin";
  private static final String TITLE = "Contrat de travail — modèle confidentiel";
  private static final String BODY =
      String.join(
          "\n",
          "# Contrat de travail",
          "Entre {{organization.name}} et {{employee.fullName}} (matricule {{employee.number}}).",
          "",
          "## Article 1 : Durée",
          "- Type : {{contract.type}}",
          "- Début : {{contract.startDate}}",
          "",
          "Lieu : {{site.name}}, {{legalEntity.name}}. Embauche le {{employment.startDate}}.",
          "Fait le {{issue.date}}.");

  /** Audit metadata keys any MVP-030 record may carry (§9). */
  private static final Set<String> METADATA_KEYS =
      Set.of(
          "schemaVersion",
          "version",
          "versionNumber",
          "from",
          "to",
          "retiredPrevious",
          "statementVersion",
          "view",
          "page",
          "resultCount",
          "outcome");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EmailLookup lookups;
  @Autowired private ContractIntegrityJob integrity;

  // ------------------------------------------------------------------------------------------
  // Fixtures
  // ------------------------------------------------------------------------------------------

  private record World(Org org, String admin, LocalDate today) {}

  private World world() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    return new World(
        org,
        Hierarchy.bearer(org.tenant(), "sub-contract-admin-" + UUID.randomUUID(), "tenant-admin"),
        Employees.today(jdbc, org.tenant()));
  }

  private UUID hire(World w) throws Exception {
    return Employees.hire(
        mvc, jdbc, w.org(), w.admin(), Employees.number(), GIVEN, FAMILY, w.today().minusDays(100));
  }

  private record Member(UUID id, String subject) {}

  private Member member(World w, String role) {
    UUID id = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    String address = "contract-" + UUID.randomUUID().toString().substring(0, 8) + "@exemple.cd";
    jdbc.update(
        "INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup,"
            + " created_at) VALUES (?, ?, ?, ?, ?, now())",
        id,
        w.org().tenant(),
        subject,
        role,
        lookups.of(EmailAddress.parse(address).orElseThrow()));
    return new Member(id, subject);
  }

  private static String bearer(UUID tenant, Member member) {
    return "Bearer "
        + TestTokens.token()
            .tenant(tenant)
            .subject(member.subject())
            .roles(List.of("employee"))
            .build();
  }

  /** An employee hired, with an employee membership linked to them. */
  private String linkedEmployee(World w, UUID employee) throws Exception {
    Member member = member(w, "employee");
    expect(
        call(
            postJson(
                    w.admin(),
                    EMPLOYEES + "/" + employee + "/access-link",
                    "{\"membershipId\":\"" + member.id() + "\"}")
                .header("Idempotency-Key", Organizations.newKey())),
        201);
    return bearer(w.org().tenant(), member);
  }

  private MvcResult call(RequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn();
  }

  private static JsonNode expect(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(status);
    return result.getResponse().getContentAsByteArray().length == 0
        ? null
        : Employees.json(result.getResponse().getContentAsByteArray());
  }

  private static JsonNode problem(MvcResult result, int status, String code) throws Exception {
    JsonNode body = expect(result, status);
    assertThat(body.get("code").asText()).isEqualTo(code);
    return body;
  }

  private MvcResult postKeyed(String bearer, String path, Object body) throws Exception {
    return call(
        postJson(bearer, path, JSON.writeValueAsString(body))
            .header("Idempotency-Key", Organizations.newKey()));
  }

  private JsonNode template(World w, String type) throws Exception {
    return expect(
        postKeyed(
            w.admin(),
            TEMPLATES,
            Map.of("code", Hierarchy.code("CT"), "name", "Modèle CDI", "contractType", type)),
        201);
  }

  private JsonNode draft(World w, String templateId, String locale, String body) throws Exception {
    return expect(
        postKeyed(
            w.admin(),
            TEMPLATES + "/" + templateId + "/versions",
            Map.of("locale", locale, "title", TITLE, "body", body)),
        201);
  }

  private JsonNode approve(World w, String templateId, JsonNode version) throws Exception {
    return expect(
        postKeyed(
            w.admin(),
            TEMPLATES + "/" + templateId + "/versions/" + version.get("id").asText() + "/approve",
            Map.of(
                "expectedVersion", version.get("version").asLong(),
                "acknowledgements", List.of("TEXT_VERIFIED"))),
        200);
  }

  /** An approved French version of a new template of the type. */
  private String approvedVersion(World w, String type, String body) throws Exception {
    JsonNode template = template(w, type);
    JsonNode draft = draft(w, template.get("id").asText(), "fr", body);
    return approve(w, template.get("id").asText(), draft).get("id").asText();
  }

  private static Map<String, Object> command(String versionId, LocalDate start, LocalDate end) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("templateVersionId", versionId);
    command.put("startDate", start.toString());
    command.put("endDate", end == null ? null : end.toString());
    return command;
  }

  private MvcResult preview(World w, UUID employee, Map<String, Object> command) throws Exception {
    return call(
        postJson(
            w.admin(),
            EMPLOYEES + "/" + employee + "/contracts/preview",
            JSON.writeValueAsString(command)));
  }

  private MvcResult issue(World w, UUID employee, Map<String, Object> command, JsonNode preview)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedEmploymentVersion", preview.get("employmentVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    return postKeyed(w.admin(), EMPLOYEES + "/" + employee + "/contracts", body);
  }

  private JsonNode issued(World w, UUID employee, String versionId, LocalDate start)
      throws Exception {
    Map<String, Object> command = command(versionId, start, null);
    JsonNode preview = expect(preview(w, employee, command), 200);
    return expect(issue(w, employee, command, preview), 201);
  }

  private static Map<String, Object> acknowledgement(JsonNode mine, String locale) {
    JsonNode statement = null;
    for (JsonNode candidate : mine.get("statements")) {
      if (candidate.get("locale").asText().equals(locale)) {
        statement = candidate;
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("snapshotSha256", mine.get("integrity").get("snapshotSha256").asText());
    body.put("snapshotDigestVersion", 1);
    body.put("grammarVersion", 1);
    body.put("rendererVersion", 1);
    body.put("statementCode", "RECEIVED_AND_REVIEWED");
    body.put("statementVersion", 1);
    body.put("statementLocale", locale);
    body.put("statementSha256", statement.get("sha256").asText());
    return body;
  }

  // ------------------------------------------------------------------------------------------
  // Templates
  // ------------------------------------------------------------------------------------------

  @Test
  void aTemplateMovesFromDraftToApprovedToRetiredAndApprovedTextNeverChanges() throws Exception {
    World w = world();
    JsonNode template = template(w, "PERMANENT");
    String id = template.get("id").asText();
    assertThat(template.get("versions")).isEmpty();
    // The code is unique in the tenant.
    problem(
        postKeyed(
            w.admin(),
            TEMPLATES,
            Map.of(
                "code", template.get("code").asText(), "name", "Autre", "contractType", "DAILY")),
        409,
        "CONTRACT_TEMPLATE_CODE_TAKEN");

    JsonNode v1 = draft(w, id, "fr", BODY);
    assertThat(v1.get("state").asText()).isEqualTo("DRAFT");
    assertThat(v1.get("versionNumber").asInt()).isEqualTo(1);
    assertThat(v1.get("bodySha256").asText()).matches("^[0-9a-f]{64}$");
    assertThat(v1.get("placeholders").toString()).contains("employee.fullName", "site.name");
    // One open draft per language.
    problem(
        postKeyed(
            w.admin(),
            TEMPLATES + "/" + id + "/versions",
            Map.of("locale", "fr", "title", "T", "body", "Texte")),
        409,
        "CONTRACT_TEMPLATE_VERSION_CONFLICT");

    // Edit the draft (expectedVersion), then a stale edit conflicts.
    String path = TEMPLATES + "/" + id + "/versions/" + v1.get("id").asText();
    JsonNode edited =
        expect(
            call(
                put(path)
                    .header("Authorization", w.admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of("title", TITLE, "body", BODY + "\n", "expectedVersion", 0)))),
            200);
    assertThat(edited.get("version").asLong()).isEqualTo(1);
    problem(
        call(
            put(path)
                .header("Authorization", w.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of("title", TITLE, "body", BODY, "expectedVersion", 0)))),
        409,
        "CONTRACT_TEMPLATE_VERSION_CONFLICT");

    // Approval requires TEXT_VERIFIED.
    problem(
        postKeyed(
            w.admin(),
            path + "/approve",
            Map.of("expectedVersion", 1, "acknowledgements", List.of())),
        422,
        "CONTRACT_TEMPLATE_ACKNOWLEDGEMENT_REQUIRED");
    JsonNode approved = approve(w, id, edited);
    assertThat(approved.get("state").asText()).isEqualTo("APPROVED");
    assertThat(approved.get("approvedAt").isNull()).isFalse();

    // Approved text never changes, and is never deleted.
    problem(
        call(
            put(path)
                .header("Authorization", w.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of("title", TITLE, "body", BODY, "expectedVersion", 2)))),
        409,
        "CONTRACT_TEMPLATE_NOT_DRAFT");
    problem(
        call(delete(path).param("expectedVersion", "2").header("Authorization", w.admin())),
        409,
        "CONTRACT_TEMPLATE_NOT_DRAFT");

    // Approving version 2 retires version 1 in the same transaction (D8).
    JsonNode v2 = draft(w, id, "fr", BODY);
    assertThat(v2.get("versionNumber").asInt()).isEqualTo(2);
    approve(w, id, v2);
    JsonNode read = expect(call(get(TEMPLATES + "/" + id).header("Authorization", w.admin())), 200);
    Map<String, String> states = new LinkedHashMap<>();
    read.get("versions").forEach(v -> states.put(v.get("id").asText(), v.get("state").asText()));
    assertThat(states)
        .containsEntry(v1.get("id").asText(), "RETIRED")
        .containsEntry(v2.get("id").asText(), "APPROVED");
    assertThat(read.get("versions").get(0).has("body")).isFalse();

    // The list shows each language line.
    JsonNode page = expect(call(get(TEMPLATES).header("Authorization", w.admin())), 200);
    JsonNode line = page.get("items").get(0).get("lines").get(0);
    assertThat(line.get("locale").asText()).isEqualTo("fr");
    assertThat(line.get("approvedVersionId").asText()).isEqualTo(v2.get("id").asText());
    assertThat(line.get("draftVersionId").isNull()).isTrue();

    // A never-approved draft can be deleted; then retiring the approved version.
    JsonNode v3 = draft(w, id, "fr", BODY);
    expect(
        call(
            delete(TEMPLATES + "/" + id + "/versions/" + v3.get("id").asText())
                .param("expectedVersion", "0")
                .header("Authorization", w.admin())),
        204);
    JsonNode retired =
        expect(
            postKeyed(
                w.admin(),
                TEMPLATES + "/" + id + "/versions/" + v2.get("id").asText() + "/retire",
                Map.of("expectedVersion", 1)),
            200);
    assertThat(retired.get("state").asText()).isEqualTo("RETIRED");
    problem(
        postKeyed(
            w.admin(),
            TEMPLATES + "/" + id + "/versions/" + v2.get("id").asText() + "/retire",
            Map.of("expectedVersion", 2)),
        409,
        "CONTRACT_TEMPLATE_NOT_APPROVED");
  }

  @Test
  void grammarRejectionsCarryAClosedReasonAndALineNeverTheText() throws Exception {
    World w = world();
    String id = template(w, "PERMANENT").get("id").asText();
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("Voir https://exemple.cd/contrat", "URI_SCHEME");
    cases.put("Voir JaVaScRiPt:alert(1)", "URI_SCHEME");
    cases.put("Voir //exemple.cd/x", "PROTOCOL_RELATIVE");
    cases.put("Voir www.exemple.cd", "WEB_ADDRESS");
    cases.put("Voir %3Cscript%3E", "ENCODED_CONTENT");
    cases.put("Voir [ici](page)", "MARKDOWN_LINK");
    cases.put("Voir <b>gras</b>", "HTML_MARKUP");
    cases.put("Bonjour {{employee.salary}}", "UNKNOWN_PLACEHOLDER");
    for (Map.Entry<String, String> entry : cases.entrySet()) {
      String body = "# Titre\nLigne sûre.\n" + entry.getKey();
      JsonNode rejected =
          problem(
              postKeyed(
                  w.admin(),
                  TEMPLATES + "/" + id + "/versions",
                  Map.of("locale", "fr", "title", "T", "body", body)),
              422,
              "CONTRACT_TEMPLATE_INVALID");
      assertThat(rejected.get("params").get("reason").asText()).isEqualTo(entry.getValue());
      assertThat(rejected.get("params").get("line").asInt()).isEqualTo(3);
      assertThat(rejected.toString()).doesNotContain("exemple").doesNotContain("script");
    }
    // The validation endpoint reports every problem and writes nothing.
    JsonNode report =
        expect(
            call(
                postJson(
                    w.admin(),
                    TEMPLATES + "/validate",
                    JSON.writeValueAsString(
                        Map.of("title", "T", "body", "Ligne\n<i>x</i>\nVoir www.exemple.cd")))),
            200);
    assertThat(report.get("valid").asBoolean()).isFalse();
    assertThat(report.get("problems").toString())
        .contains("\"HTML_MARKUP\"", "\"WEB_ADDRESS\"")
        .doesNotContain("exemple");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents.contract_template_version WHERE tenant_id = ?",
                Integer.class,
                w.org().tenant()))
        .isZero();
    // Ordinary legal punctuation is allowed.
    JsonNode ok =
        expect(
            call(
                postJson(
                    w.admin(),
                    TEMPLATES + "/validate",
                    JSON.writeValueAsString(
                        Map.of(
                            "title",
                            "Article 2 : Durée",
                            "body",
                            "Horaires 08:30 à 17:00, 2026/27, Dupont & Fils, 10 %, moins de"
                                + " < 3 mois, § 4 [annexe 1] (page 3).")))),
            200);
    assertThat(ok.get("valid").asBoolean()).isTrue();
  }

  // ------------------------------------------------------------------------------------------
  // Issue
  // ------------------------------------------------------------------------------------------

  @Test
  void aPreviewedContractIsIssuedWithTheExactSnapshotAndServerDigests() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String version = approvedVersion(w, "PERMANENT", BODY);
    Map<String, Object> command = command(version, w.today(), null);
    JsonNode preview = expect(preview(w, employee, command), 200);
    assertThat(preview.get("previewDigest").asText()).matches("^[0-9a-f]{64}$");
    assertThat(preview.get("integrity").get("digestAlgorithm").asText()).isEqualTo("SHA-256");
    assertThat(preview.get("snapshot").get("title").asText()).isEqualTo(TITLE);
    String text = preview.get("snapshot").get("blocks").toString();
    assertThat(text).contains(GIVEN + " " + FAMILY).contains("Durée indéterminée");
    assertThat(text).doesNotContain("{{");

    // A stale employment version or a forged digest is a changed preview.
    Map<String, Object> forged = new LinkedHashMap<>(command);
    forged.put("expectedEmploymentVersion", preview.get("employmentVersion").asLong());
    forged.put("previewDigest", "0".repeat(64));
    problem(
        postKeyed(w.admin(), EMPLOYEES + "/" + employee + "/contracts", forged),
        409,
        "CONTRACT_PREVIEW_CHANGED");

    MvcResult first = issue(w, employee, command, preview);
    JsonNode contract = expect(first, 201);
    assertThat(contract.get("state").asText()).isEqualTo("ISSUED");
    assertThat(contract.get("snapshot")).isEqualTo(preview.get("snapshot"));
    assertThat(contract.get("integrity")).isEqualTo(preview.get("integrity"));
    assertThat(contract.get("acknowledgement").isNull()).isTrue();
    assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-store");

    // The stored snapshot is the canonical text the digest covers (A30-4).
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT snapshot_canonical, snapshot_sha256, digest_version, grammar_version,"
                + " renderer_version FROM documents.contract WHERE id = ?",
            UUID.fromString(contract.get("id").asText()));
    assertThat(row.get("snapshot_sha256"))
        .isEqualTo(contract.get("integrity").get("snapshotSha256").asText());
    assertThat(((Number) row.get("digest_version")).intValue()).isEqualTo(1);

    // Overlapping periods are refused; the read is the same contract.
    JsonNode again = expect(preview(w, employee, command), 200);
    problem(issue(w, employee, command, again), 409, "CONTRACT_PERIOD_OVERLAP");
    JsonNode read =
        expect(
            call(
                get(EMPLOYEES + "/" + employee + "/contracts/" + contract.get("id").asText())
                    .header("Authorization", w.admin())),
            200);
    assertThat(read.get("snapshot")).isEqualTo(contract.get("snapshot"));
    JsonNode list =
        expect(
            call(get(EMPLOYEES + "/" + employee + "/contracts").header("Authorization", w.admin())),
            200);
    assertThat(list.get("items")).hasSize(1);
    assertThat(list.get("items").get(0).has("snapshot")).isFalse();

    // The integrity job finds nothing to report.
    assertThat(integrity.run().mismatches()).isZero();
  }

  @Test
  void issueRulesAreEnforcedBeforeAnythingIsWritten() throws Exception {
    World w = world();
    UUID employee = hire(w);
    // D10: a fixed-term contract needs an end date.
    String fixed = approvedVersion(w, "FIXED_TERM", BODY);
    JsonNode dates =
        problem(
            preview(w, employee, command(fixed, w.today(), null)), 422, "CONTRACT_DATES_INVALID");
    assertThat(dates.get("params").get("field").asText()).isEqualTo("endDate");
    problem(
        preview(w, employee, command(fixed, w.today(), w.today().minusDays(1))),
        422,
        "CONTRACT_DATES_INVALID");
    problem(
        preview(w, employee, command(fixed, w.today().minusDays(200), w.today())),
        422,
        "CONTRACT_DATES_INVALID");
    // D9: a placeholder without a value blocks issue (no end date on a permanent contract).
    String withEnd = approvedVersion(w, "PERMANENT", "# Contrat\nFin : {{contract.endDate}}.");
    JsonNode missing =
        problem(
            preview(w, employee, command(withEnd, w.today(), null)), 422, "CONTRACT_VALUE_MISSING");
    assertThat(missing.get("params").get("placeholder").asText()).isEqualTo("contract.endDate");
    // Unknown, foreign or malformed IDs are 404s.
    problem(
        preview(w, employee, command(UUID.randomUUID().toString(), w.today(), null)),
        404,
        "CONTRACT_TEMPLATE_NOT_FOUND");
    problem(
        preview(w, UUID.randomUUID(), command(fixed, w.today(), w.today())),
        404,
        "EMPLOYEE_NOT_FOUND");
    // A retired version cannot be issued.
    JsonNode template = template(w, "PERMANENT");
    String tid = template.get("id").asText();
    JsonNode approved = approve(w, tid, draft(w, tid, "fr", BODY));
    expect(
        postKeyed(
            w.admin(),
            TEMPLATES + "/" + tid + "/versions/" + approved.get("id").asText() + "/retire",
            Map.of("expectedVersion", approved.get("version").asLong())),
        200);
    problem(
        preview(w, employee, command(approved.get("id").asText(), w.today(), null)),
        409,
        "CONTRACT_TEMPLATE_NOT_APPROVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents.contract WHERE tenant_id = ?",
                Integer.class,
                w.org().tenant()))
        .isZero();
    // The type warning is shown when the classification differs (none recorded here).
    JsonNode preview =
        expect(preview(w, employee, command(fixed, w.today(), w.today().plusDays(90))), 200);
    assertThat(preview.get("warnings")).isEmpty();
  }

  @Test
  void voidingFreesThePeriodAndOnlyAnIssuedContractIsVoided() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String version = approvedVersion(w, "PERMANENT", BODY);
    JsonNode contract = issued(w, employee, version, w.today());
    String path = EMPLOYEES + "/" + employee + "/contracts/" + contract.get("id").asText();
    problem(
        postKeyed(w.admin(), path + "/void", Map.of("expectedVersion", 7, "reasonCode", "OTHER")),
        409,
        "CONTRACT_VERSION_CONFLICT");
    JsonNode voided =
        expect(
            postKeyed(
                w.admin(),
                path + "/void",
                Map.of("expectedVersion", 0, "reasonCode", "WRONG_TEMPLATE")),
            200);
    assertThat(voided.get("state").asText()).isEqualTo("VOID");
    assertThat(voided.get("voidReason").asText()).isEqualTo("WRONG_TEMPLATE");
    assertThat(voided.get("snapshot")).isEqualTo(contract.get("snapshot"));
    problem(
        postKeyed(w.admin(), path + "/void", Map.of("expectedVersion", 1, "reasonCode", "OTHER")),
        409,
        "CONTRACT_NOT_VOIDABLE");
    // The period is free again.
    issued(w, employee, version, w.today());
  }

  // ------------------------------------------------------------------------------------------
  // Employee self-service
  // ------------------------------------------------------------------------------------------

  @Test
  void theEmployeeAcknowledgesTheirOwnContractOnceWithBoundEvidence() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String bearer = linkedEmployee(w, employee);
    String version = approvedVersion(w, "PERMANENT", BODY);
    JsonNode contract = issued(w, employee, version, w.today());
    String id = contract.get("id").asText();

    JsonNode list = expect(call(get(MINE).header("Authorization", bearer)), 200);
    assertThat(list.get("items")).hasSize(1);
    assertThat(list.get("items").get(0).has("templateId")).isFalse();
    JsonNode mine = expect(call(get(MINE + "/" + id).header("Authorization", bearer)), 200);
    assertThat(mine.get("snapshot")).isEqualTo(contract.get("snapshot"));
    assertThat(mine.get("statements")).hasSize(2);
    assertThat(mine.get("statements").get(0).get("sha256").asText())
        .isEqualTo(AcknowledgementStatement.find("RECEIVED_AND_REVIEWED", 1, "fr").get().sha256());

    // A changed snapshot or statement digest is refused (A30-4); wrong versions are 400.
    Map<String, Object> changed = acknowledgement(mine, "fr");
    changed.put("snapshotSha256", "a".repeat(64));
    problem(
        postKeyed(bearer, MINE + "/" + id + "/acknowledgement", changed),
        409,
        "CONTRACT_ACKNOWLEDGEMENT_CHANGED");
    Map<String, Object> downgraded = acknowledgement(mine, "fr");
    downgraded.put("statementVersion", 0);
    problem(
        postKeyed(bearer, MINE + "/" + id + "/acknowledgement", downgraded),
        400,
        "VALIDATION_FAILED");

    String key = Organizations.newKey();
    MvcResult first =
        call(
            postJson(
                    bearer,
                    MINE + "/" + id + "/acknowledgement",
                    JSON.writeValueAsString(acknowledgement(mine, "fr")))
                .header("Idempotency-Key", key));
    JsonNode result = expect(first, 200);
    assertThat(result.get("alreadyAcknowledged").asBoolean()).isFalse();
    JsonNode evidence = result.get("contract").get("acknowledgement");
    assertThat(result.get("contract").get("state").asText()).isEqualTo("ACKNOWLEDGED");
    assertThat(evidence.get("statementLocale").asText()).isEqualTo("fr");
    assertThat(evidence.get("statementText").asText()).contains("pas une signature électronique");
    assertThat(evidence.get("evidenceSha256").asText()).matches("^[0-9a-f]{64}$");
    assertThat(evidence.toString()).doesNotContain("membership").doesNotContain("link");

    // Same key: replay. Another key: the existing evidence, nothing written.
    MvcResult replay =
        call(
            postJson(
                    bearer,
                    MINE + "/" + id + "/acknowledgement",
                    JSON.writeValueAsString(acknowledgement(mine, "fr")))
                .header("Idempotency-Key", key));
    assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
    JsonNode second =
        expect(
            postKeyed(bearer, MINE + "/" + id + "/acknowledgement", acknowledgement(mine, "en")),
            200);
    assertThat(second.get("alreadyAcknowledged").asBoolean()).isTrue();
    assertThat(second.get("contract").get("acknowledgement").get("evidenceSha256"))
        .isEqualTo(evidence.get("evidenceSha256"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents.contract_acknowledgement WHERE contract_id = ?",
                Integer.class,
                UUID.fromString(id)))
        .isEqualTo(1);

    // The administrator sees the evidence; an acknowledged contract is never voided.
    JsonNode admin =
        expect(
            call(
                get(EMPLOYEES + "/" + employee + "/contracts/" + id)
                    .header("Authorization", w.admin())),
            200);
    assertThat(admin.get("acknowledgement").get("evidenceSha256"))
        .isEqualTo(evidence.get("evidenceSha256"));
    problem(
        postKeyed(
            w.admin(),
            EMPLOYEES + "/" + employee + "/contracts/" + id + "/void",
            Map.of("expectedVersion", 1, "reasonCode", "OTHER")),
        409,
        "CONTRACT_NOT_VOIDABLE");
    assertPrivate(w);
  }

  @Test
  void anotherEmployeesOrTenantsContractIsTheSameNotFound() throws Exception {
    World w = world();
    UUID owner = hire(w);
    UUID other = hire(w);
    String otherBearer = linkedEmployee(w, other);
    String version = approvedVersion(w, "PERMANENT", BODY);
    String id = issued(w, owner, version, w.today()).get("id").asText();
    problem(
        call(get(MINE + "/" + id).header("Authorization", otherBearer)), 404, "CONTRACT_NOT_FOUND");
    problem(
        call(get(MINE + "/not-a-uuid").header("Authorization", otherBearer)),
        404,
        "CONTRACT_NOT_FOUND");
    JsonNode owned =
        expect(
            call(
                get(EMPLOYEES + "/" + owner + "/contracts/" + id)
                    .header("Authorization", w.admin())),
            200);
    Map<String, Object> foreignAck = new LinkedHashMap<>();
    foreignAck.put("snapshotSha256", owned.get("integrity").get("snapshotSha256").asText());
    foreignAck.put("snapshotDigestVersion", 1);
    foreignAck.put("grammarVersion", 1);
    foreignAck.put("rendererVersion", 1);
    foreignAck.put("statementCode", "RECEIVED_AND_REVIEWED");
    foreignAck.put("statementVersion", 1);
    foreignAck.put("statementLocale", "fr");
    foreignAck.put(
        "statementSha256",
        AcknowledgementStatement.find("RECEIVED_AND_REVIEWED", 1, "fr").get().sha256());
    problem(
        postKeyed(otherBearer, MINE + "/" + id + "/acknowledgement", foreignAck),
        404,
        "CONTRACT_NOT_FOUND");
    assertThat(expect(call(get(MINE).header("Authorization", otherBearer)), 200).get("items"))
        .isEmpty();
    // Another tenant's administrator.
    World foreign = world();
    problem(
        call(
            get(EMPLOYEES + "/" + owner + "/contracts/" + id)
                .header("Authorization", foreign.admin())),
        404,
        "CONTRACT_NOT_FOUND");
    // A void contract cannot be acknowledged.
    String ownerBearer = linkedEmployee(w, owner);
    JsonNode mine = expect(call(get(MINE + "/" + id).header("Authorization", ownerBearer)), 200);
    expect(
        postKeyed(
            w.admin(),
            EMPLOYEES + "/" + owner + "/contracts/" + id + "/void",
            Map.of("expectedVersion", 0, "reasonCode", "ISSUED_IN_ERROR")),
        200);
    problem(
        postKeyed(ownerBearer, MINE + "/" + id + "/acknowledgement", acknowledgement(mine, "fr")),
        409,
        "CONTRACT_NOT_ACKNOWLEDGEABLE");
  }

  @Test
  void anUnlinkedEmployeeIsRefusedAndAnAdministratorHasNoSelfServicePath() throws Exception {
    World w = world();
    Member unlinked = member(w, "employee");
    problem(
        call(get(MINE).header("Authorization", bearer(w.org().tenant(), unlinked))),
        403,
        "EMPLOYEE_LINK_REQUIRED");
    // The administrator's token has no employee role: the role gate refuses first.
    assertThat(call(get(MINE).header("Authorization", w.admin())).getResponse().getStatus())
        .isEqualTo(403);
    // An employee never reaches the administrator endpoints, even with a malformed body.
    assertThat(
            call(postJson(bearer(w.org().tenant(), unlinked), TEMPLATES, "{\"code\":")
                    .header("Idempotency-Key", Organizations.newKey()))
                .getResponse()
                .getStatus())
        .isEqualTo(403);
  }

  @Test
  void aSeparationEndsSelfServiceAtOnceAndBlocksNewIssues() throws Exception {
    World w = world();
    UUID employee = hire(w);
    String bearer = linkedEmployee(w, employee);
    String version = approvedVersion(w, "PERMANENT", BODY);
    String id = issued(w, employee, version, w.today()).get("id").asText();
    expect(call(get(MINE + "/" + id).header("Authorization", bearer)), 200);
    JsonNode mine = expect(call(get(MINE + "/" + id).header("Authorization", bearer)), 200);

    Map<String, Object> separation = new LinkedHashMap<>();
    separation.put("lastDay", w.today().toString());
    separation.put("reasonCode", "RESIGNATION");
    separation.put("accessTiming", "IMMEDIATELY");
    JsonNode preview =
        expect(
            call(
                postJson(
                    w.admin(),
                    EMPLOYEES + "/" + employee + "/separations/preview",
                    JSON.writeValueAsString(separation))),
            200);
    Map<String, Object> commit = new LinkedHashMap<>(separation);
    commit.put("expectedVersion", preview.get("expectedVersion").asLong());
    commit.put("previewDigest", preview.get("previewDigest").asText());
    List<String> acks = new java.util.ArrayList<>();
    preview.get("requiredAcknowledgements").forEach(a -> acks.add(a.asText()));
    commit.put("acknowledgements", acks);
    expect(postKeyed(w.admin(), EMPLOYEES + "/" + employee + "/separations", commit), 201);

    // The membership gate refuses before any binding: no acknowledgement after access ended.
    assertThat(call(get(MINE).header("Authorization", bearer)).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(
            postKeyed(bearer, MINE + "/" + id + "/acknowledgement", acknowledgement(mine, "fr"))
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    // No new contract while a separation is recorded; the issued one is unchanged.
    problem(
        preview(w, employee, command(version, w.today().minusDays(10), null)),
        409,
        "CONTRACT_EMPLOYMENT_ENDED");
    JsonNode admin =
        expect(
            call(
                get(EMPLOYEES + "/" + employee + "/contracts/" + id)
                    .header("Authorization", w.admin())),
            200);
    assertThat(admin.get("state").asText()).isEqualTo("ISSUED");
  }

  // ------------------------------------------------------------------------------------------
  // Privacy
  // ------------------------------------------------------------------------------------------

  /** Audit metadata keys are allow-listed; names, text and dates never reach audit or outbox. */
  private void assertPrivate(World w) {
    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT action, metadata::text AS metadata, after_state_sha256 FROM"
                + " platform.audit_event WHERE tenant_id = ? AND action LIKE 'contract%'",
            w.org().tenant());
    Set<String> actions = new TreeSet<>();
    Set<String> keys = new TreeSet<>();
    for (Map<String, Object> audit : audits) {
      actions.add((String) audit.get("action"));
      try {
        JSON.readTree((String) audit.get("metadata")).fieldNames().forEachRemaining(keys::add);
      } catch (Exception malformed) {
        throw new IllegalStateException(malformed);
      }
      assertThat((String) audit.get("after_state_sha256")).matches("^[0-9a-f]{64}$");
      assertThat((String) audit.get("metadata"))
          .doesNotContainIgnoringCase("bénédicte")
          .doesNotContainIgnoringCase("mbuyi")
          .doesNotContain("Contrat")
          .doesNotContain("PERMANENT")
          .doesNotContain("\"fr\"")
          .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }
    assertThat(actions)
        .contains(
            "contract-template.create",
            "contract-template-version.create",
            "contract-template-version.approve",
            "contract.preview",
            "contract.issue",
            "contract.self-read",
            "contract.acknowledge",
            "contract.read");
    assertThat(METADATA_KEYS).containsAll(keys);
    String outbox =
        jdbc.queryForObject(
            "SELECT coalesce(string_agg((envelope->'data')::text, ' '), '') FROM"
                + " platform.outbox_event WHERE tenant_id = ? AND event_type LIKE 'documents.%'",
            String.class, w.org().tenant());
    assertThat(outbox)
        .contains("contractId")
        .doesNotContainIgnoringCase("bénédicte")
        .doesNotContain("Contrat")
        .doesNotContain("PERMANENT")
        .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
  }

  @Test
  void logsCarryNoNamesTemplateTextOrDigests(CapturedOutput output) throws Exception {
    World w = world();
    UUID employee = hire(w);
    String bearer = linkedEmployee(w, employee);
    String version = approvedVersion(w, "PERMANENT", BODY);
    JsonNode contract = issued(w, employee, version, w.today());
    String id = contract.get("id").asText();
    JsonNode mine = expect(call(get(MINE + "/" + id).header("Authorization", bearer)), 200);
    expect(
        postKeyed(bearer, MINE + "/" + id + "/acknowledgement", acknowledgement(mine, "en")), 200);
    // Application logs are structured JSON lines (MockMvc's own request dumps are test output).
    String logs =
        String.join(
            "\n",
            output.getAll().lines().filter(line -> line.startsWith("{\"@timestamp\"")).toList());
    assertThat(logs)
        .contains("contract_disclosure")
        .doesNotContainIgnoringCase("bénédicte")
        .doesNotContainIgnoringCase("mbuyi")
        .doesNotContain(TITLE)
        .doesNotContain("Embauche le")
        .doesNotContain(contract.get("integrity").get("snapshotSha256").asText())
        .doesNotContain(mine.get("statements").get(1).get("text").asText());
  }
}
