package com.divalhr.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Test fixtures for MVP-002. Every test creates its own organizations through the real
 * platform-admin endpoint; no test relies on development fixtures.
 */
public final class Hierarchy {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Hierarchy() {}

  /**
   * Creates a fresh organization (tenant) through {@code POST /api/v1/organizations}.
   *
   * @param mvc MockMvc
   * @return the new tenant id
   * @throws Exception on request failure
   */
  public static UUID newTenant(MockMvc mvc) throws Exception {
    String bearer =
        "Bearer "
            + TestTokens.token()
                .subject("sub-fixture-platform")
                .roles(List.of("platform-admin"))
                .build();
    String body =
        mvc.perform(
                post("/api/v1/organizations")
                    .header("Authorization", bearer)
                    .header("Idempotency-Key", Organizations.newKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(Organizations.body(Organizations.uniqueName())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * A bearer token for a user of the tenant with the given roles.
   *
   * @param tenant tenant
   * @param subject subject
   * @param roles realm roles
   * @return {@code Authorization} header value
   */
  public static String bearer(UUID tenant, String subject, String... roles) {
    return "Bearer "
        + TestTokens.token().tenant(tenant).subject(subject).roles(List.of(roles)).build();
  }

  /**
   * A tenant-administrator bearer token.
   *
   * @param tenant tenant
   * @return {@code Authorization} header value
   */
  public static String admin(UUID tenant) {
    return bearer(tenant, "sub-admin-" + tenant, "tenant-admin");
  }

  /**
   * A unique, already normalized (upper-case) code with the given prefix.
   *
   * @param prefix up to 11 characters
   * @return code
   */
  public static String code(String prefix) {
    return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase(Locale.ROOT);
  }

  /**
   * A legal-entity request body.
   *
   * @param code code
   * @param name name
   * @param from effective from
   * @param to effective to or {@code null}
   * @return JSON
   */
  public static String legalEntity(String code, String name, String from, String to) {
    return """
    {"code": %s, "name": %s, "countryCode": "CD", "effectiveFrom": %s, "effectiveTo": %s}
    """
        .formatted(quote(code), quote(name), quote(from), quote(to));
  }

  /**
   * A site request body.
   *
   * @param legalEntityId parent
   * @param code code
   * @param name name
   * @param timezone time zone
   * @param from effective from
   * @param to effective to or {@code null}
   * @return JSON
   */
  public static String site(
      Object legalEntityId, String code, String name, String timezone, String from, String to) {
    return """
    {"legalEntityId": %s, "code": %s, "name": %s, "timezone": %s, "effectiveFrom": %s,
     "effectiveTo": %s}
    """
        .formatted(
            quote(legalEntityId == null ? null : legalEntityId.toString()),
            quote(code),
            quote(name),
            quote(timezone),
            quote(from),
            quote(to));
  }

  /**
   * {@code POST} with bearer, key and JSON body.
   *
   * @param path path
   * @param bearer bearer or {@code null}
   * @param key idempotency key or {@code null}
   * @param body JSON
   * @return request
   */
  public static MockHttpServletRequestBuilder create(
      String path, String bearer, String key, String body) {
    MockHttpServletRequestBuilder request =
        post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    if (bearer != null) {
      request.header("Authorization", bearer);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request;
  }

  /**
   * Creates a legal entity and returns its id.
   *
   * @param mvc MockMvc
   * @param tenant tenant
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return id
   * @throws Exception on request failure
   */
  public static UUID newLegalEntity(MockMvc mvc, UUID tenant, String code, String from, String to)
      throws Exception {
    String body =
        mvc.perform(
                create(
                    "/api/v1/legal-entities",
                    admin(tenant),
                    Organizations.newKey(),
                    legalEntity(code, "Société " + code, from, to)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * Creates a site and returns its id.
   *
   * @param mvc MockMvc
   * @param tenant tenant
   * @param parent legal entity
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return id
   * @throws Exception on request failure
   */
  public static UUID newSite(
      MockMvc mvc, UUID tenant, UUID parent, String code, String from, String to) throws Exception {
    String body =
        mvc.perform(
                create(
                    "/api/v1/sites",
                    admin(tenant),
                    Organizations.newKey(),
                    site(parent, code, "Site " + code, "Africa/Kinshasa", from, to)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * A department or cost-center request body.
   *
   * @param siteId parent
   * @param code code
   * @param name name
   * @param from effective from
   * @param to effective to or {@code null}
   * @return JSON
   */
  public static String siteUnit(Object siteId, String code, String name, String from, String to) {
    return """
    {"siteId": %s, "code": %s, "name": %s, "effectiveFrom": %s, "effectiveTo": %s}
    """
        .formatted(
            quote(siteId == null ? null : siteId.toString()),
            quote(code),
            quote(name),
            quote(from),
            quote(to));
  }

  /**
   * Creates a department or cost center and returns its id.
   *
   * @param mvc MockMvc
   * @param path {@code /api/v1/departments} or {@code /api/v1/cost-centers}
   * @param tenant tenant
   * @param siteId parent
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return id
   * @throws Exception on request failure
   */
  public static UUID newSiteUnit(
      MockMvc mvc, String path, UUID tenant, UUID siteId, String code, String from, String to)
      throws Exception {
    String body =
        mvc.perform(
                create(
                    path,
                    admin(tenant),
                    Organizations.newKey(),
                    siteUnit(siteId, code, "Unité " + code, from, to)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * A region request body.
   *
   * @param legalEntityId parent
   * @param code code
   * @param name name
   * @param from effective from
   * @param to effective to or {@code null}
   * @return JSON
   */
  public static String region(
      Object legalEntityId, String code, String name, String from, String to) {
    return """
    {"legalEntityId": %s, "code": %s, "name": %s, "effectiveFrom": %s, "effectiveTo": %s}
    """
        .formatted(
            quote(legalEntityId == null ? null : legalEntityId.toString()),
            quote(code),
            quote(name),
            quote(from),
            quote(to));
  }

  /**
   * A site request body with a region.
   *
   * @param legalEntityId parent
   * @param regionId region or {@code null}
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return JSON
   */
  public static String siteInRegion(
      Object legalEntityId, Object regionId, String code, String from, String to) {
    return """
    {"legalEntityId": %s, "regionId": %s, "code": %s, "name": %s, "timezone": "Africa/Kinshasa",
     "effectiveFrom": %s, "effectiveTo": %s}
    """
        .formatted(
            quote(legalEntityId == null ? null : legalEntityId.toString()),
            quote(regionId == null ? null : regionId.toString()),
            quote(code),
            quote("Site " + code),
            quote(from),
            quote(to));
  }

  /**
   * An assignment request body: exactly {@code {"regionId": ...}}.
   *
   * @param regionId region or {@code null}
   * @return JSON
   */
  public static String assignment(Object regionId) {
    return "{\"regionId\": " + quote(regionId == null ? null : regionId.toString()) + "}";
  }

  /**
   * {@code PUT /api/v1/sites/{siteId}/region} with bearer, key and JSON body.
   *
   * @param siteId path value (any text, to test malformed ids)
   * @param bearer bearer or {@code null}
   * @param key idempotency key or {@code null}
   * @param body JSON
   * @return request
   */
  public static MockHttpServletRequestBuilder assign(
      Object siteId, String bearer, String key, String body) {
    MockHttpServletRequestBuilder request =
        put("/api/v1/sites/" + siteId + "/region")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (bearer != null) {
      request.header("Authorization", bearer);
    }
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return request;
  }

  /**
   * Creates a region and returns its id.
   *
   * @param mvc MockMvc
   * @param tenant tenant
   * @param parent legal entity
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return id
   * @throws Exception on request failure
   */
  public static UUID newRegion(
      MockMvc mvc, UUID tenant, UUID parent, String code, String from, String to) throws Exception {
    String body =
        mvc.perform(
                create(
                    "/api/v1/regions",
                    admin(tenant),
                    Organizations.newKey(),
                    region(parent, code, "Région " + code, from, to)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * Creates a site in a region and returns its id.
   *
   * @param mvc MockMvc
   * @param tenant tenant
   * @param parent legal entity
   * @param region region
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return id
   * @throws Exception on request failure
   */
  public static UUID newSiteInRegion(
      MockMvc mvc, UUID tenant, UUID parent, UUID region, String code, String from, String to)
      throws Exception {
    String body =
        mvc.perform(
                create(
                    "/api/v1/sites",
                    admin(tenant),
                    Organizations.newKey(),
                    siteInRegion(parent, region, code, from, to)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * A team request body naming the parent through {@code parentField} ({@code departmentId} or
   * {@code costCenterId}); the other parent property is absent.
   *
   * @param parentField parent property name
   * @param parentId parent id (any text, to test malformed ids)
   * @param code code
   * @param name name
   * @param from effective from
   * @param to effective to or {@code null}
   * @return JSON
   */
  public static String team(
      String parentField, Object parentId, String code, String name, String from, String to) {
    return """
    {"%s": %s, "code": %s, "name": %s, "effectiveFrom": %s, "effectiveTo": %s}
    """
        .formatted(
            parentField,
            quote(parentId == null ? null : parentId.toString()),
            quote(code),
            quote(name),
            quote(from),
            quote(to));
  }

  /**
   * Creates a team and returns its id.
   *
   * @param mvc MockMvc
   * @param tenant tenant
   * @param parentField {@code departmentId} or {@code costCenterId}
   * @param parentId parent id
   * @param code code
   * @param from effective from
   * @param to effective to or {@code null}
   * @return id
   * @throws Exception on request failure
   */
  public static UUID newTeam(
      MockMvc mvc,
      UUID tenant,
      String parentField,
      UUID parentId,
      String code,
      String from,
      String to)
      throws Exception {
    String body =
        mvc.perform(
                create(
                    "/api/v1/teams",
                    admin(tenant),
                    Organizations.newKey(),
                    team(parentField, parentId, code, "Équipe " + code, from, to)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JSON.readTree(body).get("id").asText());
  }

  /**
   * {@code GET} with bearer.
   *
   * @param bearer bearer
   * @param path path including query
   * @return request
   */
  public static MockHttpServletRequestBuilder list(String bearer, String path) {
    return get(path).header("Authorization", bearer);
  }

  /**
   * Parses JSON.
   *
   * @param text JSON text
   * @return tree
   * @throws Exception on parse failure
   */
  public static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
  }
}
