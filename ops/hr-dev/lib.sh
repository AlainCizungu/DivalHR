# shellcheck shell=bash
# OPS-001: shared helpers for the test-environment scripts that run on the AWS instance.
# Sourced, never executed. Prints no secret: secrets are only ever read from files into the
# processes that need them.

set -u
HR_DEV_OPS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../host/host-lock.sh
. "$HR_DEV_OPS/../host/host-lock.sh"

# Live defaults; rehearse.sh overrides every one of them.
export HR_DEV_DATA="${HR_DEV_DATA:-/srv/divalhr-test}"
export HR_DEV_PROJECT="${HR_DEV_PROJECT:-divalhr-test}"
export HR_DEV_IMAGE_PREFIX="${HR_DEV_IMAGE_PREFIX:-divalhr-test}"
export HR_DEV_HOST="${HR_DEV_HOST:-hr-dev.dival.ai}"
export HR_DEV_ORIGIN="${HR_DEV_ORIGIN:-https://$HR_DEV_HOST}"
export HR_DEV_SUBNET_PREFIX="${HR_DEV_SUBNET_PREFIX:-10.71}"
export HR_DEV_ADMIN_PORT="${HR_DEV_ADMIN_PORT:-18180}"
export HR_DEV_HTTP_PUBLISH="${HR_DEV_HTTP_PUBLISH:-80}"
export HR_DEV_HTTPS_PUBLISH="${HR_DEV_HTTPS_PUBLISH:-443}"
# http-checks.sh reaches the project's own Caddy on this machine, by name, with full certificate
# and host-name validation (the published ports above, on the loopback).
export HR_DEV_CONNECT_HTTPS="${HR_DEV_CONNECT_HTTPS:-127.0.0.1:${HR_DEV_HTTPS_PUBLISH##*:}}"
export HR_DEV_CONNECT_HTTP="${HR_DEV_CONNECT_HTTP:-127.0.0.1:${HR_DEV_HTTP_PUBLISH##*:}}"
export HR_DEV_BARE_IP_URL="${HR_DEV_BARE_IP_URL:-http://127.0.0.1:${HR_DEV_HTTP_PUBLISH##*:}/}"
HR_DEV_KEEP_RELEASES="${HR_DEV_KEEP_RELEASES:-3}"

hr_log() { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
hr_die() { printf 'STOP: %s\n' "$*" >&2; exit "${2:-1}"; }

hr_require_root() {
  [ "$(id -u)" = 0 ] || hr_die "run with sudo (root owns $HR_DEV_DATA)"
}

# A65-1: the live data root must be its own mounted filesystem, and the owner must have recorded
# that this very EBS volume is encrypted (ops/aws/hr-dev-aws.sh evidence, then
# prepare-data-volume.sh --confirm-encrypted, which stores the volume's serial in the marker). Rehearsals set HR_DEV_REHEARSAL=1 and use a throw-away directory instead.
hr_require_encrypted_data_root() {
  [ "${HR_DEV_REHEARSAL:-}" = "1" ] && return 0
  mountpoint -q "$HR_DEV_DATA" || hr_die "$HR_DEV_DATA is not a mounted volume (A65-1)"
  [ "$(findmnt -n -o SOURCE "$HR_DEV_DATA")" != "$(findmnt -n -o SOURCE /)" ] \
    || hr_die "$HR_DEV_DATA is on the root volume (A65-1)"
  local marker="$HR_DEV_DATA/.volume-encryption-verified" serial
  [ -f "$marker" ] \
    || hr_die "encryption of the data volume has not been confirmed (prepare-data-volume.sh --confirm-encrypted, A65-1)"
  serial="$(hr_data_volume_serial)"
  [ -n "$serial" ] && grep -qx "volume=$serial" "$marker" \
    || hr_die "the mounted data volume is not the one whose encryption was confirmed (A65-1)"
}

# Serial of the disk mounted at HR_DEV_DATA (on EBS NVMe, the volume ID without its hyphen).
hr_data_volume_serial() {
  local source
  source="$(findmnt -n -o SOURCE "$HR_DEV_DATA")" || return 0
  lsblk -dno SERIAL "$source" 2>/dev/null | tr -d '[:space:]'
}

# The release a command runs: full 40-character SHA only (A65-7).
hr_require_sha() {
  case "$1" in
    *[!0-9a-f]* | '') hr_die "release must be a full lowercase commit SHA" ;;
  esac
  [ "${#1}" = 40 ] || hr_die "release must be a full 40-character commit SHA"
}

