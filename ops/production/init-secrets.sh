#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-5): materialises the production secrets ON THE HOST, from Secrets Manager.
#
#   sudo ops/production/init-secrets.sh [--first-run]
#
# Reads every entry of secrets.manifest through the instance role (KMS key of this environment) and
# writes it ONLY as a root-controlled file under $PROD_DATA/secrets on the encrypted data volume,
# 0400, owned by the container user that reads it. No value is ever printed, put in an environment
# variable, a command line, Git, CI output or evidence. A missing secret, a value that looks like a
# development or test value, or a file that differs from Secrets Manager stops it (fail closed).
# Existing files that equal the stored value are kept; a rotation is a deliberate runbook step
# (docs/OPS-PRODUCTION.md section 6) that re-runs this with --refresh for the rotated names.
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
prod_require_encrypted_data_root
divalhr_lock "init-secrets" || exit $?

REFRESH=0
for arg in "$@"; do
  case "$arg" in --first-run) ;; --refresh) REFRESH=1 ;; *) prod_die "unknown argument $arg" ;; esac
done
REGION="$(prod_config region)" || true
[ -n "$REGION" ] || prod_die "missing $PROD_DATA/config/region"
S="$PROD_DATA/secrets"
umask 077
mkdir -p "$S/postgres" "$S/core" "$S/keycloak" "$S/ops"
chmod 0711 "$S"
FETCH="${PROD_SECRET_FETCH:-aws}"

missing=0
while IFS='|' read -r name rel owner kind; do
  case "$name" in ''|'#'*) continue ;; esac
  file="$S/$rel"
  tmp="$file.tmp"
  if ! "$FETCH" secretsmanager get-secret-value --region "$REGION" --secret-id "divalhr/production/$name" \
      --query SecretString --output text > "$tmp" 2>/dev/null || [ ! -s "$tmp" ]; then
    rm -f "$tmp"
    if [ "$kind" = operator ]; then
      prod_log "operator-supplied secret not stored yet: $name"
    else
      prod_log "missing in Secrets Manager: $name"; missing=$((missing + 1))
    fi
    continue
  fi
  if grep -qiE 'dev-only|changeme' "$tmp"; then rm -f "$tmp"; prod_die "a development value was found for $name"; fi
  if [ -s "$file" ] && [ "$REFRESH" = 0 ]; then
    cmp -s "$tmp" "$file" || { rm -f "$tmp"; prod_die "$rel differs from Secrets Manager; a rotation needs --refresh and the runbook"; }
    rm -f "$tmp"; chown "$owner" "$file"; chmod 0400 "$file"; continue
  fi
  mv "$tmp" "$file"
  chown "$owner" "$file"; chmod 0400 "$file"
  prod_log "materialised $rel"
done < "$(dirname "$0")/secrets.manifest"
[ "$missing" = 0 ] || prod_die "$missing required secret(s) are missing; run ops/production/secrets-bootstrap.sh first"

# Keycloak: keycloak.conf with the database password and the one-time bootstrap administrator password.
conf="$S/keycloak/keycloak.conf"
if [ ! -s "$conf" ] || [ "$REFRESH" = 1 ]; then
  {
    printf 'db-password=%s\n' "$(cat "$S/keycloak/.db-password")"
    printf 'bootstrap-admin-username=bootstrap-admin\n'
    printf 'bootstrap-admin-password=%s\n' "$(cat "$S/keycloak/.bootstrap-admin-password")"
  } > "$conf.tmp" && mv "$conf.tmp" "$conf"
  prod_log "wrote keycloak/keycloak.conf"
fi
chown 1000 "$conf"; chmod 0400 "$conf"
chown 999 "$S/postgres"; chown 10001 "$S/core"; chown 1000 "$S/keycloak"; chown -R root:root "$S/ops"
chmod 0500 "$S/postgres" "$S/core" "$S/keycloak"; chmod 0700 "$S/ops"
# The hidden helper files are root-only: only keycloak.conf is mounted into a container.
chown 0 "$S/keycloak/.db-password" "$S/keycloak/.bootstrap-admin-password"
echo "Secrets ready in $S (values not shown)."
