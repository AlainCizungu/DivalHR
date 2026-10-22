"""Service-to-service authentication of the internal operation (D101-5).

Validates a Keycloak client-credentials token BEFORE the request body is read: signature (RS256
only, keys from the realm JWKS), issuer, expiry, audience ``divalhr-ai-service``, the authorized
party (the dedicated Core AI client) and the exact capability role. A human token, a browser token,
a static key or a network location is never accepted. The bearer value is never logged.
"""

import asyncio
import json
import time
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

import httpx2
import jwt
import structlog
from jwt.algorithms import RSAAlgorithm

from divalhr_ai.config import Settings

log = structlog.get_logger("divalhr_ai.auth")

INTERNAL_PREFIX = "/api/v1/internal/"
JWKS_TTL_SECONDS = 300.0
JWKS_MIN_REFRESH_SECONDS = 30.0
MAX_TOKEN_LENGTH = 8192


class AuthDeniedError(Exception):
    """401 or 403 with a stable code and no detail."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


class AuthUnavailableError(Exception):
    """The verification keys cannot be obtained: fail closed."""


class ServiceTokenVerifier:
    """Verifies the Core AI client's access token."""

    def __init__(
        self,
        *,
        issuer: str,
        jwks_url: str,
        audience: str,
        client_id: str,
        capability: str,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self._issuer = issuer
        self._jwks_url = jwks_url
        self._audience = audience
        self._client_id = client_id
        self._capability = capability
        self._http = client or httpx2.AsyncClient(timeout=httpx2.Timeout(3.0))
        self._keys: dict[str, Any] = {}
        self._fetched_at = 0.0
        self._lock = asyncio.Lock()

    async def _refresh(self) -> None:
        try:
            response = await self._http.get(self._jwks_url)
            response.raise_for_status()
            document = response.json()
            keys = {
                item["kid"]: RSAAlgorithm.from_jwk(json.dumps(item))
                for item in document["keys"]
                if item.get("kty") == "RSA" and item.get("use", "sig") == "sig" and "kid" in item
            }
        except (httpx2.HTTPError, ValueError, KeyError, TypeError) as error:
            raise AuthUnavailableError from error
        self._keys = keys
        self._fetched_at = time.monotonic()

    async def _key_for(self, kid: str) -> Any:
        async with self._lock:
            age = time.monotonic() - self._fetched_at
            if (
                not self._keys
                or age > JWKS_TTL_SECONDS
                or (kid not in self._keys and age > JWKS_MIN_REFRESH_SECONDS)
            ):
                await self._refresh()
        key = self._keys.get(kid)
        if key is None:
            raise AuthDeniedError(401, "SERVICE_TOKEN_INVALID")
        return key

    async def verify(self, token: str) -> None:
        """Return normally only for the Core AI client holding the capability."""
        if not token or len(token) > MAX_TOKEN_LENGTH:
            raise AuthDeniedError(401, "SERVICE_TOKEN_INVALID")
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as error:
            raise AuthDeniedError(401, "SERVICE_TOKEN_INVALID") from error
        kid = header.get("kid")
        if header.get("alg") != "RS256" or not isinstance(kid, str):
            raise AuthDeniedError(401, "SERVICE_TOKEN_INVALID")
        key = await self._key_for(kid)
        try:
            claims = jwt.decode(
                token,
                key,
                algorithms=["RS256"],
                audience=self._audience,
                issuer=self._issuer,
                leeway=30,
                options={"require": ["exp", "iat", "iss", "aud"]},
            )
        except jwt.InvalidAudienceError as error:
            raise AuthDeniedError(403, "SERVICE_CAPABILITY_DENIED") from error
        except jwt.PyJWTError as error:
            raise AuthDeniedError(401, "SERVICE_TOKEN_INVALID") from error
        if claims.get("azp") != self._client_id:
            raise AuthDeniedError(403, "SERVICE_CAPABILITY_DENIED")
        access = claims.get("resource_access")
        roles = access.get(self._audience, {}).get("roles") if isinstance(access, dict) else None
        if not isinstance(roles, list) or self._capability not in roles:
            raise AuthDeniedError(403, "SERVICE_CAPABILITY_DENIED")


def build_verifier(settings: Settings) -> ServiceTokenVerifier | None:
    """The verifier, or ``None`` when service authentication is not configured (fail closed)."""
    if not settings.service_auth_issuer or not settings.service_auth_jwks_url:
        return None
    return ServiceTokenVerifier(
        issuer=settings.service_auth_issuer,
        jwks_url=settings.service_auth_jwks_url,
        audience=settings.service_auth_audience,
        client_id=settings.service_auth_client_id,
        capability=settings.service_auth_capability,
    )


Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[MutableMapping[str, Any]]]
Send = Callable[[MutableMapping[str, Any]], Awaitable[None]]


class ServiceAuthMiddleware:
    """Pure ASGI middleware: authenticates internal requests before anything reads the body."""

    def __init__(
        self, app: Callable[[Scope, Receive, Send], Awaitable[None]], verifier: Any
    ) -> None:
        self._app = app
        self._verifier = verifier  # a ServiceTokenVerifier, or a callable returning one or None

    def _current(self) -> ServiceTokenVerifier | None:
        return self._verifier() if callable(self._verifier) else self._verifier  # type: ignore[no-any-return]

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not str(scope["path"]).startswith(INTERNAL_PREFIX):
            await self._app(scope, receive, send)
            return
        verifier = self._current()
        try:
            if verifier is None:
                raise AuthUnavailableError
            token = ""
            for name, value in scope["headers"]:
                if name == b"authorization":
                    scheme, _, rest = value.decode("latin-1").partition(" ")
                    token = rest.strip() if scheme.lower() == "bearer" else ""
                    break
            if not token:
                raise AuthDeniedError(401, "SERVICE_TOKEN_REQUIRED")
            await verifier.verify(token)
        except AuthDeniedError as denied:
            await _reply(send, denied.status, denied.code)
            return
        except AuthUnavailableError:
            log.error("service_auth_unavailable", operation="verify-service-token")
            await _reply(send, 503, "SUMMARY_UNAVAILABLE")
            return
        await self._app(scope, receive, send)


async def _reply(send: Send, status: int, code: str) -> None:
    body = json.dumps({"code": code}).encode()
    headers = [
        (b"content-type", b"application/json"),
        (b"content-length", str(len(body)).encode()),
        (b"cache-control", b"private, no-store"),
    ]
    if status == 401:
        headers.append((b"www-authenticate", b"Bearer"))
    await send({"type": "http.response.start", "status": status, "headers": headers})
    await send({"type": "http.response.body", "body": body})
