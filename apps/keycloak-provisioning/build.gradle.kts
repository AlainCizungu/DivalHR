import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

// Narrow Keycloak provisioning extension (Issue #31, ADR 0006). Built with the apps/core-api
// Gradle wrapper (composite build), so there is no second wrapper to pin or update.
plugins {
    java
    id("com.diffplug.spotless") version "8.10.3"
    id("com.github.spotbugs") version "6.5.12"
}

group = "com.divalhr"

// Exactly the Keycloak version of infrastructure/docker/keycloak/Dockerfile. The extension uses
// internal Keycloak APIs and refuses to start on any other version (VersionGuard).
val keycloakVersion = "26.7.4"
version = "$keycloakVersion-divalhr.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Provided by the Keycloak server at runtime; never packaged (approved as D2 on Issue #31).
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-core:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-common:$keycloakVersion")
    // The versions Keycloak 26.7.4 ships in lib/lib.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
    compileOnly("org.jboss.logging:jboss-logging:3.6.3.Final")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.jar {
    // A stable name: the Keycloak image and the Core API container tests load this file.
    archiveFileName = "divalhr-provisioning.jar"
    manifest {
        attributes(
            "Implementation-Title" to "divalhr-provisioning",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // VersionGuardTest: the packaged build metadata must name exactly this Keycloak version.
    systemProperty("divalhr.keycloakVersion", keycloakVersion)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.36.1")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

spotbugs {
    toolVersion = "4.10.4"
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    excludeFilter = file("config/spotbugs-exclude.xml")
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
