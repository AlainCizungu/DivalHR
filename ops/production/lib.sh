# shellcheck shell=bash
# LAUNCH-002 (ADR 0048): shared helpers of the PRODUCTION scripts that run on the production host.
# Sourced, never executed. It reuses the proven hr-dev helpers (host lock, SHA checks, logical
# restore, migration lists) by pointing them at production paths, and replaces everything whose
# boundary differs: the compose file, the data directories, the realm, the secrets and TLS.
# Prints no secret: secrets are only ever read from files into the processes that need them.

set -u
PROD_OPS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# A rehearsal switch of the test environment must never weaken a production script.
[ -z "${HR_DEV_REHEARSAL:-}" ] || { printf 'STOP: HR_DEV_REHEARSAL is set; production scripts never run in rehearsal mode\n' >&2; exit 1; }

export PROD_DATA="${PROD_DATA:-/srv/divalhr-prod}"
export PROD_PROJECT="${PROD_PROJECT:-divalhr-prod}"
export PROD_APP_HOST="${PROD_APP_HOST:-app.divalhr.com}"
export PROD_PUBLIC_HOST="${PROD_PUBLIC_HOST:-divalhr.com}"
export PROD_SUBNET_PREFIX="${PROD_SUBNET_PREFIX:-10.72}"
export PROD_ADMIN_PORT="${PROD_ADMIN_PORT:-18180}"
PROD_KEEP_RELEASES="${PROD_KEEP_RELEASES:-3}"

# The hr-dev helpers read HR_DEV_*: map them onto production before sourcing, never the reverse.
export HR_DEV_DATA="$PROD_DATA"
export HR_DEV_PROJECT="$PROD_PROJECT"
export HR_DEV_HOST="$PROD_APP_HOST"
export HR_DEV_ORIGIN="https://$PROD_APP_HOST"
# shellcheck source=../hr-dev/lib.sh
. "$PROD_OPS/../hr-dev/lib.sh"

prod_log() { hr_log "$@"; }
prod_die() { hr_die "$@"; }

# Production never touches a development or test path, project, host or prefix.
case "$PROD_DATA$PROD_PROJECT$PROD_APP_HOST$PROD_PUBLIC_HOST" in
  *divalhr-test*|*hr-dev*|*hrdev*|*dival.ai*|*localhost*) prod_die "a test or development name leaked into the production settings" ;;
esac

prod_require_root() { hr_require_root; }
prod_require_encrypted_data_root() { hr_require_encrypted_data_root; }

# The live region and registry come from root-owned configuration on the data volume (never Git).
prod_config() { tr -d '\r\n' < "$PROD_DATA/config/$1" 2>/dev/null; }

# The e-mail provider's NON-SECRET settings (config/mail.env: PROD_MAIL_HOST, _PORT, _FROM, _USERNAME).
# Without them the environment cannot start Core (it fails closed) and the launch lock reports
# email NOT_CONFIGURED: the provider is an operator prerequisite (docs/OPS-PRODUCTION.md).
prod_load_mail() {
  local f="$PROD_DATA/config/mail.env"
  [ -s "$f" ] || prod_die "the approved e-mail provider is not configured ($f): customer onboarding stays refused, and Core cannot start without it"
  # shellcheck disable=SC1090
  set -a; . "$f"; set +a
  [ -n "${PROD_MAIL_HOST:-}" ] && [ -n "${PROD_MAIL_FROM:-}" ] || prod_die "mail.env must set PROD_MAIL_HOST and PROD_MAIL_FROM"
  [ -s "$PROD_DATA/secrets/core/DIVALHR_MAIL_PASSWORD" ] || [ -z "${PROD_MAIL_USERNAME:-}" ] \
    || prod_die "the provider's password file is missing (secrets/core/DIVALHR_MAIL_PASSWORD)"
}

