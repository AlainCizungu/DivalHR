#!/usr/bin/env bash
# OPS-001 (section 10, A65-6): returns the test environment to an earlier release.
#
#   sudo ops/hr-dev/rollback.sh --to <previous sha> [--from <failed sha>]
#
# Called by remote-deploy.sh when a new release fails its checks, or by the owner. Two paths:
#   * no migration: the databases hold no Flyway version unknown to the target release, so the
#     target's images are started against the current data;
#   * migration: the failed release applied migrations the target does not ship. The application
#     containers are stopped, the pre-deploy dumps taken for the failed release are restored into
#     empty databases (hr_restore_backup), and only then are the target's images started.
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

applied=$(hr_applied_migrations "$FROM")
known=$(hr_release_migrations "$TO")
[ -n "$applied" ] || hr_die "cannot read the applied migrations; no change was made"
unknown=$(LC_ALL=C comm -23 <(printf '%s\n' "$applied") <(printf '%s\n' "$known") | grep -c . || true)

path="no-migration"
restore_seconds=0
if [ "$unknown" -gt 0 ]; then
  path="migration"
  backup="$(hr_latest_backup pre-deploy "$FROM")"
  [ -n "$backup" ] || hr_die "the database holds $unknown migration(s) unknown to $TO and no pre-deploy backup for $FROM exists; no change was made"
  hr_log "migration rollback: $unknown migration(s) unknown to $TO; restoring $(basename "$backup")"
  hr_compose "$FROM" stop core-api keycloak ai-service web || hr_die "stopping the application failed"
  t=$(date +%s)
  hr_restore_backup "$FROM" "$backup"
  restore_seconds=$(( $(date +%s) - t ))
  [ "$(hr_applied_migrations "$FROM")" = "$(LC_ALL=C comm -12 <(printf '%s\n' "$applied") <(printf '%s\n' "$known"))" ] \
    || hr_log "note: restored history differs from the target's known set (checked by Flyway at start)"
else
  hr_log "no-migration rollback: every applied migration is known to $TO"
fi

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
