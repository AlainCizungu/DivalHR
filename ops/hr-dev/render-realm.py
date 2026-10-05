#!/usr/bin/env python3
"""OPS-001: renders the test realm for Keycloak's first-start import.

    render-realm.py <template> <output> <origin> <provisioner-secret-file>

Replaces exactly two placeholders, ${HR_DEV_ORIGIN} and ${DIVALHR_KEYCLOAK_PROVISIONER_SECRET};
Keycloak's own message keys such as ${username} are left alone. The secret is read from a file
and never printed. The output must contain no remaining DivalHR placeholder, no user other than
the provisioner's service account, no credential and no development value.
"""

import json
import os
import sys


def main(template: str, output: str, origin: str, secret_file: str) -> None:
    if not origin.startswith("https://") or origin.endswith("/"):
        sys.exit("origin must be https://<host> without a trailing slash")
    with open(secret_file, encoding="utf-8") as handle:
        secret = handle.read().strip()
    if len(secret) < 32:
        sys.exit("provisioner secret is too short")
    with open(template, encoding="utf-8") as handle:
        text = handle.read()
    text = text.replace("${HR_DEV_ORIGIN}", origin)
    text = text.replace("${DIVALHR_KEYCLOAK_PROVISIONER_SECRET}", secret)
    if "${HR_DEV_" in text or "${DIVALHR_" in text or "dev-only" in text.lower():
        sys.exit("unresolved placeholder or development value in the rendered realm")
    realm = json.loads(text)
    if realm.get("realm") != "divalhr-test" or realm.get("sslRequired") != "all":
        sys.exit("unexpected realm or sslRequired")
    for user in realm.get("users", []):
        if not user["username"].startswith("service-account-") or user.get("credentials"):
            sys.exit("the test realm must not contain users or credentials")
    tmp = output + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o400)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(text)
    os.replace(tmp, output)
    print(f"rendered {output}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
