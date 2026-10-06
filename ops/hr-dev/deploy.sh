#!/bin/bash
# OPS-001 (section 10, A65-7): deploys one commit of main to the test environment, from the
# owner's Mac. Fails closed: nothing reaches the instance unless every check passes.
#
#   bash ops/hr-dev/deploy.sh <full commit sha> [--first-run]
#
# Provenance, in this order:
#   1. the SHA is a full 40-character commit SHA that exists locally after `git fetch origin`;
#   2. it is reachable from origin/main (merged, reviewed code only);
#   3. every GitHub check run on that SHA is green and every required check (required-checks.txt
#      as of that SHA) succeeded (provenance.py; GitHub's public API, or `gh api` when present);
#   4. the instance holds a successful, complete aws-verify run (stage all, including the
#      hr-dev rehearsal) for exactly that SHA (checked again by remote-deploy.sh on the instance);
#   5. exactly that SHA is shipped: a bundle whose only head is the SHA, unpacked with git archive,
#      images tagged with the full SHA, and the running release read back afterwards.
# Evidence (redacted) is kept in .git/divalhr-deploy/<run>/: inventory before and after and their
# diff, host evidence, the provenance decisions and the remote log.
# Overrides: DIVALHR_AWS_HOST (default ubuntu@hr-dev.dival.ai), DIVALHR_AWS_KEY (default
# ~/.ssh/divalhr-dev.pem), DIVALHR_GITHUB_REPO (default AlainCizungu/DivalHR).
set -u
export GIT_PAGER=cat PAGER=cat
HOST="${DIVALHR_AWS_HOST:-ubuntu@hr-dev.dival.ai}"
KEY="${DIVALHR_AWS_KEY:-$HOME/.ssh/divalhr-dev.pem}"
REPO="${DIVALHR_GITHUB_REPO:-AlainCizungu/DivalHR}"
SSHO="-i $KEY -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=4"
SHA="${1:-}"
FIRST_RUN="${2:-}"
die() { echo "STOP: $*" >&2; exit 1; }
rsh() { ssh $SSHO "$HOST" "$@"; }
# shellcheck source=ops/hr-dev/deploy-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/deploy-lib.sh" || die "deploy-lib.sh is missing"

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die "run from the DivalHR repository"
cd "$ROOT" || exit 1
[ -r "$KEY" ] || die "key file not readable: $KEY"
case "$FIRST_RUN" in ''|--first-run) ;; *) die "unknown option $FIRST_RUN" ;; esac
command -v python3 >/dev/null 2>&1 || die "python3 is required"

# 1. full SHA, present after a fetch
case "$SHA" in *[!0-9a-f]*|'') die "give the full 40-character commit SHA" ;; esac
[ "${#SHA}" = 40 ] || die "give the full 40-character commit SHA"
git fetch -q origin main || die "git fetch origin main failed"
git cat-file -e "$SHA^{commit}" 2>/dev/null || die "commit $SHA is not in this repository"

# 2. on main
git merge-base --is-ancestor "$SHA" origin/main || die "$SHA is not reachable from origin/main"
echo "PASS $SHA is on origin/main"

RUN="$(date -u +%Y%m%dT%H%M%SZ)-${SHA:0:12}"
OUT="$ROOT/.git/divalhr-deploy/$RUN"
mkdir -p "$OUT"
TMP=$(mktemp -d) && trap 'rm -rf "$TMP"' EXIT

