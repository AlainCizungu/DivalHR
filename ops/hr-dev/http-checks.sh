#!/usr/bin/env bash
# OPS-001 (sections 8 and 11, A65-2, A65-4): HTTP-level acceptance checks of the test
# environment through its public entry point. Read-only; needs no secret.
#
#   ops/hr-dev/http-checks.sh
#
# Environment (defaults: the live environment, from the instance itself):
#   HR_DEV_HOST          public name (hr-dev.dival.ai)
#   HR_DEV_CONNECT_HTTPS optional "ip:port" to reach the HTTPS listener (rehearsals)
#   HR_DEV_CONNECT_HTTP  optional "ip:port" to reach the HTTP listener (rehearsals)
#   HR_DEV_CA_FILE       optional CA bundle (rehearsals with Caddy's internal CA); certificate
#                        and host-name validation are never disabled
#   HR_DEV_BARE_IP_URL   URL that reaches the HTTP listener by address (default http://127.0.0.1/)
set -u
HOST="${HR_DEV_HOST:-hr-dev.dival.ai}"
ORIGIN="https://$HOST"
REALM="divalhr-test"
NOINDEX="noindex, nofollow, noarchive"
FAIL=0

CURL=(curl -sS --noproxy '*' --max-time 20)
[ -n "${HR_DEV_CA_FILE:-}" ] && CURL+=(--cacert "$HR_DEV_CA_FILE")
[ -n "${HR_DEV_CONNECT_HTTPS:-}" ] && CURL+=(--connect-to "$HOST:443:$HR_DEV_CONNECT_HTTPS")
[ -n "${HR_DEV_CONNECT_HTTP:-}" ] && CURL+=(--connect-to "$HOST:80:$HR_DEV_CONNECT_HTTP")

pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s: %s\n' "$1" "$2"; FAIL=1; }

# head <url>: status line and headers, lower-cased names.
head_of() { "${CURL[@]}" -o /dev/null -D - "$1" 2>/dev/null | tr -d '\r'; }
status_of() { printf '%s\n' "$1" | awk 'NR==1 {print $2}'; }
header_of() { printf '%s\n' "$1" | awk -v n="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" \
  'BEGIN{FS=": "} tolower($1)==n {sub(/^[^:]*: /,""); print}'; }

# expect <name> <url> <status> : status, exactly one X-Robots-Tag with the exact value.
expect() {
  local name="$1" url="$2" want="$3" h got robots
  h=$(head_of "$url")
  got=$(status_of "$h")
  robots=$(header_of "$h" x-robots-tag)
  if [ "$got" != "$want" ]; then fail "$name" "status $got, expected $want"; return; fi
  if [ "$robots" != "$NOINDEX" ]; then fail "$name" "X-Robots-Tag is '$robots'"; return; fi
  pass "$name ($got, noindex)"
}

# HTTPS responses also carry exactly one one-day HSTS header without subdomains (D10).
expect_https() {
  local name="$1" path="$2" want="$3" h hsts
  expect "$name" "$ORIGIN$path" "$want"
  h=$(head_of "$ORIGIN$path")
  hsts=$(header_of "$h" strict-transport-security)
  [ "$hsts" = "max-age=86400" ] || fail "$name hsts" "Strict-Transport-Security is '$hsts'"
}

# A new stack may still be obtaining its certificate (ACME) or warming up: wait for one good HTTPS
# answer, up to HR_DEV_READY_TIMEOUT seconds (default 180), before judging anything.
deadline=$(( $(date +%s) + ${HR_DEV_READY_TIMEOUT:-180} ))
until "${CURL[@]}" -fo /dev/null "$ORIGIN/robots.txt" 2>/dev/null; do
  if [ "$(date +%s)" -ge "$deadline" ]; then echo "FAIL $ORIGIN did not answer over HTTPS in time"; exit 1; fi
  sleep 5
done

echo "== public site $ORIGIN"
expect_https "landing page" / 200
expect_https "runtime config" /config.js 200
expect_https "robots.txt" /robots.txt 200
expect_https "core status" /api/v1/system/status 200
expect_https "ai status" /ai/api/v1/system/status 200
expect_https "realm discovery" "/identity/realms/$REALM/.well-known/openid-configuration" 200
expect_https "realm keys" "/identity/realms/$REALM/protocol/openid-connect/certs" 200
expect_https "unknown keycloak path" "/identity/realms/$REALM/no-such-endpoint" 404

"${CURL[@]}" "$ORIGIN/robots.txt" | grep -qx 'Disallow: /' && pass "robots.txt disallows all" \
  || fail "robots.txt" "missing Disallow: /"
"${CURL[@]}" "$ORIGIN/config.js" | grep -q "environment: 'test'" && pass "runtime config is test" \
  || fail "runtime config" "environment is not test"
