plugins {
    // Provisions JDK 21 automatically when the host JDK is different.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "core-api"

// Issue #31: the Keycloak provisioning extension is built with this wrapper. Absent from the Core
// API image build context (apps/core-api only), where it is not needed.
if (file("../keycloak-provisioning").isDirectory) {
    includeBuild("../keycloak-provisioning")
}
