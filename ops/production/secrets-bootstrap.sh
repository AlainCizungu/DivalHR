#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-5): creates the production secrets in AWS Secrets Manager, once.
#
#   ops/production/secrets-bootstrap.sh --region <region> [--dry-run]
#
# Run by the founder-approved operator with their own AWS credentials, from a workstation, AFTER the
# production stack is provisioned (a separately approved step). Every "generated" entry of
# secrets.manifest that does not exist yet is created with a fresh random value, encrypted with the
# production KMS key (alias/divalhr-prod) and written through a mode-0600 file on a RAM disk that is
# removed immediately; nothing is printed, put on a command line or in the environment. An existing
# secret is never overwritten or rotated here. "operator" entries are listed, never invented.
# Refuses any value that looks like a development or test value.
set -u
REGION="" DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "STOP: unknown argument $1" >&2; exit 1 ;;
  esac
done
[ -n "$REGION" ] || { echo "STOP: --region is required (choosing the region is an operator decision)" >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
PREFIX="divalhr/production"
KEY_ALIAS="alias/divalhr-prod"
command -v aws >/dev/null || { echo "STOP: the AWS CLI is not installed" >&2; exit 1; }
[ "$DRY" = 1 ] || aws sts get-caller-identity --region "$REGION" >/dev/null || { echo "STOP: no usable AWS credentials" >&2; exit 1; }

# A tmpfs-backed scratch directory when one exists; otherwise a private temp directory.
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/divalhr-secrets.XXXXXX")" || exit 1
chmod 700 "$SCRATCH"
trap 'rm -rf "$SCRATCH"' EXIT
umask 077

random() { openssl rand -base64 64 | tr -d '/+=\n' | cut -c1-48; }
created=0 existing=0 operator=0
DONE=" "   # names already handled (bash 3.2 on macOS has no associative arrays)
while IFS='|' read -r name _file _owner kind; do
  case "$name" in ''|'#'*) continue ;; esac
  case "$DONE" in *" $name "*) continue ;; esac
  DONE="$DONE$name "
  if [ "$kind" = operator ]; then
    echo "operator-supplied: $PREFIX/$name (store it from the provider's console; never generate it here)"
    operator=$((operator + 1)); continue
  fi
  if [ "$DRY" = 1 ]; then echo "would create (if absent): $PREFIX/$name"; continue; fi
  if aws secretsmanager describe-secret --region "$REGION" --secret-id "$PREFIX/$name" >/dev/null 2>&1; then
    echo "kept:    $PREFIX/$name"; existing=$((existing + 1)); continue
  fi
  random > "$SCRATCH/value"
  grep -qiE 'dev-only|changeme|password|example|test' "$SCRATCH/value" && { echo "STOP: a generated value looked like a development value; run again" >&2; exit 1; }
  aws secretsmanager create-secret --region "$REGION" --name "$PREFIX/$name" --kms-key-id "$KEY_ALIAS" \
    --secret-string "file://$SCRATCH/value" --description "DivalHR production (controlled pilot)" >/dev/null \
    || { echo "STOP: could not create $PREFIX/$name" >&2; exit 1; }
  rm -f "$SCRATCH/value"
  echo "created: $PREFIX/$name"; created=$((created + 1))
done < "$HERE/secrets.manifest"
echo "done: $created created, $existing kept, $operator operator-supplied (values never shown)"
