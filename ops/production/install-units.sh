#!/usr/bin/env bash
# LAUNCH-002: installs or refreshes the production systemd timers (watchdog every minute, nightly
# backup, metrics every five minutes) from the release being deployed. All or nothing: on any
# failure the previous unit files and enablement are restored and the script exits non-zero.
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
UNIT_DIR="${PROD_UNIT_DIR:-/etc/systemd/system}"
SYSTEMCTL="${PROD_SYSTEMCTL:-systemctl}"
TIMERS="divalhr-prod-watchdog.timer divalhr-prod-backup.timer divalhr-prod-metrics.timer"
backup="$(mktemp -d)"
trap 'rm -rf "$backup"' EXIT
units=("$PROD_OPS"/systemd/divalhr-prod-*.service "$PROD_OPS"/systemd/divalhr-prod-*.timer)
for unit in "${units[@]}"; do
  name=$(basename "$unit"); [ -f "$UNIT_DIR/$name" ] && cp -p "$UNIT_DIR/$name" "$backup/$name"
done
was_enabled=""
for t in $TIMERS; do "$SYSTEMCTL" is-enabled --quiet "$t" 2>/dev/null && was_enabled="$was_enabled $t"; done
restore() {
  prod_log "timer installation failed; restoring the previous unit state"
  for unit in "${units[@]}"; do
    name=$(basename "$unit")
    if [ -f "$backup/$name" ]; then cp -p "$backup/$name" "$UNIT_DIR/$name"; else rm -f "$UNIT_DIR/$name"; fi
  done
  "$SYSTEMCTL" daemon-reload 2>/dev/null
  for t in $TIMERS; do
    case " $was_enabled " in
      *" $t "*) "$SYSTEMCTL" enable --now "$t" >/dev/null 2>&1 ;;
      *) "$SYSTEMCTL" disable --now "$t" >/dev/null 2>&1 ;;
    esac
  done
  exit 1
}
changed=0
for unit in "${units[@]}"; do
  name=$(basename "$unit")
  if ! cmp -s "$unit" "$UNIT_DIR/$name"; then install -m 0644 "$unit" "$UNIT_DIR/$name" || restore; changed=1; prod_log "installed $name"; fi
done
if [ "$changed" = 1 ]; then "$SYSTEMCTL" daemon-reload || restore; fi
# shellcheck disable=SC2086
"$SYSTEMCTL" enable --now $TIMERS >/dev/null 2>&1 || restore
for t in $TIMERS; do [ "$("$SYSTEMCTL" is-active "$t" 2>/dev/null)" = active ] || restore; done
prod_log "timers active: $TIMERS"
