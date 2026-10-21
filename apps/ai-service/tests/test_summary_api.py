import asyncio
import json
import time
from collections.abc import Iterator
from typing import Any

import httpx2
import pytest
from fastapi.testclient import TestClient

from divalhr_ai.auth import AuthDeniedError, ServiceTokenVerifier
from divalhr_ai.config import Settings
from divalhr_ai.main import create_app
from divalhr_ai.summary.contract import Plan, SummaryInput
from divalhr_ai.summary.gateway import FailureClass, PlannerFailureError
from tests.summary_support import (
    AUDIENCE,
    CAPABILITY,
    CLIENT_ID,
    INVALID,
    ISSUER,
    JWKS_URL,
    Keys,
    apply_patch,
    case,
    with_language,
)

PATH = "/api/v1/internal/operational-summary"
KEYS = Keys()


def verifier(keys: Keys = KEYS) -> ServiceTokenVerifier:
    return ServiceTokenVerifier(
        issuer=ISSUER,
        jwks_url=JWKS_URL,
        audience=AUDIENCE,
        client_id=CLIENT_ID,
        capability=CAPABILITY,
        client=keys.client(),
    )


class Failing:
    def __init__(self) -> None:
        self.calls = 0

    async def plan(self, snapshot: SummaryInput) -> Plan:
        self.calls += 1
        raise PlannerFailureError(FailureClass.TIMEOUT)


@pytest.fixture
def planner() -> Failing:
    return Failing()


@pytest.fixture
def api(planner: Failing) -> Iterator[TestClient]:
    app = create_app(Settings(environment="test"), model_planner=planner, verifier=verifier())
    with TestClient(app) as client:
        yield client


