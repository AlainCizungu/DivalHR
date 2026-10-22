#!/usr/bin/env bash
# R157-1 (MVP-101): runs ai_capability_check.py inside the AI service container, which sits on the
# same private app network as the Core API and holds no secret of its own. The client secret is
# passed on stdin only.
#
#   sudo ops/hr-dev/ai-capability-check.sh <release sha>
set -u
. "$(dirname "$0")/lib.sh"
RELEASE="${1:?release sha}"
hr_compose "$RELEASE" exec -T ai-service python -c "$(cat "$HR_DEV_OPS/ai_capability_check.py")" \
  http://idp-internal:8081 "$HR_DEV_ORIGIN" < "$HR_DEV_DATA/secrets/ops/ai-service-client-secret"
