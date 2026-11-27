#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048): read-only host evidence, safe to post: listening sockets, containers,
# exposure checks and the launch-lock state. Addresses and AWS identifiers are redacted.
#   sudo ops/production/evidence-host.sh
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
divalhr_lock "host evidence" || exit $?
FAIL=0
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAIL=1; }
redact() { sed -E 's/\b(10|172|192)\.[0-9]+\.[0-9]+\.[0-9]+\b/<host-address>/g; s/\b(i|vol|snap|sg|subnet|vpc|eni)-[0-9a-f]{8,}\b/<aws-id>/g; s/\b[0-9]{12}\b/<account>/g'; }
INTERNAL_PORTS="22 80 5432 5173 8025 8080 8081 8090 8180 9000 18180 25"

echo "== LAUNCH-002 production host evidence ($(date -u +%Y-%m-%dT%H:%M:%SZ)), release $(hr_current_release | cut -c1-12)"
echo "== ss -lntup"
ss -Hlntup | awk '{print $1, $5, $7}' | sed -E 's/users:\(\("([^"]+)".*/\1/' | sort -u | redact
echo "== docker ps"
docker ps --format '{{.Names}} | {{.Status}} | {{.Ports}}' | sort | redact

echo "== checks"
nonloop=$(ss -Hlntu | awk '{print $5}' | grep -vE '^(127\.[0-9.]+|\[::1\]|\[::ffff:127\.[0-9.]+\]):[0-9]+$' | sort -u)
for p in $INTERNAL_PORTS; do
  if printf '%s\n' "$nonloop" | grep -qE ":$p$"; then fail "port $p listens on a non-loopback address"; else pass "port $p not on a non-loopback address"; fi
done
others=$(printf '%s\n' "$nonloop" | grep -vE ':8443$' | grep -vE ':(53|68|123|323|546)$' || true)
[ -z "$others" ] && pass "only 8443 listens on a non-loopback address (plus system DNS/DHCP/NTP): no SSH, no HTTP" \
  || fail "other non-loopback listeners: $(printf '%s' "$others" | redact | tr '\n' ' ')"
if docker ps --format '{{.Names}}|{{.Ports}}' | while IFS='|' read -r name ports; do
  printf '%s\n' "$ports" | tr ',' '\n' | grep -E '(0\.0\.0\.0|\[::\]|:::)' | sed "s/^ *//; s/^/$name /"
done | grep -vE "^$PROD_PROJECT-caddy-1 (0\.0\.0\.0|\[::\]|::):8443->8443/tcp$" | grep -q .; then
  fail "a container other than Caddy publishes on a non-loopback address"
else
  pass "only $PROD_PROJECT-caddy-1 publishes on non-loopback addresses (8443)"
fi
for svc in mailpit meta-provider payment-provider partner-receiver webhook-receiver; do
  docker ps --format '{{.Names}}' | grep -q "$svc" && fail "test service $svc is running in production" || pass "no $svc container"
done

echo "== data volume"
findmnt -no SOURCE,TARGET,FSTYPE,OPTIONS "$PROD_DATA" | redact
( hr_require_encrypted_data_root ) 2>/dev/null && pass "data root is the confirmed encrypted volume" || fail "data root check"
docker volume ls -q --filter "label=com.docker.compose.project=$PROD_PROJECT" | grep -q . \
  && fail "the project has Docker named volumes" || pass "no Docker named volumes"
echo "== secrets (names and modes only)"
find "$PROD_DATA/secrets" -type f -printf '%m %u %P\n' 2>/dev/null | sort -k3 | while read -r mode owner name; do
  case "$mode" in 400|440) ;; *) echo "FAIL secret file mode $mode: $name" ;; esac
done | tee /tmp/.prod-secret-modes.$$ | sed 's/^/  /'
grep -q '^FAIL' /tmp/.prod-secret-modes.$$ && fail "a secret file is not read-only" || pass "every secret file is 0400/0440"
rm -f /tmp/.prod-secret-modes.$$
echo "== timers"
for t in divalhr-prod-watchdog.timer divalhr-prod-backup.timer divalhr-prod-metrics.timer; do
  [ "$(systemctl is-active "$t" 2>/dev/null)" = active ] && pass "$t active" || fail "$t not active"
done
echo "== launch lock"
echo "launch-lock=$(prod_launch_lock)"
[ "$FAIL" = 0 ] && echo "ALL HOST CHECKS PASSED" || echo "HOST CHECKS: see FAIL lines"
exit "$FAIL"
