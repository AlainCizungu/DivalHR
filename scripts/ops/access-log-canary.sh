#!/usr/bin/env bash
# SEC-001: runtime test that OIDC authorization data never reaches an access log.
#
#   bash scripts/ops/access-log-canary.sh            # CI: the pinned Caddy and web nginx images
#   bash scripts/ops/access-log-canary.sh --local    # caddy and nginx binaries on PATH
#
# Unique random canaries are placed where authorization data travels: an /auth/callback query
# (code, state, session_state), a Referer header, a redirect Location header (Keycloak's redirect
# to the callback, proxied by Caddy), a Cookie, an Authorization header and a Set-Cookie header.
# The committed configuration must keep every canary out of the logs while the method, the path
# and the status stay visible. Then each required redaction is removed in turn (a mutation): the
# canary it protects must appear, which proves the check would catch that regression.
# Caddy runs the committed (accesslog) snippet verbatim in front of a local upstream; nginx runs
# the committed template (rendered by the image's own entrypoint in CI). Only counts are printed.
set -u -o pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODE=docker
[ "${1:-}" = --local ] && MODE=local
CADDY_IMAGE=$(sed -n 's/^ *image: \(caddy:[^ ]*\)$/\1/p' "$ROOT/infrastructure/hr-dev/compose.yaml" | head -1)
NGINX_IMAGE=$(sed -n 's/^FROM \(nginxinc\/[^ ]*\).*$/\1/p' "$ROOT/apps/web/Dockerfile" | head -1)
W=$(mktemp -d)
[ -n "${CANARY_KEEP:-}" ] && echo "work dir: $W"
chmod 755 "$W"
FAILED=0
STARTED=()
cleanup() {
  for c in "${STARTED[@]}"; do
    if [ "$MODE" = docker ]; then docker rm -f "$c" >/dev/null 2>&1; else kill "$c" 2>/dev/null; fi
  done
  rm -rf "$W"
}
[ -n "${CANARY_KEEP:-}" ] || trap cleanup EXIT
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; FAILED=1; }
canary() { printf 'sec001%s%s' "$1" "$(od -An -N10 -tx1 /dev/urandom | tr -d ' \n')"; }
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])'; }
wait_http() { # <url>
  for _ in $(seq 1 100); do curl -s -o /dev/null "$1" && return 0; sleep 0.2; done
  return 1
}

QCODE=$(canary code) QSTATE=$(canary state) QSESSION=$(canary session) REFERER=$(canary referer)
LOCATION=$(canary location) COOKIE=$(canary cookie) AUTHZ=$(canary authorization) SETCOOKIE=$(canary setcookie)
ALL=("$QCODE" "$QSTATE" "$QSESSION" "$REFERER" "$LOCATION" "$COOKIE" "$AUTHZ" "$SETCOOKIE")

requests() { # <base url>: the browser's view of a sign-in, with every canary in place
  local base="$1"
  curl -s -o /dev/null -H "Referer: https://hr-dev.example.test/landing?code=$REFERER" \
    -H "Cookie: KEYCLOAK_IDENTITY=$COOKIE" -H "Authorization: Bearer $AUTHZ" \
    "$base/identity/realms/divalhr-test/protocol/openid-connect/auth?client_id=divalhr-web&state=$QSTATE"
  curl -s -o /dev/null -H "Referer: https://hr-dev.example.test/identity/realms/x?session_code=$REFERER" \
    "$base/auth/callback?state=$QSTATE&session_state=$QSESSION&code=$QCODE"
}
leaks() { # <log file>: how many canaries appear (never which values)
  local n=0 c
  for c in "${ALL[@]}"; do grep -qF "$c" "$1" && n=$((n + 1)); done
  echo "$n"
}
leaked() { grep -qF "$2" "$1"; } # <log file> <canary>

