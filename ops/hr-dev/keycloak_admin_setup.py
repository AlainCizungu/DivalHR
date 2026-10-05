#!/usr/bin/env python3
"""OPS-001 (A65-2): turns Keycloak's one-time bootstrap administrator into the permanent operator.

    keycloak_admin_setup.py <admin base url> <secrets dir>

Run by keycloak-admin-setup.sh on the instance, through the operator site on the instance's
loopback (http://127.0.0.1:<admin port>/identity), never through the public origin. Idempotent:

1. Authenticates with the permanent operator from <secrets>/ops/kc-admin.env when it exists,
   otherwise with the bootstrap administrator whose password is in <secrets>/keycloak/keycloak.conf.
2. Creates the permanent operator `divalhr-operator` with a generated password and the master
   realm's `admin` role, written only to <secrets>/ops/kc-admin.env (root, 0600).
3. Deletes the temporary `bootstrap-admin` user.
4. Sets the master realm's frontendUrl to the admin URL (the console must not use the public
   origin, where the master realm and the console are refused).
5. Removes `bootstrap-admin-username` and `bootstrap-admin-password` from keycloak.conf (Keycloak
   refuses to start with a bootstrap username but no password).

Prints step names only: never a password, token or user identifier.
"""

import json
import os
import secrets
import string
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

OPERATOR = "divalhr-operator"
BOOTSTRAP = "bootstrap-admin"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fail(message: str) -> None:
    print(f"STOP: {message}", file=sys.stderr)
    sys.exit(1)


def call(method, url, token=None, body=None, form=None):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with OPENER.open(request, timeout=30) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None), response.headers
    except urllib.error.HTTPError as error:
        try:
            body = json.loads(error.read() or b"null")
        except ValueError:
            body = None
        return error.code, body, error.headers
    except (urllib.error.URLError, OSError) as error:
        return 0, {"error": "unreachable", "error_description": str(getattr(error, "reason", error))}, None


def token_for_retrying(base, username, password, attempts=20):
    """Keycloak answers 503 while it is still bootstrapping after a (re)start: retry for ~60 s."""
    for _ in range(attempts):
        token = token_for(base, username, password)
        if token:
            return token
        time.sleep(3)
    return None


def token_for(base, username, password):
    status, payload, _ = call(
        "POST",
        f"{base}/realms/master/protocol/openid-connect/token",
        form={"grant_type": "password", "client_id": "admin-cli", "username": username, "password": password},
    )
    if status != 200 or not payload or "access_token" not in payload:
        # OAuth error codes and descriptions only (never the request): e.g. invalid_grant.
        detail = payload if isinstance(payload, dict) else {}
        print(
            f"token request for {username}: HTTP {status} {detail.get('error', '')}"
            f" {detail.get('error_description', '')}".rstrip(),
            file=sys.stderr,
        )
        return None
    return payload.get("access_token")


def read_env(path):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if "=" in line:
                key, value = line.rstrip("\n").split("=", 1)
                values[key] = value
    return values


def conf_value(path, key):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith(f"{key}="):
                return line.rstrip("\n").split("=", 1)[1]
    return None


