#!/usr/bin/env bash
# OPS-001 (section 10, A65-6): returns the test environment to an earlier release.
#
#   sudo ops/hr-dev/rollback.sh --to <previous sha> [--from <failed sha>]
#
# Called by remote-deploy.sh when a new release fails its checks, or by the owner.
#
# Both databases are always returned to the state taken just before the failed release started
# (R66-2): Core's schema is versioned by Flyway, but Keycloak migrates its own database when a
# newer Keycloak image starts, and that change is not visible in Core's history. So the
# application containers are stopped, BOTH pre-deploy dumps of the failed release (divalhr and
# keycloak) are restored into empty databases (hr_restore_backup), and only then are the target's
# images started. Without such dumps the rollback refuses to run and changes nothing; recovery
# from a nightly dump is the runbook's manual procedure. Data written by the failed release is
# discarded, which is the intended behaviour for the synthetic test environment.
# Every step is timed; the result is written to $HR_DEV_DATA/state/last-rollback.
set -u
. "$(dirname "$0")/lib.sh"

FROM="" TO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --to) TO="$2"; shift 2 ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
hr_require_root
hr_require_encrypted_data_root
FROM="${FROM:-$(hr_current_release)}"
hr_require_sha "$TO"
hr_require_sha "$FROM"
[ "$FROM" != "$TO" ] || hr_die "--from and --to are the same release"
divalhr_lock "rollback $FROM -> $TO" || exit $?
[ -d "$(hr_release_dir "$TO")" ] || hr_die "release $TO is not on this instance"
TO_DIR="$(hr_release_dir "$TO")"
hr_export_tls
started=$(date +%s)

backup="$(hr_latest_backup pre-deploy "$FROM")"
[ -n "$backup" ] || hr_die "no pre-deploy backup for $FROM: its effect on the Core and Keycloak databases cannot be undone safely; no change was made (docs/OPS-HR-DEV.md, manual recovery)"
applied=$(hr_applied_migrations "$FROM")
known=$(hr_release_migrations "$TO")
unknown=$(LC_ALL=C comm -23 <(printf '%s\n' "$applied") <(printf '%s\n' "$known") | grep -c . || true)
path="restore"
hr_log "rollback: restoring both pre-deploy dumps $(basename "$backup") ($unknown Core migration(s) unknown to $TO; Keycloak schema restored regardless)"
hr_compose "$FROM" stop core-api keycloak ai-service web || hr_die "stopping the application failed"
t=$(date +%s)
hr_restore_backup "$FROM" "$backup"
restore_seconds=$(( $(date +%s) - t ))

hr_log "starting $TO"
if hr_compose "$TO" up -d --no-build --wait --wait-timeout 600 --remove-orphans \
  && "$TO_DIR/ops/hr-dev/http-checks.sh" \
  && "$TO_DIR/ops/hr-dev/backchannel-check.sh" "$TO"; then
  hr_set_current "$TO"
  printf '%s\n' "$FROM" > "$HR_DEV_DATA/state/failed-release"
  printf 'from=%s\nto=%s\npath=%s\nrestore_seconds=%s\ntotal_seconds=%s\nfinished=%s\n' \
    "$FROM" "$TO" "$path" "$restore_seconds" "$(( $(date +%s) - started ))" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    > "$HR_DEV_DATA/state/last-rollback"
  hr_log "rolled back to $TO ($path, restore ${restore_seconds}s, total $(( $(date +%s) - started ))s)"
  exit 0
fi
hr_diagnose "$TO"
hr_die "rollback to $TO failed its checks; follow docs/OPS-HR-DEV.md (manual recovery)"