def auth(token: str | None = None, **claims: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {token or KEYS.token(**claims)}"}


def body(case_id: str = "one-not-checked-in", language: str = "en") -> bytes:
    return json.dumps(with_language(case(case_id)["input"], language)).encode()


@pytest.mark.parametrize("language", ["en", "fr"])
def test_success_falls_back_and_is_never_cached(api: TestClient, language: str) -> None:
    response = api.post(PATH, content=body(language=language), headers=auth())
    assert response.status_code == 200
    assert response.headers["cache-control"] == "private, no-store"
    document = response.json()
    assert document["language"] == language
    assert document["generationMode"] == "FALLBACK"
    assert len(document["limitations"]) == 3
    assert "model" not in json.dumps(document).lower()


def test_requests_without_credentials_are_refused_before_the_body_is_read(
    api: TestClient, planner: Failing
) -> None:
    for headers in ({}, {"Authorization": "Basic abc"}, {"Authorization": "Bearer "}):
        response = api.post(PATH, content=b"{not json", headers=headers)
        assert response.status_code == 401
        assert response.json() == {"code": "SERVICE_TOKEN_REQUIRED"}
        assert response.headers["www-authenticate"] == "Bearer"
    assert planner.calls == 0


@pytest.mark.parametrize(
    ("claims", "status", "code"),
    [
        ({"aud": "someone-else"}, 403, "SERVICE_CAPABILITY_DENIED"),
        ({"azp": "divalhr-web"}, 403, "SERVICE_CAPABILITY_DENIED"),
        ({"azp": None}, 403, "SERVICE_CAPABILITY_DENIED"),
        ({"resource_access": {AUDIENCE: {"roles": ["other"]}}}, 403, "SERVICE_CAPABILITY_DENIED"),
        ({"resource_access": {}}, 403, "SERVICE_CAPABILITY_DENIED"),
        ({"resource_access": "x"}, 403, "SERVICE_CAPABILITY_DENIED"),
        ({"exp": int(time.time()) - 600}, 401, "SERVICE_TOKEN_INVALID"),
        ({"iss": "https://evil.example.test"}, 401, "SERVICE_TOKEN_INVALID"),
        ({"iat": None}, 401, "SERVICE_TOKEN_INVALID"),
        ({"kid": "unknown"}, 401, "SERVICE_TOKEN_INVALID"),
        ({"key": Keys("test-key-1").private}, 401, "SERVICE_TOKEN_INVALID"),
    ],
)
def test_token_denials(api: TestClient, claims: dict[str, Any], status: int, code: str) -> None:
    response = api.post(PATH, content=body(), headers=auth(**claims))
    assert (response.status_code, response.json()) == (status, {"code": code})


def test_garbage_and_oversized_tokens(api: TestClient) -> None:
    for token in ("garbage", "a.b.c", "x" * 9000):
        assert api.post(PATH, content=body(), headers=auth(token)).status_code == 401


def test_hs256_tokens_are_refused() -> None:
    import jwt

    now = int(time.time())
    token = jwt.encode(
        {"iss": ISSUER, "aud": AUDIENCE, "iat": now, "exp": now + 60, "azp": CLIENT_ID},
        "k" * 64,
        algorithm="HS256",
        headers={"kid": "test-key-1"},
    )
    with pytest.raises(AuthDeniedError) as raised:
        asyncio.run(verifier().verify(token))
    assert raised.value.status == 401


def test_jwks_failure_fails_closed(planner: Failing) -> None:
    def down(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(500)

    broken = ServiceTokenVerifier(
        issuer=ISSUER,
        jwks_url=JWKS_URL,
        audience=AUDIENCE,
        client_id=CLIENT_ID,
        capability=CAPABILITY,
        client=httpx2.AsyncClient(transport=httpx2.MockTransport(down)),
    )
    app = create_app(Settings(environment="test"), model_planner=planner, verifier=broken)
    with TestClient(app) as client:
        response = client.post(PATH, content=body(), headers=auth())
    assert (response.status_code, response.json()) == (503, {"code": "SUMMARY_UNAVAILABLE"})


def test_unconfigured_authentication_fails_closed(planner: Failing) -> None:
    with TestClient(create_app(Settings(environment="test"), model_planner=planner)) as client:
        response = client.post(PATH, content=body(), headers=auth())
    assert response.status_code == 503


def test_jwks_is_cached_and_rotation_is_picked_up() -> None:
    calls: list[int] = []
    current = {"keys": KEYS.jwks["keys"]}

    def handler(request: httpx2.Request) -> httpx2.Response:
        calls.append(1)
        return httpx2.Response(200, json=current)

    instance = ServiceTokenVerifier(
        issuer=ISSUER,
        jwks_url=JWKS_URL,
        audience=AUDIENCE,
        client_id=CLIENT_ID,
        capability=CAPABILITY,
        client=httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    )
    asyncio.run(instance.verify(KEYS.token()))
    asyncio.run(instance.verify(KEYS.token()))
    assert len(calls) == 1
    other = Keys("rotated")
    with pytest.raises(AuthDeniedError):  # unknown kid right after a fetch: no refresh storm
        asyncio.run(instance.verify(other.token()))
    assert len(calls) == 1
    instance._fetched_at -= 60
    current["keys"] = [*KEYS.jwks["keys"], *other.jwks["keys"]]
    asyncio.run(instance.verify(other.token()))
    assert len(calls) == 2


def test_malformed_and_oversized_bodies_are_400(api: TestClient) -> None:
    for content in (b"", b"{", b"[]", b"x" * 40_000, json.dumps({"schemaVersion": 1}).encode()):
        response = api.post(PATH, content=content, headers=auth())
        assert response.status_code == 400
        assert response.json() == {"code": "SUMMARY_INPUT_INVALID"}


@pytest.mark.parametrize("entry", INVALID, ids=[e["id"] for e in INVALID])
def test_invalid_inputs_map_to_400_or_422_and_never_echo(
    api: TestClient, planner: Failing, entry: dict[str, Any]
) -> None:
    payload = apply_patch(case(entry["base"])["input"], entry["patch"])
    response = api.post(PATH, content=json.dumps(payload).encode(), headers=auth())
    expected = (
        (400, "SUMMARY_INPUT_INVALID")
        if entry["layer"] == "schema"
        else (422, "SUMMARY_SNAPSHOT_INVALID")
    )
    assert (response.status_code, response.json()) == (expected[0], {"code": expected[1]})
    assert "ignore previous" not in response.text
    assert planner.calls == 0


def test_the_internal_path_is_not_a_browser_endpoint(api: TestClient) -> None:
    response = api.options(
        PATH,
        headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST"},
    )
    assert "access-control-allow-origin" not in response.headers


def test_provider_and_prompt_details_never_appear_in_logs(
    capfd: pytest.CaptureFixture[str], planner: Failing
) -> None:
    app = create_app(Settings(environment="test"), model_planner=planner, verifier=verifier())
    token = KEYS.token()
    with TestClient(app) as client:
        client.post(PATH, content=body(), headers={"Authorization": f"Bearer {token}"})
    captured = capfd.readouterr()
    assert token not in captured.out + captured.err
