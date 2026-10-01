package com.divalhr.core.identity.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP helpers for the Keycloak container tests: tokens, the realm Admin API (as the bootstrap
 * administrator or another caller), the {@code divalhr-provisioning} extension and Mailpit.
 * Test-only; never prints tokens, secrets or bodies.
 */
final class KeycloakCalls {

  static final String REALM = KeycloakTestStack.REALM;
  static final String PROVISIONER = "divalhr-core-provisioner";
  static final HttpClient HTTP = HttpClient.newHttpClient();
  static final JsonMapper JSON = JsonMapper.builder().build();

  private final KeycloakTestStack stack;

  KeycloakCalls(KeycloakTestStack stack) {
    this.stack = stack;
  }

  /** Status and body of a call. {@link #toString()} never prints the body. */
  record Reply(int status, String body) {

    JsonNode json() {
      return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    String code() {
      return json().path("code").asString("");
    }

    @Override
    public String toString() {
      return "Reply[" + status + "]";
    }
  }

  // --- tokens ------------------------------------------------------------------------------------

  String adminToken() throws Exception {
    return token("master", "admin-cli", null, stack.adminUser(), stack.adminPassword());
  }

  String provisionerToken() throws Exception {
    return token(REALM, PROVISIONER, KeycloakTestStack.PROVISIONER_SECRET, null, null);
  }

  String token(String realm, String client, String secret, String user, String password)
      throws Exception {
    Reply reply = tokenReply(realm, client, secret, user, password);
    if (reply.status() != 200) {
      throw new AssertionError("token request refused: " + reply.status());
    }
    return reply.json().path("access_token").asString();
  }

  Reply tokenReply(String realm, String client, String secret, String user, String password)
      throws Exception {
    StringJoiner form = new StringJoiner("&");
    form.add("client_id=" + enc(client));
    if (secret != null) {
      form.add("grant_type=client_credentials").add("client_secret=" + enc(secret));
    } else {
      form.add("grant_type=password").add("username=" + enc(user)).add("password=" + enc(password));
    }
    return send(
        HttpRequest.newBuilder(
                URI.create(stack.baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString())));
  }

  static JsonNode claims(String token) {
    return JSON.readTree(
        new String(
            java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
            StandardCharsets.UTF_8));
  }

  // --- Admin API ---------------------------------------------------------------------------------

  /** Admin API call as the bootstrap administrator; fails the test on an error status. */
  JsonNode admin(String method, String path, String body) throws Exception {
    Reply reply = adminAs(adminToken(), method, path, body);
    if (reply.status() >= 300) {
      throw new AssertionError(
          method + " " + path.replaceAll("[0-9a-f-]{36}", "{id}") + " -> " + reply.status());
    }
    return reply.json();
  }

  /** Admin API call with any bearer token. */
  Reply adminAs(String bearer, String method, String path, String body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(stack.baseUrl() + "/admin/realms/" + REALM + path))
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json");
    return send(withMethod(builder, method, body));
  }

  String userId(String username) throws Exception {
    return admin("GET", "/users?exact=true&username=" + enc(username), null)
        .get(0)
        .path("id")
        .asString();
  }

  String clientUuid(String clientId) throws Exception {
    return admin("GET", "/clients?clientId=" + enc(clientId), null).get(0).path("id").asString();
  }

  // --- extension ---------------------------------------------------------------------------------

  static String identityPath(String invitationId) {
    return "/realms/"
        + REALM
        + "/divalhr-provisioning/v1/invitations/"
        + invitationId
        + "/identity";
  }

  /** Calls the extension with a JSON body (or none). */
  Reply extension(String bearer, String method, String path, String body) throws Exception {
    return extensionRaw(bearer, method, path, "application/json", body, false);
  }

  /**
   * Calls the extension with full control of the content type and of the body's framing.
   *
   * @param chunked send the body without a Content-Length (streamed)
   */
  Reply extensionRaw(
      String bearer, String method, String path, String contentType, String body, boolean chunked)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(stack.baseUrl() + path))
            .header("X-Correlation-Id", UUID.randomUUID().toString());
    if (bearer != null) {
      builder.header("Authorization", "Bearer " + bearer);
    }
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    if (body == null) {
      return send(withMethod(builder, method, null));
    }
    HttpRequest.BodyPublisher publisher =
        chunked
            ? new UnknownLengthPublisher(body.getBytes(StandardCharsets.UTF_8))
            : HttpRequest.BodyPublishers.ofString(body);
    return send(builder.method(method, publisher));
  }

  static String provisionBody(String email, String role, UUID tenant, String locale) {
    return JSON.writeValueAsString(
        Map.of("email", email, "role", role, "tenantId", tenant.toString(), "locale", locale));
  }

  // --- Mailpit -----------------------------------------------------------------------------------

  int messagesTo(String email) throws Exception {
    return get(stack.mailpitUrl() + "/api/v1/search?query=" + enc("to:" + email))
        .path("messages")
        .size();
  }

  JsonNode awaitMessage(String email, int count) throws Exception {
    for (int i = 0; i < 75; i++) {
      JsonNode list = get(stack.mailpitUrl() + "/api/v1/search?query=" + enc("to:" + email));
      if (list.path("messages").size() >= count) {
        return get(
            stack.mailpitUrl()
                + "/api/v1/message/"
                + list.path("messages").get(0).path("ID").asString());
      }
      Thread.sleep(200);
    }
    throw new AssertionError("no message captured");
  }

  String actionLink(String email, int count) throws Exception {
    java.util.regex.Matcher link =
        java.util.regex.Pattern.compile("(https?://\\S+/login-actions/action-token\\?\\S+)")
            .matcher(awaitMessage(email, count).path("Text").asString());
    if (!link.find()) {
      throw new AssertionError("no action link in the message");
    }
    return link.group(1);
  }

  // --- plumbing ----------------------------------------------------------------------------------

  JsonNode get(String url) throws Exception {
    Reply reply = send(HttpRequest.newBuilder(URI.create(url)).GET());
    return reply.json();
  }

  static Reply send(HttpRequest.Builder builder) throws IOException, InterruptedException {
    HttpResponse<String> response =
        HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Reply(response.statusCode(), response.body());
  }

  private static HttpRequest.Builder withMethod(
      HttpRequest.Builder builder, String method, String body) {
    return switch (method) {
      case "GET" -> builder.GET();
      case "DELETE" ->
          body == null
              ? builder.DELETE()
              : builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body));
      default ->
          builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
    };
  }

  static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** A publisher that reports an unknown length, so the client streams the body (chunked). */
  private static final class UnknownLengthPublisher implements HttpRequest.BodyPublisher {
    private final HttpRequest.BodyPublisher delegate;

    UnknownLengthPublisher(byte[] bytes) {
      this.delegate = HttpRequest.BodyPublishers.ofByteArray(bytes);
    }

    @Override
    public long contentLength() {
      return -1;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
      delegate.subscribe(subscriber);
    }
  }
}
