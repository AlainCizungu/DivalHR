#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048): the single health-recovery manager of the production host. Same bounded
# behaviour as the test environment's watchdog (3 consecutive unhealthy observations, 10-minute
# cooldown, at most 3 restarts per service per hour, then "operator attention required", nothing
# while another operation holds the host lock), pointed at the production project and state.
#   sudo ops/production/watchdog.sh     (every 60 s from divalhr-prod-watchdog.timer)
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
export WATCHDOG_STATE="${WATCHDOG_STATE:-$PROD_DATA/state/watchdog}"
exec "$PROD_OPS/../hr-dev/watchdog.sh"
