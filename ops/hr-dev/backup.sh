#!/usr/bin/env bash
# OPS-001 (section 9): logical backups of the test environment's two databases.
#
#   sudo ops/hr-dev/backup.sh --reason nightly
#   sudo ops/hr-dev/backup.sh --reason pre-deploy --label <new sha> --release <running sha>
#
# pg_dump custom format of `divalhr` and `keycloak`, with SHA-256 sums, into
# $HR_DEV_DATA/backups/<reason>/<UTC timestamp>[-label]/ on the encrypted volume (A65-1).
# The dumps are the authoritative, application-consistent backup; EBS snapshots are additional
# disaster-recovery protection. Retention: 7 nightly, 3 pre-deploy.
set -u
. "$(dirname "$0")/lib.sh"

REASON="" LABEL="" RELEASE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --reason) REASON="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
case "$REASON" in nightly) KEEP=7 ;; pre-deploy) KEEP=3 ;; *) hr_die "--reason nightly|pre-deploy" ;; esac
hr_require_root
hr_require_encrypted_data_root
divalhr_lock "backup $REASON" || exit $?
RELEASE="${RELEASE:-$(hr_current_release)}"
[ -n "$RELEASE" ] || hr_die "no running release to back up"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="$HR_DEV_DATA/backups/$REASON/$STAMP${LABEL:+-$LABEL}"
umask 077
mkdir -p "$DIR.partial"
started=$(date +%s)
for db in divalhr keycloak; do
  hr_compose "$RELEASE" exec -T postgres pg_dump -U postgres -Fc --no-owner --no-privileges "$db" \
    > "$DIR.partial/$db.dump" || hr_die "pg_dump $db failed"
  [ -s "$DIR.partial/$db.dump" ] || hr_die "empty dump for $db"
done
(cd "$DIR.partial" && sha256sum divalhr.dump keycloak.dump > SHA256SUMS)
printf 'release=%s\nreason=%s\ncreated=%s\nflyway_success=%s\nseconds=%s\n' "$RELEASE" "$REASON" "$STAMP" \
  "$(hr_flyway_count "$RELEASE")" "$(( $(date +%s) - started ))" > "$DIR.partial/MANIFEST"
mv "$DIR.partial" "$DIR"
hr_log "backup $DIR ($(du -sh "$DIR" | awk '{print $1}'), $(( $(date +%s) - started ))s)"

# Retention: newest $KEEP of this reason.
ls -1d "$HR_DEV_DATA/backups/$REASON"/*/ 2>/dev/null | sort -r | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -rf "$old"
  hr_log "pruned $old"
done