"${CURL[@]}" "$ORIGIN/config.js" | grep -q "$HOST" && pass "runtime config uses the configured origin" \
  || fail "runtime config" "origin missing"

echo "== OIDC (A65-2)"
discovery=$("${CURL[@]}" "$ORIGIN/identity/realms/$REALM/.well-known/openid-configuration")
for key in issuer:"$ORIGIN/identity/realms/$REALM" \
  jwks_uri:"$ORIGIN/identity/realms/$REALM/protocol/openid-connect/certs" \
  authorization_endpoint:"$ORIGIN/identity/realms/$REALM/protocol/openid-connect/auth" \
  token_endpoint:"$ORIGIN/identity/realms/$REALM/protocol/openid-connect/token" \
  end_session_endpoint:"$ORIGIN/identity/realms/$REALM/protocol/openid-connect/logout"; do
  name="${key%%:*}" want="${key#*:}"
  got=$(printf '%s' "$discovery" | python3 -c "import json,sys
try: print(json.load(sys.stdin).get('$name',''))
except ValueError: print('')")
  [ "$got" = "$want" ] && pass "discovery $name" || fail "discovery $name" "'$got'"
done
keys=$("${CURL[@]}" "$ORIGIN/identity/realms/$REALM/protocol/openid-connect/certs" \
  | python3 -c "import json,sys
try: print(len([k for k in json.load(sys.stdin)['keys'] if k.get('use')=='sig']))
except (ValueError, KeyError): print(0)")
[ "${keys:-0}" -ge 1 ] && pass "JWKS has $keys signing key(s)" || fail "JWKS" "no signing key"

# Login page and every asset it loads (A65-2 item 7).
auth="$ORIGIN/identity/realms/$REALM/protocol/openid-connect/auth?client_id=divalhr-web&response_type=code&scope=openid&redirect_uri=$ORIGIN/auth/callback&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuKcd6g8hcM&code_challenge_method=S256&state=httpcheck"
page=$("${CURL[@]}" "$auth")
printf '%s' "$page" | grep -q 'id="kc-form-login"' && pass "login page renders" || fail "login page" "no login form"
assets=$(printf '%s' "$page" | grep -oE '(href|src)="/identity/(resources|js)/[^"]+"' | sed -E 's/^(href|src)="//; s/"$//' | sort -u)
count=0
for a in $assets; do
  count=$((count + 1))
  st=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$ORIGIN$a")
  [ "$st" = 200 ] || fail "login asset" "$a returned $st"
done
[ "$count" -gt 0 ] && pass "login page loads $count asset(s) from /identity" || fail "login assets" "none found"
# The unregistered redirect URI is refused (exact allow-list).
bad=$("${CURL[@]}" "${auth/redirect_uri=$ORIGIN\/auth\/callback/redirect_uri=https://evil.example/cb}")
if ! printf '%s' "$bad" | grep -q 'id="kc-form-login"' && printf '%s' "$bad" | grep -qi 'redirect_uri'; then
  pass "unregistered redirect URI refused"
else
  fail "redirect allow-list" "evil redirect not refused"
fi

echo "== never public (A65-2 item 6)"
for p in /identity/admin/ /identity/admin/master/console/ /identity/admin/realms/$REALM/users \
  /identity/realms/master/.well-known/openid-configuration \
  /identity/realms/master/protocol/openid-connect/token \
  /identity/realms/$REALM/account /identity/realms/$REALM/account/ \
  /identity/realms/$REALM/divalhr-provisioning/v1/invitations/00000000-0000-4000-8000-000000000000/identity \
  /identity/health /identity/health/ready /identity/metrics /actuator/health /api/actuator/health \
  /ai/docs /ai/api/v1/other; do
  expect_https "denied $p" "$p" 404
done

echo "== HTTP listener"
h=$(head_of "http://$HOST/some/path?x=1")
[ "$(status_of "$h")" = 308 ] && [ "$(header_of "$h" location)" = "https://$HOST/some/path?x=1" ] \
  && [ "$(header_of "$h" x-robots-tag)" = "$NOINDEX" ] && pass "HTTP redirects to HTTPS (308, noindex)" \
  || fail "HTTP redirect" "$(status_of "$h") $(header_of "$h" location)"
bare="${HR_DEV_BARE_IP_URL:-http://127.0.0.1/}"
h=$(curl -sS --noproxy '*' --max-time 10 -o /dev/null -D - "$bare" 2>/dev/null | tr -d '\r')
[ "$(status_of "$h")" = 404 ] && [ "$(header_of "$h" x-robots-tag)" = "$NOINDEX" ] \
  && pass "bare address: guarded 404 (noindex)" || fail "bare address" "$(status_of "$h")"

[ "$FAIL" = 0 ] && echo "ALL HTTP CHECKS PASSED" || echo "HTTP CHECKS FAILED"
exit "$FAIL"
