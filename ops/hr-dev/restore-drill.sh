#!/usr/bin/env bash
# OPS-001 (section 9, A65-6): isolated restore drill. Restores the newest logical backup into a
# separate, fresh stack and proves it works, while the running environment keeps serving.
#
#   sudo ops/hr-dev/restore-drill.sh [--backup <backup dir>] [--keep]
#
# Isolation: its own compose project (<project>-drill), its own networks (subnet prefix
# HR_DEV_DRILL_SUBNET_PREFIX, default 10.72), loopback-only ports (HR_DEV_DRILL_PORT_PREFIX,
# default 29: 127.0.0.1:29080/29443/29180), Caddy's internal CA instead of ACME, and fresh
# storage under $HR_DEV_DATA/drill/<stamp>/ on the same encrypted volume. The running project's
# containers, networks and directories (postgres/ above all) are never touched; the drill uses
# the running release's images and a copy of its secrets, and is removed afterwards unless --keep.
#
# Proof (all measured, written to $HR_DEV_DATA/state/last-restore-drill):
#   both dumps restore into empty databases; Core starts, so Flyway validated the restored
#   history, and the history count equals the backup's MANIFEST; the realm exists; the HTTP and
#   back-channel smoke checks pass on the drill; HR_DEV_DRILL_E2E (optional command) passes; the
#   running environment answered healthy every 5 seconds throughout.
set -u
. "$(dirname "$0")/lib.sh"

BACKUP="" KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
hr_require_root
hr_require_encrypted_data_root
divalhr_lock "restore drill" || exit $?
LIVE="$(hr_current_release)"
[ -n "$LIVE" ] || hr_die "no running release"
hr_require_sha "$LIVE"
if [ -z "$BACKUP" ]; then
  BACKUP=$( { hr_latest_backup nightly; hr_latest_backup pre-deploy; } | while read -r d; do
    [ -n "$d" ] && printf '%s %s\n' "$(sed -n 's/^created=//p' "$d/MANIFEST")" "$d"; done | LC_ALL=C sort | tail -1 | cut -d' ' -f2-)
fi
[ -n "$BACKUP" ] && [ -f "$BACKUP/MANIFEST" ] || hr_die "no backup to drill"
expected_flyway=$(sed -n 's/^flyway_success=//p' "$BACKUP/MANIFEST")
started=$(date +%s)
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT="$HR_DEV_DATA/state/last-restore-drill"
LIVE_DATA="$HR_DEV_DATA"
LIVE_PROJECT="$HR_DEV_PROJECT"
LIVE_CONNECT="$HR_DEV_CONNECT_HTTPS"
hr_export_tls
LIVE_CA="${HR_DEV_CA_FILE:-}"

# --- the running environment is watched for the whole drill --------------------------------------
WATCH_LOG="$(mktemp)"
watch_live() {
  local curl_args=(curl -fsS --noproxy '*' --max-time 5 -o /dev/null
    --connect-to "$HR_DEV_HOST:443:$LIVE_CONNECT")
  [ -n "$LIVE_CA" ] && curl_args+=(--cacert "$LIVE_CA")
  while :; do
    local now bad=""
    now=$(date -u +%H:%M:%SZ)
    "${curl_args[@]}" "$HR_DEV_ORIGIN/api/v1/system/status" 2>/dev/null || bad="core-status"
    "${curl_args[@]}" "$HR_DEV_ORIGIN/identity/realms/divalhr-test/.well-known/openid-configuration" 2>/dev/null \
      || bad="$bad identity"
    docker ps --filter "label=com.docker.compose.project=$LIVE_PROJECT" --format '{{.Names}} {{.Status}}' \
      | grep -E 'unhealthy|Restarting|Exited' >/dev/null && bad="$bad containers"
    if [ -n "$bad" ]; then echo "$now FAIL $bad"; else echo "$now ok"; fi
    sleep 5
  done >> "$WATCH_LOG"
}
watch_live & WATCH_PID=$!

