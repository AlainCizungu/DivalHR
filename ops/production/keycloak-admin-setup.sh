#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-6): replaces Keycloak's one-time bootstrap administrator with the named
# permanent operator and removes the bootstrap password from keycloak.conf. Idempotent; run after
# every start. Same code as the test environment, pointed at the production operator site (loopback).
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
divalhr_lock "keycloak-admin-setup" || exit $?
python3 "$PROD_OPS/../hr-dev/keycloak_admin_setup.py" "http://localhost:$PROD_ADMIN_PORT/identity" "$PROD_DATA/secrets"
