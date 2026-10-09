#!/usr/bin/env bash
# OPS-001: runs the browser acceptance suite (apps/web/e2e/hr-dev) against a rehearsal or drill
# stack on this instance, as the verification user (never as root).
#
#   rehearsal-browser.sh <https port> <admin port> [playwright arguments]
#
# Expects (from rehearse.sh): HR_DEV_DATA, HR_DEV_ORIGIN, HR_DEV_HOST, REHEARSAL_REPO,
# REHEARSAL_USER and optionally HR_DEV_USER_PATH. The rehearsal operator's credentials are read
# from the rehearsal's own kc-admin.env and passed to the test process only.
# SEC-001: after the suite's real sign-ins (employee and privileged with TOTP), log-hygiene.sh
# checks every service's log written since the suite started; either failure fails this step.
set -u
https="${1:?https port}" admin="${2:?admin port}"; shift 2
[ -n "${REHEARSAL_USER:-}" ] || { echo "no verification user (run rehearse.sh through sudo)"; exit 1; }
# shellcheck disable=SC1091
. "$HR_DEV_DATA/secrets/ops/kc-admin.env"
cd "$REHEARSAL_REPO" || exit 1
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
sudo -u "$REHEARSAL_USER" -H env PATH="${HR_DEV_USER_PATH:-$PATH}" HR_DEV_REHEARSAL=1 \
  HR_DEV_BASE_URL="$HR_DEV_ORIGIN" HR_DEV_ADMIN_URL="http://localhost:$admin" \
  HR_DEV_KC_ADMIN_USER="$KC_ADMIN_USER" HR_DEV_KC_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" \
  HR_DEV_BROWSER_ARGS="--host-resolver-rules=MAP $HR_DEV_HOST 127.0.0.1:$https" \
  npm exec --yes -- pnpm@10.34.6 --filter @divalhr/web exec playwright test -c playwright.hr-dev.config.ts "$@"
suite=$?
"$(dirname "$0")/log-hygiene.sh" --since "$since" --project "$HR_DEV_PROJECT"
hygiene=$?
[ "$suite" = 0 ] && [ "$hygiene" = 0 ]
