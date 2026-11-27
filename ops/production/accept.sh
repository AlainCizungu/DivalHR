#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-12): the production acceptance. Run by the operator after the first
# production deployment, with the launch lock CLOSED and SYNTHETIC data only. It does not lift the lock.
#
#   bash ops/production/accept.sh --evidence-dir <dir>
#
# 1. the launch lock is closed (host state AND the SSM parameter), and onboarding is refused;
# 2. exposure checks from the internet; HTTP checks through the public names;
# 3. the two browser journeys (public/sign-in; bilingual synthetic tenant, when the account is given);
# 4. host evidence, a passing restore drill younger than 100 days, backup age under 26 h, log hygiene.
# Everything is written to the evidence directory (redacted by construction: names, counts, statuses).
# Configuration: ~/.divalhr-production/deploy.env as for deploy.sh. Credentials of the synthetic
# account come from PROD_ACCEPTANCE_USER / PROD_ACCEPTANCE_PASSWORD in the environment only.
set -u
die() { echo "STOP: $*" >&2; exit 1; }
OUT=""
while [ $# -gt 0 ]; do case "$1" in --evidence-dir) OUT="$2"; shift 2 ;; *) die "unknown option $1" ;; esac; done
[ -n "$OUT" ] || die "--evidence-dir <dir> is required"
mkdir -p "$OUT"
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${DIVALHR_PROD_ENV:-$HOME/.divalhr-production/deploy.env}"
[ -r "$ENV_FILE" ] || die "missing $ENV_FILE"
# shellcheck disable=SC1090
. "$ENV_FILE"
: "${DIVALHR_PROD_REGION:?}" "${DIVALHR_PROD_INSTANCE_ID:?}"
AWS="${DIVALHR_AWS:-aws}"
FAIL=0
step() { # <name> <command...>
  local name="$1"; shift
  if "$@" > "$OUT/$name.txt" 2>&1; then echo "PASS $name"; else echo "FAIL $name (see $OUT/$name.txt)"; FAIL=1; fi
}
ssm() { # <commands> -> output
  local params id st
  params=$(python3 -c 'import json,sys; print(json.dumps({"commands": sys.argv[1].split("\n")}))' "$1")
  id=$("$AWS" ssm send-command --region "$DIVALHR_PROD_REGION" --instance-ids "$DIVALHR_PROD_INSTANCE_ID" \
    --document-name AWS-RunShellScript --parameters "$params" --query Command.CommandId --output text) || return 1
  while :; do
    sleep 5
    st=$("$AWS" ssm get-command-invocation --region "$DIVALHR_PROD_REGION" --command-id "$id" --instance-id "$DIVALHR_PROD_INSTANCE_ID" --query Status --output text 2>/dev/null) || st=Pending
    case "$st" in Success|Failed|Cancelled|TimedOut) break ;; esac
  done
  "$AWS" ssm get-command-invocation --region "$DIVALHR_PROD_REGION" --command-id "$id" --instance-id "$DIVALHR_PROD_INSTANCE_ID" --query StandardOutputContent --output text
  [ "$st" = Success ]
}
lock_closed() {
  local param
  param=$("$AWS" ssm get-parameter --region "$DIVALHR_PROD_REGION" --name /divalhr/production/launch-lock --query Parameter.Value --output text) || return 1
  echo "ssm launch lock: $param"; [ "$param" = CLOSED ] || return 1
  ssm '/srv/divalhr-prod/current/ops/production/launch-lock.sh require-closed && /srv/divalhr-prod/current/ops/production/launch-lock.sh status'
}
step 01-launch-lock-closed lock_closed
step 02-exposure bash "$HERE/exposure-check.sh" --waf-probe
step 03-http-through-public-names env PROD_EXTERNAL=1 bash "$HERE/http-checks.sh"
step 04-host-evidence ssm '/srv/divalhr-prod/current/ops/production/evidence-host.sh'
step 05-backup-and-drill ssm 'cat /srv/divalhr-prod/state/last-restore-drill; echo backup_age_s=$(( $(date +%s) - $(cat /srv/divalhr-prod/state/last-backup-success) )); grep -q "^result=PASS" /srv/divalhr-prod/state/last-restore-drill && [ $(( $(date +%s) - $(cat /srv/divalhr-prod/state/last-backup-success) )) -lt 93600 ]'
step 06-log-hygiene ssm '/srv/divalhr-prod/current/ops/production/log-hygiene.sh --since '"$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ)"
WEB="$HERE/../../apps/web"
# The journeys never skip: every input is checked BEFORE Playwright starts, and the machine-readable
# result must show exactly the two expected journeys, none failed, skipped, flaky or retried.
journeys() {
  local missing="" v
  for v in PROD_PLATFORM_ADMIN_USER PROD_PLATFORM_ADMIN_PASSWORD PROD_PLATFORM_ADMIN_TOTP_SECRET \
    PROD_ACCEPTANCE_MAILBOX PROD_ACCEPTANCE_MAIL_CMD; do
    [ -n "${!v:-}" ] || missing="$missing $v"
  done
  [ -z "$missing" ] || { echo "STOP: required and not set:$missing (the synthetic tenant journey is never skipped)"; return 1; }
  [ -x "$PROD_ACCEPTANCE_MAIL_CMD" ] || { echo "STOP: PROD_ACCEPTANCE_MAIL_CMD is not an executable file"; return 1; }
  local host="${PROD_PUBLIC_HOST:-divalhr.com}" app="${PROD_APP_HOST:-app.divalhr.com}"
  rm -f "$OUT/journeys.json" "$OUT/tenant-evidence.json"
  ( cd "$WEB" && PROD_PUBLIC_URL="https://$host" PROD_APP_URL="https://$app" PROD_WWW_URL="https://www.$host" \
      PROD_PLAYWRIGHT_JSON="$OUT/journeys.json" PROD_ACCEPTANCE_EVIDENCE="$OUT/tenant-evidence.json" \
      pnpm exec playwright test -c playwright.production.config.ts ) || { echo "FAIL playwright exited non-zero"; return 1; }
  python3 "$HERE/verify-journeys.py" "$OUT/journeys.json"
}
step 07-browser-journeys journeys
step 08-launch-lock-still-closed lock_closed
{ echo "result=$([ $FAIL = 0 ] && echo PASS || echo FAIL)"; echo "finished=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "launch_lock=CLOSED (acceptance never lifts it)"; } > "$OUT/result.txt"
( cd "$OUT" && shasum -a 256 ./*.txt > SHA256SUMS )
cat "$OUT/result.txt"
[ "$FAIL" = 0 ]
