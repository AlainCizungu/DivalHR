# shellcheck shell=bash
# OPS-001 (A65-5): one host-wide lock for every operation that changes containers, files or
# databases on the DivalHR AWS instance: verification runs (aws-verify / verify-on-host) and every
# test-environment deploy, rollback, backup, restore drill and watchdog restart.
#
#   Path:   /run/lock/divalhr-host.lock (tmpfs, recreated at boot by systemd-tmpfiles from
#           ops/host/tmpfiles-divalhr.conf: mode 0660, owner root, group divalhr-ops).
#   Usage:  . ops/host/host-lock.sh; divalhr_lock "deploy <sha>" || exit $?
#
# The lock is taken with flock -n before anything is changed. A second operation exits with
# status 75 (EX_TEMPFAIL) and a message naming the holder; it never waits and never changes
# anything. Child processes inherit the open descriptor, and DIVALHR_HOST_LOCK_HELD=1 tells
# nested scripts (for example backup.sh called by remote-deploy.sh) that the lock is already held.
# Compatible with bash 3.2 (macOS) and Linux bash.

DIVALHR_HOST_LOCK="${DIVALHR_HOST_LOCK:-/run/lock/divalhr-host.lock}"
DIVALHR_LOCK_FD=9

# divalhr_lock <operation description> [--optional]
#   --optional: when the lock file has not been provisioned (a workstation or CI), continue
#   without it; used only by verify-on-host. Every test-environment operation fails closed.
divalhr_lock() {
  local operation="$1" optional="${2:-}"
  if [ "${DIVALHR_HOST_LOCK_HELD:-}" = "1" ]; then
    return 0
  fi
  if [ ! -e "$DIVALHR_HOST_LOCK" ]; then
    if [ "$optional" = "--optional" ]; then
      echo "divalhr-lock: $DIVALHR_HOST_LOCK not provisioned on this machine; continuing without it" >&2
      return 0
    fi
    echo "divalhr-lock: $DIVALHR_HOST_LOCK is missing (run ops/aws/aws-dev-setup.sh install)" >&2
    return 78
  fi
  if ! command -v flock >/dev/null 2>&1; then
    echo "divalhr-lock: flock is not installed" >&2
    return 78
  fi
  # shellcheck disable=SC2093
  eval "exec ${DIVALHR_LOCK_FD}<>\"\$DIVALHR_HOST_LOCK\"" || {
    echo "divalhr-lock: cannot open $DIVALHR_HOST_LOCK" >&2
    return 77
  }
  if ! flock -n "$DIVALHR_LOCK_FD"; then
    local holder
    holder=$(head -c 200 "$DIVALHR_HOST_LOCK" 2>/dev/null | tr -cd '[:print:] ')
    echo "divalhr-lock: another DivalHR operation holds the host lock (${holder:-unknown}); nothing was changed" >&2
    eval "exec ${DIVALHR_LOCK_FD}>&-"
    return 75
  fi
  # Record the holder for the next caller's message. No secrets: operation, user, pid, time.
  printf '%s by %s pid %s since %s\n' "$operation" "$(id -un)" "$$" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    > "$DIVALHR_HOST_LOCK" 2>/dev/null || true
  export DIVALHR_HOST_LOCK_HELD=1
  return 0
}

# divalhr_lock_busy: 0 when another process holds the lock right now, 1 otherwise (including when
# the lock file is missing). Holds the lock for no longer than the probe itself.
divalhr_lock_busy() {
  [ "${DIVALHR_HOST_LOCK_HELD:-}" = "1" ] && return 1
  [ -e "$DIVALHR_HOST_LOCK" ] || return 1
  if ( flock -n 8 ) 8<>"$DIVALHR_HOST_LOCK" 2>/dev/null; then
    return 1
  fi
  return 0
}
