#!/usr/bin/env bash
# LAUNCH-002: Core -> AI service capability, end to end, on a synthetic all-zero snapshot (no person).
#   sudo ops/production/ai-capability-check.sh <release sha>
set -u
. "$(dirname "$0")/lib.sh"
RELEASE="${1:?release sha}"
hr_compose "$RELEASE" exec -T ai-service python -c "$(prod_realm_script ai_capability_check.py)" \
  http://idp-internal:8081 "https://$PROD_APP_HOST" < "$PROD_DATA/secrets/ops/ai-service-client-secret"
