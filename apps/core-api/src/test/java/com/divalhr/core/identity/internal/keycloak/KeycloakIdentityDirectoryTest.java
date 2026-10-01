package com.divalhr.core.identity.internal.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.identity.application.IdentityDirectory.CredentialSetupOutcome;
import com.divalhr.core.identity.application.IdentityDirectory.IdentityConflict;
import com.divalhr.core.identity.application.IdentityDirectory.Provisioned;
import com.divalhr.core.identity.application.IdentityDirectory.ProvisioningRequest;
import com.divalhr.core.identity.application.IdentityProviderUnavailableException;
import com.divalhr.core.identity.domain.EmailAddress;
import com.divalhr.core.identity.domain.InvitationLocale;
import com.divalhr.core.identity.domain.TenantRole;
import com.divalhr.core.platform.tenancy.TenantId;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps the extension's answers to the port's outcomes against a stub server (PR #32 review): only a
 * coded {@code IDENTITY_CONFLICT} is a business conflict; a lost creation race ({@code 503
 * IDENTITY_BUSY}) or any uncoded 409 stays retryable; credential setup sends no body.
 */
class KeycloakIdentityDirectoryTest {

  private static final String SUBJECT = "0b6f6f0e-3c1d-4d7e-9a1b-2c3d4e5f6a7b";

  private HttpServer server;
  private KeycloakIdentityDirectory directory;
  private final AtomicReference<Integer> status = new AtomicReference<>(200);
  private final AtomicReference<String> answer = new AtomicReference<>("{}");
  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<Integer> lastBodyBytes = new AtomicReference<>();
  private final AtomicReference<String> lastContentType = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/realms/divalhr-test/protocol/openid-connect/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 200, "{\"access_token\":\"stub\",\"expires_in\":300}");
        });
    server.createContext(
        "/realms/divalhr-test/divalhr-provisioning/",
        exchange -> {
          lastMethod.set(exchange.getRequestMethod());
          lastBodyBytes.set(exchange.getRequestBody().readAllBytes().length);
          lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          respond(exchange, status.get(), answer.get());
        });
    server.start();
    KeycloakProperties properties =
        new KeycloakProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "divalhr-test",
            "divalhr-core-provisioner",
            "stub-secret-0123456789",
            Duration.ofSeconds(2),
            Duration.ofSeconds(2));
    directory = new KeycloakIdentityDirectory(properties, "test", JsonMapper.builder().build());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
    if (code != 204) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  private void reply(int code, String body) {
    status.set(code);
    answer.set(body);
  }

  private static ProvisioningRequest request() {
    return new ProvisioningRequest(
        UUID.randomUUID(),
        new TenantId(UUID.randomUUID()),
        new EmailAddress("ada@example.test"),
        TenantRole.EMPLOYEE,
        InvitationLocale.EN);
  }

  @Test
  void createdAndConfirmedIdentitiesAreProvisioned() {
    reply(201, "{\"subject\":\"" + SUBJECT + "\"}");
    assertThat(directory.provision(request())).isEqualTo(new Provisioned(SUBJECT));
    reply(200, "{\"subject\":\"" + SUBJECT + "\"}");
    assertThat(directory.provision(request())).isEqualTo(new Provisioned(SUBJECT));
  }

  @Test
  void onlyACodedIdentityConflictIsABusinessConflict() {
    reply(409, "{\"code\":\"IDENTITY_CONFLICT\"}");
    assertThat(directory.provision(request())).isInstanceOf(IdentityConflict.class);
  }

  @Test
  void aLostRaceOrAnyUnprovenConflictStaysRetryable() {
    reply(503, "{\"code\":\"IDENTITY_BUSY\"}");
    assertThatThrownBy(() -> directory.provision(request()))
        .isInstanceOf(IdentityProviderUnavailableException.class);
    reply(409, "{\"code\":\"IDENTITY_AMBIGUOUS\"}");
    assertThatThrownBy(() -> directory.provision(request()))
        .isInstanceOf(IdentityProviderUnavailableException.class);
    // Keycloak's own answer to a duplicate key, should a race surface at commit.
    reply(409, "{\"errorMessage\":\"User exists with same username\"}");
    assertThatThrownBy(() -> directory.provision(request()))
        .isInstanceOf(IdentityProviderUnavailableException.class);
    reply(409, "");
    assertThatThrownBy(() -> directory.provision(request()))
        .isInstanceOf(IdentityProviderUnavailableException.class);
  }

  @Test
  void credentialSetupSendsNoBodyAndMapsEachOutcome() {
    reply(202, "{\"state\":\"PENDING\"}");
    assertThat(directory.requestCredentialSetup(UUID.randomUUID()))
        .isEqualTo(CredentialSetupOutcome.EMAIL_SENT);
    assertThat(lastMethod.get()).isEqualTo("POST");
    assertThat(lastBodyBytes.get()).isZero();
    assertThat(lastContentType.get()).isNull();
    reply(200, "{\"state\":\"COMPLETED\"}");
    assertThat(directory.requestCredentialSetup(UUID.randomUUID()))
        .isEqualTo(CredentialSetupOutcome.COMPLETED);
    reply(409, "{\"code\":\"SETUP_STATE_INVALID\"}");
    assertThat(directory.requestCredentialSetup(UUID.randomUUID()))
        .isEqualTo(CredentialSetupOutcome.STATE_INVALID);
    reply(503, "{\"code\":\"EMAIL_FAILED\"}");
    assertThatThrownBy(() -> directory.requestCredentialSetup(UUID.randomUUID()))
        .isInstanceOf(IdentityProviderUnavailableException.class);
  }
}
