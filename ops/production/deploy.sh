#!/bin/bash
# LAUNCH-002 (ADR 0048, L2-10): deploys one commit of main to PRODUCTION, from the operator's
# workstation, through AWS Systems Manager (no SSH). Fails closed: nothing reaches the host unless
# every check passes. It is run ONLY after the production stack exists, its prerequisites are in
# place and the founder approved this release in writing. LAUNCH-002 does not run it.
#
#   bash ops/production/deploy.sh <full sha> --evidence-run <verification run dir> --approval <reference> [--first-run]
#
# Provenance, in this order:
#   1. a full 40-character SHA reachable from origin/main;
#   2. every GitHub check run on that SHA green and every required check present (provenance.py);
#   3. a qualifying, checksummed, read-only FULL verification of exactly this tree, finished within
#      PROD_EVIDENCE_MAX_AGE_HOURS (default 72): hr-dev evidence of an earlier tree never counts;
#   4. the four images of the SHA exist in the private registry (build-push.sh);
#   5. explicit founder approval: --approval <reference> AND the interactive confirmation phrase
#      "DEPLOY PRODUCTION <first 12 of the SHA>" typed by the operator (never non-interactive);
#   6. the bundle and the evidence are uploaded to the evidence bucket, the host verifies the
#      bundle's SHA-256 and head, takes the host-wide lock, backs up, migrates, checks and, on
#      failure, restores the previous release and both databases;
#   7. the running release is read back and a redacted evidence package is kept in
#      .git/divalhr-deploy-production/<run>/ and uploaded under evidence/<run>/.
# Configuration: ~/.divalhr-production/deploy.env (DIVALHR_PROD_REGION, DIVALHR_PROD_INSTANCE_ID,
# DIVALHR_PROD_EVIDENCE_BUCKET, DIVALHR_PROD_REGISTRY), kept outside Git. Never deploys a working tree.
set -u
export GIT_PAGER=cat PAGER=cat
die() { echo "STOP: $*" >&2; exit 1; }
SHA="${1:-}"; shift || true
EVIDENCE_RUN="" APPROVAL="" FIRST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --evidence-run) EVIDENCE_RUN="$2"; shift 2 ;;
    --approval) APPROVAL="$2"; shift 2 ;;
    --first-run) FIRST="--first-run"; shift ;;
    *) die "unknown option $1" ;;
  esac
done
ENV_FILE="${DIVALHR_PROD_ENV:-$HOME/.divalhr-production/deploy.env}"
[ -r "$ENV_FILE" ] || die "missing $ENV_FILE (kept outside Git)"
# shellcheck disable=SC1090
. "$ENV_FILE"
: "${DIVALHR_PROD_REGION:?}" "${DIVALHR_PROD_INSTANCE_ID:?}" "${DIVALHR_PROD_EVIDENCE_BUCKET:?}" "${DIVALHR_PROD_REGISTRY:?}"
REPO="${DIVALHR_GITHUB_REPO:-AlainCizungu/DivalHR}"
MAX_AGE_H="${PROD_EVIDENCE_MAX_AGE_HOURS:-72}"
AWS="${DIVALHR_AWS:-aws}"

