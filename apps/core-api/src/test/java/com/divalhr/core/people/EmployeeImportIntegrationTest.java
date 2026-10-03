package com.divalhr.core.people;

import static com.divalhr.core.support.EmployeeImports.ENGLISH_HEADER;
import static com.divalhr.core.support.EmployeeImports.FRENCH_HEADER;
import static com.divalhr.core.support.EmployeeImports.comma;
import static com.divalhr.core.support.EmployeeImports.commit;
import static com.divalhr.core.support.EmployeeImports.csv;
import static com.divalhr.core.support.EmployeeImports.semi;
import static com.divalhr.core.support.EmployeeImports.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.people.application.EmployeeImportJobs;
import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Memberships;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP-020 (issue #45, E1-E16, A20-1..A20-6): the employee import end to end against PostgreSQL.
 * Personal-data markers are seeded in every flow and must never appear outside the people tables
 * and the authorized preview.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class EmployeeImportIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String GIVEN = "Élodie Marie";
  private static final String FAMILY = "N’Kanza-Mbuyi";
  private static final String GIVEN_2 = "Jean-Pierre";
  private static final String FAMILY_2 = "O'Neil";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private EmployeeImportJobs jobs;

  private static String number() {
    return ("E" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)).toUpperCase();
  }

  private static String admin(Org org, String suffix) {
    return Hierarchy.bearer(
        org.tenant(), "sub-import-" + suffix + "-" + UUID.randomUUID(), "tenant-admin");
  }

  private JsonNode json(MvcResult result) throws Exception {
    return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private int count(String sql, Object... args) {
    return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
  }

  // ------------------------------------------------------------------------------------------
  // The French flow: semicolon file with errors, preview, acknowledgement, commit (E7, A20-1/2)
  // ------------------------------------------------------------------------------------------

  @Test
  void aFrenchSemicolonFileIsPreviewedAndItsValidRowsCommittedAtomically(CapturedOutput output)
      throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "fr");
    String n1 = number();
    String n2 = number();
    byte[] file =
        csv(
            List.of(
                FRENCH_HEADER,
                semi(
                    n1,
                    GIVEN,
                    FAMILY,
                    "2026-03-01",
                    org.legalEntity(),
                    org.site(),
                    org.department(),
                    "",
                    org.team()),
                semi(
                    n2.toLowerCase(),
                    "  " + GIVEN_2 + " ",
                    FAMILY_2,
                    "2026-03-02",
                    org.legalEntity(),
                    org.site(),
                    "",
                    org.costCenter(),
                    ""),
                semi(
                    "=cmd|' /C calc'!A0",
                    "+Mallory",
                    "@Evil",
                    "31/12/2026",
                    "LE-UNKNOWN",
                    org.site(),
                    "",
                    "",
                    ""),
                ";;;;;;;;"));
    JsonNode summary = EmployeeImports.uploaded(mvc, bearer, file);
    assertThat(summary.get("status").asText()).isEqualTo("VALIDATED");
    assertThat(summary.get("totalRows").asInt()).isEqualTo(3);
    assertThat(summary.get("validRows").asInt()).isEqualTo(2);
    assertThat(summary.get("invalidRows").asInt()).isEqualTo(1);
    assertThat(summary.get("delimiter").asText()).isEqualTo("SEMICOLON");
    assertThat(summary.get("headerLanguage").asText()).isEqualTo("fr");
    assertThat(summary.get("requiresAcknowledgement").asBoolean()).isTrue();
    assertThat(summary.get("createdCount").isNull()).isTrue();
    String importId = summary.get("id").asText();
    String digest = summary.get("previewDigest").asText();

    // Preview: valid rows carry normalized values, the invalid row only keys and codes.
    JsonNode page = EmployeeImports.rows(mvc, bearer, importId, "");
    assertThat(page.get("items")).hasSize(3);
    JsonNode first = page.get("items").get(0);
    assertThat(first.get("status").asText()).isEqualTo("VALID");
    assertThat(first.get("values").get("employeeNumber").asText()).isEqualTo(n1);
    assertThat(first.get("values").get("givenNames").asText()).isEqualTo(GIVEN);
    JsonNode second = page.get("items").get(1);
    assertThat(second.get("values").get("employeeNumber").asText()).isEqualTo(n2);
    assertThat(second.get("values").get("givenNames").asText()).isEqualTo(GIVEN_2);
    JsonNode invalid = page.get("items").get(2);
    assertThat(invalid.get("status").asText()).isEqualTo("INVALID");
    assertThat(invalid.get("values").isNull()).isTrue();
    assertThat(invalid.toString())
        .doesNotContain("cmd")
        .doesNotContain("Mallory")
        .doesNotContain("Evil")
        .doesNotContain("31/12");
    assertThat(invalid.get("errors").toString())
        .contains("{\"column\":\"employee_number\",\"code\":\"ROW_FORMAT\"}")
        .contains("{\"column\":\"given_names\",\"code\":\"ROW_FORMAT\"}")
        .contains("{\"column\":\"family_name\",\"code\":\"ROW_FORMAT\"}")
        .contains("{\"column\":\"start_date\",\"code\":\"ROW_DATE_FORMAT\"}");
    mvc.perform(
            get("/api/v1/employee-imports/" + importId + "/rows").header("Authorization", bearer))
        .andExpect(header().string("Cache-Control", "private, no-store"));

    // The acknowledgement is required while some rows are invalid.
    mvc.perform(commit(bearer, importId, Organizations.newKey(), digest, 2, false))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    String key = Organizations.newKey();
    MvcResult committed =
        mvc.perform(commit(bearer, importId, key, digest, 2, true))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andReturn();
    JsonNode result = json(committed);
    assertThat(result.get("status").asText()).isEqualTo("COMMITTED");
    assertThat(result.get("createdCount").asInt()).isEqualTo(2);
    assertThat(result.get("notImportedCount").asInt()).isEqualTo(1);
    assertThat(result.toString()).doesNotContain(n1).doesNotContain(GIVEN);

    // Employees and employments, with the team's own parent derived when only the team is given.
    assertThat(count("SELECT count(*) FROM people.employee WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(2);
    Map<String, Object> employee =
        jdbc.queryForMap(
            "SELECT e.given_names, e.family_name, m.legal_entity_id, m.site_id, m.department_id,"
                + " m.cost_center_id, m.team_id, m.effective_from::text AS start, m.effective_to"
                + " FROM people.employee e JOIN people.employment m ON m.employee_id = e.id"
                + " WHERE e.tenant_id = ? AND e.employee_number = ?",
            org.tenant(),
            n1);
    assertThat(employee.get("given_names")).isEqualTo(GIVEN);
    assertThat(employee.get("family_name")).isEqualTo(FAMILY);
    assertThat(employee.get("department_id")).isEqualTo(org.departmentId());
    assertThat(employee.get("team_id")).isEqualTo(org.teamId());
    assertThat(employee.get("start")).isEqualTo("2026-03-01");
    assertThat(employee.get("effective_to")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT given_names FROM people.employee WHERE tenant_id = ? AND employee_number ="
                    + " ?",
                String.class,
                org.tenant(),
                n2))
        .isEqualTo(GIVEN_2);

    // After commit: no staged value and no link to the created employees (A20-2).
    JsonNode closed = EmployeeImports.rows(mvc, bearer, importId, "");
    for (JsonNode row : closed.get("items")) {
      assertThat(row.get("values").isNull()).isTrue();
      assertThat(row.get("status").asText()).isIn("CREATED", "NOT_IMPORTED");
    }
    assertThat(
            count(
                "SELECT count(*) FROM people.employee_import_row WHERE import_id = ?"
                    + " AND num_nonnulls(employee_number, given_names, family_name, start_date,"
                    + " legal_entity_code, site_code) > 0",
                UUID.fromString(importId)))
        .isZero();

    // Replay with the same key; another key on a committed import is refused.
    mvc.perform(commit(bearer, importId, key, digest, 2, true))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    mvc.perform(commit(bearer, importId, Organizations.newKey(), digest, 2, true))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_NOT_COMMITTABLE"))
        .andExpect(jsonPath("$.params.status").value("COMMITTED"));
    assertThat(count("SELECT count(*) FROM people.employee WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(2);

    // A20-1: audit metadata and outbox data carry identifiers and counts only.
    String audit =
        jdbc.queryForList(
                "SELECT action, metadata::text FROM platform.audit_event WHERE tenant_id = ?"
                    + " AND action LIKE 'employee%'",
                org.tenant())
            .toString();
    String outbox =
        jdbc.queryForList(
                "SELECT event_type, envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type LIKE 'people.%'",
                org.tenant())
            .toString();
    assertThat(audit)
        .contains("employee-import.create")
        .contains("employee-import.commit")
        .contains("employee.create");
    assertThat(outbox)
        .contains("people.employee.created.v1")
        .contains("people.employee-import.completed.v1");
    for (String evidence : List.of(audit, outbox, output.getAll())) {
      assertThat(evidence)
          .doesNotContain(n1)
          .doesNotContain(n2)
          .doesNotContain(GIVEN)
          .doesNotContain(FAMILY)
          .doesNotContain(GIVEN_2)
          .doesNotContain("2026-03-01")
          .doesNotContain("2026-03-02")
          .doesNotContain(org.legalEntityId().toString())
          .doesNotContain(org.siteId().toString())
          .doesNotContain(org.departmentId().toString())
          .doesNotContain(org.teamId().toString())
          .doesNotContain(org.legalEntity())
          .doesNotContain("Mallory");
    }
    JsonNode created =
        JSON.readTree(
            jdbc.queryForObject(
                "SELECT envelope::text FROM platform.outbox_event WHERE tenant_id = ?"
                    + " AND event_type = 'people.employee.created.v1' LIMIT 1",
                String.class,
                org.tenant()));
    List<String> fields = new ArrayList<>();
    created.get("data").fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("employeeId", "employmentId", "importId");
  }

  // ------------------------------------------------------------------------------------------
  // English comma file, BOM, mixed headers, template (section 2)
  // ------------------------------------------------------------------------------------------

  @Test
  void englishCommaFilesWithABomAndMixedHeadersAreAccepted() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "en");
    byte[] english =
        csv(
            List.of(
                ENGLISH_HEADER,
                comma(
                    number(),
                    "Grace",
                    "Hopper",
                    "2026-02-01",
                    org.legalEntity(),
                    org.site(),
                    "",
                    "",
                    "")));
    byte[] withBom = new byte[english.length + 3];
    withBom[0] = (byte) 0xEF;
    withBom[1] = (byte) 0xBB;
    withBom[2] = (byte) 0xBF;
    System.arraycopy(english, 0, withBom, 3, english.length);
    JsonNode summary = EmployeeImports.uploaded(mvc, bearer, withBom);
    assertThat(summary.get("delimiter").asText()).isEqualTo("COMMA");
    assertThat(summary.get("headerLanguage").asText()).isEqualTo("en");
    assertThat(summary.get("validRows").asInt()).isEqualTo(1);
    assertThat(summary.get("requiresAcknowledgement").asBoolean()).isFalse();

    // Mixed, re-ordered, accent- and case-insensitive headers; optional columns omitted.
    byte[] mixed =
        csv(
            List.of(
                "SITE CODE;matricule;given_names;NOM DE FAMILLE;date d'entree;Legal Entity Code",
                semi(org.site(), number(), "Ada", "Lovelace", "2026-02-02", org.legalEntity())));
    JsonNode mixedSummary = EmployeeImports.uploaded(mvc, bearer, mixed);
    assertThat(mixedSummary.get("headerLanguage").asText()).isEqualTo("mixed");
    assertThat(mixedSummary.get("validRows").asInt()).isEqualTo(1);
  }

  @Test
  void theTemplateIsTheHeaderOnlyInEachLanguage() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "template");
    for (String lang : List.of("fr", "en")) {
      MvcResult result =
          mvc.perform(
                  get("/api/v1/employee-imports/template?lang=" + lang)
                      .header("Authorization", bearer))
              .andExpect(status().isOk())
              .andExpect(
                  header()
                      .string(
                          "Content-Disposition",
                          "attachment; filename=\"divalhr-employee-import-" + lang + ".csv\""))
              .andReturn();
      byte[] bytes = result.getResponse().getContentAsByteArray();
      assertThat(bytes[0]).isEqualTo((byte) 0xEF);
      String text = new String(bytes, StandardCharsets.UTF_8);
      assertThat(text.lines().count()).isEqualTo(1);
      assertThat(text).contains(lang.equals("fr") ? "\"Matricule\"" : "\"Employee number\"");
      // The template itself is accepted as a header.
      mvc.perform(upload(bearer, Organizations.newKey(), bytes))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.params.reason").value("NO_ROWS"));
    }
    mvc.perform(get("/api/v1/employee-imports/template?lang=de").header("Authorization", bearer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // ------------------------------------------------------------------------------------------
  // File-level problems: nothing is staged and nothing is echoed (sections 1, 7, 8)
  // ------------------------------------------------------------------------------------------

  @Test
  void fileLevelProblemsCreateNothingAndEchoNothing() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String row =
        semi(number(), "Ana", "Secret", "2026-02-01", org.legalEntity(), org.site(), "", "", "");
    Map<String, byte[]> files = new java.util.LinkedHashMap<>();
    files.put("EMPTY", "\n\n".getBytes(StandardCharsets.UTF_8));
    files.put(
        "ENCODING",
        (FRENCH_HEADER + "\n" + row.replace("Ana", "Ané")).getBytes(StandardCharsets.ISO_8859_1));
    files.put("COLUMN_UNKNOWN", csv(List.of(FRENCH_HEADER + ";Salaire", row + ";1000")));
    files.put("COLUMN_DUPLICATE", csv(List.of(FRENCH_HEADER + ";Matricule", row + ";X")));
    files.put(
        "COLUMN_MISSING", csv(List.of("Matricule;Prénoms;Nom de famille;Code du site", "A;B;C;D")));
    files.put(
        "MALFORMED",
        csv(List.of(FRENCH_HEADER, "\"abc\"def;" + row.substring(row.indexOf(';') + 1))));
    files.put("LINE_TOO_LONG", csv(List.of(FRENCH_HEADER, "x".repeat(5000))));
    files.put("NO_ROWS", csv(List.of(FRENCH_HEADER)));
    List<String> many = new ArrayList<>();
    many.add(FRENCH_HEADER);
    for (int i = 0; i < 1001; i++) {
      many.add(
          semi("N" + i, "Ana", "Secret", "2026-02-01", org.legalEntity(), org.site(), "", "", ""));
    }
    files.put("TOO_MANY_ROWS", csv(many));
    int i = 0;
    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      // A fresh administrator each time keeps within the per-subject limit.
      String bearer = admin(org, "file-" + i++);
      MvcResult result =
          mvc.perform(upload(bearer, Organizations.newKey(), entry.getValue()))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("IMPORT_FILE_INVALID"))
              .andExpect(jsonPath("$.params.reason").value(entry.getKey()))
              .andReturn();
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("Secret")
          .doesNotContain("Salaire");
    }
    mvc.perform(upload(admin(org, "col"), Organizations.newKey(), files.get("COLUMN_UNKNOWN")))
        .andExpect(jsonPath("$.params.column").value(10));
    mvc.perform(upload(admin(org, "missing"), Organizations.newKey(), files.get("COLUMN_MISSING")))
        .andExpect(jsonPath("$.params.column").value("start_date"));

    // Size cap: one byte over 2 MiB is refused with 413.
    byte[] large = new byte[2 * 1024 * 1024 + 1];
    java.util.Arrays.fill(large, (byte) 'a');
    mvc.perform(upload(admin(org, "large"), Organizations.newKey(), large))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.code").value("IMPORT_FILE_TOO_LARGE"));

    // Multipart (and any other media type) is refused unread.
    mvc.perform(
            post("/api/v1/employee-imports")
                .header("Authorization", admin(org, "multipart"))
                .header("Idempotency-Key", Organizations.newKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .content(
                    "--x\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nSecret\r\n--x--"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.params.fields[0].field").value("Content-Type"));
    assertThat(
            count("SELECT count(*) FROM people.employee_import WHERE tenant_id = ?", org.tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Row rules, boundaries and code-point counting (sections 1, 2, 8, 10; A20-6)
  // ------------------------------------------------------------------------------------------

  @Test
  void everyRowRuleHasItsStableCodeAndLengthsCountCodePointsAfterNfc() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    Org other = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "rules");
    String le = org.legalEntity();
    String site = org.site();
    String existing = number();
    // Seed an employee with an existing number.
    JsonNode seeded =
        EmployeeImports.uploaded(
            mvc,
            bearer,
            csv(
                List.of(
                    FRENCH_HEADER,
                    semi(existing, "Ana", "Seed", "2026-02-01", le, site, "", "", ""))));
    mvc.perform(
            commit(
                bearer,
                seeded.get("id").asText(),
                Organizations.newKey(),
                seeded.get("previewDigest").asText(),
                1,
                false))
        .andExpect(status().isOk());
    String repeated = number();
    String hundred = "é".repeat(100);
    // A decomposed 'e' + combining acute (2 code points) becomes 1 code point after NFC.
    String decomposedHundred = "é".repeat(100);
    List<String> lines =
        List.of(
            FRENCH_HEADER,
            semi(
                number(),
                hundred,
                decomposedHundred,
                "2026-02-01",
                le,
                site,
                "",
                "",
                ""), // 1 valid
            semi(number(), "é".repeat(101), "Ok", "2026-02-01", le, site, "", "", ""), // 2 too long
            semi(number(), "Tab\there", "Ok", "2026-02-01", le, site, "", "", ""), // 3 control
            semi(number(), "Bidi‮evil", "Ok", "2026-02-01", le, site, "", "", ""), // 4 format char
            semi(
                number(),
                "\"Line\nbreak\"",
                "Ok",
                "2026-02-01",
                le,
                site,
                "",
                "",
                ""), // 5 quoted LF
            semi(number(), "Ana", "Ok", "2026-02-30", le, site, "", "", ""), // 6 no such date
            semi(number(), "Ana", "Ok", "1899-12-31", le, site, "", "", ""), // 7 range
            semi(repeated, "Ana", "Ok", "2026-02-01", le, site, "", "", ""), // 8 repeated
            semi(repeated, "Ana", "Ok", "2026-02-01", le, site, "", "", ""), // 9 repeated
            semi(existing, "Ana", "Ok", "2026-02-01", le, site, "", "", ""), // 10 exists
            semi(
                number(),
                "Ana",
                "Ok",
                "2026-02-01",
                other.legalEntity(),
                site,
                "",
                "",
                ""), // 11 foreign
            semi(number(), "Ana", "Ok", "2025-12-31", le, site, "", "", ""), // 12 not effective
            semi(
                number(),
                "Ana",
                "Ok",
                "2026-02-01",
                le,
                site,
                org.department(),
                org.costCenter(),
                ""), // 13
            semi(
                number(),
                "Ana",
                "Ok",
                "2026-02-01",
                le,
                site,
                "",
                org.costCenter(),
                org.team()), // 14
            semi(number(), "", "Ok", "2026-02-01", le, site, "", "", ""), // 15 required
            "only;three;cells", // 16 shape
            semi(
                number(), "x".repeat(161), "Ok", "2026-02-01", le, site, "", "", ""), // 17 cell cap
            semi("-1", "Ana", "Ok", "2026-02-01", le, site, "", "", "")); // 18 formula prefix
    JsonNode summary = EmployeeImports.uploaded(mvc, bearer, csv(lines));
    assertThat(summary.get("totalRows").asInt()).isEqualTo(18);
    assertThat(summary.get("validRows").asInt()).isEqualTo(1);
    JsonNode rows = EmployeeImports.rows(mvc, bearer, summary.get("id").asText(), "limit=100");
    Map<Integer, String> expected =
        Map.ofEntries(
            Map.entry(2, "given_names:ROW_TOO_LONG"),
            Map.entry(3, "given_names:ROW_CONTROL_CHARACTER"),
            Map.entry(4, "given_names:ROW_CONTROL_CHARACTER"),
            Map.entry(5, "given_names:ROW_CONTROL_CHARACTER"),
            Map.entry(6, "start_date:ROW_DATE_FORMAT"),
            Map.entry(7, "start_date:ROW_DATE_RANGE"),
            Map.entry(8, "employee_number:ROW_EMPLOYEE_NUMBER_REPEATED"),
            Map.entry(9, "employee_number:ROW_EMPLOYEE_NUMBER_REPEATED"),
            Map.entry(10, "employee_number:ROW_EMPLOYEE_NUMBER_EXISTS"),
            Map.entry(11, "legal_entity_code:ROW_UNIT_NOT_FOUND"),
            Map.entry(12, "legal_entity_code:ROW_UNIT_NOT_EFFECTIVE"),
            Map.entry(13, "cost_center_code:ROW_PARENT_AMBIGUOUS"),
            Map.entry(14, "team_code:ROW_UNIT_MISMATCH"),
            Map.entry(15, "given_names:ROW_REQUIRED"),
            Map.entry(16, "employee_number:ROW_SHAPE"),
            Map.entry(17, "given_names:ROW_TOO_LONG"),
            Map.entry(18, "employee_number:ROW_FORMAT"));
    for (JsonNode row : rows.get("items")) {
      int number = row.get("rowNumber").asInt();
      if (number == 1) {
        assertThat(row.get("status").asText()).isEqualTo("VALID");
        assertThat(row.get("values").get("familyName").asText())
            .hasSize(100)
            .isEqualTo("é".repeat(100));
        continue;
      }
      assertThat(row.get("status").asText()).as("row %s", number).isEqualTo("INVALID");
      List<String> errors = new ArrayList<>();
      for (JsonNode error : row.get("errors")) {
        errors.add(error.get("column").asText() + ":" + error.get("code").asText());
      }
      assertThat(errors).as("row %s", number).contains(expected.get(number));
      assertThat(row.get("values").isNull()).isTrue();
    }
    // Filters.
    assertThat(
            EmployeeImports.rows(mvc, bearer, summary.get("id").asText(), "status=valid")
                .get("items"))
        .hasSize(1);
    assertThat(
            EmployeeImports.rows(
                    mvc, bearer, summary.get("id").asText(), "status=invalid&limit=100")
                .get("items"))
        .hasSize(17);
  }

  // ------------------------------------------------------------------------------------------
  // Authorization before reading, tenant isolation (section 4)
  // ------------------------------------------------------------------------------------------

  @Test
  void unauthorizedCallersAreDeniedBeforeAnyByteIsReadAndOtherTenantsSeeNothing() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    Org other = EmployeeImports.newOrg(mvc);
    byte[] huge = new byte[10 * 1024 * 1024];
    java.util.Arrays.fill(huge, (byte) ';');
    String employee = "sub-import-employee-" + UUID.randomUUID();
    Memberships.grant(org.tenant(), employee, "employee");
    mvc.perform(
            upload(
                "Bearer "
                    + TestTokens.token()
                        .tenant(org.tenant())
                        .subject(employee)
                        .roles(List.of("employee"))
                        .build(),
                Organizations.newKey(),
                huge))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    String password = "sub-import-pwd-" + UUID.randomUUID();
    Memberships.grant(org.tenant(), password, "tenant-admin");
    mvc.perform(
            upload(
                "Bearer "
                    + TestTokens.token()
                        .tenant(org.tenant())
                        .subject(password)
                        .roles(List.of("tenant-admin"))
                        .acr(TestTokens.PASSWORD_ACR)
                        .build(),
                Organizations.newKey(),
                huge))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
    String stranger = "sub-import-stranger-" + UUID.randomUUID();
    mvc.perform(
            upload(
                "Bearer "
                    + TestTokens.token()
                        .tenant(org.tenant())
                        .subject(stranger)
                        .roles(List.of("tenant-admin"))
                        .build(),
                Organizations.newKey(),
                huge))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    assertThat(
            count("SELECT count(*) FROM people.employee_import WHERE tenant_id = ?", org.tenant()))
        .isZero();

    String bearer = admin(org, "owner");
    JsonNode summary =
        EmployeeImports.uploaded(
            mvc,
            bearer,
            csv(
                List.of(
                    FRENCH_HEADER,
                    semi(
                        number(),
                        "Ana",
                        "Iso",
                        "2026-02-01",
                        org.legalEntity(),
                        org.site(),
                        "",
                        "",
                        ""))));
    String id = summary.get("id").asText();
    String foreign = admin(other, "foreign");
    List<MvcResult> answers = new ArrayList<>();
    answers.add(
        mvc.perform(get("/api/v1/employee-imports/" + id).header("Authorization", foreign))
            .andReturn());
    answers.add(
        mvc.perform(
                get("/api/v1/employee-imports/" + id + "/rows").header("Authorization", foreign))
            .andReturn());
    answers.add(
        mvc.perform(
                commit(
                    foreign,
                    id,
                    Organizations.newKey(),
                    summary.get("previewDigest").asText(),
                    1,
                    false))
            .andReturn());
    answers.add(
        mvc.perform(
                post("/api/v1/employee-imports/" + id + "/discard")
                    .header("Authorization", foreign))
            .andReturn());
    answers.add(
        mvc.perform(get("/api/v1/employee-imports/not-a-uuid").header("Authorization", bearer))
            .andReturn());
    answers.add(
        mvc.perform(
                get("/api/v1/employee-imports/" + UUID.randomUUID())
                    .header("Authorization", bearer))
            .andReturn());
    for (MvcResult answer : answers) {
      assertThat(answer.getResponse().getStatus()).isEqualTo(404);
      JsonNode body = json(answer);
      assertThat(body.get("code").asText()).isEqualTo("EMPLOYEE_IMPORT_NOT_FOUND");
      assertThat(body.get("params").size()).isZero();
    }
    assertThat(count("SELECT count(*) FROM people.employee WHERE tenant_id = ?", other.tenant()))
        .isZero();
  }

  // ------------------------------------------------------------------------------------------
  // Idempotency, binding, staleness, concurrency (E8, E9)
  // ------------------------------------------------------------------------------------------

  @Test
  void uploadsReplayCommitsAreBoundToThePreviewAndStaleImportsCreateNothing() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org, "idem");
    String shared = number();
    byte[] file =
        csv(
            List.of(
                FRENCH_HEADER,
                semi(
                    shared,
                    "Ana",
                    "Idem",
                    "2026-02-01",
                    org.legalEntity(),
                    org.site(),
                    "",
                    "",
                    "")));
    String key = Organizations.newKey();
    JsonNode first =
        json(mvc.perform(upload(bearer, key, file)).andExpect(status().isCreated()).andReturn());
    mvc.perform(upload(bearer, key, file))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(first.get("id").asText()));
    mvc.perform(
            upload(
                bearer,
                key,
                csv(
                    List.of(
                        FRENCH_HEADER,
                        semi(
                            number(),
                            "Other",
                            "File",
                            "2026-02-01",
                            org.legalEntity(),
                            org.site(),
                            "",
                            "",
                            "")))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    String id = first.get("id").asText();
    String digest = first.get("previewDigest").asText();
    mvc.perform(commit(bearer, id, Organizations.newKey(), "0".repeat(64), 1, false))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_PREVIEW_CHANGED"));
    mvc.perform(commit(bearer, id, Organizations.newKey(), digest, 2, false))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_PREVIEW_CHANGED"));

    // A second import of the same number, committed first by another administrator: stale.
    String other = admin(org, "idem-2");
    JsonNode second = EmployeeImports.uploaded(mvc, other, file);
    mvc.perform(
            commit(
                other,
                second.get("id").asText(),
                Organizations.newKey(),
                second.get("previewDigest").asText(),
                1,
                false))
        .andExpect(status().isOk());
    mvc.perform(commit(bearer, id, Organizations.newKey(), digest, 1, false))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
    assertThat(count("SELECT count(*) FROM people.employee WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(1);
    mvc.perform(get("/api/v1/employee-imports/" + id).header("Authorization", bearer))
        .andExpect(jsonPath("$.status").value("VALIDATED"));
  }

  @Test
  void concurrentCommitsOfOneImportCreateEachEmployeeOnce() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    List<String> lines = new ArrayList<>();
    lines.add(FRENCH_HEADER);
    for (int i = 0; i < 50; i++) {
      lines.add(
          semi(
              number(),
              "Ana",
              "Concurrent",
              "2026-02-01",
              org.legalEntity(),
              org.site(),
              "",
              "",
              ""));
    }
    JsonNode summary = EmployeeImports.uploaded(mvc, admin(org, "conc"), csv(lines));
    String id = summary.get("id").asText();
    String digest = summary.get("previewDigest").asText();
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String bearer = admin(org, "conc-" + i);
      calls.add(
          () ->
              mvc.perform(commit(bearer, id, Organizations.newKey(), digest, 50, false))
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Integer> statuses = new ArrayList<>();
    try {
      for (Future<Integer> future : pool.invokeAll(calls)) {
        statuses.add(future.get());
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
    assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM people.employee WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(50);
    assertThat(count("SELECT count(*) FROM people.employment WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(50);
  }

  // ------------------------------------------------------------------------------------------
  // Open-import cap, discard, expiry and retention (E6, E12, A20-2)
  // ------------------------------------------------------------------------------------------

  @Test
  void openImportsAreCappedDiscardedExpiredAndTheirDetailsRetainedThirtyDays() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      ids.add(
          EmployeeImports.uploaded(
                  mvc,
                  admin(org, "cap-" + i),
                  csv(
                      List.of(
                          FRENCH_HEADER,
                          semi(
                              number(),
                              "Ana",
                              "Cap",
                              "2026-02-01",
                              org.legalEntity(),
                              org.site(),
                              "",
                              "",
                              ""))))
              .get("id")
              .asText());
    }
    byte[] fourth =
        csv(
            List.of(
                FRENCH_HEADER,
                semi(
                    number(),
                    "Ana",
                    "Cap",
                    "2026-02-01",
                    org.legalEntity(),
                    org.site(),
                    "",
                    "",
                    "")));
    mvc.perform(upload(admin(org, "cap-4"), Organizations.newKey(), fourth))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPORT_LIMIT_REACHED"));

    // Discard erases staged values; discarding again returns it unchanged; commit is refused.
    String bearer = admin(org, "discard");
    mvc.perform(
            post("/api/v1/employee-imports/" + ids.get(0) + "/discard")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISCARDED"));
    mvc.perform(
            post("/api/v1/employee-imports/" + ids.get(0) + "/discard")
                .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISCARDED"));
    mvc.perform(commit(bearer, ids.get(0), Organizations.newKey(), "0".repeat(64), 1, false))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.params.status").value("DISCARDED"));
    EmployeeImports.uploaded(mvc, admin(org, "cap-5"), fourth);

    // Expiry: staged values erased, audit with the job actor, commit refused as EXPIRED.
    jdbc.update(
        "UPDATE people.employee_import SET expires_at = created_at + interval '1 microsecond'"
            + " WHERE id = ?",
        UUID.fromString(ids.get(1)));
    assertThat(jobs.expireDue()).isGreaterThanOrEqualTo(1);
    mvc.perform(get("/api/v1/employee-imports/" + ids.get(1)).header("Authorization", bearer))
        .andExpect(jsonPath("$.status").value("EXPIRED"));
    assertThat(
            count(
                "SELECT count(*) FROM people.employee_import_row WHERE import_id = ?"
                    + " AND given_names IS NOT NULL",
                UUID.fromString(ids.get(1))))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM platform.audit_event WHERE action = 'employee-import.expire'"
                    + " AND resource_id = ? AND actor_subject = 'system:employee-import-expiry'",
                UUID.fromString(ids.get(1))))
        .isEqualTo(1);

    // Retention: closed imports older than 30 days are deleted; employees and audit stay.
    String committing = admin(org, "retained");
    JsonNode kept =
        EmployeeImports.uploaded(
            mvc,
            committing,
            csv(
                List.of(
                    FRENCH_HEADER,
                    semi(
                        number(),
                        "Ana",
                        "Kept",
                        "2026-02-01",
                        org.legalEntity(),
                        org.site(),
                        "",
                        "",
                        ""))));
    mvc.perform(
            commit(
                committing,
                kept.get("id").asText(),
                Organizations.newKey(),
                kept.get("previewDigest").asText(),
                1,
                false))
        .andExpect(status().isOk());
    jdbc.update(
        "UPDATE people.employee_import SET closed_at = now() - interval '31 days'"
            + " WHERE tenant_id = ? AND closed_at IS NOT NULL",
        org.tenant());
    int auditBefore =
        count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", org.tenant());
    assertThat(jobs.deleteRetained()).isGreaterThanOrEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM people.employee_import WHERE tenant_id = ? AND closed_at IS"
                    + " NOT NULL",
                org.tenant()))
        .isZero();
    assertThat(count("SELECT count(*) FROM people.employee WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM platform.audit_event WHERE tenant_id = ?", org.tenant()))
        .isEqualTo(auditBefore);
    mvc.perform(
            get("/api/v1/employee-imports/" + kept.get("id").asText())
                .header("Authorization", committing))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------------------------------
  // Telemetry privacy (section 9)
  // ------------------------------------------------------------------------------------------

  @Test
  void metricsCarryOnlyBoundedLabels() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    String marker = number();
    EmployeeImports.uploaded(
        mvc,
        admin(org, "metrics"),
        csv(
            List.of(
                FRENCH_HEADER,
                semi(
                    marker,
                    "Zéphyrin",
                    "Métrique",
                    "2026-02-01",
                    org.legalEntity(),
                    org.site(),
                    "",
                    "",
                    ""))));
    for (Meter meter : meters.getMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertThat(tag.getValue())
            .doesNotContain(marker)
            .doesNotContain("Zéphyrin")
            .doesNotContain(org.tenant().toString())
            .doesNotContain(org.legalEntity());
      }
    }
    assertThat(meters.find("divalhr.employee_import.rows").counters())
        .allSatisfy(
            c -> assertThat(c.getId().getTag("outcome")).isIn("valid", "invalid", "created"));
  }
}
