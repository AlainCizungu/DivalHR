package com.divalhr.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.EmployeeImports.Org;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** MVP-021 fixtures: employees hired through the MVP-020 import, and change requests. */
public final class Employees {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Employees() {}

  /**
   * A unique, well-formed employee number.
   *
   * @return number
   */
  public static String number() {
    return ("E" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
        .toUpperCase(Locale.ROOT);
  }

  /**
   * Hires one employee through an import, placed at the organization's site and department.
   *
   * @param mvc MockMvc
   * @param jdbc JDBC (to read the new ID)
   * @param org organization
   * @param bearer tenant administrator
   * @param number employee number
   * @param given given names
   * @param family family name
   * @param start start date
   * @return the employee ID
   * @throws Exception on failure
   */
  public static UUID hire(
      MockMvc mvc,
      JdbcTemplate jdbc,
      Org org,
      String bearer,
      String number,
      String given,
      String family,
      LocalDate start)
      throws Exception {
    byte[] file =
        EmployeeImports.csv(
            List.of(
                EmployeeImports.FRENCH_HEADER,
                EmployeeImports.semi(
                    number,
                    given,
                    family,
                    start.toString(),
                    org.legalEntity(),
                    org.site(),
                    org.department(),
                    "",
                    "")));
    JsonNode summary = EmployeeImports.uploaded(mvc, bearer, file);
    mvc.perform(
            EmployeeImports.commit(
                bearer,
                summary.get("id").asText(),
                Organizations.newKey(),
                summary.get("previewDigest").asText(),
                1,
                false))
        .andExpect(status().isOk());
    return jdbc.queryForObject(
        "SELECT id FROM people.employee WHERE tenant_id = ? AND employee_number = ?",
        UUID.class,
        org.tenant(),
        number);
  }

  /**
   * The business date of a tenant: today in its organization's time zone.
   *
   * @param jdbc JDBC
   * @param tenant tenant
   * @return today
   */
  public static LocalDate today(JdbcTemplate jdbc, UUID tenant) {
    String zone =
        jdbc.queryForObject(
            "SELECT timezone FROM tenant.organization WHERE id = ?", String.class, tenant);
    return LocalDate.now(ZoneId.of(zone));
  }

  /**
   * A JSON {@code POST} with bearer.
   *
   * @param bearer authorization header
   * @param path path
   * @param body JSON body
   * @return request
   */
  public static MockHttpServletRequestBuilder postJson(String bearer, String path, String body) {
    return post(path)
        .header("Authorization", bearer)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /**
   * Parses a response body.
   *
   * @param body UTF-8 JSON
   * @return the tree
   * @throws Exception on malformed JSON
   */
  public static JsonNode json(byte[] body) throws Exception {
    return JSON.readTree(new String(body, StandardCharsets.UTF_8));
  }
}
