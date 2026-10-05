#!/usr/bin/env bash
# OPS-001: full rehearsal of the test environment on the AWS instance, run by every complete
# aws-verify (verify-on-host stage hrdev). Exercises the real scripts and containers end to end,
# in a throw-away project that cannot touch the live one:
#
#   project divalhr-rehearsal, images divalhr-rehearsal/*:<sha>, subnets 10.73 (drill 10.74),
#   ports 127.0.0.1:28080/28443/28180 (drill 39080/39443/39180), Caddy's internal CA,
#   data in /var/tmp/divalhr-rehearsal (synthetic only), the same public name and issuer
#   (https://hr-dev.dival.ai) reached through --connect-to and Chromium host-resolver rules.
#
#   sudo -n env HR_DEV_USER_PATH="$PATH" ops/hr-dev/rehearse.sh      (from the verified checkout)
#
# Steps (each prints PASS/FAIL; the summary decides the exit status):
#   first deployment (remote-deploy --first-run) with HTTP and back-channel checks
#   browser acceptance suite (apps/web/e2e/hr-dev) against the rehearsal
#   realm verification in final mode (privileged MFA, narrow provisioning)
#   nightly backup; host-lock concurrency (A65-5); watchdog failure injection (A65-3)
#   isolated restore drill with the browser suite on the restored stack (A65-6)
#   no-migration rollback: a release whose checks fail is rolled back automatically
#   migration rollback: a release with a new migration whose checks fail; the pre-deploy dumps
#   are restored before the previous images start; timings are measured
# Everything is removed at the end (HR_DEV_REHEARSAL_KEEP=1 keeps it for diagnosis).
set -u
export HR_DEV_REHEARSAL=1
export HR_DEV_DATA="${HR_DEV_REHEARSAL_DATA:-/var/tmp/divalhr-rehearsal}"
export HR_DEV_PROJECT=divalhr-rehearsal HR_DEV_IMAGE_PREFIX=divalhr-rehearsal
export HR_DEV_SUBNET_PREFIX=10.73 HR_DEV_ADMIN_PORT=28180
export HR_DEV_HTTP_PUBLISH=127.0.0.1:28080 HR_DEV_HTTPS_PUBLISH=127.0.0.1:28443
export HR_DEV_TLS=internal
export HR_DEV_DRILL_SUBNET_PREFIX=10.74 HR_DEV_DRILL_PORT_PREFIX=39
. "$(dirname "$0")/lib.sh"
hr_require_root
# The rehearsal's Caddy uses its internal CA; every check run from here trusts exactly that CA.
HR_DEV_CA_FILE="$(hr_internal_ca)"; export HR_DEV_CA_FILE
divalhr_lock "hr-dev rehearsal" || exit $?

REPO="$(cd "$HR_DEV_OPS/../.." && pwd)"
SHA_A="$(git -C "$REPO" rev-parse HEAD)"
RUN_USER="${SUDO_USER:-}"
W="$(mktemp -d /var/tmp/divalhr-rehearsal-work.XXXXXX)"
SUMMARY="$W/summary"
: > "$SUMMARY"
pass() { echo "PASS $*" | tee -a "$SUMMARY"; }
fail() { echo "FAIL $*" | tee -a "$SUMMARY"; }
step() { local name="$1"; shift; echo ""; echo "=== [$name]"; if "$@"; then pass "$name"; else fail "$name"; return 1; fi; }

