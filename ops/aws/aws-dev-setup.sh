#!/bin/bash
# Prepares the DivalHR AWS dev instance (hr-dev.dival.ai, not the DIP instance) to run DivalHR builds and the host
# verification (scripts/dev/verify-on-host.sh), so the Mac no longer runs Gradle, Docker or Playwright.
#
# Run on the Mac, from anywhere:
#   bash aws-dev-setup.sh check     read-only report: CPU, memory, disk, tools, ports, DIP containers
#   bash aws-dev-setup.sh install   installs Docker, compose, buildx, JDK 21, Node/npm, Playwright
#                                   system libraries; creates ~/DivalHR (empty git repo) and ~/divalhr-runs;
#                                   then the host lock (below)
#   bash aws-dev-setup.sh host-lock OPS-001 (A65-5): group divalhr-ops (with this user), and
#                                   /run/lock/divalhr-host.lock (root:divalhr-ops 0660) recreated at
#                                   every boot by systemd-tmpfiles from ops/host/tmpfiles-divalhr.conf
#
# Nothing is cloned from GitHub and no GitHub credential is placed on the instance: code reaches the
# instance only as git bundles sent by aws-verify.sh. No secret is written anywhere.
# Overrides: DIVALHR_AWS_HOST (default ubuntu@hr-dev.dival.ai), DIVALHR_AWS_KEY (default ~/.ssh/divalhr-dev.pem),
#            ALLOW_LOW_DISK=1 to install with less than 20 GB free.
set -u
HOST="${DIVALHR_AWS_HOST:-ubuntu@hr-dev.dival.ai}"
KEY="${DIVALHR_AWS_KEY:-$HOME/.ssh/divalhr-dev.pem}"
MODE="${1:-check}"
SSH="ssh -i $KEY -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=4"

[ -r "$KEY" ] || { echo "STOP: key file not readable: $KEY" >&2; exit 1; }

check() {
  $SSH "$HOST" 'bash -s' <<'REMOTE'
set -u
echo "== host";      . /etc/os-release; echo "$PRETTY_NAME, kernel $(uname -r), $(nproc) vCPU"
echo "== memory";    free -h | sed -n '1,3p'
echo "== disk /";    df -h / | tail -1
FREE_GB=$(df -BG --output=avail / | tail -1 | tr -dc 0-9)
echo "== tools"
for c in docker java node npm git curl shasum; do
  if command -v "$c" >/dev/null 2>&1; then echo "  $c: $(command -v $c)"; else echo "  $c: MISSING"; fi
done
command -v docker >/dev/null 2>&1 && {
  echo "  docker server: $(docker version --format '{{.Server.Version}}' 2>&1 | head -1)"
  echo "  compose: $(docker compose version --short 2>&1 | head -1)"
  echo "  buildx: $(docker buildx version 2>&1 | head -1)"
  echo "  docker group: $(id -nG | tr ' ' '\n' | grep -qx docker && echo yes || echo no)"
}
command -v java >/dev/null 2>&1 && echo "  java: $(java -version 2>&1 | head -1)"
command -v node >/dev/null 2>&1 && echo "  node: $(node -v)"
echo "== containers (all projects, names and status only)"
if command -v docker >/dev/null 2>&1; then
  (docker ps --format '  {{.Names}}  {{.Status}}  {{.Ports}}' 2>/dev/null || sudo -n docker ps --format '  {{.Names}}  {{.Status}}  {{.Ports}}' 2>/dev/null) | head -30
  echo "== docker disk"; (docker system df 2>/dev/null || sudo -n docker system df 2>/dev/null)
fi
echo "== DivalHR host ports (must be free or held by the divalhr compose project)"
BUSY=0
for p in 5173 8080 8090 8180 8025; do
  line=$(ss -Hltn "sport = :$p" 2>/dev/null | head -1)
  if [ -n "$line" ]; then echo "  $p: IN USE"; BUSY=1; else echo "  $p: free"; fi
done
echo "== host lock (OPS-001)"; ls -l /run/lock/divalhr-host.lock 2>/dev/null | awk '{print "  " $1, $3, $4}' || echo "  not provisioned"
echo "== reboot";    [ -f /var/run/reboot-required ] && echo "  reboot required (pending kernel or library updates)" || echo "  not required"
echo "== verdict"
[ "$FREE_GB" -lt 20 ] && echo "  DISK: only ${FREE_GB} GB free; DivalHR needs about 12-15 GB (images, Gradle, node_modules, Chromium). Grow the EBS volume to 60 GB (see notes) before install, or set ALLOW_LOW_DISK=1."
[ "$FREE_GB" -ge 20 ] && echo "  DISK: ok (${FREE_GB} GB free)"
MEM_MB=$(free -m | awk '/^Mem:/ {print $2}')
[ "$MEM_MB" -lt 7500 ] && echo "  MEMORY: ${MEM_MB} MB total; the full stack plus Gradle tests wants about 8 GB on top of DIP. Expect slow or OOM-killed runs; consider a larger instance type."
[ "$MEM_MB" -ge 7500 ] && echo "  MEMORY: ${MEM_MB} MB total"
[ "$BUSY" = 1 ] && echo "  PORTS: a DivalHR port is in use (probably DIP). The stack stage cannot run while it is; tell Claude which port and service."
[ "$BUSY" = 0 ] && echo "  PORTS: ok"
echo "FREE_GB=$FREE_GB"
REMOTE
}

