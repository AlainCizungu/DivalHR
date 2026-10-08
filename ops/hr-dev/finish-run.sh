#!/usr/bin/env bash
# DEVX-001B (A75B-4, R79-1): finishes one aws-verify run ON THE INSTANCE, as the verification user,
# after verify-on-host.sh returned and its logs were copied into the run directory:
#
#   ops/hr-dev/finish-run.sh <run directory> <stage> <verification exit status>
#
# Writes the checksummed, read-only record (ops/hr-dev/evidence.py record) for every finished run,
# failed or not. If the record cannot be written, the run FAILS: `FAIL evidence-record` is added
# to the stage summary and the log, and the exit file holds a nonzero status, so aws-verify can
# never report success without evidence. The exit file is written last; it holds the final status.
set -u
D="${1:?run directory}" STAGE="${2:?stage}" RC="${3:?exit status}"
case "$RC" in *[!0-9]* | '') RC=1 ;; esac
case "$STAGE" in *[!a-z]* | '') STAGE=unknown ;; esac
FINAL="$RC"
if ! python3 "$(dirname "$0")/evidence.py" record --run-dir "$D" --exit "$RC"; then
  mkdir -p "$D/logs"
  echo "FAIL evidence-record" | tee -a "$D/logs/$STAGE.summary"
  [ -f "$D/logs/$STAGE.log" ] && echo "FAIL evidence-record (the verification record could not be written)" >> "$D/logs/$STAGE.log"
  [ "$FINAL" = 0 ] && FINAL=1
fi
echo "$FINAL" > "$D/exit"
exit "$FINAL"
