#!/usr/bin/env bash
# OPS-001 (section 6): creates the test environment's secrets on the instance, once.
#
#   sudo ops/hr-dev/init-secrets.sh
#
# Every value is generated here with openssl, written only to files under
# $HR_DEV_DATA/secrets (on the encrypted data volume, A65-1), readable only by the one container
# user that needs it, and never printed. Existing files are never overwritten. Nothing comes from
# .env.example or the development realm. The script prints file names and permissions only.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
hr_require_encrypted_data_root
divalhr_lock "init-secrets" || exit $?

S="$HR_DEV_DATA/secrets"
umask 077
mkdir -p "$S/postgres" "$S/core" "$S/keycloak" "$S/ops"
chmod 0711 "$S"

random() { openssl rand -base64 64 | tr -d '/+=\n' | cut -c1-48; }

# put <file> <owner uid> <value-command...>: create once, 0400, owned by the container user.
put() {
  local file="$1" owner="$2"; shift 2
  if [ -s "$file" ]; then
    echo "kept     $file"
  else
    "$@" > "$file.tmp" && mv "$file.tmp" "$file"
    echo "created  $file"
  fi
  chown "$owner" "$file"
  chmod 0400 "$file"
}
same_as() { cat "$1"; }

# PostgreSQL (container uid 999): superuser and the two application roles.
put "$S/postgres/POSTGRES_PASSWORD" 999 random
put "$S/postgres/DIVALHR_DB_PASSWORD" 999 random
put "$S/postgres/KEYCLOAK_DB_PASSWORD" 999 random

# Core API (container uid 10001), read through Spring's config tree; file name = property name.
put "$S/core/DIVALHR_DB_PASSWORD" 10001 same_as "$S/postgres/DIVALHR_DB_PASSWORD"
put "$S/core/DIVALHR_CURSOR_SIGNING_KEY" 10001 random
# Do not rotate in place: stored invitation lookups are keyed with it (EmailLookup).
put "$S/core/DIVALHR_EMAIL_LOOKUP_KEY" 10001 random
put "$S/ops/provisioner-secret" 0 random
put "$S/core/DIVALHR_KEYCLOAK_PROVISIONER_SECRET" 10001 same_as "$S/ops/provisioner-secret"

# Keycloak (container uid 1000): keycloak.conf with the database password and the one-time
# bootstrap administrator password (removed by the runbook once the permanent admin exists).
keycloak_conf() {
  printf 'db-password=%s\n' "$(cat "$S/postgres/KEYCLOAK_DB_PASSWORD")"
  # Username and password together: Keycloak refuses to start with a bootstrap username alone,
  # so keycloak-admin-setup.sh removes both lines once the permanent operator exists.
  printf 'bootstrap-admin-username=bootstrap-admin\n'
  printf 'bootstrap-admin-password=%s\n' "$(random)"
}
put "$S/keycloak/keycloak.conf" 1000 keycloak_conf

# Each directory belongs to the container user that reads it (Spring lists its config tree).
chown 999 "$S/postgres"; chown 10001 "$S/core"; chown 1000 "$S/keycloak"; chown -R root:root "$S/ops"
chmod 0500 "$S/postgres" "$S/core" "$S/keycloak"
chmod 0700 "$S/ops"

for f in "$S"/*/*; do
  if grep -qi 'dev-only' "$f"; then
    hr_die "a development value was found in $f"
  fi
done
echo "Secrets ready in $S (values not shown)."
