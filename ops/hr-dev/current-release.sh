#!/usr/bin/env bash
# DEVX-001B (A75-5): prints the full SHA of the release currently recorded as running on hr-dev,
# or nothing (first deployment, unreadable state). Read-only; used by verify-on-host.sh to
# classify a candidate against what is deployed. Prints nothing but a 40-character SHA.
#
#   sudo -n ops/hr-dev/current-release.sh
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
# R79-4: the whole value must be exactly one SHA (hr_read_release_file); anything else prints
# nothing and the classification widens to `complete`.
hr_current_release_strict
exit 0
