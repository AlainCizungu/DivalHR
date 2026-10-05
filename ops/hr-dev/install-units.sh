#!/usr/bin/env bash
# OPS-001: installs or refreshes the test environment's systemd timers from the running release
# (watchdog every minute, nightly backup). Idempotent; called by remote-deploy.sh after a
# successful live deployment. Units run scripts through $HR_DEV_DATA/current, the running release.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
changed=0
for unit in "$HR_DEV_OPS"/systemd/divalhr-hrdev-*.{service,timer}; do
  name=$(basename "$unit")
  if ! cmp -s "$unit" "/etc/systemd/system/$name"; then
    install -m 0644 "$unit" "/etc/systemd/system/$name"
    changed=1
    hr_log "installed $name"
  fi
done
[ "$changed" = 1 ] && systemctl daemon-reload
systemctl enable --now divalhr-hrdev-watchdog.timer divalhr-hrdev-backup.timer >/dev/null 2>&1 \
  || hr_die "enabling the timers failed"
hr_log "timers active: $(systemctl is-active divalhr-hrdev-watchdog.timer) / $(systemctl is-active divalhr-hrdev-backup.timer)"
