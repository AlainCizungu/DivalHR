#!/usr/bin/env bash
# Runs build and verification stages on a developer workstation and writes logs to
# .git/divalhr-verify/ (inside .git so they are never committed).
#
# Usage: scripts/dev/verify-on-host.sh <stage>
#   spike   Core API compatibility spike (Gradle check, SpotBugs, tests) + image digests
#   core    Core API format + full clean check + bootJar
#   stack   Compose stack up, status/CORS probes, Playwright smoke, log secret scan, down
#   all     core + stack
#
# Compatible with macOS bash 3.2 and Linux bash.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAGE="${1:-spike}"
OUT="$ROOT/.git/divalhr-verify"
mkdir -p "$OUT"
LOG="$OUT/$STAGE.log"
SUMMARY="$OUT/$STAGE.summary"
: > "$LOG"
: > "$SUMMARY"

log() { printf '%s\n' "$*" | tee -a "$LOG"; }
result() { printf '%s\n' "$*" | tee -a "$SUMMARY" >> "$LOG"; }
run() {
  local name="$1"; shift
  log ""
  log "=== [$name] $*"
  if "$@" >> "$LOG" 2>&1; then
    result "PASS $name"
    return 0
  fi
  result "FAIL $name (see $LOG)"
  return 1
}

java_major() {
  command -v java >/dev/null 2>&1 || { echo 0; return; }
  java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}'
}

gradle_core() {
  # Uses the host JDK when >= 17 (Gradle provisions JDK 21 for compilation via toolchains);
  # otherwise runs Gradle inside a pinned Temurin container with access to Docker for Testcontainers.
  if [ "$(java_major)" -ge 17 ]; then
    (cd "$ROOT/apps/core-api" && ./gradlew --no-daemon "$@")
  else
    docker run --rm \
      -v "$ROOT":/w -w /w/apps/core-api \
      -v divalhr-gradle-cache:/root/.gradle \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
      eclipse-temurin:21.0.12.1_1-jdk ./gradlew --no-daemon "$@"
  fi
}

