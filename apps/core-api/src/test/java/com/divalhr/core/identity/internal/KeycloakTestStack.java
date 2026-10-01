package com.divalhr.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A throwaway Keycloak 26.7 with the {@code divalhr-provisioning} extension (Issue #31), importing
 * the committed development realm, with a Mailpit capture attached as the realm's SMTP server.
 * Test-only credentials.
 */
final class KeycloakTestStack implements AutoCloseable {

  static final String REALM = "divalhr-dev";

  /** Same pinned image as infrastructure/docker/keycloak/Dockerfile. */
  static final String KEYCLOAK_IMAGE =
      "quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c";

  /** Development provisioner secret of the committed realm (published, dev-only). */
  static final String PROVISIONER_SECRET = "dev-only-provisioner-secret-2026";

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
        keycloak(extensionJar())
            .withNetwork(network)
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

  /**
   * The extension JAR built by {@code apps/keycloak-provisioning} (wired by the Gradle build).
   *
   * @return path
   */
  static Path extensionJar() {
    String jar = System.getProperty("divalhr.provisioningJar");
    if (jar == null) {
      throw new IllegalStateException("divalhr.provisioningJar is not set (run through Gradle)");
    }
    return Path.of(jar);
  }

  /**
   * A Keycloak container with the given extension JAR and the extension options of the Compose
   * stack, as in infrastructure/docker/keycloak/Dockerfile.
   *
   * @param jar extension JAR
   * @return the container, not started
   */
  static GenericContainer<?> keycloak(Path jar) {
    return new GenericContainer<>(KEYCLOAK_IMAGE)
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
        .withEnv("KC_SPI_REALM_RESTAPI_EXTENSION__DIVALHR_PROVISIONING__REALMS", REALM)
        .withEnv(
            "KC_SPI_REALM_RESTAPI_EXTENSION__DIVALHR_PROVISIONING__WEB_REDIRECT_URI",
            "http://localhost:5173/auth/callback")
        .withCopyFileToContainer(
            MountableFile.forHostPath(jar), "/opt/keycloak/providers/divalhr-provisioning.jar");
  }

  /**
   * Keycloak's log so far (for secrecy assertions).
   *
   * @return log text
   */
  String keycloakLogs() {
    return keycloak.getLogs();
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
   * Mailpit SMTP host reachable from the test.
   *
   * @return host
   */
  String mailpitSmtpHost() {
    return mailpit.getHost();
  }

  /**
   * Mailpit SMTP port reachable from the test.
   *
   * @return port
   */
  int mailpitSmtpPort() {
    return mailpit.getMappedPort(1025);
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
