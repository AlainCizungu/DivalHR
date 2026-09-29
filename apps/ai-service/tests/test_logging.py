import json

import pytest
from fastapi.testclient import TestClient

from divalhr_ai.logging import REDACTED, redact_sensitive


def test_redacts_sensitive_keys_recursively() -> None:
    event = {
        "event": "x",
        "Authorization": "Bearer abc.def.ghi",
        "nested": {"password": "p", "ok": 1, "items": [{"token": "t"}]},
    }
    result = redact_sensitive(None, "info", event)
    assert result["Authorization"] == REDACTED
    assert result["nested"]["password"] == REDACTED
    assert result["nested"]["ok"] == 1
    assert result["nested"]["items"][0]["token"] == REDACTED


def test_access_log_is_json_and_never_contains_credentials(
    client: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    secret = "eyJhbGciOiJSUzI1NiJ9.super-secret-token.signature"
    client.get(
        "/api/v1/system/status?access_token=" + secret,
        headers={"Authorization": "Bearer " + secret, "X-Correlation-Id": "log-test-0001"},
    )
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines() if line.strip()]
    access = [line for line in lines if line.get("message") == "http_request"]
    assert access, "expected an access log line"
    assert access[-1]["correlation_id"] == "log-test-0001"
    assert access[-1]["path"] == "/api/v1/system/status"
    assert all(secret not in json.dumps(line) for line in lines)
