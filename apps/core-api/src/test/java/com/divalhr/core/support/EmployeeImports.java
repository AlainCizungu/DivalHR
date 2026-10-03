package com.divalhr.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Helpers for MVP-020 employee import tests: an organization with units and CSV builders. */
public final class EmployeeImports {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** French header, semicolon-delimited, in template order. */
  public static final String FRENCH_HEADER =
      "Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;"
          + "Code du département;Code du centre de coût;Code de l’équipe";

  /** English header, comma-delimited, in template order. */
  public static final String ENGLISH_HEADER =
      "Employee number,Given names,Family name,Start date,Legal entity code,Site code,"
          + "Department code,Cost center code,Team code";

  private EmployeeImports() {}

  /**
   * One organization with a legal entity, a site, a department with a team and a cost center,
   * effective from 2026-01-01.
   *
   * @param tenant tenant
   * @param legalEntity legal entity code
   * @param site site code
   * @param department department code
   * @param costCenter cost center code
   * @param team team code
   * @param legalEntityId legal entity
   * @param siteId site
   * @param departmentId department
   * @param costCenterId cost center
   * @param teamId team
   */
  public record Org(
      UUID tenant,
      String legalEntity,
      String site,
      String department,
      String costCenter,
      String team,
      UUID legalEntityId,
      UUID siteId,
      UUID departmentId,
      UUID costCenterId,
      UUID teamId) {}

  /**
   * Creates a tenant with units.
   *
   * @param mvc MockMvc
   * @return the organization
   * @throws Exception on request failure
   */
  public static Org newOrg(MockMvc mvc) throws Exception {
    UUID tenant = Hierarchy.newTenant(mvc);
    String le = Hierarchy.code("LE");
    UUID leId = Hierarchy.newLegalEntity(mvc, tenant, le, "2026-01-01", null);
    String site = Hierarchy.code("ST");
    UUID siteId = Hierarchy.newSite(mvc, tenant, leId, site, "2026-01-01", null);
    String dept = Hierarchy.code("DP");
    UUID deptId =
        Hierarchy.newSiteUnit(mvc, "/api/v1/departments", tenant, siteId, dept, "2026-01-01", null);
    String cc = Hierarchy.code("CC");
    UUID ccId =
        Hierarchy.newSiteUnit(mvc, "/api/v1/cost-centers", tenant, siteId, cc, "2026-01-01", null);
    String team = Hierarchy.code("TM");
    UUID teamId = Hierarchy.newTeam(mvc, tenant, "departmentId", deptId, team, "2026-01-01", null);
    return new Org(tenant, le, site, dept, cc, team, leId, siteId, deptId, ccId, teamId);
  }

  /**
   * A CSV file from lines (LF line endings).
   *
   * @param lines lines, header first
   * @return UTF-8 bytes
   */
  public static byte[] csv(List<String> lines) {
    return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /**
   * A semicolon-delimited data line.
   *
   * @param cells cells
   * @return the line
   */
  public static String semi(String... cells) {
    return String.join(";", cells);
  }

  /**
   * A comma-delimited data line.
   *
   * @param cells cells
   * @return the line
   */
  public static String comma(String... cells) {
    return String.join(",", cells);
  }

  /**
   * An upload request.
   *
   * @param bearer authorization header
   * @param key idempotency key
   * @param bytes body
   * @return the request
   */
  public static MockHttpServletRequestBuilder upload(String bearer, String key, byte[] bytes) {
    return post("/api/v1/employee-imports")
        .header("Authorization", bearer)
        .header("Idempotency-Key", key)
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .content(bytes);
  }

  /**
   * A commit request.
   *
   * @param bearer authorization header
   * @param importId import
   * @param key idempotency key
   * @param digest preview digest
   * @param validRows valid rows
   * @param acknowledge acknowledgement
   * @return the request
   */
  public static MockHttpServletRequestBuilder commit(
      String bearer,
      Object importId,
      String key,
      String digest,
      int validRows,
      boolean acknowledge) {
    return post("/api/v1/employee-imports/" + importId + "/commit")
        .header("Authorization", bearer)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"previewDigest\":\""
                + digest
                + "\",\"validRows\":"
                + validRows
                + ",\"acknowledgeInvalidRows\":"
                + acknowledge
                + "}");
  }

  /**
   * Uploads and returns the import summary.
   *
   * @param mvc MockMvc
   * @param bearer authorization header
   * @param bytes body
   * @return the summary
   * @throws Exception on failure
   */
  public static JsonNode uploaded(MockMvc mvc, String bearer, byte[] bytes) throws Exception {
    String body =
        mvc.perform(upload(bearer, Organizations.newKey(), bytes))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JSON.readTree(body);
  }

  /**
   * Reads a page of rows.
   *
   * @param mvc MockMvc
   * @param bearer authorization header
   * @param importId import
   * @param query query string (without '?'), may be empty
   * @return the page
   * @throws Exception on failure
   */
  public static JsonNode rows(MockMvc mvc, String bearer, Object importId, String query)
      throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/employee-imports/"
                        + importId
                        + "/rows"
                        + (query.isEmpty() ? "" : "?" + query))
                    .header("Authorization", bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JSON.readTree(body);
  }
}