# Releases live under the running project's data root; a drill points HR_DEV_RELEASES at them.
hr_release_dir() { printf '%s/%s' "${HR_DEV_RELEASES:-$HR_DEV_DATA/releases}" "$1"; }
hr_current_release() { cat "$HR_DEV_DATA/state/current-release" 2>/dev/null || true; }
hr_previous_release() { cat "$HR_DEV_DATA/state/previous-release" 2>/dev/null || true; }
# DEVX-001B (R79-4): the recorded current release only when the whole file, trimmed of leading
# and trailing whitespace, is exactly one 40-character lowercase SHA; otherwise nothing, so the
# classification widens to `complete`. Used by the verification classifier and the evidence gate.
hr_read_release_file() {
  local raw trimmed
  [ -f "$1" ] || return 0
  raw="$(cat "$1" 2>/dev/null)" || return 0
  trimmed="${raw#"${raw%%[![:space:]]*}"}"
  trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
  case "$trimmed" in *[!0-9a-f]* | '') return 0 ;; esac
  [ "${#trimmed}" = 40 ] && printf '%s\n' "$trimmed"
  return 0
}
hr_current_release_strict() { hr_read_release_file "$HR_DEV_DATA/state/current-release"; }

# Records the running release: state/current-release and the $HR_DEV_DATA/current symlink the
# systemd units run through.
hr_set_current() {
  printf '%s\n' "$1" > "$HR_DEV_DATA/state/current-release"
  ln -sfn "releases/$1" "$HR_DEV_DATA/current"
}

# docker compose for one release, from that release's own files (never from a working checkout).
hr_compose() {
  local release="$1"; shift
  local dir
  dir="$(hr_release_dir "$release")"
  [ -f "$dir/infrastructure/hr-dev/compose.yaml" ] || hr_die "release $release is not unpacked"
  HR_DEV_RELEASE="$release" docker compose \
    --project-name "$HR_DEV_PROJECT" \
    -f "$dir/infrastructure/hr-dev/compose.yaml" "$@"
}

# Creates the project's directories under HR_DEV_DATA (owners are the container users) and
# renders the realm import from the release. Secrets must already exist (init-secrets.sh).
hr_prepare_data() {
  local rel_dir="$1"
  install -d -m 0711 "$HR_DEV_DATA"
  install -d -m 0700 -o 999 -g 999 "$HR_DEV_DATA/postgres"
  install -d -m 0700 "$HR_DEV_DATA/caddy/data" "$HR_DEV_DATA/caddy/config" "$HR_DEV_DATA/mailpit"
  install -d -m 0700 "$HR_DEV_DATA/state" "$HR_DEV_DATA/backups" "$HR_DEV_DATA/config"
  install -d -m 0500 -o 1000 -g 1000 "$HR_DEV_DATA/keycloak/import"
  python3 "$rel_dir/ops/hr-dev/render-realm.py" \
    "$rel_dir/infrastructure/hr-dev/keycloak/realm-divalhr-test.json" \
    "$HR_DEV_DATA/keycloak/import/realm-divalhr-test.json" \
    "$HR_DEV_ORIGIN" "$HR_DEV_DATA/secrets/ops/provisioner-secret" >/dev/null || hr_die "realm render failed"
  chown 1000:1000 "$HR_DEV_DATA/keycloak/import/realm-divalhr-test.json"
}

# Caddy's internal CA root of this project (rehearsals and drills use `tls internal`).
hr_internal_ca() { printf '%s/caddy/data/caddy/pki/authorities/local/root.crt' "$HR_DEV_DATA"; }

# After a failed start: state of every service and the recent log lines of those not healthy.
# Application logs carry no secrets or personal data by design; this is the same output an
# operator reads with docker compose logs.
hr_diagnose() {
  local release="$1" svc
  echo "--- diagnosis: services of $HR_DEV_PROJECT"
  hr_compose "$release" ps -a --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null | sed 's/^/  /'
  for svc in $(hr_compose "$release" ps -a --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null \
      | awk '$2 != "running" || ($3 != "" && $3 != "healthy") {print $1}'); do
    echo "--- last log lines of $svc"
    hr_compose "$release" logs --no-color --tail 40 "$svc" 2>&1 | cut -c1-300 | sed 's/^/  /'
  done
  # Keycloak's own view (sign-in and start-up errors, never credentials) whatever its health.
  echo "--- last log lines of keycloak"
  hr_compose "$release" logs --no-color --tail 40 keycloak 2>&1 | grep -viE 'secret=|password=|credential=' \
    | cut -c1-300 | sed 's/^/  /'
}

# TLS mode for Caddy: the ACME contact address (live) or "internal" / certificate files (drills).
hr_tls() {
  if [ -n "${HR_DEV_TLS:-}" ]; then
    printf '%s' "$HR_DEV_TLS"
  else
    tr -d '\r\n' < "$HR_DEV_DATA/config/acme-email" 2>/dev/null \
      || hr_die "missing $HR_DEV_DATA/config/acme-email (D13)"
  fi
}

