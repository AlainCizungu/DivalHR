#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048): HTTP-level checks of production through the HOST'S OWN listener (8443, the
# way the load balancer reaches it) with the correct Host headers, and - when PROD_EXTERNAL=1 - the
# same through the public names. Read-only; needs no secret and never disables certificate checks
# on the public path (the internal-CA hop on 8443 is the only --insecure use, loopback only).
#
#   ops/production/http-checks.sh             on the host, after a start
#   PROD_EXTERNAL=1 ops/production/http-checks.sh   from anywhere, after DNS exists (acceptance)
set -u
APP="${PROD_APP_HOST:-app.divalhr.com}"
PUB="${PROD_PUBLIC_HOST:-divalhr.com}"
REALM="divalhr-production"
FAIL=0
LISTENER="${PROD_LISTENER:-127.0.0.1:8443}"   # the restore drill points this at its own loopback port
pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s: %s\n' "$1" "$2"; FAIL=1; }
if [ "${PROD_EXTERNAL:-0}" = 1 ]; then
  CURL=(curl -sS --noproxy '*' --max-time 20)
  APP_URL="https://$APP"; PUB_URL="https://$PUB"
else
  # Loopback to the host listener; Caddy's certificate is from its internal CA (loopback only).
  CURL=(curl -sS --noproxy '*' --max-time 20 --insecure --connect-to "$APP:443:$LISTENER" --connect-to "$PUB:443:$LISTENER")
  APP_URL="https://$APP"; PUB_URL="https://$PUB"
fi
head_of() { "${CURL[@]}" -o /dev/null -D - "$1" 2>/dev/null | tr -d '\r'; }
status_of() { printf '%s\n' "$1" | awk 'NR==1 {print $2}'; }
header_of() { printf '%s\n' "$1" | awk -v n="$2" 'BEGIN{FS=": "} tolower($1)==n {sub(/^[^:]*: /,""); print}'; }

deadline=$(( $(date +%s) + ${PROD_READY_TIMEOUT:-180} ))
until "${CURL[@]}" -fo /dev/null "$APP_URL/api/v1/system/status" 2>/dev/null; do
  [ "$(date +%s)" -lt "$deadline" ] || { echo "FAIL $APP_URL did not answer in time"; exit 1; }
  sleep 5
done

# expect <name> <url> <status>
expect() {
  local h got; h=$(head_of "$2"); got=$(status_of "$h")
  [ "$got" = "$3" ] && pass "$1 ($got)" || fail "$1" "status $got, expected $3"
}
echo "== application $APP_URL"
expect "app shell" "$APP_URL/" 200
expect "runtime config" "$APP_URL/config.js" 200
expect "core status" "$APP_URL/api/v1/system/status" 200
expect "ai status" "$APP_URL/ai/api/v1/system/status" 200
expect "realm discovery" "$APP_URL/identity/realms/$REALM/.well-known/openid-configuration" 200
expect "realm keys" "$APP_URL/identity/realms/$REALM/protocol/openid-connect/certs" 200
h=$(head_of "$APP_URL/"); r=$(header_of "$h" x-robots-tag)
printf '%s' "$r" | grep -qi noindex && pass "application is noindex" || fail "application noindex" "X-Robots-Tag '$r'"
hsts=$(header_of "$h" strict-transport-security)
[ "$hsts" = "max-age=31536000" ] && pass "HSTS one year, no subdomains" || fail "HSTS" "'$hsts'"
# The runtime config is production, never test, and names no development host.
cfg=$("${CURL[@]}" "$APP_URL/config.js")
printf '%s' "$cfg" | grep -q "environment: 'production'" && pass "runtime config is production" || fail "runtime config" "environment is not production"
printf '%s' "$cfg" | grep -qiE 'hr-dev|dival\.ai|mailpit|localhost' && fail "runtime config" "names a development host" || pass "runtime config names no development host"
discovery=$("${CURL[@]}" "$APP_URL/identity/realms/$REALM/.well-known/openid-configuration")
iss=$(printf '%s' "$discovery" | python3 -c "import json,sys
try: print(json.load(sys.stdin).get('issuer',''))
except ValueError: print('')")
[ "$iss" = "$APP_URL/identity/realms/$REALM" ] && pass "OIDC issuer is the public production issuer" || fail "OIDC issuer" "'$iss'"

echo "== never public"
for p in /identity/admin/ /identity/admin/master/console/ /identity/realms/master/.well-known/openid-configuration \
  /identity/realms/master/protocol/openid-connect/token /identity/realms/$REALM/account \
  /identity/health /identity/health/ready /identity/metrics /actuator/health /api/actuator/health \
  /ai/docs /ai/api/v1/internal/operational-summary; do
  expect "denied $p" "$APP_URL$p" 404
done

echo "== commercial site $PUB_URL"
expect "commercial home" "$PUB_URL/" 200
expect "privacy notice" "$PUB_URL/legal/privacy" 200
expect "terms" "$PUB_URL/legal/terms" 200
h=$(head_of "$PUB_URL/api/v1/system/status")
[ "$(status_of "$h")" = 308 ] && [ "$(header_of "$h" location)" = "$APP_URL/api/v1/system/status" ] \
  && pass "non-commercial path on the public name goes to the application (308)" \
  || fail "public-origin redirect" "$(status_of "$h") $(header_of "$h" location)"
expect "no sign-in endpoint on the public name" "$PUB_URL/identity/realms/$REALM/protocol/openid-connect/certs" 308

echo "== load balancer health"
if [ "${PROD_EXTERNAL:-0}" != 1 ]; then
  st=$(curl -sS --noproxy '*' --insecure --max-time 10 -o /dev/null -w '%{http_code}' https://$LISTENER/healthz)
  [ "$st" = 200 ] && pass "/healthz answers on the listener" || fail "/healthz" "status $st"
fi
[ "$FAIL" = 0 ] && echo "ALL HTTP CHECKS PASSED" || echo "HTTP CHECKS FAILED"
exit "$FAIL"