# --- Caddy ---------------------------------------------------------------------------------------
snippet() { # the committed (accesslog) snippet, verbatim
  awk '/^\(accesslog\) \{$/ {on=1} on {print} on && /^\}$/ {exit}' "$ROOT/infrastructure/hr-dev/caddy/Caddyfile"
}
caddy_config() { # <snippet file> <front port> <upstream port>
  cat <<EOF
{
	admin off
	auto_https off
	persist_config off
}
$(cat "$1")

# Stand-in for Keycloak: the redirect to the callback carries the code; a session cookie is set.
http://:$3 {
	header Set-Cookie "KEYCLOAK_SESSION=$SETCOOKIE; Path=/"
	@auth path /identity/*
	redir @auth "https://hr-dev.example.test/auth/callback?state=$QSTATE&session_state=$QSESSION&code=$LOCATION" 302
	respond "app" 200
}

# The public site's logging, in front of the stand-in.
http://:$2 {
	import accesslog
	reverse_proxy 127.0.0.1:$3
}
EOF
}
run_caddy() { # <snippet file> <log file>
  local cfg="$W/Caddyfile.$RANDOM" port
  if [ "$MODE" = docker ]; then
    caddy_config "$1" 8080 8081 > "$cfg"; chmod 644 "$cfg"
    local id
    id=$(docker run -d -p 127.0.0.1::8080 -v "$cfg:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" \
      caddy run --config /etc/caddy/Caddyfile --adapter caddyfile) || return 1
    STARTED+=("$id")
    port=$(docker port "$id" 8080/tcp | head -1 | sed 's/.*://')
    wait_http "http://127.0.0.1:$port/" || return 1
    requests "http://127.0.0.1:$port"
    sleep 1
    docker logs "$id" > "$2" 2>&1
    docker rm -f "$id" >/dev/null
  else
    port=$(free_port)
    caddy_config "$1" "$port" "$(free_port)" > "$cfg"
    : > "$2"
    caddy run --config "$cfg" --adapter caddyfile >> "$2" 2>&1 &
    local pid=$!
    STARTED+=("$pid")
    wait_http "http://127.0.0.1:$port/" || return 1
    requests "http://127.0.0.1:$port"
    sleep 1
    kill "$pid"; wait "$pid" 2>/dev/null
  fi
}

snippet > "$W/accesslog"
grep -q 'resp_headers>Location delete' "$W/accesslog" || { echo "FAIL the (accesslog) snippet was not found"; exit 1; }
if run_caddy "$W/accesslog" "$W/caddy.log"; then
  n=$(leaks "$W/caddy.log")
  [ "$n" = 0 ] && pass "Caddy: none of ${#ALL[@]} canaries in the access log" || fail "Caddy: $n canaries in the access log"
  grep -q '"uri":"/identity/realms/divalhr-test/protocol/openid-connect/auth"' "$W/caddy.log" \
    && grep -q '"uri":"/auth/callback"' "$W/caddy.log" && grep -q '"method":"GET"' "$W/caddy.log" \
    && grep -q '"status":302' "$W/caddy.log" \
    && pass "Caddy: method, path without query and status are still logged" \
    || fail "Caddy: the safe fields are missing from the access log"
  # The deleted fields are absent altogether (Caddy itself would only write REDACTED for some).
  present=$(grep -oE '"(Referer|Location|Cookie|Set-Cookie|Authorization)":' "$W/caddy.log" | sort -u | tr -d '":' | tr '\n' ' ')
  [ -z "$present" ] && pass "Caddy: no Referer, Location, Cookie, Set-Cookie or Authorization field is logged" \
    || fail "Caddy: logged fields that must be deleted: $present"
else
  fail "Caddy did not start"
fi

# Mutations: each redaction removed in turn must let its canary through.
caddy_mutation() { # <name> <pattern of the removed line> <canary or field marker that must appear>
  grep -v -- "$2" "$W/accesslog" > "$W/mutated"
  if cmp -s "$W/mutated" "$W/accesslog"; then fail "mutation $1: nothing removed"; return; fi
  if run_caddy "$W/mutated" "$W/mutated.log" && leaked "$W/mutated.log" "$3"; then
    pass "mutation $1: the check catches the leak"
  else
    fail "mutation $1: the leak went unnoticed"
  fi
}
caddy_mutation 'Caddy query kept' 'request>uri regexp' "$QCODE"
caddy_mutation 'Caddy Referer kept' 'request>headers>Referer delete' "$REFERER"
caddy_mutation 'Caddy Location kept' 'resp_headers>Location delete' "$LOCATION"
# Caddy writes REDACTED for these three by default, so their values cannot leak even then; the
# deletion is still required, and its removal makes the deleted-field check above fail.
caddy_mutation 'Caddy Cookie kept' 'request>headers>Cookie delete' '"Cookie":'
caddy_mutation 'Caddy Authorization kept' 'request>headers>Authorization delete' '"Authorization":'
caddy_mutation 'Caddy Set-Cookie kept' 'resp_headers>Set-Cookie delete' '"Set-Cookie":'

# --- web nginx -----------------------------------------------------------------------------------
mkdir -p "$W/html" && echo '<!doctype html><title>app</title>' > "$W/html/index.html"
chmod -R a+rX "$W/html"
render_local() { # <template> -> a complete nginx.conf for a local nginx (image defaults mirrored)
  local port="$2" tmpdir="$W/ngx.$RANDOM"
  mkdir -p "$tmpdir" && chmod 777 "$tmpdir"
  sed -e 's#\${DIVALHR_CSP_CONNECT_SRC}#http://core.example.test#' -e 's#\${DIVALHR_OIDC_ORIGIN}#https://idp.example.test#' \
    -e "s#/usr/share/nginx/html#$W/html#" -e "s#listen 8080;#listen 127.0.0.1:$port;#" "$1" > "$tmpdir/site.conf"
  cat <<EOF
worker_processes 1;
$( [ "$(id -u)" = 0 ] && echo 'user root;' )
pid $tmpdir/nginx.pid;
error_log stderr notice;
daemon off;
events {}
http {
  client_body_temp_path $tmpdir; proxy_temp_path $tmpdir; fastcgi_temp_path $tmpdir;
  uwsgi_temp_path $tmpdir; scgi_temp_path $tmpdir;
  # As in the image's nginx.conf: the default format and log, which the template must override.
  log_format main '\$remote_addr - \$remote_user [\$time_local] "\$request" '
                  '\$status \$body_bytes_sent "\$http_referer" "\$http_user_agent" "\$http_x_forwarded_for"';
  access_log /dev/stdout main;
  include $tmpdir/site.conf;
}
EOF
}
run_nginx() { # <template> <log file>
  local port
  if [ "$MODE" = docker ]; then
    chmod 644 "$1"
    docker run --rm -e DIVALHR_CSP_CONNECT_SRC=http://core.example.test -e DIVALHR_OIDC_ORIGIN=https://idp.example.test \
      -v "$1:/etc/nginx/templates/default.conf.template:ro" "$NGINX_IMAGE" nginx -t > "$W/nginx-t.log" 2>&1 \
      || { cat "$W/nginx-t.log"; return 1; }
    local id
    id=$(docker run -d -p 127.0.0.1::8080 -e DIVALHR_CSP_CONNECT_SRC=http://core.example.test \
      -e DIVALHR_OIDC_ORIGIN=https://idp.example.test -v "$1:/etc/nginx/templates/default.conf.template:ro" \
      -v "$W/html:/usr/share/nginx/html:ro" "$NGINX_IMAGE") || return 1
    STARTED+=("$id")
    port=$(docker port "$id" 8080/tcp | head -1 | sed 's/.*://')
    wait_http "http://127.0.0.1:$port/healthz" || return 1
    requests "http://127.0.0.1:$port"
    sleep 1
    docker logs "$id" > "$2" 2>&1
    docker rm -f "$id" >/dev/null
  else
    port=$(free_port)
    render_local "$1" "$port" > "$W/nginx.conf.$port"
    nginx -t -c "$W/nginx.conf.$port" > "$W/nginx-t.log" 2>&1 || { cat "$W/nginx-t.log"; return 1; }
    : > "$2"
    # Append mode: nginx reopens /dev/stdout itself; a truncating redirect would let the two
    # writers overwrite each other in the file.
    nginx -c "$W/nginx.conf.$port" >> "$2" 2>&1 &
    local pid=$!
    STARTED+=("$pid")
    wait_http "http://127.0.0.1:$port/healthz" || return 1
    requests "http://127.0.0.1:$port"
    sleep 1
    kill "$pid"; wait "$pid" 2>/dev/null
  fi
}

cp "$ROOT/apps/web/docker/default.conf.template" "$W/template"
if run_nginx "$W/template" "$W/nginx.log"; then
  pass "nginx: the generated configuration passes nginx -t"
  n=$(leaks "$W/nginx.log")
  [ "$n" = 0 ] && pass "nginx: none of ${#ALL[@]} canaries in the access log" || fail "nginx: $n canaries in the access log"
  grep -q '"GET /auth/callback HTTP/1.1" 200 ' "$W/nginx.log" \
    && grep -q '"GET /identity/realms/divalhr-test/protocol/openid-connect/auth HTTP/1.1" 200 ' "$W/nginx.log" \
    && pass "nginx: method, path without query and status are still logged" \
    || fail "nginx: the safe fields are missing from the access log"
else
  fail "nginx: the generated configuration does not start (nginx -t)"
fi

nginx_mutation() { # <name> <sed expression> <canary that must leak>
  sed -e "$2" "$W/template" > "$W/template.mutated"
  if cmp -s "$W/template.mutated" "$W/template"; then fail "mutation $1: nothing changed"; return; fi
  if run_nginx "$W/template.mutated" "$W/mutated.log" && leaked "$W/mutated.log" "$3"; then
    pass "mutation $1: the check catches the leak"
  else
    fail "mutation $1: the leak went unnoticed"
  fi
}
nginx_mutation 'nginx explicit access_log removed (image default format)' '/access_log \/dev\/stdout divalhr_safe;/d' "$QCODE"
nginx_mutation 'nginx request line logged' 's/\$request_method \$divalhr_path \$server_protocol/$request/' "$QCODE"
nginx_mutation 'nginx Referer logged' "s/\\\$request_time'/\$request_time \"\$http_referer\"'/" "$REFERER"
nginx_mutation 'nginx Cookie logged' "s/\\\$request_time'/\$request_time \"\$http_cookie\"'/" "$COOKIE"
nginx_mutation 'nginx uninitialized-variable warning on (error log)' '/uninitialized_variable_warn off;/d' "$QCODE"
nginx_mutation 'nginx Authorization logged' "s/\\\$request_time'/\$request_time \"\$http_authorization\"'/" "$AUTHZ"

if [ "$FAILED" = 0 ]; then echo "ALL PASS (SEC-001 access-log canaries, $MODE)"; else echo "SOME CHECKS FAILED"; fi
exit "$FAILED"