# --- the drill project -----------------------------------------------------------------------
DRILL_DATA="$LIVE_DATA/drill/$STAMP"
P="${HR_DEV_DRILL_PORT_PREFIX:-29}"
drill_env() {
  export HR_DEV_DATA="$DRILL_DATA" HR_DEV_PROJECT="$LIVE_PROJECT-drill" HR_DEV_RELEASES="$LIVE_DATA/releases"
  export HR_DEV_SUBNET_PREFIX="${HR_DEV_DRILL_SUBNET_PREFIX:-10.72}" HR_DEV_ADMIN_PORT="${P}180"
  export HR_DEV_HTTP_PUBLISH="127.0.0.1:${P}080" HR_DEV_HTTPS_PUBLISH="127.0.0.1:${P}443"
  export HR_DEV_CONNECT_HTTPS="127.0.0.1:${P}443" HR_DEV_CONNECT_HTTP="127.0.0.1:${P}080"
  export HR_DEV_BARE_IP_URL="http://127.0.0.1:${P}080/" HR_DEV_TLS=internal
  export HR_DEV_CA_FILE="$DRILL_DATA/caddy/data/caddy/pki/authorities/local/root.crt"
}
teardown() {
  kill "$WATCH_PID" 2>/dev/null
  if [ "$KEEP" = 0 ]; then
    ( drill_env; hr_compose "$LIVE" down --remove-orphans >/dev/null 2>&1 )
    rm -rf "${DRILL_DATA:?}"
    rmdir "$LIVE_DATA/drill" 2>/dev/null
  fi
  rm -f "$WATCH_LOG"
}
trap teardown EXIT

result=FAIL
(
  set -u
  drill_env
  [ "$HR_DEV_PROJECT" != "$LIVE_PROJECT" ] || hr_die "drill project equals the live project"
  hr_log "drill $HR_DEV_PROJECT of $(basename "$BACKUP") on release $LIVE"
  mkdir -p "$DRILL_DATA"
  cp -a "$LIVE_DATA/secrets" "$DRILL_DATA/secrets"
  hr_prepare_data "$(hr_release_dir "$LIVE")"
  t=$(date +%s)
  hr_compose "$LIVE" up -d --no-build --wait --wait-timeout 300 postgres || hr_die "drill PostgreSQL did not start"
  hr_restore_backup "$LIVE" "$BACKUP"
  restore_s=$(( $(date +%s) - t ))
  t=$(date +%s)
  hr_compose "$LIVE" up -d --no-build --wait --wait-timeout 600 || hr_die "the restored stack did not become healthy (Flyway validation or start-up)"
  healthy_s=$(( $(date +%s) - t ))
  got=$(hr_flyway_count "$LIVE")
  [ "$got" = "$expected_flyway" ] || hr_die "Flyway history has $got entries, backup recorded $expected_flyway"
  hr_log "Flyway history: $got successful entries, as in the backup"
  realm=$(hr_compose "$LIVE" exec -T postgres psql -U postgres -d keycloak -At \
    -c "SELECT count(*) FROM realm WHERE name = 'divalhr-test'" | tr -d '[:space:]')
  [ "$realm" = 1 ] || hr_die "the restored Keycloak database has no divalhr-test realm"
  "$(hr_release_dir "$LIVE")/ops/hr-dev/http-checks.sh" || hr_die "HTTP smoke checks failed on the drill"
  "$(hr_release_dir "$LIVE")/ops/hr-dev/backchannel-check.sh" "$LIVE" || hr_die "back-channel check failed on the drill"
  if [ -n "${HR_DEV_DRILL_E2E:-}" ]; then
    bash -c "$HR_DEV_DRILL_E2E" || hr_die "browser smoke failed on the drill"
  fi
  printf 'restore_seconds=%s\nhealthy_seconds=%s\n' "$restore_s" "$healthy_s" > "$LIVE_DATA/state/.drill-times"
) && result=PASS
[ "$result" = PASS ] || ( drill_env; hr_diagnose "$LIVE" )

kill "$WATCH_PID" 2>/dev/null; wait "$WATCH_PID" 2>/dev/null
live_failures=$(grep -c ' FAIL' "$WATCH_LOG" || true)
live_samples=$(grep -c . "$WATCH_LOG" || true)
grep ' FAIL' "$WATCH_LOG" | head -5
[ "$live_failures" = 0 ] && [ "$live_samples" -gt 0 ] || result=FAIL
{
  printf 'result=%s\nbackup=%s\nrelease=%s\nfinished=%s\ntotal_seconds=%s\n' "$result" "$(basename "$BACKUP")" \
    "$LIVE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(( $(date +%s) - started ))"
  cat "$LIVE_DATA/state/.drill-times" 2>/dev/null
  printf 'live_samples=%s\nlive_failures=%s\n' "$live_samples" "$live_failures"
} > "$REPORT"
rm -f "$LIVE_DATA/state/.drill-times"
sed 's/^/  /' "$REPORT"
[ "$result" = PASS ] && hr_log "restore drill PASSED" && exit 0
hr_log "restore drill FAILED"
exit 1
