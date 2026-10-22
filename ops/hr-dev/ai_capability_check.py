"""R157-1 (MVP-101): the Core -> AI service capability of the test environment, end to end.

Runs inside the AI service container on the private app network (it has Python and no secret of
its own), so it sees the network path the Core API uses:

    python -c "$(cat ai_capability_check.py)" <internal base> <public origin> < ai-service-client-secret

Checks: the divalhr-core-ai client-credentials token is issued with the public issuer, the
audience divalhr-ai-service, the authorized party divalhr-core-ai, exactly the one capability role
and a lifetime of at most 120 seconds; the internal operation refuses a request without a token;
with the token it answers a synthetic all-zero snapshot (no person, no real data) with the
deterministic briefing (generationMode FALLBACK). The secret arrives on stdin and is never printed.
"""

import base64
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

REALM = "divalhr-test"
OPERATION = "http://127.0.0.1:8090/api/v1/internal/operational-summary"

SNAPSHOT = {
    "schemaVersion": 1,
    "language": "en",
    "asOfInstant": "2026-01-05T09:00:00.000000Z",
    "scope": {"kind": "ORGANIZATION", "siteFilter": False, "legalEntityFilter": False},
    "thresholds": {
        "lateMinutes": 5,
        "notCheckedInGraceMinutes": 30,
        "leaveOverdueHours": 48,
        "timesheetOverdueHours": 72,
    },
    "shifts": {"scheduled": 0, "expected": 0, "notCheckedIn": 0, "present": 0, "completed": 0, "late": 0},
    "coverage": {"uncovered": 0},
    "approvals": {
        "leave": {"pending": 0, "overdue": 0},
        "timesheets": {"pending": 0, "overdue": 0},
    },
    "facts": [
        {
            "factId": "f1",
            "type": "SCHEDULED_SHIFTS",
            "value": 0,
            "thresholdValue": None,
            "severity": "NONE",
            "routeId": "SCHEDULES",
        }
    ],
}


def request(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:  # noqa: S310 (fixed internal URL)
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()


def claims_of(token):
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def main(base, origin):
    secret = sys.stdin.read().strip()
    failures = 0

    def check(name, ok, detail=""):
        nonlocal failures
        print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))
        failures += 0 if ok else 1

    realm = f"{base}/identity/realms/{REALM}"
    form = urllib.parse.urlencode(
        {"grant_type": "client_credentials", "client_id": "divalhr-core-ai", "client_secret": secret}
    ).encode()
    status, body = request(
        "POST", f"{realm}/protocol/openid-connect/token", form,
        {"Content-Type": "application/x-www-form-urlencoded"},
    )
    token = json.loads(body).get("access_token", "") if status == 200 else ""
    check("AI client token", bool(token), f"status {status}")
    if token:
        claims = claims_of(token)
        check("AI token issuer is public", claims.get("iss") == f"{origin}/identity/realms/{REALM}", claims.get("iss"))
        aud = claims.get("aud")
        aud = aud if isinstance(aud, list) else [aud]
        check("AI token audience", aud == ["divalhr-ai-service"], str(aud))
        check("AI token authorized party", claims.get("azp") == "divalhr-core-ai", str(claims.get("azp")))
        access = claims.get("resource_access") or {}
        check(
            "AI token holds exactly the one capability",
            list(access) == ["divalhr-ai-service"]
            and access["divalhr-ai-service"].get("roles") == ["generate-operational-summary"],
            str(sorted(access)),
        )
        check("AI token lifetime", 0 < claims.get("exp", 0) - claims.get("iat", 0) <= 120)
        headers = {"Content-Type": "application/json"}
        status, _ = request("POST", OPERATION, json.dumps(SNAPSHOT).encode(), headers)
        check("internal operation refuses no token", status == 401, f"status {status}")
        status, body = request(
            "POST", OPERATION, json.dumps(SNAPSHOT).encode(), {**headers, "Authorization": f"Bearer {token}"}
        )
        answer = json.loads(body) if status == 200 else {}
        check("internal operation answers the service token", status == 200, f"status {status}")
        check(
            "deterministic briefing, no external provider",
            answer.get("generationMode") == "FALLBACK" and answer.get("language") == "en",
            str(answer.get("generationMode")),
        )
    return failures


if __name__ == "__main__":
    sys.exit(1 if main(sys.argv[1], sys.argv[2]) else 0)