cleanup() {
  [ "${HR_DEV_REHEARSAL_KEEP:-}" = 1 ] && { echo "kept $HR_DEV_DATA and $W"; return; }
  for project in "$HR_DEV_PROJECT" "$HR_DEV_PROJECT-drill"; do
    docker ps -aq --filter "label=com.docker.compose.project=$project" | xargs -r docker rm -f >/dev/null 2>&1
    docker network ls -q --filter "label=com.docker.compose.project=$project" | xargs -r docker network rm >/dev/null 2>&1
  done
  for sha in "$SHA_A" "${SHA_B:-}" "${SHA_C:-}"; do
    [ -n "$sha" ] || continue
    for svc in keycloak core-api ai-service web; do docker image rm "$HR_DEV_IMAGE_PREFIX/$svc:$sha" >/dev/null 2>&1; done
  done
  rm -rf "${HR_DEV_DATA:?}" "${W:?}"
}
trap cleanup EXIT
[ ! -e "$HR_DEV_DATA" ] || rm -rf "${HR_DEV_DATA:?}"
docker ps -aq --filter "label=com.docker.compose.project=$HR_DEV_PROJECT" | grep -q . \
  && { echo "STOP: a previous rehearsal is still running"; exit 1; }

# --- releases: A = the verified commit; C = A + failing checks; B = A + migration + failing checks
git clone -q --no-checkout "$REPO" "$W/repo" && git -C "$W/repo" checkout -q --detach "$SHA_A" || exit 1
commit() {
  git -C "$W/repo" add -A && git -C "$W/repo" -c user.name=rehearsal -c user.email=rehearsal@hr-dev.example.test \
    commit -q -m "$1" && git -C "$W/repo" rev-parse HEAD
}
bundle() {
  git -C "$W/repo" update-ref refs/divalhr/rehearsal "$1" \
    && git -C "$W/repo" bundle create "$W/$1.bundle" refs/divalhr/rehearsal >/dev/null 2>&1
}
inject_check_failure() {
  [ -f "$W/repo/ops/hr-dev/http-checks.sh" ] || return 1
  printf '#!/usr/bin/env bash\necho "rehearsal: injected check failure"\nexit 1\n' > "$W/repo/ops/hr-dev/http-checks.sh"
}
bundle "$SHA_A" && inject_check_failure || { echo "STOP: cannot prepare the rehearsal releases"; exit 1; }
SHA_C=$(commit "rehearsal: release whose checks fail (no migration)") && hr_require_sha "$SHA_C" && bundle "$SHA_C"
git -C "$W/repo" checkout -q --detach "$SHA_A" && inject_check_failure || exit 1
printf -- '-- OPS-001 rehearsal only: a migration the previous release does not know.\nCREATE TABLE platform.ops001_rehearsal_marker (id integer PRIMARY KEY);\n' \
  > "$W/repo/apps/core-api/src/main/resources/db/migration/V9000__ops001_rehearsal_marker.sql"
SHA_B=$(commit "rehearsal: release with a migration whose checks fail") && hr_require_sha "$SHA_B" && bundle "$SHA_B"

# --- 1. first deployment ---------------------------------------------------------------------
step deploy-first "$HR_DEV_OPS/remote-deploy.sh" --bundle "$W/$SHA_A.bundle" --sha "$SHA_A" --first-run \
  || { echo "STOP: the first deployment failed"; cat "$SUMMARY"; exit 1; }
[ "$(hr_current_release)" = "$SHA_A" ] && pass "current release is the deployed SHA" || fail "current release"
for svc in keycloak core-api ai-service web; do
  docker image inspect "$HR_DEV_IMAGE_PREFIX/$svc:$SHA_A" >/dev/null 2>&1 || fail "image $svc:$SHA_A missing"
done

# --- 2. browser acceptance suite ---------------------------------------------------------------
export REHEARSAL_REPO="$REPO" REHEARSAL_USER="$RUN_USER"
if [ -n "$RUN_USER" ]; then
  step browser-suite "$HR_DEV_OPS/rehearsal-browser.sh" 28443 28180
else
  fail "browser-suite (run through sudo from the verification user)"
fi

