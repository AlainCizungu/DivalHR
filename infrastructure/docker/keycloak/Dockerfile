# DivalHR Keycloak image (Issue #31, ADR 0006): the pinned Keycloak release plus the narrow
# divalhr-provisioning extension. Build context: the repository root.
#
# The extension uses internal Keycloak APIs and refuses to start on any Keycloak version other than
# the one it was compiled for. Upgrading Keycloak means changing, together, the base image below,
# `keycloakVersion` in apps/keycloak-provisioning/build.gradle.kts and its build metadata, and the
# Testcontainers image in KeycloakTestStack (KeycloakImageConsistencyTest fails otherwise).

FROM eclipse-temurin:21.0.12.1_1-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026 AS build
WORKDIR /src
# The Core API's Gradle wrapper builds the extension (no second wrapper to pin).
COPY apps/core-api/gradlew wrapper/gradlew
COPY apps/core-api/gradle wrapper/gradle
RUN wrapper/gradlew --no-daemon --version
COPY apps/keycloak-provisioning keycloak-provisioning
RUN wrapper/gradlew --no-daemon -p keycloak-provisioning jar

FROM quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c
COPY --from=build /src/keycloak-provisioning/build/libs/divalhr-provisioning.jar /opt/keycloak/providers/divalhr-provisioning.jar
# Pre-builds the server with the provider. `start-dev` re-augments on its own; production starts
# with `start --optimized` after its own `kc.sh build` options (Keycloak README, production checklist).
RUN /opt/keycloak/bin/kc.sh build
