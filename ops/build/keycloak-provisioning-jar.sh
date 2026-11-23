#!/usr/bin/env bash
# DEVX-005 (Issue #214): builds the divalhr-provisioning extension JAR from the checked-out commit,
# as its own step before any Keycloak image is built, and records its SHA-256. The Keycloak
# Dockerfile only copies this verified JAR; it never runs Gradle or resolves a dependency.
#
#   ops/build/keycloak-provisioning-jar.sh            build (Gradle; its caches are reused) and record
#   ops/build/keycloak-provisioning-jar.sh --verify   recompute the digest and compare it to the record
#   ops/build/keycloak-provisioning-jar.sh --export   print `export KEYCLOAK_PROVISIONING_JAR_SHA256=...`
#                                                     after a build (use: eval "$(... --export)")
#
# The JAR is installed at infrastructure/docker/keycloak/build/divalhr-provisioning.jar with the
# record divalhr-provisioning.jar.sha256 beside it (both untracked: build/ is ignored). The last
# line of stdout is always `KEYCLOAK_PROVISIONING_JAR_SHA256=<64 hex>`.
#
# Gradle runs on this host when it has JDK 21; otherwise in the same pinned Temurin image the
# Core API image builds with (docker run, the repository mounted), so no host JDK is required.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR_NAME=divalhr-provisioning.jar
BUILT="$ROOT/apps/keycloak-provisioning/build/libs/$JAR_NAME"
OUT_DIR="$ROOT/infrastructure/docker/keycloak/build"
OUT="$OUT_DIR/$JAR_NAME"
RECORD="$OUT.sha256"
# Keep identical to the verify stage of infrastructure/docker/keycloak/Dockerfile (tested).
TEMURIN_IMAGE='eclipse-temurin:21.0.12.1_1-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026'

die() { printf 'STOP: %s\n' "$*" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mode=build
case "${1:-}" in
  "") ;;
  --verify) mode=verify ;;
  --export) mode=export ;;
  *) die "usage: keycloak-provisioning-jar.sh [--verify|--export]" ;;
esac

verify() {
  [ -f "$OUT" ] && [ -f "$RECORD" ] || die "the provisioning JAR or its record is missing: run ops/build/keycloak-provisioning-jar.sh"
  local recorded actual
  recorded="$(cut -d' ' -f1 < "$RECORD")"
  actual="$(sha256_of "$OUT")"
  printf '%s' "$recorded" | grep -Eq '^[0-9a-f]{64}$' || die "the JAR record is not a SHA-256"
  [ "$recorded" = "$actual" ] || die "the provisioning JAR does not match its recorded SHA-256"
  printf 'KEYCLOAK_PROVISIONING_JAR_SHA256=%s\n' "$actual"
}

if [ "$mode" = verify ]; then verify; exit 0; fi

# Never reuse a JAR built from other sources: remove the previous output first.
rm -f "$BUILT" "$OUT" "$RECORD"

java_major=""
if command -v java >/dev/null 2>&1; then
  java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
fi
if [ "$java_major" = 21 ]; then
  ( cd "$ROOT" && apps/core-api/gradlew --no-daemon -p apps/keycloak-provisioning jar ) >&2 \
    || die "the provisioning JAR build failed"
else
  command -v docker >/dev/null 2>&1 || die "neither JDK 21 nor Docker is available to build the provisioning JAR"
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp/gradle-home -e GRADLE_USER_HOME=/tmp/gradle-home \
    -v "$ROOT:/src" -w /src "$TEMURIN_IMAGE" \
    apps/core-api/gradlew --no-daemon -p apps/keycloak-provisioning jar >&2 \
    || die "the provisioning JAR build failed"
fi
[ -s "$BUILT" ] || die "the build produced no $JAR_NAME"

mkdir -p "$OUT_DIR"
cp "$BUILT" "$OUT"
sha="$(sha256_of "$OUT")"
printf '%s  %s\n' "$sha" "$JAR_NAME" > "$RECORD"
verify >/dev/null
if [ "$mode" = export ]; then printf 'export KEYCLOAK_PROVISIONING_JAR_SHA256=%s\n' "$sha"; exit 0; fi
printf 'KEYCLOAK_PROVISIONING_JAR_SHA256=%s\n' "$sha"
