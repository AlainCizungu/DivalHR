#!/bin/bash
# Runs scripts/dev/verify-on-host.sh on the AWS dev instance instead of the Mac.
#
# Run on the Mac, from the DivalHR repository root:
#   bash ops/aws/aws-verify.sh [ref] [profile]  verify a committed ref (default HEAD) with a profile
#                                              (DEVX-001A): changed, pr (core + stack) or full
#                                              (core + stack + hrdev, recorded as `all`), or a
#                                              stage: all, core, stack or hrdev
#   bash ops/aws/aws-verify.sh --attach        follow the latest run again (after Ctrl-C or a dropped connection)
#
# Exit 0 only when every stage passed. Only committed code is verified: the ref is sent as a git
# bundle (no GitHub access or credentials on the instance). The run is detached on the instance, so
# closing the Mac lid or losing Wi-Fi does not stop it. OPS-001 (A65-5): every run holds the
# host-wide lock /run/lock/divalhr-host.lock (ops/host/host-lock.sh) for its whole duration, so it
# never overlaps a test-environment deploy, backup, rollback, restore drill or watchdog restart;
# a busy lock ends the run at once with exit 75 and nothing changed.
# Logs are copied back to .git/divalhr-verify-aws/<run>/ on the Mac.
# Overrides: DIVALHR_AWS_HOST (default ubuntu@hr-dev.dival.ai), DIVALHR_AWS_KEY (default ~/.ssh/divalhr-dev.pem).
set -u
export GIT_PAGER=cat PAGER=cat LESS=FRX
HOST="${DIVALHR_AWS_HOST:-ubuntu@hr-dev.dival.ai}"
KEY="${DIVALHR_AWS_KEY:-$HOME/.ssh/divalhr-dev.pem}"
SSHO="-i $KEY -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=4"
POLL=30

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "STOP: run from the DivalHR repository" >&2; exit 1; }
[ -r "$KEY" ] || { echo "STOP: key file not readable: $KEY" >&2; exit 1; }
rsh() { ssh $SSHO "$HOST" "$@"; }

if [ "${1:-}" = "--attach" ]; then
  RUN=$(rsh 'ls -1t "$HOME/divalhr-runs" | grep -v "^\." | head -1') || exit 1
  [ -n "$RUN" ] || { echo "STOP: no run found" >&2; exit 1; }
  STAGE=$(rsh "cat \"\$HOME/divalhr-runs/$RUN/stage\"") || exit 1
else
  REF="${1:-HEAD}"
  STAGE="${2:-all}"
  case "$STAGE" in
    all|core|stack|hrdev|changed|pr) ;;
    # `full` is recorded as `all`, the complete run the deployment gate recognises.
    full) STAGE=all ;;
    *) echo "STOP: profile must be changed, pr or full (stages: all, core, stack, hrdev)" >&2; exit 2 ;;
  esac
  SHA=$(git rev-parse --verify "$REF^{commit}") || { echo "STOP: unknown ref $REF" >&2; exit 1; }
  # The `changed` profile compares with the merge-base of origin/main (absent: it widens to `pr`).
  BASE=""
  [ "$STAGE" = changed ] && BASE=$(git merge-base origin/main "$SHA" 2>/dev/null || true)
  [ -n "$(git --no-pager status --porcelain --untracked-files=no)" ] && \
    echo "NOTE: uncommitted changes are NOT verified; only $SHA is."
  RUN="$(date -u +%Y%m%dT%H%M%SZ)-${SHA:0:12}"
  TMP=$(mktemp -d) && trap 'rm -rf "$TMP"' EXIT
  # A private ref (not a branch) names the commit inside the bundle; it is removed right after.
  git update-ref refs/divalhr/aws-verify "$SHA" || exit 1
  git bundle create "$TMP/verify.bundle" refs/divalhr/aws-verify >/dev/null 2>&1
  BRC=$?
  git update-ref -d refs/divalhr/aws-verify
  [ "$BRC" = 0 ] || { echo "STOP: bundle failed" >&2; exit 1; }
  # Refuse any machine that was not prepared by aws-dev-setup.sh (for example the DIP instance).
  rsh 'test -d "$HOME/DivalHR/.git"' || {
    echo "STOP: $HOST is not reachable or not set up for DivalHR (ops/aws/aws-dev-setup.sh install)" >&2; exit 1; }
  echo "Target: $HOST"
  rsh "mkdir -p \"\$HOME/divalhr-runs/$RUN\"" || { echo "STOP: cannot reach $HOST" >&2; exit 1; }
  scp -q $SSHO "$TMP/verify.bundle" "$HOST:divalhr-runs/$RUN/verify.bundle" || { echo "STOP: upload failed" >&2; exit 1; }

  # Start the run detached on the instance.
  rsh "bash -s -- '$RUN' '$SHA' '$STAGE' '$BASE'" <<'REMOTE' || { echo "STOP: could not start the run" >&2; exit 1; }
