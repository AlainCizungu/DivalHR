"""Shared fixtures for the MVP-101 tests: the evaluation set, patches and signed service tokens."""

import copy
import json
import time
from pathlib import Path
from typing import Any

import httpx2
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt.algorithms import RSAAlgorithm

EVAL_DIR = (
    Path(__file__).resolve().parents[3]
    / "packages/shared-contracts/evaluation/operational-summary/v1"
)
ISSUER = "https://hr-dev.example.test/identity/realms/divalhr-test"
JWKS_URL = "http://keycloak.internal/realms/divalhr-test/protocol/openid-connect/certs"
AUDIENCE = "divalhr-ai-service"
CLIENT_ID = "divalhr-core-ai"
CAPABILITY = "generate-operational-summary"


def fixture(name: str) -> Any:
    return json.loads((EVAL_DIR / name).read_text(encoding="utf-8"))


CASES: list[dict[str, Any]] = fixture("cases.json")["cases"]
INVALID: list[dict[str, Any]] = fixture("invalid-inputs.json")["entries"]
PLANS: list[dict[str, Any]] = fixture("plans.json")["entries"]
VOCABULARY: dict[str, list[str]] = fixture("prohibited-vocabulary.json")


def case(case_id: str) -> dict[str, Any]:
    return next(c for c in CASES if c["id"] == case_id)


def with_language(payload: dict[str, Any], language: str) -> dict[str, Any]:
    return {**copy.deepcopy(payload), "language": language}


def apply_patch(payload: dict[str, Any], patch: list[dict[str, Any]]) -> dict[str, Any]:
    result = copy.deepcopy(payload)
    for step in patch:
        parts = step["path"].split("/")[1:]
        last = parts.pop()
        node: Any = result
        for part in parts:
            node = node[int(part)] if isinstance(node, list) else node[part]
        if step["op"] == "remove":
            if isinstance(node, list):
                del node[int(last)]
            else:
                del node[last]
        elif isinstance(node, list):
            node[int(last)] = step["value"]
        else:
            node[last] = step["value"]
    return result


class Keys:
    """A throwaway RSA key pair, its JWKS document and a token signer."""

    def __init__(self, kid: str = "test-key-1") -> None:
        self.kid = kid
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        jwk = json.loads(RSAAlgorithm.to_jwk(self.private.public_key()))
        jwk.update({"kid": kid, "use": "sig", "alg": "RS256"})
        self.jwks = {"keys": [jwk]}

    def token(self, **overrides: Any) -> str:
        """A signed token; an override of ``None`` removes the claim."""
        now = int(time.time())
        claims: dict[str, Any] = {
            "iss": ISSUER,
            "aud": AUDIENCE,
            "azp": CLIENT_ID,
            "iat": now,
            "exp": now + 300,
            "resource_access": {AUDIENCE: {"roles": [CAPABILITY]}},
        }
        kid = overrides.pop("kid", self.kid)
        signer = overrides.pop("key", self.private)
        for name, value in overrides.items():
            if value is None:
                claims.pop(name, None)
            else:
                claims[name] = value
        return jwt.encode(claims, signer, algorithm="RS256", headers={"kid": kid})

    def client(self) -> httpx2.AsyncClient:
        def handler(request: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(200, json=self.jwks)

        return httpx2.AsyncClient(transport=httpx2.MockTransport(handler))
