#!/usr/bin/env bash
# SEC-001: read-only check that the logs of one Compose project (every service: Caddy, web,
# Keycloak, Core, AI service, PostgreSQL, Mailpit) hold no secret and no OIDC authorization data.
#
#   sudo ops/hr-dev/log-hygiene.sh --since <RFC 3339 UTC time> [--project <name>]
#
# Run after real sign-ins (the rehearsal's and the drill's browser suites through
# rehearsal-browser.sh; the live environment after a deployment's acceptance). It looks for:
#   - the secret values themselves, read from $HR_DEV_DATA/secrets (database passwords, keys, the
#     provisioner secret, Keycloak's and the operator's passwords and, live, the synthetic
#     acceptance accounts' passwords and authenticator keys);
#   - token, cookie, code and credential shapes: JWTs, bearer credentials, Keycloak session cookie
#     values, OIDC query parameters (code, state, session_state, tokens, verifiers) and
#     password or secret assignments.
# It prints counts per container only, never a matched value or a log line. Exit 1 on any match.
set -u
. "$(dirname "$0")/lib.sh"
SINCE="" PROJECT="$HR_DEV_PROJECT"
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift 2 ;;
    --project) PROJECT="$2"; shift 2 ;;
    *) hr_die "unknown argument $1" ;;
  esac
done
hr_require_root
case "$SINCE" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) ;; *) hr_die "--since <YYYY-MM-DDTHH:MM:SSZ>" ;; esac

W=$(mktemp -d) || hr_die "mktemp failed"
chmod 700 "$W"
trap 'rm -rf "$W"' EXIT

# The secret values, one per line, from the root-only secrets directory (never printed).
S="$HR_DEV_DATA/secrets"
if [ -d "$S" ]; then
  # Not secrets: identifiers kept beside the accounts (organization.txt).
  find "$S" -type f ! -name '*.txt' -print0 | while IFS= read -r -d '' f; do
    if grep -q '=' "$f"; then
      # KEY=VALUE files (keycloak.conf, kc-admin.env, acceptance accounts): secret-named keys only.
      grep -iE '^[A-Za-z0-9_.-]*(password|secret|key)[A-Za-z0-9_.-]*=' "$f" | cut -d= -f2- | sed -e "s/^['\"]//" -e "s/['\"]$//"
    else
      head -1 "$f"
    fi
  done | awk 'length($0) >= 8' | sort -u > "$W/needles"
else
  : > "$W/needles"
fi
needles=$(wc -l < "$W/needles")
[ -d "$S" ] && [ "$needles" = 0 ] && hr_die "no secret values could be read from $S"

SHAPES='eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}|[Bb]earer [A-Za-z0-9._~+/=-]{16,}|KEYCLOAK_(IDENTITY|SESSION)[A-Z_]*=[^;[:space:]"]{8,}|[?&](code|state|session_state|code_verifier|id_token_hint|id_token|access_token|refresh_token)=[^&[:space:]"]{6,}|(password|secret|totpSecret)=[^&[:space:]",]{6,}'

total=0 bad=0
containers=$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}}' | sort)
[ -n "$containers" ] || hr_die "no containers of project $PROJECT"
for c in $containers; do
  docker logs --since "$SINCE" "$c" > "$W/log" 2>&1 || hr_die "cannot read the logs of $c"
  lines=$(wc -l < "$W/log")
  values=0
  [ "$needles" -gt 0 ] && values=$(grep -cF -f "$W/needles" "$W/log")
  shapes=$(grep -cE "$SHAPES" "$W/log")
  total=$((total + lines))
  [ "$values" = 0 ] && [ "$shapes" = 0 ] || bad=1
  printf '%s: %s lines, secret values %s, token/cookie/code/credential shapes %s\n' "$c" "$lines" "$values" "$shapes"
  : > "$W/log"
done
echo "log hygiene of $PROJECT since $SINCE: $total lines, $needles secret values looked for"
[ "$bad" = 0 ] || { echo "FAIL secrets or authorization data in the logs (values not shown)"; exit 1; }
echo "PASS no secret value, token, cookie, code or credential in the logs"