# Exports what compose needs from root-owned configuration. Fails closed on anything missing.
prod_export_env() {
  PROD_REGISTRY="$(prod_config registry)" || true
  PROD_VPC_CIDR="$(prod_config vpc-cidr)" || true
  [ -n "${PROD_REGISTRY:-}" ] || prod_die "missing $PROD_DATA/config/registry (the private ECR registry host)"
  [ -n "${PROD_VPC_CIDR:-}" ] || prod_die "missing $PROD_DATA/config/vpc-cidr (the load balancer's VPC range)"
  case "$PROD_REGISTRY" in *.dkr.ecr.*.amazonaws.com) ;; *) prod_die "registry is not a private ECR host" ;; esac
  export PROD_REGISTRY PROD_VPC_CIDR
  prod_load_mail
}

# Same function name as the hr-dev helper, so every reused helper (restore, diagnose, migrations)
# addresses the PRODUCTION project, from that release's own files, never a working checkout.
hr_compose() {
  local release="$1"; shift
  local dir
  dir="$(hr_release_dir "$release")"
  [ -f "$dir/infrastructure/production/compose.yaml" ] || hr_die "release $release is not unpacked"
  PROD_RELEASE="$release" docker compose \
    --project-name "$PROD_PROJECT" \
    -f "$dir/infrastructure/production/compose.yaml" "$@"
}

# Directories on the encrypted volume (owners are the container users) and the rendered realm.
# Secrets must already exist (init-secrets.sh).
hr_prepare_data() {
  local rel_dir="$1"
  install -d -m 0711 "$PROD_DATA"
  install -d -m 0700 -o 999 -g 999 "$PROD_DATA/postgres"
  install -d -m 0700 "$PROD_DATA/caddy/data" "$PROD_DATA/caddy/config"
  install -d -m 0700 "$PROD_DATA/state" "$PROD_DATA/backups" "$PROD_DATA/config" "$PROD_DATA/evidence"
  install -d -m 0500 -o 1000 -g 1000 "$PROD_DATA/keycloak/import"
  python3 "$rel_dir/ops/production/render-realm.py" \
    "$rel_dir/infrastructure/hr-dev/keycloak/realm-divalhr-test.json" \
    "$PROD_DATA/keycloak/import/realm-divalhr-production.json" \
    "https://$PROD_APP_HOST" "$PROD_DATA/secrets/ops/provisioner-secret" "$PROD_DATA/config/mail.env" >/dev/null \
    || hr_die "production realm render failed"
  chown 1000:1000 "$PROD_DATA/keycloak/import/realm-divalhr-production.json"
}

# --- the launch lock (L2-12) --------------------------------------------------------------------
# Closed by default and fail closed: anything other than the exact word OPEN reads as CLOSED, and a
# missing or unreadable file is CLOSED. The host copy mirrors the SSM parameter that only the
# founder-approved lift policy can change; LAUNCH-002 never lifts it.
prod_launch_lock() {
  local v
  v="$(cat "$PROD_DATA/state/launch-lock" 2>/dev/null | tr -d '[:space:]')" || v=""
  [ "$v" = "OPEN" ] && printf 'OPEN' || printf 'CLOSED'
}

# The synthetic acceptance of LAUNCH-002 runs only while the lock is CLOSED. Deployments record the
# lock state in their evidence but do not need it closed: later releases deploy after the founder lifts it.
prod_require_launch_lock_closed() {
  [ "$(prod_launch_lock)" = "CLOSED" ] || prod_die "the launch lock is OPEN: the LAUNCH-002 synthetic acceptance runs only while it is closed"
}

prod_current_release() { hr_current_release_strict; }

# The hr-dev Python checks name their realm in one constant. Production runs the SAME code pointed at
# the production realm; the substitution is exact and fails closed if the constant is not found.
PROD_REALM="divalhr-production"
prod_realm_script() {
  local out
  out="$(sed "s/^REALM = \"divalhr-test\"\$/REALM = \"$PROD_REALM\"/" "$PROD_OPS/../hr-dev/$1")" || return 1
  printf '%s\n' "$out" | grep -qx "REALM = \"$PROD_REALM\"" || { printf 'STOP: realm constant not found in %s\n' "$1" >&2; return 1; }
  printf '%s\n' "$out"
}
