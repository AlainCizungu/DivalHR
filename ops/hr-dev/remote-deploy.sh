#!/usr/bin/env bash
# OPS-001 (section 10): deploys one release of the test environment ON THE INSTANCE.
# Called by ops/hr-dev/deploy.sh from the owner's Mac after its provenance checks; never run by
# hand except in a documented recovery.
#
#   sudo ops/hr-dev/remote-deploy.sh --bundle <file> --sha <full sha> [--first-run]
#
# Fail closed (A65-7): the release must be the bundle's exact head and must have qualifying
# verification evidence on this instance (DEVX-001B: a run of the same tree whose rehearsal covers
# the release class; see check_verification). Images are tagged with the full SHA.
# The release is unpacked with `git archive`, never from the verification checkout ~/DivalHR.
set -u
. "$(dirname "$0")/lib.sh"

BUNDLE="" SHA="" FIRST_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle) BUNDLE="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --first-run) FIRST_RUN=1; shift ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
hr_require_root
hr_require_sha "$SHA"
[ -r "$BUNDLE" ] || hr_die "bundle not readable"
BUNDLE="$(cd "$(dirname "$BUNDLE")" && pwd)/$(basename "$BUNDLE")"
hr_require_encrypted_data_root
divalhr_lock "deploy $SHA" || exit $?

VERIFY_RUNS="${HR_DEV_VERIFY_RUNS:-/home/ubuntu/divalhr-runs}"

# --- provenance on the instance (A65-7) --------------------------------------------------------
check_bundle_head() {
  local heads
  heads=$(git bundle list-heads "$BUNDLE" 2>/dev/null | awk '{print $1}' | sort -u)
  [ "$heads" = "$SHA" ] || hr_die "the bundle's head is not exactly $SHA"
}

# DEVX-001B (A75-6, A75B-2..A75B-6): ops/hr-dev/evidence.py decides from the checksummed,
# read-only verification records on this instance. A run qualifies only with an intact record,
# exit 0, profile pr or full, its retained bundle unchanged with the record's commit as single
# head, the same tree as this release (recomputed from both bundles), and every rehearsal step
# the release class requires (recomputed from the deployed release..SHA diff) recorded as PASS.
# The newest qualifying run wins; otherwise the deployment stops with the verification to run.
check_verification() {
  [ "${HR_DEV_REHEARSAL:-}" = "1" ] && { hr_log "rehearsal: verification evidence not required"; return 0; }
  local decision rc
  decision=$(python3 "$(dirname "$0")/evidence.py" decide --release "$SHA" --release-bundle "$BUNDLE" \
    --runs "$VERIFY_RUNS" --deployed "$(hr_current_release_strict)" 2>&1); rc=$?
  printf '%s\n' "$decision" | while IFS= read -r line; do hr_log "evidence: $line"; done
  [ "$rc" = 0 ] || hr_die "no qualifying verification evidence for $SHA on this instance"
}

check_bundle_head
check_verification

# --- unpack the release ------------------------------------------------------------------------
REL="$(hr_release_dir "$SHA")"
if [ ! -d "$REL" ]; then
  tmp=$(mktemp -d)
  git init -q --bare "$tmp/repo"
  git -C "$tmp/repo" fetch -q "$BUNDLE" "+refs/*:refs/bundle/*" || hr_die "bundle fetch failed"
  mkdir -p "$REL.partial"
  git -C "$tmp/repo" archive "$SHA" | tar -x -C "$REL.partial" || hr_die "archive failed"
  mv "$REL.partial" "$REL"
  rm -rf "$tmp"
fi
hr_log "release $SHA unpacked"

# --- data directories and secrets (on the encrypted volume) ------------------------------------
if [ ! -s "$HR_DEV_DATA/secrets/core/DIVALHR_EMAIL_LOOKUP_KEY" ]; then
  [ "$FIRST_RUN" = 1 ] || hr_die "secrets are missing; the first deployment needs --first-run"
  "$REL/ops/hr-dev/init-secrets.sh" || hr_die "init-secrets failed"
fi
hr_prepare_data "$REL"
hr_export_tls

# --- build, back up, start ---------------------------------------------------------------------
PREVIOUS="$(hr_current_release)"
hr_log "building images ${HR_DEV_IMAGE_PREFIX}/*:$SHA"
hr_compose "$SHA" build --pull || hr_die "image build failed"

if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$SHA" ]; then
  "$REL/ops/hr-dev/backup.sh" --reason pre-deploy --label "$SHA" --release "$PREVIOUS" \
    || hr_die "pre-deploy backup failed; nothing was changed"
fi

# The timers (watchdog, nightly backup) are part of the release's checks (R66-4): the release
# is recorded as current only after they are installed, so a failure here rolls back like any
# other failed check. install-units.sh restores the previous unit files when it fails.
install_units() {
  if [ -n "${HR_DEV_INSTALL_UNITS_CMD:-}" ]; then
    $HR_DEV_INSTALL_UNITS_CMD
  elif [ "${HR_DEV_REHEARSAL:-}" = "1" ]; then
    hr_log "rehearsal: systemd timers not installed"
  else
    "$REL/ops/hr-dev/install-units.sh"
  fi
}

hr_log "starting $SHA"
if hr_compose "$SHA" up -d --wait --wait-timeout 600 --remove-orphans \
  && "$REL/ops/hr-dev/keycloak-admin-setup.sh" \
  && "$REL/ops/hr-dev/http-checks.sh" \
  && "$REL/ops/hr-dev/backchannel-check.sh" "$SHA" \
  && install_units; then
  if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$SHA" ]; then
    printf '%s\n' "$PREVIOUS" > "$HR_DEV_DATA/state/previous-release"
  fi
  hr_set_current "$SHA"
  rm -f "$HR_DEV_DATA/state/failed-release"
  hr_log "deployed $SHA"
else
  hr_log "release $SHA failed its checks"
  hr_diagnose "$SHA"
  if [ -n "$PREVIOUS" ] && [ "$PREVIOUS" != "$SHA" ]; then
    printf '%s\n' "$SHA" > "$HR_DEV_DATA/state/failed-release"
    "$REL/ops/hr-dev/rollback.sh" --from "$SHA" --to "$PREVIOUS"
    exit 1
  fi
  hr_compose "$SHA" stop
  hr_die "first deployment failed; the stack is stopped and no release is recorded as current"
fi

# --- keep the last releases (exact tags only; never a global prune) -----------------------------
# shellcheck disable=SC2010,SC2012
ls -1t "$HR_DEV_DATA/releases" | grep -E '^[0-9a-f]{40}$' | tail -n +"$((HR_DEV_KEEP_RELEASES + 1))" | while read -r old; do
  if [ "$old" = "$(hr_current_release)" ] || [ "$old" = "$(hr_previous_release)" ]; then continue; fi
  for svc in keycloak core-api ai-service web; do
    docker image rm "$HR_DEV_IMAGE_PREFIX/$svc:$old" >/dev/null 2>&1 || true
  done
  rm -rf "${HR_DEV_DATA:?}/releases/$old"
  hr_log "removed old release $old"
done
