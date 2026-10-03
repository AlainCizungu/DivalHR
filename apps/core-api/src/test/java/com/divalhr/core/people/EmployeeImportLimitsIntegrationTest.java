package com.divalhr.core.people;

import static com.divalhr.core.support.EmployeeImports.FRENCH_HEADER;
import static com.divalhr.core.support.EmployeeImports.commit;
import static com.divalhr.core.support.EmployeeImports.csv;
import static com.divalhr.core.support.EmployeeImports.semi;
import static com.divalhr.core.support.EmployeeImports.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.divalhr.core.support.EmployeeImports;
import com.divalhr.core.support.EmployeeImports.Org;
import com.divalhr.core.support.Hierarchy;
import com.divalhr.core.support.IntegrationTest;
import com.divalhr.core.support.Organizations;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP-020 request limits (A20-4) and timeouts (A20-5) with small values: the per-subject and the
 * per-tenant buckets apply independently, after authorization and before the body is read, and a
 * database timeout rolls everything back with {@code 503 IMPORT_TIMEOUT} and a retry succeeds.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
      "divalhr.request-limits.subject.employee-import=3",
      "divalhr.request-limits.tenant.employee-import-upload.requests=5",
      "divalhr.request-limits.tenant.employee-import-upload.window=10m",
      "divalhr.request-limits.tenant.employee-import-commit.requests=4",
      "divalhr.request-limits.tenant.employee-import-commit.window=10m",
      "divalhr.employee-import.validation-statement-timeout=1s",
      "divalhr.employee-import.commit-statement-timeout=1s"
    })
class EmployeeImportLimitsIntegrationTest {

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private static String admin(Org org) {
    return Hierarchy.bearer(org.tenant(), "sub-limit-" + UUID.randomUUID(), "tenant-admin");
  }

  private static byte[] file(Org org) {
    return csv(
        List.of(
            FRENCH_HEADER,
            semi(
                "L" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Ana",
                "Limite",
                "2026-02-01",
                org.legalEntity(),
                org.site(),
                "",
                "",
                "")));
  }

  /** Waits for a fresh minute and a fresh ten-minute window when either is about to end. */
  private static void headroom() throws InterruptedException {
    LocalTime now = LocalTime.now(ZoneOffset.UTC);
    int inTen = (now.getMinute() % 10) * 60 + now.getSecond();
    if (now.getSecond() >= 45 || inTen >= 585) {
      Thread.sleep((61 - now.getSecond()) * 1000L);
    }
  }

  @Test
  void theSubjectBucketLimitsOneAdministratorAcrossUploadAndCommit() throws Exception {
    headroom();
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org);
    long minute = Instant.now().getEpochSecond() / 60;
    // Three requests in the shared bucket (malformed bodies count: limits run before reading).
    mvc.perform(upload(bearer, "short", new byte[0])).andExpect(status().isBadRequest());
    mvc.perform(upload(bearer, "short", new byte[0])).andExpect(status().isBadRequest());
    mvc.perform(commit(bearer, UUID.randomUUID(), "short", "x", 0, false))
        .andExpect(status().isBadRequest());
    MvcResult limited = mvc.perform(upload(bearer, Organizations.newKey(), file(org))).andReturn();
    if (Instant.now().getEpochSecond() / 60 == minute) {
      assertThat(limited.getResponse().getStatus()).isEqualTo(429);
      assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
    }
    // Another administrator of the same tenant is not affected.
    mvc.perform(upload(admin(org), Organizations.newKey(), file(org)))
        .andExpect(status().isCreated());
  }

  @Test
  void theTenantBucketsLimitUploadsAndCommitsIndependentlyOfSubjects() throws Exception {
    headroom();
    Org org = EmployeeImports.newOrg(mvc);
    // Five uploads by five administrators: the tenant upload bucket is then used up.
    for (int i = 0; i < 5; i++) {
      mvc.perform(upload(admin(org), "short", new byte[0])).andExpect(status().isBadRequest());
    }
    MvcResult upload =
        mvc.perform(upload(admin(org), Organizations.newKey(), file(org))).andReturn();
    assertThat(upload.getResponse().getStatus()).isEqualTo(429);
    assertThat(upload.getResponse().getContentAsString()).contains("RATE_LIMITED");
    assertThat(upload.getResponse().getHeader("Retry-After")).isNotBlank();
    // Commits have their own tenant bucket.
    for (int i = 0; i < 4; i++) {
      mvc.perform(commit(admin(org), UUID.randomUUID(), "short", "x", 0, false))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            commit(admin(org), UUID.randomUUID(), Organizations.newKey(), "0".repeat(64), 1, false))
        .andExpect(status().isTooManyRequests());
    // Another tenant is not affected.
    Org other = EmployeeImports.newOrg(mvc);
    mvc.perform(upload(admin(other), Organizations.newKey(), file(other)))
        .andExpect(status().isCreated());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform.authorization_denial WHERE tenant_id = ?"
                    + " AND stage = 'rate_limit'",
                Integer.class,
                org.tenant()))
        .isEqualTo(2);
  }

  @Test
  void aDatabaseTimeoutRollsEverythingBackAndARetrySucceeds() throws Exception {
    headroom();
    Org org = EmployeeImports.newOrg(mvc);
    String bearer = admin(org);
    byte[] file = file(org);
    String uploadKey = Organizations.newKey();
    // Staging blocked: 503, nothing stored, the same key then succeeds.
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute("LOCK TABLE people.employee_import IN ACCESS EXCLUSIVE MODE");
      }
      Instant start = Instant.now();
      mvc.perform(upload(bearer, uploadKey, file))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("IMPORT_TIMEOUT"));
      assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
      lock.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employee_import WHERE tenant_id = ?",
                Integer.class,
                org.tenant()))
        .isZero();
    JsonNode summary =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                mvc.perform(upload(bearer, uploadKey, file))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
    String id = summary.get("id").asText();
    String commitKey = Organizations.newKey();
    String committer = admin(org);
    // Commit blocked: 503, nothing created, the import stays open, the same key then succeeds.
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute("LOCK TABLE people.employee IN ACCESS EXCLUSIVE MODE");
      }
      mvc.perform(commit(committer, id, commitKey, summary.get("previewDigest").asText(), 1, false))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("IMPORT_TIMEOUT"));
      lock.rollback();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM people.employee WHERE tenant_id = ?",
                Integer.class,
                org.tenant()))
        .isZero();
    mvc.perform(get("/api/v1/employee-imports/" + id).header("Authorization", committer))
        .andExpect(jsonPath("$.status").value("VALIDATED"));
    mvc.perform(commit(committer, id, commitKey, summary.get("previewDigest").asText(), 1, false))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMMITTED"));
  }
}
