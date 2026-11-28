#!/bin/bash
# LAUNCH-002 (ADR 0048, L2-3a): mirrors the two third-party base images (Caddy, PostgreSQL) BY DIGEST into the
# private ECR mirror repository, so the host (no internet) can pull them. Run once per image
# version by the operator, from a workstation with skopeo (or crane) and the operator's AWS access.
#
#   bash ops/production/mirror-images.sh
#
# The digests come from the production Compose file, never from a tag: the copy is verified to have
# exactly that digest on the destination, or the script stops. The digests are the ones the test
# environment already runs, so production starts from images that were exercised there.
set -u
die() { echo "STOP: $*" >&2; exit 1; }
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "run from the DivalHR repository"
ENV_FILE="${DIVALHR_PROD_ENV:-$HOME/.divalhr-production/deploy.env}"
[ -r "$ENV_FILE" ] || die "missing $ENV_FILE"
# shellcheck disable=SC1090
. "$ENV_FILE"
: "${DIVALHR_PROD_REGION:?}" "${DIVALHR_PROD_REGISTRY:?}"
command -v skopeo >/dev/null || die "skopeo is required"
command -v aws >/dev/null || die "the AWS CLI is required"
COMPOSE="$ROOT/infrastructure/production/compose.yaml"
SOURCES=(
  "docker.io/library/caddy:2.10.2-alpine"
  "docker.io/library/postgres:17.11"
)
CREDS="AWS:$(aws ecr get-login-password --region "$DIVALHR_PROD_REGION")" || die "ECR login failed"
for src in "${SOURCES[@]}"; do
  name="${src##*/}"
  digest=$(grep -oE "divalhr-prod/mirror@sha256:[0-9a-f]{64}" "$COMPOSE" | sort -u | sed 's/.*@//' | while read -r d; do
    skopeo inspect --raw "docker://$src@$d" >/dev/null 2>&1 && { echo "$d"; break; }
  done)
  [ -n "$digest" ] || die "no digest of the production Compose file matches $src (is the pin current?)"
  tag="$(printf '%s' "$name" | tr ':/' '--')-${digest:7:12}"
  skopeo copy --all --dest-creds "$CREDS" "docker://$src@$digest" "docker://$DIVALHR_PROD_REGISTRY/divalhr-prod/mirror:$tag" \
    || die "copy of $src failed"
  got=$(skopeo inspect --raw --creds "$CREDS" "docker://$DIVALHR_PROD_REGISTRY/divalhr-prod/mirror:$tag" | shasum -a 256 | awk '{print "sha256:" $1}')
  [ "$got" = "$digest" ] || die "the mirrored manifest of $src has digest $got, expected $digest"
  echo "MIRRORED $src -> mirror:$tag ($digest)"
done
