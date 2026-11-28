#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-9): isolated restore drill. Restores a backup into a separate, fresh stack
# on the same host and proves it works, while production keeps serving. The first production
# deployment is not accepted until one complete drill passes; the runbook repeats it every quarter.
#
#   sudo ops/production/restore-drill.sh [--local] [--backup <dir>] [--keep]
#
# By default the newest NIGHTLY backup is downloaded from the OFF-HOST bucket and its checksums are
# verified, so the drill proves the copy that would be used after losing the host (--local drills the
# newest local dump instead; --backup names a directory). Isolation: its own compose project
# (<project>-drill), its own networks (PROD_DRILL_SUBNET_PREFIX, default 10.73), loopback-only ports
# (29443, 29180), fresh storage under $PROD_DATA/drill/<stamp>/, and NO outbound e-mail: the drill's
# mail host is an unresolvable name, so restored data can never cause a message to be sent.
#
# Proof (all measured, written to state/last-restore-drill): both dumps restore into empty
# databases; Core starts, so Flyway validated the restored history, and the history count equals the
# backup's MANIFEST; the restored migration set equals the running release's; the production realm
# exists; the HTTP and back-channel checks pass on the drill; production answered healthy every
# 5 seconds throughout. Recovery time is measured from "start of restore" to "drill stack healthy".
set -u
. "$(dirname "$0")/lib.sh"

BACKUP="" KEEP=0 LOCAL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) BACKUP="$2"; shift 2 ;;
    --local) LOCAL=1; shift ;;
    --keep) KEEP=1; shift ;;
    *) prod_die "unknown argument $1" ;;
  esac
done
prod_require_root
prod_require_encrypted_data_root
divalhr_lock "restore drill" || exit $?
LIVE="$(hr_current_release)"
[ -n "$LIVE" ] || prod_die "no running release"
hr_require_sha "$LIVE"
LIVE_DATA="$PROD_DATA" LIVE_PROJECT="$PROD_PROJECT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DRILL_DATA="$LIVE_DATA/drill/$STAMP"
started=$(date +%s)
REPORT="$LIVE_DATA/state/last-restore-drill"
SOURCE="local"

if [ -z "$BACKUP" ] && [ "$LOCAL" = 0 ]; then
  BUCKET="$(prod_config backup-bucket)" REGION="$(prod_config region)"
  [ -n "$BUCKET" ] && [ -n "$REGION" ] || prod_die "off-host backup is not configured; use --local only for a rehearsal"
  AWS="${PROD_AWS:-aws}"
  latest=$("$AWS" s3api list-objects-v2 --region "$REGION" --bucket "$BUCKET" --prefix backups/nightly/ --delimiter / \
    --query 'CommonPrefixes[].Prefix' --output text | tr '\t' '\n' | LC_ALL=C sort | tail -1)
  [ -n "$latest" ] || prod_die "no nightly backup in the off-host bucket"
  BACKUP="$LIVE_DATA/drill-download/$STAMP"
  mkdir -p "$BACKUP"; chmod 700 "$LIVE_DATA/drill-download" "$BACKUP"
  "$AWS" s3 cp --region "$REGION" --only-show-errors --recursive "s3://$BUCKET/$latest" "$BACKUP/" || prod_die "download failed"
  SOURCE="off-host"
  prod_log "downloaded $latest from the off-host bucket"
elif [ -z "$BACKUP" ]; then
  BACKUP="$(hr_latest_backup nightly)"
fi
[ -n "$BACKUP" ] && [ -f "$BACKUP/MANIFEST" ] || prod_die "no backup to drill"
expected_flyway=$(sed -n 's/^flyway_success=//p' "$BACKUP/MANIFEST")
backup_created=$(sed -n 's/^created=//p' "$BACKUP/MANIFEST")
backup_release=$(sed -n 's/^release=//p' "$BACKUP/MANIFEST")

# --- production is watched for the whole drill --------------------------------------------------
WATCH_LOG="$(mktemp)"
watch_live() {
  while :; do
    now=$(date -u +%H:%M:%SZ); bad=""
    curl -fsS --noproxy '*' --insecure --max-time 5 -o /dev/null --connect-to "$PROD_APP_HOST:443:127.0.0.1:8443" \
      "https://$PROD_APP_HOST/api/v1/system/status" 2>/dev/null || bad="core-status"
    curl -fsS --noproxy '*' --insecure --max-time 5 -o /dev/null --connect-to "$PROD_APP_HOST:443:127.0.0.1:8443" \
      "https://$PROD_APP_HOST/identity/realms/$PROD_REALM/.well-known/openid-configuration" 2>/dev/null || bad="$bad identity"
    docker ps --filter "label=com.docker.compose.project=$LIVE_PROJECT" --format '{{.Names}} {{.Status}}' \
      | grep -E 'unhealthy|Restarting|Exited' >/dev/null && bad="$bad containers"
    if [ -n "$bad" ]; then echo "$now FAIL $bad"; else echo "$now ok"; fi
    sleep 5
  done >> "$WATCH_LOG"
}
watch_live & WATCH_PID=$!

