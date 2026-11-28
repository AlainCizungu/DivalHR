#!/usr/bin/env python3
"""LAUNCH-002 (ADR 0048, L2-6): renders the PRODUCTION realm for Keycloak's first-start import.

    render-realm.py <hr-dev template> <output> <application origin> <provisioner-secret-file> [<mail.env>]

The production realm is derived from the verified realm boundary (the test realm template: privileged
MFA, narrow provisioning, password-level re-entry for employees only), never from live hr-dev data. It
is renamed `divalhr-production`, takes only the application origin for its redirect URI and web
origin, carries the provider's non-secret e-mail settings (no password: the operator stores it in the
identity provider afterwards) and is checked in final production mode: no development value, no
http:// address, no localhost, no test host, no user except the provisioner's service account, no
credential. The secret is read from a file and never printed.
"""

import json
import os
import re
import sys

REALM = "divalhr-production"
FORBIDDEN = re.compile(
    r"localhost|127\.0\.0\.1|example\.test|\.test\b|mailpit|hr-dev|dival\.ai|dev-only|"
    r"divalhr-test|divalhr-dev|\$\{HR_DEV_|\$\{DIVALHR_",
    re.IGNORECASE,
)
MAIL_KEYS = {"PROD_MAIL_HOST", "PROD_MAIL_PORT", "PROD_MAIL_FROM", "PROD_MAIL_USERNAME"}


def read_env(path: str) -> dict:
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key in MAIL_KEYS:
                values[key] = value.strip()
    return values


def render(template: str, origin: str, secret: str, mail: dict) -> str:
    if not re.fullmatch(r"https://[a-z0-9.-]+", origin):
        sys.exit("origin must be https://<host> without a path or trailing slash")
    if len(secret) < 32:
        sys.exit("provisioner secret is too short")
    text = open(template, encoding="utf-8").read()
    text = text.replace("${HR_DEV_ORIGIN}", origin)
    text = text.replace("${DIVALHR_KEYCLOAK_PROVISIONER_SECRET}", secret)
    realm = json.loads(text)
    if realm.get("realm") != "divalhr-test":
        sys.exit("the template is not the verified test realm")
    realm["realm"] = REALM
    realm["displayName"] = "DivalHR"
    realm["registrationAllowed"] = False
    realm.pop("smtpServer", None)
    if mail.get("PROD_MAIL_HOST") and mail.get("PROD_MAIL_FROM"):
        smtp = {
            "host": mail["PROD_MAIL_HOST"],
            "port": mail.get("PROD_MAIL_PORT") or "587",
            "from": mail["PROD_MAIL_FROM"],
            "fromDisplayName": "DivalHR",
            "ssl": "false",
            "starttls": "true",
            "auth": "true" if mail.get("PROD_MAIL_USERNAME") else "false",
        }
        if mail.get("PROD_MAIL_USERNAME"):
            smtp["user"] = mail["PROD_MAIL_USERNAME"]
        realm["smtpServer"] = smtp
    return json.dumps(realm, indent=2, ensure_ascii=False)


def verify(rendered: str, origin: str, secret: str) -> None:
    """Final production mode: the rendered realm must hold exactly the production boundary."""
    realm = json.loads(rendered)
    scan = rendered.replace(secret, "")
    smtp = json.dumps(realm.get("smtpServer", {}))
    for needle in FORBIDDEN.findall(scan.replace(smtp, "")):
        sys.exit(f"development or test value in the production realm: {needle!r}")
    if realm.get("realm") != REALM or realm.get("sslRequired") != "all":
        sys.exit("unexpected realm name or sslRequired")
    if realm.get("registrationAllowed") or not realm.get("bruteForceProtected"):
        sys.exit("self-registration must be off and brute-force protection on")
    for user in realm.get("users", []):
        if not user["username"].startswith("service-account-") or user.get("credentials"):
            sys.exit("the production realm must contain no user except service accounts, and no credential")
    web = [c for c in realm["clients"] if c["clientId"] == "divalhr-web"]
    if len(web) != 1 or web[0]["redirectUris"] != [f"{origin}/auth/callback"] or web[0]["webOrigins"] != [origin]:
        sys.exit("the web client must have exactly the application origin as redirect URI and web origin")
    for client in realm["clients"]:
        for uri in client.get("redirectUris") or []:
            if not uri.startswith("https://"):
                sys.exit("every redirect URI must be https")
    if "divalhr-privileged-mfa" not in {r["name"] for r in realm["roles"]["realm"]}:
        sys.exit("the privileged MFA marker role is missing")
    for flow in ("divalhr browser", "divalhr browser level 2 otp"):
        if flow not in {f["alias"] for f in realm["authenticationFlows"]}:
            sys.exit("the privileged MFA flows are missing")


def main(template: str, output: str, origin: str, secret_file: str, mail_env: str = "") -> None:
    with open(secret_file, encoding="utf-8") as handle:
        secret = handle.read().strip()
    mail = read_env(mail_env) if mail_env and os.path.exists(mail_env) else {}
    rendered = render(template, origin, secret, mail)
    verify(rendered, origin, secret)
    tmp = output + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o400)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(rendered)
    os.replace(tmp, output)
    print(f"rendered {output}")


if __name__ == "__main__":
    if len(sys.argv) not in (5, 6):
        sys.exit(__doc__)
    main(*sys.argv[1:])
