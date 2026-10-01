package com.divalhr.keycloak.provisioning;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Refuses to run on any Keycloak version other than the one the extension was compiled against. The
 * extension uses internal Keycloak APIs (ADR 0006), so a mismatch stops Keycloak at start-up.
 */
public final class VersionGuard {

  /** Build metadata written by the extension's build. */
  public static final String RESOURCE = "/META-INF/divalhr-provisioning.properties";

  private VersionGuard() {}

  /**
   * The Keycloak version the extension was built for.
   *
   * @return version
   * @throws IllegalStateException when the build metadata is missing or empty
   */
  public static String builtFor() {
    Properties properties = new Properties();
    try (InputStream in = VersionGuard.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("divalhr-provisioning: build metadata missing");
      }
      properties.load(in);
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          "divalhr-provisioning: build metadata unreadable", unreadable);
    }
    String version = properties.getProperty("keycloak.version", "").trim();
    if (version.isEmpty()) {
      throw new IllegalStateException("divalhr-provisioning: build metadata has no version");
    }
    return version;
  }

  /**
   * Fails unless the running version equals the built-for version exactly.
   *
   * @param builtFor compiled-for version
   * @param running running Keycloak version
   * @throws IllegalStateException on any difference
   */
  public static void require(String builtFor, String running) {
    if (running == null || !running.equals(builtFor)) {
      throw new IllegalStateException(
          "divalhr-provisioning was built for Keycloak "
              + builtFor
              + " and refuses to run on "
              + running);
    }
  }
}
