plugins {
    // Provisions JDK 21 automatically when the host JDK is different (same as apps/core-api).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "keycloak-provisioning"
