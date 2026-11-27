#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-10): returns production to an earlier release AND its databases.
#
#   sudo ops/production/rollback.sh --to <previous sha> [--from <failed sha>]
#
# Called by remote-deploy.sh when a release fails its checks, or by the operator. Both databases are
# restored from the pre-deploy dumps taken just before the failed release (Keycloak migrates its own
# schema on start, which Core's history cannot show). Without such dumps it refuses and changes
# nothing; recovery from a nightly/off-host dump is the manual runbook (docs/OPS-PRODUCTION.md
# section 8). Data written after the pre-deploy backup is discarded: the rollback is meant for a
# release that failed its checks seconds after start, before the launch lock was lifted or while no
# customer wrote data; the runbook says what to do otherwise. Timed; result in state/last-rollback.
set -u
. "$(dirname "$0")/lib.sh"

FROM="" TO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --to) TO="$2"; shift 2 ;;
    *) prod_die "unknown argument $1" ;;
  esac
done
prod_require_root
prod_require_encrypted_data_root
FROM="${FROM:-$(hr_current_release)}"
hr_require_sha "$TO"
hr_require_sha "$FROM"
[ "$FROM" != "$TO" ] || prod_die "--from and --to are the same release"
divalhr_lock "rollback $FROM -> $TO" || exit $?
[ -d "$(hr_release_dir "$TO")" ] || prod_die "release $TO is not on this instance"
TO_DIR="$(hr_release_dir "$TO")"
prod_export_env
started=$(date +%s)

backup="$(hr_latest_backup pre-deploy "$FROM")"
[ -n "$backup" ] || prod_die "no pre-deploy backup for $FROM: its effect on the databases cannot be undone safely; no change was made (docs/OPS-PRODUCTION.md section 8)"
prod_log "rollback: restoring both pre-deploy dumps $(basename "$backup")"
hr_compose "$FROM" stop core-api keycloak ai-service web || prod_die "stopping the application failed"
t=$(date +%s)
hr_restore_backup "$FROM" "$backup"
restore_seconds=$(( $(date +%s) - t ))

prod_log "starting $TO"
if hr_compose "$TO" up -d --no-build --wait --wait-timeout 600 --remove-orphans \
  && "$TO_DIR/ops/production/http-checks.sh" \
  && "$TO_DIR/ops/production/backchannel-check.sh" "$TO"; then
  hr_set_current "$TO"
  printf '%s\n' "$FROM" > "$PROD_DATA/state/failed-release"
  printf 'from=%s\nto=%s\nrestore_seconds=%s\ntotal_seconds=%s\nfinished=%s\n' \
    "$FROM" "$TO" "$restore_seconds" "$(( $(date +%s) - started ))" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    > "$PROD_DATA/state/last-rollback"
  prod_log "rolled back to $TO (restore ${restore_seconds}s, total $(( $(date +%s) - started ))s)"
  exit 0
fi
hr_diagnose "$TO"
prod_die "rollback to $TO failed its checks; follow docs/OPS-PRODUCTION.md section 8 (manual recovery)"
