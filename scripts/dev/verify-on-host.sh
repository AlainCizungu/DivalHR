#!/usr/bin/env bash
# Runs build and verification stages on a developer workstation and writes logs to
# .git/divalhr-verify/ (inside .git so they are never committed).
#
# Usage: scripts/dev/verify-on-host.sh <profile or stage>
# Profiles (DEVX-001A, D3 and A75-2; verification only: nothing here deploys or touches hr-dev):
#   changed  checks for what changed since the base (DIVALHR_VERIFY_BASE, default the merge-base
#            with origin/main), chosen by scripts/dev/classify-changed.mjs; widens to `pr` or
#            `full` whenever a path needs it. Used during implementation and review fixes.
#   pr       core + stack: complete application checks and the optimized browser suite
#   full     core + stack + hrdev (the complete operational rehearsal); always runnable manually
# Stages (and compatible aliases):
#   spike   Core API compatibility spike (Gradle check, SpotBugs, tests) + image digests
#   core    Core API format + full clean check + bootJar
#   stack   Compose stack up, status/CORS probes, browser suite (e2e/run-suite.sh: features in
#           parallel, then identity), log secret scan, down
#   hrdev   OPS-001: full test-environment rehearsal (ops/hr-dev/rehearse.sh, needs sudo -n):
#           deploy, browser acceptance, backup, host lock, watchdog, restore drill, rollbacks
#   all     the same as `full`
# Each stage's duration is written to .git/divalhr-verify/<profile>.timings (`TIME <name> <s>s`);
# the summary format is unchanged.
#
# OPS-001 (A65-5): takes the host-wide lock (ops/host/host-lock.sh) unless the caller (aws-verify)
# already holds it; on a machine without the lock file (a workstation) it runs without it.
#
# Compatible with macOS bash 3.2 and Linux bash.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAGE="${1:-spike}"
# shellcheck source=../../ops/host/host-lock.sh
. "$ROOT/ops/host/host-lock.sh"
divalhr_lock "verify-on-host $STAGE" --optional || exit $?
OUT="$ROOT/.git/divalhr-verify"
mkdir -p "$OUT"
LOG="$OUT/$STAGE.log"
SUMMARY="$OUT/$STAGE.summary"
TIMINGS="$OUT/$STAGE.timings"
: > "$LOG"
: > "$SUMMARY"
: > "$TIMINGS"

