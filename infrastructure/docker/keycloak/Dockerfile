# DivalHR Keycloak image (Issue #31, ADR 0006): the pinned Keycloak release plus the narrow
# divalhr-provisioning extension. Build context: the repository root.
#
# The extension uses internal Keycloak APIs and refuses to start on any Keycloak version other than
# the one it was compiled for. Upgrading Keycloak means changing, together, the base image below,
# `keycloakVersion` in apps/keycloak-provisioning/build.gradle.kts and its build metadata, and the
# Testcontainers image in KeycloakTestStack (KeycloakImageConsistencyTest fails otherwise).
#
# Build the JAR first, then the image, with the recorded digest:
#   eval "$(ops/build/keycloak-provisioning-jar.sh --export)"
#   docker build -f infrastructure/docker/keycloak/Dockerfile \
#     --build-arg PROVISIONING_JAR_SHA256="$KEYCLOAK_PROVISIONING_JAR_SHA256" .

# DEVX-005 (Issue #214): this build resolves nothing from the network. The extension JAR is built
# beforehand from the same commit (ops/build/keycloak-provisioning-jar.sh), which records its
# SHA-256; the image installs that file only if it still has the digest passed as
# PROVISIONING_JAR_SHA256 (the verify stage fails the build otherwise).
FROM eclipse-temurin:21.0.12.1_1-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026 AS verify
ARG PROVISIONING_JAR_SHA256
COPY infrastructure/docker/keycloak/build/divalhr-provisioning.jar /verified/divalhr-provisioning.jar
RUN test -n "${PROVISIONING_JAR_SHA256}" \
 || { echo "PROVISIONING_JAR_SHA256 is required: build the JAR with ops/build/keycloak-provisioning-jar.sh" >&2; exit 1; }; \
    echo "${PROVISIONING_JAR_SHA256}  /verified/divalhr-provisioning.jar" | sha256sum --check --strict -

FROM quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c
COPY --from=verify /verified/divalhr-provisioning.jar /opt/keycloak/providers/divalhr-provisioning.jar
# Pre-builds the server with the provider. `start-dev` re-augments on its own; production starts
# with `start --optimized` after its own `kc.sh build` options (Keycloak README, production checklist).
# Default: the development build (start-dev). The OPS-001 test environment passes production-mode
# build options (PostgreSQL, single node, /identity path, health): see infrastructure/hr-dev.
ARG KC_BUILD_OPTIONS=""
RUN /opt/keycloak/bin/kc.sh build ${KC_BUILD_OPTIONS}
