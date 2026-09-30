package com.divalhr.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Invitation request helpers for integration tests (MVP-010). */
public final class Invitations {

  private Invitations() {}

  /**
   * A unique, already normalized address.
   *
   * @param prefix local-part prefix
   * @return address
   */
  public static String address(String prefix) {
    return (prefix + "." + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
        .toLowerCase(Locale.ROOT);
  }

  /**
   * A create body.
   *
   * @param email address
   * @param role role
   * @param locale locale
   * @return JSON
   */
  public static String body(String email, String role, String locale) {
    return "{\"email\": "
        + quote(email)
        + ", \"role\": "
        + quote(role)
        + ", \"locale\": "
        + quote(locale)
        + "}";
  }

  /**
   * A create request.
   *
   * @param bearer authorization header value
   * @param key idempotency key
   * @param body JSON body
   * @return request
   */
  public static MockHttpServletRequestBuilder create(String bearer, String key, String body) {
    MockHttpServletRequestBuilder request =
        post("/api/v1/invitations").contentType(MediaType.APPLICATION_JSON).content(body);
    if (bearer != null) {
      request = request.header("Authorization", bearer);
    }
    if (key != null) {
      request = request.header("Idempotency-Key", key);
    }
    return request;
  }

  /**
   * Creates an invitation and returns its receipt.
   *
   * @param mvc MockMvc
   * @param tenant tenant
   * @param email address
   * @param role role
   * @return receipt JSON
   * @throws Exception on request failure
   */
  public static JsonNode invite(MockMvc mvc, UUID tenant, String email, String role)
      throws Exception {
    MvcResult result =
        mvc.perform(
                create(Hierarchy.admin(tenant), Organizations.newKey(), body(email, role, "fr")))
            .andReturn();
    if (result.getResponse().getStatus() != 201) {
      throw new AssertionError(
          "invite failed: "
              + result.getResponse().getStatus()
              + " "
              + result.getResponse().getContentAsString());
    }
    return Hierarchy.json(result.getResponse().getContentAsString());
  }

  /**
   * A list request.
   *
   * @param bearer authorization header value
   * @param query query string without {@code ?}, or empty
   * @return request
   */
  public static MockHttpServletRequestBuilder list(String bearer, String query) {
    return get("/api/v1/invitations" + (query.isEmpty() ? "" : "?" + query))
        .header("Authorization", bearer);
  }

  /**
   * An anonymous inspect or accept request.
   *
   * @param action {@code inspect} or {@code accept}
   * @param token token (quoted as JSON), or {@code null} for an empty object
   * @return request
   */
  public static MockHttpServletRequestBuilder anonymous(String action, String token) {
    return post("/api/v1/public/invitations/" + action)
        .contentType(MediaType.APPLICATION_JSON)
        .content(token == null ? "{}" : "{\"token\": " + quote(token) + "}");
  }

  private static String quote(String value) {
    return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