# --- realm in final mode: privileged MFA and narrow provisioning (MVP-011, Issue #31) ------------
realm_verify() {
  # shellcheck disable=SC1091
  ( . "$HR_DEV_DATA/secrets/ops/kc-admin.env"
    PATH="${HR_DEV_USER_PATH:-$PATH}" KEYCLOAK_VERIFY_TRANSPORT=http KEYCLOAK_REALM=divalhr-test \
      KEYCLOAK_URL="http://localhost:$HR_DEV_ADMIN_PORT/identity" \
      KEYCLOAK_ADMIN_USER="$KC_ADMIN_USER" KEYCLOAK_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" \
      node "$REPO/infrastructure/docker/keycloak/verify-realm.mjs" --mode=final )
}
step realm-verify-final realm_verify

# --- 3. backup ---------------------------------------------------------------------------------
step backup "$HR_DEV_OPS/backup.sh" --reason nightly
NIGHTLY="$(hr_latest_backup nightly)"
[ -n "$NIGHTLY" ] && grep -q '^flyway_success=[1-9]' "$NIGHTLY/MANIFEST" && pass "backup manifest records the Flyway history" \
  || fail "backup manifest"

# --- 4. host lock: a second operation exits before changing anything (A65-5) -------------------
lock_test() {
  local lock holder="" rc before after
  if [ "${DIVALHR_HOST_LOCK_HELD:-}" = 1 ] && [ -e "$DIVALHR_HOST_LOCK" ]; then
    lock="$DIVALHR_HOST_LOCK"            # the real host lock, held by this aws-verify run
  else
    lock="$W/test.lock"; : > "$lock"
    flock -n "$lock" sleep 600 & holder=$!; sleep 1
  fi
  before=$(find "$HR_DEV_DATA/backups" "$HR_DEV_DATA/releases" "$HR_DEV_DATA/state" -maxdepth 2 | LC_ALL=C sort | sha256sum)
  for op in "backup.sh --reason nightly" "remote-deploy.sh --bundle $W/$SHA_C.bundle --sha $SHA_C" \
    "rollback.sh --to $SHA_C" "restore-drill.sh" "keycloak-admin-setup.sh"; do
    # shellcheck disable=SC2086
    env -u DIVALHR_HOST_LOCK_HELD DIVALHR_HOST_LOCK="$lock" "$HR_DEV_OPS"/$op > "$W/lock.out" 2>&1
    rc=$?
    if [ "$rc" = 75 ] && grep -q 'holds the host lock' "$W/lock.out"; then
      echo "  ${op%% *}: exit 75, refused"
    else
      echo "  ${op%% *}: exit $rc"; sed 's/^/    /' "$W/lock.out" | tail -3; [ -n "$holder" ] && kill "$holder"; return 1
    fi
  done
  out=$(env -u DIVALHR_HOST_LOCK_HELD DIVALHR_HOST_LOCK="$lock" "$HR_DEV_OPS/watchdog.sh" 2>&1)
  printf '%s\n' "$out" | grep -q 'skipped' || { echo "  watchdog did not skip"; return 1; }
  echo "  watchdog: skipped"
  after=$(find "$HR_DEV_DATA/backups" "$HR_DEV_DATA/releases" "$HR_DEV_DATA/state" -maxdepth 2 | LC_ALL=C sort | sha256sum)
  [ -n "$holder" ] && kill "$holder"
  [ "$before" = "$after" ] || { echo "  files changed while the lock was busy"; return 1; }
  echo "  nothing changed under $HR_DEV_DATA"
}
step host-lock-concurrency lock_test

# HTTP checks with their failures shown; a recovered container may need a few seconds to serve.
checks_ok() {
  local i
  for i in 1 2 3; do
    "$HR_DEV_OPS/http-checks.sh" > "$W/checks.out" 2>&1 && return 0
    sleep 10
  done
  echo "  HTTP checks failed $1:"; grep -E '^FAIL' "$W/checks.out" | sed 's/^/    /'
  return 1
}

# --- 5. watchdog: a hung Core API is restarted by the watchdog, not by Docker (A65-3) ------------
watchdog_test() {
  local id deadline out
  id=$(hr_compose "$SHA_A" ps -q core-api) || return 1
  rm -rf "$HR_DEV_DATA/state/watchdog"
  docker kill --signal STOP "$id" >/dev/null || return 1
  echo "  Core API process stopped (SIGSTOP); waiting for its health check to report unhealthy"
  deadline=$(( $(date +%s) + 300 ))
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$id")" = unhealthy ]; do
    [ "$(date +%s)" -lt "$deadline" ] || { echo "  never became unhealthy"; docker kill --signal CONT "$id"; return 1; }
    sleep 10
  done
  [ "$(docker inspect -f '{{.State.Status}}' "$id")" = running ] && echo "  unhealthy and still running: Docker's restart policy does not act"
  for i in 1 2 3; do
    out=$("$HR_DEV_OPS/watchdog.sh" 2>&1); echo "  run $i: $(printf '%s' "$out" | grep core-api | tail -1)"
    sleep 5
  done
  printf '%s\n' "$out" | grep -q 'restarted core-api' || { echo "  the watchdog did not restart Core"; return 1; }
  deadline=$(( $(date +%s) + 240 ))
  until [ "$(docker inspect -f '{{.State.Health.Status}}' "$id")" = healthy ]; do
    [ "$(date +%s)" -lt "$deadline" ] || { echo "  Core did not recover"; return 1; }
    sleep 5
  done
  echo "  Core API healthy again after the watchdog restart"
  out=$("$HR_DEV_OPS/watchdog.sh" 2>&1)
  printf '%s\n' "$out" | grep -q 'restarted' && { echo "  unexpected second restart"; return 1; }
  [ "$(grep -c . "$HR_DEV_DATA/state/watchdog/core-api.restarts")" = 1 ] || return 1
  checks_ok "after recovery" || return 1
}
step watchdog-failure-injection watchdog_test

