#!/usr/bin/env bash
# LAUNCH-002: host side of accept.sh --finalize. Read-only: proves the synthetic first administrator
# of the acceptance tenant is disabled in the production realm.
#   sudo ops/production/acceptance-cleanup-check.sh <synthetic administrator address>
set -u
. "$(dirname "$0")/lib.sh"
prod_require_root
ADDRESS="${1:?synthetic administrator address}"
case "$ADDRESS" in *[!A-Za-z0-9.@+_-]*) prod_die "the address has unexpected characters" ;; esac
exec python3 "$PROD_OPS/acceptance-cleanup-check.py" "http://localhost:$PROD_ADMIN_PORT/identity" "$PROD_DATA/secrets" "$PROD_REALM" "$ADDRESS"
