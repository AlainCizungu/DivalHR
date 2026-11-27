#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-6): FINAL-mode verification of the production realm (privileged MFA, narrow
# provisioning, no broad provisioner rights, SSO re-entry rules). Read-only; prints rule names only.
#   sudo ops/production/realm-verify.sh <release sha>
# Needs Node.js on the host (an AMI prerequisite, docs/OPS-PRODUCTION.md section 2); the permanent
# operator's credentials are read from the root-only file into that one process only.
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
RELEASE="${1:?release sha}"
command -v node >/dev/null || prod_die "Node.js is not installed on the host (prerequisite of the final realm verification)"
env_file="$PROD_DATA/secrets/ops/kc-admin.env"
[ -r "$env_file" ] || prod_die "no permanent Keycloak operator yet (run keycloak-admin-setup.sh)"
user=$(sed -n 's/^KC_ADMIN_USER=//p' "$env_file"); pass=$(sed -n 's/^KC_ADMIN_PASSWORD=//p' "$env_file")
KEYCLOAK_VERIFY_TRANSPORT=http KEYCLOAK_REALM="$PROD_REALM" \
  KEYCLOAK_URL="http://localhost:$PROD_ADMIN_PORT/identity" \
  KEYCLOAK_ADMIN_USER="$user" KEYCLOAK_ADMIN_PASSWORD="$pass" \
  node "$(hr_release_dir "$RELEASE")/infrastructure/docker/keycloak/verify-realm.mjs" --mode=final
