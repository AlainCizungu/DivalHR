#!/usr/bin/env bash
# OPS-001 (A65-2): after Keycloak's first start, replaces the bootstrap administrator with the
# permanent operator, points the master realm at the private admin URL and removes the bootstrap
# password from keycloak.conf. Idempotent; called by remote-deploy.sh after every start.
#
#   sudo ops/hr-dev/keycloak-admin-setup.sh
#
# Talks only to the operator site on the instance's loopback. Prints step names only.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
divalhr_lock "keycloak-admin-setup" || exit $?
python3 "$HR_DEV_OPS/keycloak_admin_setup.py" "http://localhost:$HR_DEV_ADMIN_PORT/identity" \
  "$HR_DEV_DATA/secrets"