case "$SHA" in *[!0-9a-f]*|'') die "give the full 40-character commit SHA" ;; esac
[ "${#SHA}" = 40 ] || die "give the full 40-character commit SHA"
[ -n "$EVIDENCE_RUN" ] && [ -d "$EVIDENCE_RUN" ] || die "--evidence-run <verification run directory> is required"
case "$APPROVAL" in ''|*[!A-Za-z0-9._#:/-]*) die "--approval <reference of the founder's written approval> is required" ;; esac
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "run from the DivalHR repository"
cd "$ROOT" || exit 1
[ -t 0 ] || die "the founder confirmation is interactive; run from a terminal"
command -v python3 >/dev/null || die "python3 is required"

# 1. on main
git fetch -q origin main || die "git fetch origin main failed"
git cat-file -e "$SHA^{commit}" 2>/dev/null || die "commit $SHA is not in this repository"
git merge-base --is-ancestor "$SHA" origin/main || die "$SHA is not reachable from origin/main"
echo "PASS $SHA is on origin/main"
RUN="$(date -u +%Y%m%dT%H%M%SZ)-${SHA:0:12}"
OUT="$ROOT/.git/divalhr-deploy-production/$RUN"
mkdir -p "$OUT"
TMP=$(mktemp -d) && trap 'rm -rf "$TMP"' EXIT

# 2. GitHub checks
git show "$SHA:ops/hr-dev/required-checks.txt" > "$TMP/required" 2>/dev/null || die "the commit has no required-checks.txt"
git show "$SHA:ops/hr-dev/provenance.py" > "$TMP/provenance.py" || die "the commit has no provenance.py"
fetch_checks() {
  local page=1 out="[" sep="" body
  while [ "$page" -le 10 ]; do
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
      body=$(gh api "repos/$REPO/commits/$SHA/check-runs?per_page=100&page=$page") || return 1
    else
      body=$(curl -fsS -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$REPO/commits/$SHA/check-runs?per_page=100&page=$page") || return 1
    fi
    out="$out$sep$body"; sep=","
    [ "$(printf '%s' "$body" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["check_runs"]))')" = 100 ] || break
    page=$((page + 1))
  done
  printf '%s]' "$out"
}
fetch_checks > "$TMP/checks.json" || die "cannot read the check runs of $SHA from GitHub"
python3 "$TMP/provenance.py" "$TMP/required" "$SHA" < "$TMP/checks.json" | tee "$OUT/provenance.txt"
[ "${PIPESTATUS[0]}" = 0 ] || die "GitHub checks are not all green for $SHA"

# 3. fresh, qualifying, FULL verification of this tree (the class is never narrowed to what changed)
git show "$SHA:ops/hr-dev/evidence.py" > "$TMP/evidence.py" || die "the commit has no evidence.py"
git update-ref refs/divalhr/deploy "$SHA" || exit 1
git bundle create "$TMP/release.bundle" refs/divalhr/deploy >/dev/null 2>&1; BRC=$?
git update-ref -d refs/divalhr/deploy
[ "$BRC" = 0 ] || die "bundle failed"
[ "$(git bundle list-heads "$TMP/release.bundle" | awk '{print $1}' | sort -u)" = "$SHA" ] || die "bundle head mismatch"
RUNS_PARENT="$(cd "$(dirname "$EVIDENCE_RUN")" && pwd)"
python3 "$TMP/evidence.py" decide --release "$SHA" --release-bundle "$TMP/release.bundle" --runs "$RUNS_PARENT" \
  | tee "$OUT/evidence-decision.txt"
[ "${PIPESTATUS[0]}" = 0 ] || die "no qualifying full verification of this tree (hr-dev evidence of another tree never counts)"
python3 - "$EVIDENCE_RUN/evidence.json" "$MAX_AGE_H" <<'PY' || die "the verification is older than $MAX_AGE_H hours"
import json, sys, datetime
rec = json.load(open(sys.argv[1], encoding="utf-8"))
done = datetime.datetime.strptime(rec["finishedAt"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
age = (datetime.datetime.now(datetime.timezone.utc) - done).total_seconds() / 3600
sys.exit(0 if age <= float(sys.argv[2]) else 1)
PY
echo "PASS the verification is fresh (at most $MAX_AGE_H h)"

# 4. images in the private registry
for svc in keycloak core-api ai-service web; do
  "$AWS" ecr describe-images --region "$DIVALHR_PROD_REGION" --repository-name "divalhr-prod/$svc" \
    --image-ids "imageTag=$SHA" >/dev/null 2>&1 || die "image divalhr-prod/$svc:${SHA:0:12} is not in the registry (run build-push.sh)"
done
echo "PASS the four images exist in the private registry"

# 5. founder approval
echo "Production deployment of $SHA (approval reference: $APPROVAL)."
printf 'Type exactly "DEPLOY PRODUCTION %s" to continue: ' "${SHA:0:12}"
read -r answer
[ "$answer" = "DEPLOY PRODUCTION ${SHA:0:12}" ] || die "not confirmed"
printf 'sha=%s\napproval=%s\nconfirmed=%s\n' "$SHA" "$APPROVAL" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OUT/approval.txt"

# 6. upload and run through Systems Manager (no SSH)
BSUM=$(shasum -a 256 "$TMP/release.bundle" | awk '{print $1}')
S3="s3://$DIVALHR_PROD_EVIDENCE_BUCKET"
"$AWS" s3 cp --region "$DIVALHR_PROD_REGION" --only-show-errors "$TMP/release.bundle" "$S3/releases/$SHA/release.bundle" || die "upload failed"
"$AWS" s3 cp --region "$DIVALHR_PROD_REGION" --only-show-errors --recursive "$EVIDENCE_RUN" "$S3/verification/$SHA/$(basename "$EVIDENCE_RUN")/" || die "evidence upload failed"
cp "$OUT/provenance.txt" "$OUT/evidence-decision.txt" "$OUT/approval.txt" "$TMP/" 2>/dev/null
"$AWS" s3 cp --region "$DIVALHR_PROD_REGION" --only-show-errors --recursive "$OUT" "$S3/evidence/$RUN/" || die "evidence upload failed"

COMMANDS=$(cat <<REMOTE
set -eu
D=/root/divalhr-deploy/$RUN; mkdir -p "\$D/runs"; chmod 700 /root/divalhr-deploy "\$D"
aws s3 cp --region $DIVALHR_PROD_REGION --only-show-errors $S3/releases/$SHA/release.bundle "\$D/release.bundle"
echo "$BSUM  \$D/release.bundle" | sha256sum -c - >/dev/null
aws s3 cp --region $DIVALHR_PROD_REGION --only-show-errors --recursive $S3/verification/$SHA/ "\$D/runs/"
T=\$(mktemp -d); git init -q --bare "\$T/repo"; git -C "\$T/repo" fetch -q "\$D/release.bundle" "+refs/*:refs/bundle/*"
mkdir "\$T/ops"; git -C "\$T/repo" archive $SHA ops | tar -x -C "\$T/ops"
PROD_VERIFY_RUNS="\$D/runs" "\$T/ops/ops/production/remote-deploy.sh" --bundle "\$D/release.bundle" --sha $SHA $FIRST 2>&1 | tee "\$D/deploy.log"
rc=\${PIPESTATUS[0]}; rm -rf "\$T"; echo "exit=\$rc"; exit \$rc
REMOTE
)
ssm_run() { # <commands> -> command id
  python3 - "$1" > "$TMP/params.json" <<'PY'
import json, sys
print(json.dumps({"commands": sys.argv[1].split("\n"), "executionTimeout": ["3600"]}))
PY
  "$AWS" ssm send-command --region "$DIVALHR_PROD_REGION" --instance-ids "$DIVALHR_PROD_INSTANCE_ID" \
    --document-name AWS-RunShellScript --parameters "file://$TMP/params.json" --query Command.CommandId --output text
}
ssm_wait() { # <command id> <outfile> -> status
  local id="$1" status=""
  while :; do
    sleep 15
    status=$("$AWS" ssm get-command-invocation --region "$DIVALHR_PROD_REGION" --command-id "$id" \
      --instance-id "$DIVALHR_PROD_INSTANCE_ID" --query Status --output text 2>/dev/null) || status=Pending
    case "$status" in Success|Failed|Cancelled|TimedOut) break ;; esac
  done
  "$AWS" ssm get-command-invocation --region "$DIVALHR_PROD_REGION" --command-id "$id" \
    --instance-id "$DIVALHR_PROD_INSTANCE_ID" --query StandardOutputContent --output text > "$2"
  printf '%s' "$status"
}
CID=$(ssm_run "$COMMANDS") || die "could not start the deployment through Systems Manager"
echo "deployment of ${SHA:0:12} started (command $CID); waiting"
STATUS=$(ssm_wait "$CID" "$OUT/deploy.log")
sed 's/^/  /' "$OUT/deploy.log"

# 7. read back and collect the redacted host evidence
RUNNING_CID=$(ssm_run 'cat /srv/divalhr-prod/state/current-release; /srv/divalhr-prod/current/ops/production/evidence-host.sh || true') || die "evidence command failed"
ssm_wait "$RUNNING_CID" "$OUT/evidence-host.txt" >/dev/null
RUNNING=$(head -n 1 "$OUT/evidence-host.txt" | tr -d '[:space:]')
sed -n '/== checks/,$p' "$OUT/evidence-host.txt"
"$AWS" s3 cp --region "$DIVALHR_PROD_REGION" --only-show-errors --recursive "$OUT" "$S3/evidence/$RUN/" >/dev/null
if [ "$STATUS" = Success ] && [ "$RUNNING" = "$SHA" ]; then
  echo "DEPLOYED $SHA to production (evidence: $OUT). The launch lock is unchanged."
  exit 0
fi
echo "DEPLOYMENT FAILED (status $STATUS); running release is ${RUNNING:-none}. Evidence: $OUT"
exit 1