# Exports HR_DEV_TLS for compose and, with Caddy's internal CA, the CA file for http-checks.sh.
hr_export_tls() {
  HR_DEV_TLS="$(hr_tls)" || exit 1
  export HR_DEV_TLS
  if [ "$HR_DEV_TLS" = internal ]; then
    export HR_DEV_CA_FILE="${HR_DEV_CA_FILE:-$(hr_internal_ca)}"
  fi
}

# The Flyway versions applied to the project's Core database, one per line, sorted for comm(1).
hr_applied_migrations() {
  local release="$1"
  hr_compose "$release" exec -T postgres psql -U postgres -d divalhr -At \
    -c "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL" 2>/dev/null \
    | LC_ALL=C sort -u
}

# The Flyway versions shipped in a release's sources (SQL and Java migrations), one per line,
# sorted for comm(1).
hr_release_migrations() {
  local dir
  dir="$(hr_release_dir "$1")/apps/core-api/src/main"
  find "$dir/resources/db/migration" "$dir/java/db/migration" -maxdepth 1 \
    \( -name 'V*__*.sql' -o -name 'V*__*.java' \) -exec basename {} \; 2>/dev/null \
    | sed -E 's/^V([0-9_]+)__.*/\1/; s/_/./g' | LC_ALL=C sort -u
}

# The newest backup directory of a reason (nightly | pre-deploy), optionally with a label suffix.
hr_latest_backup() {
  local reason="$1" label="${2:-}"
  ls -1d "$HR_DEV_DATA/backups/$reason"/*"${label:+-$label}"/ 2>/dev/null | LC_ALL=C sort | tail -1 | sed 's:/$::'
}

# hr_restore_backup <release> <backup dir>
# Restores both logical dumps of a backup into EMPTY databases of the release's PostgreSQL
# (A65-6): checksums first, then for each database DROP ... WITH (FORCE), CREATE with its
# least-privilege owner, the btree_gist extension created by the superuser (as on first start),
# and pg_restore as the owner role without the archive's extension entries. The application
# containers of that project must be stopped. Prints database names and timings only.
hr_restore_backup() {
  local release="$1" dir="$2" pair db owner started
  [ -f "$dir/SHA256SUMS" ] || hr_die "no SHA256SUMS in $dir"
  (cd "$dir" && sha256sum --quiet -c SHA256SUMS) || hr_die "backup checksums do not match: $dir"
  for pair in divalhr:divalhr_app keycloak:keycloak; do
    db="${pair%%:*}" owner="${pair#*:}" started=$(date +%s)
    hr_compose "$release" exec -T postgres sh -c 'umask 077; cat > /tmp/divalhr-restore.dump' \
      < "$dir/$db.dump" || hr_die "copying the $db dump failed"
    hr_compose "$release" exec -T postgres psql -v ON_ERROR_STOP=1 -q -U postgres -d postgres \
      -c "DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)" \
      -c "CREATE DATABASE \"$db\" OWNER \"$owner\"" \
      -c "REVOKE ALL ON DATABASE \"$db\" FROM PUBLIC" || hr_die "recreating $db failed"
    if [ "$db" = divalhr ]; then
      hr_compose "$release" exec -T postgres psql -v ON_ERROR_STOP=1 -q -U postgres -d divalhr \
        -c 'CREATE EXTENSION IF NOT EXISTS btree_gist' || hr_die "btree_gist failed"
    fi
    # shellcheck disable=SC2016
    hr_compose "$release" exec -T postgres sh -c '
      pg_restore -l /tmp/divalhr-restore.dump \
        | grep -v -E "^[0-9]+; [0-9]+ [0-9]+ (EXTENSION|COMMENT - EXTENSION) " > /tmp/divalhr-restore.list \
        && pg_restore -U postgres -d "$1" --role="$2" --no-owner --no-privileges --exit-on-error \
             -L /tmp/divalhr-restore.list /tmp/divalhr-restore.dump
      rc=$?; rm -f /tmp/divalhr-restore.dump /tmp/divalhr-restore.list; exit $rc' sh "$db" "$owner" \
      || hr_die "pg_restore of $db failed"
    hr_log "restored $db in $(( $(date +%s) - started ))s"
  done
}

# Number of successful Flyway entries in a project's Core database (restore verification).
hr_flyway_count() {
  hr_compose "$1" exec -T postgres psql -U postgres -d divalhr -At \
    -c "SELECT count(*) FROM flyway_schema_history WHERE success" 2>/dev/null | tr -d '[:space:]'
}