set -u
RUN="$1"; SHA="$2"; STAGE="$3"; BASE="${4:-}"; D="$HOME/divalhr-runs/$RUN"
echo "$STAGE" > "$D/stage"
cat > "$D/run.sh" <<EOF
set -u
L=/run/lock/divalhr-host.lock
if [ -e "\$L" ]; then
  exec 9<>"\$L" || { echo "cannot open the host lock (is this user in divalhr-ops?)"; echo 77 > "$D/exit"; exit 77; }
  if ! flock -n 9; then echo "another DivalHR operation holds the host lock: \$(head -c 200 "\$L" | tr -cd '[:print:] ')"; echo 75 > "$D/exit"; exit 75; fi
  printf 'aws-verify %s by %s pid %s since %s\\n' "${SHA:0:12}" "\$(id -un)" "\$\$" "\$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "\$L"
else
  echo "NOTE: host lock not provisioned yet (aws-dev-setup.sh install); using the legacy run lock"
  exec 9> "\$HOME/divalhr-runs/.lock"
  if ! flock -n 9; then echo "another DivalHR run is in progress"; echo 75 > "$D/exit"; exit 75; fi
fi
export DIVALHR_HOST_LOCK_HELD=1
cd "\$HOME/DivalHR" || { echo 1 > "$D/exit"; exit 1; }
git fetch -q "$D/verify.bundle" refs/divalhr/aws-verify && git checkout -q --force --detach FETCH_HEAD && git clean -fdq \
  || { echo "checkout failed"; echo 1 > "$D/exit"; exit 1; }
[ "\$(git rev-parse HEAD)" = "$SHA" ] || { echo "head mismatch"; echo 1 > "$D/exit"; exit 1; }
for p in 5173 8080 8090 8180 8025; do
  if ss -Hltn "sport = :\$p" | grep -q . && ! docker ps --filter label=com.docker.compose.project=divalhr --format '{{.Ports}}' | grep -q ":\$p->"; then
    echo "port \$p is in use by another service"; echo 1 > "$D/exit"; exit 1
  fi
done
rm -rf .git/divalhr-verify
touch "$D/started"
DIVALHR_VERIFY_BASE="$BASE" scripts/dev/verify-on-host.sh "$STAGE"
rc=\$?
docker image prune -f --filter label=com.docker.compose.project=divalhr >/dev/null 2>&1
echo "disk after run: \$(df -h / | tail -1)"
cp -R .git/divalhr-verify "$D/logs" 2>/dev/null
echo \$rc > "$D/exit"
EOF
setsid nohup bash "$D/run.sh" > "$D/run.log" 2>&1 < /dev/null &
echo "started run $RUN on $(hostname) for ${SHA:0:12}, stage $STAGE"
REMOTE
fi

echo "Following run $RUN (Ctrl-C stops following, not the run; resume with: bash ops/aws/aws-verify.sh --attach)"
SEEN=0
while :; do
  OUT=$(rsh "D=\$HOME/divalhr-runs/$RUN; S=\$HOME/DivalHR/.git/divalhr-verify/$STAGE.summary; [ -f \"\$D/logs/$STAGE.summary\" ] && S=\"\$D/logs/$STAGE.summary\"; [ -f \"\$D/started\" ] && cat \"\$S\" 2>/dev/null; echo \"@@EXIT \$(cat \"\$D/exit\" 2>/dev/null)\"" 2>/dev/null)
  if [ -z "$OUT" ]; then echo "  (instance unreachable, retrying)"; sleep "$POLL"; continue; fi
  LINES=$(printf '%s\n' "$OUT" | grep -v '^@@EXIT')
  N=$(printf '%s\n' "$LINES" | grep -c .)
  [ "$N" -gt "$SEEN" ] && printf '%s\n' "$LINES" | sed -n "$((SEEN + 1)),\$p" | sed 's/^/  /' && SEEN=$N
  RC=$(printf '%s\n' "$OUT" | sed -n 's/^@@EXIT //p')
  [ -n "$RC" ] && break
  sleep "$POLL"
done

DEST="$ROOT/.git/divalhr-verify-aws/$RUN"
mkdir -p "$DEST"
scp -q -r $SSHO "$HOST:divalhr-runs/$RUN/run.log" "$HOST:divalhr-runs/$RUN/logs" "$DEST/" 2>/dev/null
echo ""
tail -3 "$DEST/run.log" 2>/dev/null | grep -E 'disk after run|another|mismatch|in use|failed' | sed 's/^/  /'
echo "Logs: $DEST"
if [ "$RC" = 0 ]; then
  echo "AWS verification PASSED for run $RUN."
  exit 0
fi
echo "AWS verification FAILED (exit $RC) for run $RUN. Nothing was pushed. Send the output above to Claude."
exit 1
