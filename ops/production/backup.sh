#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-9): logical backups of the production Core and Keycloak databases.
#
#   sudo ops/production/backup.sh --reason nightly
#   sudo ops/production/backup.sh --reason pre-deploy --label <new sha> --release <running sha>
#
# pg_dump custom format of `divalhr` and `keycloak` with SHA-256 sums and a MANIFEST, on the
# encrypted data volume, then copied OFF-HOST to the versioned, KMS-encrypted backup bucket under
# backups/<reason>/<stamp>/. The secret store is NEVER part of a backup: only the two dumps, their sums and
# the manifest leave the database container (secrets live in Secrets Manager).
# The off-host copy is verified by listing the uploaded objects against the sums. A backup counts as
# successful (state/last-backup-success, read by the age alarm) only when the local dumps AND the
# off-host copy are verified. Retention on the host: 7 nightly, 3 pre-deploy; the bucket's lifecycle
# rule keeps its own, longer, history.
set -u
. "$(dirname "$0")/lib.sh"

REASON="" LABEL="" RELEASE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --reason) REASON="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    *) prod_die "unknown argument $1" ;;
  esac
done
case "$REASON" in nightly) KEEP=7 ;; pre-deploy) KEEP=3 ;; *) prod_die "--reason nightly|pre-deploy" ;; esac
prod_require_root
prod_require_encrypted_data_root
divalhr_lock "backup $REASON" || exit $?
RELEASE="${RELEASE:-$(hr_current_release)}"
[ -n "$RELEASE" ] || prod_die "no running release to back up"
prod_export_env

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="$PROD_DATA/backups/$REASON/$STAMP${LABEL:+-$LABEL}"
umask 077
mkdir -p "$DIR.partial"
started=$(date +%s)
for db in divalhr keycloak; do
  hr_compose "$RELEASE" exec -T postgres pg_dump -U postgres -Fc --no-owner --no-privileges "$db" \
    > "$DIR.partial/$db.dump" || prod_die "pg_dump $db failed"
  [ -s "$DIR.partial/$db.dump" ] || prod_die "empty dump for $db"
done
(cd "$DIR.partial" && sha256sum divalhr.dump keycloak.dump > SHA256SUMS)
printf 'release=%s\nreason=%s\ncreated=%s\nflyway_success=%s\nseconds=%s\n' "$RELEASE" "$REASON" "$STAMP" \
  "$(hr_flyway_count "$RELEASE")" "$(( $(date +%s) - started ))" > "$DIR.partial/MANIFEST"
mv "$DIR.partial" "$DIR"
prod_log "backup $DIR ($(du -sh "$DIR" | awk '{print $1}'), $(( $(date +%s) - started ))s)"

# Off-host copy (versioned bucket, SSE-KMS enforced by the bucket policy), then verification.
BUCKET="$(prod_config backup-bucket)" REGION="$(prod_config region)"
AWS="${PROD_AWS:-aws}"
if [ -z "$BUCKET" ] || [ -z "$REGION" ]; then
  prod_die "off-host backup is not configured (config/backup-bucket, config/region): the backup is local only and is NOT counted as successful"
fi
prefix="backups/$REASON/$(basename "$DIR")"
"$AWS" s3 cp --region "$REGION" --only-show-errors --sse aws:kms --recursive "$DIR" "s3://$BUCKET/$prefix/" \
  || prod_die "off-host copy failed; the backup is NOT counted as successful"
remote=$("$AWS" s3api list-objects-v2 --region "$REGION" --bucket "$BUCKET" --prefix "$prefix/" \
  --query 'Contents[].[Key,Size]' --output text | LC_ALL=C sort)
for f in divalhr.dump keycloak.dump SHA256SUMS MANIFEST; do
  size=$(wc -c < "$DIR/$f" | tr -d ' ')
  printf '%s\n' "$remote" | grep -qx "$prefix/$f	$size" \
    || prod_die "off-host object $f is missing or has a different size; the backup is NOT counted as successful"
done
prod_log "off-host copy verified in s3://<backup bucket>/$prefix/"
date +%s > "$PROD_DATA/state/last-backup-success"
[ "$REASON" = nightly ] && date +%s > "$PROD_DATA/state/last-nightly-success"

# Host retention: newest $KEEP of this reason.
ls -1d "$PROD_DATA/backups/$REASON"/*/ 2>/dev/null | sort -r | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -rf "$old"
  prod_log "pruned $old"
done
