package com.divalhr.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestBodiesTest {

  private static final String JSON = "application/json";
  private static final String VALID =
      "{\"email\":\"ada@example.test\",\"role\":\"tenant-admin\","
          + "\"tenantId\":\"00000000-0000-4000-8000-00000000000a\",\"locale\":\"fr\"}";

  private static InputStream body(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String code(ThrowingCall call) {
    try {
      call.run();
    } catch (RequestBodies.Rejected rejected) {
      return rejected.code() + "/" + rejected.status();
    }
    return "accepted";
  }

  @FunctionalInterface
  interface ThrowingCall {
    void run() throws RequestBodies.Rejected;
  }

  @Test
  void acceptsExactlyTheAllowListedFields() throws Exception {
    RequestBodies.Provision request =
        RequestBodies.provision(RequestBodies.readObject(JSON, -1, body(VALID)));
    assertThat(request.email()).isEqualTo("ada@example.test");
    assertThat(request.role()).isEqualTo(InvitationRole.TENANT_ADMIN);
    assertThat(request.tenantId())
        .isEqualTo(UUID.fromString("00000000-0000-4000-8000-00000000000a"));
    assertThat(request.locale()).isEqualTo("fr");
  }

  @Test
  void rejectsEverythingElseWithoutEchoingIt() {
    String base = VALID.substring(0, VALID.length() - 1);
    for (String extra :
        new String[] {
          ",\"groups\":[\"/x\"]",
          ",\"attributes\":{}",
          ",\"credentials\":[]",
          ",\"enabled\":false",
          ",\"emailVerified\":false",
          ",\"requiredActions\":[]",
          ",\"realmRoles\":[\"platform-admin\"]",
          ",\"username\":\"x\""
        }) {
      assertThat(
              code(
                  () ->
                      RequestBodies.provision(
                          RequestBodies.readObject(JSON, -1, body(base + extra + "}")))))
          .as(extra)
          .isEqualTo("INVALID_REQUEST/400");
    }
    for (String role :
        new String[] {"platform-admin", "divalhr-privileged-mfa", "admin", "", "EMPLOYEE"}) {
      String text = VALID.replace("tenant-admin", role);
      assertThat(
              code(() -> RequestBodies.provision(RequestBodies.readObject(JSON, -1, body(text)))))
          .isEqualTo("INVALID_REQUEST/400");
    }
    for (String email :
        new String[] {
          "Ada@example.test",
          " ada@example.test",
          "ada@exämple.test",
          "ada",
          "a@b",
          "ada@@example.test"
        }) {
      String text = VALID.replace("ada@example.test", email);
      assertThat(
              code(() -> RequestBodies.provision(RequestBodies.readObject(JSON, -1, body(text)))))
          .as(email)
          .isEqualTo("INVALID_REQUEST/400");
    }
    assertThat(
            code(
                () ->
                    RequestBodies.provision(
                        RequestBodies.readObject(
                            JSON, -1, body(VALID.replace("\"fr\"", "\"de\""))))))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(
            code(
                () ->
                    RequestBodies.provision(
                        RequestBodies.readObject(
                            JSON, -1, body(VALID.replace("0000000a", "0000000A"))))))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(
            code(
                () ->
                    RequestBodies.provision(
                        RequestBodies.readObject(
                            JSON, -1, body(VALID.replace("\"locale\":\"fr\"", "\"locale\":1"))))))
        .isEqualTo("INVALID_REQUEST/400");
  }

  @Test
  void enforcesTheTransportRulesBeforeBinding() throws Exception {
    assertThat(code(() -> RequestBodies.readObject(JSON, -1, body(VALID + VALID))))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(code(() -> RequestBodies.readObject(JSON, -1, body("[" + VALID + "]"))))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(
            code(() -> RequestBodies.readObject(JSON, -1, body("{\"role\":\"a\",\"role\":\"b\"}"))))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(code(() -> RequestBodies.readObject(JSON, -1, body(""))))
        .isEqualTo("INVALID_REQUEST/400");
    assertThat(code(() -> RequestBodies.readObject("text/plain", -1, body(VALID))))
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE/415");
    assertThat(code(() -> RequestBodies.readObject(null, -1, body(VALID))))
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE/415");
    assertThat(
            code(
                () ->
                    RequestBodies.readObject("application/json; charset=latin1", -1, body(VALID))))
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE/415");
    assertThat(
            code(
                () -> RequestBodies.readObject("application/json; charset=UTF-8", -1, body(VALID))))
        .isEqualTo("accepted");
    // Declared too large: refused before reading.
    assertThat(code(() -> RequestBodies.readObject(JSON, 2049, body(VALID))))
        .isEqualTo("REQUEST_TOO_LARGE/413");
    // Undeclared (chunked) and too large: refused while streaming, before parsing.
    String padded = "{\"role\":\"" + "x".repeat(RequestBodies.MAX_BYTES) + "\"}";
    assertThat(code(() -> RequestBodies.readObject(JSON, -1, body(padded))))
        .isEqualTo("REQUEST_TOO_LARGE/413");
    try (InputStream endless =
        new InputStream() {
          @Override
          public int read() {
            return ' ';
          }
        }) {
      assertThat(code(() -> RequestBodies.readObject(JSON, -1, endless)))
          .isEqualTo("REQUEST_TOO_LARGE/413");
    }
  }

  @Test
  void pathAndCorrelationIdsMustBeCanonical() {
    assertThat(code(() -> RequestBodies.invitationId("00000000-0000-4000-8000-00000000000a")))
        .isEqualTo("accepted");
    for (String bad : new String[] {"00000000-0000-4000-8000-00000000000A", "x", "", "../users"}) {
      assertThat(code(() -> RequestBodies.invitationId(bad))).isEqualTo("INVALID_REQUEST/400");
    }
    assertThat(RequestBodies.correlationId("00000000-0000-4000-8000-00000000000a")).isPresent();
    assertThat(RequestBodies.correlationId("ada@example.test")).isEmpty();
  }

  @Test
  void setupBodyIsOnlyTheRole() throws Exception {
    assertThat(
            RequestBodies.setupRole(
                RequestBodies.readObject(JSON, -1, body("{\"role\":\"employee\"}"))))
        .isEqualTo(InvitationRole.EMPLOYEE);
    assertThat(
            code(
                () ->
                    RequestBodies.setupRole(
                        RequestBodies.readObject(
                            JSON, -1, body("{\"role\":\"employee\",\"actions\":[]}")))))
        .isEqualTo("INVALID_REQUEST/400");
  }
}
