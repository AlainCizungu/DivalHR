#!/usr/bin/env bash
# LAUNCH-002: SEC-001 log hygiene of the PRODUCTION project (counts only, never a value or line).
#   sudo ops/production/log-hygiene.sh --since <RFC 3339 UTC time>
set -u
. "$(dirname "$0")/lib.sh"
exec "$PROD_OPS/../hr-dev/log-hygiene.sh" "$@"
