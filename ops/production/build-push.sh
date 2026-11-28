#!/bin/bash
# LAUNCH-002 (ADR 0048, L2-3a): builds the release images of an exact commit of origin/main on the
# operator's workstation and pushes them, tagged with the full SHA, to the private ECR repositories.
# The production host has no internet access and builds nothing.
#
#   bash ops/production/build-push.sh <full commit sha>
#
# Configuration comes from ~/.divalhr-production/deploy.env (never Git): DIVALHR_PROD_REGION,
# DIVALHR_PROD_REGISTRY (the private ECR host) and the usual AWS credentials of the named operator.
# Builds from `git archive` of the SHA, never from the working tree. Repositories are immutable-tag:
# a second push of the same tag fails, so a SHA always names one set of images.
set -u
SHA="${1:-}"
die() { echo "STOP: $*" >&2; exit 1; }
case "$SHA" in *[!0-9a-f]*|'') die "give the full 40-character commit SHA" ;; esac
[ "${#SHA}" = 40 ] || die "give the full 40-character commit SHA"
ENV_FILE="${DIVALHR_PROD_ENV:-$HOME/.divalhr-production/deploy.env}"
[ -r "$ENV_FILE" ] || die "missing $ENV_FILE (region, registry; kept outside Git)"
# shellcheck disable=SC1090
. "$ENV_FILE"
: "${DIVALHR_PROD_REGION:?}" "${DIVALHR_PROD_REGISTRY:?}"
case "$DIVALHR_PROD_REGISTRY" in *.dkr.ecr.*.amazonaws.com) ;; *) die "registry is not a private ECR host" ;; esac
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "run from the DivalHR repository"
cd "$ROOT" || exit 1
git fetch -q origin main || die "git fetch origin main failed"
git cat-file -e "$SHA^{commit}" 2>/dev/null || die "commit $SHA is not in this repository"
git merge-base --is-ancestor "$SHA" origin/main || die "$SHA is not reachable from origin/main"
command -v docker >/dev/null || die "docker is required"
command -v aws >/dev/null || die "the AWS CLI is required"

W=$(mktemp -d) && trap 'rm -rf "$W"' EXIT
git archive "$SHA" | tar -x -C "$W" || die "git archive failed"
cd "$W" || exit 1
echo "== Keycloak provisioning extension"
jar_line="$(ops/build/keycloak-provisioning-jar.sh | tail -n 1)" || die "the provisioning extension build failed"
case "$jar_line" in KEYCLOAK_PROVISIONING_JAR_SHA256=*) JAR_SHA="${jar_line#*=}" ;; *) die "no SHA-256 recorded" ;; esac
printf '%s' "$JAR_SHA" | grep -Eq '^[0-9a-f]{64}$' || die "malformed SHA-256"

aws ecr get-login-password --region "$DIVALHR_PROD_REGION" | docker login --username AWS --password-stdin "$DIVALHR_PROD_REGISTRY" >/dev/null \
  || die "ECR login failed"
PREFIX="$DIVALHR_PROD_REGISTRY/divalhr-prod"
build_push() { # <service> <docker build args...>
  local svc="$1"; shift
  echo "== $svc"
  docker build --platform linux/amd64 --pull -t "$PREFIX/$svc:$SHA" "$@" || die "build of $svc failed"
  docker push "$PREFIX/$svc:$SHA" >/dev/null || die "push of $svc failed (immutable tag already exists?)"
}
build_push keycloak -f infrastructure/docker/keycloak/Dockerfile \
  --build-arg "PROVISIONING_JAR_SHA256=$JAR_SHA" \
  --build-arg 'KC_BUILD_OPTIONS=--db=postgres --cache=local --http-relative-path=/identity --health-enabled=true' .
build_push core-api apps/core-api
build_push ai-service apps/ai-service
build_push web -f apps/web/Dockerfile .
echo "PUSHED $SHA to the private registry (4 images)"