drill_env() {
  export PROD_DATA="$DRILL_DATA" HR_DEV_DATA="$DRILL_DATA" PROD_PROJECT="$LIVE_PROJECT-drill" HR_DEV_PROJECT="$LIVE_PROJECT-drill"
  export HR_DEV_RELEASES="$LIVE_DATA/releases"
  export PROD_SUBNET_PREFIX="${PROD_DRILL_SUBNET_PREFIX:-10.73}" PROD_ADMIN_PORT=29180 PROD_HTTPS_PUBLISH=127.0.0.1:29443
  export PROD_LISTENER=127.0.0.1:29443
  export PROD_DRILL_MODE=1
}
teardown() {
  kill "$WATCH_PID" 2>/dev/null
  if [ "$KEEP" = 0 ]; then
    ( drill_env; hr_compose "$LIVE" down --remove-orphans >/dev/null 2>&1 )
    rm -rf "${DRILL_DATA:?}" "$LIVE_DATA/drill-download/$STAMP"
    rmdir "$LIVE_DATA/drill" "$LIVE_DATA/drill-download" 2>/dev/null
  fi
  rm -f "$WATCH_LOG"
}
trap teardown EXIT

result=FAIL
(
  set -u
  drill_env
  [ "$PROD_PROJECT" != "$LIVE_PROJECT" ] || prod_die "drill project equals the live project"
  prod_log "drill $PROD_PROJECT of backup $backup_created ($SOURCE) on release $LIVE"
  mkdir -p "$DRILL_DATA"
  cp -a "$LIVE_DATA/secrets" "$DRILL_DATA/secrets"
  cp -a "$LIVE_DATA/config" "$DRILL_DATA/config"
  # No outbound e-mail from the drill, whatever the restored data holds.
  sed -i 's/^PROD_MAIL_HOST=.*/PROD_MAIL_HOST=drill-no-mail.invalid/' "$DRILL_DATA/config/mail.env"
  grep -q '^PROD_MAIL_HOST=drill-no-mail.invalid$' "$DRILL_DATA/config/mail.env" || prod_die "could not neutralise the drill's mail host"
  prod_export_env
  hr_prepare_data "$(hr_release_dir "$LIVE")"
  t=$(date +%s)
  hr_compose "$LIVE" up -d --no-build --wait --wait-timeout 300 postgres || prod_die "drill PostgreSQL did not start"
  hr_restore_backup "$LIVE" "$BACKUP"
  restore_s=$(( $(date +%s) - t ))
  hr_compose "$LIVE" up -d --no-build --wait --wait-timeout 600 || prod_die "the restored stack did not become healthy (Flyway validation or start-up)"
  total_s=$(( $(date +%s) - t ))
  got=$(hr_flyway_count "$LIVE")
  [ "$got" = "$expected_flyway" ] || prod_die "Flyway history has $got entries, backup recorded $expected_flyway"
  prod_log "Flyway history: $got successful entries, as in the backup"
  missing=$(LC_ALL=C comm -23 <(hr_applied_migrations "$LIVE") <(hr_release_migrations "$LIVE") | grep -c . || true)
  [ "$missing" = 0 ] || prod_die "the restored database holds $missing migration(s) unknown to the running release"
  prod_log "the restored migration set matches release ${LIVE:0:12} (backup was taken on ${backup_release:0:12})"
  realm=$(hr_compose "$LIVE" exec -T postgres psql -U postgres -d keycloak -At \
    -c "SELECT count(*) FROM realm WHERE name = '$PROD_REALM'" | tr -d '[:space:]')
  [ "$realm" = 1 ] || prod_die "the restored Keycloak database has no $PROD_REALM realm"
  "$(hr_release_dir "$LIVE")/ops/production/http-checks.sh" || prod_die "HTTP checks failed on the drill"
  "$(hr_release_dir "$LIVE")/ops/production/backchannel-check.sh" "$LIVE" || prod_die "back-channel check failed on the drill"
  printf 'restore_seconds=%s\nrecovery_seconds=%s\n' "$restore_s" "$total_s" > "$LIVE_DATA/state/.drill-times"
) && result=PASS
[ "$result" = PASS ] || ( drill_env; hr_diagnose "$LIVE" )

kill "$WATCH_PID" 2>/dev/null; wait "$WATCH_PID" 2>/dev/null
live_failures=$(grep -c ' FAIL' "$WATCH_LOG" || true)
live_samples=$(grep -c . "$WATCH_LOG" || true)
grep ' FAIL' "$WATCH_LOG" | head -5
[ "$live_failures" = 0 ] && [ "$live_samples" -gt 0 ] || result=FAIL
{
  printf 'result=%s\nsource=%s\nbackup=%s\nbackup_release=%s\nrelease=%s\nfinished=%s\ntotal_seconds=%s\n' "$result" "$SOURCE" \
    "$backup_created" "$backup_release" "$LIVE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(( $(date +%s) - started ))"
  cat "$LIVE_DATA/state/.drill-times" 2>/dev/null
  printf 'live_samples=%s\nlive_failures=%s\nrpo_note=recovery point is the backup timestamp above\n' "$live_samples" "$live_failures"
} > "$REPORT"
rm -f "$LIVE_DATA/state/.drill-times"
sed 's/^/  /' "$REPORT"
[ "$result" = PASS ] && prod_log "restore drill PASSED" && exit 0
prod_log "restore drill FAILED"
exit 1
