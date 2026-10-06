#!/usr/bin/env bash
# OPS-001: installs or refreshes the test environment's systemd timers from the release being
# deployed (watchdog every minute, nightly backup). Called by remote-deploy.sh as the last check
# before the release is recorded as current (R66-4). All or nothing: on any failure the previous
# unit files and enablement are restored, and the script exits non-zero.
# Units run scripts through $HR_DEV_DATA/current, the running release.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
UNIT_DIR="${HR_DEV_UNIT_DIR:-/etc/systemd/system}"
SYSTEMCTL="${HR_DEV_SYSTEMCTL:-systemctl}"
TIMERS="divalhr-hrdev-watchdog.timer divalhr-hrdev-backup.timer"
backup="$(mktemp -d)"
trap 'rm -rf "$backup"' EXIT

# Remember the current state: unit files and which timers were enabled.
for unit in "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.service "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.timer; do
  name=$(basename "$unit")
  [ -f "$UNIT_DIR/$name" ] && cp -p "$UNIT_DIR/$name" "$backup/$name"
done
was_enabled=""
for t in $TIMERS; do
  "$SYSTEMCTL" is-enabled --quiet "$t" 2>/dev/null && was_enabled="$was_enabled $t"
done

restore() {
  hr_log "timer installation failed; restoring the previous unit state"
  for unit in "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.service "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.timer; do
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
for unit in "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.service "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.timer; do
  name=$(basename "$unit")
  if ! cmp -s "$unit" "$UNIT_DIR/$name"; then
    install -m 0644 "$unit" "$UNIT_DIR/$name" || restore
    changed=1
    hr_log "installed $name"
  fi
done
if [ "$changed" = 1 ]; then "$SYSTEMCTL" daemon-reload || restore; fi
# shellcheck disable=SC2086
"$SYSTEMCTL" enable --now $TIMERS >/dev/null 2>&1 || restore
for t in $TIMERS; do
  [ "$("$SYSTEMCTL" is-active "$t" 2>/dev/null)" = active ] || restore
done
hr_log "timers active: $TIMERS"
