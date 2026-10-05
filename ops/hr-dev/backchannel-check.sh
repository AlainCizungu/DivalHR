#!/usr/bin/env bash
# OPS-001 (A65-2): runs backchannel_check.py inside the AI service container, which sits on the
# same private app network as the Core API and holds no secret of its own. The provisioner
# secret is passed on stdin only.
#
#   sudo ops/hr-dev/backchannel-check.sh <release sha>
set -u
. "$(dirname "$0")/lib.sh"
RELEASE="${1:?release sha}"
hr_compose "$RELEASE" exec -T ai-service python -c "$(cat "$HR_DEV_OPS/backchannel_check.py")" \
  http://idp-internal:8081 "$HR_DEV_ORIGIN" < "$HR_DEV_DATA/secrets/ops/provisioner-secret"
