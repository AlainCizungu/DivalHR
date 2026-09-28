import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("com.diffplug.spotless") version "8.10.3"
    id("com.github.spotbugs") version "6.5.12"
}

group = "com.divalhr"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springdocVersion = "3.1.1"
val archunitVersion = "1.5.1"

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // API docs only (no Swagger UI); used as drift-verification output, not as the contract.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:$springdocVersion")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testImplementation("org.yaml:snakeyaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    // The drift test reads the authoritative design-first contract.
    systemProperty("divalhr.apiSpecPath", rootProject.file("../../docs/API-SPEC.yaml").absolutePath)
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
