#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-10): deploys one release of PRODUCTION ON THE HOST. Called by deploy.sh
# through Systems Manager after its provenance checks; never run by hand except in a documented
# recovery.
#
#   sudo ops/production/remote-deploy.sh --bundle <file> --sha <full sha> [--first-run]
#
# Fail closed: the bundle's only head is the SHA; a qualifying FULL verification of the same tree,
# finished within PROD_EVIDENCE_MAX_AGE_HOURS, exists (the class is always `complete`: nothing is
# inferred from "what changed since"); the e-mail provider is configured (Core cannot start without
# it); the secrets exist in Secrets Manager. The release is unpacked with `git archive`, never from
# a working tree. Images come from the private registry, tagged with the full SHA.
# On any failed check the previous release AND both databases are restored (rollback.sh).
set -u
. "$(dirname "$0")/lib.sh"

BUNDLE="" SHA="" FIRST_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle) BUNDLE="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --first-run) FIRST_RUN=1; shift ;;
    *) prod_die "unknown argument $1" ;;
  esac
done
prod_require_root
hr_require_sha "$SHA"
[ -r "$BUNDLE" ] || prod_die "bundle not readable"
BUNDLE="$(cd "$(dirname "$BUNDLE")" && pwd)/$(basename "$BUNDLE")"
prod_require_encrypted_data_root
divalhr_lock "deploy $SHA" || exit $?
VERIFY_RUNS="${PROD_VERIFY_RUNS:?PROD_VERIFY_RUNS (the downloaded verification records) is required}"
MAX_AGE_H="${PROD_EVIDENCE_MAX_AGE_HOURS:-72}"

# --- provenance on the host ---------------------------------------------------------------------
heads=$(git bundle list-heads "$BUNDLE" 2>/dev/null | awk '{print $1}' | sort -u)
[ "$heads" = "$SHA" ] || prod_die "the bundle's head is not exactly $SHA"

decision=$(python3 "$PROD_OPS/../hr-dev/evidence.py" decide --release "$SHA" --release-bundle "$BUNDLE" \
  --runs "$VERIFY_RUNS" 2>&1); rc=$?
printf '%s\n' "$decision" | while IFS= read -r line; do prod_log "evidence: $line"; done
[ "$rc" = 0 ] || prod_die "no qualifying full verification for $SHA on this host"
run=$(printf '%s\n' "$decision" | sed -n 's/^ACCEPT //p' | head -1)
[ -n "$run" ] && [ -f "$VERIFY_RUNS/$run/evidence.json" ] || prod_die "the accepted verification run cannot be located"
python3 - "$VERIFY_RUNS/$run/evidence.json" "$MAX_AGE_H" <<'PY' || prod_die "the verification is older than $MAX_AGE_H hours"
import json, sys, datetime
rec = json.load(open(sys.argv[1], encoding="utf-8"))
done = datetime.datetime.strptime(rec["finishedAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
sys.exit(0 if (datetime.datetime.now(datetime.timezone.utc) - done).total_seconds() / 3600 <= float(sys.argv[2]) else 1)
PY

# --- unpack the release ------------------------------------------------------------------------
REL="$(hr_release_dir "$SHA")"
install -d -m 0711 "$PROD_DATA" "$PROD_DATA/releases"
if [ ! -d "$REL" ]; then
  tmp=$(mktemp -d)
  git init -q --bare "$tmp/repo"
  git -C "$tmp/repo" fetch -q "$BUNDLE" "+refs/*:refs/bundle/*" || prod_die "bundle fetch failed"
  mkdir -p "$REL.partial"
  git -C "$tmp/repo" archive "$SHA" | tar -x -C "$REL.partial" || prod_die "archive failed"
  mv "$REL.partial" "$REL"
  rm -rf "$tmp"
fi
prod_log "release $SHA unpacked"

# --- configuration, secrets, data directories ----------------------------------------------------
prod_export_env       # fails closed without the registry, VPC range and the e-mail provider
if [ ! -s "$PROD_DATA/secrets/core/DIVALHR_EMAIL_LOOKUP_KEY" ]; then
  [ "$FIRST_RUN" = 1 ] || prod_die "secrets are missing; the first deployment needs --first-run"
fi
"$REL/ops/production/init-secrets.sh" || prod_die "init-secrets failed"
hr_prepare_data "$REL"
PROD_REGION="$(prod_config region)"
aws ecr get-login-password --region "$PROD_REGION" | docker login --username AWS --password-stdin "$PROD_REGISTRY" >/dev/null \
  || prod_die "private registry sign-in failed"

# --- pull, back up, start ---------------------------------------------------------------------
PREVIOUS="$(hr_current_release)"
prod_log "pulling images of $SHA"
hr_compose "$SHA" pull --quiet || prod_die "image pull failed (are the four images pushed?)"
if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$SHA" ]; then
  "$REL/ops/production/backup.sh" --reason pre-deploy --label "$SHA" --release "$PREVIOUS" \
    || prod_die "pre-deploy backup failed; nothing was changed"
fi

install_units() { "$REL/ops/production/install-units.sh"; }

prod_log "starting $SHA"
rm -f "$PROD_DATA/state/deploy-failed-flag"
if hr_compose "$SHA" up -d --no-build --wait --wait-timeout 600 --remove-orphans \
  && "$REL/ops/production/keycloak-admin-setup.sh" \
  && "$REL/ops/production/keycloak-ai-client.sh" \
  && "$REL/ops/production/realm-verify.sh" "$SHA" \
  && "$REL/ops/production/http-checks.sh" \
  && "$REL/ops/production/backchannel-check.sh" "$SHA" \
  && "$REL/ops/production/ai-capability-check.sh" "$SHA" \
  && install_units; then
  [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$SHA" ] && printf '%s\n' "$PREVIOUS" > "$PROD_DATA/state/previous-release"
  hr_set_current "$SHA"
  rm -f "$PROD_DATA/state/failed-release"
  { printf 'release=%s\nlaunch_lock=%s\ndeployed=%s\n' "$SHA" "$(prod_launch_lock)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$PROD_DATA/state/last-deploy"
  prod_log "deployed $SHA (launch lock: $(prod_launch_lock))"
else
  prod_log "release $SHA failed its checks"
  hr_diagnose "$SHA"
  : > "$PROD_DATA/state/deploy-failed-flag"
  if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$SHA" ]; then
    printf '%s\n' "$SHA" > "$PROD_DATA/state/failed-release"
    "$REL/ops/production/rollback.sh" --from "$SHA" --to "$PREVIOUS"
    exit 1
  fi
  hr_compose "$SHA" stop
  prod_die "first deployment failed; the stack is stopped and no release is recorded as current"
fi

# --- keep the last releases (exact tags only; never a global prune) -----------------------------
# shellcheck disable=SC2010,SC2012
ls -1t "$PROD_DATA/releases" | grep -E '^[0-9a-f]{40}$' | tail -n +"$((PROD_KEEP_RELEASES + 1))" | while read -r old; do
  if [ "$old" = "$(hr_current_release)" ] || [ "$old" = "$(hr_previous_release)" ]; then continue; fi
  for svc in keycloak core-api ai-service web; do docker image rm "$PROD_REGISTRY/divalhr-prod/$svc:$old" >/dev/null 2>&1 || true; done
  rm -rf "${PROD_DATA:?}/releases/$old"
  prod_log "removed old release $old"
done