log() { printf '%s\n' "$*" | tee -a "$LOG"; }
result() { printf '%s\n' "$*" | tee -a "$SUMMARY" >> "$LOG"; }
run() {
  local name="$1"; shift
  log ""
  log "=== [$name] $*"
  local started rc
  started=$(date +%s)
  "$@" >> "$LOG" 2>&1
  rc=$?
  printf 'TIME %s %ss\n' "$name" "$(( $(date +%s) - started ))" | tee -a "$TIMINGS" >> "$LOG"
  if [ "$rc" = 0 ]; then
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

# DEVX-001A (A75-7): the backend-split investigation. Test time per group from the JUnit XML of
# the clean check (one test JVM today): `spring` = Spring or Testcontainers classes, `keycloak` =
# the Keycloak container tests, `unit` = the rest; plus the ten slowest classes. Class names only.
test_groups() {
  local dir="$ROOT/apps/core-api/build/test-results/test" src="$ROOT/apps/core-api/src/test/java"
  [ -d "$dir" ] || return 0
  local xml cls file group line
  for xml in "$dir"/*.xml; do
    line=$(grep -m1 -o '<testsuite name="[^"]*" tests="[0-9]*"[^>]* time="[0-9.]*"' "$xml") || continue
    cls=$(printf '%s' "$line" | sed 's/^<testsuite name="\([^"]*\)".*/\1/')
    file="$src/$(printf '%s' "${cls%%\$*}" | tr . /).java"
    group=unit
    if grep -qE 'KeycloakTestStack|KeycloakContainer' "$file" 2>/dev/null; then group=keycloak
    elif grep -qE '@SpringBootTest|@WebMvcTest|@DataJpaTest|Testcontainers|IntegrationTest' "$file" 2>/dev/null; then group=spring
    fi
    printf '%s %s %s %s\n' "$group" "$(printf '%s' "$line" | sed 's/.* tests="\([0-9]*\)".*/\1/')" \
      "$(printf '%s' "$line" | sed 's/.* time="\([0-9.]*\)"$/\1/')" "$cls"
  done > "$OUT/core-test-classes.txt"
  awk '{c[$1]++; t[$1]+=$2; s[$1]+=$3} END {for (g in c) printf "GROUP %s classes=%d tests=%d seconds=%d\n", g, c[g], t[g], s[g]}' \
    "$OUT/core-test-classes.txt" | sort | tee -a "$TIMINGS" >> "$LOG"
  { echo "slowest test classes (seconds):"; sort -k3 -rn "$OUT/core-test-classes.txt" | head -10 |
    awk '{printf "  %s %.1f %s\n", $1, $3, $4}'; } >> "$LOG"
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
    mcr.microsoft.com/playwright:v1.63.0-noble \
    axllent/mailpit:v1.29.6; do
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

# MVP-011 (A4) and Issue #31 (A3): read-only check of the running realm in final mode (the only mode
# acceptable as evidence); prints rule names and PASS/FAIL only.
realm_verify() {
  (
    export KEYCLOAK_VERIFY_TRANSPORT=compose KEYCLOAK_ADMIN_USER=dev-kc-admin
    export KEYCLOAK_ADMIN_PASSWORD=dev-only-keycloak-admin
    pnpm_pinned realm:verify
  )
}

# MVP-012A (A3, A7): read-only membership preflight against the running stack; prints counts and
# IDs only. Credentials come from the environment, never from arguments.
membership_preflight() {
  (
    export KEYCLOAK_ADMIN_USER=dev-kc-admin KEYCLOAK_ADMIN_PASSWORD=dev-only-keycloak-admin
    export PREFLIGHT_DB_TRANSPORT=compose
    pnpm_pinned membership:preflight
  )
}

pnpm_pinned() { (cd "$ROOT" && npm exec --yes -- pnpm@10.34.6 "$@"); }

# DEVX-001A (D2, A75-4): `features` in parallel, then `identity` alone; both always run, either
# failing fails the stage; per-part timings go to the timings file. E2E_WORKERS overrides 2.
e2e_suite() {
  (cd "$ROOT" && PATH="$PATH" bash apps/web/e2e/run-suite.sh --workers "${E2E_WORKERS:-2}" \
    --timings "$TIMINGS")
}

# DEVX-001A (D3): the `changed` profile. The classifier only widens; an unreadable diff or a missing
# base selects `pr`.
stage_changed() {
  env_report
  local base="${DIVALHR_VERIFY_BASE:-}" decision profile
  [ -n "$base" ] || base=$(git -C "$ROOT" merge-base origin/main HEAD 2>/dev/null) || base=""
  decision=$(cd "$ROOT" && node scripts/dev/classify-changed.mjs ${base:+"$base"} HEAD 2>/dev/null) \
    || decision="profile=pr"
  printf '%s\n' "$decision" | sed 's/^/  /' >> "$LOG"
  profile=$(printf '%s\n' "$decision" | sed -n 's/^profile=//p')
  result "INFO changed base=${base:-none} profile=${profile:-pr}"
  case "$profile" in
    full) stage_core; stage_stack; stage_hrdev; return ;;
    changed) ;;
    *) stage_core; stage_stack; return ;;
  esac
  local checks specs
  checks=$(printf '%s\n' "$decision" | sed -n 's/^check=//p')
  specs=$(printf '%s\n' "$decision" | sed -n 's/^spec=//p')
  [ -n "$checks" ] || { result "INFO nothing to verify"; return; }
  if printf '%s\n' "$checks" | grep -qx core; then
    run spotless-apply gradle_core spotlessApply
    run core-check-incremental gradle_core check
    test_totals
  fi
  if printf '%s\n' "$checks" | grep -qxE 'web|docs|e2e'; then
    run node24 use_node24
    run pnpm-install pnpm_pinned install --frozen-lockfile
    run format-check pnpm_pinned format:check
  fi
  if printf '%s\n' "$checks" | grep -qx web; then
    run lint pnpm_pinned lint
    run typecheck pnpm_pinned typecheck
    run web-test pnpm_pinned test
  fi
  if printf '%s\n' "$checks" | grep -qx e2e; then
    if [ "$specs" = "*" ]; then
      stage_stack
    else
      # Only the changed spec files, still split into features and identity by run-suite.sh.
      E2E_SPECS="$(printf '%s ' $specs)" stage_stack
    fi
  fi
}

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
  test_groups
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
  run realm-test pnpm_pinned realm:test
  run ops-test pnpm_pinned ops:test
  run realm-verify realm_verify
  run playwright-browsers pnpm_pinned --filter @divalhr/web exec playwright install chromium
  run e2e e2e_suite
  run membership-preflight membership_preflight
  run compose-logs bash -c "$compose logs --no-color > '$OUT/compose.log' 2>&1"
  run no-secrets-in-logs bash -c "! grep -E 'eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.|Bearer [A-Za-z0-9._-]{20,}|dev-only-(Admin|Employee|Platform|totp)' '$OUT/compose.log'"
  run compose-down $compose down -v
}

# OPS-001: the test-environment rehearsal runs as root through sudo -n (it creates files owned by
# the container users); the browser suite inside it runs as this user again.
stage_hrdev() {
  env_report
  run node24 use_node24
  run pnpm-install pnpm_pinned install --frozen-lockfile
  run playwright-browsers pnpm_pinned --filter @divalhr/web exec playwright install chromium
  run hrdev-ops-test pnpm_pinned ops:test
  run hrdev-rehearsal sudo -n env DIVALHR_HOST_LOCK_HELD="${DIVALHR_HOST_LOCK_HELD:-}" \
    HR_DEV_USER_PATH="$PATH" "$ROOT/ops/hr-dev/rehearse.sh"
}

case "$STAGE" in
  spike) stage_spike ;;
  core) stage_core ;;
  stack) stage_stack ;;
  hrdev) stage_hrdev ;;
  changed) stage_changed ;;
  pr) stage_core; stage_stack ;;
  all|full) stage_core; stage_stack; stage_hrdev ;;
  *) echo "unknown profile or stage: $STAGE" >&2; exit 2 ;;
esac

log ""
log "=== summary ($SUMMARY)"
cat "$SUMMARY"
grep -q '^FAIL' "$SUMMARY" && exit 1 || exit 0