# --- 6. isolated restore drill (A65-6) -----------------------------------------------------------
export HR_DEV_DRILL_E2E="'$HR_DEV_OPS/rehearsal-browser.sh' 39443 39180 --grep-invert 'admin console'"
step restore-drill "$HR_DEV_OPS/restore-drill.sh" && sed 's/^/  /' "$HR_DEV_DATA/state/last-restore-drill"
unset HR_DEV_DRILL_E2E

# --- 7. no-migration rollback ------------------------------------------------------------------
rollback_test() { # <bundle sha> <expected path>
  local sha="$1" path="$2" rc
  "$HR_DEV_OPS/remote-deploy.sh" --bundle "$W/$sha.bundle" --sha "$sha"; rc=$?
  [ "$rc" != 0 ] || { echo "  the failing release was accepted"; return 1; }
  [ "$(hr_current_release)" = "$SHA_A" ] || { echo "  the running release is not the previous one"; return 1; }
  grep -qx "path=$path" "$HR_DEV_DATA/state/last-rollback" || { echo "  rollback path was not $path"; return 1; }
  sed 's/^/  /' "$HR_DEV_DATA/state/last-rollback"
  checks_ok "after the rollback" || return 1
}
step rollback-no-migration rollback_test "$SHA_C" no-migration

# --- 8. migration rollback ---------------------------------------------------------------------
migration_test() {
  rollback_test "$SHA_B" migration || return 1
  hr_applied_migrations "$SHA_A" | grep -qx 9000 && { echo "  the rehearsal migration survived"; return 1; }
  [ "$(hr_compose "$SHA_A" exec -T postgres psql -U postgres -d divalhr -At \
    -c "SELECT to_regclass('platform.ops001_rehearsal_marker') IS NULL")" = t ] || { echo "  marker table survived"; return 1; }
  echo "  the pre-deploy dumps were restored before the previous release started"
}
step rollback-migration migration_test

echo ""
echo "=== rehearsal summary"
cat "$SUMMARY"
grep -q '^FAIL' "$SUMMARY" && exit 1
exit 0
