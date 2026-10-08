#!/usr/bin/env bash
# DEVX-001A (D2, A75-3, A75-4): the browser suite against the running Compose stack.
#
#   apps/web/e2e/run-suite.sh [--workers N] [--timings FILE] [--log-dir DIR]
#
# 1. A private SSO state directory (mktemp, mode 0700, outside the repository and every report
#    path) is created and removed again by a trap on exit, whatever happens.
# 2. `auth-setup` + `features`: one real MFA sign-in per privileged seed role, then the feature
#    tests in parallel (N workers, default 2), reusing those Keycloak sessions through the normal
#    Authorization Code + PKCE flow.
# 3. `identity`: the tests tagged @identity, alone and on one worker (interactive sign-ins, MFA,
#    wrong and replayed codes, membership changes, sign-outs). It runs even when `features` failed,
#    unless the stack itself is unavailable.
# 4. The credential-hygiene check (e2e/hygiene.ts) over the reports, results and logs.
# Exit status 0 only when every part passed.
# A single-worker diagnostic run of everything: `pnpm --filter @divalhr/web run e2e:serial`.
# E2E_SPECS (the `changed` profile): space-separated spec files to run instead of all of them,
# still split between features and identity.
# --results FILE (DEVX-001B): one `BROWSER passed= skipped= failed= flaky= notrun= retries=
# hygiene=` line for the verification record.
# Test hooks (e2e/tooling.test.ts only): E2E_PLAYWRIGHT replaces `npx playwright`;
# E2E_SKIP_STACK_CHECK=1 skips the availability probe; E2E_OUT replaces the web app directory as
# the parent of test-results/ and playwright-report/.
set -u
WEB="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${E2E_OUT:-$WEB}"
WORKERS="${E2E_WORKERS:-2}"
TIMINGS=""
LOG_DIR=""
RESULTS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --workers) WORKERS="$2"; shift 2 ;;
    --timings) TIMINGS="$2"; shift 2 ;;
    --log-dir) LOG_DIR="$2"; shift 2 ;;
    --results) RESULTS="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
case "$WORKERS" in 1|2|3|4) ;; *) echo "--workers must be 1 to 4" >&2; exit 2 ;; esac

SSO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/divalhr-e2e-sso-XXXXXXXX")" || exit 1
chmod 0700 "$SSO_DIR"
cleanup() { rm -rf "$SSO_DIR"; }
trap cleanup EXIT
trap 'exit 130' INT TERM
export DIVALHR_E2E_SSO_DIR="$SSO_DIR"
# Projects are independent here: identity runs as its own invocation, after features.
export E2E_SUITE=1
START=$(date +%s)

timing() { # <name> <seconds> <exit>
  local line="TIME $1 $2s exit=$3"
  echo "$line"
  [ -n "$TIMINGS" ] && echo "$line" >> "$TIMINGS"
  return 0
}

PLAYWRIGHT="${E2E_PLAYWRIGHT:-npx playwright}"
FILTER=()
if [ -n "${E2E_SPECS:-}" ]; then
  read -r -a FILTER <<< "$E2E_SPECS"
  FILTER+=(--pass-with-no-tests)
fi

stack_up() {
  [ "${E2E_SKIP_STACK_CHECK:-}" = 1 ] && return 0
  curl -fsS -o /dev/null --max-time 10 "${E2E_BASE_URL:-http://localhost:5173}/healthz" &&
    curl -fsS -o /dev/null --max-time 10 "${E2E_CORE_API_URL:-http://localhost:8080/api/v1}/system/status"
}

cd "$WEB" || exit 1
mkdir -p "$OUT/test-results"
OUTPUT="$OUT/test-results/run-suite.log"
: > "$OUTPUT"
stack_up || { echo "STOP: the stack is not available"; exit 1; }

t=$(date +%s)
PLAYWRIGHT_HTML_OUTPUT_DIR="$OUT/playwright-report/features" \
  $PLAYWRIGHT test --project=auth-setup --project=features --workers="$WORKERS" \
  --output="$OUT/test-results/features" ${FILTER[@]+"${FILTER[@]}"} 2>&1 | tee -a "$OUTPUT"
FEATURES=${PIPESTATUS[0]}
timing e2e-features $(( $(date +%s) - t )) "$FEATURES"

IDENTITY=1
if stack_up; then
  t=$(date +%s)
  PLAYWRIGHT_HTML_OUTPUT_DIR="$OUT/playwright-report/identity" \
    $PLAYWRIGHT test --project=identity --workers=1 --output="$OUT/test-results/identity" \
    ${FILTER[@]+"${FILTER[@]}"} 2>&1 | tee -a "$OUTPUT"
  IDENTITY=${PIPESTATUS[0]}
  timing e2e-identity $(( $(date +%s) - t )) "$IDENTITY"
else
  echo "FAIL the stack became unavailable: the identity project could not run"
fi

cleanup
t=$(date +%s)
node "$WEB/e2e/hygiene.ts" --since "$START" --sso-dir "$SSO_DIR" \
  "$OUT/test-results" "$OUT/playwright-report" ${LOG_DIR:+"$LOG_DIR"}
HYGIENE=$?
timing e2e-hygiene $(( $(date +%s) - t )) "$HYGIENE"
timing e2e-total $(( $(date +%s) - START )) $(( FEATURES || IDENTITY || HYGIENE ))

# DEVX-001B (R79-2): browser totals for the verification record, summed over both invocations
# from Playwright's list-reporter totals; retried tests are counted from their `(retry #n)` lines.
total() { grep -E "^ +[0-9]+ $1( |\$)" "$OUTPUT" | awk '{n += $1} END {print n + 0}'; }
if [ -n "$RESULTS" ]; then
  printf 'BROWSER passed=%s skipped=%s failed=%s flaky=%s notrun=%s retries=%s hygiene=%s\n' \
    "$(total passed)" "$(total skipped)" "$(total failed)" "$(total flaky)" "$(total 'did not run')" \
    "$(grep -c '(retry #' "$OUTPUT")" "$([ "$HYGIENE" = 0 ] && echo PASS || echo FAIL)" > "$RESULTS"
fi

echo "features exit=$FEATURES identity exit=$IDENTITY hygiene exit=$HYGIENE"
[ "$FEATURES" = 0 ] && [ "$IDENTITY" = 0 ] && [ "$HYGIENE" = 0 ]
