#!/usr/bin/env bash
# OPS-001 (A65-1, A65-4): read-only host evidence for the review, safe to post on GitHub.
#
#   sudo ops/hr-dev/evidence-host.sh
#
# Prints the listening sockets (ss -lntup), the containers and their published ports
# (docker ps --format), the data volume mount and the systemd timers, then checks that only
# Caddy (through Docker) and SSH listen on non-loopback addresses and that none of the internal
# ports is reachable from outside the host. Takes the host lock, so no verification run or
# deployment is half-way through while the snapshot is taken. Host addresses other than the
# wildcard and loopback are printed as <host-address>.
set -u
. "$(dirname "$0")/lib.sh"
hr_require_root
divalhr_lock "host evidence" || exit $?
FAIL=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAIL=1; }
redact() {
  sed -E 's/\b(10|172|192)\.[0-9]+\.[0-9]+\.[0-9]+\b/<host-address>/g; s/\b(i|vol)-[0-9a-f]{8,}\b/<aws-id>/g'
}
INTERNAL_PORTS="5173 5432 8025 8080 8081 8090 8180 9000 18180 18025"

echo "== OPS-001 host evidence ($(date -u +%Y-%m-%dT%H:%M:%SZ)), release $(hr_current_release | cut -c1-12)"
echo "== ss -lntup"
ss -Hlntup | awk '{print $1, $5, $7}' | sed -E 's/users:\(\("([^"]+)".*/\1/' | sort -u | redact

echo "== docker ps --format '{{.Names}} {{.Status}} {{.Ports}}'"
docker ps --format '{{.Names}} | {{.Status}} | {{.Ports}}' | sort | redact

echo "== checks"
nonloop=$(ss -Hlntu | awk '{print $5}' | grep -vE '^(127\.[0-9.]+|\[::1\]|\[::ffff:127\.[0-9.]+\]):[0-9]+$' | sort -u)
for p in $INTERNAL_PORTS; do
  if printf '%s\n' "$nonloop" | grep -qE ":$p$"; then fail "port $p listens on a non-loopback address"; else pass "port $p not on a non-loopback address"; fi
done
others=$(printf '%s\n' "$nonloop" | grep -vE ':(22|80|443)$' | grep -vE ':(53|68|323|546)$' || true)
[ -z "$others" ] && pass "only 22, 80 and 443 listen on non-loopback TCP/UDP addresses (plus system DNS/DHCP/NTP)" \
  || fail "other non-loopback listeners: $(printf '%s' "$others" | redact | tr '\n' ' ')"
# Every non-loopback publication, one per line with its container name.
if docker ps --format '{{.Names}}|{{.Ports}}' | while IFS='|' read -r name ports; do
  printf '%s\n' "$ports" | tr ',' '\n' | grep -E '(0\.0\.0\.0|\[::\]|:::)' | sed "s/^ *//; s/^/$name /"
done | grep -vE "^$HR_DEV_PROJECT-caddy-1 (0\.0\.0\.0|\[::\]|::):(80|443)->(80|443)/tcp$" | grep -q .; then
  fail "a container other than Caddy publishes on a non-loopback address"
else
  pass "only $HR_DEV_PROJECT-caddy-1 publishes on non-loopback addresses (80, 443)"
fi

echo "== data volume (A65-1)"
findmnt -no SOURCE,TARGET,FSTYPE,OPTIONS "$HR_DEV_DATA" | redact
if ( hr_require_encrypted_data_root ) 2>/dev/null; then pass "data root is the confirmed encrypted volume"; else fail "data root check"; fi
docker volume ls -q --filter "label=com.docker.compose.project=$HR_DEV_PROJECT" | grep -q . \
  && fail "the project has Docker named volumes" || pass "no Docker named volumes in $HR_DEV_PROJECT"

echo "== timers"
systemctl list-timers --all --no-legend 'divalhr-hrdev-*' 2>/dev/null | awk '{print $(NF-1), $NF}'
for t in divalhr-hrdev-watchdog.timer divalhr-hrdev-backup.timer; do
  [ "$(systemctl is-active "$t" 2>/dev/null)" = active ] && pass "$t active" || fail "$t not active"
done
echo "== lock"
ls -l "$DIVALHR_HOST_LOCK" | awk '{print $1, $3, $4, $NF}'
[ "$FAIL" = 0 ] && echo "ALL HOST CHECKS PASSED" || echo "HOST CHECKS: see FAIL lines"
exit "$FAIL"
