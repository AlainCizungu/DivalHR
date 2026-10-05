"""OPS-001 (A65-2 items 1 and 3): the Core API's private backchannel to Keycloak.

Runs inside a container on the private app network (the AI service container, which has Python
and no secret of its own), so it sees exactly the network path the Core API uses:

    python -c "$(cat backchannel_check.py)" <internal base> <public origin> < provisioner-secret

Checks: the keys endpoint answers through the internal route; the provisioner's
client-credentials token is issued with the public issuer; one provisioning REST operation is
authorised; a path outside the internal allow-list is refused; Keycloak itself is not reachable
from this network. The secret arrives on stdin and is never printed.
"""

import json
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request

REALM = "divalhr-test"


def request(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310 (fixed internal URL)
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()


def main(base, origin):
    secret = sys.stdin.read().strip()
    failures = 0

    def check(name, ok, detail=""):
        nonlocal failures
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))
        failures += 0 if ok else 1

    realm = f"{base}/identity/realms/{REALM}"
    status, body = request("GET", f"{realm}/protocol/openid-connect/certs")
    keys = json.loads(body).get("keys", []) if status == 200 else []
    check("internal JWKS", status == 200 and len(keys) > 0, f"status {status}")

    form = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": "divalhr-core-provisioner",
        "client_secret": secret,
    }).encode()
    status, body = request("POST", f"{realm}/protocol/openid-connect/token", form,
                           {"Content-Type": "application/x-www-form-urlencoded"})
    token = json.loads(body).get("access_token", "") if status == 200 else ""
    check("provisioner token", bool(token), f"status {status}")
    if token:
        payload = token.split(".")[1]
        claims = json.loads(__import__("base64").urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        check("token issuer is public", claims.get("iss") == f"{origin}/identity/realms/{REALM}",
              claims.get("iss"))
        aud = claims.get("aud")
        aud = aud if isinstance(aud, list) else [aud]
        check("token audience", "divalhr-provisioning" in aud, str(aud))
        probe = "00000000-0000-4000-8000-0000000000f1"
        status, _ = request("DELETE", f"{realm}/divalhr-provisioning/v1/invitations/{probe}/identity",
                            headers={"Authorization": f"Bearer {token}"})
        check("provisioning operation authorised", status in (204, 404), f"status {status}")
        status, _ = request("DELETE", f"{realm}/divalhr-provisioning/v1/invitations/{probe}/identity")
        check("provisioning refuses no token", status in (401, 403), f"status {status}")

    status, _ = request("GET", f"{realm}/.well-known/openid-configuration")
    check("internal route allow-list", status == 404, f"status {status}")
    status, _ = request("GET", f"{base}/identity/admin/master/console/")
    check("no admin on internal route", status == 404, f"status {status}")

    try:
        socket.create_connection(("keycloak", 8080), timeout=3).close()
        direct = True
    except OSError:
        direct = False
    check("Keycloak not directly reachable from the app network", not direct)
    return failures


if __name__ == "__main__":
    sys.exit(1 if main(sys.argv[1], sys.argv[2]) else 0)
