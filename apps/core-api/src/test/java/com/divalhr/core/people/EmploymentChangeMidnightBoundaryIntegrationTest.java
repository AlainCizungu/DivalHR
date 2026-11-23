package com.divalhr.core.people;

import static com.divalhr.core.support.Employees.postJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Employees;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.divalhr.core.support.SettableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * DEVX-005 (Issue #222): an employment change dated with a site's local date stays valid on both
 * sides of the organization's midnight. The organization is in {@code Africa/Lubumbashi} (UTC+2)
 * and the site in {@code Africa/Kinshasa} (UTC+1), so for the hour between 22:00Z and 23:00Z the
 * site's date is one day behind the organization's business date and the change is retroactive. The
 * reason code keeps the request valid at either instant, in the preview and in the commit.
 */
@IntegrationTest
@Import(SettableClock.Config.class)
class EmploymentChangeMidnightBoundaryIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ZoneId SITE = ZoneId.of("Africa/Kinshasa");
  private static final String BASE = "/api/v1/employees";

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SettableClock clock;

  @AfterEach
  void followTheSystemClockAgain() {
    clock.reset();
  }

  @Test
  void aSiteDatedChangeIsValidJustBeforeAndJustAfterTheOrganizationMidnight() throws Exception {
    Org org = EmployeeImports.newOrg(mvc);
    jdbc.update(
        "UPDATE tenant.organization SET timezone = 'Africa/Lubumbashi' WHERE id = ?", org.tenant());
    String bearer =
        Hierarchy.bearer(org.tenant(), "sub-midnight-" + UUID.randomUUID(), "tenant-admin");
    LocalDate start = Employees.today(jdbc, org.tenant()).minusDays(20);
    UUID employee =
        Employees.hire(mvc, jdbc, org, bearer, Employees.number(), "Midnight", "Boundary", start);
    UUID manager =
        Employees.hire(mvc, jdbc, org, bearer, Employees.number(), "Midnight", "Manager", start);

    // 21:59:59Z is 23:59:59 in Lubumbashi and 22:59:59 in Kinshasa: the same date, a current
    // change.
    LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(5);
    Instant before = day.atTime(21, 59, 59).toInstant(ZoneOffset.UTC);
    // 22:00:00Z is 00:00 the next day in Lubumbashi and 23:00 the same day in Kinshasa.
    Instant after = day.atTime(22, 0, 0).toInstant(ZoneOffset.UTC);
    LocalDate siteDate = before.atZone(SITE).toLocalDate();
    assertThat(after.atZone(SITE).toLocalDate()).isEqualTo(siteDate);

    clock.set(before);
    assertThat(previewStatus(bearer, employee, siteDate, manager)).isEqualTo(200);

    clock.set(after);
    JsonNode preview = preview(bearer, employee, siteDate, manager);
    assertThat(preview.get("timing").asText()).isEqualTo("RETROACTIVE");
    Map<String, Object> command = command(siteDate, manager);
    Map<String, Object> body = new LinkedHashMap<>(command);
    body.put("expectedVersion", preview.get("expectedVersion").asLong());
    body.put("previewDigest", preview.get("previewDigest").asText());
    body.put("acknowledgeRetroactive", true);
    MvcResult committed =
        mvc.perform(
                postJson(
                        bearer,
                        BASE + "/" + employee + "/employment-changes",
                        JSON.writeValueAsString(body))
                    .header("Idempotency-Key", Organizations.newKey()))
            .andReturn();
    assertThat(committed.getResponse().getStatus())
        .as(committed.getResponse().getContentAsString())
        .isEqualTo(201);
  }

  private static Map<String, Object> command(LocalDate from, UUID manager) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", "CHANGE");
    command.put("effectiveFrom", from.toString());
    command.put("reasonCode", "LATE_NOTIFICATION");
    command.put("manager", Map.of("employeeId", manager.toString()));
    return command;
  }

  private int previewStatus(String bearer, UUID employee, LocalDate from, UUID manager)
      throws Exception {
    return mvc.perform(
            postJson(
                bearer,
                BASE + "/" + employee + "/employment-changes/preview",
                JSON.writeValueAsString(command(from, manager))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private JsonNode preview(String bearer, UUID employee, LocalDate from, UUID manager)
      throws Exception {
    MvcResult result =
        mvc.perform(
                postJson(
                    bearer,
                    BASE + "/" + employee + "/employment-changes/preview",
                    JSON.writeValueAsString(command(from, manager))))
            .andReturn();
    assertThat(result.getResponse().getStatus())
        .as(result.getResponse().getContentAsString())
        .isEqualTo(200);
    return Employees.json(result.getResponse().getContentAsByteArray());
  }
}
