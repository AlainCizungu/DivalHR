#!/bin/sh
# Writes the public runtime configuration consumed by /config.js. Values are URLs only; the
# script refuses characters that could break out of the JavaScript string literals.
set -eu

for name in DIVALHR_ENVIRONMENT DIVALHR_CORE_API_URL DIVALHR_AI_SERVICE_URL DIVALHR_OIDC_AUTHORITY DIVALHR_OIDC_CLIENT_ID; do
  eval "value=\${$name:?$name is required}"
  case "$value" in
    *\"*|*\'*|*\\*|*\<*|*\>*|*' '*) echo "Refusing unsafe value for $name" >&2; exit 1 ;;
  esac
done

mkdir -p /tmp/divalhr
cat > /tmp/divalhr/config.js <<CONFIG
window.__DIVALHR_CONFIG__ = {
  environment: '${DIVALHR_ENVIRONMENT}',
  coreApiUrl: '${DIVALHR_CORE_API_URL}',
  aiServiceUrl: '${DIVALHR_AI_SERVICE_URL}',
  oidcAuthority: '${DIVALHR_OIDC_AUTHORITY}',
  oidcClientId: '${DIVALHR_OIDC_CLIENT_ID}',
};
CONFIG
echo "divalhr: runtime config written for environment ${DIVALHR_ENVIRONMENT}"
