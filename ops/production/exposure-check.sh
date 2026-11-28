#!/usr/bin/env bash
# LAUNCH-002 (ADR 0048, L2-3, L2-4): what the INTERNET can reach. Run from an operator workstation
# after DNS exists. Read-only except the optional --waf-probe. Prints PASS/FAIL lines only.
#
#   bash ops/production/exposure-check.sh [--waf-probe]
#
# Checks: only 80 (redirect only) and 443 answer on the application and commercial names; none of
# SSH, PostgreSQL, Keycloak, Core, AI, the internal back-channel, the host listener or the operator
# port is reachable; HTTP redirects to HTTPS; the certificate validates and has more than 21 days
# left; TLS 1.0/1.1 are refused; HSTS is present. --waf-probe sends ONE benign classic injection
# pattern and expects the managed WAF baseline to block it (403).
set -u
APP="${PROD_APP_HOST:-app.divalhr.com}"
PUB="${PROD_PUBLIC_HOST:-divalhr.com}"
FAIL=0
pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s: %s\n' "$1" "$2"; FAIL=1; }
open_port() { (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; }
WITH_WAF=0; [ "${1:-}" = "--waf-probe" ] && WITH_WAF=1

for host in "$APP" "$PUB"; do
  for p in 22 25 3306 5432 5173 8025 8080 8081 8090 8180 8443 9000 18180; do
    if timeout 4 bash -c "exec 3<>/dev/tcp/$host/$p" 2>/dev/null; then fail "$host:$p" "reachable from the internet"; else pass "$host:$p not reachable"; fi
  done
  open_port "$host" 443 && pass "$host:443 answers" || fail "$host:443" "does not answer"
  h=$(curl -sS --max-time 15 -o /dev/null -D - "http://$host/some/path?x=1" | tr -d '\r')
  st=$(printf '%s\n' "$h" | awk 'NR==1 {print $2}'); loc=$(printf '%s\n' "$h" | awk 'tolower($1)=="location:" {print $2}')
  case "$st" in 301|308) [ "$loc" = "https://$host/some/path?x=1" ] && pass "$host: HTTP redirects to HTTPS ($st)" || fail "$host HTTP redirect" "Location '$loc'" ;; *) fail "$host HTTP redirect" "status $st" ;; esac
  # The certificate validates for the name and is not close to expiry.
  end=$(echo | openssl s_client -connect "$host:443" -servername "$host" -verify_return_error 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  if [ -z "$end" ]; then fail "$host certificate" "does not validate"; else
    left=$(( ( $(date -d "$end" +%s 2>/dev/null || date -j -f '%b %e %T %Y %Z' "$end" +%s) - $(date +%s) ) / 86400 ))
    [ "$left" -gt 21 ] && pass "$host certificate valid, $left days left" || fail "$host certificate" "$left days left"
  fi
  for v in tls1 tls1_1; do
    if echo | openssl s_client -connect "$host:443" -servername "$host" "-$v" >/dev/null 2>&1; then fail "$host $v" "accepted"; else pass "$host $v refused (or unsupported by this client)"; fi
  done
  hsts=$(curl -sS --max-time 15 -o /dev/null -D - "https://$host/" | tr -d '\r' | awk 'tolower($1)=="strict-transport-security:" {print $2}')
  [ -n "$hsts" ] && pass "$host sends HSTS" || fail "$host HSTS" "missing"
done
if [ "$WITH_WAF" = 1 ]; then
  st=$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' "https://$APP/api/v1/public/x?q=1%27%20OR%20%271%27%3D%271")
  [ "$st" = 403 ] && pass "WAF managed baseline blocks a classic injection probe (403)" || fail "WAF probe" "status $st, expected 403"
fi
[ "$FAIL" = 0 ] && echo "ALL EXPOSURE CHECKS PASSED" || echo "EXPOSURE CHECKS FAILED"
exit "$FAIL"
