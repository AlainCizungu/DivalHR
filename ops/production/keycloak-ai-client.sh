#!/usr/bin/env bash
# LAUNCH-002: creates/updates the Core -> AI service client in the production realm (idempotent).
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
divalhr_lock "keycloak-ai-client" || exit $?
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
prod_realm_script keycloak_ai_client.py > "$tmp" || exit 1
python3 "$tmp" "http://localhost:$PROD_ADMIN_PORT/identity" "$PROD_DATA/secrets"