install() {
  local report free
  report=$(check) || { echo "$report"; echo "STOP: cannot reach $HOST" >&2; exit 1; }
  echo "$report"
  free=$(printf '%s\n' "$report" | sed -n 's/^FREE_GB=//p')
  if [ "${free:-0}" -lt 20 ] && [ "${ALLOW_LOW_DISK:-0}" != 1 ]; then
    echo "STOP: less than 20 GB free. Grow the volume first, or rerun with ALLOW_LOW_DISK=1." >&2; exit 1
  fi
  echo ""; echo "== installing (sudo apt on $HOST)"
  $SSH "$HOST" 'bash -s' <<'REMOTE' || { echo "STOP: install failed (output above)" >&2; exit 1; }
set -eu
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -q
PKGS="git curl ca-certificates perl"
command -v docker >/dev/null 2>&1 || PKGS="$PKGS docker.io"
if apt-cache show openjdk-21-jdk-headless >/dev/null 2>&1; then PKGS="$PKGS openjdk-21-jdk-headless"
else PKGS="$PKGS default-jdk-headless"; fi
command -v npm >/dev/null 2>&1 || PKGS="$PKGS nodejs npm"
sudo apt-get install -y -q $PKGS
# Compose v2 and buildx only when the installed Docker lacks them (DIP may use Docker CE packages).
docker compose version >/dev/null 2>&1 || sudo docker compose version >/dev/null 2>&1 || sudo apt-get install -y -q docker-compose-v2
docker buildx version >/dev/null 2>&1 || sudo docker buildx version >/dev/null 2>&1 || sudo apt-get install -y -q docker-buildx
sudo systemctl enable --now docker
id -nG | tr ' ' '\n' | grep -qx docker || sudo usermod -aG docker "$USER"
# Playwright 1.63.0 system libraries for Chromium (the browser itself is installed per run, unprivileged).
sudo npx --yes playwright@1.63.0 install-deps chromium || echo "WARN: playwright install-deps failed; the e2e stage may fail on missing libraries"
mkdir -p "$HOME/divalhr-runs"
[ -d "$HOME/DivalHR/.git" ] || git init -q "$HOME/DivalHR"
git -C "$HOME/DivalHR" config advice.detachedHead false
echo "install done"
REMOTE
  echo ""; echo "== after install (new session, docker group applied)"
  $SSH "$HOST" 'docker version --format "docker {{.Server.Version}}" && docker compose version --short && java -version 2>&1 | head -1 && node -v && df -h / | tail -1' \
    || { echo "STOP: post-install check failed" >&2; exit 1; }
  echo ""
  echo "Setup complete. Next: from the DivalHR repo on the Mac, run  bash aws-verify.sh  to verify HEAD on the instance."
}

host_lock() {
  local conf
  conf="$(cd "$(dirname "$0")/../host" && pwd)/tmpfiles-divalhr.conf"
  [ -r "$conf" ] || { echo "STOP: $conf not found (run from the repository)" >&2; exit 1; }
  $SSH "$HOST" 'bash -s' < <(cat <<REMOTE
set -eu
getent group divalhr-ops >/dev/null || sudo groupadd --system divalhr-ops
id -nG | tr ' ' '\n' | grep -qx divalhr-ops || sudo usermod -aG divalhr-ops "\$USER"
sudo tee /etc/tmpfiles.d/divalhr.conf >/dev/null <<'CONF'
$(cat "$conf")
CONF
sudo systemd-tmpfiles --create /etc/tmpfiles.d/divalhr.conf
command -v flock >/dev/null || { echo "flock missing"; exit 1; }
ls -l /run/lock/divalhr-host.lock | awk '{print "host lock:", \$1, \$3, \$4}'
REMOTE
) || { echo "STOP: host lock setup failed" >&2; exit 1; }
  echo "Host lock ready. The group applies to new SSH sessions."
}

case "$MODE" in
  check) check ;;
  install) install; host_lock ;;
  host-lock) host_lock ;;
  *) echo "usage: bash ops/aws/aws-dev-setup.sh check|install|host-lock" >&2; exit 2 ;;
esac
