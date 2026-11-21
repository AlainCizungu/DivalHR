package com.divalhr.core.integrations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.divalhr.core.support.TestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVP-120D test helpers. Every organization, person, address, reference and note here is SYNTHETIC:
 * the names are marked and the domains are reserved example domains.
 */
final class PartnerWorld {

  static final ObjectMapper JSON = new ObjectMapper();
  static final String PUBLIC = "/api/v1/public/partner-applications";
  static final String PLATFORM = "/api/v1/platform/partner-applications";

  private PartnerWorld() {}

  /** A unique lowercase token. */
  static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** A valid SYNTHETIC English bank/microfinance application. */
  static Map<String, Object> bank(String legalName, String email) {
    String token = token();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("legalName", legalName);
    body.put("countryOfRegistration", "CD");
    body.put("countriesOfOperation", List.of("CD"));
    body.put("registrationReference", "SYN-RCCM-" + token);
    body.put("primaryCategory", "BANK");
    body.put("additionalCategories", List.of("MICROFINANCE"));
    body.put("requestedCapabilities", List.of("SALARY_ADVANCE", "EMPLOYEE_LOANS"));
    body.put("website", "https://partner-" + token + ".example.test/");
    body.put("contactName", "Synthetic Contact " + token);
    body.put("contactRole", "Head of Partnerships");
    body.put("businessEmail", email);
    body.put("businessPhone", "+243810000000");
    body.put("supportedLanguages", List.of("en", "fr"));
    body.put("apiAvailability", "PLANNED");
    body.put("regulatorName", "Synthetic Banking Regulator");
    body.put("licenceReference", "SYN-LICENCE-" + token);
    body.put(
        "serviceSummary", "Synthetic institution offering salary advances to partner employees.");
    body.put("privacyContactEmail", "privacy-" + token + "@example.test");
    body.put("acknowledgment", true);
    body.put("privacyNoticeVersion", "2026-10");
    body.put("consentVersion", "2026-10");
    body.put("locale", "en");
    return body;
  }

  /** A valid SYNTHETIC French insurer application (regulated). */
  static Map<String, Object> insurer(String legalName, String email) {
    Map<String, Object> body = bank(legalName, email);
    body.put("primaryCategory", "INSURANCE");
    body.put("additionalCategories", List.of("TELECOM"));
    body.put("requestedCapabilities", List.of("HEALTH_INSURANCE", "LIFE_INSURANCE"));
    body.put("locale", "fr");
    return body;
  }

  static String uniqueEmail() {
    return "synthetic-" + token() + "@example.test";
  }

  static String uniqueName() {
    return "Synthetic Partner " + token();
  }

  static MockHttpServletResponse submit(MockMvc mvc, Map<String, Object> body, String key)
      throws Exception {
    return mvc.perform(
            post(PUBLIC)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  static MockHttpServletResponse submit(MockMvc mvc, Map<String, Object> body) throws Exception {
    return submit(mvc, body, "synthetic-key-" + UUID.randomUUID());
  }

  static String adminToken(String subject) {
    return "Bearer "
        + TestTokens.token()
            .subject(subject)
            .tenant(TestTokens.TENANT_A)
            .roles(List.of("platform-admin"))
            .build();
  }

  static String admin() {
    return adminToken("sub-platform-" + UUID.randomUUID());
  }

  static MockHttpServletResponse read(MockMvc mvc, String bearer, String path) throws Exception {
    return mvc.perform(get(path).header("Authorization", bearer)).andReturn().getResponse();
  }

  static MockHttpServletResponse setCheck(
      MockMvc mvc,
      String bearer,
      String reference,
      String code,
      String result,
      int expectedVersion,
      String note)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("expectedVersion", expectedVersion);
    if (note != null) {
      body.put("note", note);
    }
    return mvc.perform(
            put(PLATFORM + "/" + reference + "/checklist/" + code)
                .header("Authorization", bearer)
                .header("Idempotency-Key", "synthetic-check-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  static MockHttpServletResponse transition(
      MockMvc mvc,
      String bearer,
      String reference,
      String to,
      int expectedVersion,
      String reason,
      boolean confirmed)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", to);
    body.put("expectedVersion", expectedVersion);
    if (reason != null) {
      body.put("reasonCode", reason);
    }
    body.put("confirmed", confirmed);
    return mvc.perform(
            post(PLATFORM + "/" + reference + ":transition")
                .header("Authorization", bearer)
                .header("Idempotency-Key", "synthetic-transition-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  static JsonNode body(MockHttpServletResponse response) throws Exception {
    return JSON.readTree(response.getContentAsString());
  }

  static String code(MockHttpServletResponse response) throws Exception {
    return body(response).path("code").asText();
  }

  /** The current application version, read through the API. */
  static int version(MockMvc mvc, String bearer, String reference) throws Exception {
    return body(read(mvc, bearer, PLATFORM + "/" + reference)).path("version").asInt();
  }

  /** The current version of one check, read through the API. */
  static int checkVersion(MockMvc mvc, String bearer, String reference, String code)
      throws Exception {
    for (JsonNode check : body(read(mvc, bearer, PLATFORM + "/" + reference)).path("checklist")) {
      if (code.equals(check.path("checkCode").asText())) {
        return check.path("version").asInt();
      }
    }
    throw new IllegalStateException("check not found");
  }

  /** Submits, starts the review and returns the reference (status UNDER_REVIEW). */
  static String underReview(MockMvc mvc, String bearer, Map<String, Object> application)
      throws Exception {
    String reference = body(submit(mvc, application)).path("reference").asText();
    MockHttpServletResponse started =
        transition(
            mvc, bearer, reference, "UNDER_REVIEW", version(mvc, bearer, reference), null, false);
    if (started.getStatus() != 200) {
      throw new IllegalStateException("review did not start: " + started.getStatus());
    }
    return reference;
  }

  /** The mandatory checks, in a fixed order. */
  static List<String> mandatory() {
    return List.of(
        "BUSINESS_IDENTITY",
        "REGISTRATION_REFERENCE",
        "REGULATORY_INFORMATION",
        "SERVICE_SCOPE",
        "PRIVACY_SECURITY_CONTACT",
        "TECHNICAL_API_READINESS");
  }

  /** Marks every mandatory check SATISFIED. */
  static void satisfyAll(MockMvc mvc, String bearer, String reference) throws Exception {
    for (String code : mandatory()) {
      MockHttpServletResponse response =
          setCheck(
              mvc,
              bearer,
              reference,
              code,
              "SATISFIED",
              checkVersion(mvc, bearer, reference, code),
              null);
      if (response.getStatus() != 200) {
        throw new IllegalStateException("check not recorded: " + code + " " + response.getStatus());
      }
    }
  }

  /** Total rows of every table in the identity, tenant and integrations schemas (not partner). */
  static long accessFootprint(JdbcTemplate jdbc) {
    List<String> tables =
        jdbc.queryForList(
            "SELECT schemaname || '.' || tablename FROM pg_tables"
                + " WHERE schemaname IN ('identity', 'tenant', 'integrations')"
                + " AND tablename NOT LIKE 'partner_application%'",
            String.class);
    long total = 0;
    List<String> failures = new ArrayList<>();
    for (String table : tables) {
      Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
      total += count == null ? 0 : count;
    }
    if (!failures.isEmpty()) {
      throw new IllegalStateException(failures.toString());
    }
    return total;
  }
}
