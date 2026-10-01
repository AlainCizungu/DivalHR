package com.divalhr.core.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Request helpers for the MVP-014 tenant-administrator bootstrap. */
public final class Bootstraps {

  private Bootstraps() {}

  /**
   * A platform-administrator bearer token with exact MFA. Its tenant claim is set to an unrelated
   * tenant on purpose: bootstrap targets come only from the path.
   *
   * @param subject subject
   * @return {@code Authorization} header value
   */
  public static String platform(String subject) {
    return "Bearer "
        + TestTokens.token()
            .subject(subject)
            .tenant(TestTokens.TENANT_A)
            .roles(List.of("platform-admin"))
            .build();
  }

  /**
   * A unique platform subject, so per-actor limits never interfere between tests.
   *
   * @return subject
   */
  public static String platformSubject() {
    return "sub-platform-" + UUID.randomUUID();
  }

  /**
   * A create body.
   *
   * @param email address
   * @param locale locale
   * @return JSON
   */
  public static String body(String email, String locale) {
    return "{\"email\": \"" + email + "\", \"locale\": \"" + locale + "\"}";
  }

  /**
   * The bootstrap path of an organization.
   *
   * @param organization organization id or any raw value
   * @return path
   */
  public static String path(Object organization) {
    return "/api/v1/organizations/" + organization + "/tenant-admin-bootstrap";
  }

  /**
   * A create request.
   *
   * @param bearer authorization header value
   * @param organization target
   * @param key idempotency key
   * @param body JSON body
   * @return request
   */
  public static MockHttpServletRequestBuilder create(
      String bearer, Object organization, String key, String body) {
    MockHttpServletRequestBuilder request =
        post(path(organization)).contentType(MediaType.APPLICATION_JSON).content(body);
    if (bearer != null) {
      request = request.header("Authorization", bearer);
    }
    if (key != null) {
      request = request.header("Idempotency-Key", key);
    }
    return request;
  }

  /**
   * A status request.
   *
   * @param bearer authorization header value
   * @param organization target
   * @return request
   */
  public static MockHttpServletRequestBuilder status(String bearer, Object organization) {
    return get(path(organization)).header("Authorization", bearer);
  }

  /**
   * A revoke request.
   *
   * @param bearer authorization header value
   * @param organization target
   * @return request
   */
  public static MockHttpServletRequestBuilder revoke(String bearer, Object organization) {
    return post(path(organization) + "/revoke").header("Authorization", bearer);
  }

  /**
   * A resend request.
   *
   * @param bearer authorization header value
   * @param organization target
   * @param key idempotency key
   * @return request
   */
  public static MockHttpServletRequestBuilder resend(
      String bearer, Object organization, String key) {
    return post(path(organization) + "/resend")
        .header("Authorization", bearer)
        .header("Idempotency-Key", key);
  }
}
