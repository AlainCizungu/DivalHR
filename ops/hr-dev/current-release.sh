#!/usr/bin/env bash
# DEVX-001B (A75-5): prints the full SHA of the release currently recorded as running on hr-dev,
# or nothing (first deployment, unreadable state). Read-only; used by verify-on-host.sh to
# classify a candidate against what is deployed. Prints nothing but a 40-character SHA.
#
#   sudo -n ops/hr-dev/current-release.sh
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
sha="$(hr_current_release | head -1)"
case "$sha" in
  *[!0-9a-f]* | '') exit 0 ;;
esac
[ "${#sha}" = 40 ] && printf '%s\n' "$sha"
exit 0