def write_private(path, content, uid=0, gid=0, mode=0o600):
    directory = os.path.dirname(path)
    fd, tmp = tempfile.mkstemp(dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(content)
        os.chmod(tmp, mode)
        os.chown(tmp, uid, gid)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def user_id(base, token, username):
    status, users, _ = call(
        "GET", f"{base}/admin/realms/master/users?exact=true&username={urllib.parse.quote(username)}", token
    )
    if status != 200:
        fail(f"user lookup returned {status}")
    return users[0]["id"] if users else None


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: keycloak_admin_setup.py <admin base url> <secrets dir>")
    base = sys.argv[1].rstrip("/")
    secrets_dir = sys.argv[2]
    env_file = os.path.join(secrets_dir, "ops", "kc-admin.env")
    conf_file = os.path.join(secrets_dir, "keycloak", "keycloak.conf")

    token = None
    if os.path.exists(env_file):
        values = read_env(env_file)
        token = token_for_retrying(base, values.get("KC_ADMIN_USER", ""), values.get("KC_ADMIN_PASSWORD", ""))
        if not token:
            fail("the permanent operator in kc-admin.env cannot sign in")
        print("operator: existing permanent operator signs in")
    else:
        bootstrap_password = conf_value(conf_file, "bootstrap-admin-password")
        if not bootstrap_password:
            fail("no kc-admin.env and no bootstrap-admin-password: recover through the runbook")
        # Keycloak may still be bootstrapping (503) just after it reports ready.
        token = token_for_retrying(base, BOOTSTRAP, bootstrap_password)
        if not token:
            fail("the bootstrap administrator cannot sign in")
        alphabet = string.ascii_letters + string.digits
        password = "".join(secrets.choice(alphabet) for _ in range(40))
        existing = user_id(base, token, OPERATOR)
        if existing is None:
            status, _, _ = call(
                "POST",
                f"{base}/admin/realms/master/users",
                token,
                body={"username": OPERATOR, "enabled": True, "firstName": "DivalHR", "lastName": "Operator"},
            )
            if status != 201:
                fail(f"creating the operator returned {status}")
            existing = user_id(base, token, OPERATOR)
        status, _, _ = call(
            "PUT",
            f"{base}/admin/realms/master/users/{existing}/reset-password",
            token,
            body={"type": "password", "value": password, "temporary": False},
        )
        if status != 204:
            fail(f"setting the operator password returned {status}")
        status, role, _ = call("GET", f"{base}/admin/realms/master/roles/admin", token)
        if status != 200:
            fail(f"reading the admin role returned {status}")
        status, _, _ = call(
            "POST", f"{base}/admin/realms/master/users/{existing}/role-mappings/realm", token, body=[role]
        )
        if status != 204:
            fail(f"granting the admin role returned {status}")
        write_private(env_file, f"KC_ADMIN_USER={OPERATOR}\nKC_ADMIN_PASSWORD={password}\n")
        token = token_for(base, OPERATOR, password)
        if not token:
            fail("the new operator cannot sign in")
        print("operator: permanent operator created (credentials in secrets/ops/kc-admin.env)")

    bootstrap = user_id(base, token, BOOTSTRAP)
    if bootstrap:
        status, _, _ = call("DELETE", f"{base}/admin/realms/master/users/{bootstrap}", token)
        if status != 204:
            fail(f"deleting the bootstrap administrator returned {status}")
        print("bootstrap administrator: deleted")
    else:
        print("bootstrap administrator: absent")

    # Last: changing frontendUrl changes the master realm's issuer, which ends this token.
    status, realm, _ = call("GET", f"{base}/admin/realms/master", token)
    if status != 200:
        fail(f"reading the master realm returned {status}")
    attributes = dict(realm.get("attributes") or {})
    if attributes.get("frontendUrl") != base:
        attributes["frontendUrl"] = base
        status, _, _ = call("PUT", f"{base}/admin/realms/master", token, body={"attributes": attributes})
        if status != 204:
            fail(f"setting the master frontendUrl returned {status}")
        print("master realm: frontendUrl set to the admin URL")
    else:
        print("master realm: frontendUrl already the admin URL")

    if conf_value(conf_file, "bootstrap-admin-password") is not None:
        stat = os.stat(conf_file)
        with open(conf_file, encoding="utf-8") as handle:
            kept = [line for line in handle if not line.startswith(("bootstrap-admin-password=", "bootstrap-admin-username="))]
        write_private(conf_file, "".join(kept), stat.st_uid, stat.st_gid, 0o400)
        print("keycloak.conf: bootstrap username and password removed")


if __name__ == "__main__":
    main()