# 3. GitHub checks for exactly this SHA
git show "$SHA:ops/hr-dev/required-checks.txt" > "$TMP/required" 2>/dev/null || die "the commit has no required-checks.txt"
git show "$SHA:ops/hr-dev/provenance.py" > "$TMP/provenance.py" || die "the commit has no provenance.py"
fetch_checks() {
  local page=1 out="[" sep=""
  while [ "$page" -le 10 ]; do
    local body
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
      body=$(gh api "repos/$REPO/commits/$SHA/check-runs?per_page=100&page=$page") || return 1
    else
      body=$(curl -fsS -H 'Accept: application/vnd.github+json' \
        "https://api.github.com/repos/$REPO/commits/$SHA/check-runs?per_page=100&page=$page") || return 1
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

# 4 and 5. bundle with the SHA as its only head; the instance checks its verification evidence
git update-ref refs/divalhr/deploy "$SHA" || exit 1
git bundle create "$TMP/release.bundle" refs/divalhr/deploy >/dev/null 2>&1; BRC=$?
git update-ref -d refs/divalhr/deploy
[ "$BRC" = 0 ] || die "bundle failed"
[ "$(git bundle list-heads "$TMP/release.bundle" | awk '{print $1}' | sort -u)" = "$SHA" ] || die "bundle head mismatch"

echo "== inventory before (redacted)"
git show "$SHA:ops/hr-dev/inventory-host.sh" | rsh 'sudo bash -s' > "$OUT/inventory-before.txt" 2>&1 \
  || die "cannot reach $HOST"
echo "saved $OUT/inventory-before.txt"

rsh "mkdir -p \"\$HOME/divalhr-deploy/$RUN\"" || die "cannot reach $HOST"
scp -q $SSHO "$TMP/release.bundle" "$HOST:divalhr-deploy/$RUN/release.bundle" || die "upload failed"

rsh "bash -s -- '$RUN' '$SHA' '$FIRST_RUN'" <<'REMOTE' || die "could not start the deployment"
set -u
RUN="$1"; SHA="$2"; FIRST="$3"; D="$HOME/divalhr-deploy/$RUN"
cat > "$D/run.sh" <<EOF
set -u
T=\$(mktemp -d)
git init -q --bare "\$T/repo" && git -C "\$T/repo" fetch -q "$D/release.bundle" "+refs/*:refs/bundle/*" \
  && mkdir "\$T/ops" && git -C "\$T/repo" archive "$SHA" ops | tar -x -C "\$T/ops" \
  || { echo "cannot unpack the deployment scripts"; echo 1 > "$D/exit"; exit 1; }
sudo -n "\$T/ops/ops/hr-dev/remote-deploy.sh" --bundle "$D/release.bundle" --sha "$SHA" $FIRST
rc=\$?
rm -rf "\$T"
echo \$rc > "$D/exit"
EOF
setsid nohup bash "$D/run.sh" > "$D/deploy.log" 2>&1 < /dev/null &
echo "deployment of ${SHA:0:12} started on the instance"
REMOTE

echo "Following the deployment (Ctrl-C stops following, not the deployment)"
SEEN=0
while :; do
  sleep 15
  LOG=$(rsh "$(hr_deploy_log_command "$RUN")" 2>/dev/null) || { echo "  (instance unreachable, retrying)"; continue; }
  LINES=$(printf '%s\n' "$LOG" | grep -v '^@@EXIT')
  N=$(hr_log_line_count "$LINES")
  if [ "$N" -gt "$SEEN" ]; then hr_log_new_lines "$LINES" "$SEEN"; SEEN=$N; fi
  RC=$(printf '%s\n' "$LOG" | sed -n 's/^@@EXIT //p')
  [ -n "$RC" ] && break
done
printf '%s\n' "$LINES" > "$OUT/deploy.log"

echo "== inventory after (redacted) and comparison"
git show "$SHA:ops/hr-dev/inventory-host.sh" | rsh 'sudo bash -s' > "$OUT/inventory-after.txt" 2>&1
diff -u "$OUT/inventory-before.txt" "$OUT/inventory-after.txt" > "$OUT/inventory.diff"
echo "saved $OUT/inventory.diff ($(grep -c '^[-+][^-+]' "$OUT/inventory.diff") changed lines)"
hr_deploy_finish "$RC" "$SHA" "$OUT"
