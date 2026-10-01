package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A throwaway Keycloak 26.7 importing the committed development realm, with a Mailpit capture
 * attached as the realm's SMTP server. Test-only credentials.
 */
final class KeycloakTestStack implements AutoCloseable {

  static final String REALM = "divalhr-dev";
  static final String ADMIN = "test-kc-admin";
  static final String ADMIN_PASSWORD = "test-only-kc-admin-password";

  private final Network network;
  private final GenericContainer<?> mailpit;
  private final GenericContainer<?> keycloak;

  private KeycloakTestStack(
      Network network, GenericContainer<?> mailpit, GenericContainer<?> keycloak) {
    this.network = network;
    this.mailpit = mailpit;
    this.keycloak = keycloak;
  }

  /**
   * Starts Mailpit and Keycloak and relaxes TLS on the container's own {@code master} realm (see
   * {@link KeycloakProvisioningContainerTest}).
   *
   * @return the running stack
   * @throws Exception when a container cannot start
   */
  static KeycloakTestStack start() throws Exception {
    Network network = Network.newNetwork();
    GenericContainer<?> mailpit =
        new GenericContainer<>(MailpitImage.PINNED)
            .withNetwork(network)
            .withNetworkAliases("mailpit")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));
    mailpit.start();
    Path realm =
        Path.of(System.getProperty("divalhr.repoRoot", "../.."))
            .resolve("infrastructure/docker/keycloak/realm-divalhr-dev.json");
    GenericContainer<?> keycloak =
        new GenericContainer<>(KeycloakProvisioningContainerTest.KEYCLOAK_IMAGE)
            .withNetwork(network)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(realm),
                "/opt/keycloak/data/import/realm-divalhr-dev.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/" + REALM)
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));
    keycloak.start();
    KeycloakTestStack stack = new KeycloakTestStack(network, mailpit, keycloak);
    String config = "/tmp/kcadm.config";
    stack.kcadm(
        "config",
        "credentials",
        "--server",
        "http://localhost:8080",
        "--realm",
        "master",
        "--user",
        ADMIN,
        "--password",
        ADMIN_PASSWORD,
        "--config",
        config);
    stack.kcadm("update", "realms/master", "-s", "sslRequired=NONE", "--config", config);
    return stack;
  }

  private void kcadm(String... arguments) throws Exception {
    String[] command = new String[arguments.length + 1];
    command[0] = "/opt/keycloak/bin/kcadm.sh";
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    assertThat(keycloak.execInContainer(command).getExitCode())
        .as("kcadm " + arguments[0])
        .isZero();
  }

  /**
   * Keycloak base URL reachable from the test.
   *
   * @return base URL
   */
  String baseUrl() {
    return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
  }

  /**
   * Mailpit HTTP API base URL.
   *
   * @return base URL
   */
  String mailpitUrl() {
    return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025);
  }

  /**
   * Bootstrap administrator of the throwaway {@code master} realm.
   *
   * @return username
   */
  String adminUser() {
    return ADMIN;
  }

  /**
   * Bootstrap administrator password (test-only).
   *
   * @return password
   */
  String adminPassword() {
    return ADMIN_PASSWORD;
  }

  @Override
  public void close() {
    keycloak.stop();
    mailpit.stop();
    network.close();
  }
}