test_totals() {
  local dir="$ROOT/apps/core-api/build/test-results/test"
  [ -d "$dir" ] || { result "TESTS no results"; return; }
  local t f e s
  t=$(cat "$dir"/*.xml | grep -o 'tests="[0-9]*"' | head -1000 | awk -F'"' '{n+=$2} END {print n+0}')
  f=$(cat "$dir"/*.xml | grep -o 'failures="[0-9]*"' | awk -F'"' '{n+=$2} END {print n+0}')
  e=$(cat "$dir"/*.xml | grep -o 'errors="[0-9]*"' | awk -F'"' '{n+=$2} END {print n+0}')
  s=$(cat "$dir"/*.xml | grep -o 'skipped="[0-9]*"' | awk -F'"' '{n+=$2} END {print n+0}')
  result "TESTS core-api total=$t failures=$f errors=$e skipped=$s"
}

env_report() {
  log "=== environment"
  { uname -a; sw_vers 2>/dev/null; echo "java: $(java -version 2>&1 | head -1)";
    echo "docker: $(docker version --format '{{.Server.Version}}' 2>&1)";
    echo "node: $(node -v 2>&1)"; echo "git: $(git --version)"; } >> "$LOG" 2>&1
  result "INFO java_major=$(java_major)"
}

image_digests() {
  log ""
  log "=== image digests"
  for image in \
    postgres:17.11 \
    quay.io/keycloak/keycloak:26.7.4 \
    eclipse-temurin:21.0.12.1_1-jdk \
    eclipse-temurin:21.0.12.1_1-jre \
    python:3.12.14-slim-trixie \
    node:24.21.0-alpine3.24 \
    nginxinc/nginx-unprivileged:1.31.6-alpine3.24 \
    mcr.microsoft.com/playwright:v1.63.0-noble; do
    digest=$(docker buildx imagetools inspect "$image" --format '{{json .Manifest.Digest}}' 2>>"$LOG" | tr -d '"')
    result "DIGEST $image@${digest:-UNRESOLVED}"
  done
}

gradle_sha() {
  local zip
  zip=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists" -name 'gradle-9.8.0-bin.zip' 2>/dev/null | head -1)
  if [ -n "$zip" ]; then
    result "GRADLE_SHA256 $(shasum -a 256 "$zip" | awk '{print $1}')"
  else
    result "GRADLE_SHA256 unavailable ($(ls -d "${GRADLE_USER_HOME:-$HOME/.gradle}"/wrapper/dists/* 2>&1 | tr '\n' ' '))"
  fi
}

use_node24() {
  # Uses the active Node when it is 24.x; otherwise installs the pinned Node 24 from the npm
  # registry into ~/.cache/divalhr/node24 (outside the repository, no global changes) and puts it
  # first on PATH for this run only.
  if ! node -v 2>/dev/null | grep -q '^v24\.'; then
    local dir="${XDG_CACHE_HOME:-$HOME/.cache}/divalhr/node24"
    if [ ! -x "$dir/node_modules/node/bin/node" ]; then
      mkdir -p "$dir" && printf '{"private":true}\n' > "$dir/package.json" &&
        npm install --prefix "$dir" --no-save --no-package-lock --no-audit --no-fund node@24.21.0 || return 1
    fi
    export PATH="$dir/node_modules/node/bin:$PATH"
    hash -r
  fi
  node -v | grep -q '^v24\.' || { echo "Node 24 is required (see .nvmrc)"; return 1; }
  echo "node $(node -v)"
}

pnpm_pinned() { (cd "$ROOT" && npm exec --yes -- pnpm@10.34.6 "$@"); }

stage_spike() {
  env_report
  run gradle-version gradle_core --version
  run spotless-apply gradle_core spotlessApply
  run core-check gradle_core clean check
  test_totals
  run resolved-versions gradle_core dependencyInsight --configuration testRuntimeClasspath \
    --dependency org.springframework.boot:spring-boot
  (cd "$ROOT/apps/core-api" && gradle_core dependencies --configuration testRuntimeClasspath \
    > "$OUT/core-api-dependencies.txt" 2>&1)
  gradle_sha
  image_digests
}

stage_core() {
  env_report
  run spotless-apply gradle_core spotlessApply
  run core-check gradle_core --no-build-cache clean check bootJar
  test_totals
  gradle_sha
}

stage_stack() {
  env_report
  local compose="docker compose -f $ROOT/infrastructure/docker/compose.yaml --env-file $ROOT/.env.example"
  run compose-build $compose build
  run compose-up $compose up -d --wait --wait-timeout 300
  run status-core curl -fsS -H 'X-Correlation-Id: host-verify-0001' http://localhost:8080/api/v1/system/status
  run status-ai curl -fsS http://localhost:8090/api/v1/system/status
  run cors-core-reject bash -c "! curl -fsS -X OPTIONS -H 'Origin: http://evil.example' -H 'Access-Control-Request-Method: GET' http://localhost:8080/api/v1/system/status"
  run compose-ps $compose ps
  run node24 use_node24
  run pnpm-install pnpm_pinned install --frozen-lockfile
  run playwright-browsers pnpm_pinned --filter @divalhr/web exec playwright install chromium
  run e2e pnpm_pinned --filter @divalhr/web exec playwright test
  run compose-logs bash -c "$compose logs --no-color > '$OUT/compose.log' 2>&1"
  run no-secrets-in-logs bash -c "! grep -E 'eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.|Bearer [A-Za-z0-9._-]{20,}|dev-only-(Admin|Employee|Platform)' '$OUT/compose.log'"
  run compose-down $compose down -v
}

case "$STAGE" in
  spike) stage_spike ;;
  core) stage_core ;;
  stack) stage_stack ;;
  all) stage_core; stage_stack ;;
  *) echo "unknown stage: $STAGE" >&2; exit 2 ;;
esac

log ""
log "=== summary ($SUMMARY)"
cat "$SUMMARY"
grep -q '^FAIL' "$SUMMARY" && exit 1 || exit 0
