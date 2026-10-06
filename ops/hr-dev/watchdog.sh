#!/usr/bin/env bash
# OPS-001 (A65-3): the single health-recovery manager of the test environment.
#
#   sudo ops/hr-dev/watchdog.sh          (every 60 s from divalhr-hrdev-watchdog.timer)
#
# Division of work, so that nothing competes:
#   * Docker's restart policy (unless-stopped) acts only when a container EXITS; the watchdog never
#     touches a container that is exited, restarting or being created.
#   * The watchdog acts only on a container that is RUNNING but reported UNHEALTHY by its own
#     health check, which Docker never restarts by itself.
# Bounds: a restart only after WATCHDOG_THRESHOLD (3) consecutive unhealthy observations, at
# least WATCHDOG_COOLDOWN (600 s) after the previous restart of that service, and at most
# WATCHDOG_MAX_PER_HOUR (3) restarts per service per hour; beyond that it logs "operator
# attention required" and leaves the container alone. While another operation holds the host
# lock (a deploy, backup, rollback or drill) it observes nothing and changes nothing.
# Output goes to the journal: service names and states only.
set -u
. "$(dirname "$0")/lib.sh"

DOCKER="${DOCKER:-docker}"
THRESHOLD="${WATCHDOG_THRESHOLD:-3}"
COOLDOWN="${WATCHDOG_COOLDOWN:-600}"
MAX_PER_HOUR="${WATCHDOG_MAX_PER_HOUR:-3}"
STATE="${WATCHDOG_STATE:-$HR_DEV_DATA/state/watchdog}"
NOW="${WATCHDOG_NOW:-$(date +%s)}"
if divalhr_lock_busy; then
  echo "watchdog: another operation holds the host lock; skipped"
  exit 0
fi
mkdir -p "$STATE"

containers=$("$DOCKER" ps -a --filter "label=com.docker.compose.project=$HR_DEV_PROJECT" \
  --format '{{.ID}} {{.Label "com.docker.compose.service"}}') || { echo "watchdog: docker unavailable"; exit 1; }

printf '%s\n' "$containers" | while read -r id service; do
  [ -n "$id" ] || continue
  state=$("$DOCKER" inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null) || continue
  status="${state%% *}" health="${state#* }"
  count_file="$STATE/$service.count" restarts_file="$STATE/$service.restarts"
  if [ "$status" != running ] || [ "$health" != unhealthy ]; then
    # Exited/restarting containers belong to Docker's restart policy; healthy ones reset the count.
    rm -f "$count_file"
    continue
  fi
  count=$(( $(cat "$count_file" 2>/dev/null || echo 0) + 1 ))
  echo "$count" > "$count_file"
  echo "watchdog: $service unhealthy ($count/$THRESHOLD)"
  [ "$count" -ge "$THRESHOLD" ] || continue

  recent=$(awk -v now="$NOW" '$1 > now - 3600' "$restarts_file" 2>/dev/null | grep -c . || true)
  last=$(tail -1 "$restarts_file" 2>/dev/null || echo 0)
  if [ "$recent" -ge "$MAX_PER_HOUR" ]; then
    echo "watchdog: $service restart budget exhausted ($recent in the last hour); operator attention required"
    continue
  fi
  if [ $(( NOW - ${last:-0} )) -lt "$COOLDOWN" ]; then
    echo "watchdog: $service in cooldown after the restart at $(date -u -d "@$last" +%H:%M:%SZ 2>/dev/null || echo "$last")"
    continue
  fi
  # The lock is held only for the restart itself; a busy lock means an operation just started.
  (
    divalhr_lock "watchdog restart $service" || exit 75
    "$DOCKER" restart --time 30 "$id" >/dev/null
  ) 2>&1 || { echo "watchdog: $service not restarted (host lock busy or restart failed)"; continue; }
  echo "$NOW" >> "$restarts_file"
  rm -f "$count_file"
  echo "watchdog: restarted $service (restart $((recent + 1)) of $MAX_PER_HOUR this hour)"
done
