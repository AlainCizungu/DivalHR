#!/usr/bin/env python3
"""LAUNCH-002: read-only proof that the synthetic acceptance tenant's first administrator is disabled.

    acceptance-cleanup-check.py <admin base url> <secrets dir> <realm> <address>

Run on the host through accept.sh --finalize, against the operator site on loopback, with the
permanent operator from <secrets>/ops/kc-admin.env. Passes only when exactly one realm user has
that exact e-mail address and it is DISABLED. Prints a verdict only: never an address, id, token
or password. Writes nothing.
"""
import importlib.util
import os
import sys
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("kc", os.path.join(HERE, "..", "hr-dev", "keycloak_admin_setup.py"))
kc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kc)


def main(base, secrets_dir, realm, address):
    if "@" not in address or "+acc-admin-" not in address:
        print("FAIL the address is not a synthetic acceptance administrator")
        return 1
    values = kc.read_env(os.path.join(secrets_dir, "ops", "kc-admin.env"))
    token = kc.token_for_retrying(base, values.get("KC_ADMIN_USER", ""), values.get("KC_ADMIN_PASSWORD", ""))
    if not token:
        print("FAIL the operator cannot sign in to the realm console")
        return 1
    status, users, _ = kc.call(
        "GET", f"{base}/admin/realms/{realm}/users?exact=true&email={urllib.parse.quote(address)}", token
    )
    if status != 200 or not isinstance(users, list):
        print(f"FAIL the user lookup answered HTTP {status}")
        return 1
    if len(users) != 1:
        print(f"FAIL {len(users)} users match the synthetic administrator, exactly 1 is required")
        return 1
    if users[0].get("enabled") is not False:
        print("FAIL the synthetic first administrator is still ENABLED: disable it in the realm console")
        return 1
    print("PASS the synthetic first administrator exists and is disabled")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:5]) if len(sys.argv) == 5 else 2)
