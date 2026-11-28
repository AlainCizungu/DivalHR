#!/usr/bin/env bash
# LAUNCH-002: Core's private back-channel to Keycloak, checked from inside the private app network
# (the AI service container; the secret arrives on stdin only). Same code as the test environment,
# pointed at the production realm and origin.
#   sudo ops/production/backchannel-check.sh <release sha>
set -u
. "$(dirname "$0")/lib.sh"
RELEASE="${1:?release sha}"
hr_compose "$RELEASE" exec -T ai-service python -c "$(prod_realm_script backchannel_check.py)" \
  http://idp-internal:8081 "https://$PROD_APP_HOST" < "$PROD_DATA/secrets/ops/provisioner-secret"
