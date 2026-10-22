#!/usr/bin/env bash
# R157-1 (MVP-101): ensures the Core -> AI service Keycloak client of the test realm exists and
# matches the secret on the instance. Idempotent; called by remote-deploy.sh after every start,
# after keycloak-admin-setup.sh (it signs in as the permanent operator).
#
#   sudo ops/hr-dev/keycloak-ai-client.sh
#
# Talks only to the operator site on the instance's loopback. Prints step names only.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
divalhr_lock "keycloak-ai-client" || exit $?
python3 "$HR_DEV_OPS/keycloak_ai_client.py" "http://localhost:$HR_DEV_ADMIN_PORT/identity" \
  "$HR_DEV_DATA/secrets"
